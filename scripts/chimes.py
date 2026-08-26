#!/usr/bin/env python3
"""
The bundled alert chimes, synthesised rather than sourced.

A car does not shout at you, and the ones that get it right do not even *beep*: a door left
open plays a soft, low, struck tone — something nearer a xylophone bar than a buzzer — and lets
it ring out, and then plays it again a moment later. That is what these are. Two things make
that sound what it is, and both were wrong here before:

- **The register.** The band the ear is most sensitive to (2-4 kHz) is exactly the band that
  makes a tone feel shrill; sitting a chime in it is how you get "loud" for free and "piercing"
  along with it. These live between 330 and 800 Hz, where a tone can be perfectly audible and
  still be pleasant, and the ear's own sensitivity is left to do less of the work than the
  alarm stream's volume does.
- **The envelope.** A flat-topped tone is a beep; a tone that is struck and then decays is a
  chime. So every note here is an exponential decay with a soft rise into it, the way anything
  hit with a mallet behaves, and each carries an octave below it (a bell's hum note) for warmth
  and almost nothing above it.

Written here rather than downloaded: they are ours, licensed by nobody, and reproducible — run
this and the same files come out.

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

# What a struck bar sounds like: the note, its hum an octave below, a quiet octave above, and a
# trace of the twelfth. Nothing in the shrill band, which is the whole point of this rewrite.
BELL = [(0.5, 0.13), (2.0, 0.12), (3.0, 0.035)]


def strike(freq, ms, attack_ms=18, tail=0.34, partials=BELL, level=1.0):
    """
    One note, hit and left to ring: a raised-cosine rise so nothing clicks, then an exponential
    decay. [tail] is how much of the note's length the decay constant is — smaller is drier.
    """
    total = int(RATE * ms / 1000)
    attack = max(1, int(RATE * attack_ms / 1000))
    tau = max(1e-4, ms / 1000 * tail)
    weight = 1 + sum(l for _, l in partials)
    out = []
    for i in range(total):
        t = i / RATE
        value = math.sin(2 * math.pi * freq * t)
        for ratio, partial in partials:
            value += partial * math.sin(2 * math.pi * freq * ratio * t)
        value /= weight
        rise = 0.5 - 0.5 * math.cos(math.pi * i / attack) if i < attack else 1.0
        # Decay from the moment it is struck, not from the end of the rise: a bar does not wait.
        out.append(value * rise * math.exp(-t / tau) * level)
    return out


def silence(ms):
    return [0.0] * int(RATE * ms / 1000)


def rounded(samples, cutoff=2400):
    """
    One pole of low pass. The rise is soft already; this takes the last of the edge off the
    strike itself, which is where a synthesised tone gives itself away as synthesised.
    """
    alpha = 1 - math.exp(-2 * math.pi * cutoff / RATE)
    out = []
    last = 0.0
    for value in samples:
        last += alpha * (value - last)
        out.append(last)
    return out


def faded(samples, ms=70):
    """
    Bring the very end down to a true zero.

    An exponential decay never actually reaches silence: these notes were still at three to
    eight per cent of their peak on the last sample and then stopped dead, and a step from
    there to nothing is a click — the "artefact just before it stops". It is worst on the
    quietest chime, where there is no note left to hide it. Seventy milliseconds is thirty-odd
    cycles at these frequencies: long enough to be a fade and far too short to be heard as the
    note ending early. The trailing silence is for the encoder, which should not have to guess
    what happens after the last sample it was given.
    """
    span = min(len(samples), int(RATE * ms / 1000))
    out = list(samples)
    for i in range(span):
        position = i / (span - 1) if span > 1 else 1.0
        out[len(out) - span + i] *= 0.5 + 0.5 * math.cos(math.pi * position)
    return out + silence(20)


def write(path, samples):
    peak = max(abs(s) for s in samples) or 1.0
    with wave.open(path, "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(b"".join(struct.pack("<h", int(s / peak * PEAK * 32767)) for s in samples))


CHIMES = {
    # The door left open: one soft tone, struck four times, unhurried. The most insistent of
    # these and the one that sounds least like an alarm — which is the trick a car pulls.
    "chime_alert": (
        strike(740, 420) + silence(120)
        + strike(740, 420) + silence(120)
        + strike(740, 420) + silence(120)
        + strike(740, 520)
    ),
    # Ding-dong, falling a fourth: the seat-belt reminder. Says "look at me" and nothing more.
    "chime_two_tone": strike(784, 380) + silence(40) + strike(587, 700, tail=0.4),
    # Two lower still, for anybody who finds even that too bright, or a quiet room at night.
    "chime_low": strike(440, 420) + silence(60) + strike(330, 780, tail=0.4),
    # One warm note with a long tail: the quietest thing here, for a phone on a desk.
    "chime_soft": strike(587, 1100, attack_ms=26, tail=0.3),
}


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, samples in CHIMES.items():
        samples = faded(rounded(samples))
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
