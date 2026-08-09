# safeSy — Consolidated Build Specification

**Status:** settled decisions only. This is the *build spec*. `DESIGN.md` remains the reasoning record — why each decision was made, what was rejected, and which earlier claims were wrong. When they disagree, this file wins.

**Phase 1 scope:** Android driver app + backend + minimal Ministry portal. Buses and minibuses, Arabic-first, voluntary adoption.

**Not in Phase 1** (but designed around): embedded hardware, LoRa mesh, passenger manifests, ticketing, OBD2, dashcam.

---

## 1. System Decomposition

Eight systems. Phase-1 work is S1–S6; S7–S8 are reserved so nothing built now forecloses them.

| # | System | Phase 1 | Owner package |
|---|---|---|---|
| **S1** | Protocol & Conformance | ✅ Full | `safesy-proto`, `safesy-spec`, `safesy-conformance` |
| **S2** | Trip Lifecycle & Durability | ✅ Full | `safesy-android` |
| **S3** | Sensing & Detection | ✅ Full | `safesy-android` |
| **S4** | Transport & Outbox | ✅ Full | `safesy-android` |
| **S5** | Backend: Ingest, Store, Score | ✅ Full | `safesy-server` |
| **S6** | Portal & Identity | ✅ Minimal | `safesy-server` |
| **S7** | Embedded Producer | ⬜ Phase 2 | `safesy-embedded` |
| **S8** | LoRa Liveness Channel | ⬜ Phase 2 | — |

**Dependency order:** S1 → (S2, S5 in parallel) → S3 → S4 → S6.

S1 first and alone: the schema and spec are what keep the future embedded package honest, and retrofitting them after the app exists is how the portability requirement gets quietly lost.

---

## S1 — Protocol & Conformance

The only genuinely shared artifacts. Everything else is two independent implementations bound by these.

### S1.1 `safesy-proto` — wire schema

```protobuf
syntax = "proto3";
package safesy.v1;

message TelemetryBatch {
  bytes    trip_id       = 1;   // 16B UUIDv7, client-generated at trip start
  uint32   seq           = 2;   // gapless within trip, allocated at seal time
  Anchor   anchor        = 3;
  repeated Delta  points = 4;   // 60 @ 1 Hz
  repeated Event  events = 5;   // sparse
  repeated RadioSample radio = 7;  // ~2 per batch (30 s cadence)
  Producer producer      = 6;   // per-BATCH, not per-trip
  uint32   detect_version = 8;
  uint32   app_version    = 9;
}

message Anchor {
  uint64 t_ms       = 1;  // wall clock — ADVISORY ONLY, never for ordering
  sint64 lat_e7     = 2;
  sint64 lon_e7     = 3;
  uint64 mono_ms    = 4;  // elapsedRealtime — monotonic, survives deep sleep
  uint64 boot_id_lo = 5;  // random per boot; mono_ms comparable only within one
  uint64 gnss_t_ms  = 6;  // GPS time — AUTHORITATIVE. Set ONLY for GPS_PROVIDER fixes.
  bool   has_fix    = 7;  // false ⇒ lat/lon unset; server must never plot
}

message Delta {              // each field is a delta from the previous point
  sint32 dt_ms   = 1;
  sint32 dlat_e7 = 2;
  sint32 dlon_e7 = 3;
  sint32 dspeed  = 4;  // cm/s
  sint32 dhead   = 5;  // decidegrees
  uint32 hdop_q  = 6;  // 0–15 quantized
}

message Event {
  enum Kind {
    KIND_UNSPECIFIED = 0;  HARSH_BRAKE = 1;  HARSH_ACCEL = 2;  HARSH_CORNER = 3;
    SPEEDING_ENTER   = 4;  SPEEDING_EXIT = 5; POSSIBLE_CRASH = 6;
    TRIP_START = 7;  TRIP_END = 8;  COVERAGE_LOST = 9;  COVERAGE_REGAIN = 10;
    MOUNT_SHIFTED = 11;  SOS = 12;
  }
  Kind   kind      = 1;
  uint32 offset_ms = 2;
  uint32 severity  = 3;   // 0–1000
  bytes  snippet   = 4;   // §S1.2 encoding; absent when capped
  uint32 snippet_fmt = 5; // 0=none 1=int16@50Hz 2=envelope@100ms
}

message RadioSample {       // §S3.4 — independent of whether anything transmits
  uint32 offset_ms = 1;
  Rat    rat       = 2;
  sint32 rssi_dbm  = 3;
  uint32 cell_id   = 4;   // aggregate coverage map ONLY; ages out per §S6.4
  uint32 mcc_mnc   = 5;
  bool   data_ok   = 6;   // attached AND a request actually succeeded
}

enum Rat { RAT_UNKNOWN = 0; NONE = 1; GPRS = 2; EDGE = 3; UMTS = 4; HSPA = 5; LTE = 6; NR = 7; }
enum Producer { PRODUCER_UNSPECIFIED = 0; PHONE = 1; HARDWARE = 2; }
```

**Rules, permanently:** additive-only; never reuse or renumber a field; `reserved` removed numbers explicitly. A 12-month tail of un-updated clients is permanent policy, not a transition.

### S1.2 Snippet encoding

| Format | Use | Size (10 s) |
|---|---|---|
| `1` int16 @ 50 Hz | `POSSIBLE_CRASH` — full fidelity | ~5 KB gz |
| `2` envelope min/max/RMS per 100 ms | routine events | ~1 KB gz |

**Never average raw IMU** — a ~120 ms crash impact averaged into 500 ms buckets retains 14% of peak and the threshold never fires. The envelope form keeps `max` explicitly, which is why it works where averaging doesn't.

Cap: ~20–30 snippets/trip, severity-prioritised. **`POSSIBLE_CRASH` is exempt from the cap.**

### S1.3 `safesy-conformance` — three fixture classes

| Class | Contract | Covers |
|---|---|---|
| **A. Encoding** | byte-exact | delta encoding, anchor selection, framing, seq |
| **B. Detection** | toleranced: `offset_ms` ±20 ms, `severity` ±2%, kind+count exact | filter output, thresholds |
| **C. Scenarios** | state assertions | seq across force-kill, ORPHANED/RESUMED, retry, drain |

Byte-exactness is impossible for B — JVM and Rust float math differ (libm, double promotion, FMA). Class C is scripts: sample streams interleaved with `KILL` / `RELAUNCH` / `NET_UP` / `NACK`, asserting persisted state.

**Kotlin is the pinned reference implementation** and generates fixtures. Regenerate on every spec change.

---

## S2 — Trip Lifecycle & Durability

### S2.1 States

```
IDLE ──[Start]──> ACTIVE ──[End]──> CLOSING ──(drained)──> SETTLED
                     │
                     ├─[<5 km/h 45 min]──> auto-PAUSE (motion resumes)
                     └─[process death]───> ORPHANED
                                              ├─ gap <15 min & near last fix → RESUMED (same trip_id, seq continues)
                                              └─ else → CLOSED_INCOMPLETE
```

- **`IDLE` is a privacy guarantee:** zero sensors, zero location, zero storage, zero network. State it in Arabic onboarding — it is what makes voluntary adoption credible.
- **`pending` is a label, not a state** — an active trip with a non-empty outbox. Informational icon, no warning colours.
- **Auto-PAUSE, not auto-end.** Checkpoint and border queues are routine; auto-ending splits trips and loses post-queue driving. 12 V power presence is a strong "still on the bus" signal.
- **`ORPHANED` still drains.** A crash is exactly when the process dies *and* the data matters most.
- **RESUMED's clock:** prefer `gnss_t_ms` deltas; fall back to wall clock with wide tolerance and a quality flag. **Bias toward `CLOSED_INCOMPLETE`** — wrongly splitting one trip is far cheaper than wrongly merging two.
- The RESUMED proximity check needs one location fix while IDLE. **Carve this out explicitly** in onboarding; do not let it become background location.

### S2.2 Durability

- **Write each point to Room as it arrives (1 Hz).** This is the durability boundary. Batching writes leaves the final minute before a crash in RAM only.
- **Seal 60-point batches separately**, reading back from Room.
- **Seal immediately on `POSSIBLE_CRASH`**, priority drain.
- **`seq` allocated at seal time, in the same Room transaction as the bytes.** Never from an in-memory counter — a force-kill would replay a seq against different content and silently corrupt via dedupe.
- WAL on, `synchronous = NORMAL`. Points retained 7 days after SETTLED.

**Force-stop caveat:** MIUI/Realme force-swipe suspends WorkManager *and* the boot receiver until manual launch. Recovery is a persistent Arabic notification prompting one tap — **do not claim it is automatic.** Both your test devices are Xiaomi, so this is a day-one concern, not an edge case.

---

## S3 — Sensing & Detection

### S3.1 Sources

| Source | Rate | Notes |
|---|---|---|
| Location | 1 Hz | `LocationManager.GPS_PROVIDER` primary; FusedLocation only when GMS present |
| IMU (accel + gyro) | 50 Hz | batched reads; never transmitted raw |
| Radio state | 0.033 Hz (30 s) | §S3.4, independent channel |

**GPS time trap:** `Location.getTime()` is satellite-derived **only** for `GPS_PROVIDER` fixes; network/fused return the untrusted system clock. Populate `gnss_t_ms` only for genuine GPS fixes; use `getElapsedRealtimeNanos()` for ordering.

### S3.2 Detection engine

Pure function: `(samples, VehicleProfile, DetectionConfig) → events`. **No scoring here** (§S5.3).

```
VehicleProfile {
  class             : BUS | MINIBUS | VAN
  harsh_brake_mps2  : BUS 2.5, MINIBUS 3.2
  harsh_accel_mps2  : BUS 2.0, MINIBUS 2.8
  harsh_corner_mps2 : BUS 2.5, MINIBUS 3.0
  crash_g           : 3.0
  rollover_deg_s    : buses are top-heavy
}
```

**Placement is inferred, not asked** (`PlacementClassifier`). A driver will not select a mounting mode before every trip, and a wrong answer is worse than none. Classified over the first 60 s from orientation variance, vibration coupling, and proximity; the verdict is **held for the trip** so thresholds cannot move mid-journey. `imuEventsTrustworthy()` returns false for `CARRIED` and while `UNKNOWN` — GPS-derived metrics stay valid regardless.

Must handle: **gravity separation** (complementary/Madgwick filter — the phone is mounted at an unknown angle; getting this wrong makes every trip full of phantom braking), **speed fusion** (GPS accurate but 1 Hz and laggy; accel fast but drifts), **GPS quality gating** (hdop spike + implausible jump must suppress detection, not emit phantom corners), and **continuous re-estimation of the gravity vector** with a `MOUNT_SHIFTED` event when orientation changes suddenly.

**Bias thresholds LOW.** Over-detection is recoverable server-side from snippets; missed events leave no trace anywhere and are permanent.

### S3.3 Accuracy limits — do not overclaim

| Metric | Confidence | Why |
|---|---|---|
| Speed, speed variance | High | GPS-derived, mount-independent |
| Duration | High | timestamps only |
| Night exposure | High **with GPS fix**, Medium without | needs trustworthy absolute time |
| Harsh brake/accel | Medium | correctable from GPS speed derivative |
| Harsh cornering | Medium-low | needs stable vehicle-frame orientation |
| Rollover proxy | Low | alerting hint only, never a scoring input |

**Validated on real hardware (2026-08-08, Xiaomi Mi 11 Lite 5G):** IMU 49.9 Hz sustained · gravity separation leaves **0.041 m/s² of 9.81** stationary and **0.877 m/s² horizontal mean** while driving · **zero false positives** across 75 s of driving · GNSS TTFF 24 s in open sky.

⚠️ **Still unproven: that it catches true positives.** No deliberate hard brake has been recorded. Every threshold remains a spec placeholder that has never met a Syrian road. See [`docs/STATUS.md`](../STATUS.md).

### S3.5 Phone-handling — collected under consent, not scored

**Decision (2026-08-08):** collected and transmitted in production, under consent disclosed at Ministry enrolment (§S6.1.8b).

Drivers take calls and send messages while driving. Whatever the rules say, it happens, and a dataset that pretends otherwise does not describe reality.

| | |
|---|---|
| Collect + transmit | ✅ with disclosed, versioned consent |
| Show the driver their own record | ✅ symmetry is the trust argument (§S6.3) |
| **Scoring input** | ❌ **no** |

**Why it is collected:** handling is the largest source of phantom IMU events. A phone lifted to an ear rotates ~90° and accelerates hard, which reads as violent cornering. Without this signal, those events are indistinguishable from real driving — and a trace gap is indistinguishable from a coverage dead zone or a killed process.

**Why it is not scored:** the measurement cannot support the conclusion. Proximity + app-background tells you the *phone* was handled — not *who* handled it, nor whether the vehicle was moving. Scoring on a misattributing signal creates disputes with no resolution, which is the same failure mode §S5.3 identifies for route-confounded scoring. It is also the feature most likely to get the app uninstalled, against a voluntary-adoption assumption the whole plan rests on.

Aggregate research use (does handling explain phantom cornering? how often does it coincide with events?) is in scope and valuable.

### S3.4 Radio/coverage channel — independent by design

Cannot be inferred from telemetry: a gap in *arrival* could be no coverage, a killed process, a dead battery, or a trip ending. And "covered but 2G-only / marginal" leaves no trace in a stream that only records what got through.

- Sample every ~30 s while ACTIVE, **regardless of connectivity**. ~0.3 MB/month.
- **Record `NONE` explicitly.** A dead zone is data. (This is exactly why crowdsourced OpenCelliD is unusable — Deir ez-Zor city shows zero cells while having working service.)
- `rat` matters as much as presence: EDGE-only vs LTE is "covered" vs "about to go dark" given planned 2G retirement.
- `cell_id` is for the aggregate map only and **ages out with location detail** — per-driver cell IDs are a finer-grained location trail than GPS.

---

## S4 — Transport & Outbox

### S4.1 Transport

- **HTTP/2 POST, protobuf body, gzip.** Not MQTT for v1 — better NAT traversal, no persistent connection liability.
- Server **must accept `identity` and small-window deflate `Content-Encoding` from day one.** One line now; a breaking change once embedded ships (full-window gzip needs 256 KB, more than an MCU's usable SRAM).
- `POST /v1/ingest` accepts an array; **per-batch ack/nack** required. A permanently-rejected batch is quarantined and flagged, never silently dropped.

### S4.2 Outbox

- **`(trip_id, seq)` is the primary key.** Re-uploads are no-ops. Accept out-of-order and late batches for 30 days.
- **Sealed batches are immutable.** No re-encoding at a different resolution — that would let dedupe discard the full-resolution version permanently.
- **Drain order matters**, not just drain: reconnection windows are minutes while passing a town. Priority: `POSSIBLE_CRASH` → recent status → bulk backlog.
- **Resumable, chunked transfers.** The real 2G failure is attach → start upload → drop mid-transfer, repeatedly.
- **Reuse connections** (HTTP/2 keep-alive). GPRS attach is 2–5 s and TLS 1–3 s, against a 0.29 s upload — setup dominates.
- **Debounce connection state.** One successful attach ≠ a stable window.
- Trigger: `WorkManager` (NetworkType.CONNECTED, backoff 30 s → 15 min) **plus** an in-service `NetworkCallback` for immediate drain on reconnect.

### S4.3 Budget (8 h/day × 26 d = 208 driving-hours)

| Component | Per month |
|---|---|
| Telemetry (1 Hz points + events, 60 s batches) | 10.9 MB |
| Crash snippets | ~0.1 MB |
| Routine snippets | ~0.4 MB |
| Radio samples | ~0.3 MB |
| **Total** | **~11.7 MB** |

2G is not a constraint: a 742 B batch uploads in 0.29 s on slow GPRS; total uplink is 78 min/month; a 115 km backlog drains in 33 s.

---

## S5 — Backend

### S5.1 Pipeline

```
POST /v1/ingest → validate → dedupe (trip_id, seq) → per-batch ack → enqueue
                                                          ↓
   ┌──────────────┬─────────────────┬────────────────┬────────────────┐
 points         events           radio            scoring          live cache
 Timescale      Postgres         PostGIS/H3       (S5.3)           Redis
```

**Ingest must be dumb and fast** — validate, dedupe, enqueue, 200. The load spike that matters is 50 buses reconnecting simultaneously after a regional outage.

- PostgreSQL + PostGIS + TimescaleDB. **Self-hosted** (sanctions + data residency).
- ⚠️ **Timescale late inserts:** a 30-day out-of-order window against compressed chunks likely means "don't compress chunks younger than 30 days" — changes storage sizing ~10×. Run the math before choosing.
- **Live cache single-writer.** Sealed batches only. Last-write-wins on a comparable clock, or a backfilled batch makes a bus jump backward on the map.

### S5.2 Trip settlement

Undefined settlement is a real bug: the portal shows 87, a backfill lands 3 days later, and it silently becomes 79.

- **Provisional** score at `TRIP_END`; **final** when the seq range is gapless or a timeout lapses. Portal displays which.
- `CLOSED_INCOMPLETE` trips never send `TRIP_END` — the server needs its own "no more batches coming" rule.

### S5.3 Scoring — server-side, from day one

Scoring is *policy*; it changes as the pilot teaches you things. On-device, every change is an app update into a 12-month tail.

**Behavior vs exposure — mandatory, not a refinement:**

| Category | Treatment |
|---|---|
| **Behavior** (driver's choice) | Scored, **normalized per road segment** — vs the fleet distribution on *the same segment* |
| **Exposure** (schedule's imposition) | **Reported as context, never penalized** — night hours, duration, route difficulty |

Speed variance is confounded by road condition; 60→100→60 is what everyone does on a potholed checkpoint-riddled route. Score it naively and a Deir ez-Zor driver is structurally punished versus a Damascus–Homs driver for identical driving. They will notice within a week, and it kills both adoption and validity.

Segment normalization also solves the missing-speed-limit problem for free: "faster than 85% of drivers on this exact segment" needs no external dataset.

**Fatigue is the honest exception** — exposure, but a real risk. Report to the Ministry as a *rostering* finding; keep it out of the driver's score.

Version every score with `scoring_version`; every batch with `detect_version`.

### S5.4 Config channel

Signed config (thresholds, `VehicleProfile`) piggybacked on the ingest response. **Without this you cannot tune during the pilot — and tuning during the pilot is the pilot's purpose.**

---

## S6 — Portal & Identity

### S6.1 Enrolment (registry is paper, confirmed)

1. Ministry provides a roster CSV `(plate, name, national ID)` → imported as the allowlist.
2. Driver enters plate + national ID; matched against the roster.
3. No match → clerk-approved pending queue.
4. Device keypair in Android Keystore; credential binds **`(vehicle_id, device_pubkey)`**.
5. **`driver_id` is per-trip** — one bus, several drivers, often one shared phone.
6. **Attestation optional.** Grey-market Tecno/Infinix on Android 8–9 often lack it; requiring it fails enrolment on the target fleet.
7. **Possession of the phone IS the credential.** Acceptable (fraud out of scope), but never misrepresent it as stronger.
8. **National IDs stored as salted hashes** — needed only as a match key.
8b. **Consent record.** Enrolment captures which data classes the driver agreed to, with a timestamp and the consent-text version they saw. Phone-handling data (§S3.5) is collected under this consent, so the record must be **auditable** — "the driver signed something" is not a defence if nobody can produce what they signed. Store: `driver_id`, `consented_at`, `consent_version`, and the granted classes.
9. Needs: roster re-import, "no longer in roster" state, lost-phone revocation, replacement re-enrolment.

**Stale tokens must never block ingest.** Buses reconnect after hours holding expired tokens; accept on device-credential signature and issue a fresh token in the response.

### S6.2 Map

- **Staleness displayed honestly.** A bus in a dead zone sits stale for hours; rendering it like a live bus makes an operator act on a 3-hour-old pin. Every pin carries update age; beyond ~5 min renders visibly different (`آخر تحديث منذ ٢٤ دقيقة`).
- Liveness target: ≤5 min when covered. 60 s batches deliver ~65 s — 4.6× margin.

### S6.3 Driver-facing

- **Live speed and within/over indicator — yes.** Changes behavior in the moment, honest, same number the Ministry sees.
- **Live score — no.** Invites gaming, pulls eyes off the road.
- Post-trip: full breakdown, **exactly the data the Ministry has**. Symmetry is the trust argument.
- ⚠️ No enforced Syrian speed limits exist. Unless the Ministry declares limits per road class, the indicator is **advisory** and must be labelled so.
- **SOS button in v1.** Nearly free, and the clearest driver-facing value in the system.

### S6.4 Governance — a pilot gate, not an appendix

1. **Retention that forgets locations.** Events carry coordinates, so "events forever" builds a permanent movement archive. Location detail ages out at ~12 months into per-driver aggregates. **Scores persist; coordinates don't.**
2. **Right of exit.** Opt-out **deletes** history, not just stops collection. Say so in Arabic onboarding — it is the strongest trust argument available.
3. **Tiered access.** Live individual tracking role-gated separately from aggregate views.
4. **Append-only audit log** of who queried whose movement history, with a **named reviewer**. An unread log is theater.
5. **Written Ministry data-use agreement before the pilot**: safety scoring only, no third-agency access, driver right to see their own record.

**Coercion drift:** state plainly that these validity claims hold only for safety-improvement use. The score is not fit to be an enforcement instrument — §S5.3's exposure split is part of why.

---

## S7/S8 — Reserved (Phase 2)

**S7 Embedded.** Rust `no_std`, target undecided (ESP32-S3 leaves only ~90–110 KB after BLE+TLS+modems — flash-backed outbox mandatory). Paired mode: **hardware owns `trip_id`**, app relays; BLE drop → app resumes the *same* trip_id with `producer = PHONE`. Server allows at most one active trip per `vehicle_id`.

**S8 LoRa.** ⚠️ **Not a failover transport.** ~2 KB/hour/device vs 55 KB/hour of events — a 15× shortfall. It is a **liveness + SOS channel** with its own ~12 B message type. Beacon interval must scale with fleet density (50 buses × 1/min = 3,000 pkt/hour/gateway; ALOHA degrades well before that).

---

## Phase-1 Build Order

| Step | Deliverable | Gate |
|---|---|---|
| **0** | `safesy-proto` + `safesy-spec` + first conformance fixtures | Schema reviewed; three fixture classes defined |
| **1** | Kotlin detection engine + replay harness | Thresholds tuned against **recorded real drives** |
| **2** | Ingest + storage + scoring + config channel | 50-bus reconnect storm passes; ack contract defined |
| **3** | Android shell: foreground service, Room, outbox | Survives force-stop and reboot |
| **4** | **8-hour real-drive spike** | **Go/no-go.** Summer heat, checkpoint queue, known dead zone. Measures battery, thermal, GPS gaps, backfill integrity, false-positive rate |
| **5** | Arabic Drive Mode + Ministry read-only map | RTL verified on a system-Arabic device |
| **5b** | Enrolment + roster import + portal auth + crash reporting | — |
| **6** | Pilot: 1 cooperative, 10–20 buses, 4 weeks | Pre-registered metrics (below) |

**Pre-registered pilot pass/fail** — without these it "succeeds" by anecdote:
trip completeness ≥95% · upload success ≥99% within 24 h · IMU false positives < N/100 km **validated by ride-along ground truth** · driver retention ≥80% at 4 weeks · zero thermal shutdowns.

**Ride-along labeling is unbudgeted field work** and is the only way to know whether detection works. Budget it.

---

## Critical Path Risks

1. **Ministry counterparty latency.** Roster CSV, portal accounts, clerk workflow, a named sponsor — all outside your control. **Get the roster and a named contact before step 3.**
2. **First-week false positives.** One driver showing colleagues a phantom harsh-brake flag poisons a cooperative. Hold the narrow-score line even when asked for the rich score.
3. **Xiaomi/MIUI background kill.** Both test devices are Xiaomi. Instrument kill-rate per device model from day one.
4. **Summer thermal.** The step-4 spike must run in real heat. A mild-weather run validates nothing.

---

## Open Questions

1. **Hardware target** for Phase 2 — driven by the S7 memory budget, not by language.
2. **Speed limits** — Ministry-declared per road class, or purely segment-relative (§S5.3)?
3. **Whose SIM pays** the ~12 MB/month? Ministry reimbursement, bundle, or zero-rating.
4. **Depot WiFi?** If buses yard nightly, full 50 Hz raw drains free — near-complete data exactly when threshold tuning needs it.
5. **APK signing-key custody.** Whoever holds it can push code to every driver's phone. Recommend developer-held, documented.
