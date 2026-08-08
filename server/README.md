# Section: Server & Admin Portal

**What lives here:** ingest, storage, scoring, the Ministry portal, and enrolment.

**Owner:** unassigned · **Status:** not started (Step 2)

---

## Why this is its own section

Nothing here needs Android. It is a backend with a web front-end, and a contributor can build the whole thing against the protobuf schema without ever opening Android Studio.

It is also where **all the policy** lives. The device emits *observations*; the server decides what they are *worth*. That separation is deliberate — scoring changes as the pilot teaches you things, and server-side it is recomputable across all history instead of an app update into a 12-month tail of un-updated phones.

## Architecture

```
POST /v1/ingest → validate → dedupe (trip_id, seq) → per-batch ack → enqueue
                                                          ↓
   ┌──────────────┬─────────────────┬────────────────┬────────────────┐
 points         events            radio            scoring        live cache
 TimescaleDB    PostgreSQL        PostGIS/H3       (policy)       Redis
```

**Ingest must be dumb and fast:** validate, dedupe, enqueue, return 200. All real work is async. The load spike that matters is **50 buses reconnecting simultaneously** after a regional outage — bursty by nature.

**Self-hosted.** Sanctions make managed cloud a liability, and the Ministry will require data residency regardless.

## Invariants

1. **Accept out-of-order and late batches for 30 days.** A bus backfilling a Deir ez-Zor gap delivers `seq` 40–52 long after other buses delivered newer data. Any design assuming monotonic arrival breaks in week one.
2. **Per-batch ack/nack.** Partial failure must never be ambiguous.
3. **A stale token must never block ingest.** Buses reconnect after hours holding expired tokens; accept on device-credential signature and issue a fresh one in the response. Rejecting them would discard data from exactly the worst-connected vehicles.
4. **Accept `identity` and small-window deflate `Content-Encoding` from day one.** One line now; a breaking protocol change once embedded hardware ships (full-window gzip needs 256 KB, more than an MCU's usable SRAM).
5. **Single writer to the live cache.** A backfilled stale batch must not make a bus jump backward on the map.

## Scoring: behaviour vs. exposure

**The most important design decision in this section.**

Speed variance is confounded by road condition — oscillating 60→100→60 is what *everyone* does on a potholed, checkpoint-riddled route. Score it naively and a Deir ez-Zor driver is **structurally punished** relative to a Damascus–Homs driver for identical driving. Drivers talk; they would notice within a week, and it would kill both adoption and the score's validity.

| Category | Treatment |
|---|---|
| **Behaviour** — what the driver chose | Scored, **normalized per road segment** — against the fleet distribution on *that same segment* |
| **Exposure** — what the schedule imposed | **Reported as context, never penalised.** Night hours, duration, route difficulty |

Segment normalization also solves the missing-speed-limit problem for free: *"faster than 85% of drivers on this exact segment"* needs no external dataset — and WHO rates Syrian speed-limit legislation as "weak/none", so there is no legal limit to score against anyway.

**Fatigue is the honest exception:** it is exposure, but a real safety risk. Report it to the Ministry as a *rostering* finding; keep it out of the driver's score.

## Enrolment — the registry is paper

Confirmed: there is no Ministry API.

1. Ministry provides a roster CSV `(plate, name, national ID)` → imported as the allowlist
2. Driver enters plate + national ID; matched against the roster
3. No match → clerk-approved pending queue
4. Credential binds **`(vehicle_id, device_pubkey)`**; **`driver_id` is per-trip** (one bus, several drivers, often one shared phone)
5. **National IDs stored as salted hashes** — needed only as a match key
6. **Attestation optional** — grey-market phones on Android 8–9 often lack it; requiring it fails enrolment on the target fleet

## Governance — a pilot gate, not an appendix

This is a state-operated database of named individuals' movements, in a country where that has historically been dangerous. Five commitments:

1. **Retention that forgets locations.** Events carry coordinates, so "events forever" builds a permanent movement archive. Location detail ages out at ~12 months into per-driver aggregates. **Scores persist; coordinates don't.**
2. **Right of exit.** Opt-out **deletes** history, not just future collection.
3. **Tiered access.** Live individual tracking role-gated separately from aggregate views.
4. **Append-only audit log** of who queried whose movement history, with a **named reviewer**. An unread log is theatre.
5. **Written Ministry data-use agreement before the pilot.**

## The portal

- **Staleness shown honestly.** A bus in a dead zone sits stale for hours; rendering it like a live bus makes an operator act on a 3-hour-old pin. Every pin carries its update age; beyond ~5 min renders visibly different.
- **Coverage dead-zone map** as a first-class feature, not a debug view. It is the first empirical measurement of where cellular fails on Syrian highways — valuable to the Ministry and carriers independent of the safety product.

## Contributing

Good first tasks:
- `docker-compose` with PostgreSQL + PostGIS + TimescaleDB
- Ingest endpoint: validate, dedupe on `(trip_id, seq)`, per-batch ack
- **50-bus reconnect storm test** — the load spike that actually matters
- Roster CSV import + pending-approval queue
- Coverage-map aggregation into an H3 grid

⚠️ **Before the storm test is meaningful**, the ack contract and trip-settlement rule must be defined — see [`docs/architecture/SPEC.md`](../docs/architecture/SPEC.md) §S5.2 and §8b.

**Language is undecided.** Kotlin/JVM (shares the proto tooling) or Go (simpler deployment). Open an issue if you have a view — this is a genuinely open question.

## Related

Consumes [`proto/`](../proto/README.md) · Receives from [`outbox/`](../android/app/src/main/kotlin/sy/safesy/outbox/README.md) · Spec: [`docs/architecture/SPEC.md`](../docs/architecture/SPEC.md) §S5–S6
