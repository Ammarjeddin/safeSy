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

## ⚠️ The mounting assumption may be wrong

The detector currently assumes a **fixed mount** — orientation is learned once at trip start and any large change is treated as a fault (`MOUNT_SHIFTED`), suppressing detection until it settles.

**Syrian bus drivers are unlikely to use a cradle.** Realistically the phone will be:

| Placement | What it does to the detector |
|---|---|
| **Shirt pocket** | Rotates with the driver's torso on every lean, every turn, every time they check a mirror. Orientation is *continuously* changing, not occasionally. |
| **Trouser pocket** | Worse — also flexes with leg movement and pedal work |
| **Loose on the dashboard** | Slides on every corner and every brake. `MOUNT_SHIFTED` would fire constantly |
| **Cup holder / bag** | Roughly fixed, but at an arbitrary and often near-vertical angle |
| **Cradle** | What the current design assumes |

**Why this matters:** if the phone re-orients continuously, "learn the mount once, flag changes as faults" is the wrong model. IMU-derived events (cornering, rollover) may be unusable in a pocket, and the honest response may be to **fall back to GPS-only metrics for those placements** — speed, variance, duration, and braking from the speed derivative are all mount-independent (§S3.3).

**Placement is now INFERRED, not asked.** `PlacementClassifier` decides from behaviour over the first 60 s and holds the verdict for the trip:

| Signal | Separates |
|---|---|
| Orientation variance | A cradle holds steady; a pocket swings with the torso |
| Vibration coupling | A hard mount couples engine/road vibration; cloth damps it |
| Proximity covered | A pocket keeps the sensor covered |

Asking a bus driver to select a mode before every trip is a step that will not happen — and a wrong answer is worse than no answer. The verdict is **held for the trip** because a classifier that flips mid-journey would move thresholds under the detector's feet.

`imuEventsTrustworthy()` gates IMU-derived events: `false` for `CARRIED` and while `UNKNOWN`. GPS-derived metrics stay valid regardless, which is why §S3.3 rates them High confidence.

⚠️ **The classifier's thresholds are placeholders** — never calibrated against labelled sessions. The session page's manual placement selector is retained precisely as **ground truth to validate the classifier against**.

**This must be measured, not guessed.** The open questions:

1. Does a pocket produce so many `MOUNT_SHIFTED` events that detection is suppressed most of the time?
2. Can harsh braking still be detected from GPS alone when IMU events are unusable?
3. Should `VehicleProfile` carry a *placement* field that disables IMU-derived metrics?

Until there is pocket data, **treat cornering and rollover as cradle-only capabilities.**

## Phone handling during driving

Drivers take calls and send messages while driving. Whatever the rules say, it happens — and a system that pretends otherwise produces data that does not describe reality.

Debug sessions therefore record **app-background time** and **proximity-sensor "held to ear"** episodes, for two reasons:

1. **Data quality.** A gap in the trace could be a coverage dead zone, a killed process, or the driver answering a call. Indistinguishable after the fact unless recorded.
2. **Detection validity.** A phone lifted to an ear rotates ~90° and accelerates hard. Without knowing that happened, the detector sees violent cornering. **Handling is the single largest source of phantom IMU events**, and it must be separable from real driving.

**Status (revised 2026-08-08):** this data **is** collected and transmitted in production, under consent disclosed at Ministry enrolment.

The decision splits three ways:

| | |
|---|---|
| Collect + transmit | ✅ with disclosed consent |
| Show the driver their own record | ✅ symmetry is the trust argument |
| **Use as a scoring input** | ❌ **no** |

Scoring stays off because the measurement cannot support the conclusion: proximity + app-background tells you the *phone* was handled, not *who* handled it or whether the bus was moving. A score built on a signal that misattributes creates disputes with no resolution — the same failure mode §3.4 warns about for route-confounded scoring. See DESIGN.md §3.3 for the full reasoning.

## Related

Runs only while [`policy/`](../policy/README.md) says `ACTIVE` · Events are packed by [`outbox/`](../outbox/README.md) · Scoring is **not** here — it's in [`server/`](../../../../../../../../server/README.md)
