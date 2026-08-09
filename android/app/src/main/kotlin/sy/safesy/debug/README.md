# Section: Debug & Drive Testing

**What lives here:** the drive-test harness — session recording, live metrics, GNSS diagnostics, permission checks, and full-rate raw capture.

**Owner:** unassigned · **Status:** ✅ working; used for all real-hardware testing

---

## Why this exists

The Step 4 drive spike is a **go/no-go gate** measured on a real bus over 8 hours in summer heat. Two constraints follow from that:

1. **A tester in a moving vehicle cannot read logcat.** Every number that decides pass/fail has to be on the screen.
2. **A drive test with no record is an anecdote.** Everything is written to files that survive the app being killed — and MIUI kills apps.

⚠️ **This is not the production path.** `SensorPump` is a debug harness that runs foreground-only and writes CSV directly to external storage. The real path is the foreground service in [`policy/`](../policy/README.md) plus [`outbox/`](../outbox/README.md), which is Step 3 and does not exist yet.

## Pages

| | |
|---|---|
| **SESSION** | Name a session, pick placement, start/stop, tap one-tap ground-truth marks |
| **METRICS** | Live sensor rates, position, detection state, radio, data usage |
| **GNSS** | Per-satellite C/N0 — separates "acquiring" from "receiving nothing" |
| **PERMISSIONS** | Every requirement with live status, tappable to fix |

Navigate with the ◀ ▶ arrows, pinned so they never scroll away.

## Ground truth is the point

SPEC §8 makes labeled ride-along data a **pilot pass/fail requirement**: *"IMU false positives < N/100 km validated by ride-along ground truth."*

Without a human saying "I braked hard **here**", a detected event cannot be scored true or false — you can only count events, not judge them. So the tester marks what actually happened, and `tools/replay.py` cross-references those marks against detector output.

Mark buttons are large and single-tap because they are pressed in a moving vehicle, often without looking. **A mark that is awkward to record will not be recorded.**

## Files a session produces

```
session-<id>/
├── meta.txt      name, start time, phone placement
├── marks.csv     human ground truth: elapsed_s, label
├── trace.csv     1 Hz summary — everything on the metrics screen
├── events.csv    detector output
└── raw-imu.csv   FULL 50 Hz raw IMU (~5.5 MB/driving-hour)
```

**Why full raw during testing:** production ships events only (~11.9 MB/month vs ~894 MB), but a drive on Syrian roads is expensive and a replay is free. One recording answers *"what would threshold X have done?"* for any X.

Raw values are logged **before any processing** — device-frame, not gravity-corrected — so correction can be re-run differently later. Buffered and flushed every 250 rows, because appending 50×/second unbuffered would distort the very timing we are measuring, and an unflushed buffer is data that never existed.

## Design findings that came out of this harness

- **Dark text on white beats a dark theme outdoors.** An LCD's black pixels still emit light, so white-on-black washes out in sun. Worth carrying into Drive Mode.
- **Two bugs were found by putting numbers in front of a human**: `rssi` rendering Android's `Integer.MAX_VALUE` "unknown" sentinel raw, and the accel breakdown reading `0.00` during the exact 30 s window a tester stares at it.
- **`MOUNT_SHIFTED` fired 24 times in 3 minutes** on a real trace — the reference was re-anchoring to a *moving* orientation. Invisible in synthetic tests.

## Phone-usage tracking

Records app-background time and proximity "held to ear" episodes. Drivers take calls while driving; a dataset that pretends otherwise does not describe reality.

Two reasons: a trace gap could be a dead zone, a killed process, *or* a phone call — indistinguishable otherwise. And a phone lifted to an ear rotates ~90° and accelerates hard, which reads as violent cornering.

⚠️ **Collected under Ministry-enrolment consent; deliberately not a scoring input.** See DESIGN.md §3.3.

## Pulling a session

```bash
adb pull /sdcard/Android/data/sy.safesy/files/session-<id> .
tools/replay.py session-<id>/ --sweep
```

## Contributing

Good first tasks:
- Session comparison view — two placements on the same route, side by side
- Export a session as a single zip for sharing
- Automatic detection of "vehicle is moving" so calibration waits for stillness

## Related

Drives [`detect/`](../detect/README.md) · Will be replaced in production by [`policy/`](../policy/README.md) + [`outbox/`](../outbox/README.md) · Replay: `tools/replay.py`
