"""성장 창·재능 카드 창 텍스처 (PIL + numpy, 2배로 그린 뒤 줄임).
GUI 1칸 = 3픽셀 (GUI 크기 3 에서 1:1). 자바 쪽은 Ui.tex(...) 로 GUI 칸 크기에 맞춰 그림.
python3 tools/make_ui_textures.py  (city-ability 폴더에서)"""
import math, os, random
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = 'src/main/resources/assets/bbc/textures/gui'
S = 3          # 픽셀 / GUI 칸
SS = 2         # 슈퍼샘플
K = S * SS
random.seed(7)
np.random.seed(7)

# 카드 배치 (GUI 칸) — TalentScreen.java 와 같아야 함
CW, CH, M = 104, 162, 6
TITLE = (7, 21)
ART = (11, 25, 93, 85)
TYPE = (14, 89, 90, 98)
DESC = (8, 101, 96, 155)

RAR = {
    0: ((214, 170, 104), (104, 70, 36)),     # 청동
    1: ((228, 238, 246), (88, 110, 132)),    # 은
    2: ((255, 232, 140), (148, 96, 22)),     # 금
}
GEM = {0: (222, 140, 52), 1: (70, 170, 235), 2: (255, 236, 150)}


def u(v):
    return int(round(v * K))


def new(wu, hu, mode='RGBA'):
    return Image.new(mode, (u(wu), u(hu)), 0 if mode == 'L' else (0, 0, 0, 0))


def done(img):
    return img.resize((img.width // SS, img.height // SS), Image.LANCZOS)


def blur(m, r):
    return m.filter(ImageFilter.GaussianBlur(r))


def arr(m):
    return np.asarray(m, dtype=np.float32) / 255.0


def to_img(rgb, alpha):
    a = np.clip(alpha, 0, 1)
    out = np.dstack([np.clip(rgb, 0, 255), a * 255]).astype(np.uint8)
    return Image.fromarray(out, 'RGBA')


def vgrad(h, w, c0, c1):
    t = np.linspace(0, 1, h)[:, None, None]
    return np.array(c0, np.float32) * (1 - t) + np.array(c1, np.float32) * t + np.zeros((h, w, 3), np.float32)


def rgrad(h, w, c0, c1, cx=0.5, cy=0.5, p=1.0):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    d = np.sqrt(((xx / w - cx) * (w / max(w, h))) ** 2 + ((yy / h - cy) * (h / max(w, h))) ** 2) / 0.72
    t = np.clip(d, 0, 1)[..., None] ** p
    return np.array(c0, np.float32) * (1 - t) + np.array(c1, np.float32) * t


def bevel(mask, r, k=4.0, invert=False):
    b = arr(blur(mask, r))
    d = max(1, int(r * 0.8))
    sh = np.roll(np.roll(b, d, 0), d, 1)
    l = np.clip((b - sh) * k, -1, 1)
    return -l if invert else l


def shade(rgb, light, amt=0.55):
    pos = np.clip(light, 0, 1)[..., None]
    neg = np.clip(-light, 0, 1)[..., None]
    return rgb + (255 - rgb) * pos * amt - rgb * neg * amt


def noise(rgb, amt):
    return rgb + np.random.normal(0, amt, rgb.shape[:2])[..., None]


def metal(mask, light, dark, r=None, invert=False, k=4.0):
    m = arr(mask)
    h, w = m.shape
    base = vgrad(h, w, light, dark)
    # 금속 결: 가로 줄무늬 약간
    base = base * (1 + 0.05 * np.sin(np.linspace(0, 40, h))[:, None, None])
    rgb = shade(base, bevel(mask, r or u(1.0), k, invert))
    return to_img(noise(rgb, 3), m)


def flat(mask, color, alpha=1.0):
    m = arr(mask)
    h, w = m.shape
    return to_img(np.zeros((h, w, 3)) + np.array(color, np.float32), m * alpha)


def comp(base, *layers):
    for l in layers:
        base = Image.alpha_composite(base, l)
    return base


def rrect(img_l, box, r, fill=255):
    ImageDraw.Draw(img_l).rounded_rectangle([u(box[0]), u(box[1]), u(box[2]) - 1, u(box[3]) - 1], radius=u(r), fill=fill)
    return img_l


def outline(mask, px):
    return mask.filter(ImageFilter.MaxFilter(px * 2 + 1 if px % 2 == 0 else px * 2 + 1))


def dil(mask, px):
    m = mask
    while px > 0:
        step = min(px, 6)
        m = m.filter(ImageFilter.MaxFilter(step * 2 + 1))
        px -= step
    return m


def ero(mask, px):
    m = mask
    while px > 0:
        step = min(px, 6)
        m = m.filter(ImageFilter.MinFilter(step * 2 + 1))
        px -= step
    return m


def diamond(d, cx, cy, r, fill=255):
    d.polygon([(u(cx), u(cy - r)), (u(cx + r), u(cy)), (u(cx), u(cy + r)), (u(cx - r), u(cy))], fill=fill)


def shadow(mask, r, dx, dy, a=0.7):
    m = blur(mask, r)
    sh = Image.new('L', m.size)
    sh.paste(m, (dx, dy))
    return flat(sh, (0, 0, 0), a)


def glow(mask, r, color, a=0.8):
    return flat(blur(dil(mask, int(r * 0.4)), r), color, a)


def parchment(mask, c0=(242, 232, 204), c1=(214, 196, 150)):
    m = arr(mask)
    h, w = m.shape
    rgb = vgrad(h, w, c0, c1)
    edge = 1 - arr(blur(ero(mask, u(0.6)), u(2.2)))
    rgb = rgb * (1 - 0.35 * edge[..., None])
    rgb = noise(rgb, 4)
    return to_img(rgb, m)


def leather(mask, c0=(44, 40, 36), c1=(26, 23, 20)):
    m = arr(mask)
    h, w = m.shape
    rgb = rgrad(h, w, c0, c1, 0.5, 0.45, 1.3)
    rgb = noise(rgb, 5)
    return to_img(rgb, m)


def save(img, name):
    os.makedirs(OUT + '/' + os.path.dirname(name), exist_ok=True) if '/' in name else os.makedirs(OUT, exist_ok=True)
    img.save(f'{OUT}/{name}.png', optimize=True)


# ═════════ 카드 ═════════

def card_frame(r):
    W, H = CW + 2 * M, CH + 2 * M
    o = lambda b: (b[0] + M, b[1] + M, b[2] + M, b[3] + M)
    light, dark = RAR[r]
    card = rrect(new(W, H, 'L'), o((0, 0, CW, CH)), 3)
    img = new(W, H)
    img = comp(img, shadow(card, u(2.5), u(0.8), u(1.6), 0.75))
    if r == 2:
        img = comp(img, glow(card, u(3.5), (255, 214, 110), 0.9))
    elif r == 1:
        img = comp(img, glow(card, u(2.5), (150, 210, 255), 0.45))
    img = comp(img, metal(card, light, dark, u(1.2)))
    # 새긴 선
    line = rrect(new(W, H, 'L'), o((2.6, 2.6, CW - 2.6, CH - 2.6)), 2.4)
    line = Image.fromarray((np.clip(arr(line) - arr(rrect(new(W, H, 'L'), o((3.2, 3.2, CW - 3.2, CH - 3.2)), 2.0)), 0, 1) * 255).astype(np.uint8))
    img = comp(img, flat(line, tuple(int(c * 0.45) for c in dark), 0.9))
    inner = rrect(new(W, H, 'L'), o((5, 5, CW - 5, CH - 5)), 1.5)
    img = comp(img, shadow(inner, u(1.0), 0, 0, 0.0), leather(inner))
    img = comp(img, flat(Image.fromarray((np.clip(arr(dil(inner, u(0.5))) - arr(inner), 0, 1) * 255).astype(np.uint8)), (10, 8, 6), 0.9))
    # 귀퉁이 마름모
    cm = new(W, H, 'L')
    d = ImageDraw.Draw(cm)
    for cx, cy in ((2.5, 2.5), (CW - 2.5, 2.5), (2.5, CH - 2.5), (CW - 2.5, CH - 2.5)):
        diamond(d, cx + M, cy + M, 3.2)
    if r >= 1:
        diamond(d, CW / 2 + M, 2.2 + M, 2.6)
    if r == 2:
        for dx in (-10, 10):
            diamond(d, CW / 2 + dx + M, 2.2 + M, 1.8)
        for cy in (CH * 0.33, CH * 0.66):
            diamond(d, 2.2 + M, cy + M, 1.8)
            diamond(d, CW - 2.2 + M, cy + M, 1.8)
    img = comp(img, flat(dil(cm, u(0.5)), (20, 14, 8), 1), metal(cm, tuple(min(255, c + 30) for c in light), dark, u(0.6)))
    # 제목 띠 (양끝이 파인 리본)
    t0, t1 = TITLE
    rib = new(W, H, 'L')
    ImageDraw.Draw(rib).polygon([(u(5 + M), u(t0 + M)), (u(CW - 5 + M), u(t0 + M)), (u(CW - 9 + M), u((t0 + t1) / 2 + M)),
                                 (u(CW - 5 + M), u(t1 + M)), (u(5 + M), u(t1 + M)), (u(9 + M), u((t0 + t1) / 2 + M))], fill=255)
    img = comp(img, shadow(rib, u(0.8), 0, u(0.8), 0.8), flat(dil(rib, u(0.5)), (24, 18, 10)), parchment(rib))
    # 그림 창 (오목한 금속 테)
    ax0, ay0, ax1, ay1 = ART
    win = rrect(new(W, H, 'L'), o((ax0 - 2.2, ay0 - 2.2, ax1 + 2.2, ay1 + 2.2)), 1.2)
    img = comp(img, shadow(win, u(1.0), 0, u(0.6), 0.8), metal(win, light, dark, u(0.8), invert=True))
    hole = rrect(new(W, H, 'L'), o((ax0, ay0, ax1, ay1)), 0.4)
    img = comp(img, flat(dil(hole, u(0.4)), (14, 10, 6)), flat(hole, (8, 8, 8)))
    # 분류 띠
    tp = rrect(new(W, H, 'L'), o(TYPE), 0.8)
    img = comp(img, metal(dil(tp, u(0.7)), light, dark, u(0.5)), flat(tp, (30, 26, 22)))
    dm = new(W, H, 'L')
    for x in (TYPE[0] - 1.2, TYPE[2] + 1.2):
        diamond(ImageDraw.Draw(dm), x + M, (TYPE[1] + TYPE[3]) / 2 + M, 2.4)
    img = comp(img, flat(dil(dm, u(0.4)), (20, 14, 8)), metal(dm, light, dark, u(0.5)))
    # 설명 종이
    ds = rrect(new(W, H, 'L'), o(DESC), 1.6)
    img = comp(img, shadow(ds, u(1.0), 0, u(0.8), 0.8), flat(dil(ds, u(0.5)), (24, 18, 10)), parchment(ds))
    il = rrect(new(W, H, 'L'), o((DESC[0] + 2, DESC[1] + 2, DESC[2] - 2, DESC[3] - 2)), 1.0)
    il = Image.fromarray((np.clip(arr(il) - arr(ero(il, u(0.35))), 0, 1) * 255).astype(np.uint8))
    img = comp(img, flat(il, (150, 120, 70), 0.55))
    # 아래 보석
    gm = new(W, H, 'L')
    ImageDraw.Draw(gm).ellipse([u(CW / 2 - 5 + M), u(CH - 6.5 + M), u(CW / 2 + 5 + M), u(CH + 3.5 + M)], fill=255)
    img = comp(img, flat(dil(gm, u(0.5)), (20, 14, 8)), metal(gm, light, dark, u(0.6)))
    gi = new(W, H, 'L')
    ImageDraw.Draw(gi).ellipse([u(CW / 2 - 3.3 + M), u(CH - 4.8 + M), u(CW / 2 + 3.3 + M), u(CH + 1.8 + M)], fill=255)
    gc = GEM[r]
    m = arr(gi)
    hh, ww = m.shape
    rgb = rgrad(hh, ww, tuple(min(255, c + 90) for c in gc), tuple(int(c * 0.45) for c in gc), (CW / 2 - 1 + M) / (CW + 2 * M), (CH - 3 + M) / (CH + 2 * M), 0.8)
    img = comp(img, to_img(rgb, m))
    hl = new(W, H, 'L')
    ImageDraw.Draw(hl).ellipse([u(CW / 2 - 2 + M), u(CH - 4 + M), u(CW / 2 - 0.3 + M), u(CH - 2.5 + M)], fill=255)
    img = comp(img, flat(blur(hl, u(0.3)), (255, 255, 255), 0.9))
    return done(img)


def card_back():
    W, H = CW + 2 * M, CH + 2 * M
    o = lambda b: (b[0] + M, b[1] + M, b[2] + M, b[3] + M)
    card = rrect(new(W, H, 'L'), o((0, 0, CW, CH)), 3)
    img = comp(new(W, H), shadow(card, u(2.5), u(0.8), u(1.6), 0.75), metal(card, (205, 168, 100), (96, 66, 34), u(1.2)))
    inner = rrect(new(W, H, 'L'), o((5, 5, CW - 5, CH - 5)), 1.5)
    m = arr(inner)
    h, w = m.shape
    rgb = rgrad(h, w, (40, 58, 74), (14, 20, 30), 0.5, 0.5, 1.2)
    img = comp(img, to_img(noise(rgb, 4), m))
    # 금색 격자
    lat = new(W, H, 'L')
    d = ImageDraw.Draw(lat)
    step = 13
    for k in range(-20, 30):
        x = k * step
        d.line([(u(x + M), u(M)), (u(x + CH + M), u(CH + M))], fill=255, width=u(0.45))
        d.line([(u(x + M), u(CH + M)), (u(x + CH + M), u(M))], fill=255, width=u(0.45))
    lat = Image.fromarray((arr(lat) * arr(ero(inner, u(3))) * 255).astype(np.uint8))
    img = comp(img, flat(lat, (214, 176, 98), 0.55))
    dots = new(W, H, 'L')
    d = ImageDraw.Draw(dots)
    for k in range(-20, 30):
        for j in range(-20, 30):
            cx, cy = (k + j) * step / 2, (k - j) * step / 2 + step / 2
            if 9 < cx < CW - 9 and 9 < cy < CH - 9:
                diamond(d, cx + M, cy + M, 1.4)
    img = comp(img, metal(dots, (240, 210, 140), (150, 110, 50), u(0.4)))
    # 가운데 메달
    cx, cy = CW / 2 + M, CH / 2 + M
    med = new(W, H, 'L')
    ImageDraw.Draw(med).ellipse([u(cx - 22), u(cy - 22), u(cx + 22), u(cy + 22)], fill=255)
    img = comp(img, flat(dil(med, u(1.5)), (14, 20, 30)), shadow(med, u(1.5), 0, u(1), 0.8), metal(med, (240, 205, 130), (120, 80, 30), u(1.2)))
    core = new(W, H, 'L')
    ImageDraw.Draw(core).ellipse([u(cx - 17), u(cy - 17), u(cx + 17), u(cy + 17)], fill=255)
    m = arr(core)
    img = comp(img, to_img(rgrad(h, w, (50, 70, 90), (16, 24, 34), 0.5, 0.5, 1.0), m))
    star = new(W, H, 'L')
    d = ImageDraw.Draw(star)
    pts = []
    for i in range(16):
        a = math.pi * 2 * i / 16 - math.pi / 2
        rr = 14 if i % 4 == 0 else (6 if i % 2 == 0 else 4)
        pts.append((u(cx + math.cos(a) * rr), u(cy + math.sin(a) * rr)))
    d.polygon(pts, fill=255)
    img = comp(img, glow(star, u(2), (255, 220, 140), 0.6), metal(star, (255, 240, 190), (190, 140, 60), u(0.5)))
    cm = new(W, H, 'L')
    d = ImageDraw.Draw(cm)
    for x, y in ((2.5, 2.5), (CW - 2.5, 2.5), (2.5, CH - 2.5), (CW - 2.5, CH - 2.5)):
        diamond(d, x + M, y + M, 3.2)
    img = comp(img, flat(dil(cm, u(0.5)), (20, 14, 8)), metal(cm, (240, 200, 130), (110, 76, 36), u(0.6)))
    return done(img)


# ═════════ 카드 그림 ═════════

AW, AH = ART[2] - ART[0], ART[3] - ART[1]

TALENTS = {   # id: (분류, 등급)
    'hunter': ('전투', 0), 'rhythm': ('전투', 0), 'heavy_finish': ('전투', 0), 'sharp_switch': ('전투', 1),
    'provoker': ('전투', 1), 'breath': ('생존', 0), 'thick_skin': ('생존', 0), 'leftover': ('생존', 1),
    'tumbler': ('생존', 1), 'shadow_step': ('생존', 1), 'once_more': ('생존', 2), 'lumberjack': ('생활', 0),
    'self_taught': ('생활', 1), 'i_am_ahead': ('전투', 2),
}


def art_bg(group, r):
    h, w = u(AH), u(AW)
    if group == '전투':
        rgb = rgrad(h, w, (236, 120, 64), (54, 8, 14), 0.5, 0.55, 0.9)
    elif group == '생존':
        rgb = rgrad(h, w, (120, 206, 176), (8, 36, 40), 0.5, 0.5, 0.9)
    elif group == '생활':
        rgb = rgrad(h, w, (252, 214, 124), (84, 54, 18), 0.5, 0.4, 0.9)
    else:
        rgb = rgrad(h, w, (180, 140, 230), (30, 14, 50), 0.5, 0.5, 0.9)
    img = to_img(noise(rgb, 5), np.ones((h, w)))
    cx, cy = w / 2, h * 0.52
    ln = Image.new('L', (w, h))
    d = ImageDraw.Draw(ln)
    if group == '전투':        # 집중선
        for i in range(48):
            a = random.uniform(0, math.pi * 2)
            r0 = random.uniform(0.45, 0.7) * w
            wd = random.uniform(0.004, 0.012) * w
            p = [(cx + math.cos(a) * r0, cy + math.sin(a) * r0), (cx + math.cos(a + wd / r0 * 6) * w, cy + math.sin(a + wd / r0 * 6) * w),
                 (cx + math.cos(a - wd / r0 * 6) * w, cy + math.sin(a - wd / r0 * 6) * w)]
            d.polygon(p, fill=255)
        img = comp(img, flat(ln, (30, 4, 6), 0.55))
    elif group == '생존':      # 빛줄기
        for i in range(7):
            x = random.uniform(0.1, 0.9) * w
            wd = random.uniform(0.03, 0.08) * w
            d.polygon([(x - wd, 0), (x + wd, 0), (x + wd * 2.5 - 0.15 * w, h), (x - wd * 0.5 - 0.15 * w, h)], fill=255)
        img = comp(img, flat(blur(ln, u(2)), (230, 255, 240), 0.22))
    elif group == '생활':      # 언덕
        pts = [(0, h)]
        for i in range(0, 41):
            x = i / 40 * w
            pts.append((x, h * (0.78 + 0.06 * math.sin(i * 0.5) + 0.03 * math.sin(i * 1.7))))
        pts.append((w, h))
        d.polygon(pts, fill=255)
        img = comp(img, flat(ln, (70, 44, 14), 0.55))
    if r == 2:                 # 전설: 금빛 햇살
        rays = Image.new('L', (w, h))
        d = ImageDraw.Draw(rays)
        for i in range(16):
            a0 = math.pi * 2 * i / 16
            d.polygon([(cx, cy), (cx + math.cos(a0) * w, cy + math.sin(a0) * w), (cx + math.cos(a0 + 0.16) * w, cy + math.sin(a0 + 0.16) * w)], fill=255)
        img = comp(img, flat(blur(rays, u(1)), (255, 226, 140), 0.35))
    # 가장자리 어둡게
    m = rgrad(h, w, (0, 0, 0), (255, 255, 255), 0.5, 0.5, 2.2)[..., 0] / 255
    img = comp(img, to_img(np.zeros((h, w, 3)), m * 0.75))
    return img


def stroke(d, pts, w0, w1, fill=255):
    """굵기가 w0 → w1 로 변하는 선 (다각형)"""
    left, right = [], []
    n = len(pts)
    for i, (x, y) in enumerate(pts):
        x0, y0 = pts[max(0, i - 1)]
        x1, y1 = pts[min(n - 1, i + 1)]
        dx, dy = x1 - x0, y1 - y0
        l = math.hypot(dx, dy) or 1
        nx, ny = -dy / l, dx / l
        wd = w0 + (w1 - w0) * i / (n - 1)
        left.append((x + nx * wd, y + ny * wd))
        right.append((x - nx * wd, y - ny * wd))
    d.polygon(left + right[::-1], fill=fill)


def emblem(eid, w, h):
    """흰색 = 문양, 검은색 = 비움. 좌표는 가운데 기준 -1..1 (R = 짧은 변의 0.38)"""
    m = Image.new('L', (w, h))
    _d = ImageDraw.Draw(m)

    class _W:   # polygon 기본 채우기 = 흰색 (L 모드에서 fill 이 없으면 안 그려짐)
        def __getattr__(self, k): return getattr(_d, k)
        def polygon(self, xy, fill=255, **kw): _d.polygon(xy, fill=fill, **kw)
    d = _W()
    cx, cy, R = w / 2, h * 0.52, min(w, h) * 0.38
    P = lambda x, y: (cx + x * R, cy + y * R)
    PS = lambda pts: [P(x, y) for x, y in pts]

    def rot(pts, a, ox=0, oy=0):
        c, s = math.cos(a), math.sin(a)
        return [(ox + x * c - y * s, oy + x * s + y * c) for x, y in pts]

    def circ(x, y, r, f=255):
        d.ellipse([cx + (x - r) * R, cy + (y - r) * R, cx + (x + r) * R, cy + (y + r) * R], fill=f)

    if eid == 'hunter':
        for k in (-0.42, 0, 0.42):
            pts = [P(0.55 + k + 0.2 * math.sin(t * 2.4) * 0.3 - t * 1.0, -0.95 + t * 1.9) for t in np.linspace(0, 1, 14)]
            stroke(d, pts, 0.02 * R, 0.16 * R)
    elif eid == 'rhythm':
        for x in (-0.6, 0.0, 0.6):
            d.polygon(PS([(x - 0.42, -0.7), (x - 0.12, -0.7), (x + 0.28, 0), (x - 0.12, 0.7), (x - 0.42, 0.7), (x - 0.02, 0)]))
        for x in (-0.95, 0.95):
            circ(x, 0, 0.09)
    elif eid == 'heavy_finish':
        pts = []
        for i in range(18):
            a = math.pi * 2 * i / 18 + random.uniform(-0.08, 0.08)
            rr = (1.0 if i % 2 == 0 else 0.5) * random.uniform(0.85, 1.05)
            pts.append((math.cos(a) * rr, math.sin(a) * rr))
        d.polygon(PS(pts))
        inner = [(x * 0.55, y * 0.55) for x, y in pts]
        d.polygon(PS(inner), fill=0)
        circ(0, 0, 0.22)
    elif eid == 'sharp_switch':
        sword = [(-0.07, 0.95), (-0.07, -0.55), (0, -0.8), (0.07, -0.55), (0.07, 0.95)]
        for a in (-0.78, 0.78):
            blade = rot([(-0.08, 0.45), (-0.08, -0.85), (0, -1.05), (0.08, -0.85), (0.08, 0.45)], a)
            guard = rot([(-0.32, 0.45), (0.32, 0.45), (0.32, 0.56), (-0.32, 0.56)], a)
            grip = rot([(-0.06, 0.56), (0.06, 0.56), (0.06, 0.85), (-0.06, 0.85)], a)
            for poly in (blade, guard, grip):
                d.polygon(PS(poly))
            px, py = rot([(0, 0.93)], a)[0]
            circ(px, py, 0.09)
    elif eid == 'provoker':
        pts = []
        for i in range(24):
            a = math.pi * 2 * i / 24
            rr = 0.95 if i % 2 == 0 else 0.74
            pts.append((math.cos(a) * rr * 1.1, math.sin(a) * rr * 0.92 - 0.05))
        d.polygon(PS(pts))
        d.polygon(PS([(-0.12, -0.55), (0.12, -0.55), (0.07, 0.25), (-0.07, 0.25)]), fill=0)
        circ(0, 0.45, 0.1, 0)
    elif eid == 'breath':
        def wind(y, x0, x1, curl, up):
            pts = [P(x, y) for x in np.linspace(x0, x1, 10)]
            ang = np.linspace(math.pi / 2 if up else -math.pi / 2, (math.pi / 2 if up else -math.pi / 2) + (-1 if up else 1) * math.pi * 1.6, 14)
            for a in ang[1:]:
                pts.append(P(x1 + math.cos(a) * curl, y - (curl if up else -curl) + math.sin(a) * curl * (1 if up else 1)))
            stroke(d, pts, 0.09 * R, 0.05 * R)
        wind(-0.45, -1.0, 0.35, 0.25, True)
        wind(0.05, -0.8, 0.65, 0.22, False)
        wind(0.55, -1.0, 0.1, 0.18, True)
    elif eid == 'thick_skin':
        def shield(sc):
            pts = [(-0.78, -0.88), (0.78, -0.88)]
            for t in np.linspace(0, 1, 12):
                pts.append((0.78 - 0.78 * t ** 1.6, -0.1 + t * 1.1))
            for t in np.linspace(1, 0, 12):
                pts.append((-(0.78 - 0.78 * t ** 1.6), -0.1 + t * 1.1))
            return [(x * sc, y * sc + 0.02) for x, y in pts]
        d.polygon(PS(shield(1.0)))
        d.polygon(PS(shield(0.8)), fill=0)
        d.polygon(PS(shield(0.66)))
        d.polygon(PS([(-0.08, -0.5), (0.08, -0.5), (0.08, 0.5), (-0.08, 0.5)]), fill=0)
        d.polygon(PS([(-0.4, -0.15), (0.4, -0.15), (0.4, 0.0), (-0.4, 0.0)]), fill=0)
    elif eid == 'leftover':
        d.polygon(PS([(-0.75, -1.0), (0.75, -1.0), (0.75, -0.84), (-0.75, -0.84)]))
        d.polygon(PS([(-0.75, 1.0), (0.75, 1.0), (0.75, 0.84), (-0.75, 0.84)]))
        d.polygon(PS([(-0.6, -0.84), (0.6, -0.84), (0.08, -0.02), (0.6, 0.84), (-0.6, 0.84), (-0.08, -0.02)]))
        d.polygon(PS([(-0.42, -0.72), (0.42, -0.72), (0.0, -0.12), (0.42, 0.72), (-0.42, 0.72), (0.0, 0.12)]), fill=0)
        d.polygon(PS([(-0.18, -0.42), (0.18, -0.42), (0, -0.16)]))
        d.polygon(PS([(-0.42, 0.72), (0.42, 0.72), (0.28, 0.5), (-0.28, 0.5)]))
    elif eid == 'tumbler':
        circ(0, 0.32, 0.64)
        circ(0, -0.52, 0.38)
        circ(-0.13, -0.56, 0.06, 0)
        circ(0.13, -0.56, 0.06, 0)
        d.arc([cx - 0.4 * R, cy + 0.0 * R, cx + 0.4 * R, cy + 0.65 * R], 20, 160, fill=0, width=int(0.08 * R))
    elif eid == 'shadow_step':
        def foot(ox, oy, a):
            sole = []
            for t in np.linspace(0, math.pi * 2, 30):
                sole.append((math.cos(t) * 0.2, math.sin(t) * 0.36))
            d.polygon(PS(rot(sole, a, ox, oy)))
            for i, (tx, tr) in enumerate(((-0.13, 0.07), (-0.02, 0.075), (0.09, 0.065), (0.18, 0.055))):
                px, py = rot([(tx, -0.47 + abs(tx) * 0.3)], a, ox, oy)[0]
                circ(px, py, tr)
        foot(-0.42, 0.38, -0.25)
        foot(0.38, -0.3, 0.2)
    elif eid == 'once_more':
        pts = []
        for t in np.linspace(0, math.pi * 2, 80):
            x = 16 * math.sin(t) ** 3
            y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
            pts.append((x / 17, y / 17 + 0.05))
        d.polygon(PS(pts))
        crack = [P(0.05, -0.62), P(-0.12, -0.3), P(0.1, -0.05), P(-0.1, 0.25), P(0.04, 0.5)]
        d.line(crack, fill=0, width=int(0.1 * R), joint='curve')
    elif eid == 'lumberjack':
        handle = rot([(-0.07, -0.9), (0.07, -0.9), (0.09, 1.0), (-0.09, 1.0)], 0.5)
        d.polygon(PS(handle))
        head = rot([(-0.1, -0.95), (0.55, -1.05), (0.75, -0.75), (0.68, -0.25), (0.45, -0.38), (-0.1, -0.45)], 0.5)
        d.polygon(PS(head))
    elif eid == 'self_taught':
        d.polygon(PS([(-1.0, -0.55), (-0.06, -0.38), (-0.06, 0.72), (-1.0, 0.55)]))
        d.polygon(PS([(1.0, -0.55), (0.06, -0.38), (0.06, 0.72), (1.0, 0.55)]))
        for k in range(4):
            y = -0.25 + k * 0.22
            d.line([P(-0.85, y - 0.12), P(-0.2, y)], fill=0, width=int(0.06 * R))
            d.line([P(0.85, y - 0.12), P(0.2, y)], fill=0, width=int(0.06 * R))
        d.polygon(PS([(-0.9, 0.62), (-0.04, 0.8), (0.04, 0.8), (0.9, 0.62), (0.9, 0.72), (0.04, 0.9), (-0.04, 0.9), (-0.9, 0.72)]))
    elif eid == 'i_am_ahead':
        d.polygon(PS([(-0.85, 0.25), (0.85, 0.25), (0.85, 0.62), (-0.85, 0.62)]))
        d.polygon(PS([(-0.85, 0.25), (-0.95, -0.45), (-0.45, 0.0), (0, -0.7), (0.45, 0.0), (0.95, -0.45), (0.85, 0.25)]))
        for x, y in ((-0.95, -0.45), (0, -0.7), (0.95, -0.45)):
            circ(x, y - 0.1, 0.13)
        for x in (-0.5, 0, 0.5):
            circ(x, 0.43, 0.09, 0)
    return m


def art(eid):
    group, r = TALENTS[eid]
    img = art_bg(group, r)
    w, h = img.size
    m = emblem(eid, w, h)
    if r == 1:   # 희귀: 반짝이
        sp = Image.new('L', (w, h))
        d = ImageDraw.Draw(sp)
        for i in range(6):
            x, y, s = random.uniform(0.1, 0.9) * w, random.uniform(0.1, 0.9) * h, random.uniform(0.02, 0.05) * w
            d.polygon([(x, y - s), (x + s * 0.25, y), (x, y + s), (x - s * 0.25, y)], fill=255)
            d.polygon([(x - s, y), (x, y + s * 0.25), (x + s, y), (x, y - s * 0.25)], fill=255)
        img = comp(img, flat(sp, (220, 240, 255), 0.8))
    gl = {'전투': (255, 210, 150), '생존': (210, 255, 230), '생활': (255, 240, 190)}.get(group, (230, 210, 255))
    img = comp(img, glow(m, u(2.2), gl, 0.75), flat(dil(m, u(0.9)), (24, 14, 8), 1.0))
    a = arr(m)
    hh, ww = a.shape
    rgb = vgrad(hh, ww, (255, 248, 226), (222, 184, 112))
    rgb = shade(rgb, bevel(m, u(0.8), 3.0), 0.5)
    img = comp(img, to_img(rgb, a))
    return done(img)


# ═════════ 성장 창 ═════════

PW, PH, PM = 360, 400, 8


def panel():
    W, H = PW + 2 * PM, PH + 2 * PM
    o = lambda b: (b[0] + PM, b[1] + PM, b[2] + PM, b[3] + PM)
    box = rrect(new(W, H, 'L'), o((0, 0, PW, PH)), 4)
    img = comp(new(W, H), shadow(box, u(4), 0, u(2), 0.8))
    bronze = metal(box, (196, 158, 96), (92, 64, 34), u(1.0))
    img = comp(img, bronze)
    inner = rrect(new(W, H, 'L'), o((3.5, 3.5, PW - 3.5, PH - 3.5)), 2.5)
    a = arr(inner)
    h, w = a.shape
    rgb = rgrad(h, w, (30, 56, 52), (9, 20, 20), 0.5, 0.4, 1.1)
    # 희미한 마름모 무늬
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    pat = (np.abs(((xx + yy) / u(14)) % 1 - 0.5) < 0.012) | (np.abs(((xx - yy) / u(14)) % 1 - 0.5) < 0.012)
    rgb = rgb + pat[..., None] * 9
    img = comp(img, to_img(noise(rgb, 1.5), a))
    img = comp(img, flat(Image.fromarray((np.clip(arr(dil(inner, u(0.6))) - a, 0, 1) * 255).astype(np.uint8)), (8, 6, 4)))
    # 안쪽 크림색 선
    l1 = rrect(new(W, H, 'L'), o((8, 8, PW - 8, PH - 8)), 1.5)
    l1 = Image.fromarray((np.clip(arr(l1) - arr(ero(l1, u(0.4))), 0, 1) * 255).astype(np.uint8))
    img = comp(img, flat(l1, (207, 195, 160), 0.75))
    # 귀퉁이 장식: ㄱ자 꺾쇠 + 마름모
    cm = new(W, H, 'L')
    d = ImageDraw.Draw(cm)
    for sx, sy in ((1, 1), (-1, 1), (1, -1), (-1, -1)):
        bx = PM + (8 if sx > 0 else PW - 8)
        by = PM + (8 if sy > 0 else PH - 8)
        d.line([(u(bx), u(by + sy * 22)), (u(bx), u(by)), (u(bx + sx * 22), u(by))], fill=255, width=u(1.2))
        d.line([(u(bx + sx * 4), u(by + sy * 12)), (u(bx + sx * 4), u(by + sy * 4)), (u(bx + sx * 12), u(by + sy * 4))], fill=255, width=u(0.6))
        diamond(d, bx, by, 4.2)
        diamond(d, bx + sx * 24, by, 1.6)
        diamond(d, bx, by + sy * 24, 1.6)
    img = comp(img, flat(dil(cm, u(0.5)), (10, 8, 6)), metal(cm, (240, 228, 196), (176, 156, 112), u(0.4)))
    return done(img)


def ring_mask(W, H, cx, cy, r0, r1):
    m = new(W, H, 'L')
    d = ImageDraw.Draw(m)
    d.ellipse([u(cx - r1), u(cy - r1), u(cx + r1), u(cy + r1)], fill=255)
    if r0 > 0:
        d.ellipse([u(cx - r0), u(cy - r0), u(cx + r0), u(cy + r0)], fill=0)
    return m


def medallion():
    D = 80
    c = D / 2
    img = new(D, D)
    teeth = new(D, D, 'L')
    pts = []
    for i in range(24):
        a = math.pi * 2 * i / 24 - math.pi / 2
        rr = 38 if i % 2 == 0 else 33
        pts.append((u(c + math.cos(a) * rr), u(c + math.sin(a) * rr)))
    ImageDraw.Draw(teeth).polygon(pts, fill=255)
    img = comp(img, shadow(teeth, u(1.5), 0, u(1), 0.8), flat(dil(teeth, u(0.6)), (12, 8, 4)), metal(teeth, (232, 196, 120), (110, 74, 34), u(0.8)))
    rim = ring_mask(D, D, c, c, 0, 32)
    img = comp(img, flat(dil(rim, u(0.6)), (12, 8, 4)), metal(rim, (214, 176, 108), (96, 66, 30), u(1.2)))
    eng = ring_mask(D, D, c, c, 29.4, 30)
    img = comp(img, flat(eng, (60, 40, 18), 0.8))
    groove = ring_mask(D, D, c, c, 24, 28)
    img = comp(img, metal(groove, (20, 22, 22), (50, 46, 40), u(0.6), invert=True))
    core = ring_mask(D, D, c, c, 0, 24)
    a = arr(core)
    h, w = a.shape
    rgb = rgrad(h, w, (54, 92, 112), (14, 30, 44), 0.45, 0.4, 1.0)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    rr = np.sqrt((xx - w / 2) ** 2 + (yy - h / 2) ** 2) / K
    rgb = rgb + ((np.abs(rr % 4 - 2) < 0.15) * 10)[..., None]
    img = comp(img, to_img(noise(rgb, 2), a))
    img = comp(img, flat(ring_mask(D, D, c, c, 22.6, 24), (150, 186, 200), 0.8))
    return done(img)


def badge(trait=False):
    D = 40 if not trait else 32
    c = D / 2
    img = new(D, D)
    if not trait:
        rim = ring_mask(D, D, c, c, 0, 18)
        img = comp(img, shadow(rim, u(1.2), 0, u(0.8), 0.8), flat(dil(rim, u(0.6)), (12, 8, 4)), metal(rim, (210, 170, 100), (92, 62, 28), u(1.0)))
        groove = ring_mask(D, D, c, c, 12, 15.6)
        img = comp(img, metal(groove, (18, 18, 16), (48, 42, 34), u(0.5), invert=True))
        cream = ring_mask(D, D, c, c, 0, 12)
        img = comp(img, metal(cream, (246, 238, 214), (190, 176, 140), u(0.6)))
        core = ring_mask(D, D, c, c, 0, 10.2)
    else:
        rim = ring_mask(D, D, c, c, 0, 14.5)
        img = comp(img, shadow(rim, u(1.2), 0, u(0.8), 0.8), flat(dil(rim, u(0.6)), (12, 8, 4)), metal(rim, (246, 236, 206), (168, 150, 104), u(1.0)))
        core = ring_mask(D, D, c, c, 0, 11.5)
    a = arr(core)
    h, w = a.shape
    img = comp(img, flat(dil(core, u(0.4)), (12, 8, 4)), to_img(noise(rgrad(h, w, (64, 60, 50), (22, 20, 16), 0.45, 0.4, 1.0), 2), a))
    return done(img)


def tab(on):
    D = 28
    c = D / 2
    rim = ring_mask(D, D, c, c, 0, 13)
    img = comp(new(D, D), shadow(rim, u(1.2), 0, u(0.8), 0.8), flat(dil(rim, u(0.6)), (10, 8, 4)),
               metal(rim, (236, 204, 130) if on else (170, 140, 92), (110, 76, 34) if on else (70, 52, 30), u(1.0)))
    core = ring_mask(D, D, c, c, 0, 10.5)
    a = arr(core)
    h, w = a.shape
    col = ((250, 242, 220), (200, 186, 148)) if on else ((44, 70, 66), (18, 32, 30))
    img = comp(img, flat(dil(core, u(0.4)), (10, 8, 4)), to_img(rgrad(h, w, col[0], col[1], 0.45, 0.4, 1.0), a))
    return done(img)


def button(light, dark, face0, face1):
    W, H = 40, 22
    b = rrect(new(W, H, 'L'), (0.5, 0.5, W - 0.5, H - 0.5), 3)
    img = comp(new(W, H), shadow(b, u(1), 0, u(0.8), 0.7), flat(dil(b, u(0.5)), (12, 8, 4)), metal(b, light, dark, u(0.9)))
    f = rrect(new(W, H, 'L'), (3, 3, W - 3, H - 3), 1.6)
    a = arr(f)
    hh, ww = a.shape
    img = comp(img, flat(dil(f, u(0.4)), (14, 10, 6)), to_img(noise(vgrad(hh, ww, face0, face1), 2), a))
    hl = rrect(new(W, H, 'L'), (4, 3.6, W - 4, 6), 1)
    img = comp(img, flat(blur(hl, u(0.6)), (255, 255, 255), 0.18))
    return done(img)


def bar():
    W, H = 160, 10
    b = rrect(new(W, H, 'L'), (4, 1.5, W - 4, H - 1.5), 1.5)
    img = comp(new(W, H), flat(dil(b, u(0.5)), (10, 8, 4)), metal(b, (196, 160, 98), (90, 62, 30), u(0.6)))
    hole = rrect(new(W, H, 'L'), (6, 3, W - 6, H - 3), 0.5)
    img = comp(img, flat(hole, (16, 18, 16)))
    cm = new(W, H, 'L')
    d = ImageDraw.Draw(cm)
    diamond(d, 4, H / 2, 4.2)
    diamond(d, W - 4, H / 2, 4.2)
    img = comp(img, flat(dil(cm, u(0.5)), (10, 8, 4)), metal(cm, (240, 214, 150), (120, 84, 36), u(0.5)))
    return done(img)


def plaque():
    W, H = 130, 24
    m = new(W, H, 'L')
    ImageDraw.Draw(m).polygon([(u(8), u(2)), (u(W - 8), u(2)), (u(W - 1), u(H / 2)), (u(W - 8), u(H - 2)), (u(8), u(H - 2)), (u(1), u(H / 2))], fill=255)
    img = comp(new(W, H), shadow(m, u(1.2), 0, u(1), 0.8), flat(dil(m, u(0.6)), (10, 8, 4)), metal(m, (220, 182, 112), (100, 68, 30), u(1.0)))
    f = new(W, H, 'L')
    ImageDraw.Draw(f).polygon([(u(10), u(4.5)), (u(W - 10), u(4.5)), (u(W - 4.5), u(H / 2)), (u(W - 10), u(H - 4.5)), (u(10), u(H - 4.5)), (u(4.5), u(H / 2))], fill=255)
    a = arr(f)
    hh, ww = a.shape
    img = comp(img, flat(dil(f, u(0.4)), (14, 10, 6)), to_img(noise(rgrad(hh, ww, (36, 64, 60), (12, 26, 26), 0.5, 0.4, 1.0), 2), a))
    return done(img)


def ornament():
    W, H = 200, 12
    m = new(W, H, 'L')
    d = ImageDraw.Draw(m)
    c = W / 2
    d.polygon([(u(4), u(H / 2)), (u(c - 10), u(H / 2 - 0.9)), (u(c - 10), u(H / 2 + 0.9))], fill=255)
    d.polygon([(u(W - 4), u(H / 2)), (u(c + 10), u(H / 2 - 0.9)), (u(c + 10), u(H / 2 + 0.9))], fill=255)
    diamond(d, c, H / 2, 5.2)
    diamond(d, c - 9, H / 2, 2.2)
    diamond(d, c + 9, H / 2, 2.2)
    img = comp(new(W, H), flat(dil(m, u(0.5)), (10, 8, 4), 0.8), metal(m, (248, 238, 210), (186, 166, 120), u(0.4)))
    hole = new(W, H, 'L')
    diamond(ImageDraw.Draw(hole), c, H / 2, 2.6)
    img = comp(img, flat(hole, (30, 50, 46)))
    return done(img)


def gem(r):
    D = 10
    c = D / 2
    m = new(D, D, 'L')
    diamond(ImageDraw.Draw(m), c, c, 4.4)
    img = comp(new(D, D), flat(dil(m, u(0.5)), (10, 8, 4)))
    gc = GEM[r]
    a = arr(m)
    h, w = a.shape
    img = comp(img, to_img(shade(rgrad(h, w, tuple(min(255, x + 90) for x in gc), tuple(int(x * 0.45) for x in gc), 0.4, 0.35, 0.8), bevel(m, u(0.6), 3), 0.5), a))
    return done(img)


def row_sel():
    W, H = 140, 12
    m = rrect(new(W, H, 'L'), (0, 0.5, W, H - 0.5), 1)
    a = arr(m)
    h, w = a.shape
    t = np.linspace(0, 1, w)[None, :]
    alpha = a * np.clip(1.4 - t * 1.2, 0, 1)
    rgb = vgrad(h, w, (240, 206, 120), (170, 120, 50))
    img = to_img(rgb, alpha * 0.85)
    edge = Image.fromarray((np.clip(arr(m) - arr(ero(m, u(0.4))), 0, 1) * 255).astype(np.uint8))
    img = comp(img, flat(edge, (255, 236, 170), 0.6))
    return done(img)


def vignette():
    s = 256
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32)
    d = np.sqrt(((xx - s / 2) / (s / 2)) ** 2 + ((yy - s / 2) / (s / 2)) ** 2)
    a = np.clip((d - 0.25) / 1.0, 0, 1) ** 1.4 * 0.92 + 0.45
    return to_img(np.dstack([np.full((s, s), 8), np.full((s, s), 12), np.full((s, s), 12)]).astype(np.float32), np.clip(a, 0, 0.96))


def power_up():
    W, H = 220, 64
    m = new(W, H, 'L')
    d = ImageDraw.Draw(m)
    c, cy = W / 2, H / 2
    # 가운데 별
    pts = []
    for i in range(8):
        a = math.pi * 2 * i / 8 - math.pi / 2
        rr = 22 if i % 2 == 0 else 6
        pts.append((u(c + math.cos(a) * rr), u(cy + math.sin(a) * rr)))
    d.polygon(pts, fill=255)
    # 날개 (깃털 3장씩)
    for s in (-1, 1):
        for k, (l, dy, wd) in enumerate(((80, -7, 3.2), (66, 0, 2.6), (50, 7, 2.0))):
            x0 = c + s * 14
            d.polygon([(u(x0), u(cy + dy * 0.3 - wd)), (u(x0 + s * l), u(cy + dy - 0.4)), (u(x0 + s * (l - 6)), u(cy + dy + wd * 0.6)), (u(x0), u(cy + dy * 0.3 + wd))], fill=255)
        diamond(d, c + s * 100, cy - 7, 2.4)
    img = comp(new(W, H), glow(m, u(4), (255, 236, 180), 0.7), flat(dil(m, u(0.6)), (20, 14, 8), 0.9), metal(m, (255, 248, 226), (214, 186, 120), u(0.6)))
    return done(img)


def main():
    os.makedirs(OUT + '/art', exist_ok=True)
    for r in range(3):
        save(card_frame(r), f'card_{r}')
        save(gem(r), f'gem_{r}')
    save(card_back(), 'card_back')
    for eid in TALENTS:
        save(art(eid), f'art/{eid}')
    save(panel(), 'panel')
    save(medallion(), 'medallion')
    save(badge(False), 'badge')
    save(badge(True), 'badge_trait')
    save(tab(True), 'tab_on')
    save(tab(False), 'tab_off')
    save(button((236, 200, 120), (120, 82, 30), (200, 160, 74), (140, 100, 36)), 'btn_gold')
    save(button((210, 120, 100), (100, 36, 26), (150, 50, 36), (96, 26, 18)), 'btn_red')
    save(button((150, 196, 186), (40, 70, 64), (44, 84, 78), (24, 48, 44)), 'btn_teal')
    save(bar(), 'bar')
    save(plaque(), 'plaque')
    save(ornament(), 'ornament')
    save(row_sel(), 'row_sel')
    vignette().save(f'{OUT}/vignette.png', optimize=True)
    save(power_up(), 'power_up')


if __name__ == '__main__':
    main()
