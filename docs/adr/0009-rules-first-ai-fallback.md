# 9. Deterministic rules first, AI only as a reviewed fallback

- Status: Accepted
- Date: 2026-10-25

## Context

Transcripts are free speech, for example "blood pressure one twenty over seventy-five, pulse 80". A large language
model (LLM) can read almost any phrasing, but it can also invent or misread a number, it costs money and time per
call, and it sends text to a third party. In a care record, a wrong value is worse than a missing one.

## Decision

- **A normalizer** turns spoken variants into one standard form (lowercase with a fixed locale, "over" → "/", and so
  on).
- **Rule extractors** for blood pressure, pulse and temperature (Strategy and Composite patterns) find candidate
  values. Each gets a confidence based on how clear the nurse was: keyword and unit score higher than an assumed
  unit, which scores higher than no keyword.
- **Every value**, from rules or AI, goes through the same domain smart constructors: plausible ranges, diastolic
  below systolic, whole numbers where required. An implausible value comes back as `rejected` with a reason and is
  never stored.
- **The AI fallback** (Chain of Responsibility: rules, then AI) runs only when the rules find nothing and only when
  `AI_FALLBACK_ENABLED=true`. It sits behind a port, with a timeout per attempt and its own circuit breaker. Any AI
  failure (timeout, error, open breaker, unreadable answer) simply means "no AI result"; the nurse's request still
  succeeds.
- **Review status**: confident rule results (confidence 0.8 or more) are `CONFIRMED`. Low-confidence rule results and
  all AI results are `NEEDS_REVIEW` until a nurse confirms them. alert-service still raises alerts for unconfirmed
  values, marked as unconfirmed, so a possible crisis is never hidden.

## Consequences

- Most transcripts are processed deterministically: fast, free, and testable with exhaustive tables.
- The AI covers more phrasings without being trusted blindly. Its use is a metric (`carevoice_ai_fallback_total`).
- Rules need maintenance for new phrasings; the AI fallback rate shows where.

## Alternatives considered

- **AI for everything**: the simplest code, but not deterministic, slower, more expensive and harder to test.
- **Rules only**: safe, but misses unusual phrasing completely.

The fallback can call Claude or Gemini (`AI_PROVIDER`); see [ADR-0025](0025-model-provider-behind-ports.md).
