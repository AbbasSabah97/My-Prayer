#!/usr/bin/env python3
"""Generate short original notification WAV files for the Android resource bundle."""
from pathlib import Path
import math
import struct
import wave

OUTPUT = Path("android/app/src/main/res/raw")
OUTPUT.mkdir(parents=True, exist_ok=True)

for name, frequency in (("soft", 520), ("clear", 760)):
    sample_rate = 44100
    duration = 1.7
    frames = bytearray()
    for index in range(int(sample_rate * duration)):
        t = index / sample_rate
        envelope = min(1.0, t / 0.06) * max(0.0, 1.0 - t / duration) ** 2
        value = 0.27 * envelope * (
            math.sin(2 * math.pi * frequency * t)
            + 0.12 * math.sin(4 * math.pi * frequency * t)
        )
        frames.extend(struct.pack("<h", int(max(-1.0, min(1.0, value)) * 32767)))
    with wave.open(str(OUTPUT / f"{name}.wav"), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(frames)
