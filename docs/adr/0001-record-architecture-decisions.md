# 1. Record architecture decisions

- Status: Accepted
- Date: 2026-10-25

## Context

Many choices in CareVoice are not obvious from the code alone: two databases, an outbox, a message broker, a
connection pooler that exists only in Kubernetes. A later reader needs to know why each choice was made and what it
costs.

## Decision

Every important decision gets an Architecture Decision Record (ADR) in `docs/adr/`. Each ADR has a number and four
parts: Context, Decision, Consequences, Alternatives considered. `docs/CONTRACTS.md` says *what* the system must do;
the ADRs say *why*.

ADRs describe the system as it is today. When the details of a decision change (a new limit, a new retry rule), the
ADR is updated. When a decision is reversed, a new ADR replaces it, and the old one is marked
`Superseded by ADR-NNNN`.

## Consequences

- Reviewers can question a decision by its number instead of arguing about it from the code.
- Each decision costs a little writing.
