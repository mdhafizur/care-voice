package com.carevoice.alert.support

import com.carevoice.alert.application.AlertRepository
import com.carevoice.alert.application.GuidanceGenerator
import com.carevoice.alert.application.GuidanceStep
import com.carevoice.alert.application.GuidanceWriter
import com.carevoice.alert.application.Keyset
import com.carevoice.alert.application.ProtocolChunkRepository
import com.carevoice.alert.domain.Alert
import com.carevoice.alert.domain.AlertRuleType
import com.carevoice.alert.domain.AlertStatus
import com.carevoice.alert.domain.Severity
import com.carevoice.alert.domain.Vital
import com.carevoice.alert.domain.WardScope
import com.carevoice.alert.domain.guidance.EmbeddedChunk
import com.carevoice.alert.domain.guidance.ScoredChunk
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import java.time.Instant

/** In-memory knowledge base for use-case tests (the Mongo adapter is tested through the endpoint test). */
class InMemoryProtocolChunks : ProtocolChunkRepository {
    private val versions = mutableMapOf<String, String>()
    private val corpora = mutableMapOf<String, List<EmbeddedChunk>>()
    var replaceCalls = 0
        private set

    override suspend fun corpusVersion(model: String): String? = versions[model]

    override suspend fun replaceCorpus(model: String, version: String, chunks: List<EmbeddedChunk>) {
        replaceCalls++
        versions[model] = version
        corpora[model] = chunks
    }

    override suspend fun findAll(model: String): List<EmbeddedChunk> = corpora[model].orEmpty()
}

/** Alerts by id, scoped like the real repository; only the reads the guidance use case needs. */
class InMemoryAlerts(vararg alerts: Alert) : AlertRepository {
    private val byId = alerts.associateBy { it.id }

    override suspend fun findById(scope: WardScope, alertId: String): Alert? =
        byId[alertId]?.takeIf { it.scope == scope }

    override suspend fun insertAll(alerts: List<Alert>): List<Alert> = error("not used")

    override suspend fun findPage(scope: WardScope, status: AlertStatus?, keyset: Keyset?, limit: Int): List<Alert> =
        error("not used")

    override suspend fun count(scope: WardScope, status: AlertStatus?): Long = error("not used")

    override suspend fun acknowledge(
        scope: WardScope,
        alertId: String,
        expectedVersion: Long,
        acknowledgedBy: String,
        acknowledgedAt: Instant,
    ): Alert? = error("not used")
}

/** A GuidanceWriter whose answer the test scripts; records what it received. */
class ScriptedGuidanceWriter(private val answer: (List<ScoredChunk>) -> List<GuidanceStep>?) : GuidanceWriter {
    override val generator: GuidanceGenerator = GuidanceGenerator.CLAUDE
    var receivedSources: List<ScoredChunk> = emptyList()
        private set

    override suspend fun write(situation: String, sources: List<ScoredChunk>): List<GuidanceStep>? {
        receivedSources = sources
        return answer(sources)
    }
}

object GuidanceTestAlerts {
    /** A fresh alert per call (never mutate shared fixtures). */
    fun alert(
        rule: AlertRuleType = AlertRuleType.HYPERTENSIVE_CRISIS,
        vital: Vital = Vital.BloodPressure(185, 95),
        severity: Severity = Severity.CRITICAL,
        unconfirmed: Boolean = false,
        id: String = "alert-0001",
        wardId: String = TestFixtures.WARD,
    ) = Alert(
        id = id,
        tenantId = TestFixtures.TENANT,
        wardId = wardId,
        residentId = "res-1",
        observationId = "obs-1",
        eventId = "event-$id",
        rule = rule,
        severity = severity,
        message = "Test alert for $rule." + if (unconfirmed) " (unconfirmed value: needs review by a nurse)" else "",
        vital = vital,
        status = AlertStatus.OPEN,
        observedAt = Instant.parse("2026-10-25T09:59:00Z"),
        createdAt = TestFixtures.NOW,
        acknowledgedBy = null,
        acknowledgedAt = null,
        version = 0,
    )
}

/** Guidance buckets. */
object GuidanceTestValues {
    private const val CRISIS = "hypertensive-crisis"

    /**
     * The retrieval evaluation set: (rule, vital, severity, expected protocol). Every alert rule, including both
     * branches of the crisis rule, must retrieve its own protocol. Measured margin over the next-best protocol with
     * the hashing embedder: >= 0.18.
     */
    @JvmField
    val evaluationSet: List<Arguments> =
        listOf(
            arguments(AlertRuleType.HYPERTENSIVE_CRISIS, Vital.BloodPressure(185, 95), Severity.CRITICAL, CRISIS),
            arguments(AlertRuleType.HYPERTENSIVE_CRISIS, Vital.BloodPressure(150, 125), Severity.CRITICAL, CRISIS),
            arguments(AlertRuleType.HYPOTENSION, Vital.BloodPressure(82, 50), Severity.WARNING, "hypotension"),
            arguments(AlertRuleType.TACHYCARDIA, Vital.Pulse(135), Severity.WARNING, "tachycardia"),
            arguments(AlertRuleType.BRADYCARDIA, Vital.Pulse(40), Severity.WARNING, "bradycardia"),
            arguments(AlertRuleType.HIGH_FEVER, Vital.BodyTemperature(39.8), Severity.CRITICAL, "high-fever"),
            arguments(AlertRuleType.HYPOTHERMIA, Vital.BodyTemperature(34.2), Severity.CRITICAL, "hypothermia"),
        )

    /** Texts no protocol covers: they must stay below the relevance threshold (no guidance is better than wrong). */
    @JvmField
    val unrelatedTexts: List<String> =
        listOf(
            "wound dressing changed, skin intact",
            "resident enjoyed the music afternoon",
            "lunch eaten completely, good appetite",
        )
}
