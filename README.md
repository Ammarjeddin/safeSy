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

> ### This is volunteer work
>
> **safeSy is built by volunteers, for free, and it is open to anyone who wants to help.**
>
> There is no company behind it and nothing to sell. The goal is simply to keep people alive on Syrian roads and to reduce, by whatever small amount is possible, the suffering that Syrians have already endured too much of.
>
> If you can write Kotlin, run a backend, test on a real bus route, translate an interface into proper Arabic, or tell us what drivers actually need — **you are welcome here.** See [Contributing](#contributing).

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

## Sections

The repo is organised into **sections** — each a directory with its own README, its own invariants, and its own contribution guide. **You can work on one section without understanding the others.** Pick the one that matches what you want to do.

| Section | What it is | Needs | Status |
|---|---|---|---|
| [**Detection**](android/app/src/main/kotlin/sy/safesy/detect/) | Crash & driving-behaviour detection from 50 Hz IMU + GPS. Pure computation — no Android APIs. | Kotlin, signal processing | Core done |
| [**Policy**](android/app/src/main/kotlin/sy/safesy/policy/) | Trip lifecycle and the rules for when the app may collect anything. Privacy guarantees live here. | Kotlin | Partial |
| [**Outbox**](android/app/src/main/kotlin/sy/safesy/outbox/) | Durable storage, batch sealing, and upload across a network that fails for hours. | Kotlin, Room, WorkManager | Stub |
| [**App UI**](android/app/src/main/kotlin/sy/safesy/ui/) | Drive Mode, trip screens, SOS, onboarding. **Arabic-first.** | Compose, design, **native Arabic** | Not started |
| [**Debug harness**](android/app/src/main/kotlin/sy/safesy/debug/) | Drive-test tooling: sessions, ground-truth marks, live metrics, GNSS diagnostics | Kotlin, Compose | ✅ working |
| [**Server & Portal**](server/) | Ingest, storage, scoring, Ministry portal, enrolment. No Android needed. | Backend, PostGIS | Not started |
| [**Wire Protocol**](proto/) | The contract shared by every client. Additive-only, forever. | protobuf | v1 done |
| [**Spec**](spec/) | Sealing, `seq`, and time rules as prose. | Writing | Not started |
| [**Conformance**](conformance/) | Golden fixtures keeping two implementations identical. | Any language | Not started |

```
proto/ spec/ conformance/     the shared contract
android/app/src/main/kotlin/sy/safesy/
├── detect/  policy/  outbox/  ui/
server/                        ingest, scoring, portal
docs/
├── architecture/   SPEC.md (what) · DESIGN.md (why, and what was rejected)
├── plans/          BUSINESS_CASE.md
└── research/       Coverage, BOM, market
```

## Status

**The app runs on real hardware and records analysable drives.** It does not yet store to Room, seal batches, or talk to a server.

| Step | Deliverable | |
|---|---|---|
| 0 | `safesy-proto` + spec + conformance fixtures | 🟡 proto done; spec/conformance empty |
| 1 | Kotlin detection engine + replay harness | ✅ **done** |
| 2 | Ingest + storage + scoring + config channel | ⬜ |
| 3 | Android shell — foreground service, Room, outbox | 🟡 debug harness only |
| 4 | **8-hour real-drive spike** — go/no-go gate | ⬜ short drives so far |
| 5 | Arabic Drive Mode + Ministry map | ⬜ |
| 6 | Pilot — 1 cooperative, 10–20 buses, 4 weeks | ⬜ |

**Measured on a Xiaomi Mi 11 Lite 5G:** IMU 49.9 Hz sustained · gravity separation leaves **0.041 m/s² of 9.81** · **zero false positives** across 75 s of real driving · GNSS TTFF 24 s in open sky.

⚠️ **Every detection threshold is still a placeholder** that has never met a Syrian road. Full status, including what is assumed rather than proven: [`docs/STATUS.md`](docs/STATUS.md)

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

## Contributing

**This is volunteer work and help is genuinely wanted.** You do not need to be a telematics expert — most of what's needed isn't specialist.

**Most useful right now:**

| | |
|---|---|
| 🇸🇾 **Arabic & RTL** | Native review of the interface. Machine translation is not good enough for something a driver reads at 90 km/h. Also: does Syria use Western (0-9) or Eastern (٠-٩) numerals in transport contexts? |
| 🚌 **Ground truth from Syria** | What do drivers actually need? What would make them uninstall it? Which routes are worst? Do buses return to a depot at night? |
| 📱 **Android / Kotlin** | Foreground services, Room, sensor fusion. The detection engine (Step 1) is the next big piece. |
| 📡 **Coverage data** | Anyone driving Syrian highways with a logging app produces data that does not currently exist anywhere. |
| 🖥️ **Backend** | Ingest, PostGIS/TimescaleDB, scoring service. |
| 🔍 **Review** | Tell us where the design is wrong. Several numbers here were wrong by 2×–1000× until someone checked the arithmetic — that habit is why they got caught. |

**Before you start:** read [`CLAUDE.md`](CLAUDE.md) for the non-negotiable invariants. They look arbitrary without context and each one is there because getting it wrong loses data or breaks a privacy guarantee.

Open an issue or a PR. Questions and corrections are as welcome as code.

## Ethics

This system tracks the movements of named individuals in a country where that has historically been dangerous. That is taken seriously, not as an afterthought:

- **Nothing is recorded outside an active trip.** Not a policy — the app requests no background location permission at all.
- **Opt-out deletes history**, not just future collection.
- **Location detail ages out.** Scores persist; coordinates do not.
- **Live individual tracking is access-tiered** separately from aggregate views, with audit logs someone is responsible for reading.
- **It is not an enforcement instrument**, and the score is not engineered to survive being used as one.

Full commitments: [`docs/architecture/SPEC.md`](docs/architecture/SPEC.md) §S6.4.

## License

Not yet chosen — likely a permissive open-source license consistent with the volunteer, non-commercial intent. Open an issue if you have a view.
