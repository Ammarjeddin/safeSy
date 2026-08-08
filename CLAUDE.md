# safeSy — Working Notes for Claude

## What this is

Driver-safety telematics for Syrian buses. Android-first (Kotlin), Arabic-first, deployed where roads are degraded and cellular coverage fails for 150+ km at a stretch.

Read [`docs/architecture/SPEC.md`](docs/architecture/SPEC.md) for settled decisions. Read [`docs/architecture/DESIGN.md`](docs/architecture/DESIGN.md) only when you need to know *why* something is the way it is, or what was already rejected — it is long, and it deliberately records corrections to earlier wrong claims.

## Environment

- Monorepo root: `/Users/ammarjeddin/Local/Projects/safeSy`
- **JDK 17 required for Gradle** (`$JAVA_HOME_17`) even though Java 26 is the system default. AGP does not handle 26 reliably.
- `ANDROID_HOME=~/Library/Android/sdk`
- Test devices: **2× Xiaomi**. MIUI is the worst-case background-kill environment — that is a feature for testing, not a problem.
- Gradle: `cd android && ./gradlew assembleDebug`

## Non-negotiable invariants

Violating any of these is a bug even if tests pass:

1. **Trip-scoped collection.** `IDLE` means zero sensors, zero location requests, zero storage, zero network. The manifest deliberately omits `ACCESS_BACKGROUND_LOCATION` — do not add it.
2. **`seq` is allocated at seal time, inside the same storage transaction as the bytes.** Never from an in-memory counter: a force-kill would replay a seq against different content and silently corrupt via the dedupe path.
3. **Sealed batches are immutable.** No re-encoding at a different resolution. The server dedupes on `(trip_id, seq)`, so a re-encoded batch would let the fuller version be discarded forever.
4. **Points are written to Room at 1 Hz as they arrive.** Batching writes leaves the final minute before a crash in RAM only — exactly the data the system exists to capture.
5. **Never average raw IMU.** A ~120 ms crash impact averaged into 500 ms buckets keeps 14% of its peak and the threshold never fires. Use the envelope form (min/max/RMS) if you must compress.
6. **`gnss_t_ms` is set only for genuine `GPS_PROVIDER` fixes.** `Location.getTime()` returns the untrusted system clock for network/fused fixes. Order by `getElapsedRealtimeNanos()`.
7. **Loss of signal is never a violation.** It is a normal state that resolves itself.
8. **The proto lives at `proto/` and is never duplicated** into `android/`. One schema, shared with the server and the future embedded client.

## Style

- Conventional commits with scopes: `feat(trip):`, `fix(sync):`, `docs(spec):`, `chore(deps):`
- Comment the *why*, especially where a decision looks wrong without context. The proto file is the model for this.
- Prefer measuring over estimating. Several numbers in this project were wrong by 2×–1000× until someone actually ran the arithmetic; the habit of verifying is why they got caught.
- When correcting an earlier claim in a doc, mark the correction inline rather than silently editing. The reasoning record is more valuable than a clean-looking document.

## Current state

Step 0 done (proto schema, verified 799 B/batch → 11.9 MB/month/bus). Android project scaffolded but **not yet built** — the SDK install was pending. Step 1 is the detection engine + replay harness.

## Open questions

See [`docs/architecture/SPEC.md`](docs/architecture/SPEC.md) §Open Questions. The most consequential are the Phase-2 hardware target, whether the Ministry will declare speed limits per road class, and who pays for driver data.
