# Section: Policy

**What lives here:** the trip lifecycle state machine and the rules that decide *when* the app is allowed to collect anything at all.

**Owner:** unassigned · **Status:** partial (state machine done, service wiring is Step 3)

---

## Why this is its own section

Everything else in the app is downstream of one question: *is a trip in progress?* This section answers it, and the answer is a **privacy guarantee**, not a performance optimisation.

If this section is wrong, the app records when it shouldn't. That is the failure mode that ends voluntary adoption, so the rules live in one place where they can be read and audited without understanding sensors, protobuf, or networking.

## The state machine

```
IDLE ──[Start]──> ACTIVE ──[End]──> CLOSING ──(drained)──> SETTLED
                     │
                     ├─[<5 km/h for 45 min]──> PAUSED (motion resumes)
                     └─[process death]───────> ORPHANED
                                                  ├─ resumable → ACTIVE (same trip_id)
                                                  └─ else      → CLOSED_INCOMPLETE
```

| State | Sensors | Storage | Network |
|---|---|---|---|
| `IDLE` | ✗ | ✗ | ✗ |
| `ACTIVE` | ✓ | ✓ | ✓ |
| `PAUSED` | ✗ | ✗ | ✓ |
| `CLOSING` | ✗ | ✗ | ✓ |
| `ORPHANED` | ✗ | ✗ | ✓ |
| `SETTLED` / `CLOSED_INCOMPLETE` | ✗ | ✗ | ✗ |

## Invariants — these are bugs if violated, even when tests pass

1. **`IDLE` is inert.** Zero sensor subscriptions, zero location requests, zero stored points, zero network. The manifest deliberately omits `ACCESS_BACKGROUND_LOCATION` — do not add it.
2. **`ORPHANED` still drains its outbox.** A crash is exactly the scenario where the process dies *and* the data matters most.
3. **Auto-PAUSE, never auto-end.** Checkpoint and border queues are routine in Syria. Auto-ending there splits one journey into two trips and silently loses the post-queue driving.
4. **Bias toward `CLOSED_INCOMPLETE`.** Wrongly splitting one trip is far cheaper than wrongly merging two separate journeys.
5. **Loss of signal is never a violation.** It is a normal state that resolves itself.

## The clock problem

`OrphanResolver` decides whether a trip that died mid-route should resume. Which clock it uses is subtle:

- `elapsedRealtime` has just **reset** — that is exactly what `boot_id` detects.
- The wall clock is **untrusted by design**: cheap phones boot with no valid time, users change it, NTP corrections land mid-trip.
- So: prefer **GNSS time** deltas. Fall back to wall clock with **one-third the tolerance** and a data-quality flag.

The proximity check needs one location fix while `IDLE`. That is an **explicit carve-out** from invariant 1 — a single one-shot fix at launch, only when an `ORPHANED` trip exists, disclosed in onboarding. It must never become background location.

## Files

| File | |
|---|---|
| `TripState.kt` | States, allowed transitions, `OrphanResolver` |
| `../trip/TripService.kt` | Foreground service (Step 3 — will move here) |

## Contributing

Good first tasks:
- Auto-PAUSE detection (speed < 5 km/h for 45 min, motion-triggered resume)
- Wire 12 V power presence as a "still on the bus" signal
- The Arabic onboarding text explaining the `IDLE` guarantee

**Before changing anything:** a change here can silently break a privacy promise. Add a test that fails before your change and passes after — and if you weaken a guarantee, say so explicitly in the PR.

Run tests: `cd android && ./gradlew testDebugUnitTest --tests '*policy*'`

## Related

[`detect/`](../detect/README.md) runs only while `ACTIVE` · [`outbox/`](../outbox/README.md) drains in every state except `IDLE` · Spec: [`docs/architecture/SPEC.md`](../../../../../../../../docs/architecture/SPEC.md) §S2
