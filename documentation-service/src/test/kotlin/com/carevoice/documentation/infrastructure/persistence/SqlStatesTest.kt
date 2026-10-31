package com.carevoice.documentation.infrastructure.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/** Which database errors become 503 + Retry-After (the client may retry) instead of 500. */
@DisplayName("SqlStates.isUnavailable")
class SqlStatesTest {
    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @ParameterizedTest(name = "should treat SQLState {0} as an unreachable database")
        @ValueSource(strings = ["08001", "08006", "08003", "57P01", "57P02", "57P03"])
        fun `should treat connection and shutdown states as unavailable`(state: String) {
            assertThat(SqlStates.isUnavailable(SQLException("down", state))).isTrue()
        }

        @Test
        fun `should treat the pool timing out as unavailable, also when wrapped`() {
            val poolTimeout = SQLTransientConnectionException("Connection is not available, request timed out")

            assertThat(SqlStates.isUnavailable(RuntimeException("wrapped", poolTimeout))).isTrue()
        }

        @ParameterizedTest(name = "should keep SQLState {0} a statement error")
        @ValueSource(strings = ["23505", "42P01", "22001", "40001"])
        fun `should not hide statement errors behind a 503`(state: String) {
            assertThat(SqlStates.isUnavailable(SQLException("bad statement", state))).isFalse()
        }
    }
}
