# Section: Wire Protocol

**What lives here:** `safesy-proto` — the contract shared by the Android app, the server, and any future embedded client.

**Owner:** unassigned · **Status:** ✅ v1 complete and in use

Generates cleanly, compiles into the Android build, and every PR runs a backward-compatibility check. `PhoneUsage` was added 2026-08-08 (additive).

---

## Why this is its own section

This is the **only** genuinely shared artifact in the system. Two independent client implementations (Kotlin now, Rust later) are bound by this schema, a written spec, and a conformance suite — not by a shared library.

A change here changes **every** client at once. Treat it accordingly.

## The one permanent rule

> **The schema is ADDITIVE-ONLY, forever.**

Never renumber, never reuse, never repurpose a field. Retire one by leaving its number in place and adding an explicit `reserved`.

**Why so strict:** a renumbered field silently corrupts data on every phone running an older build. A 12-month tail of un-updated clients is **permanent policy**, not a transition — version-gating ingest would drop exactly the buses on the worst connections, which are the ones the system exists to protect.

This is enforced mechanically by [`scripts/check-proto-compat.sh`](../scripts/check-proto-compat.sh), which runs on every PR.

## Field-number budget

Numbers 1–15 cost **one** tag byte; 16+ cost two. At ~12 B/point that is ~8% of payload. **Keep hot fields (`Delta`, `RadioSample`) under 16.**

## Measured sizes

| | |
|---|---|
| 60-point batch + 2 radio samples | 1127 B raw → **799 B gzipped** |
| Per point | ~13.3 B |
| Per bus per month | **~11.9 MB** |

## Design notes

**Delta encoding.** Absolute coordinates cost 8 B as `fixed64`. At 80 km/h a 1-second delta is ~2000 in 1e-7 units — a 2-byte zigzag varint. That is the whole trick.

**Three clocks** (`Anchor`). Wall clock is *advisory only*; `mono_ms` + `boot_id` for durations; `gnss_t_ms` is authoritative — but set **only** for genuine `GPS_PROVIDER` fixes, because network/fused fixes return the untrusted system clock.

**`has_fix`.** Without it, proto3 defaults silently produce `(0,0)` — a valid-looking coordinate off West Africa.

**`RadioSample` is an independent channel.** Coverage cannot be inferred from telemetry arrival gaps: a late point could mean no coverage, a killed process, a dead battery, or a trip ending. And "covered but 2G-only" leaves no trace in a stream that only records what got through. **Record `rat = NONE` explicitly** — a dead zone is data.

## Contributing

Changes here need more care than anywhere else in the project. Before proposing one:

1. Can it be **additive**? Almost always yes.
2. Does it fit under field number 16 if it is hot?
3. Run `scripts/check-proto-compat.sh` — CI will anyway.
4. Update [`conformance/`](../conformance/README.md) fixtures in the same PR.

Regenerate: `protoc --proto_path=proto --java_out=lite:OUT proto/safesy/v1/telemetry.proto`

## Related

[`spec/`](../spec/README.md) — sealing and seq rules · [`conformance/`](../conformance/README.md) — golden fixtures
