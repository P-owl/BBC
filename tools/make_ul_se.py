# 완전생물 효과음 (직접 합성 — 모드 jar 에 넣음). python3 tools/make_ul_se.py
# 참고 영상의 느낌만: 파랗게 튀는 파문 불꽃(지직), 살이 꿈틀대며 변하는 소리, 생물 떼가 기어가는 소리, 큰 날갯짓, 변신 때 껍질이 깨지며 터지는 빛
import numpy as np, os, subprocess, tempfile, wave
SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'city-ability', 'src', 'main', 'resources', 'assets', 'bbc', 'sounds', 'ultimate_se')
rng = np.random.default_rng(11)
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

# 파문 불꽃: 짧은 딱딱 튀는 전기 + 높은 윙 소리 (지지직)
def crackle(sec, rate, lo=2500, hi=14000):
    x = np.zeros(int(SR * sec))
    for _ in range(int(rate * sec)):
        i = rng.integers(0, len(x) - 400)
        x[i:i + 300] += env(bandpass(noise(300 / SR), lo, hi), 0.0002, 0.0015) * rng.uniform(0.4, 1.0)
    return x
tt = t(0.7)
buzz = np.sign(np.sin(2 * np.pi * 120 * tt)) * 0.15 * np.exp(-tt / 0.25)
save('ripple', crackle(0.7, 90) * np.exp(-t(0.7) / 0.3) + bandpass(buzz, 200, 4000) + at(ring(0.5, [3800, 5700, 7600], 0.12, 0.3), 0, 0.7))
# 파문 크게 (장타): 터지는 전기 + 낮은 쿵
save('ripple_big', at(crackle(1.0, 160) * np.exp(-t(1.0) / 0.4), 0, 1.1) + at(env(bandpass(noise(0.2), 60, 300), 0.002, 0.08) * 1.5, 0, 1.1)
     + at(ring(0.9, [2200, 3300, 4950], 0.3, 0.5), 0.02, 1.1))
# 살이 꿈틀대며 변함: 낮게 일렁이는 질척임 (변형 팔 · 포식)
tt = t(0.6)
wob = bandpass(noise(0.6), 80, 900) * (0.6 + 0.4 * np.sin(2 * np.pi * 14 * tt)) * np.minimum(1, tt / 0.05) * np.exp(-tt / 0.3)
save('flesh', wob * 1.4 + at(env(bandpass(noise(0.1), 300, 2500), 0.003, 0.03) * 0.6, 0.05, 0.6) + at(env(bandpass(noise(0.1), 300, 2500), 0.003, 0.03) * 0.5, 0.22, 0.6))
# 생물 떼: 짧은 긁는 발소리가 빠르게 많이 (사각사각) + 끽 우는 소리
sk = np.zeros(int(SR * 1.2))
for _ in range(70):
    i = rng.integers(0, len(sk) - 2000)
    sk[i:i + 1500] += env(bandpass(noise(1500 / SR), 1800, 7000), 0.0005, 0.006) * rng.uniform(0.3, 1.0)
tt = t(0.25)
squeak = np.sin(2 * np.pi * (2400 + 900 * np.sin(2 * np.pi * 9 * tt)) * tt) * env(np.ones_like(tt), 0.01, 0.08)
save('swarm', sk * np.minimum(1, t(1.2) / 0.1) * np.exp(-t(1.2) / 0.6) + at(squeak * 0.3, 0.1, 1.2) + at(squeak * 0.25, 0.45, 1.2))
# 큰 날갯짓: 낮은 바람 덩어리 둘 (퍼억 퍼억)
def flap(sec=0.35):
    x = bandpass(noise(sec), 70, 1200); tt = t(sec)
    return x * np.minimum(1, tt / 0.03) * np.exp(-tt / 0.09)
save('wing', at(flap() * 1.4, 0, 0.9) + at(flap() * 1.1, 0.38, 0.9))
# 변신: 돌 껍질이 깨지는 소리 + 부풀어 오르는 빛 + 낮은 울림 (바-앙)
crack = np.zeros(int(SR * 2.4))
for _ in range(40):
    i = rng.integers(0, int(SR * 0.5))
    crack[i:i + 2500] += env(bandpass(noise(2500 / SR), 400, 6000), 0.0005, 0.01) * rng.uniform(0.3, 1.0)
tt = t(2.4)
swell = bandpass(noise(2.4), 1500, 9000) * np.minimum(1, tt / 0.3) * np.exp(-np.maximum(0, tt - 0.3) / 0.7) * 0.5
boom = env(np.sin(2 * np.pi * 52 * tt) + 0.5 * np.sin(2 * np.pi * 78 * tt), 0.01, 0.9) * 0.9
chord = sum(ring(2.2, [f, f * 2.0, f * 3.0], 0.9, 0.35) for f in (523, 659, 784, 1046))
save('burst', crack * 1.2 + swell + boom + at(chord, 0.15, 2.4))
print(sorted(os.listdir(OUT)))
