package com.carevoice.documentation.application

import com.carevoice.documentation.resilience.LogType
import com.carevoice.documentation.resilience.event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.UUID

/** Use case: read a processed transcript; answers with the same body the original 201 returned. */
class GetTranscript(private val transcripts: TranscriptRepository) {
    private val log = LoggerFactory.getLogger(GetTranscript::class.java)

    /** Returns the stored result. A malformed id or a transcript from another ward is a 404. */
    suspend fun execute(caller: Caller, transcriptId: String): TranscriptResult {
        caller.requireAnyRole()
        val id =
            parseUuid(transcriptId) ?: run {
                log.event(Level.DEBUG, LogType.BUSINESS, "Transcript not found", "reason" to "malformed-id")
                throw ApplicationError.NotFound("Transcript", transcriptId)
            }
        val record =
            transcripts.findById(caller.tenantId, caller.wardId, id)
                ?: run {
                    log.event(
                        Level.DEBUG,
                        LogType.BUSINESS,
                        "Transcript not found",
                        "transcriptId" to id,
                        "reason" to "not-in-ward",
                    )
                    throw ApplicationError.NotFound("Transcript", transcriptId)
                }
        return record.result
    }
}

/** A path id that is not a UUID cannot name an existing resource: that is a 404, not a 400. */
internal fun parseUuid(raw: String): UUID? = try {
    UUID.fromString(raw).takeIf { it.toString() == raw.lowercase() }
} catch (_: IllegalArgumentException) {
    null
}
