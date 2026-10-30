"""
모션 미리보기: make_motions.py 의 모션을 마인크래프트 플레이어 상자 모델로 그려 tools/preview/<이름>.png 로 저장.
playerAnimator 와 같은 규칙으로 계산한다 (키프레임 보간·이징, 부위 회전 순서 ZYX, 몸 전체 회전은 발 위 0.7칸 축).
굽힘(bend)은 그리지 않는다. 각 칸 = 한 틱, 위줄은 비스듬히 앞에서, 아래줄은 옆에서 본 모습.
    python tools/preview_motions.py [이름 ...]
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(__file__))
import make_motions as MM

HERE = os.path.dirname(__file__)
OUTDIR = os.path.join(HERE, "preview")

def ease(name, f):
    n = name.upper().replace("EASE", "")
    if n == "LINEAR": return f
    if n == "CONSTANT": return 0.0
    if n == "OUTQUAD": return 1 - (1 - f) ** 2
    if n == "INQUAD": return f * f
    if n == "INOUTSINE": return -(math.cos(math.pi * f) - 1) / 2
    if n == "OUTSINE": return math.sin(f * math.pi / 2)
    if n == "OUTEXPO": return 1 if f >= 1 else 1 - 2 ** (-10 * f)
    return f

PARTS = ["head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg", "torso"]

def tracks(anim):
    """part -> axis -> sorted list of (tick, value, ease)"""
    tr = {}
    for m in anim["emote"]["moves"]:
        for p in PARTS:
            if p in m:
                for ax, v in m[p].items():
                    tr.setdefault(p, {}).setdefault(ax, []).append((m["tick"], v, m["easing"]))
    for p in tr:
        for ax in tr[p]:
            tr[p][ax].sort(key=lambda x: x[0])
    return tr

def value(keys, t, end, stop):
    # playerAnimator: 처음 키 전엔 기본값(0)에서, 마지막 키 후 endTick~stopTick 동안 0으로
    if not keys: return 0.0
    before = (0, 0.0, "LINEAR")
    after = None
    for k in keys:
        if k[0] <= t: before = k
        else: after = k; break
    if after is None:
        if t <= end: return before[1]
        after = (stop, 0.0, "LINEAR")
    if after[0] == before[0]: return before[1]
    f = (t - before[0]) / (after[0] - before[0])
    return before[1] + (after[1] - before[1]) * ease(after[2], f)   # easeBeforeKeyframe = 뒤 키의 이징

def pose(anim, t):
    tr = tracks(anim)
    e = anim["emote"]
    out = {}
    for p in PARTS:
        out[p] = {ax: value(tr.get(p, {}).get(ax, []), t, e["endTick"], e["stopTick"]) for ax in ("pitch", "yaw", "roll")}
    return out

def Rx(a): c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
def Ry(a): c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
def Rz(a): c, s = math.cos(a), math.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])

# 모델 공간 (픽셀, y 아래로): (피벗, 상자 시작, 크기, 색)
MODEL = {
    "head": ((0, 0, 0), (-4, -8, -4), (8, 8, 8), (230, 190, 150)),
    "body": ((0, 0, 0), (-4, 0, -2), (8, 12, 4), (60, 110, 200)),
    "rightArm": ((-5, 2, 0), (-3, -2, -2), (4, 12, 4), (220, 80, 70)),
    "leftArm": ((5, 2, 0), (-1, -2, -2), (4, 12, 4), (90, 190, 90)),
    "rightLeg": ((-1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (70, 70, 140)),
    "leftLeg": ((1.9, 12, 0), (-2, 0, -2), (4, 12, 4), (110, 110, 180)),
}
FACES = [(0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (1, 2, 6, 5), (0, 3, 7, 4)]

def boxes(ps):
    """세계 좌표(블록, y 위, 앞 = -z, 오른쪽 = +x)의 면 목록"""
    polys = []
    b = ps["body"]
    W = Rz(math.radians(b["roll"])) @ Ry(math.radians(b["yaw"])) @ Rx(math.radians(b["pitch"]))
    for name, (piv, o, sz, col) in MODEL.items():
        part = "torso" if name == "body" else name
        r = ps[part]
        R = Rz(r["roll"] * math.pi / 180) @ Ry(r["yaw"] * math.pi / 180) @ Rx(r["pitch"] * math.pi / 180)
        if name == "head": col = col
        corners = []
        for i in range(8):
            v = np.array([o[0] + sz[0] * (i in (1, 2, 5, 6)), o[1] + sz[1] * (i in (2, 3, 6, 7)), o[2] + sz[2] * (i >= 4)], float)
            v = R @ v + np.array(piv, float)
            w = np.array([-v[0], -v[1], v[2]]) / 16 + np.array([0, 1.501, 0])   # scale(-1,-1,1), translate
            w = W @ (w - np.array([0, 0.7, 0])) + np.array([0, 0.7, 0])        # 몸 전체 회전
            corners.append(w)
        for fi, f in enumerate(FACES):
            c = col
            if name == "head" and fi == 0: c = (250, 220, 120)   # 얼굴(앞면) 표시
            polys.append(([corners[i] for i in f], c))
    return polys

def render(ps, view, size=200):
    img = Image.new("RGB", (size, size), (245, 245, 240))
    d = ImageDraw.Draw(img)
    if view == "top":   # 위에서: 화면 위쪽 = 앞, 오른쪽 = 내 오른쪽
        V = Rx(math.radians(90))
    else:
        V = Rx(math.radians(-12)) @ Ry(math.radians({"front": 145, "side": 90}[view]))
    proj = []
    for pts, col in boxes(ps):
        q = [V @ (p - np.array([0, 1.0, 0])) for p in pts]
        depth = sum(p[2] for p in q) / 4
        n = np.cross(q[1] - q[0], q[2] - q[0])
        shade = 0.6 + 0.4 * abs(n[2]) / (np.linalg.norm(n) + 1e-9)
        proj.append((depth, [(size / 2 + p[0] * 62, size * 0.55 - p[1] * 62) for p in q], tuple(int(c * shade) for c in col)))
    for depth, pts, col in sorted(proj, key=lambda x: x[0]):   # 먼 면부터 (카메라는 -z 쪽에서 봄)
        d.polygon(pts, fill=col, outline=(30, 30, 30))
    d.line([(0, size * 0.55 + 1.0 * 62), (size, size * 0.55 + 1.0 * 62)], fill=(180, 180, 180))
    return img

def sheet(name):
    a = MM.A[name]
    n = a["emote"]["stopTick"] + 1
    cell = 200
    img = Image.new("RGB", (cell * n, cell * 3 + 16), (255, 255, 255))
    d = ImageDraw.Draw(img)
    for t in range(n):
        ps = pose(a, t)
        img.paste(render(ps, "front"), (t * cell, 16))
        img.paste(render(ps, "side"), (t * cell, 16 + cell))
        img.paste(render(ps, "top"), (t * cell, 16 + cell * 2))
        d.text((t * cell + 4, 2), f"{name} t{t}", fill=(0, 0, 0))
    os.makedirs(OUTDIR, exist_ok=True)
    img.save(os.path.join(OUTDIR, name + ".png"))

if __name__ == "__main__":
    names = sys.argv[1:] or list(MM.A)
    for nm in names: sheet(nm)
    print("preview ->", OUTDIR, names)
