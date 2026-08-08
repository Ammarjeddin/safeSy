# Section: Outbox (Patch Uploader)

**What lives here:** durable storage of trip data, sealing it into immutable batches, and getting those batches to the server across a network that fails for hours at a time.

**Owner:** unassigned · **Status:** stub (Step 3/4)

---

## Why this is its own section

Store-and-forward is not a fallback here — it is the **normal case**. The Damascus–Deir ez-Zor corridor has an estimated **150–180 km continuous dead zone**, about 2.5–3 hours at bus speed. A bus goes offline, keeps recording, and delivers everything on reconnect.

This section is where data is either preserved or silently lost. It is the most correctness-critical code in the app, and it has the subtlest failure modes.

## Invariants — bugs if violated, even when tests pass

1. **`seq` is allocated at *seal time*, in the same storage transaction as the bytes.** Never from an in-memory counter — a force-kill would replay a sequence number against *different content*, and the server's dedupe would silently accept the corruption.
2. **Sealed batches are immutable.** No re-encoding at a different resolution. The server dedupes on `(trip_id, seq)`, so a re-encoded batch lets the fuller version be discarded forever.
3. **Points are written at 1 Hz as they arrive.** Batching the *writes* leaves the final minute before a crash in RAM only — exactly the data the system exists to capture.
4. **`POSSIBLE_CRASH` seals immediately** and drains first, regardless of batch cadence.
5. **A rejected batch is quarantined and flagged, never silently dropped.**

## Drain order matters, not just drain

Reconnection windows are **minutes long** while passing a town. If the window closes early, what crossed first is what you keep:

```
1. POSSIBLE_CRASH        (someone may be dying)
2. Recent status         (is the bus alive and where)
3. Bulk backlog          (everything else)
```

## Network reality

Measured against real Syrian conditions — see [`docs/research/`](../../../../../../../../docs/research/).

| | |
|---|---|
| One 60 s batch | **799 B gzipped** |
| Upload on slow GPRS | **0.29 s** |
| Total uplink per month | **78 min on GPRS**, 26 min on EDGE |
| Drain a 115 km backlog | **33 s** |
| Per bus per month | **~11.9 MB** |

**2G is not the constraint.** What costs time is **session setup** — GPRS attach is 2–5 s plus 1–3 s of TLS, against a 0.29 s upload. So:

- **Reuse connections** (HTTP/2 keep-alive) rather than handshaking per batch.
- **Make transfers resumable.** The real failure at a cell edge is attach → start upload → drop mid-transfer, repeatedly.
- **Debounce connection state.** One successful attach is not a stable window.

## The MIUI problem

Both test devices are Xiaomi, which is the **worst-case** background-kill environment — good for testing, brutal in production.

A force-swipe on MIUI is effectively `force-stop`, which suspends **WorkManager *and* the boot receiver** until the user manually launches the app. Recovery is a persistent Arabic notification prompting one tap.

⚠️ **Do not claim this is automatic.** Design for the tap.

## Files

| File | |
|---|---|
| `BootReceiver.kt` | Schedules an outbox drain on boot — no sensors |
| *(Step 3)* | Room entities, `BatchSealer`, `OutboxWorker`, `IngestClient` |

## Contributing

Good first tasks:
- Room schema for points, events, radio samples, and the outbox
- `BatchSealer` — seal 60 points into a `TelemetryBatch`, allocate `seq` transactionally
- `OutboxWorker` — WorkManager + `NetworkCallback` for immediate drain on reconnect
- **Process-death tests** — kill the process mid-seal and assert nothing is lost or duplicated

**Before changing anything:** invariants 1 and 2 are the ones that corrupt data silently. Any change touching `seq` or batch immutability needs a test that kills the process at the exact wrong moment.

Run tests: `cd android && ./gradlew testDebugUnitTest --tests '*outbox*'`

## Related

Gated by [`policy/`](../policy/README.md) · Packs events from [`detect/`](../detect/README.md) · Talks to [`server/`](../../../../../../../../server/README.md) · Wire format: [`proto/`](../../../../../../../../proto/README.md)
