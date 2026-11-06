package com.carevoice.alert

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("replay-dead-letters command line")
class ReplayCommandTest {
    @Test
    fun `should replay everything without a limit and at most n with one`() {
        assertThat(replayLimit(listOf("replay-dead-letters"))).isEqualTo(Int.MAX_VALUE)
        assertThat(replayLimit(listOf("replay-dead-letters", "25"))).isEqualTo(25)
    }

    @Test
    fun `should reject other arguments`() {
        assertThat(replayLimit(listOf("replay-dead-letters", "0"))).isNull()
        assertThat(replayLimit(listOf("replay-dead-letters", "all"))).isNull()
        assertThat(replayLimit(listOf("migrate"))).isNull()
        assertThat(replayLimit(emptyList())).isNull()
    }
}
