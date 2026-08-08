<div align="center">

# safeSy · سيف سوريا

**Road-safety telematics for Syrian buses — running on the phone the driver already owns.**

[![Phase](https://img.shields.io/badge/phase-1%20·%20Android-blue)](docs/architecture/SPEC.md)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](android/)
[![Kotlin](https://img.shields.io/badge/kotlin-2.1-7F52FF)](android/)
[![Data](https://img.shields.io/badge/data-11.9%20MB%2Fmonth%2Fbus-orange)](#the-numbers)
[![License](https://img.shields.io/badge/license-TBD-lightgrey)](#license)

</div>

---

## Why we're building this

Syria is coming out of more than a decade of war. The roads are damaged, unlit, and largely unsigned. Cellular towers across the eastern corridors are gone or unpowered. Emergency dispatch has no coordinating agency. Rebuilding all of it is a **$82bn, multi-year infrastructure programme** — and it is genuinely underway, but it will take years to reach the roads people travel on today.

Buses run those roads now. Every day, with passengers.

**safeSy is what you can do in the meantime.** It uses the smartphone the driver already carries to provide the two things missing on Syrian highways: *someone knows where the bus is*, and *someone knows when it crashes*. No towers, no hardware, no roadworks — deployable this quarter, on the network that exists today.

It is deliberately designed to become **less** necessary as real infrastructure arrives, and to keep working better as it does. Every new tower shortens a dead zone. Every repaired road shows up in the data. When Syria has proper highway telemetry, safeSy will have spent years mapping exactly where to put it.

## The problem, in one number

<div align="center">

### 6,383
**estimated road deaths per year** · **29.9 per 100,000 people**

*~7× the EU rate · ~2× the world average*

</div>

*(Source: [WHO Global Status Report on Road Safety 2023](https://cdn.who.int/media/docs/default-source/country-profiles/road-safety/road-safety-2023-syr.pdf))*

Syria's own road safety strategy already sets a **10%-by-2030 fatality target** and a **15-minute post-crash response target**. Both exist on paper. Neither has funding or data behind it — WHO records the strategy as *"Not funded"*, with **no lead agency coordinating pre-hospital emergency care**.

safeSy is the measurement and response layer those existing national targets require.

## What it does

| | |
|---|---|
| 📍 **Trip logging** | Position, speed, and driving events — works fully offline, syncs when coverage returns |
| 📊 **Driver safety score** | Normalized per road segment, so a driver on a worse road isn't punished for it |
| 🗺️ **Live fleet map** | ~65 s fresh where covered; staleness shown honestly where it isn't |
| 🚨 **Crash detection** | ~65–70 s from impact to warning nearby buses |
| 🆘 **SOS button** | Driver-initiated, one tap |
| 📡 **Coverage map** | The first empirical measurement of where cellular fails on Syrian highways — a byproduct, at zero extra effort |

## Why this is hard

The design is shaped by four constraints that rule out most off-the-shelf approaches:

**Coverage fails for 150+ km at a stretch.** The Damascus–Deir ez-Zor corridor has an estimated **150–180 km continuous dead zone** — about 2.5–3 hours at bus speed. Field reporting describes a *"near-total lack of cellular networks as soon as you leave the two cities."* Store-and-forward isn't a fallback here; it's the normal case. → [coverage research](docs/research/RESEARCH-syria-cellular-coverage-2026-08-07.md)

**Bandwidth is 2G-class.** Measured: **zero LTE cells** recorded across the desert corridor against 402 nationally. Everything is sized for EDGE and gzip is not available on the future embedded target (full-window deflate needs 256 KB — more than an MCU's entire usable SRAM).

**Adoption is voluntary.** Drivers opt in because passengers feel safer and the Ministry sees they drive well. Every design decision has to earn that: nothing is recorded outside a trip, loss of signal is never a violation, opt-out deletes history, and the driver sees exactly what the Ministry sees.

**It must port to hardware later without a rewrite.** A protobuf schema plus a written spec plus a conformance suite — not a shared native library — bind the Kotlin client to a future Rust embedded client.

## The numbers

Everything below is **measured, not estimated**. Several of these were wrong by 2×–1000× in earlier drafts until someone actually ran the arithmetic; the corrections are recorded inline in [`DESIGN.md`](docs/architecture/DESIGN.md).

| Metric | Value |
|---|---|
| Wire format | 60-point batch → **799 B gzipped** (13.3 B/point) |
| Per bus per month | **11.9 MB** (8 h/day × 26 days) |
| Upload time, one batch on slow GPRS | **0.29 s** |
| Total uplink time per month | **78 min on GPRS**, 26 min on EDGE |
| Drain a 115 km backlog | **33 s** |
| Rejected alternative — streaming raw 50 Hz IMU | **894 MB/month** and ~59 min to drain a 4 h backlog |

## Architecture

The phone is **one of several telemetry producers**, not the system.

```
ON DEVICE (permanent, both clients)        ON SERVER (from day one)
  sensors → detect events @ 50 Hz            points + events + snippets
    → seal immutable batches                   → scoring (policy, recomputable)
    → outbox (survives process death)          → portal
```

**Detection lives on the edge** because bandwidth forbids shipping raw IMU. **Scoring lives on the server** because scoring is policy, and policy changes as a pilot teaches you things — server-side it is recomputable across all history instead of an app update into a 12-month tail of un-updated phones.

Three shared artifacts bind the clients together, replacing what would otherwise be a shared native library:

| | |
|---|---|
| [`proto/`](proto/safesy/v1/telemetry.proto) | The wire contract. Additive-only, forever. |
| `spec/` | Sealing, seq allocation, and time rules — prose, not code |
| `conformance/` | Golden fixtures: byte-exact encoding · toleranced detection · process-death scenarios |

## Repo layout

```
proto/          safesy-proto — THE contract, shared by Android, server, future embedded
spec/           Written spec: sealing, seq allocation, time handling
conformance/    Golden fixtures (3 classes)
android/        Kotlin app — minSdk 26, compileSdk 36
server/         Ingest, storage, scoring, portal
docs/
├── architecture/   SPEC.md (what) · DESIGN.md (why, and what was rejected)
├── plans/          BUSINESS_CASE.md
├── research/       Coverage, BOM, market
└── reviews/        Adversarial design reviews
```

## Status

| Step | Deliverable | |
|---|---|---|
| 0 | `safesy-proto` + spec + conformance fixtures | ✅ |
| 1 | Kotlin detection engine + replay harness | ⬜ next |
| 2 | Ingest + storage + scoring + config channel | ⬜ |
| 3 | Android shell — foreground service, Room, outbox | 🔨 scaffolded |
| 4 | **8-hour real-drive spike** — go/no-go gate | ⬜ |
| 5 | Arabic Drive Mode + Ministry map | ⬜ |
| 6 | Pilot — 1 cooperative, 10–20 buses, 4 weeks | ⬜ |

## Getting started

```bash
brew install --cask temurin@17 android-studio
brew install protobuf android-platform-tools scrcpy
```

> ⚠️ **JDK 17 is required for Gradle** even if a newer JDK is installed.
> Android Studio → Settings → Build → Build Tools → Gradle → Gradle JDK → Temurin 17.

Full setup, including MIUI-specific device configuration: [`docs/SETUP.md`](docs/SETUP.md)

## Design principles

These are enforced as invariants, not aspirations — see [`CLAUDE.md`](CLAUDE.md).

- **Trip-scoped collection.** No trip means zero sensors, zero location, zero storage, zero network. The manifest deliberately omits `ACCESS_BACKGROUND_LOCATION`.
- **Loss of signal is never a violation.** It is a normal state that resolves itself.
- **Measure, don't estimate.** And when a number turns out wrong, correct it in place and say so.
- **Behaviour vs. exposure.** Score what the driver chose; report what the schedule imposed. A driver on a harder route must not be structurally punished for it.
- **Governance is a pilot gate, not an appendix.** Retention that forgets locations, a real right of exit, tiered access, and audit logs someone actually reads.

## Honest limitations

- Does **not** prevent accidents directly — it changes behaviour through feedback and shortens response time.
- Crash alerts **do not work in dead zones** in real time. Data is never lost, but alerts wait for reconnect. That is what the Phase-3 LoRa mesh addresses.
- Phone-based IMU detection is **less accurate than fixed hardware**. Cornering and rollover metrics are advisory in Phase 1.
- It is **not an enforcement system**, and the score is not engineered to survive being used as one.

## License

Not yet chosen. Open an issue if you have a view.
