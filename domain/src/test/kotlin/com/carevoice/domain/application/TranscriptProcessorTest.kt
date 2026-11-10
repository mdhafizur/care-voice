package com.carevoice.domain.application

import arrow.core.left
import arrow.core.right
import com.carevoice.domain.assertRight
import com.carevoice.domain.extraction.AiVitalExtractor
import com.carevoice.domain.extraction.RawVitalCandidate
import com.carevoice.domain.extraction.RejectedCandidate
import com.carevoice.domain.extraction.VitalExtractor
import com.carevoice.domain.model.BlankInput
import com.carevoice.domain.model.BloodPressure
import com.carevoice.domain.model.BodyTemperature
import com.carevoice.domain.model.Confidence
import com.carevoice.domain.model.ExtractionSource
import com.carevoice.domain.model.InconsistentValues
import com.carevoice.domain.model.Observation
import com.carevoice.domain.model.ObservationId
import com.carevoice.domain.model.ObservationStatus
import com.carevoice.domain.model.OutOfRange
import com.carevoice.domain.model.Pulse
import com.carevoice.domain.model.ResidentId
import com.carevoice.domain.model.TooLong
import com.carevoice.domain.model.VitalSign
import com.carevoice.domain.model.VitalType
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@DisplayName("TranscriptProcessor")
class TranscriptProcessorTest {
    private val now = Instant.parse("2026-10-25T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val resident = ResidentId.of("res-1").assertRight()

    /** Ids 1, 2, 3, ... in call order, so expected observations can be written out in full. */
    private fun sequentialIds(): () -> ObservationId {
        var next = 0L
        return { ObservationId(UUID(0, ++next)) }
    }

    private fun id(n: Long) = ObservationId(UUID(0, n))

    /** A hand-written fake instead of a mocking library: records what it was asked, returns a canned answer. */
    private class FakeAi(private val answer: List<RawVitalCandidate>) : AiVitalExtractor {
        val receivedTexts = mutableListOf<String>()

        override suspend fun extract(normalized: String): List<RawVitalCandidate> {
            receivedTexts += normalized
            return answer
        }
    }

    private fun processor(ai: AiVitalExtractor? = null, rules: VitalExtractor? = null) = if (rules == null) {
        TranscriptProcessor(aiFallback = ai, clock = clock, newId = sequentialIds())
    } else {
        TranscriptProcessor(rules = rules, aiFallback = ai, clock = clock, newId = sequentialIds())
    }

    private fun observation(
        n: Long,
        vital: VitalSign,
        source: ExtractionSource = ExtractionSource.RULES,
        confidence: Double = 0.95,
        status: ObservationStatus = ObservationStatus.CONFIRMED,
    ) = Observation(id(n), resident, vital, now, source, Confidence(confidence), status)

    /** The result of "pulse 80" with the default rules: one confirmed observation, nothing rejected, no AI. */
    private val pulse80Confirmed =
        ProcessingResult(
            observations = listOf(observation(1, Pulse.of(80).assertRight())),
            rejected = emptyList(),
            usedAiFallback = false,
        ).right()

    @Nested
    @DisplayName("Request Validation")
    inner class RequestValidation {
        @ParameterizedTest(name = "should reject blank transcript \"{0}\"")
        @FieldSource("com.carevoice.testsupport.InvalidValues#emptyStrings")
        fun `should reject a blank transcript`(transcript: String) = runTest {
            assertThat(processor().process(resident, transcript)).isEqualTo(BlankInput("transcript").left())
        }

        @ParameterizedTest(name = "should reject a transcript of {0} characters")
        @FieldSource("com.carevoice.domain.TranscriptLengthValues#tooLong")
        fun `should reject a transcript longer than the limit`(length: Int) = runTest {
            assertThat(processor().process(resident, "a".repeat(length)))
                .isEqualTo(TooLong("transcript", TranscriptProcessor.MAX_TRANSCRIPT_LENGTH).left())
        }

        @ParameterizedTest(name = "should accept a transcript of {0} characters")
        @FieldSource("com.carevoice.domain.TranscriptLengthValues#accepted")
        fun `should accept a transcript up to the limit`(length: Int) = runTest {
            val transcript = "pulse 80" + " ".repeat(length - "pulse 80".length)

            assertThat(processor().process(resident, transcript)).isEqualTo(pulse80Confirmed)
        }

        @Test
        fun `should not ask the AI about invalid input`() = runTest {
            val ai = FakeAi(listOf(RawVitalCandidate(VitalType.PULSE, 80.0)))

            processor(ai).process(resident, " ")

            assertThat(ai.receivedTexts).isEmpty()
        }
    }

    @Nested
    @DisplayName("Business Logic")
    inner class BusinessLogic {
        @Test
        fun `should turn a spoken transcript into confirmed observations with one timestamp and sequential ids`() =
            runTest {
                val transcript = "Mrs. Smith, blood pressure 120 over 75, pulse 80, temperature 37 point 2 degrees"

                val result = processor().process(resident, transcript)

                assertThat(result).isEqualTo(
                    ProcessingResult(
                        observations =
                        listOf(
                            observation(1, BloodPressure.of(120, 75).assertRight()),
                            observation(2, Pulse.of(80).assertRight()),
                            observation(3, BodyTemperature.ofCelsius(37.2).assertRight()),
                        ),
                        rejected = emptyList(),
                        usedAiFallback = false,
                    ).right(),
                )
            }

        @Test
        fun `should send a low-confidence rule result to review`() = runTest {
            assertThat(processor().process(resident, "measured 120/75")).isEqualTo(
                ProcessingResult(
                    observations =
                    listOf(
                        observation(
                            1,
                            BloodPressure.of(120, 75).assertRight(),
                            confidence = 0.6,
                            status = ObservationStatus.NEEDS_REVIEW,
                        ),
                    ),
                    rejected = emptyList(),
                    usedAiFallback = false,
                ).right(),
            )
        }

        @Test
        fun `should convert an unnamed Fahrenheit reading and send it to review`() = runTest {
            assertThat(processor().process(resident, "temp 101.3")).isEqualTo(
                ProcessingResult(
                    observations =
                    listOf(
                        observation(
                            1,
                            BodyTemperature.ofCelsius(38.5).assertRight(),
                            confidence = 0.7,
                            status = ObservationStatus.NEEDS_REVIEW,
                        ),
                    ),
                    rejected = emptyList(),
                    usedAiFallback = false,
                ).right(),
            )
        }

        @Test
        fun `should return implausible values as rejected candidates next to the valid ones`() = runTest {
            assertThat(processor().process(resident, "BP 12 over 10, pulse 80")).isEqualTo(
                ProcessingResult(
                    observations = listOf(observation(1, Pulse.of(80).assertRight())),
                    rejected = listOf(RejectedCandidate("bp 12/10", OutOfRange("systolic", 12, 50, 260))),
                    usedAiFallback = false,
                ).right(),
            )
        }

        @Test
        fun `should return an empty result when nothing was found and no AI is configured`() = runTest {
            assertThat(processor().process(resident, "Resident slept well, no complaints"))
                .isEqualTo(ProcessingResult(emptyList(), emptyList(), usedAiFallback = false).right())
        }

        @Test
        fun `should use the injected rules instead of the default ones`() = runTest {
            val noRules = VitalExtractor { emptyList() }

            assertThat(processor(rules = noRules).process(resident, "pulse 80"))
                .isEqualTo(ProcessingResult(emptyList(), emptyList(), usedAiFallback = false).right())
        }
    }

    @Nested
    @DisplayName("AI fallback")
    inner class AiFallback {
        @Test
        fun `should not ask the AI when the rules found a value`() = runTest {
            val ai = FakeAi(listOf(RawVitalCandidate(VitalType.PULSE, 70.0)))

            val result = processor(ai).process(resident, "pulse 80")

            assertThat(ai.receivedTexts).isEmpty()
            assertThat(result).isEqualTo(pulse80Confirmed)
        }

        @Test
        fun `should ask the AI with the normalized text when the rules found nothing and send its result to review`() =
            runTest {
                val ai = FakeAi(listOf(RawVitalCandidate(VitalType.PULSE, 88.0)))

                val result = processor(ai).process(resident, "Heart beating  about eighty-eight times")

                assertThat(ai.receivedTexts).containsExactly("heart beating about eighty-eight times")
                assertThat(result).isEqualTo(
                    ProcessingResult(
                        observations =
                        listOf(
                            observation(
                                1,
                                Pulse.of(88).assertRight(),
                                source = ExtractionSource.AI,
                                confidence = 0.5,
                                status = ObservationStatus.NEEDS_REVIEW,
                            ),
                        ),
                        rejected = emptyList(),
                        usedAiFallback = true,
                    ).right(),
                )
            }

        @Test
        fun `should ask the AI when the rules found only rejected candidates and keep those candidates`() = runTest {
            val ai = FakeAi(listOf(RawVitalCandidate(VitalType.BLOOD_PRESSURE, 120.0, 80.0)))

            assertThat(processor(ai).process(resident, "bp 12/10")).isEqualTo(
                ProcessingResult(
                    observations =
                    listOf(
                        observation(
                            1,
                            BloodPressure.of(120, 80).assertRight(),
                            source = ExtractionSource.AI,
                            confidence = 0.5,
                            status = ObservationStatus.NEEDS_REVIEW,
                        ),
                    ),
                    rejected = listOf(RejectedCandidate("bp 12/10", OutOfRange("systolic", 12, 50, 260))),
                    usedAiFallback = true,
                ).right(),
            )
        }

        @Test
        fun `should reject hallucinated AI values with the same validation as the rules`() = runTest {
            val ai =
                FakeAi(
                    listOf(
                        RawVitalCandidate(VitalType.PULSE, 400.0),
                        RawVitalCandidate(VitalType.PULSE, 88.5),
                    ),
                )

            assertThat(processor(ai).process(resident, "nothing measurable")).isEqualTo(
                ProcessingResult(
                    observations = emptyList(),
                    rejected =
                    listOf(
                        RejectedCandidate("AI: PULSE 400", OutOfRange("pulse", 400, 20, 250)),
                        RejectedCandidate(
                            "AI: PULSE 88.5",
                            InconsistentValues("pulse must be a whole number, was 88.5"),
                        ),
                    ),
                    usedAiFallback = true,
                ).right(),
            )
        }

        @Test
        fun `should report the fallback as used even when the AI found nothing`() = runTest {
            assertThat(processor(FakeAi(emptyList())).process(resident, "nothing measurable"))
                .isEqualTo(ProcessingResult(emptyList(), emptyList(), usedAiFallback = true).right())
        }
    }
}
