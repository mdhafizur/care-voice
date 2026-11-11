# 25. The model provider is an adapter: Claude or Gemini

- Status: Accepted
- Date: 2026-10-26

## Context

CareVoice uses a language model in three places: the extraction fallback when the rules find no vital sign
(ADR-0009), and the guidance writer and the embeddings of the protocol RAG (ADR-0023). At first, each was written
against one provider (Claude, Voyage). Teams choose model vendors for reasons outside the code (contracts, data
residency, price, an existing Google Cloud account), and a care product should not need a rewrite to switch.

## Decision

- The application layer only knows the ports `AiVitalExtractor`, `GuidanceWriter` and `Embedder`. Each provider is
  an adapter in `infrastructure`. `AI_PROVIDER=claude|gemini` selects the writers, and
  `GUIDANCE_EMBEDDER=hashing|voyage|gemini` selects the embedder.
- **What must be the same for every provider is written once.** `HttpAiVitalExtractor` (Template Method) owns the
  failure policy, the retry, the circuit breaker per provider, the metrics and the logging; the subclasses only build
  the request and read the answer text. `VitalExtractionPrompt` / `GuidancePrompt` are the prompts both providers
  get, and `VitalAnswerParser` / `GuidancePrompt.parse` read both answers. The grounding check in `GetAlertGuidance`
  and the guidance circuit breakers (`guidance-writer`, `guidance-embedder`) work the same for every provider.
- **Each adapter uses what its API does best.** Gemini gets a JSON Schema (`responseJsonSchema`) for the answer, and
  for guidance the allowed citation ids as an `enum`, so it cannot produce an invented citation at all. Thinking is
  set to `low` (`GEMINI_THINKING_LEVEL`), because extracting and rephrasing need no reasoning, and Gemini 3 cannot
  switch thinking off. Gemini embeddings are requested at 768 dimensions with `RETRIEVAL_DOCUMENT` /
  `RETRIEVAL_QUERY` and normalized locally.
- Model names are separate variables per provider (`AI_MODEL`, `GEMINI_MODEL`, `GEMINI_EMBEDDING_MODEL`), so
  switching the provider never sends one vendor's model name to the other.
- The time limit per attempt is configurable (`AI_TIMEOUT_MS`; default 3 s for Claude, 5 s for Gemini). Every
  request also has an overall deadline (`REQUEST_TIMEOUT_MS`, default 30 s), which must stay above twice
  `AI_TIMEOUT_MS`.

## Consequences

- Switching providers is configuration only. Adding one (OpenAI, a self-hosted model) means one adapter per port
  plus a test with a stubbed HTTP API.
- The metrics (`carevoice_ai_fallback_total{result}`, `carevoice_ai_request_seconds`) stay comparable across
  providers, and the breaker name (`anthropic`, `gemini`) shows which one is active.
- Retrieval quality depends on the embedder. `minScore` is set per model and must be tuned with the evaluation set
  when the embedder changes. Switching the embedder re-embeds the corpus at startup (the corpus is keyed by model and
  dimensions).
- The adapters are tested against recorded response shapes, not against the live APIs. A smoke test with a real key
  (docs/local-access.md) stays a manual step.

## Alternatives considered

- **An LLM gateway or SDK abstraction (LangChain4j, LiteLLM)**: one more dependency and abstraction layer for three
  HTTP calls. The lowest common denominator would also hide exactly the features used here (schemas, thinking
  level).
- **Provider SDKs** (Anthropic Java SDK, Google GenAI SDK): heavier dependency trees and their own HTTP stacks, next
  to the Ktor client, resilience and metrics that the services already have.
