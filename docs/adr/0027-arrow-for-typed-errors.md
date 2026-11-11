# 27. Arrow's Either for typed errors in the domain

- Status: Accepted (replaces the hand-written `Either` of ADR-0003)
- Date: 2026-11-10

## Context

The domain returns validation results as values, not exceptions: a pulse of 300 is an expected outcome, and the type
`Either<ValidationError, Pulse>` makes every caller handle it (ADR-0003). This started as a 40-line hand-written
`Either` to keep the domain free of dependencies. As validation grew, chains of steps that can each fail were written
as nested `flatMap` calls, and every smart constructor repeated the same `when` / `.left()` / `.right()` shape.

## Decision

- Use **Arrow's core library** (`io.arrow-kt:arrow-core`, pinned in the version catalog) as the domain's only
  dependency, exposed with `api` because `Either` is part of the domain's public signatures.
- Write validation with the **`either { }` builder**: `ensure(condition) { error }` for each rule,
  `ensureNotNull(value) { error }` for required values, and `bind()` to take a step's success or stop with its error.
  The first failing rule wins, exactly as before.
- No error accumulation (`zipOrAccumulate`): a vital sign has two or three fields, and the first problem is enough for
  a nurse to correct it. The error type stays a single `ValidationError`.
- Only `arrow-core`: no optics, no `arrow-resilience` (retry and circuit breakers live in the services).
- The architecture test turns the domain rule into an **allow-list**: production code in `com.carevoice.domain` may
  import only itself, `kotlin.*`, `arrow.core.*`, `java.time.*` and `java.util.UUID`.

## Consequences

- Smart constructors read as a list of rules; multi-step validation reads top to bottom instead of nested lambdas.
- A well-known, maintained type instead of a home-made one; new team members may already know it.
- The domain has one dependency. The allow-list keeps it at one; Arrow updates go through the version catalog like
  any other library.
- Arrow's `fold` names its parameters `ifLeft` / `ifRight`.
- `either { }` stops early by throwing a private `CancellationException` subclass. Code inside the block must not catch
  `CancellationException` or `Throwable` without rethrowing (the codebase already rethrows `CancellationException`
  everywhere).

## Alternatives considered

- **Keep the hand-written `Either`**: no dependency, but each new chain repeats `flatMap` nesting, and anything beyond
  the basics (a builder, accumulation) would mean re-implementing Arrow.
- **Kotlin's `Result`**: the error side is always a `Throwable`, so typed domain errors (`OutOfRange`, `BlankInput`)
  would have to become exceptions again.
- **Exceptions for validation**: invisible in signatures; the compiler cannot check that every caller handles them.
