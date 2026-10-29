#!/usr/bin/env python3
"""Create original short PCM WAV notification sounds using Python stdlib."""
from pathlib import Path
import wave, math, struct
out = Path("MyPrayer/Resources")
out.mkdir(parents=True, exist_ok=True)
for name, frequency in [("soft", 520), ("clear", 760)]:
    rate, duration = 44100, 1.7
    with wave.open(str(out / f"{name}.wav"), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(rate)
        samples = []
        for n in range(int(rate * duration)):
            t = n / rate
            envelope = min(1.0, t / .06) * max(0.0, 1 - t / duration) ** 2
            val = .27 * envelope * (math.sin(2*math.pi*frequency*t) + .12*math.sin(4*math.pi*frequency*t))
            samples.append(struct.pack("<h", int(max(-1,min(1,val))*32767)))
        wav.writeframes(b"".join(samples))
