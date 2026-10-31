package com.carevoice.domain.extraction

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource
import java.util.Locale

@DisplayName("TranscriptNormalizer")
class TranscriptNormalizerTest {
    @Nested
    @DisplayName("Transformation")
    inner class Transformation {
        @ParameterizedTest(name = "should normalize \"{0}\" to \"{1}\"")
        @FieldSource("com.carevoice.domain.TranscriptValues#spokenVariants")
        fun `should turn every spoken variant into the canonical form`(spoken: String, normalized: String) {
            assertThat(TranscriptNormalizer.normalize(spoken)).isEqualTo(normalized)
        }

        @Test
        fun `should lowercase independently of the default locale`() {
            // In a Turkish locale "I".lowercase(locale) is a dotless "ı", which no extractor keyword would match.
            val previous = Locale.getDefault()
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            try {
                assertThat(TranscriptNormalizer.normalize("CELSIUS")).isEqualTo("celsius")
            } finally {
                Locale.setDefault(previous)
            }
        }

        @Test
        fun `should return an empty text for whitespace only`() {
            assertThat(TranscriptNormalizer.normalize(" \t\n ")).isEmpty()
        }
    }

    @Nested
    @DisplayName("Words left untouched")
    inner class WordsLeftUntouched {
        @ParameterizedTest(name = "should keep \"{0}\"")
        @FieldSource("com.carevoice.domain.TranscriptValues#wordsLeftUntouched")
        fun `should keep over, slash and point when they do not stand between two numbers`(text: String) {
            assertThat(TranscriptNormalizer.normalize(text)).isEqualTo(text)
        }

        @ParameterizedTest(name = "should keep \"{0}\"")
        @FieldSource("com.carevoice.domain.TranscriptValues#decimalComma")
        fun `should deliberately not rewrite a decimal comma`(text: String) {
            assertThat(TranscriptNormalizer.normalize(text)).isEqualTo(text)
        }
    }
}
