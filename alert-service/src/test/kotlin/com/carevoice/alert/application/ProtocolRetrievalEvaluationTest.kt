package com.carevoice.alert.application

import com.carevoice.alert.domain.AlertRuleType
import com.carevoice.alert.domain.Severity
import com.carevoice.alert.domain.Vital
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.domain.guidance.Retriever
import com.carevoice.alert.infrastructure.guidance.ClasspathProtocolSource
import com.carevoice.alert.infrastructure.guidance.HashingEmbedder
import com.carevoice.alert.support.GuidanceTestAlerts
import com.carevoice.alert.support.InMemoryAlerts
import com.carevoice.alert.support.InMemoryProtocolChunks
import com.carevoice.alert.support.TestFixtures
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.FieldSource

/**
 * The retrieval evaluation set (ADR-0023): runs in every build against the real, shipped protocols. A protocol edit or
 * an embedder change that makes any alert rule retrieve the wrong protocol, or makes unrelated text match one, fails
 * the build instead of silently degrading the guidance.
 */
@DisplayName("Protocol retrieval evaluation")
class ProtocolRetrievalEvaluationTest {
    private val embedder = HashingEmbedder()
    private val knowledgeBase =
        InMemoryProtocolChunks().also { chunks ->
            runBlocking { IngestProtocols(ClasspathProtocolSource(), embedder, chunks).execute() }
        }
    private val scope = WardScope(TestFixtures.TENANT, TestFixtures.WARD)

    @ParameterizedTest(name = "should retrieve {3} for {0} {1}")
    @FieldSource("com.carevoice.alert.support.GuidanceTestValues#evaluationSet")
    fun `should retrieve the protocol of the alert rule and no other`(
        rule: AlertRuleType,
        vital: Vital,
        severity: Severity,
        expectedProtocol: String,
    ) = runBlocking<Unit> {
        val alert = GuidanceTestAlerts.alert(rule = rule, vital = vital, severity = severity)

        val useCase = GetAlertGuidance(InMemoryAlerts(alert), embedder, knowledgeBase, writer = null)

        val guidance = useCase.execute(scope, alert.id)

        assertThat(guidance.sources.map { it.chunk.documentId }.distinct()).containsExactly(expectedProtocol)
    }

    @ParameterizedTest(name = "should find no protocol for \"{0}\"")
    @FieldSource("com.carevoice.alert.support.GuidanceTestValues#unrelatedTexts")
    fun `should not retrieve any protocol for text that no protocol covers`(text: String) = runBlocking<Unit> {
        val corpus = knowledgeBase.findAll(embedder.model)

        assertThat(Retriever.bestProtocol(embedder.embedQuery(text), corpus, embedder.minScore)).isEmpty()
    }
}
