# Syria Cellular Coverage Research — 2026-08-07

Extracted from DESIGN.md §2.6b/2.6c. Establishes the store-and-forward sizing case.

**Headline:** the Damascus–Deir ez-Zor corridor has a 150–180 km continuous dead zone
(~2.5–3 h at bus speed) — roughly 20× the "5–10 km gaps" originally assumed.

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

