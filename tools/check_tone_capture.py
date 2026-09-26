#!/usr/bin/env python3
"""Check a receiver's raw PCM capture for a clean test tone.

Play a pure sine (e.g. 1 kHz) on the phone while the receiver writes its decoded
output as raw s16le/44100/stereo (shairport-sync: `-o stdout > capture.raw`), then:

    python3 tools/check_tone_capture.py capture.raw --freq 1000

A correctly byte-ordered stream shows ~2*freq zero crossings per second. Byte-swapped
PCM turns a sine into broadband noise with thousands of crossings per second, and a
silent capture (emulator) has almost none. Stdlib only.
"""
import argparse
import array
import sys


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    ap.add_argument("--freq", type=float, default=1000.0)
    ap.add_argument("--rate", type=int, default=44100)
    ap.add_argument("--skip", type=float, default=2.0, help="seconds to skip at start")
    args = ap.parse_args()

    samples = array.array("h")
    with open(args.capture, "rb") as f:
        data = f.read()
    samples.frombytes(data[: len(data) - len(data) % 4])
    if sys.byteorder == "big":
        samples.byteswap()

    left = samples[0::2][int(args.skip * args.rate):]
    if len(left) < args.rate:
        print("FAIL: less than 1 s of audio after skip")
        return 1

    peak = max(abs(s) for s in left)
    if peak < 300:
        print(f"FAIL: capture is (near) silent, peak={peak}")
        return 1

    # Ignore tiny values around zero so dither/noise floor doesn't count as crossings.
    threshold = peak * 0.05
    crossings, sign = 0, 0
    for s in left:
        if s > threshold:
            if sign < 0:
                crossings += 1
            sign = 1
        elif s < -threshold:
            if sign > 0:
                crossings += 1
            sign = -1

    seconds = len(left) / args.rate
    measured = crossings / seconds / 2
    ok = abs(measured - args.freq) / args.freq < 0.05
    print(f"peak={peak} estimated_freq={measured:.1f} Hz expected={args.freq:.0f} Hz")
    print("PASS: clean tone" if ok else "FAIL: not the expected tone (byte order / corruption?)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
