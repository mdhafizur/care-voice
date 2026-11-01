package com.carevoice.documentation.infrastructure.persistence

import com.carevoice.documentation.application.ApplicationError
import com.carevoice.documentation.support.TestDatabase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Migration V4: the database bounds every statement of the application role, because cancelling a request cannot
 * stop a blocking JDBC call. A statement over the limit becomes 503 + Retry-After, not a hung request.
 */
@DisplayName("Statement time limits (migration V4)")
class StatementTimeLimitsTest {
    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should give every new session of the application role a statement timeout`() {
            assertThat(TestDatabase.query("SHOW statement_timeout").single().values.single()).isEqualTo("5s")
        }

        @Test
        fun `should end sessions that stall inside a transaction`() {
            assertThat(TestDatabase.query("SHOW idle_in_transaction_session_timeout").single().values.single())
                .isEqualTo("15s")
        }

        @Test
        fun `should answer a statement that hits the timeout with 503 instead of hanging`() {
            val transactions = JdbcTransactions(TestDatabase.dataSource)

            // SET LOCAL lowers the limit for this transaction only, so the test needs 0.2 s, not 5 s.
            assertThatThrownBy {
                runBlocking {
                    transactions.inTransaction {
                        JdbcTransactions.currentConnection().createStatement().use { statement ->
                            statement.execute("SET LOCAL statement_timeout = '200ms'")
                            statement.execute("SELECT pg_sleep(1)")
                        }
                    }
                }
            }.isInstanceOf(ApplicationError.ServiceUnavailable::class.java)
        }
    }
}
