# Section: Android App UI

**What lives here:** everything the driver sees. Drive Mode, trip start/end, post-trip score, SOS, onboarding.

**Owner:** unassigned · **Status:** placeholder (Step 5)

---

## Why this is its own section

This section needs **no** knowledge of sensor fusion, protobuf, or store-and-forward. If you want to contribute design or Compose work, you can work entirely inside this directory.

It is also the section where the project most needs help from someone who actually knows Syrian drivers — see [Contributing](#contributing).

## The constraint that shapes everything: this is used while driving

A bus driver at 90 km/h on an unlit, unsigned, single-lane desert road is looking at this screen. Any UI decision that pulls their eyes from the road **makes the app the cause of the accident it exists to prevent.**

**Drive Mode rules:**

| | |
|---|---|
| Above 15 km/h | Screen locks into a minimal, dark, high-contrast view |
| Shows | Current speed, large. Trip elapsed. A quiet sync indicator. |
| Warnings | **Haptic and audio only.** Never a visual that requires a glance. |
| Everything else | Behind the trip-ended screen — score breakdown, history, event list |

## Arabic is the primary language, not a translation

`values-ar/` is the source of truth; `values/` is the English development fallback. Specific traps, because RTL apps usually fail on these:

1. **Use `start`/`end`, never `left`/`right`** — Compose mirrors automatically, but only if you never hardcode a side.
2. **Numerals:** Syria uses **Western Arabic numerals (0-9)** in most official transport contexts, not Eastern (٠-٩). ⚠️ **This is unconfirmed** — see Contributing.
3. **Speed and units are LTR runs inside RTL text.** Wrap them in Unicode isolates or the digits visually reorder next to punctuation.
4. **Test with Arabic as the *system* locale**, not just an in-app toggle. They behave differently.
5. **Ship Noto Naskh Arabic.** Do not trust OEM defaults — Tecno and Infinix ship inconsistent Arabic fonts.

## What the driver sees, and deliberately does not

| | |
|---|---|
| **Live speed + within/over indicator** | ✅ Yes — changes behaviour in the moment, and it's the same number the Ministry sees |
| **Live score** | ❌ No — invites gaming and pulls eyes off the road |
| **Post-trip breakdown** | ✅ Yes — *exactly* the data the Ministry has. Symmetry is the trust argument. |
| **SOS button** | ✅ v1. One tap. |

⚠️ **"Over the limit" needs care.** Syrian highways have **no enforced speed limits** and no reliable `maxspeed` dataset. Unless the Ministry declares limits per road class, the indicator is an **advisory speed** and must be labelled as such. Presenting an advisory as a violation is exactly the wrongness that destroys credibility under voluntary adoption.

## The `IDLE` screen is a privacy statement

When no trip is running, the app says so plainly in Arabic: *nothing is being recorded.* That sentence is load-bearing — it is what makes voluntary adoption credible, and it happens to be **literally true** (the app requests no background location permission at all).

Do not bury it in settings.

## Files

| File | |
|---|---|
| `MainActivity.kt` | Placeholder — Step 5 replaces it |

## Contributing

**This section needs non-code help as much as code.** Most valuable:

| | |
|---|---|
| 🇸🇾 **Native Arabic review** | Machine translation is not good enough for text read at 90 km/h |
| 🔢 **Settle the numeral question** | Western (0-9) or Eastern (٠-٩) in Syrian transport documents? Check a real bus ticket or Ministry form. |
| 🚌 **Driver research** | What would make a driver uninstall this? What do they actually want to see? |
| 🎨 **Drive Mode design** | Sunlight-readable, glanceable in under a second, minimal |
| 📱 **Compose implementation** | Drive Mode, trip screens, onboarding, SOS |

Good first code tasks:
- Drive Mode composable with the speed display
- Trip start/end flow with the Arabic confirmation dialogs
- Onboarding that explains the `IDLE` guarantee and the right of exit
- SOS button with a confirm-to-send interaction (it must be hard to press by accident, easy to press on purpose)

**Testing on a real device matters here.** `scrcpy` mirrors the phone to your Mac, which is much faster than squinting at a handset for RTL review.

## Related

Reads trip state from [`policy/`](../policy/README.md) · Shows sync status from [`outbox/`](../outbox/README.md) · Score comes from [`server/`](../../../../../../../../server/README.md), computed there not here
