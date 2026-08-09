#!/usr/bin/env python3
"""
Replay a recorded drive through the detector's logic at different thresholds.

This is the point of capturing full 50 Hz raw IMU during testing: one real
drive can answer "what would have happened at threshold X?" for any X, instead
of needing a fresh drive per experiment. Drives on Syrian roads are expensive;
replays are free.

Mirrors DrivingDetector's arithmetic. It is NOT the implementation — the Kotlin
engine is authoritative (conformance class B). This is for exploring thresholds
before changing them.

Usage:
  tools/replay.py session-dir/                    # summarise
  tools/replay.py session-dir/ --brake 2.0        # try a threshold
  tools/replay.py session-dir/ --sweep            # find where events appear
"""
import argparse, csv, math, os, sys


def load_raw(path):
    rows = []
    with open(path) as f:
        for r in csv.DictReader(f):
            rows.append((int(r["t_ms"]),
                         float(r["ax"]), float(r["ay"]), float(r["az"]),
                         float(r["gx"]), float(r["gy"]), float(r["gz"])))
    return rows


def load_marks(path):
    """Human ground truth: what the tester said actually happened."""
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return [(int(r["elapsed_s"]), r["label"]) for r in csv.DictReader(f) if r.get("elapsed_s")]


def gravity_track(rows, alpha=0.98):
    """Complementary filter, matching OrientationEstimator."""
    gx = gy = gz = None
    out = []
    for (t, ax, ay, az, wx, wy, wz) in rows:
        mag = math.sqrt(ax*ax + ay*ay + az*az)
        if mag < 1e-3:
            out.append((t, 0.0, 0.0, 0.0))
            continue
        ux, uy, uz = ax/mag, ay/mag, az/mag
        if gx is None:
            gx, gy, gz = ux, uy, uz
        else:
            # Weight the accelerometer down when |a| is far from 1g — under
            # hard acceleration it is a poor gravity reference.
            trust = max(0.0, min(1.0, 1.0 - abs(mag/9.81 - 1.0) * 2))
            a = alpha + (1 - alpha) * (1 - trust)
            gx = a*gx + (1-a)*ux
            gy = a*gy + (1-a)*uy
            gz = a*gz + (1-a)*uz
            n = math.sqrt(gx*gx + gy*gy + gz*gz) or 1.0
            gx, gy, gz = gx/n, gy/n, gz/n
        lx, ly, lz = ax - gx*9.81, ay - gy*9.81, az - gz*9.81
        vert = lx*gx + ly*gy + lz*gz
        hx, hy, hz = lx - vert*gx, ly - vert*gy, lz - vert*gz
        out.append((t, math.sqrt(lx*lx+ly*ly+lz*lz), vert,
                    math.sqrt(hx*hx+hy*hy+hz*hz)))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("session")
    ap.add_argument("--brake", type=float, default=2.5, help="harsh brake m/s^2")
    ap.add_argument("--crash-g", type=float, default=3.0)
    ap.add_argument("--sweep", action="store_true")
    a = ap.parse_args()

    raw_path = os.path.join(a.session, "raw-imu.csv")
    if not os.path.exists(raw_path):
        sys.exit(f"no raw-imu.csv in {a.session} — was it recorded with a debug build?")

    rows = load_raw(raw_path)
    if not rows:
        sys.exit("raw-imu.csv is empty")

    dur = (rows[-1][0] - rows[0][0]) / 1000.0
    hz = len(rows) / dur if dur else 0
    print(f"{len(rows):,} samples over {dur:.0f}s ({hz:.1f} Hz)")

    marks = load_marks(os.path.join(a.session, "marks.csv"))
    if marks:
        print(f"ground truth: {len(marks)} marks")
        for t, label in marks:
            print(f"   {t:>5}s  {label}")

    lin = gravity_track(rows)
    t0 = rows[0][0]
    peak_g = max(v[1] for v in lin) / 9.81
    print(f"\npeak |a| {max(v[1] for v in lin):.2f} m/s^2 ({peak_g:.2f} g)")
    print(f"crash threshold {a.crash_g}g -> "
          f"{'WOULD FIRE' if peak_g >= a.crash_g else 'no crash'}")

    if a.sweep:
        print("\nthreshold sweep — horizontal accel excursions:")
        for thr in (1.5, 2.0, 2.5, 3.0, 3.5, 4.0):
            n = sum(1 for v in lin if v[3] >= thr)
            print(f"  >= {thr:>4.1f} m/s^2 : {n:>6} samples ({n/hz:.1f}s)")
        print("\n  Pick a threshold where real marked events appear but")
        print("  ordinary driving does not.")
    else:
        # Where does the signal exceed the threshold, and does it line up
        # with what the human marked?
        hits, run = [], None
        for (t, tot, vert, hor) in lin:
            if hor >= a.brake:
                if run is None:
                    run = t
            elif run is not None:
                if t - run >= 250:      # matches minEventDurationMs
                    hits.append(((run - t0)//1000, (t - run)))
                run = None
        print(f"\nsustained excursions >= {a.brake} m/s^2 (>=250ms): {len(hits)}")
        for sec, ms in hits[:20]:
            near = [l for (mt, l) in marks if abs(mt - sec) <= 3]
            tag = f"  <- marked: {', '.join(near)}" if near else ""
            print(f"   {sec:>5}s  {ms:>5}ms{tag}")


if __name__ == "__main__":
    main()
