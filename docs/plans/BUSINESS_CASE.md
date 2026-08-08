# safeSy — Business Case & Presentation Base

**Purpose:** the narrative and evidence base for a website, a deck, or a Ministry/funder conversation. Structured so each `##` section maps to roughly one slide or one page.

**Discipline:** every claim here is either measured, sourced, or explicitly labelled as an estimate. Overclaiming to a Ministry is how pilots die in month three — and the engineering document behind this one has already been wrong four times and corrected each time in public. That habit is an asset; keep it.

> **Confidence discipline:** **[H]** verified from a primary source · **[I]** calculated, reasoning shown · **[E]** judgment from adjacent data. Nothing unlabelled is presented as fact.

---

## 1. The One-Line Pitch

> **safeSy turns the phone already in every Syrian bus driver's pocket into a national road-safety network — starting today, with no hardware, no new infrastructure, and no cost to the driver.**

**The sharper version, for a Ministry audience:**

> **Syria is rebuilding from war. Reconstruction of road and telecom infrastructure is an $82bn, multi-year programme — but buses run those roads today. WHO estimates 6,383 road deaths a year. Syria's own strategy sets a 10%-by-2030 fatality target and a 15-minute post-crash response target, with no funding or data behind either. safeSy is the interim layer that makes those targets measurable — deployable now, on phones that already exist, and designed to keep improving as real infrastructure arrives.**

## 2. The Problem

Three failures compound on Syrian intercity roads:

| Failure | Evidence |
|---|---|
| **Roads are dangerous** | The Damascus–Deir ez-Zor desert route is single-lane each way, ~6 m wide, potholed, unlit, unsigned. A July 2026 crash between al-Sukhnah and Palmyra killed 35 and injured 30 — nearest hospital 50 km away. |
| **Nobody knows where the buses are** | A breakdown or crash in the desert corridor is invisible until someone drives past. Field reporting: a *"near-total lack of cellular networks isolates travellers as soon as they leave the two cities."* |
| **There is no data** | **WHO estimates 6,383 road deaths a year — 29.9 per 100,000, ~2× the world average** (§11). No enforced speed limits (WHO rates the legislation "weak/none"), no measured coverage map, no per-driver safety record, no evidence base for where to spend repair budget. |

**The gap this fills:** rebuilding roads and towers takes years and billions. **Knowing what is happening on the roads can start this quarter, for the cost of software.**

## 3. Why Now

- **Post-transition reconstruction is active** — highway rehabilitation programs announced for exactly this corridor.
- **Telecom is being rebuilt** — Syria rejoined the GSMA (July 2025); Zain won a 20-year, $1.5bn licence (June 2026), launching ~Q1 2027.
- **Sanctions have eased** — Caesar Act repealed December 2025; the Syria sanctions program revoked July 2025. (BIS export controls on equipment remain — relevant to Phase 2 hardware, not to Phase 1 software.)
- **Smartphone penetration is already there** — ~20.1M mobile connections, 77.7% penetration, 93.7% of connections 3G+.

**The window:** the app works on the network that exists *today*. Every improvement in coverage makes it work better, at no additional cost.

## 4. What It Does — Phase 1

| Capability | Who benefits | Status |
|---|---|---|
| **Continuous trip logging** — position, speed, driving events | Ministry, operator | Works offline; syncs on reconnect |
| **Driver safety score** — route-normalized, fair across corridors | Driver, Ministry | Server-side, recomputable |
| **Live fleet map** (~65 s fresh where covered) | Ministry, operator | Staleness shown honestly |
| **Crash detection & alert** — ~65–70 s from impact to warning nearby buses | Everyone | Covered areas only |
| **SOS button** | Driver, passengers | v1 |
| **National coverage map** — the first empirical measurement of where cellular fails on Syrian highways | Ministry, carriers | **Byproduct, zero extra effort** |

## 5. The Strategic Asset Nobody Else Has

The **coverage dead-zone map** deserves its own slide.

Every bus running safeSy records, every 30 seconds, exactly where cellular works, which generation (2G/3G/LTE), and where it fails — **including explicit "no signal here" records**, which is what crowdsourced datasets structurally cannot capture.

Why this matters:
- **No such dataset exists.** Public crowdsourced data shows *zero cells* in Deir ez-Zor city — a functioning provincial capital with working service. It measures where app users drove, not coverage.
- **Neither operator publishes a coverage map.** ITU has no population-coverage-by-technology data for Syria at all.
- **It is directly actionable** — tells the Ministry and carriers where towers are needed, and tells safeSy where LoRa nodes should go in Phase 2.
- **It requires no behavior change from anyone.**

> A road-safety pilot that also produces the national highway coverage map is two deliverables for one budget.

## 6. Why Drivers Will Actually Use It

Voluntary adoption is the load-bearing assumption. The design earns it structurally rather than assuming it:

| Design commitment | Why a driver cares |
|---|---|
| **Nothing is recorded outside a trip** — zero sensors, zero location, zero network when idle | Not being tracked on personal time |
| **Loss of signal is never a violation** | Not punished for the network's failure |
| **Route-normalized scoring** | A Deir ez-Zor driver is not punished for driving a worse road |
| **Driver sees exactly what the Ministry sees** | No secret file |
| **Opt-out deletes history** | Genuinely revocable, not a one-way ratchet |
| **~12 MB/month** | Doesn't consume their data bundle |
| **SOS + crash alerts** | The app protects *them*, not just watches them |

**The pitch to a driver:** *your passengers see you're monitored for their safety, the Ministry sees you drive well, and if you crash in the desert someone knows within a minute.*

## 7. Phased Plan

| Phase | Scope | Timeline | Investment |
|---|---|---|---|
| **1. Software** | Android app, backend, portal, 10–20 bus pilot | Now → ~6 months | Software only |
| **2. Hardware** | Embedded unit: tamper-proof, always-on, vehicle-powered | +12–18 months | **~$60/unit landed** |
| **3. LoRa mesh** | Solar repeaters covering desert dead zones | +18–24 months | **~$1,100/node** |
| **4. Civic mesh** | Weather sensors, SOS boxes, checkpoint logging on the same network | +24 months | Incremental |

**The app is never retired.** Most buses will never get hardware; a mixed fleet is the steady state. Phase 2 adds tamper-resistance where it's justified, and the app becomes the driver's screen.

## 8. Phase 2 — Hardware Unit Economics

**Confidence labelling:** **[H]** = verified from a distributor product page. **[I]** = calculated, reasoning shown. **[E]** = judgment from adjacent data. Rows 6–12 are estimates and are ~42% of the total — **honest error bar ±20%. Get a turnkey JLCPCB/Seeed quote before any of this is presented as firm.**

| # | Component | 100 u | 1,000 u | 10,000 u | Conf |
|---|---|---|---|---|---|
| 1 | MCU — ESP32-S3-WROOM-1-N16R8 (16MB/8MB) | $3.62 | $3.40 | $2.90 | H/H/I |
| 2 | GNSS — ATGM336H-5N31 | $1.80 | $1.55 | $1.25 | H/I/I |
| 3 | IMU — **ICM-42688-P** | $13.34 | $10.50 | $8.00 | H/I/I |
| 4 | Cellular — **SIMCom A7670SA** (LTE Cat-1 **+ 2G/GPRS/EDGE**) | $7.20 | $6.30 | $5.20 | H/I/I |
| 5 | LoRa — Ra-01SH-P (SX1262) | $3.81 | $3.30 | $2.70 | H/I/I |
| 6 | Storage — SPI NOR 16MB | $1.10 | $0.90 | $0.70 | E |
| 7 | Power — 12/24V buck, 60V-rated + load-dump TVS | $2.60 | $2.10 | $1.60 | E |
| 8 | LiPo 500mAh + charger (tamper "last gasp") | $2.40 | $2.00 | $1.60 | E |
| 9 | Antennas ×3 (GNSS/LTE/LoRa) + u.FL | $4.50 | $3.60 | $2.80 | E |
| 10 | PCB 4-layer + passives + connectors | $5.00 | $3.20 | $2.20 | E |
| 11 | Enclosure (IP54 automotive) | $3.50 | $2.40 | $1.70 | E |
| 12 | Assembly + test + yield | $6.00 | $3.50 | $2.30 | E |
| | **EX-WORKS TOTAL** | **$54.87** | **$42.75** | **$32.95** | |
| | **LANDED IN SYRIA** | ~$70 | **~$60** | ~$45–50 | I |

Landed build-up at 1,000 u: freight $2.50–4.00 · customs+VAT $6–13 *(14–30% assumed, **unverified**)* · clearance $1.50–3.00 · banking friction $1.30–2.60.

### 8.1 Two component decisions worth stating

**Take the good IMU.** The MPU-6050 — conventionally "the cheap one" — is **$10.61/100** at LCSC; the far better **ICM-42688-P is $13.34**. The price gap has evaporated (old part, on allocation). **$2.73 buys materially better noise and temperature stability**, which is crash-detection accuracy. Choosing MPU-6050 to save money costs precision for nothing.

**The modem is a requirement, not a preference.** The A7670SA carries **LTE Cat-1 *plus* GSM/GPRS/EDGE**. A Cat-M/NB-IoT part (BG95) would be a mistake: Cat-M/NB-IoT coverage in Syria is essentially nonexistent, so it would fail in precisely the rural corridors where a crash is least survivable — and §2.6b measured **zero LTE cells** across the desert corridor against 402 nationally.

### 8.2 Sanctions — better than commonly assumed, but do not overstate

- **Caesar Act repealed** (FY2026 NDAA §6211, 18 Dec 2025).
- **BIS License Exception SPP** (2 Sept 2025) generally authorizes **EAR99** exports to Syria — substantially all of this BOM is EAR99.
- Syria removed from the State Sponsors of Terrorism list (2026).

⚠️ **Never say "sanctions are solved."** Say: *the export-control barrier is largely removed; the banking barrier is not.* Correspondent-banking relationships lag legal change by years, and the Syrian import side is unverified.

## 9. Competitive Position — Stated Honestly

**We do not undercut Shenzhen, and we should say so before anyone else does.**

| | Hardware | Per vehicle/month |
|---|---|---|
| Concox GT06N / Jimi IoT | **$15** (floor $5–12 at volume) | $1–3 or self-hosted |
| **safeSy Phase 2** | **~$60 landed** | — |
| Samsara | bundled | **$27–33** (36-month lock-in) |
| Motive | ~$150 | $25–35 |
| Geotab | ~$100–200 | $10–18 |

**Two honest conclusions:**

1. **A $15 Concox tracker beats us on price permanently.** Shenzhen amortizes across millions of units. Any pitch claiming cost leadership will be dismantled in the room.
2. **Samsara/Geotab/Motive are irrelevant as competitors** — they will not sell into Syria, and $27–33/vehicle/month is unpayable against Syrian operator economics. *(For scale: 3 years of Samsara is **$1,080/vehicle** versus a **one-time ~$60** for safeSy hardware — but the relevant point is that they simply are not present.)*

**So the differentiator is not price — it is that a $15 Concox box assumes continuous cellular backhaul and therefore goes dark across the exact corridors that matter.** Store-and-forward plus the LoRa mesh is the defensible position, and it is defensible precisely because **no Shenzhen vendor will build Syria-specific mesh infrastructure.**

**And Phase 1 sidesteps the comparison entirely: marginal cost per vehicle is $0.** The phone already exists.

## 10. Phase 3 — LoRa Mesh

**What it is for — state precisely, because the original plan overstated it:** LoRa is **not** a backup data link. Measured capacity is ~2 KB/hour per device against ~55 KB/hour of telemetry — a 15× shortfall. It carries **liveness beacons and crash alerts only** (~12 bytes). Everything else waits for cellular.

That narrow job is exactly the unmet need: on a corridor where a crash currently goes unnoticed for hours, a 12-byte alert is the difference between a rescue and a fatality.

### 10.1 Per-node cost

| Item | Low | High |
|---|---|---|
| Gateway (Dragino DLOS8N outdoor, IP67, 12–24V) **[H]** | $315 | $315 |
| Solar panel 100–150W | $60 | $120 |
| Battery 100Ah **LiFePO4** (not lead-acid — desert heat kills it) | $180 | $320 |
| MPPT charge controller | $30 | $70 |
| Pole, mounting, IP65 cabinet | $80 | $180 |
| Surge arrestor, grounding, cabling | $40 | $90 |
| Install labour + site visit | $100 | $250 |
| **Per node** | **$805** | **$1,345** |

**Budget ~$1,000–1,200/node.** Sizing assumes ~4W continuous draw (~96 Wh/day), ~3.5 peak-sun-hours winter, ~3 days autonomy.

### 10.2 Spacing — use conservative numbers

Ignore vendor "50–150 km" claims (they assume 50 m masts and clean line-of-sight). Credible field data: **>90% delivery at 11 km** LOS at SF12; ~6 km in non-ideal conditions. Radio horizon ≈ 3.57×√h(m).

**Plan 8–15 km spacing at SF10–SF12 with 8–12 m masts in flat desert.**

### 10.3 The number to put in front of a funder

> **The Damascus–Homs–Hama–Aleppo corridor is ~350 km. At 12 km spacing that is ~29 nodes — roughly $30,000 to cover Syria's main north–south artery with crash-alert coverage.**

For scale: against road trauma costing **$642M–$1,070M/year** (§11.3), a **1% reduction saves $6–11M/year**. The corridor pays for itself many times over — and $30,000 is a genuinely fundable pilot figure rather than an infrastructure programme.

**Known risk:** solar panels and batteries in remote desert are theft targets — documented and recurring (57,079 m of telecom cable stolen in 2023). Mitigations: co-locate at existing secured checkpoints, conceal, tamper-switches that broadcast on opening.

⚠️ **Unverified before committing:** the Syrian regulator's position on 433/868 MHz. Check the band before any hardware order.

## 11. Market Size & The Data Problem — WHO Primary Source

**Source:** [WHO Global Status Report on Road Safety 2023 — Syrian Arab Republic](https://cdn.who.int/media/docs/default-source/country-profiles/road-safety/road-safety-2023-syr.pdf). Verified from the primary PDF, not secondary reporting.

| Indicator | Value |
|---|---|
| Population | 21,324,367 |
| **WHO-estimated** road fatalities (2021) | **6,383** (95% CI 5,269–7,497) |
| WHO-estimated rate | **29.9 per 100,000** |
| Registered vehicles | 2,596,542 |
| Total paved road km | 8,958 |
| National road safety strategy | **Exists — NOT FUNDED** |
| Fatality reduction target | 10% by 2030 |

### 11.1 The framing that matters

> **WHO estimates 6,383 road deaths a year in Syria — 29.9 per 100,000, roughly 7× the EU rate.**

**safeSy is, structurally, a measurement instrument.** Every trip logged is a data point that currently does not exist. That reframes the pitch: this is not "an app that scores drivers," it is **the national road-safety data system Syria's own strategy requires and does not have.**

⚠️ **Presentation note.** The WHO country profile also records 549 *reported* fatalities against its 6,383 estimate. That gap is real and is in the source, but **do not lead with it** — it invites a debate about reporting methodology instead of a conversation about road deaths, and it reads as criticism of the counterpart in the room. Use the WHO estimate alone. If the discrepancy comes up, treat it as a measurement-capability gap that safeSy exists to close.

### 11.2 Severity in context

| | Deaths per 100,000 |
|---|---|
| EU average | 4.2 |
| World average | 15.0 |
| Eastern Mediterranean region | ~18.0 |
| **Syria** | **29.9** |

**~7× the EU rate. ~2× the world average.**

### 11.3 Economic cost

WHO and the World Bank estimate road trauma costs developing economies **3–5% of GDP annually**. Against a deliberately conservative $9bn GDP estimate, that is **$270–450M per year**. Syria's GDP is disputed post-conflict, so treat this as an order-of-magnitude figure — but note that even the *lowest* plausible number dwarfs the cost of a national telematics program.

### 11.4 What the WHO profile confirms about our design

Four items in the profile independently validate design decisions made before we found it:

| WHO finding | What it confirms |
|---|---|
| **Speed-limit legislation rated WEAK/NONE**; no maximum urban, rural, or motorway limit recorded | §S5.3's **segment-relative scoring** is not a workaround — it is the *only* defensible method. There is no legal limit to score against. |
| **Enforcement is MANUAL only** | There is no automated enforcement infrastructure to compete with or duplicate. |
| **Post-crash target: 15 minutes** from crash to professional emergency care on highways | Our ~65–70 s crash alert directly serves an **existing, stated national target**. On the Deir ez-Zor corridor, where the nearest hospital can be 50 km away, this target is currently unmeetable — and unmeasurable. |
| **No agency coordinates pre-hospital emergency care** | The dispatch gap is institutional, not just technical. Frame safeSy as feeding a system that needs building. |

**The strategic framing this unlocks:** Syria has a road safety strategy, a fatality reduction target, and a post-crash response target. It has **no funding and no data**. safeSy supplies the data layer those targets require — which is a far easier thing to fund than a new program, because it makes an *existing* commitment achievable.

## 12. Funding Path

### 12.1 Scale of the opportunity [HARD — World Bank, Oct 2025]
- **Total reconstruction $216bn** (range $140–345bn); **infrastructure $82bn**, the most-damaged category
- Real GDP fell 53% (2010–2022): $67.5bn → **$21.4bn (2024)**

### 12.2 Active transport money
- **World Bank $200M railway recovery grant** (finalized Feb 2026), after Transport Minister **Yarub Badr** met the MENA Regional Director
- July 2026: further World Bank engagement on rail implementation
- $146M Syria Electricity Emergency Project (June 2025)

⚠️ **All disclosed transport money so far is RAIL, not road.** Road-safety telematics is adjacent to, not inside, the current envelope — you would be opening a new line, not joining one. The Ministry *does* have an active World Bank relationship and a named counterpart, which is the useful part.

### 12.3 Who to approach, ranked by fit

1. **Global Road Safety Facility (GRSF)** — World Bank-housed, maintains a Syria country page, exists precisely for this. Small grants, best fit, lowest friction. **Start here.**
2. **World Bank** — best as a road-safety component attached to a larger transport operation.
3. **WFP** — runs the largest logistics fleet in Syria with direct operational self-interest in fleet telematics. **Potentially a first customer, not just a funder** — more valuable than a grant.
4. **Gulf sovereign/strategic** — largest cheques, most political volatility.
5. **EBRD** — no confirmed Syria transport operation found; speculative.

### 12.4 ⚠️ The structural warning — read this before building a revenue model

WHO records Syria's road safety strategy as **"Not funded"**, with **no lead agency** coordinating emergency care. **There is currently no obvious Syrian government budget line that pays for this.**

Realistic near-term revenue is **donor-funded pilots or a commercial operator** (WFP, a bus company, an insurer) — *not* ministry procurement. **Building the model on government purchase is the single most likely way this business case goes wrong.** Raise it with the Ministry as a co-design question rather than assuming a budget exists.

## 13. Risks & Limitations — Stated Plainly

Presenting these builds more credibility than hiding them. Every one has a mitigation.

| Risk | Severity | Mitigation |
|---|---|---|
| **Ministry counterparty latency** — roster, accounts, a named sponsor are outside our control | **Highest** | Secure roster + named contact *before* build step 3 |
| **First-week false positives** poison a cooperative | High | Ship a narrow score drivers trust; hold that line under pressure for a richer one |
| **Xiaomi/MIUI kills background services** | High | Instrument kill-rate per device model from day one; guided Arabic setup |
| **Summer thermal** — 45 °C+ cabin, phone charging, GPS active | High | Go/no-go spike must run in real heat |
| **Coverage worse than assumed** — 150–180 km continuous gap | Medium | Store-and-forward already survives it; measured, not assumed |
| **Planned 2G retirement** could make corridors *worse* before better | Medium | Never depend on GSM fallback |
| **Surveillance misuse** | **Structural** | §6.4 governance commitments as a pilot gate, not an appendix |
| **Coercion drift** — score becomes a licence condition | Structural | Stated in design and MOU: not fit for enforcement use |
| **Detection accuracy on a loose phone mount** | Medium | Score primarily on GPS-derived metrics; IMU events secondary until validated |

### What safeSy explicitly does **not** claim

- It does not prevent accidents directly — it changes behavior through feedback and shortens response time.
- It does not work where there is no cellular coverage **in real time** — data is never lost, but alerts are delayed until reconnect. That is what Phase 3 addresses.
- Phone-based IMU detection is **less accurate** than fixed hardware. Cornering and rollover metrics are advisory in Phase 1.
- It is not an enforcement system, and the score is not engineered to survive being used as one.

## 14. The Ask

**Framing note:** safeSy is volunteer-built and non-commercial. There is no company, no licence fee, and nothing being sold. That is a genuine strength in every one of these conversations — the ask is for access, permission, and data, not for a procurement decision. It also means the sustainability question is real and should be raised honestly: volunteer capacity is finite, and a pilot that succeeds will need a maintenance path.

*Tailor per audience.*

- **Ministry:** roster access, a named operational contact, one cooperative for a 4-week pilot, and a written data-use agreement.
- **Funder:** Phase-1 pilot cost, against a measurable outcome — trip completeness, driver retention, and the national coverage map as a hard deliverable.
- **Operator:** 10–20 buses for 4 weeks, drivers keep their phones, no cost, no obligation.

## 15. Proof Points — Pre-Registered

Pilot success is defined *before* it runs, so it cannot succeed by anecdote:

| Metric | Target |
|---|---|
| Trip completeness | ≥95% |
| Upload success within 24 h | ≥99% |
| IMU false positives | < N/100 km, **validated by ride-along ground truth** |
| Driver retention at 4 weeks | ≥80% |
| Thermal shutdowns | Zero |
| Coverage map | First empirical measurement of the corridor |
