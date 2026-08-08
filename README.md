# safeSy — Syrian Road Safety Telematics

Driver-safety telematics for Syrian buses and minibuses. Android-first, Arabic-first, built for degraded roads and unreliable cellular coverage.

**Phase 1 (current):** Android driver app + backend + Ministry portal. No hardware, no new infrastructure — it runs on the phone the driver already owns.

---

## Why

| | |
|---|---|
| Syria **reports** ~549 road deaths/year | WHO **estimates** **6,383** |
| | **An 11.6× measurement gap** |

Syria has a national road safety strategy with a 10%-by-2030 fatality target and a 15-minute post-crash response target. It has **no funding and no data**. safeSy is the measurement layer those existing targets require.

Full evidence base: [`docs/plans/BUSINESS_CASE.md`](docs/plans/BUSINESS_CASE.md)

## Architecture in one paragraph

The phone is **one of several telemetry producers**, not the system. A producer-agnostic protobuf wire format is the only genuinely shared artifact between the Android client (Kotlin) and a future embedded client (Rust) — bound by a written spec and a conformance suite rather than a shared native library. Detection runs on the edge at 50 Hz because bandwidth forbids shipping raw IMU; scoring runs on the server because scoring is policy and policy changes. Everything is trip-scoped: no trip means zero sensors, zero storage, zero network.

- **What we're building:** [`docs/architecture/SPEC.md`](docs/architecture/SPEC.md) — settled decisions, 8 systems
- **Why, and what we rejected:** [`docs/architecture/DESIGN.md`](docs/architecture/DESIGN.md) — reasoning record, including corrections to earlier wrong claims

## Repo layout

```
proto/          # safesy-proto — THE contract. Shared by Android, server, future embedded.
spec/           # safesy-spec — sealing, seq allocation, time rules (prose, not code)
conformance/    # Golden fixtures: A) byte-exact encoding B) toleranced detection C) scenarios
android/        # Kotlin app — Gradle, minSdk 26, compileSdk 36
server/         # Ingest, storage, scoring, portal
docs/
├── architecture/   SPEC.md, DESIGN.md
├── plans/          BUSINESS_CASE.md
├── decisions/      Point-in-time architecture decisions
├── research/       Coverage, BOM, market research
└── reviews/        Adversarial design reviews
```

## Getting started

See [`docs/SETUP.md`](docs/SETUP.md) — macOS + Xiaomi test devices.

```bash
brew install --cask temurin@17 android-studio
brew install protobuf android-platform-tools scrcpy
```

⚠️ **JDK 17 is required for Gradle** even if you have a newer JDK installed. Android Studio → Settings → Build → Build Tools → Gradle → Gradle JDK → Temurin 17.

## Build order

| Step | Deliverable | Status |
|---|---|---|
| **0** | `safesy-proto` + spec + first conformance fixtures | ✅ Done |
| **1** | Kotlin detection engine + replay harness | ⬜ Next |
| **2** | Ingest + storage + scoring + config channel | ⬜ |
| **3** | Android shell: foreground service, Room, outbox | 🔨 Scaffolded |
| **4** | **8-hour real-drive spike** — go/no-go gate | ⬜ |
| **5** | Arabic Drive Mode + Ministry map | ⬜ |
| **5b** | Enrolment, roster import, portal auth, crash reporting | ⬜ |
| **6** | Pilot: 1 cooperative, 10–20 buses, 4 weeks | ⬜ |

## Key constraints

- **Trip-scoped only.** `IDLE` = zero sensors, zero location, zero network. This is a privacy guarantee, and it is what makes voluntary adoption credible.
- **Loss of signal is never a violation.** Expect a 150–180 km continuous dead zone on the Damascus–Deir ez-Zor corridor. Store-and-forward is the normal case.
- **~11.9 MB/month/bus** — measured, not estimated. 2G/EDGE is sufficient.
- **Voluntary adoption.** Drivers opt in. Every design decision has to earn that.
- **Portability.** Nothing may trap the wire format or detection rules in Android-only code.
