# Section: Conformance Fixtures

**What lives here:** golden fixtures that keep two independent client implementations behaviourally identical.

**Owner:** unassigned · **Status:** not started (Step 0 remainder)

---

## Why this exists

There is deliberately **no shared native library** between the Kotlin client and the future Rust embedded client. Writing the code twice is cheap; what is expensive is not knowing what the rules *are* and letting two implementations quietly diverge.

These fixtures are what makes "two implementations" safe. They are **data, not code** — any language can run them.

## Three classes, three different contracts

| Class | Contract | Covers |
|---|---|---|
| **A. Encoding** | **Byte-exact** | Delta encoding, anchor selection, framing, `seq` semantics |
| **B. Detection** | **Toleranced** — `offset_ms` ±20 ms, `severity` ±2%, event kind and count exact | Filter output, thresholds |
| **C. Scenarios** | **State assertions** | Process death, resume, retry, drain |

⚠️ **Class B cannot be byte-exact.** Detection runs Madgwick/Kalman math — `atan2`, `sqrt`, trig. Kotlin/JVM and `no_std` Rust will not produce bit-identical floats (different libm, JVM double promotion, FMA contraction). A byte-exact suite here would fail spuriously on day one of the Rust port, then get quietly weakened under deadline pressure — exactly the hopeful guarantee this is meant to avoid.

**Class C is the one golden vectors cannot reach.** Fixtures are *scripts*: sample streams interleaved with `KILL` / `RELAUNCH` / `NET_UP` / `NACK` directives, asserting on persisted state. Retry timing and resume heuristics are precisely what will drift between implementations.

## Reference implementation

**Kotlin is pinned as the reference** and generates the fixtures. In practice it becomes the de facto spec while prose rots — that is acceptable *if named*. Regenerate fixtures on every spec change.

## Contributing

Good first tasks:
- Fixture format (JSON in, binary expected-out)
- Class A generator from the Kotlin encoder
- Class C scripting format and runner

## Related

[`proto/`](../proto/README.md) · [`spec/`](../spec/README.md) · [`detect/`](../android/app/src/main/kotlin/sy/safesy/detect/README.md)
