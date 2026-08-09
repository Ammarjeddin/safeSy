# Project Status

**Last updated:** 2026-08-08 · **Branch:** `main` · **12 PRs merged** · **32 tests passing**

A running record of what exists, what is proven, and what is assumed. Kept honest deliberately — several numbers in this project were wrong by 2×–1000× until someone ran the arithmetic, and the habit of separating *measured* from *assumed* is why they were caught.

---

## Where we are

**Step 1 of the build order is done** (detection engine). **Step 3 is partially done** via a debug harness rather than the production foreground service. Steps 0, 2, 4, 5, 6 remain.

The app runs on real hardware, records real drives, and produces analysable data. It does **not** yet store to Room, seal batches, or talk to a server.

| Step | Deliverable | Status |
|---|---|---|
| **0** | `safesy-proto` + spec + conformance fixtures | 🟡 proto done; spec/ and conformance/ still empty |
| **1** | Kotlin detection engine + replay harness | ✅ **done** |
| **2** | Ingest + storage + scoring + config channel | ⬜ not started |
| **3** | Android shell — foreground service, Room, outbox | 🟡 debug harness only; no service, no Room, no outbox |
| **4** | 8-hour real-drive spike (go/no-go gate) | ⬜ short drives only so far |
| **5** | Arabic Drive Mode + Ministry map | ⬜ not started |
| **5b** | Enrolment, roster import, portal auth | ⬜ not started |
| **6** | Pilot — 1 cooperative, 10–20 buses | ⬜ not started |

---

## What is measured, on real hardware

Test device: **Xiaomi Mi 11 Lite 5G**, Android 13, MIUI 14, 7.4 GB RAM, no SIM (WiFi/hotspot only).

| | Result |
|---|---|
| IMU sample rate | **49.9 Hz** sustained (spec assumes 50) |
| Calibration | completes at 30 s as designed |
| **Gravity separation, stationary** | **0.041 m/s² residual from 9.81** — 99.6% removed |
| **Gravity separation, driving** | horizontal mean **0.877 m/s²**, peak 3.22 — plausible driving values |
| **False positives on a real drive** | **zero** across 75 s of driving |
| GNSS TTFF (open sky) | **24 s**, 18 satellites used |
| GNSS in poor sky | 26 dB-Hz average, **never converges** |
| Wire format | 799 B/batch gzipped → **~11.9 MB/month/bus** |

**The near-miss worth remembering:** GPS acceleration reached **−2.93 m/s²** against a −2.5 threshold. One sample crossed it and correctly did *not* fire, because the sustained-duration filter rejected a single-sample excursion. That defence had been shipped untested until mutation testing exposed it.

---

## What is assumed, not proven

Listed so nobody mistakes them for findings.

1. **Every detection threshold is a placeholder.** They come from the spec and have never met a real Syrian road or a real bus suspension. We know they produce no false positives on gentle driving; we do **not** know they catch true positives.
2. **`PlacementClassifier` thresholds are uncalibrated.** Never validated against labelled pocket/cradle sessions. The manual selector exists precisely to check it.
3. **The fixed-mount assumption may not hold.** Syrian drivers likely use a pocket or a loose dashboard, not a cradle. Untested.
4. **No thermal data in real heat.** All testing has been indoors or in mild weather. The 45 °C+ cabin case is a named go/no-go risk and remains unmeasured.
5. **Nothing has been tested on a 2–3 GB device.** The test phone has 7.4 GB, which is generous by fleet standards.
6. **MIUI background-kill has not been exercised.** The debug harness runs foreground-only.
7. **Coverage gap figures are inference**, not drive-test measurement — settlement geography plus official statements, with no public drive test of the corridor.

---

## Decisions taken today

| Decision | Rationale |
|---|---|
| **Phone-handling: collect + transmit, do not score** | Owner decision to collect under Ministry-enrolment consent. Scoring stays off because proximity + app-background identifies the *phone*, not *who* handled it or whether the bus was moving — a misattributing score creates disputes with no resolution. Recorded as a recommendation, liftable. |
| **Placement inferred, not asked** | A driver will not select a mode before every trip, and a wrong answer is worse than none. Classified from orientation variance, vibration coupling, and proximity; held for the trip so thresholds cannot move mid-journey. |
| **Full 50 Hz raw IMU during testing** | Production stays events-only (~11.9 MB vs ~894 MB). But a drive on Syrian roads is expensive and a replay is free — one recording can answer "what would threshold X have done?" for any X. |
| **GNSS monitor at Application scope** | A receiver that powers down on navigation cold-starts again, 30–90 s each time. |

---

## Corrections made to earlier claims

Kept visible rather than quietly edited.

- **"No SIM → no A-GPS → GPS cannot work"** — **wrong**. GPS works without A-GPS, just slower. The real cause of the failed drives was weak reception (26 dB-Hz near buildings). In open sky it locked in 24 s on the same phone, same missing SIM.
- **"~6–8 B/point on the wire"** — wrong by ~2×. Measured 12.4 B/point.
- **"500 buses = 436 TB/month"** — wrong by 1000× (unit error). Correct: ~437 GB.
- **"48 h hot window = 140 GB"** — wrong again; conflated clock hours with driving hours. Correct: 3.4 GB.
- **"Backlog never converges"** — overstated. It converges at ~4× drain rate with continuous coverage; non-convergence needs a coverage duty cycle below ~25%.

---

## Next session

**Drive tests.** The app is ready: named sessions, inferred placement, one-tap ground-truth marks, always-warm GPS, full raw IMU capture.

Highest value, in order:

1. **Deliberate hard brake, marked.** We know the detector produces no false positives; we do not know it catches true ones.
2. **Same route twice — `CRADLE` then `SHIRT POCKET`.** Directly answers whether the fixed-mount assumption survives real usage, and validates the classifier against your manual label.
3. **Higher speed**, closer to highway conditions.

Afterwards: `adb pull` the session folder and `tools/replay.py session-dir/ --sweep` to tune thresholds against real data instead of spec placeholders.
