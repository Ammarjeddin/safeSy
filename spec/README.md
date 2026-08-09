# Section: Written Spec

**What lives here:** the rules that are prose rather than code — batch sealing, `seq` allocation, time handling, outbox policy, degradation.

**Owner:** unassigned · **Status:** ⬜ **empty** — the rules exist only in code comments and DESIGN.md

⚠️ This is a real risk to the portability requirement. The sealing, `seq`, and time rules are currently only in Kotlin comments and prose in `docs/architecture/`. Until they are written here, a future Rust implementation has nothing normative to implement against. (Step 0 remainder)

---

## Why prose and not a library

These rules must hold identically in Kotlin and in Rust. A shared native library was considered and **rejected** — see [`docs/architecture/DESIGN.md`](../docs/architecture/DESIGN.md) §0.1.

What replaces it: this spec (the rules), [`proto/`](../proto/README.md) (the format), and [`conformance/`](../conformance/README.md) (the proof).

## What belongs here

- **Batch sealing** — when to seal, what goes in, immutability
- **`seq` allocation** — exactly once, transactionally with the bytes
- **Time** — the three-clock model and how the server reconciles them
- **Outbox policy** — drain order, retry, backoff, resumability
- **Degradation** — behaviour on a bad link

## What does not

Anything already enforced by the schema (put it in `proto/`), and anything Android-specific (put it in the relevant section README).

## Related

[`proto/`](../proto/README.md) · [`conformance/`](../conformance/README.md) · [`docs/architecture/SPEC.md`](../docs/architecture/SPEC.md)
