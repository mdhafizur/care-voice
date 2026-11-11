# 23. Retrieval-augmented protocol guidance for alerts

- Status: Accepted
- Date: 2026-10-26

## Context

A ward lead who gets an alert ("blood pressure 185/95 mmHg") has to remember what the facility's protocol says: how
to recheck, when to escalate, what to document. The protocols exist as documents. A language model (LLM) could
phrase the right steps for this exact alert. But an LLM on its own invents instructions that sound right, and in care
a wrong instruction is worse than none.

## Decision

`GET /v1/alerts/{alertId}/guidance` (WARD_LEAD, limited to the caller's ward like every alert read) returns short
steps for the alert, and each step cites the protocol sections it is based on. It works as retrieval-augmented
generation (RAG) inside alert-service:

1. **Knowledge base.** The protocols are markdown files shipped with the service (`resources/protocols`), reviewed
   like code. At startup they are split into one chunk per `##` section, embedded, and stored in MongoDB
   (`protocol_chunks`, migration M003). Ingestion is idempotent: a corpus version (a hash of the model and all chunk
   texts) skips a corpus that has not changed, and pods that ingest at the same time write identical documents.
2. **Retrieval.** One query per aspect of the alert: the clinical situation (rule terms + measured value) and, for an
   unconfirmed value, the review situation. (With one combined query, the clinical terms drowned out the review
   aspect.) Each query picks the protocol whose best chunk matches best, and then returns all sections of that
   protocol ("small-to-big"), so "Immediate steps" and "When to escalate" always arrive together. No personal data
   is part of a query.
3. **Generation.** Optional (`GUIDANCE_AI_ENABLED`): the configured LLM (Claude or Gemini, ADR-0025) writes at most
   6 steps, using only the retrieved sections and citing their ids, or answers `NO_GUIDANCE`. Claude runs at
   temperature 0; Gemini returns structured output whose citations can only be retrieved ids. Without an LLM, the
   extractive writer copies the protocol's own steps.
4. **Grounding check.** Every step must cite at least one retrieved section and nothing else, within limits on count
   and length. An answer that is ungrounded, declined, malformed or failed falls back to the extractive steps. The
   caller gets cited guidance or an explicit `NO_MATCHING_PROTOCOL`, never text without a source.
5. **Advisory only.** The endpoint changes nothing, and every response carries a disclaimer.
6. **Evaluation in CI.** `ProtocolRetrievalEvaluationTest` checks against the shipped protocols that every alert rule
   retrieves its own protocol, and that unrelated text retrieves none. A protocol edit or an embedder change that
   breaks retrieval fails the build.

**Embeddings** sit behind an `Embedder` port:
- `HashingEmbedder` (default): feature-hashed words and word pairs, local and deterministic. It needs no API key, and
  no health text leaves the service. It is lexical, which is enough for a curated corpus that shares its wording with
  the queries. In the evaluation set, the correct protocol beats the next best by 0.18 or more, and unrelated text
  scores below 0.16 (threshold 0.2).
- `VoyageEmbedder` (`GUIDANCE_EMBEDDER=voyage`) and `GeminiEmbedder` (`GUIDANCE_EMBEDDER=gemini`): semantic
  embeddings, for synonyms and free-text questions. Claude has no embedding endpoint; Anthropic recommends Voyage AI.

**Vector search** is exact and in memory: all chunks of the model are loaded and scored. MongoDB Community has no
vector index, and a few dozen sections are scored in microseconds.

**Failures.** The writer and a remote embedder each have a circuit breaker (`guidance-writer`, `guidance-embedder`).
While the writer fails, the extractive steps are served at once. While the embedder fails, the endpoint answers 503
at once, because without embeddings there is nothing to retrieve.

## Consequences

- The writer and the embedder are adapters: Claude or Gemini writes; hashing, Voyage or Gemini embeds
  ([ADR-0025](0025-model-provider-behind-ports.md)).
- Guidance is traceable: every step links to protocol text that a reviewer can read.
- It works fully offline by default. The LLM and semantic embeddings are opt-in, and they fall back to the
  extractive answer.
- Alerting never depends on it. If ingestion fails, the failure is logged and the service still starts. An embedding
  failure at query time is a 503 for this endpoint only.
- The hashing embedder does not understand synonyms. New protocols must use the clinical terms the rules use, or the
  evaluation set fails (on purpose).
- With the LLM on, each call costs an API request. There is no caching yet; answers could be cached per alert and
  corpus version.

## Alternatives considered

- **An LLM without retrieval**: fluent but impossible to verify; ruled out for clinical instructions.
- **pgvector / Atlas Vector Search / a vector database**: needed at thousands of chunks. Today it would add a Postgres
  extension (in both the compose and the CloudNativePG images) or a new system. The port allows the switch later.
- **Fine-tuning on the protocols**: protocols change. Retrieval picks up an edit at the next deploy and keeps the
  citations.
- **Top-k chunks without small-to-big**: often returned "When this protocol applies" but cut off "When to escalate".
