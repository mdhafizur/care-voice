package com.carevoice.documentation.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

/**
 * A [DataSource] that EXPLAINs every query the code under test sends, right before running it: on the same connection,
 * in the same transaction, with the same bind values. Plan tests therefore assert on the SQL the repositories really
 * build (no copied query strings that drift), and a change that makes a query lose its index fails the build.
 *
 * Plain `EXPLAIN` (no ANALYZE) only plans the statement, so explaining an UPDATE or a `FOR UPDATE` claim changes and
 * locks nothing. The statement itself then runs unchanged.
 */
class ExplainingDataSource(
    private val delegate: DataSource,
    private val explainWhen: (String) -> Boolean = ::isQuery,
) : DataSource by delegate {
    private val captured = CopyOnWriteArrayList<QueryPlan>()

    /** Plans captured since the last [clear], in execution order. */
    val plans: List<QueryPlan> get() = captured.toList()

    fun clear() = captured.clear()

    /** The single captured plan whose SQL matches [predicate]; fails with every captured SQL when there is none. */
    fun planOf(description: String, predicate: (String) -> Boolean): QueryPlan {
        val matching = plans.filter { predicate(it.sql) }
        check(matching.size == 1) {
            "expected exactly one $description query, found ${matching.size} among:\n" +
                plans.joinToString("\n") { "  " + it.sql.oneLine() }
        }
        return matching.single()
    }

    override fun getConnection(): Connection = explaining(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        explaining(delegate.getConnection(username, password))

    private fun explaining(connection: Connection): Connection = proxy { method, args ->
        val result = method.invokeOn(connection, args)
        if (method.name == "prepareStatement" && result is PreparedStatement && explainWhen(args!![0] as String)) {
            explaining(connection, args[0] as String, result)
        } else {
            result
        }
    }

    /** Records every `setXxx(index, value, ...)` call; on execution, replays them on an EXPLAIN of the same SQL. */
    private fun explaining(connection: Connection, sql: String, statement: PreparedStatement): PreparedStatement {
        val binds = sortedMapOf<Int, Pair<Method, Array<out Any?>>>()
        return proxy { method, args ->
            if (method.name.startsWith("set") && args != null && args.size >= 2 && args[0] is Int) {
                binds[args[0] as Int] = method to args
            }
            if (method.name in EXECUTE_METHODS && args.isNullOrEmpty()) {
                captured += explain(connection, sql, binds.values)
            }
            method.invokeOn(statement, args)
        }
    }

    private fun explain(connection: Connection, sql: String, binds: Collection<Pair<Method, Array<out Any?>>>) =
        connection.prepareStatement("EXPLAIN (FORMAT JSON) $sql").use { explain ->
            binds.forEach { (method, args) -> method.invokeOn(explain, args) }
            explain.executeQuery().use { rows ->
                rows.next()
                QueryPlan(sql, Json.parseToJsonElement(rows.getString(1)).jsonArray)
            }
        }

    private companion object {
        val EXECUTE_METHODS = setOf("executeQuery", "executeUpdate", "execute")

        fun isQuery(sql: String): Boolean = sql.trimStart().let {
            it.startsWith("SELECT", ignoreCase = true) || it.startsWith("WITH", ignoreCase = true)
        }

        /** A [T] whose every call goes to [handler] (which forwards it to the real object). */
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T : Any> proxy(crossinline handler: (Method, Array<out Any?>?) -> Any?): T {
            val loader = ExplainingDataSource::class.java.classLoader
            val proxy = Proxy.newProxyInstance(loader, arrayOf(T::class.java)) { _, method, args ->
                handler(method, args)
            }
            return proxy as T
        }

        fun Method.invokeOn(target: Any, args: Array<out Any?>?): Any? = try {
            invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}

/** One `EXPLAIN (FORMAT JSON)` result: the plan tree flattened into its nodes, outermost first. */
class QueryPlan(val sql: String, json: JsonArray) {
    private val root: JsonObject = json.first().jsonObject.getValue("Plan").jsonObject

    val nodes: List<JsonObject> = flatten(root)

    /** Node types in the plan, e.g. `Limit`, `Index Scan`, `Sort`. */
    val nodeTypes: List<String> = nodes.map { it.text("Node Type")!! }

    /** Every index the plan reads. */
    val indexes: Set<String> = nodes.mapNotNull { it.text("Index Name") }.toSet()

    /** The `Index Cond` of every node that reads [index]. */
    fun indexConditions(index: String): List<String> =
        nodes.filter { it.text("Index Name") == index }.mapNotNull { it.text("Index Cond") }

    /** Readable plan tree for assertion messages. */
    override fun toString(): String = buildString {
        appendLine(sql.oneLine())
        render(root, 0)
    }

    private fun StringBuilder.render(node: JsonObject, depth: Int) {
        append("  ".repeat(depth + 1)).append(node.text("Node Type"))
        node.text("Index Name")?.let { append(" using ").append(it) }
        node.text("Relation Name")?.let { append(" on ").append(it) }
        node.text("Index Cond")?.let { append("  [").append(it).append(']') }
        appendLine()
        node["Plans"]?.jsonArray?.forEach { render(it.jsonObject, depth + 1) }
    }

    private companion object {
        fun flatten(node: JsonObject): List<JsonObject> =
            listOf(node) + (node["Plans"]?.jsonArray?.flatMap { flatten(it.jsonObject) } ?: emptyList())

        fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content
    }
}

private fun String.oneLine(): String = trim().replace(Regex("\\s+"), " ")
