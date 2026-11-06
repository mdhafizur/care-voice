package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.support.TestDatabase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/**
 * A read whose connection breaks is retried once on a fresh connection; everything that is not a lost connection,
 * and every read inside a transaction, fails as before (503 for an unavailable database).
 */
@DisplayName("Read retry on a lost connection")
class ReadRetryTest {
    /** Fails the first [failures] attempts with [error], then runs `SELECT 1`. */
    private class FlakyRead(private val failures: Int, private val error: () -> SQLException) : (Connection) -> Int {
        var attempts = 0

        override fun invoke(connection: Connection): Int {
            attempts++
            if (attempts <= failures) throw error()
            return connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    @ParameterizedTest(name = "SQLState {0}")
    @ValueSource(strings = ["08006", "08003", "57P01", "57P03"])
    fun `should retry a read once when its connection was lost`(sqlState: String) {
        val read = FlakyRead(1) { SQLException("connection lost", sqlState) }

        assertThat(runBlocking { withReadConnection(TestDatabase.dataSource, read) }).isEqualTo(1)
        assertThat(read.attempts).isEqualTo(2)
    }

    @Test
    fun `should retry only once and then answer 503`() {
        val read = FlakyRead(2) { SQLException("connection lost", "08006") }

        assertThatThrownBy { runBlocking { withReadConnection(TestDatabase.dataSource, read) } }
            .isInstanceOf(ApplicationError.ServiceUnavailable::class.java)
        assertThat(read.attempts).isEqualTo(2)
    }

    @Test
    fun `should not retry a statement timeout`() {
        val read = FlakyRead(1) { SQLException("canceling statement due to statement timeout", "57014") }

        assertThatThrownBy { runBlocking { withReadConnection(TestDatabase.dataSource, read) } }
            .isInstanceOf(ApplicationError.ServiceUnavailable::class.java)
        assertThat(read.attempts).isEqualTo(1)
    }

    @Test
    fun `should not retry when the pool had no connection in time`() {
        val timeout = SQLTransientConnectionException("Connection is not available", "08001")

        assertThat(SqlStates.isConnectionLost(timeout)).isFalse()
        assertThat(SqlStates.isConnectionLost(SQLException("wrapped", "XX000", SQLException("lost", "08006")))).isTrue()
        assertThat(SqlStates.isConnectionLost(SQLException("duplicate key", SqlStates.UNIQUE_VIOLATION))).isFalse()
    }

    @Test
    fun `should not retry inside a transaction`() {
        val read = FlakyRead(1) { SQLException("connection lost", "08006") }

        assertThatThrownBy {
            runBlocking {
                JdbcTransactions(TestDatabase.dataSource).inTransaction {
                    withReadConnection(TestDatabase.dataSource, read)
                }
            }
        }.isInstanceOf(ApplicationError.ServiceUnavailable::class.java)
        assertThat(read.attempts).isEqualTo(1)
    }
}
