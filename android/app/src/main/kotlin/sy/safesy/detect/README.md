# Section: Accident & Behaviour Detection

**What lives here:** turning 50 Hz accelerometer + 1 Hz GPS into a handful of events. Crash detection, harsh braking, cornering, mount-shift.

**Owner:** unassigned · **Status:** core complete, needs real-drive tuning

---

## Why this is its own section

This is the only part of the app that is **pure computation** — no Android APIs, no I/O, no clocks, no storage. That is deliberate:

- It can be replayed against recorded drives on a laptop with no phone attached.
- It is the behavioural target the future Rust/embedded implementation must match (conformance class B).
- A contributor can work on it knowing **nothing** about foreground services, protobuf, or Room.

If you want to improve detection quality, everything you need is in this directory.

## The one thing to understand first

> **The 50 Hz IMU stream NEVER leaves the device.**

That single constraint is why the app costs ~11.9 MB/month instead of ~894 MB, and why a 3-hour desert dead zone is survivable — the *events* survive the gap, not just the raw points. Any proposal that involves shipping raw sensor data will not work here; see [`docs/architecture/DESIGN.md`](../../../../../../../../docs/architecture/DESIGN.md) §0.3 for the measurements.

## Design bias: prefer false positives

Thresholds run **low** on purpose.

- Over-detection is **recoverable** — the server keeps a ±5 s snippet and thresholds can be re-tuned retroactively across the whole fleet.
- A missed event leaves **no trace anywhere** and is permanent.

So: over-detect, and filter server-side.

⚠️ **But false positives are also what kills adoption.** One driver showing colleagues a phantom harsh-braking flag costs more credibility than five missed events. The resolution: be liberal about *emitting* events, and conservative about what the *score* penalises (that decision lives on the server, §S5.3).

## Components

| File | |
|---|---|
| `Types.kt` | `ImuSample`, `GnssSample`, `VehicleProfile`, `DetectionConfig` |
| `OrientationEstimator.kt` | Gravity separation — the hardest and most important piece |
| `DrivingDetector.kt` | Event detection and the sustained-excursion filter |

### Gravity separation is the whole ballgame

The accelerometer reports ~9.81 m/s² of gravity **in an unknown direction**, because the phone sits at whatever angle the driver's cradle holds it. Remove it wrongly and every trip is full of phantom braking.

A complementary filter blends gyro (accurate short-term, drifts) with accelerometer (correct on average, noisy under motion). Two subtleties that were bugs before they were features:

1. **Mount-shift detection uses the *instantaneous* accelerometer direction, not the filtered estimate.** The filter lags by design (alpha 0.98), so after a phone slides, phantom cornering fires *before* the shift is noticed. Gated on measured magnitude being near 1g, so hard braking isn't mistaken for a slide.
2. **Vertical and horizontal are separated.** Potholes are constant on Syrian roads. Without the split, every bump reads as lateral acceleration and manufactures cornering events.

### Where each signal comes from

| Event | Source | Why |
|---|---|---|
| `HARSH_BRAKE` / `HARSH_ACCEL` | **GPS speed derivative** | Mount-independent — survives a sliding phone |
| `HARSH_CORNER` | Residual lateral g | Needs correct orientation, so lower confidence |
| `POSSIBLE_CRASH` | Total magnitude | Checked **immediately**, not via sustained excursion — an impact lasts ~120 ms |
| `MOUNT_SHIFTED` | Instantaneous gravity direction | Suppresses IMU events until re-converged |

## Honest accuracy limits

Do not overclaim these — the pilot will expose it.

| Metric | Confidence | Why |
|---|---|---|
| Speed, speed variance | High | GPS-derived, mount-independent |
| Harsh brake/accel | Medium | Correctable from GPS speed derivative |
| Harsh cornering | Medium-low | Needs stable vehicle-frame orientation |
| Rollover proxy | Low | Alerting hint only, never a scoring input |

## Testing: mutations, not just assertions

**Passing tests here are not enough.** The tests were once entirely vacuous — every defence could be deleted with no test failing, which hid two real bugs.

So before claiming a defence is tested, **break it deliberately and confirm a test fails.** All seven current defences are verified this way:

| Break this | And this test must fail |
|---|---|
| Sustained-duration filter | `a single-sample deceleration blip…` |
| hdop gating | `high hdop suppresses detection` |
| GPS teleport rejection | `a GPS teleport does not manufacture acceleration` |
| Mount-shift suppression | `no cornering events are emitted while…` |
| Calibration wait | `events are not emitted during the calibration window` |
| Gravity removal | `heavy road vibration alone does not trigger events` |
| Vertical/horizontal split | `a vertical pothole jolt does not leak…` |

Run: `cd android && ./gradlew testDebugUnitTest --tests '*detect*'`

## Contributing

Good first tasks:
- **Speed-fusion Kalman filter** — GPS is accurate but 1 Hz and laggy; accelerometer is fast but drifts. Fusing them gives braking events both correct timing *and* correct magnitude.
- **Rollover proxy** — sustained lateral g + roll rate. Buses are top-heavy; cars usually aren't.
- **Cornering speed vs. curve radius** — derive radius from heading-rate/speed. Catches the specific failure mode that rolls a bus.
- **Replay harness** — load recorded drives from CSV/JSON and replay them offline. This is the highest-value item on the list.

⚠️ **Thresholds are placeholders.** They come from the spec and have never met a real Syrian road or a real bus suspension. Do not treat them as tuned.

## Related

Runs only while [`policy/`](../policy/README.md) says `ACTIVE` · Events are packed by [`outbox/`](../outbox/README.md) · Scoring is **not** here — it's in [`server/`](../../../../../../../../server/README.md)
