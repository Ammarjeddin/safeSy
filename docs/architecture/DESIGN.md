# safeSy — System Design v0.5

*v0.3 incorporated an adversarial review. v0.4 replaced the shared-native-library model with two independent client packages bound by a shared protocol and conformance suite. **v0.5 adds measured Syrian coverage-gap data (§2.6b), deletes the preview lane against a stated 5-minute liveness requirement (§2.3), and fixes the snippet encoding (§2.7).** Corrections to earlier claims are marked inline rather than silently edited, so the reasoning stays auditable.*

**Scope of this document:** the architecture for the Phase-1 Android app and its backend, designed so that Phase-2 embedded hardware becomes an additional telemetry source without rewriting the server, the scoring, or the data model.

**Explicitly out of scope for v1 (but reserved for in the design):** passenger manifests, ticketing, LoRa mesh, OBD2, dashcam.

---

## 0. The One Architectural Idea

Everything below follows from a single decision:

> **The phone is not the system. The phone is the first of several *telemetry producers*. The Ministry backend consumes a producer-agnostic wire format, and the app remains a permanent first-class producer even after hardware ships.**

### 0.1 Two client packages, one protocol

Earlier drafts tried to share a single native `safesy-core` library between Android (via JNI) and firmware (via C++). **That was the wrong optimisation.** The expensive thing is not writing the code twice — it is *not knowing what the rules are*, and letting two implementations quietly diverge.

So: **two independent client packages, bound by three shared artifacts.**

| Artifact | What it is | Nature |
|---|---|---|
| **`safesy-proto`** | Protobuf schema. The wire contract. | Genuinely shared — language-neutral by construction |
| **`safesy-spec`** | Written spec: batch sealing, seq allocation, time handling, outbox policy, degradation rules. A few pages. | Prose + pseudocode, not a library |
| **`safesy-conformance`** | Golden vectors: fixed input samples → byte-exact expected batches, as binary/JSON fixtures. | Data, not code — any language can run it |

| Package | Language | Status |
|---|---|---|
| **`safesy-android`** | **Pure Kotlin.** No JNI, no native library. | Phase 1 — ships first, **never retired** |
| **`safesy-embedded`** | **Rust** (`no_std`), target TBD | Phase 2 — additive |
| **`safesy-server`** | Kotlin/JVM or Go | Phase 1 — one ingest, one store, one portal |

Two implementations of a *written-down, conformance-tested* rule is cheap and safe. Two implementations of an *unwritten* rule is how systems diverge.

**But "byte-identical batches" is the wrong contract, and an earlier draft got this wrong.** Detection runs a Madgwick filter and Kalman fusion — `atan2`, `sqrt`, trig. Kotlin/JVM and `no_std` Rust **will not** produce bit-identical floats: different libm implementations, JVM intermediate promotion to `double`, FMA contraction differences. A byte-exact suite would fail spuriously on day one of the Rust port, then get quietly weakened to tolerance bands chosen under deadline pressure — precisely the hopeful guarantee this is meant to avoid.

So the suite has **three fixture classes**, with different contracts:

| Class | Contract | Covers |
|---|---|---|
| **A. Encoding** | **Byte-exact** | Delta encoding, anchor selection, batch framing, seq semantics, proto field ordering |
| **B. Detection** | **Toleranced** — `offset_ms` ±20 ms, `severity` ±2%, event kind and count exact | Filter output, event thresholds |
| **C. Scenarios** | **State assertions** | The stateful invariants golden vectors cannot express |

Class C is the one an input→bytes fixture cannot reach, and it covers the trickiest logic: **seq allocation surviving force-kill, ORPHANED/RESUMED under clock ambiguity, outbox retry/backoff, CLOSING drain.** Fixtures are scripts — sample streams interleaved with `KILL` / `RELAUNCH` / `NET_UP` / `NACK` directives, asserting on persisted state and emitted batches. Each package runs them against its own storage. Without class C, retry timing and resume heuristics are exactly what will drift between Kotlin and Rust.

**Alternative worth considering for class B:** fixed-point/integer detection math. It would make byte-exactness genuinely achievable and removes a whole category of cross-platform float divergence. Costs implementation effort; decide in `safesy-spec`, don't default.

**Fixture authorship:** in practice the Kotlin implementation will generate expected outputs and become the de facto spec while the prose rots. That's acceptable if named — **pin Kotlin as the reference implementation**, version fixtures alongside the spec, and require regeneration on every spec-change PR.

**What this buys:** no JNI bridge, no cross-thread ring buffer (and no memory-visibility bug that came with it), no `StoragePort` abstraction, no C++/Rust decision today, and idiomatic code on both sides. Room and coroutines handle Android directly; Rust handles flash and DMA directly. Neither is contorted to serve the other.

**Hardware target is deliberately undecided.** §3.2b's budget (BLE + TLS + modems + flash queue) may well point away from an ESP32-S3 toward something with more headroom. Choosing the chip two years early would be the actual mistake.

### 0.2 The app is permanent — a binding invariant

> **The Android app is not a stopgap. It remains a fully supported telemetry producer for the life of the system, and the portal serves both sources equally and simultaneously.**

Most buses will never get hardware. Hardware will fail, be swapped between vehicles, and arrive gradually over years. A mixed fleet is the **steady state**, not a migration window. Concretely, this forbids:

- Any portal feature that assumes a single producer type.
- Any schema where `producer = PHONE` is a legacy or degraded path.
- Any scoring rule that isn't comparable across producers — a driver's score must mean the same thing on either.
- Any ingest change that would break older app clients (§2.7's 12-month tail is permanent policy, not a transition).

Where the two genuinely differ — GNSS precision, IMU quality, tamper resistance, coverage via LoRa — the *data* carries the difference (`producer`, `hdop_q`, data-quality flags) and the portal surfaces it. The **pipeline never branches**. One schema, one ingest, one store, one scoring service, one portal.

When both are present on one vehicle, the hardware is authoritative for telemetry and the app becomes the driver's screen — but that is a per-vehicle runtime state, not a different system.

**Producer collision — the one place "the pipeline never branches" is currently untrue by omission.** In this section's own steady state, phone and hardware coexist on one bus. Each would generate its own `trip_id`, so the server sees **two trips for one journey** and the portal double-counts. Rules needed before the schema freezes:

- **Paired mode: the hardware owns `trip_id`.** The app receives it over BLE and does not start its own trip. The app stops its own sensors and becomes a display + relay.
- **BLE drops mid-trip → the app resumes its own sensors under the *same* `trip_id`**, setting `producer = PHONE` on its batches. One journey, one trip, mixed producers within it. The server must accept a `trip_id` whose batches change producer partway through — so `producer` belongs on the *batch*, not the trip.
- **Server arbitration:** at most one active trip per `vehicle_id`. A second producer opening a trip on a vehicle that already has one active gets reconciled into it, not accepted as new.
- **Unpaired hardware + unpaired app on the same bus** (driver forgot to pair) is the messy case: two trips, same vehicle, overlapping time. Detect and merge server-side, flag for review. Do not silently pick one.

**Score comparability across producers is asserted above, not designed.** A rigidly-mounted hardware IMU and a phone sliding on a dash have systematically different false-positive rates — so the same driver's score would shift when hardware arrives, with identical driving. Until per-producer calibration exists, **score only on producer-invariant metrics** (speed, variance, duration, exposure), and treat IMU-derived events as context. Revisit once there is paired data from both producers on the same trips — which paired mode conveniently generates.

### 0.3 Where computation lives

Split by bandwidth, not by convenience:

```
ON DEVICE (permanent — both packages):
  sensors → detect events → seal batches → outbox
                │
                └─ raw IMU snippet ±5s around each event

ON SERVER (from day 1):
  points + events + snippets → scoring → portal
```

**Detection must stay on the edge.** Worth being precise about *why*, because the obvious objection — "4 MB/hour is only ~9.5 kbps, EDGE handles that" — is correct as far as it goes. Sustained bandwidth is **not** the binding constraint. Three others are:

| | Events only | Raw 50 Hz IMU |
|---|---|---|
| Sustained rate | ~55 KB/h (0.12 kbps) | 4.3 MB/h (9.5 kbps) — *both fine on EDGE as a burst* |
| Buffer, 12-min gap | 11 KB | 0.9 MB |
| Buffer, 4-hour rural stretch | 220 KB | 17 MB |
| **Per bus/month (8h × 26d)** | **~11 MB** | **894 MB** |
| **Drain a 4 h backlog on EDGE** | **~45 s** | **59 min** |
| 500 buses/month, self-hosted | 5.5 GB | 0.44 TB |

(Measured: raw IMU gzips only to ~78% — float sensor noise has incompressible low-order mantissa bits. 4.3 MB/h is the *compressed* figure.)

**Burst vs. sustained.** 9.5 kbps is trivial as a burst, but as a *sustained per-bus* load it consumes a large share of a real shared EDGE cell — and several buses in one rural cell is the normal case, not the exception. The rate argument is weak for one bus and much less weak for a fleet.

1. ~~**Buffer.**~~ **Withdrawn.** An earlier draft argued a 12-min gap (0.9 MB) overflows the ~90–110 KB embedded SRAM outbox. That anchored on internal SRAM when external storage is the obvious answer: a **16 GB SD card (~$3) buffers 159 days** of raw IMU. Not a constraint.
2. ~~**Storage.**~~ **Also withdrawn — the arithmetic was wrong twice, and fixing it removes the argument.**

   ⚠️ Two rounds of unit errors in earlier drafts: (a) "500 buses = 436 TB/month" was off by 1000× (MB→GB divided once, then labelled TB); (b) the replacement "48 h window ≈ 140 GB" conflated 48 *clock* hours with 48 *driving* hours. Recomputed cleanly:

   | | Correct |
   |---|---|
   | 500 buses × 8 h/day × 26 d, raw | **437 GB/month** (0.43 TB) |
   | 48 h rolling hot window, 50 buses (= 16 driving-h each) | **3.4 GB** |
   | 6 h rolling hot window, 50 buses | **1.3 GB** |
   | Permanent, after process-then-drop | ~0.3 GB/month at 50 buses |

   A 3.4 GB hot window is **trivially provisionable** — it fits on a laptop. Storage is not an argument against raw IMU; it was arithmetic noise. Stated plainly because this document's authority rests on its measured numbers, and it got these wrong twice.
3. **Uplink — this is the binding constraint.** Not the sustained rate (9.5 kbps is fine), but **backlog drain time**:

   | | Events only | Raw IMU |
   |---|---|---|
   | Per bus/month over cellular | 6 MB | **894 MB** |
   | Drain a 4 h dead-zone backlog on EDGE (~40 kbps) | **24 s** | **59 min** |

   A bus leaving a dead zone needs ~an hour of continuous uplink to catch up.

   ⚠️ **Precision, because an earlier draft overstated this.** It said "the backlog never converges; it grows." That is wrong in general: raw IMU is produced at ~1.2 KB/s while driving and EDGE drains at ~4.9 KB/s, so with **continuous** coverage the backlog converges at ~4× drain rate. Non-convergence holds only below roughly a **25% coverage duty cycle** — plus trip-end unplugging, which truncates drain time regardless.

   That condition is plausible on exactly the rural routes this system exists for, so the concern is real — but state it as *"on low-coverage routes"*, not *"never."* The first engineer who does the division will otherwise discount the whole section.

   **The decisive leg is cost, not convergence:** ~900 MB/month on a volunteer's prepaid SIM, versus ~11 MB events-only. That holds on every route.

**LoRa is a different regime entirely — and it constrains Phase 2 far harder than cellular constrains Phase 1.** Measured at SF10, 1% duty cycle: **~2 KB/hour of payload per device.**

| Payload | Over LoRa |
|---|---|
| Position beacon (12 B: lat/lon/speed/status) | **Yes** — 176 packets/hour available |
| Crash alert (12 B, event-driven) | **Yes** — the killer app |
| Events-only telemetry (~30 KB/h) | No — **15× over** |
| Raw IMU (4.3 MB/h) | No — **2130× over** |

So **LoRa is not a fallback transport for telemetry.** It cannot carry even the events-only stream. It is a separate, much narrower channel with a different job: *"I am alive, here, and this is my status"* — plus `POSSIBLE_CRASH`. Everything else waits for cellular. That still solves the original plan's "missing vehicle" problem and makes §6 accident-ahead alerts work in dead zones, which is exactly where crashes are least survivable.

**Design consequences to lock in now:**
- The LoRa path needs its **own tiny message type** (~12 B fixed), not a downsampled `TelemetryBatch`. Do not try to make one format serve both.
- **LoRa is a shared medium.** At 1 packet/min, 50 buses = 3,000 packets/hour through one gateway; ALOHA collisions degrade badly above ~1,000–2,000. Beacon interval must **scale with local fleet density**, not be a fixed constant, and repeater siting depends on bus density as much as coverage holes.
- A LoRa beacon is a **liveness signal, not a position history**. The full trip still arrives later over cellular via store-and-forward. The two channels are complementary, never redundant.

**Conclusion — narrower than earlier drafts claimed.** Detection stays on the edge because *cellular uplink* can't carry raw IMU, not because devices can't store it or servers can't process it. That distinction matters, because it opens two things earlier drafts foreclosed:

- **Raise the snippet cap.** §2.7's ~5/trip was set against the withdrawn buffer fear. With SD storage and process-then-drop, **20–30 snippets/trip** (240–360 KB) is affordable — still ~40× under streaming.
- **Retain full raw locally, upload selectively.** Embedded with an SD card can keep *complete* raw IMU on-device for crash investigation via physical retrieval, while cellular carries only events + capped snippets.
- **Depot WiFi changes the pilot entirely.** If buses return to a yard nightly, raw IMU drains at **zero cellular cost** — making near-complete raw capture viable exactly when it's most valuable for threshold tuning. **Open question for the Ministry: do these buses return to a depot?** If yes, build the WiFi sync path in Phase 1.

**Scoring belongs on the server from day one** — earlier than v0.3 implied. Scoring is *policy*, and policy changes repeatedly as the pilot teaches you things. On-device, every change is an app update into a 12-month tail; server-side, it is recomputable across all history immediately. It is also less work, not more. The device emits *observations*; the server decides what they are *worth*.

This also resolves the recompute contradiction found in review more cleanly than v0.3's patch: scoring is fully recomputable because it lives on the server, and the ±5 s IMU snippets allow *detection* thresholds to be retuned retroactively — **though only downward for false positives**, since a missed event leaves no data behind (§2.7).

---

## 1. Trip Lifecycle

The trip is the only unit of collection. Nothing happens outside one.

```
   IDLE ──[driver taps "بدء الرحلة" / Start Trip]──> ACTIVE
     ^                                                 |
     |                                                 | (network gone)
     |                                                 v
     |                                              ACTIVE (pending sync)
     |                                                 |
     |                                                 | (network back — backfill drains)
     |                                                 v
     └──[driver taps "إنهاء الرحلة" / End Trip]──── ACTIVE
                        |
                        v
                    CLOSING ──(outbox fully drained)──> SETTLED
```

**Rules:**

- `IDLE`: zero sensor subscriptions, zero location requests, zero stored points, zero network. The app is inert. This is a privacy guarantee, not just a battery one — and we should say so plainly in the app's Arabic onboarding, because it is the thing that makes voluntary adoption credible.
- `ACTIVE`: foreground service running, notification visible and honest ("رحلة جارية — يتم تسجيل بيانات السلامة" / Trip in progress — safety data is being recorded).
- **`pending` is not an error state.** It's a UI label on an active trip whose outbox is non-empty. The driver sees a small cloud-with-slash icon and a count ("١٢ دقيقة غير مُرسَلة"). No warning colors, no nagging. It resolves itself.
- `CLOSING`: trip is over, but the outbox still has data. The service stays alive on a relaxed duty cycle (network-triggered only, no sensors) until drained, then stops. If the driver's phone dies here, `WorkManager` resumes the drain on next app launch — the trip is not lost.
- **Auto-end safety net:** if a trip has been `ACTIVE` with speed < 5 km/h for 45 minutes, prompt the driver ("هل انتهت الرحلة؟"). If no response in 10 min, auto-close. Drivers forget to end trips; an unclosed trip that logs a parked bus overnight is both a battery liability and a privacy violation.

**The states the v0.1 draft was missing.** On this device population, process death is routine — MIUI kills, OOM on a 2 GB phone, reboots, and the driver swiping the app away. Those produce trips that are neither active nor cleanly ended, and without explicit states they become silent data loss:

```
ACTIVE ──[process dies: OEM kill / OOM / reboot / force-swipe]──> ORPHANED
                                                                     │
   on next app launch, read persisted trip state:                    │
     ├─ gap < 15 min and vehicle near last fix ──► RESUMED (same trip_id, seq continues)
     └─ otherwise ──────────────────────────────► CLOSED_INCOMPLETE (auto-ended at last fix)
                                                        │
                                                        ▼
                                                    outbox still drains — data is NOT lost
```

Three rules make this work:

1. **Trip state is persisted to Room on every state change and every batch seal**, never held only in service memory. Room is the source of truth; the service is a cache of it.
2. **`ORPHANED` still drains its outbox.** A trip that ended badly must still deliver everything it recorded — that's the whole point of store-and-forward, and a crash is exactly the scenario where the process dies *and* the data matters most.
3. **`RESUMED` continues the same `trip_id` and `seq` counter.** A reboot mid-route is one trip, not two. `boot_id` (§2.6) is what lets the server reconcile the monotonic clock discontinuity across the restart.

**`seq` allocation must survive process death.** Allocate `seq` at *seal* time, persisted in the same Room transaction that writes the batch. Never derive it from an in-memory counter — a force-kill would replay a sequence number against different content, silently corrupting the record via the dedupe path (§2.5).

**Boot receiver:** `RECEIVE_BOOT_COMPLETED` restarts nothing sensor-related, but does schedule an outbox drain. A phone that reboots at the depot should deliver yesterday's tail without the driver opening the app.

**Trip identity:** a client-generated UUIDv7 assigned at Start. Time-ordered, collision-free offline, and it becomes the idempotency key for every packet in that trip. The server never assigns trip IDs — that would require connectivity at trip start, which we cannot assume.

---

## 2. Wire Format — the 3G Budget

This is where the hardware-portability requirement bites hardest, so it gets designed first and most carefully.

### 2.1 Design targets

⚠️ **The gap assumption was wrong by ~20×.** Earlier drafts sized everything around "5–10 km coverage gaps (~12 min)". Field reporting on the Damascus–Deir ez-Zor corridor describes a **near-total absence of cellular service as soon as you leave the two cities**, across a ~200 km desert leg. Realistic worst case is a **150–180 km continuous dead zone ≈ 2.5–3 hours**, not 12 minutes. See §2.8.

| Constraint | Target |
|---|---|
| Sustained bandwidth on a bad 3G/EDGE link | ≤ 1 KB per minute of trip, compressed |
| A 10 km / ~12 min coverage gap backfill | ≤ 12 KB in one burst |
| **A 170 km / ~3 h desert gap backfill** | **~64 KB in one burst — the real design case** |
| Must survive re-encoding on a constrained MCU (~512 KB RAM) | No JSON, no dynamic allocation in the hot path |
| Must be forward-compatible | New fields never break old servers or old clients |

### 2.2 Format: Protobuf, delta-encoded, batched

JSON is disqualified — a single GPS point in JSON is ~120 bytes; we need ~10. Protobuf gives us varint packing, a schema shared verbatim between Kotlin and Rust (`prost`/`micropb` on embedded), and free forward-compatibility via field numbers.

The core trick is **delta encoding against a batch anchor**. Absolute coordinates cost 8 bytes each as fixed64. Deltas between points 1 second apart cost 1–2 bytes as zigzag varints, because a bus at 80 km/h moves ~22m, which is ~0.0002° — a small number.

```protobuf
syntax = "proto3";
package safesy.v1;

// One batch = one anchor + N deltas. This is the atomic upload unit.
message TelemetryBatch {
  bytes    trip_id      = 1;  // 16B UUIDv7
  uint32   seq          = 2;  // batch sequence within trip, 0-based, gapless
  Anchor   anchor       = 3;
  repeated Delta points = 4;  // typically 60 (one minute at 1 Hz)
  repeated Event events = 5;  // sparse — usually empty
  Producer producer     = 6;  // set once by the producer, never inferred server-side
}

message Anchor {
  uint64 t_ms   = 1;  // absolute epoch millis
  sint64 lat_e7 = 2;  // degrees * 1e7
  sint64 lon_e7 = 3;
}

// Every field is a delta from the PREVIOUS point (or the anchor, for the first).
message Delta {
  sint32 dt_ms   = 1;  // usually ~1000
  sint32 dlat_e7 = 2;
  sint32 dlon_e7 = 3;
  sint32 dspeed  = 4;  // cm/s
  sint32 dhead   = 5;  // decidegrees
  uint32 hdop_q  = 6;  // GPS quality, quantized 0-15. Cheap, and it lets the
                       // server distinguish "driver swerved" from "GPS jumped."
}

// Detected on-device. The point of edge detection is that a
// 12-minute coverage gap still yields the events, not just raw points.
message Event {
  enum Kind {
    KIND_UNSPECIFIED = 0;
    HARSH_BRAKE      = 1;
    HARSH_ACCEL      = 2;
    HARSH_CORNER     = 3;
    SPEEDING_ENTER   = 4;
    SPEEDING_EXIT    = 5;
    POSSIBLE_CRASH   = 6;
    TRIP_START       = 7;
    TRIP_END         = 8;
    COVERAGE_LOST    = 9;   // client-observed, feeds the dead-zone map
    COVERAGE_REGAIN  = 10;
  }
  Kind   kind      = 1;
  uint32 offset_ms = 2;  // from batch anchor
  uint32 severity  = 3;  // 0-1000, normalized per safesy-spec
  bytes  detail    = 4;  // optional packed floats: peak_g, duration, etc.
}

enum Producer {
  PRODUCER_UNSPECIFIED = 0;
  PHONE                = 1;  // permanent, first-class — never a legacy path (§0.2)
  HARDWARE             = 2;  // deliberately not "ESP32"; target undecided (§0.1)
}
```

**Measured cost per point** (byte-accurate protobuf encoding, realistic 1 Hz bus deltas with GPS jitter, gzip -9):

| Scenario | Raw | Gzipped | Per point |
|---|---|---|---|
| Highway, 60-point batch | 1063 B | 742 B | 12.4 B |
| Urban/curvy, 60-point batch | 1155 B | 846 B | 14.1 B |
| 12-min backfill (12 batches, one POST) | 12.5 KB | 6.5 KB | — |
| **1 hour, backfilled as one stream** | 62 KB | **29.5 KB** |
| **1 hour, live per-minute drain** | 62 KB | **~55 KB** (43.5 payload + ~12 HTTP/TLS) | — |

Why it's ~12 B/point and not the ~7 B a naive delta estimate suggests:

- Field tags cost 1 byte each; six fields = 6 bytes of pure overhead per point, before any data.
- Each `Delta` is a repeated embedded message, adding tag + length = 2 more bytes.
- `dt_ms ≈ 1000` needs a 2-byte varint (zigzag 2000 > 127), and real GPS timestamp jitter means it does not compress to nothing.
- At 80 km/h the position deltas are ~1995 (lat) and ~2435 (lon) in 1e-7 units — 2-byte zigzag varints each, and their low bits are noisy.

⚠️ **Two different numbers, and an earlier draft conflated them.** Gzipping a whole hour as one stream (~29.5 KB) is the **backfill** case. In normal operation each ~742 B batch is compressed and POSTed separately, so live tracking costs **~55 KB/hour** including HTTP/2 and TLS overhead — roughly **40% more** than the figure this section previously used to warn people about budgeting data plans.

**Budget on the live figure:** ~55 KB/h → ~440 KB/day at 8 h → **~11 MB/month per bus**, rising to ~13–15 MB once event snippets (§2.7) land. Backfill bursts are cheaper per byte (~6.5 KB per 12-minute gap) because a longer stream compresses better.

**Unanswered product question: whose SIM pays?** Even 15 MB/month is real money on Syrian prepaid, charged to a driver who volunteered. Ministry reimbursement, a data bundle, or carrier zero-rating needs an answer before the pilot — this is a quiet adoption killer, not a line item.

**If that needs to come down**, the levers in order of value: drop `dt_ms` in favor of a fixed cadence with an explicit gap marker (saves 3 B/point ≈ 25%); quantize `hdop_q` into spare bits of another field (2 B); pack the whole `Delta` array as a hand-rolled fixed-layout `bytes` blob instead of a repeated message (saves all tag+length overhead, ~8 B/point → roughly 5 B/point, at the cost of forfeiting protobuf's forward-compatibility for that field). I would not do the third until measurements prove it necessary — it trades away the exact property that makes the format portable.

**Sampling rate:** 1 Hz for position is right for road safety — a bus does not change meaningfully faster. The IMU runs much faster (50 Hz) but *never ships raw*; the detection engine consumes it and emits only `Event`s. This is the single biggest bandwidth win in the design, and it's also what makes ESP32 parity feasible.

### 2.3 Adaptive degradation

When the link is bad, degrade *resolution*, not *coverage*. But the naive version of this **breaks the idempotency scheme**, so the mechanism matters:

> ⚠️ **The trap.** If "downsample for transmission" means *re-encoding batch `seq=7` with 12 points now and the full 60 later*, then `(trip_id, seq)` no longer identifies fixed content. The server dedupes on that key, sees `seq=7` already present, and **discards the full-resolution version forever**. Degradation and idempotency are in direct conflict unless the batch is immutable.

✅ **RESOLVED BY REQUIREMENT — the preview lane is deleted (v0.5).** The liveness requirement is now stated: *"live is OK within 5 minutes when coverage exists; best-effort otherwise."* Sealed 60 s batches already deliver **~65 s staleness** — 4.6× better than required. The preview lane existed only to fill sub-minute gaps between batches, which is now out of scope.

Deleting it removes, at zero cost to the requirement:
- a second message type from `safesy-proto`,
- the **dual-writer hazard to the Redis live cache** (a backfilled batch racing a fresh preview could make a bus jump backward on the map — flagged in §8b, now structurally impossible),
- a degradation-lane code path from **both** client packages.

**Sealed batches are the single writer to everything.** One subsystem and one class of bug, gone.

**Batch interval stays at 60 s.** Larger batches are cheaper (300 s → 8.1 MB/mo vs 10.9), but 5-minute batches sit exactly at the requirement boundary with no margin — one failed upload doubles staleness to 10 minutes. 60 s gives 5× headroom for ~2.8 MB/month, which is noise. `POSSIBLE_CRASH` still seals immediately regardless (§4), so the crash-alert path is unaffected.

**The portal must display staleness honestly.** A bus in the Deir ez-Zor gap (§2.6b) sits at a stale position for hours. If the map renders it identically to a live bus, an operator will believe a 3-hour-old pin — a safety hazard created by the UI, not the network. Every vehicle pin carries its last-update age; anything beyond ~5 minutes renders visibly differently (greyed, `آخر تحديث منذ ٢٤ دقيقة`). This is §1's `pending` concept surfaced on the Ministry side.

---

*Historical note — the superseded design, retained because the trap it avoids is instructive:*

**Resolution: batches are immutable once sealed, and low-resolution data travels in a separate lane.**

```
Sealed batch (seq=7, 60 points)  ──────────────► outbox   [authoritative, always sent eventually]
        │
        └─ derived 12-point summary ──────────► preview lane  [lossy, disposable, no seq]
```

| Link quality | Behavior |
|---|---|
| Good (LTE/3G, RTT < 500 ms) | Sealed batches drain in order. No preview lane. |
| Poor (EDGE, RTT > 2 s, or 2+ consecutive failures) | Sealed batches keep draining slowly in the background. Additionally a `PositionPreview` (~32 B: trip_id + timestamp + one absolute fix + status flags — **no score**, the device no longer computes one, §0.3) is sent every 60 s so the Ministry map stays live. |
| None | Pure store-and-forward. Outbox grows. |

`PositionPreview` is a **distinct message type with no `seq`**, written to a separate Redis-backed live-position cache, never to the telemetry store. It is explicitly disposable: if it is lost, nothing is lost, because the authoritative batch is still queued. This keeps exactly one writer to the durable record.

The local database is always full-resolution and every sealed batch is delivered eventually. Nothing authoritative is ever discarded for bandwidth reasons — crash reconstruction needs the fine-grained data, and it is precisely during a bad link (remote highway) that a crash is most likely.

### 2.4 Transport

**HTTP/2 POST with protobuf bodies + gzip.** Not MQTT for v1.

I'd flagged MQTT earlier; on reflection HTTP is the better v1 call: it traverses Syrian carrier NAT and middleboxes more reliably, needs no persistent connection (which is a battery and reconnect-storm liability on flaky links), and store-and-forward means we don't need push semantics. MQTT becomes genuinely right in Phase 2 when the ESP32 needs a persistent low-overhead uplink — and because the payload is protobuf either way, that's a transport swap, not a format change.

`POST /v1/ingest` accepts an array of batches so a backfill drains in one round trip.

### 2.5 Idempotency

`(trip_id, seq)` is the primary key. Re-uploads are no-ops. This is essential — flaky networks mean the client will often not know whether a POST succeeded, and its only safe move is to retry.

The server must accept **out-of-order and late** batches indefinitely (well, 30 days). A bus backfilling a Deir ez-Zor gap will deliver `seq` 40–52 after other buses have delivered much newer data. Any design that assumes monotonic arrival breaks in week one.

### 2.6 Time: never trust the phone's wall clock

The v0.1 draft used client `t_ms` epoch timestamps throughout. That is unsafe, and on this exact device population it will bite:

- A cheap Android phone that boots with no SIM and no network has **no valid wall clock** until NITZ or NTP lands. A trip can legitimately start at epoch 1970 or at whatever the RTC drifted to.
- Users manually change the clock. Timezone handling on grey-market Tecno/Infinix firmware is inconsistent.
- If wall-clock time jumps mid-trip (NTP correction lands at minute 12), naive `dt_ms` deltas produce a negative or hour-long gap, and every downstream speed/accel calculation derived from them is garbage.

**Fix — three independent clocks, all carried:**

```protobuf
message Anchor {
  uint64 t_ms         = 1;  // best-effort wall clock, ADVISORY ONLY
  uint64 mono_ms      = 4;  // SystemClock.elapsedRealtime() — monotonic, survives sleep
  uint64 boot_id_lo   = 5;  // random per-boot; mono_ms only comparable within one boot_id
  uint64 gnss_t_ms    = 6;  // GPS time when a fix is available — authoritative when present
  sint64 lat_e7       = 2;
  sint64 lon_e7       = 3;
}
```

- **All intra-trip duration math uses `mono_ms`.** It cannot jump, cannot go backward, and is unaffected by the user or NTP.
- **`gnss_t_ms` is the authority for absolute time.** GPS time is atomic-clock-derived and we have a GPS receiver running anyway — this is free and correct. The server reconciles `mono_ms` → absolute using the first GNSS fix in the trip.

  ⚠️ **Android specifics — the API makes this easy to get wrong.** `Location.getTime()` is only satellite-derived **when the fix came from `GPS_PROVIDER`**. Fixes from other providers (network, fused) commonly return the *device system clock* instead — the untrusted one. Since §8b already mandates `LocationManager.GPS_PROVIDER` as a fallback for GMS-free devices, use that same path as the authoritative time source, and:
  - **Record which provider produced each fix.** Only populate `gnss_t_ms` when the fix is genuinely `GPS_PROVIDER`; leave it unset otherwise. A network-provider timestamp silently masquerading as GPS time is worse than no timestamp.
  - **Use `Location.getElapsedRealtimeNanos()` for ordering**, never `getTime()`. It is monotonic, survives deep sleep, and is directly comparable to `SystemClock.elapsedRealtimeNanos()` — this is the documented correct way to order fixes.
  - Treat `getTime()` as advisory for display only.
- **`boot_id`** distinguishes "monotonic clock reset because the phone rebooted" from "clock went backward, data is corrupt." A reboot mid-trip is a normal event on these devices; without `boot_id` it is indistinguishable from corruption.
- The server stamps its own `received_at` and records `clock_skew = server_time - client_t_ms` per batch. Large skew is a data-quality flag, not a rejection — never drop a trip because a phone's clock is wrong.

This also matters for **§6 accident-ahead alerts**, where "is this crash report 30 seconds old or 3 hours old?" is the entire question. Advisory wall-clock time cannot answer it; GNSS time can.

**The fix-less cases — which v0.1 left undefined:**

- **No GNSS fix for an entire trip** (dead antenna, underground depot start, deep urban canyon). Absolute time then rests on an advisory wall clock that may read 1970, bounded above only by server `received_at` — and for a backfilled trip that bound can be hours wrong. **Consequence: §3.3's "High confidence" rating for night exposure and driving duration is conditional on having a GNSS fix.** Both are downgraded to Medium when `gnss_t_ms` is absent for the trip, and the portal must show that, not silently score it.
- **Anchor before first fix.** Proto3 defaults would silently emit `(0,0)` — a valid-looking coordinate off the coast of Africa. Add an explicit `bool has_fix` to `Anchor`; batches without a fix carry timing and IMU events only, and the server must never plot them.
- **RESUMED's "gap < 15 min" branch runs on which clock?** `elapsedRealtime` has just reset (that's what `boot_id` detects) and wall clock is untrusted by this section's own argument. Resolution: prefer `gnss_t_ms` from the last pre-reboot batch vs. the first post-reboot fix; if either is missing, fall back to wall-clock delta **with a wide tolerance and a data-quality flag** — and bias toward `CLOSED_INCOMPLETE`, because wrongly splitting one trip into two is far cheaper than wrongly merging two trips.
- **The RESUMED "vehicle near last fix" check needs a location fix while no trip is active**, which breaches §1's `IDLE` "zero location requests" guarantee. Carve it out explicitly: a single one-shot fix at app launch, only when an `ORPHANED` trip exists, disclosed in onboarding. Do not let this quietly become background location.

### 2.6b Real coverage gaps — the Damascus–Deir ez-Zor corridor

The store-and-forward design must be sized against actual Syrian road coverage, not a guess. What is documented:

**Route.** Damascus → Deir ez-Zor is **~456 km** by road (~5 h by car, longer by bus), running Damascus → Palmyra (Tadmur) → Deir ez-Zor. The **Palmyra → Deir ez-Zor leg alone is ~200 km** through open Badiya desert.

**Coverage.** Enab Baladi's May 2025 field reporting on this road states that a *near-total lack of cellular networks isolates travellers as soon as they leave the two cities* — travellers cannot call for help after a breakdown or accident. The same reporting describes the road as narrow, single-lane each way, heavily potholed, and a frequent accident site. Syria's Minister of Communications announced in **May 2025** that teams were commissioned to *begin installing* Syriatel and MTN towers along the Deir ez-Zor–Damascus road, with explicit concern about protecting them from sabotage and theft — an announcement that only makes sense if coverage was largely absent. Syriatel added three towers on the Deir ez-Zor–Al-Bukamal highway (a different road) later that year.

**Settlement pattern — towers follow people, so this is the best available proxy.** The corridor is not empty: **al-Sukhnah** sits roughly midway on the desert leg.

| Leg | Distance | Cumulative from Damascus |
|---|---|---|
| Damascus → Palmyra (Tadmur) | 215 km | 215 km |
| Palmyra → **al-Sukhnah** | 130 km | 345 km |
| al-Sukhnah → Deir ez-Zor | ~65 km | ~410 km |

**Engineering estimate — two bounds, because al-Sukhnah's tower status is unknown:**

| Scenario | Gap profile | Worst gap | Outbox | Backfill |
|---|---|---|---|---|
| **Optimistic** — al-Sukhnah has working coverage | Two gaps: ~100 km + ~35 km | **~105 km (~1.8 h)** | 76 KB | 40 KB |
| **Pessimistic** — al-Sukhnah dark | One continuous gap | **~170 km (~2.8 h)** | 123 KB | 64 KB |

**Which bound applies is genuinely uncertain, and leans pessimistic.** Al-Sukhnah (pre-war population ~15–20k) would normally have a tower — but it was heavily contested and damaged between 2015 and 2017, and a tower that existed pre-war is not a tower operating in 2026. The May 2025 ministerial announcement to *begin* installing towers **on this exact road** implies coverage was still largely absent as of then.

**Independent corroboration — UN OpenCelliD data, queried directly.** A free, unauthenticated UN-hosted feature service publishes crowdsourced cell records for Syria. Verified counts (national total 15,414, matching the service's own figure):

| Region | Recorded cells |
|---|---|
| Damascus–Homs (M5 corridor) | **7,373** |
| Entire Palmyra → Deir ez-Zor desert band | **5** |
| **LTE cells in the corridor** | **0** (vs **402** nationally) |

0.3° longitude slices across the desert leg show two multi-slice voids (38.9–39.5°E and 40.1–40.4°E), each ~30 km wide, with sparse 2G-only records between.

⚠️ **This data cannot be read naively — and the tell is decisive.** **Deir ez-Zor city itself returns 0 cells**, while being a functioning provincial capital with working service. The dataset measures *where people with the app drove*, not where coverage exists; ~98% of Syrian records sit west of 38°E and most predate 2018. **Absence of records is not absence of coverage.**

It is still useful for two narrower claims where sampling bias doesn't invalidate the inference:
- **Relative corridor ranking** — a 1,475× density difference between the M5 and the desert leg is not explained by sampling alone.
- **Technology generation** — every corridor record is 2G, created 2015. If anyone had *seen* LTE there it would be recorded; zero LTE against 402 nationally is about what was observed, not how much.

**Design either way:** size the outbox for the pessimistic case. The phone handles it trivially; embedded needs flash regardless (76 KB is already uncomfortably close to the ~90–110 KB SRAM budget, and 123 KB exceeds it outright).

**Four operational consequences beyond gap length:**

1. **Assume 2G/GPRS-class bandwidth at reconnection, not 3G/LTE.** Zero LTE recorded corridor-wide. Uploads must be small, chunked, and **resumable across session loss** — a 64 KB backfill burst that must restart from zero on a dropped connection is a different problem than one that resumes.
2. **Reconnection windows are brief** — minutes while passing a town. **Prioritise compact status + critical events ahead of bulk backlog**, so the most valuable data crosses first if the window closes early. The outbox drain order matters, not just its existence.
3. **Expect flapping** attach/detach at cell edges. Debounce connection state; one successful attach is not a stable window, and treating it as one will produce partial uploads and retry storms.
4. **Coverage is time-varying, not just position-varying.** Desert sites are generator- or solar-dependent, and signal follows the electricity schedule. Cable and equipment theft from remote towers is documented and recurring. **Never cache a "known good" coverage zone as permanently reliable** — the dead-zone map (§5) must carry timestamps and decay, not assert permanence.

**Consequences — the design survives, with three amendments:**

1. **The phone is fine.** 123 KB of sealed batches is nothing for Room/SQLite, and a 64 KB backfill burst uploads in seconds on any link. Store-and-forward was the right architecture and it scales to gaps 20× longer than assumed. **No change needed.**
2. **Embedded is not fine — this independently confirms the flash-backed outbox.** 123 KB exceeds the ~90–110 KB of SRAM left after BLE and TLS (§3.2b). A second, unrelated line of reasoning arrives at the same mandatory requirement.
3. **§6 accident-ahead alerts are inoperative for ~3 hours of this route** — the single most dangerous stretch, on a road documented as a frequent accident site. This is not a limitation to note in passing; it is the **strongest argument in the whole document for the Phase-2 LoRa mesh**, whose 12-byte beacon and crash alert (§0.3) are precisely what this corridor lacks and precisely what fits.

**Caveat on the numbers.** The 150–180 km figure is inference from qualitative field reporting plus settlement geography, not from a measured drive test. Absence of crowdsourced data (nPerf, OpenSignal) on this corridor is *not* evidence of absence of coverage — it more likely means few people with those apps drive it. **The pilot's coverage map (§5) will produce the first real measurement of this**, which is itself a deliverable worth having.

### 2.6c Network trajectory 2026–2027 — two items that change planning

**1. Zain won a 20-year license (30 June 2026)** — $747M bid, >$1.5bn committed, taking over MTN's ~6.3M customers, commercial launch targeted **Q1 2027**. Early phases target **">98% population coverage"** with standalone 5G.

⚠️ **Do not read that as coverage of this corridor.** It is a *population* target and forward-looking. Population coverage systematically overstates area coverage here — MTN's own pre-war figures were 99.5% population vs **80% geographic**, a 20-point gap in an *undamaged* network. In the Badiya the divergence is far larger. **A >98% population target is fully compatible with the desert corridor staying dark**, because almost nobody lives there.

**2. Planned 2G/3G retirement is a real risk to this design.** Every cell recorded in the corridor is **GSM-era 2G** — precisely the generation slated for shutdown. If the few working desert sites are retired before LTE replaces them, **corridor coverage could get worse before it gets better.** Concretely: do not build anything that depends on GSM fallback, and treat the dead-zone map as tracking a *moving* target.

**Sanctions note:** the Caesar Act was repealed 18 December 2025 and the Syria sanctions program revoked effective 1 July 2025 — but **BIS export controls on equipment remain**, which still matters for Phase-2 hardware procurement.

**Starlink is not a fallback.** No Syrian license as of mid-2026; terminals were confiscated in March 2025. Usage is gray-market. **Do not architect around it.**

**What would actually settle the gap question:** one instrumented drive logging signal state, cell ID, and RAT against GPS every few seconds. That single trip would beat every public source combined — and it is exactly what the pilot produces as a byproduct (§5). No public drive-test of this corridor exists; GSMA's operator-declared coverage rasters would settle it definitively but are paywalled, and operator self-reporting is systematically optimistic.

Sources: [Enab Baladi, May 2025](https://english.enabbaladi.net/archives/2025/05/deir-ezzor-palmyra-death-road-in-the-heart-of-the-desert/) · [ACCORD route research](https://www.ecoi.net/en/document/2136350.html) · [UN OpenCelliD Syria service](https://pro-ags2.dfs.un.org/arcgis/rest/services/Hosted/CellTowers_SYR_OpenCelliD_2023/FeatureServer/1) · [Zain Syria license](https://zain.com/en/press-release/zain-syria) · [DataReportal Digital 2026 Syria](https://datareportal.com/reports/digital-2026-syria) · [SMEX telecom sector](https://smex.org/syrias-telecom-sector-between-neglect-and-reconstruction/) · [Rome2Rio route data](https://www.rome2rio.com/s/Damascus/Deir-ez-Zor)

### 2.7 Fleet versioning — phones you cannot force-update

Drivers on cheap prepaid data will not update promptly, and there is no mechanism to compel them. Assume **a 12+ month tail of old clients in production**, permanently.

- **Proto:** additive-only forever. Never reuse or renumber a field. Never change a field's meaning. Reserve removed numbers explicitly (`reserved 7;`). This is protobuf's native strength — the discipline just has to be enforced in review.
- **Every batch carries `core_version` and `app_version`.** Scores are stored tagged with the `core_version` that produced them.
- **Server-side recompute is a *partial* escape hatch — and v0.1 overstated it.** Because scoring runs server-side (§0.3) and 1 Hz points are retained, GPS-derived metrics (speed, variance, duration, night) can be recomputed against stored data. **But IMU-derived events cannot**, because §2.2 never ships the 50 Hz accelerometer trace and nothing persists it. If the harsh-corner threshold proves too low in the pilot, there is no stored input to re-detect against — false positives cannot be removed by replay, and missed events are gone permanently.

  **Fix: ship a raw IMU snippet with each event — but encode it properly.** Naive float32 at ±5 s / 50 Hz is 16 KB raw, **~12 KB gzipped** (float sensor noise only compresses to ~78% — random mantissa bits are incompressible by construction; an earlier draft's "~5 KB" was too optimistic).

  **Encoding matters more than rate.** ⚠️ A tempting shortcut — *"average the IMU over 0.5 s instead of 50 Hz"* — **breaks crash detection**. Averaging is a low-pass filter, and a crash impact lasts ~120 ms; averaged into 500 ms buckets a real 1.2 g impact reads as **0.17 g (14% of true peak)** and the 3 g threshold never fires. Measured:

  | Representation | Peak preserved | Crash detected? |
  |---|---|---|
  | Raw 50 Hz | 93% | **Yes** |
  | 10 Hz averaged | 69% | Yes |
  | 2 Hz (0.5 s averaging) | **14%** | **No** |

  (Note also: averaging into 0.5 s buckets yields 2 Hz, not 30 Hz — a 25× reduction, not 2×.)

  **Detection always runs at 50 Hz on-device.** What can shrink is the *snippet payload*:

  | Option | 10 s snippet | Use for |
  |---|---|---|
  | float32 @ 50 Hz | ~12 KB gz | — (wasteful; sensor noise floor is far above float32 precision) |
  | **int16 @ 50 Hz** | **~5 KB gz** | **`POSSIBLE_CRASH`** — full fidelity where reconstruction matters |
  | int16 @ 25 Hz | ~2.5 KB gz | — |
  | **Envelope: min/max/RMS per 100 ms** | **~1 KB gz** | **Routine harsh-brake/corner** — 12× smaller, **peaks preserved** |

  The envelope form is the version of "averaging" that works: storing min/max/RMS per window keeps the peak explicitly instead of smearing it. Waveform shape is lost; peak magnitude and timing survive — which is all threshold retuning needs.

  Events are sparse, so this stays affordable — but it must be **budgeted and capped**, not left open-ended:

  | Events/trip | Snippet cost | vs. ~30 KB/h baseline |
  |---|---|---|
  | 2 (typical good driver) | 24 KB | ~doubles a 1-hour trip |
  | 5 | 60 KB | 3× |
  | 20 (bad driver, or bad thresholds) | 240 KB | 9× — unacceptable |

  So: **hard cap of ~20-30 snippets per trip** (earlier drafts said 5, sized against a buffer fear since withdrawn — see §0.3), prioritised by severity. **`POSSIBLE_CRASH` snippets are exempt from the cap entirely** — a crash reconstruction must never lose its slot to earlier cornering events. Capped-out events ship without a snippet and are therefore not retroactively reviewable; acceptable, but stated. Config-gated so it can be throttled per device or disabled fleet-wide. Consider dropping to 25 Hz and float16 for snippets — halves the cost, and threshold retuning does not need full precision.

  This buys retroactive **false-positive removal** — not false-negative recovery, since snippets exist only around *detected* events and a missed event leaves no trace. Practical consequence: **bias initial thresholds low**, over-detect, and filter server-side, because that is the direction replay can fix. Plus real field data for the §8.1 replay harness and crash reconstruction evidence, at a bounded, known cost.

  Without that snippet, the honest statement is: **IMU thresholds are frozen per `core_version` and only fixable by app update.** Given the 12-month update tail, that is a poor position — which is why the snippet is worth its bandwidth.
- **Ingest must accept the oldest supported client indefinitely.** Version-gating ingest would silently drop the buses on the worst connections — precisely the ones the system exists to protect.
- A soft **"update available"** nudge in-app is fine. A hard block is not: a driver locked out mid-route is a safety regression.

---

## 3. The Detection Engine (`safesy-detect`)

Pure logic, no I/O. A function of `(sensor samples, vehicle profile, config) → events`. Implemented **twice** — Kotlin for Android, Rust for embedded — and held equivalent by the conformance suite (§0.1). Scoring is NOT here; it lives on the server (§0.3).

**Language: whatever is idiomatic for each package.** Per §0.1 there is no shared native library, so:

- **Android: pure Kotlin.** Sensor callbacks, coroutines, Room. No JNI, no `ByteBuffer` ring, no cross-thread visibility hazard — those existed only to serve a shared C++ core and are pure overhead once it's gone. Detection runs in a coroutine on a dedicated dispatcher, fed directly by `SensorEventListener`.
- **Embedded: Rust (`no_std`).** The case strengthened once framing logic joined the core: power-loss-mid-write flash queues, DMA sensor buffers, and tight memory are exactly what Rust's ownership model prevents bugs in and C++ does not. C++'s only edge was ESP-IDF maturity, which mattered when Android had to consume the same library through JNI. Without that constraint the tradeoff is clean — and by the time this is written, embedded Rust will be further along than it is today.

**The decision is deferred, not made.** Nothing in Phase 1 depends on it. Revisit when the hardware target is chosen (§9.1).

**What actually keeps them equivalent** is not shared source but the conformance suite: identical input fixtures must produce byte-identical batches from both packages. That is a stronger guarantee than shared code, because it tests observable behavior rather than assuming a common implementation is correct.

### 3.1 Interface

Spec pseudocode — each package implements this idiomatically (Kotlin coroutines on Android, `no_std` Rust on embedded). **No scoring here**; the engine emits observations only (§0.3).

```
ImuSample   { t_ms, ax, ay, az, gx, gy, gz }        // 50 Hz
GnssSample  { t_ms, lat, lon, speed_mps, heading_deg, hdop }   // 1 Hz

VehicleProfile {
  class            : BUS | MINIBUS | VAN
  harsh_brake_mps2 : BUS 2.5, MINIBUS 3.2
  harsh_accel_mps2 : BUS 2.0, MINIBUS 2.8
  harsh_corner_mps2: lateral; BUS 2.5, MINIBUS 3.0
  crash_g          : 3.0
  rollover_deg_s   : roll-rate threshold — buses are top-heavy
}

DetectionEngine:
  configure(VehicleProfile, DetectionConfig)   // thresholds only, NOT scoring policy
  on_imu(ImuSample)
  on_gnss(GnssSample)
  drain_events() -> [Event]                    // bounded, no unbounded allocation
```

Thresholds arrive from the server via the signed config channel (§8b), so tuning does not require an app update.

### 3.2 What it must handle that a naive version won't

- **Gravity separation.** Raw accelerometer includes 1g of gravity in an unknown direction, because the phone is mounted at whatever angle the driver felt like. Needs a complementary or Madgwick filter fusing accel + gyro to establish the vehicle frame, plus a calibration period at trip start (first 30s of steady driving) to learn the mounting orientation. **This is the single most under-appreciated source of false positives in phone telematics.** Get it wrong and every trip is full of phantom harsh-braking events.
- **Speed source fusion.** GPS speed is accurate but 1 Hz and laggy; integrated accelerometer is fast but drifts. Fuse them (a small Kalman filter) so braking events have both correct timing and correct magnitude.
- **GPS quality gating.** An `hdop` spike plus an implausible position jump must suppress event detection rather than emit a phantom `HARSH_CORNER`. Urban canyons in Damascus and Aleppo will do this constantly.
- **Vehicle-class thresholds.** As you said — a loaded bus and an empty minibus are different vehicles physically. Thresholds come from `VehicleProfile`, never hardcoded.

### 3.2b Embedded feasibility — checked on ESP32-S3 as a reference target

Worth checking that detection fits beside a modem stack. ESP32-S3 (512 KB SRAM, ~320 KB usable) as a reference — **the target is not yet chosen (§0.1)**, and this budget is part of why:

| Consumer | RAM |
|---|---|
| IMU ring (50 Hz × 2 s) | 3.2 KB |
| GNSS ring (1 Hz × 60 s) | 2.4 KB |
| Filter state (quaternion, bias, Kalman) | 0.5 KB |
| Event scratch | 0.5 KB |
| **Detection engine total** | **~6.5 KB** |
| lwIP + TCP/IP | 40 KB |
| mbedTLS (one session, before handshake peaks) | 32 KB |
| LTE modem driver + buffers | 16 KB |
| LoRa / Meshtastic stack | 24 KB |
| FreeRTOS tasks + stacks | 40 KB |
| **BLE (NimBLE) — required by §7 pairing** | **50–70 KB** |
| **Total** | **~210–230 KB of ~320 KB** |

Detection itself is comfortable — 6.5 KB is noise. **The pressure is entirely in the outbox**, and it is tighter than v0.1 claimed: after BLE (which v0.1 omitted despite §7 requiring it), only ~**90–110 KB** remains, buffering roughly **1.5 hours** of sealed batches in SRAM. The mbedTLS figure is also a floor, not a typical — 16 KB in + 16 KB out record buffers before handshake peaks; plan on negotiating Maximum Fragment Length.

**Compression does not port — verified, and it changes the protocol.** Every headline number in §2.2 assumes `gzip -9`. Measured:

| Encoder | Working memory | Highway batch | Per point |
|---|---|---|---|
| gzip, 32 KB window (`windowBits=15, memLevel=8`) | **256 KB** | 742 B | 12.4 B |
| deflate, 512 B window (`windowBits=9, memLevel=1`) | **3 KB** | 862 B | 14.4 B |

Full-window deflate needs more working memory than the ESP32's entire usable SRAM. The embedded-viable configuration costs **+16% bandwidth**. So the §2.2 budget is a *phone* budget presented as a *protocol* budget.

**Act on this now, not in Phase 2:** the ingest endpoint must accept `identity` and small-window deflate `Content-Encoding` from day one. That is a one-line server change today and a breaking protocol change later. Budget ESP32 traffic at ~14.4 B/point, not 12.4.

**Outbox must be flash-backed from day one** (SPI flash or SD, ring-structured with wear levelling and power-loss-safe writes). Room hides this on Android; the firmware needs a real persistent queue. Genuine new Phase-2 work — see the §7 table.

**Float math:** the ESP32-S3 has a single-precision FPU, so the Madgwick filter and Kalman fusion run natively. Keep the detection engine strictly `float`, never `double` — a stray `double` silently drops to software emulation and costs ~50× per operation. Enforce it with a build flag on the firmware target.

### 3.3 Scoring metrics

You listed speed, maneuvers, acceleration, braking. Those are the right core. Additions I'd argue for, in order of value-per-unit-effort:

1. **Speed *variance* on a road segment,** not just absolute speed. With no enforced limits, absolute speed is a weak signal — but a driver oscillating 60→100→60 is objectively more dangerous than one holding a steady 95. This is also the metric that works *today*, before any speed-limit map exists.
2. **Night driving exposure.** Unlit Syrian highways after dark are a large, well-documented risk multiplier. Cheap to compute (timestamp + coordinates → solar elevation), no extra sensors.
3. **Continuous driving duration.** Fatigue is a leading cause of intercity bus crashes. You already have trip duration; surfacing "4 hours without a break" is nearly free and is the kind of finding the Ministry can act on.
4. **Cornering speed vs. curve radius.** Derive radius from the heading-rate/speed relationship. Catches the specific failure mode that rolls a top-heavy bus. Moderate effort, high relevance.
5. **Rollover-risk proxy** — sustained lateral g combined with roll rate. Buses roll; cars usually don't. Worth having even if it only ever fires a handful of times.

Two I'd deliberately *skip*: tailgating (needs a forward camera or radar — no) and phone-handling detection (technically easy, but it turns the app from a safety companion into a surveillance tool, which directly undermines the voluntary-adoption story you're relying on).

**Honest accuracy limits — what a loosely-mounted phone can and cannot measure.** The scoring section should not overclaim, because the pilot will expose it:

| Metric | Confidence | Why |
|---|---|---|
| Speed, speed variance | **High** | GPS-derived, mount-independent |
| Night exposure, driving duration | **High** | Timestamps only |
| Harsh braking / acceleration | **Medium** | Longitudinal axis is recoverable from GPS speed derivative, so IMU error is correctable |
| Harsh cornering | **Medium-low** | Needs correct vehicle-frame orientation; a phone sliding on the dash mid-trip corrupts it |
| Rollover proxy | **Low** | Requires stable mounting; treat as an alerting hint, never a scoring input |

The dominant error source is **mount instability** — a phone in a loose cradle, or on the dash, re-orients during the trip and invalidates the calibration from §3.2. Mitigations: re-estimate the gravity vector continuously rather than once at trip start; detect sudden orientation change and emit a `MOUNT_SHIFTED` marker that suppresses IMU-derived events until re-converged; and lean on GPS-derived longitudinal acceleration, which needs no orientation at all, as the trustworthy fallback.

**Implication for the pilot:** score primarily on the high-confidence metrics (speed, variance, duration, night) and treat IMU events as secondary until the replay harness (§8.1) proves the false-positive rate on real Syrian roads. It is far better to ship a narrow score drivers trust than a rich score they can point at and call wrong — with voluntary adoption, one loudly-wrong harsh-braking flag costs more credibility than five missing ones.

### 3.4 Separate *behavior* from *exposure* — or the score is structurally unfair

The single most dangerous flaw in a naive implementation of §3.3, and it is a **scoring-service design requirement, not a tuning detail.**

Speed variance — the metric ranked #1 above — is heavily confounded by road condition. Oscillating 60→100→60 is what *every* driver does on a degraded, potholed, checkpoint-riddled route; it is not a choice. Night exposure has the same problem: intercity night buses are scheduled service, and the driver does not pick the departure time.

Score these naively and a driver on a Deir ez-Zor route is **structurally punished** relative to a Damascus–Homs driver for doing the same job well. Drivers will notice within one pilot week — they talk to each other, and they know which routes are worse. That is fatal twice over: it destroys voluntary adoption, and it means the score isn't measuring what it claims to.

**The fix, and it must be in the scoring service from the start:**

| Category | Treatment |
|---|---|
| **Behavior** — what the driver chose | Scored. **Normalized per road segment**: compare this driver against the distribution of all drivers on *the same segment*, not against a global constant. |
| **Exposure** — what the schedule imposed | **Reported as context, never penalized.** Night hours, continuous duration, route difficulty appear on the record as facts the Ministry can act on (rostering, rest rules) — not as marks against the driver. |

Segment normalization also solves the §9.2 speed-limit problem for free: with no enforced limits and no reliable `maxspeed` data, "faster than 85% of drivers on this exact segment" is both more meaningful and more defensible than any absolute threshold — and it requires no external dataset, just accumulated fleet data.

**Fatigue is the honest exception.** Continuous driving duration is exposure, not behavior, but it is also a genuine safety risk. Report it prominently, route it to the Ministry as a *rostering* finding, and keep it out of the driver's score — the driver rarely controls the schedule.

**Scoring runs server-side by design (§0.3).** A scoring-policy change is therefore recomputable across all historical trips rather than requiring an app update. Version every score with the `scoring_version` that produced it, and every batch with the `detect_version` that produced its events.

---

## 4. Android Client

Pure Kotlin (§0.1). Its job: acquire sensors, run detection, persist, drain the outbox, and render Arabic.

```
app/
├─ trip/          TripService (foreground), TripStateMachine
├─ sensors/       LocationManager (+FusedLocation when GMS present) + SensorManager
├─ storage/       Room: trips, points, events, outbox
├─ sync/          OutboxWorker (WorkManager), BatchEncoder, backoff policy
├─ ui/            Compose. Drive Mode + trip history + score
└─ detect/        Kotlin detection engine — conformance-tested vs. safesy-conformance
```

**Persistence — durability decoupled from sealing.** v0.1 said "points written in batches of 60 rather than one row per second." That was wrong, and dangerously so: it left up to 60 seconds of points in process memory, so an OOM kill, force-swipe, or the crash itself would destroy **the final minute before impact** — precisely the data the system exists to capture, and precisely what §6's crash confirmation needs.

The batching was solving a non-problem. One insert/second into WAL-mode SQLite is trivial on any device in this fleet (sub-millisecond, negligible battery). So:

- **Write each point to Room as it arrives** (1 Hz). This is the durability boundary.
- **Seal 60-point wire batches separately**, reading back from Room. Sealing is a transmission concern, not a durability one.
- **Seal immediately on `POSSIBLE_CRASH`**, without waiting for the 60-point boundary, and mark that batch for priority drain.

WAL on, `synchronous = NORMAL`. Retain raw points 7 days after `SETTLED`, then keep only events + score. A day of trips is a few MB.

**Force-stop caveat — and an honest correction to §1.** A MIUI/Realme force-swipe is effectively `force-stop`, which suspends WorkManager jobs *and* the boot receiver until the user manually launches the app again. So §1's promise that "a phone that reboots at the depot should deliver yesterday's tail without the driver opening the app" **fails on exactly the OEMs this document names as problematic**. Nothing recovers that automatically — the mitigation is a persistent, dismissible Arabic notification after an unclean shutdown ("بيانات رحلة لم تُرسَل — افتح التطبيق") prompting one tap. Design for the tap; do not claim it is automatic.

**Outbox drain:** `WorkManager` with `NetworkType.CONNECTED`, exponential backoff starting at 30s capped at 15 min. Additionally, an in-service `ConnectivityManager.NetworkCallback` triggers an immediate drain the moment connectivity returns — that's what makes the 5–10 km gap resolve promptly rather than on the next Worker tick.

**Drive Mode UI:** current speed, large. Trip elapsed. A sync indicator that's informational, never alarming. Everything else — score breakdown, history, event list — lives behind the trip-ended screen. Haptics and audio for warnings, never a visual that pulls the eyes off the road.

**Arabic/RTL specifics** (this is where RTL apps usually go wrong):
- `android:supportsRtl="true"`, and Compose handles mirroring via `LocalLayoutDirection` — but only if you use `start`/`end` padding everywhere and never `left`/`right`.
- **Numerals:** Syria uses Western Arabic numerals (0-9) in most transport/official contexts, not Eastern Arabic (٠-٩). Confirm with actual Ministry documents — getting this wrong looks immediately foreign to users.
- **Speed and units** are LTR runs inside RTL text. Wrap them in Unicode isolates (`⁨`…`⁩`) or the digits will visually reorder next to punctuation.
- Test on a device with Arabic as the *system* locale, not just an in-app language toggle. They behave differently.
- Ship a font with proper Arabic shaping — Noto Naskh Arabic. Do not trust OEM defaults on cheap devices; Tecno and Infinix ship inconsistent Arabic fonts.

**OEM background-kill:** MIUI/Realme/Oppo aggressively kill foreground services despite the API contract. Needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, a guided Arabic walkthrough for the per-OEM autostart settings, and a heartbeat that detects a killed service and warns the driver on next launch. Budget real time for this — it is the most common cause of "the app stopped working" reports in this device market.

---

## 5. Backend

```
Ingest (stateless, horizontally scalable)
   ↓ validate → dedupe on (trip_id, seq) → expand deltas → enqueue
Queue (NATS JetStream or Redis Streams)
   ↓
Processors
   ├─ persist points        → TimescaleDB hypertable
   ├─ persist events/scores → PostgreSQL
   ├─ live position cache   → Redis (Ministry map)
   └─ coverage-gap analysis → PostGIS (the dead-zone map)
   ↓
Ministry API (read) → portal
```

- **PostgreSQL + PostGIS + TimescaleDB.** PostGIS for geofencing and spatial queries; Timescale for the point stream with a continuous aggregate for map rendering (rendering 200 buses × a full day of 1 Hz points raw will not perform).
- **Self-hosted.** Sanctions make managed cloud a liability, and the Ministry will require data residency regardless. Plan for on-prem or a regional VPS, with Docker Compose for v1 — Kubernetes is not warranted at this scale.
- **Ingest must be dumb and fast.** Validate, dedupe, enqueue, return 200. All real work is async. A backfill burst from 50 buses reconnecting simultaneously after a regional outage is the load spike that matters, and it is bursty by nature.
- **Retention:** raw points 90 days, then downsample to 10 s resolution. **Events and scores are NOT kept forever** — see §8a: events carry coordinates, so location-bearing detail ages out at ~12 months into per-driver aggregates. Scores persist; coordinates don't. This keeps crash reconstruction viable for the window when anyone would actually ask, without building a permanent movement archive.

### 5.1 Coverage recording is a separate channel — not derived from telemetry

**Why it cannot be inferred from the telemetry stream.** The obvious approach — "look at which points arrived late and call that a gap" — fails in both directions:

- A gap in *arrival* can mean no coverage, a killed process, a dead battery, a driver ending the trip, or an app crash. Indistinguishable after the fact.
- Coverage can be present but unusable (attached but no data path, cell overloaded, backhaul down). Telemetry arriving late says nothing about *why*.
- The most valuable observation — **coverage existed here but was 2G-only, or signal was marginal** — leaves no trace at all in a stream that only records what got through.

So coverage gets its own recording line, sampled independently of whether anything is being transmitted:

```protobuf
// Sampled every ~30 s while a trip is ACTIVE, regardless of connectivity.
// Stored locally like any other data; ships with the normal outbox.
message RadioSample {
  uint32 offset_ms    = 1;   // from batch anchor
  Rat    rat          = 2;   // NONE | GPRS | EDGE | UMTS | HSPA | LTE | NR
  sint32 rssi_dbm     = 3;   // signal strength, or unset when detached
  uint32 cell_id      = 4;   // for dedup/verification; NOT for tracking
  uint32 mcc_mnc      = 5;   // which operator — Syriatel vs MTN differ by region
  bool   data_ok      = 6;   // attached AND a request actually succeeded
}
enum Rat { RAT_UNKNOWN = 0; NONE = 1; GPRS = 2; EDGE = 3; UMTS = 4; HSPA = 5; LTE = 6; NR = 7; }
```

**Cost: negligible.** ~12 B every 30 s = ~1.4 KB/hour, ~0.3 MB/month — about 2.5% of the total budget, for the dataset §2.6b establishes does not otherwise exist anywhere.

**Design notes:**
- **Record `NONE` samples explicitly.** A dead zone is data, not absence of data — this is exactly the distinction that makes the crowdsourced OpenCelliD data unusable (§2.6b: Deir ez-Zor city returns zero cells while having working service). An explicit "I was here, at this time, with no signal" is worth far more than an inferred blank.
- **`rat` matters as much as presence.** Given the planned 2G retirement (§2.6c), knowing a stretch is EDGE-only versus LTE is the difference between "covered" and "about to go dark."
- **Timestamp every sample with GNSS time** where available — coverage is time-varying via grid outages (§2.6b), so a sample without a trustworthy clock cannot distinguish "this cell is down at night" from "this cell is gone."
- **Privacy:** `cell_id` is retained only for the aggregate coverage map and must age out with location detail under §8a's retention rule. It must never become a second, finer-grained location trail — cell IDs are precisely that if kept per-driver.

**Independence is the point.** This channel keeps recording when the IMU snippet budget is exhausted, when detection is disabled, and when nothing is being transmitted. Per the stated constraint — *if the driver never reaches WiFi and the raw IMU is lost, that is acceptable; the coverage record is not.*

**Coverage dead-zone map — a genuinely valuable byproduct.** Every `COVERAGE_LOST`/`COVERAGE_REGAIN` pair plus the backfilled points between them is a measured, ground-truth observation of where cellular coverage fails. Aggregate into a PostGIS H3 grid and you have something no Syrian carrier currently publishes: an empirical national coverage map of the highway network, built for free by buses. That is Phase-2 LoRa node siting data, and it's independently useful to the Ministry and to the carriers. I'd surface it as a first-class portal feature, not a debug view. It's also, notably, a deliverable that requires no driver behavior change at all.

---

## 5b. Identity, Enrolment & Keys

The v0.1 draft had no authentication design at all — a significant omission, since the Ministry's stated verification model (driver matched to bus plate against Ministry records) is the thing that makes the whole trust story work.

**The Ministry registry is paper, not an API — confirmed.** This was flagged in review as a possible pilot blocker. It isn't:

1. Ministry provides a roster — CSV, spreadsheet, or typed from paper — of `(plate, driver name, national ID)`.
2. Import it into the backend. That import **is** the allowlist.
3. Driver enters plate + national ID; the app checks against the imported roster.
4. No match → a pending queue a Ministry clerk approves in the portal.

For a 10–20 bus pilot this is a spreadsheet and an afternoon, and it scales to a few thousand rows before it hurts. The only real design consequences are that the roster goes stale — so build a **re-import path** and a **"driver no longer in roster"** state — and both would be needed even with a live API. **Not a blocker; removed from the risk list.**

**Enrolment (once per device, requires connectivity):**

1. Driver enters plate + national ID.
2. Backend checks the imported roster (above). Mismatch → pending queue, not hard failure.
3. Device generates a keypair **in the Android Keystore** (`StrongBox` where available, TEE otherwise).
4. Server issues a long-lived **device credential** bound to `(vehicle_id, device_pubkey)`. **`driver_id` is carried per-trip, not in the credential** — one bus commonly has several drivers and one shared phone.
5. **Attestation is optional, not required.** Grey-market Tecno/Infinix on Android 8–9 frequently lack working hardware attestation and StrongBox is near-absent; requiring it would fail enrolment on exactly the target fleet. Attest when available, record the result as a quality signal, never gate on it.
6. **Correction to an earlier draft:** v0.2 claimed "a stolen phone yields no reusable credential without the screen lock." That is **wrong** — Keystore gates on unlock only with `setUserAuthenticationRequired(true)`, which conflicts with frictionless trip start, and many drivers have no lock screen at all. State it plainly: **possession of the phone is the credential.** Acceptable here, since fraud is out of scope and the Ministry verifies out-of-band — but it must not be misrepresented as stronger than it is.

**Lifecycle (was missing):** lost-phone revocation, replacement re-enrolment, factory-reset recovery. Each is a portal action against the credential, and each needs a screen.

**Per-request auth:** short-lived JWT from the device credential, refreshed opportunistically. Critically — **a stale token must never block ingest.** Buses reconnect after hours offline holding an expired token; the server accepts the batch on device-credential signature alone and issues a fresh token in the response. Rejecting backfill for token expiry would silently discard exactly the data from the worst-connected buses.

**Multi-driver reality:** one bus, several drivers, often one shared phone. §9.4 has to resolve to *something* before the schema is written. My recommendation: the **trip** carries `driver_id`, set by a lightweight driver selection at trip start; the *device* is bound to the vehicle. This scores drivers correctly, survives phone sharing, and costs one screen.

**Phase-2 forward compatibility:** hardware gets the same credential shape — a keypair in its secure element, `(vehicle_id, device_pubkey)` with `driver_id` per-trip. Nothing about the auth model is phone-specific. For LoRa, where a JWT does not fit in a 12-byte payload, the device credential becomes a short symmetric key ID + AES-CCM MAC, keyed off the same enrolment record.

**Key rotation:** per-device keys rotate independently, so a compromised device is revoked alone. The original plan's §5.3 proposal of Ministry-managed *shared* LoRa keys is a real weakness — one extracted device key would compromise the entire mesh. Per-device keys with a rotating network key is the right structure, and enrolment is where that gets established.

---

## 5c. Liveness Requirements & Bandwidth Budget

Stated requirement: **"Live is OK within 5 minutes when coverage exists; best-effort otherwise."** Everything below follows from that.

| Path | Target | Actual | Margin |
|---|---|---|---|
| Portal map staleness (covered) | ≤ 5 min | **~65 s** | 4.6× |
| Portal map staleness (dead zone) | best-effort | hours — **displayed as stale** | — |
| Crash → warning to following buses (covered) | as fast as possible | **~65–70 s** | — |
| Crash → warning (in a 170 km gap) | — | **never, until reconnect** | — |

**Crash alert latency breakdown** (covered areas):

| Stage | Time |
|---|---|
| Detection on-device (50 Hz, local) | < 1 s |
| Immediate seal on `POSSIBLE_CRASH` (§4) | ~0 s |
| **Server confirmation window** (speed → 0, no motion) | **60 s** ← dominant |
| Push to buses within 30 km | 1–5 s |

The 60 s confirmation window is the whole budget, and it is deliberate — it is what distinguishes a crash from a pothole (§5.5 of the original plan). Shortening it trades false-alarm rate for latency; do not tune it without measuring both.

**Final per-bus bandwidth budget** (8 h/day × 26 days = 208 driving-hours):

| Component | Per month |
|---|---|
| Telemetry: 1 Hz points + events, 60 s batches | 10.9 MB |
| Crash snippets (int16 @ 50 Hz, full fidelity) | ~0.1 MB |
| Routine snippets (envelope form) | ~0.4 MB |
| **Radio/coverage samples** (§5.1, ~12 B / 30 s) | **~0.3 MB** |
| ~~Preview lane~~ | ~~deleted, §2.3~~ |
| **Total** | **~11.7 MB/month** |

**2G is not a constraint at this size.** A 60 s batch is 742 B gzipped — 0.29 s to upload on slow GPRS (20 kbps), 0.10 s on EDGE. Monthly uplink time is **78 min on GPRS, 26 min on EDGE**: the radio is idle >99% of driving time. Even a 115 km contiguous backlog (83 KB) drains in **33 s on GPRS**. Compare the rejected raw-IMU design, which needed ~59 min of continuous uplink to clear a 4 h backlog — that 33 s vs 59 min gap is the whole justification for edge detection.

⚠️ **What actually costs time on 2G is session setup, not throughput.** GPRS attach is 2-5 s and a TLS handshake adds 1-3 s, so a 0.29 s upload can sit behind ~5 s of setup. **Reuse connections** (HTTP/2 keep-alive) rather than handshaking per batch, and make transfers **resumable** — the real failure mode at a cell edge is attaching, starting an upload, and dropping mid-transfer, repeatedly.

≈1% of a 1 GB prepaid bundle. For comparison, the rejected raw-IMU-streaming design was **894 MB/month** — but note that is *not* the same data at a lower rate: see §0.3 and §2.7. The saving comes from discarding ~99.4% of the accelerometer stream and keeping only detector-flagged windows, which is a real tradeoff, not free compression.

### 5c.1 What the driver sees — live speed, not live score

§9.5 asked whether the driver sees their own score in real time. Resolved: **live speed yes, live score no.**

- **Live speed and a within/over indicator** are what change behavior in the moment, and they are honest — the driver sees the same number the Ministry sees.
- **A live score invites gaming and pulls eyes off the road**, which contradicts Drive Mode's minimalism (§4). Show the full breakdown post-trip, with exactly the data the Ministry has. That symmetry is the trust argument.

⚠️ **"Law following" needs care.** Syrian highways have no enforced speed limits and no reliable `maxspeed` dataset (§9.2), which is why §3.4 scores segment-relative rather than absolute. So the live indicator is only a *legal* limit if the Ministry declares limits per road class. Otherwise it is an **advisory speed**, and must be labelled as such — presenting an advisory as a violation is exactly the kind of wrongness that costs credibility under voluntary adoption.

---

## 6. Accident-Ahead Alerts

You flagged this as a maybe. It's achievable in v1 and it's the feature that flips the app from *surveillance* to *service* in the driver's mind — which matters a lot given adoption is voluntary. I'd prioritize it higher than it appears in your list.

**Flow:** `POSSIBLE_CRASH` event ingested → server confirms (speed → 0, no motion for 60s, corroborating criteria from §5.5 of the plan) → identify buses within 30 km approaching along the same road segment → push alert.

**Delivery:** FCM where available; a poll on the existing ingest response as fallback (Google Play Services availability in Syria is not guaranteed, and piggybacking on a connection we're already making costs nothing).

**Driver-facing:** audio + haptic only. Arabic TTS: "حادث محتمل على بعد ٥ كيلومترات" — possible accident 5 km ahead. Never a visual requiring a look.

**Caveat to design around:** a crashed bus in a coverage dead zone cannot report until it reconnects, which may be after other buses have already passed. The feature helps on covered roads and is one of the strongest arguments for the Phase-2 LoRa mesh. Be honest about that limitation rather than overselling it.

---

## 7. What Phase 2 Costs, Given This Design

The test of the architecture is what breaks when hardware arrives:

| Component | Change |
|---|---|
| `safesy-proto` | None. Set `producer = HARDWARE`. |
| `safesy-detect` | Reimplemented in Rust, held equivalent by `safesy-conformance`. |
| Backend | Accept identity / small-window `Content-Encoding` (do this now, §3.2b). Otherwise none. |
| Ministry portal | Add a producer badge to the vehicle row. |
| Android app | Add BLE pairing; when paired, stop own sensors and relay hardware batches. |
| **New work** | Firmware (sensors, power, LTE/LoRa modems), flash-backed outbox, embedded compressor, provisioning, LoRa gateway → ingest bridge. |

**⚠️ This corrects the original project plan's LoRa framing.** That plan (§2) described "dual-network failover (LoRa vs. Cellular)", implying LoRa is a substitute path for the same telemetry. **It is not, and cannot be** — §0.3 measures ~2 KB/hour per device against ~55 KB/hour of events-only traffic, a 15× shortfall before raw data is even considered.

LoRa Phase-2 work is therefore **a second, much narrower channel**, not a fallback transport:

- Its own **~12-byte message type** (beacon + crash alert), not a downsampled `TelemetryBatch`.
- **Liveness semantics**, not position history — the full trip still arrives later over cellular via store-and-forward. Complementary, never redundant.
- **Beacon interval scales with local fleet density.** 50 buses at 1 packet/min ≈ 3,000 packets/hour through one gateway, and ALOHA-style collision loss degrades well before that. A fixed interval that works at 10 buses fails silently at 50.

**An honest correction.** The v0.1 draft claimed "nothing existing is rewritten." That was only true because it hid the hard parts in the "new work" row. Batch sealing, seq durability, delta encoding, and outbox policy were Android Kotlin; re-implementing them on firmware would have meant two independent implementations of the system's trickiest invariants — the same trap as trapping the wire format, one layer up.

With the §0.1 boundary redrawn, the claim becomes defensible: what remains genuinely new is **firmware, not logic** — drivers, power management, a flash queue behind a narrow port, and a compressor swap. The shared invariants stay in one place, enforced by golden-vector conformance tests.

That is the property to defend in every subsequent decision. Any time a rule about *when* or *how* data is framed starts living in Kotlin, the leak is back.

---

## 8. Recommended Build Order

0. **Write `safesy-proto` + `safesy-spec` + the first conformance fixtures.** The schema, a few pages of prose on sealing/seq/time, and golden vectors. Cheap now; expensive to retrofit once the app exists. This is what replaces the shared native library.
1. **Kotlin detection engine + replay harness.** Record real drives with a throwaway logging app, replay them offline, tune thresholds. Before any product UI — thresholds tuned against real Syrian roads and real bus suspensions are the difference between a useful score and noise.
2. **Ingest + storage + server-side scoring + config-over-ingest.** Prove idempotency and out-of-order backfill with a synthetic 50-bus reconnect storm — but define the per-batch ack contract and settlement rule (§8b) first, or the storm test measures nothing. Scoring lives here from day one (§0.3), with §3.4's behavior/exposure split built in, not retrofitted. **The signed config channel ships here too**: you cannot tune thresholds during the pilot without it, and tuning during the pilot is the pilot's entire purpose.
3. **Android shell.** Foreground service, Room outbox, per-second durability. No polish.
4. **The 8-hour real-drive spike — in summer heat, on a route with a checkpoint queue.** One real bus, one real route with a known dead zone. Measure battery delta, **thermal behavior and whether 12 V charging keeps up**, GPS gap distribution, backfill integrity, auto-pause behavior in the queue, and false-positive event rate. This is the go/no-go gate, and a mild-weather run does not count.
5. **Arabic Drive Mode UI + Ministry read-only map.**
5b. **The institution-facing half — do not defer this.** Enrolment + roster import, minimal portal auth with §8a's tiered access, crash reporting, and the APK distribution channel. Earlier drafts hid roughly 40% of pilot-blocking work between steps 5 and 6; each of these gates the pilot as hard as any client code.
6. **Pilot:** one cooperative, one city, 10–20 buses, 4 weeks — **with pass/fail criteria registered in advance.** Without them the pilot "succeeds" by anecdote. Minimum set: trip completeness ≥ 95%, upload success ≥ 99% within 24 h, IMU false positives < N per 100 km **validated against ride-along ground truth**, driver retention ≥ 80% at 4 weeks, zero thermal shutdowns.

   **The replay harness needs labeled ground truth**, which means someone riding the bus marking real events by hand. That is unbudgeted field work and it is the only way to know whether the detector works. Budget it now.

Steps 1–2 are where the hardware-portability requirement is either honored or quietly lost. Everything after is replaceable.

---

## 8a. Governance — a Pilot Gate, Not an Appendix

Earlier drafts filed "audit logging" under missing chapters alongside DR and APK distribution. That was a misjudgement of what this system *is*. This is a state-operated database of named individuals' movements, run by a transitional government with immature institutions, over a population for whom movement data has historically been dangerous.

The privacy *engineering* here is genuinely good — trip-scoped collection with a hard `IDLE` guarantee, the honest Arabic notification, dropping the public plate-lookup on targeting grounds, the §2.6 carve-out discipline. That is better than most commercial telematics. But engineering that makes misuse *visible* is not the same as making it *hard*. Five commitments, each a pilot gate:

**1. Retention that forgets locations.** Current policy — raw points 90 days, "events and scores forever" — is backwards, because **events carry coordinates**. "Forever" quietly builds a permanent movement archive one event at a time. Commit instead: location-bearing detail ages out at ~12 months into per-driver aggregates. **Scores persist; coordinates don't.**

**2. Right of exit.** Voluntary must mean revocable. Today, uninstalling stops collection and leaves the archive intact — that is not consent, it is a one-way ratchet. Build a deletion path that removes the driver's historical trip data, and **say so in the Arabic onboarding**. It is also the single strongest trust argument available: *you can leave, and take your data with you.*

**3. Access structure, not just access logs.** Role-gate **live individual tracking** separately from aggregate and fleet views. Most Ministry users need only the latter. An audit log that records unrestricted access is documentation of misuse, not prevention of it.

**4. Audit logs that someone actually reads.** Append-only, tamper-evident, recording who queried which driver's movement history — and a **named person responsible for reviewing them**. An unread log is theater.

**5. A written data-use agreement with the Ministry, before the pilot.** Safety scoring only; no third-agency access; driver right to see their own record. The architecture cannot enforce politics, but the project can decline to ship without the paper. Cheap now, impossible later.

**Coercion drift — name it explicitly.** "Voluntary" erodes the day a score becomes a condition of license renewal or route assignment. No code prevents that. What the design *can* do is state plainly — here and in the MOU — that §3.3's validity claims hold **only** for safety-improvement use, and that the score was never engineered to be, and is not fit to be, an enforcement instrument. §3.4's exposure/behavior split is part of that: a score that penalizes drivers for their route is unfit for any consequential use.

---

## 8b. Gaps Still Requiring Design Before Coding

Raised in adversarial review; each needs a decision, most need only a paragraph.

**Trip settlement semantics.** **TimescaleDB late-insert scoping:** a 30-day out-of-order window against compressed chunks likely means "do not compress chunks younger than 30 days," which changes storage sizing by roughly 10×. Run that math before choosing the window. §2.5 accepts backfill for 30 days, but nothing defines when a score is *final*. Today the Ministry sees driver X at 87, a backfill lands three days later, and the score silently becomes 79 — or doesn't, depending on unwritten code. Need: provisional score at `TRIP_END`, final when the seq range is gapless or a timeout lapses, and the portal must show which. `CLOSED_INCOMPLETE` trips never send a `TRIP_END` at all, so the server needs its own "no more batches coming" rule. Related: **TimescaleDB compressed chunks penalize late inserts** — a 30-day out-of-order window means compression policy, retention, and continuous-aggregate invalidation need real design.

**Ingest response contract.** `POST /v1/ingest` takes an array; partial failure is undefined. Need per-batch ack/nack, plus a client rule for a permanently-rejected batch (quarantine and flag — never silently drop). Missing hardening: request size caps, decompression-bomb limits, per-device rate limits, protobuf fuzzing. This is an internet-facing parser of attacker-controllable compressed input.

**Live-cache write ordering.** §2.3's immutability fix is sufficient for the durable store, but §5 has the batch processor *also* writing the Redis live-position cache that `PositionPreview` writes to. A backfilled stale batch arriving after a fresh preview will regress a bus's position on the map. Cache writes must be last-write-wins on a comparable clock — and §2.6 means defining which clock wins between a preview and a reconciled batch.

**Auth corrections to §5b.** Four fixes: (1) delete the screen-lock claim — Keystore gates on unlock only with `setUserAuthenticationRequired(true)`, which conflicts with frictionless trip start, and many drivers have no lock screen; state plainly that possession of the phone is the credential (acceptable, since fraud is out of scope). (2) **Hardware attestation will fail on the target fleet** — grey-market Tecno/Infinix on Android 8–9 frequently lack it and StrongBox is near-absent; enrolment must degrade gracefully or it fails on exactly the devices we're targeting. (3) The credential tuple contradicts the multi-driver recommendation — bind device↔vehicle, carry `driver_id` on the trip. (4) Add lost-phone revocation, replacement re-enrolment, factory-reset flow. **Biggest external risk: enrolment assumes a queryable Ministry plate↔driver registry API. If that registry is paper, this is a pilot blocker** — design a roster-import/manual-verification fallback now.

**Auto-pause, not auto-end.** §1's "<5 km/h for 45 min → close" will fire inside long checkpoint and border queues, which are routine here. The trip splits and post-queue driving goes unrecorded until the driver notices. Use auto-*pause* with motion-triggered resume; 12 V power presence is a strong "still on the bus" signal already available.

**FusedLocation contradicts our own GMS skepticism.** §6 hedges on Play Services for FCM while §4 builds location on FusedLocation, which *is* GMS and is absent on de-Googled and grey-market devices. Specify `LocationManager.GPS_PROVIDER` as a mandatory fallback.

**Config distribution channel.** Tuning `VehicleProfile`/`DetectionConfig` after the §8.4 spike is the entire point of the replay harness, but there is no server→client config path — so every threshold change becomes an app update into the 12-month tail. Piggyback signed config on the ingest response.

**Sensor backpressure.** (The JNI ring-buffer hazard from v0.3 is gone with JNI itself.) Still needed: an overflow policy when detection stalls under GC pressure — bounded channel, conflate or drop-oldest, plus a dropped-sample counter shipped as data quality rather than silently lost.

**Thermal.** Never mentioned in v0.1. A dash-cradled phone in a 45 °C+ Syrian summer cabin, charging at 12 V with GPS active, will throttle, suspend charging, or shut down. **The §8.4 spike must run in summer heat**, or it validates nothing about the season that matters most.

**Roster PII.** §5b imports `(plate, name, national ID)`. National IDs are the most sensitive field in the system and are needed **only as an enrolment match key** — store a salted hash, never plaintext. There is no feature that requires reading them back.

**APK signing-key custody — a governance decision, not an ops detail.** Sideloading plus self-update is the only realistic distribution channel in Syria, which means **whoever holds the signing key can push arbitrary code to every driver's phone**. If that is the Ministry, that is a very different system than if it is the developer. Recommend: developer-held key, documented custody, and the app verifying signed updates before applying them.

**What actually kills this in month 3, ranked:**
1. **Ministry counterparty latency.** Roster delivery, portal accounts, clerk workflows, and one official's sustained sponsorship are all on the critical path and outside your control. **Get the roster CSV and a named operational contact before step 3**, not at step 6.
2. **First-week false positives.** One driver showing colleagues a phantom harsh-brake flag poisons an entire cooperative. §3.3's narrow-score strategy is the defense — hold that line even when the Ministry asks for the rich score.
3. **OEM kill rate.** Budget the time §4 estimates, then double it, and instrument kill-rate per device model from day one. It is the most important fleet-health metric you have.
4. **Summer thermal failure.** Correctly gated by step 4 — do not let schedule pressure move that spike to autumn.

**Operations — design for unattended, because nobody runs this at 3am.** The client outbox already makes backend downtime survivable; **that is the DR strategy**, and the DR chapter should say so rather than inventing a second one. Add: disk-usage alerts (1 Hz points fill disks quietly), daily backups with an encrypted off-site copy (the residency-vs-resilience tension needs an explicit decision — recommend encrypted off-site with Ministry-held keys), and **server power** — grid outages hit the server too, so UPS plus a documented cold-start runbook. On observability, self-hosted Sentry is itself a heavy distributed system (Kafka + ClickHouse); at this scale **GlitchTip or a plain crash-report endpoint** is the honest choice.

**Missing chapters — a page each, none can be zero:**
- **Observability.** No crash reporting (Crashlytics is GMS and sanctions-problematic; self-hosted Sentry instead), no fleet-health metrics (upload success rate, OEM-kill rate per device model). Without these, "the app stopped working" is undebuggable — and it will be the most common report.
- **Testing strategy.** Golden-vector conformance across both client packages (§0.1), process-death injection for the §1 races, and the 50-bus reconnect storm.
- **Ministry portal security.** Official authentication, regional partitioning, and **audit logging of who queried which driver's movement history**. In this context that history is safety-critical personal data. v0.1 treated privacy as an adoption pitch; it also needs to be access control. The voluntary-trust story dies on first misuse.
- **The original plan's public plate-lookup** exposed live bus locations to anyone with a plate number. v0.2 drops it — recording that here as a deliberate removal on targeting-risk grounds, not an oversight.
- **DR/backups** for a self-hosted in-country server, including the backup-residency vs. resilience tension. A day-long backend outage is survivable client-side (the outbox handles it), but portal downtime and cache rebuild are unaddressed.
- **APK distribution.** Play Store availability in Syria is historically restricted; sideloading underpins the whole §2.7 update story and is currently unstated.
- **SOS button.** In the original plan, absent from v1, nearly free to add.

---

## 9. Open Questions

1. ~~C++ vs. Rust for a shared core.~~ **Resolved (§0.1):** no shared native core. Kotlin on Android, Rust for embedded when that phase starts. The remaining open question is the **hardware target itself**, which §3.2b's budget should drive — not the language.
2. **Where do speed limits come from?** There's no enforced-limit dataset. OSM `maxspeed` coverage in Syria is sparse. Options: crowd-derive from observed p85 speeds per segment, or have the Ministry declare limits per road class. Affects both scoring and geofencing, and speed-variance scoring (§3.3) is the hedge that works without it.
3. **Vehicle profile source.** Ministry registration data by plate, or driver self-declaration at first run? Self-declaration is gameable, but per your note, fraud pressure is low.
4. **Driver identity vs. vehicle identity.** Multiple drivers per bus is common. Score the driver, the vehicle, or the pair? Affects the data model — worth settling before the schema is written.
5. **Does the driver see their own score in real time?** Argues for behavior change; also argues for gaming and for distraction. I lean toward post-trip only.
