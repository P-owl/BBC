# 빛 루트 효과음 (직접 합성한 소리 — 모드 jar 에 그대로 넣음). python3 tools/make_light_se.py
# 참고 영상의 느낌만: 칼날이 돋는 맑은 쇳소리(키잉), 빛이 그어지는 쉬익+반짝, 베이는 짧은 쇳소리, 크게 번쩍이는 빛 소리, 각성기 큰 베기
import numpy as np, os, subprocess, tempfile, wave
SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'city-ability', 'src', 'main', 'resources', 'assets', 'bbc', 'sounds', 'pillar_light_se')
rng = np.random.default_rng(7)
def t(sec): return np.arange(int(SR * sec)) / SR
def env(x, a, d): n = len(x); e = np.minimum(1, np.arange(n) / max(1, a * SR)) * np.exp(-np.arange(n) / (d * SR)); return x * e
def ring(sec, freqs, decay, amp=1.0):
    tt = t(sec); s = sum(np.sin(2 * np.pi * f * tt + rng.uniform(0, 6)) * (0.8 ** i) for i, f in enumerate(freqs))
    return env(s, 0.002, decay) * amp
def noise(sec): return rng.standard_normal(int(SR * sec))
def bandpass(x, lo, hi):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR); X[(f < lo) | (f > hi)] = 0; return np.fft.irfft(X, len(x))
def sweep_noise(sec, f0, f1, width):
    x = noise(sec); out = np.zeros_like(x); seg = 1024
    for i in range(0, len(x), seg):
        u = i / len(x); fc = f0 + (f1 - f0) * u
        blk = x[max(0, i - seg):i + seg * 2]
        out[i:i + seg] = bandpass(blk, fc - width, fc + width)[seg if i else 0:][:len(out[i:i + seg])]
    return out
def pad(x, sec): y = np.zeros(int(SR * sec)); y[:len(x)] += x[:len(y)]; return y
def at(x, sec, total): y = np.zeros(int(SR * total)); i = int(SR * sec); y[i:i + len(x)] += x[:len(y) - i]; return y
def save(name, x):
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.9
    os.makedirs(OUT, exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f: tmp = f.name
    with wave.open(tmp, 'wb') as w: w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype(np.int16).tobytes())
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', tmp, '-c:a', 'libvorbis', '-q:a', '5', os.path.join(OUT, name + '.ogg')], check=True)
    os.remove(tmp)
METAL = [3150, 4720, 6930, 9120, 11800]
# 칼날이 돋음: 짧은 긁힘 + 길게 울리는 맑은 쇳소리 (키잉—)
shing = at(env(bandpass(noise(0.12), 3000, 12000), 0.005, 0.03) * 0.6, 0, 1.4) + at(ring(1.3, METAL, 0.45), 0.02, 1.4) + at(ring(1.2, [f * 1.5 for f in METAL[:3]], 0.25, 0.4), 0.05, 1.4)
save('shing', shing)
# 빛이 그어짐: 높아지는 쉬익 + 끝에 반짝
sw = env(sweep_noise(0.32, 900, 6500, 700), 0.04, 0.12) * 1.2
swish = at(sw, 0, 0.6) + at(ring(0.5, [5200, 7800, 10400], 0.12, 0.35), 0.16, 0.6)
save('swish', swish)
# 베임: 아주 짧은 찢는 소리 + 쇳소리
cut = at(env(bandpass(noise(0.08), 1500, 9000), 0.001, 0.018) * 1.4, 0, 0.5) + at(ring(0.45, [2600, 4100, 6100, 8300], 0.09, 0.7), 0.005, 0.5)
save('cut', cut)
# 크게 번쩍임: 부풀어 오르는 빛 + 화음 종소리
tt = t(1.6); rise = bandpass(noise(1.6), 2000, 10000) * np.minimum(1, tt / 0.35) * np.exp(-np.maximum(0, tt - 0.35) / 0.35)
chord = sum(ring(1.5, [f, f * 2.01, f * 3.03], 0.6, 0.5) for f in (1318, 1661, 1976, 2637))
save('flash', rise * 0.6 + at(chord, 0.3, 1.6))
# 각성기 큰 베기: 낮은 바람 + 긴 쉬익 + 두꺼운 쇳소리
big = at(env(sweep_noise(0.5, 400, 5000, 900), 0.06, 0.2) * 1.3, 0, 1.2) + at(ring(1.0, [1900, 2850, 4300, 6400], 0.3, 0.8), 0.22, 1.2) + at(env(bandpass(noise(0.1), 80, 400), 0.002, 0.05) * 0.9, 0.22, 1.2)
save('big', big)
# 칼날이 빛남 (유법 켤 때): 맑게 떨리는 빛 소리
tt = t(1.2); hum = sum(np.sin(2 * np.pi * f * tt) * (1 + 0.5 * np.sin(2 * np.pi * 9 * tt)) for f in (880, 1320, 1760, 2640)) * np.minimum(1, tt / 0.1) * np.exp(-tt / 0.5)
save('shine', hum + at(ring(1.0, METAL, 0.35, 0.6), 0.0, 1.2))
print(sorted(os.listdir(OUT)))
