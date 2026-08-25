#!/usr/bin/env python3
"""
The bundled alert chimes, synthesised rather than sourced.

A modern car does not shout at you. It plays two or three short tones in the band the ear is
most sensitive to, with an envelope soft enough that nothing clicks, and it stops. That is a
sound anybody notices at a volume nobody minds, and it is also a sound made of two sine waves —
so it is written here rather than downloaded, which means it is ours, licensed by nobody, and
reproducible: run this and the files come out the same.

Usage: python3 scripts/chimes.py app/src/main/res/raw
"""
import math
import os
import struct
import subprocess
import sys
import tempfile
import wave

RATE = 44_100
PEAK = 0.63  # about -4 dBFS: audible across a room without being the loudest thing in it.


def envelope(index, total, attack_ms, release_ms):
    """A soft attack and a long release. Square edges are what make a beep sound cheap."""
    attack = max(1, int(RATE * attack_ms / 1000))
    release = max(1, int(RATE * release_ms / 1000))
    if index < attack:
        # A raised cosine rather than a ramp: no corner for the speaker to click on.
        return 0.5 - 0.5 * math.cos(math.pi * index / attack)
    if index > total - release:
        return 0.5 - 0.5 * math.cos(math.pi * (total - index) / release)
    return 1.0


def beep(freq, partials, ms, attack_ms=8, release_ms=60):
    total = int(RATE * ms / 1000)
    out = []
    for i in range(total):
        t = i / RATE
        value = math.sin(2 * math.pi * freq * t)
        for ratio, level in partials:
            value += level * math.sin(2 * math.pi * freq * ratio * t)
        out.append(value / (1 + sum(level for _, level in partials)) * envelope(i, total, attack_ms, release_ms))
    return out


def silence(ms):
    return [0.0] * int(RATE * ms / 1000)


def write(path, samples):
    peak = max(abs(s) for s in samples) or 1.0
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(b"".join(struct.pack("<h", int(s / peak * PEAK * 32767)) for s in samples))


# The fifth above is what makes a chime sound like an instrument instead of a test tone.
FIFTH = [(1.5, 0.35)]
OCTAVE = [(2.0, 0.22)]

CHIMES = {
    # Three quick ones, the blind-spot warning: the most urgent of these and still not shrill.
    "chime_alert": beep(1568, OCTAVE, 90) + silence(70) + beep(1568, OCTAVE, 90) + silence(70) + beep(1568, OCTAVE, 90),
    # Two, a fifth apart, falling: the seat-belt reminder. Says "look at me" and nothing more.
    "chime_two_tone": beep(1568, FIFTH, 150, release_ms=90) + silence(90) + beep(1046, FIFTH, 220, release_ms=150),
    # Three low ones for anybody who finds the high band piercing. Same shape, gentler register.
    "chime_low": beep(660, FIFTH, 110, release_ms=90) + silence(80) + beep(660, FIFTH, 110, release_ms=90) + silence(80) + beep(660, FIFTH, 140, release_ms=140),
    # One warm ding with a long tail: the quietest thing here, for a phone on a desk.
    "chime_soft": beep(880, FIFTH + OCTAVE, 420, attack_ms=14, release_ms=320),
}


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, samples in CHIMES.items():
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            write(tmp.name, samples)
            target = os.path.join(out_dir, f"{name}.ogg")
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-c:a", "libvorbis", "-q:a", "2", "-ac", "1", target],
                check=True,
            )
            os.unlink(tmp.name)
            print(f"{name}.ogg  {len(samples) / RATE:.2f}s  {os.path.getsize(target)} bytes")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw")
