"""재능 카드 그림 14장 — Deepwoken 카드처럼 '흰 종이 위 먹선으로 그린 상징 하나'를 도트로.
(그림은 직접 만든 상징이고 Deepwoken 그림을 베끼지 않음)
4배 크기에 거친 붓질로 그린 뒤 84x52 로 줄이고, 진하기에 따라 먹 / 회색 / 종이 세 단계로 나눠 픽셀을 딱 떨어지게.
붓 자국 느낌: 먹 픽셀 일부를 회색으로 (마른 붓), 주변에 먹 점 몇 개.
python3 tools/make_card_art.py  (city-ability 폴더에서)"""
import math, os, random
from PIL import Image, ImageDraw, ImageFilter

GUI = 'src/main/resources/assets/bbc/textures/gui'
W, H = 84, 52
K = 4                       # 그릴 때 배율
INK = (34, 30, 28, 255)
GREY = (110, 104, 96, 255)
PAPER = (246, 243, 236, 255)
PAPER_M = (228, 223, 212, 255)
PAPER_D = (204, 198, 186, 255)
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


class Ink:
    """가운데 기준 좌표 (-1..1, 1 = 22픽셀) 로 그리는 붓"""

    def __init__(self):
        self.im = Image.new('L', (W * K, H * K), 0)
        self.d = ImageDraw.Draw(self.im)
        self.cx, self.cy, self.R = W * K / 2, H * K / 2 + K, 22 * K

    def P(self, x, y):
        return (self.cx + x * self.R, self.cy + y * self.R)

    def dot(self, x, y, r, v=255):
        X, Y = self.P(x, y)
        rr = r * self.R
        self.d.ellipse([X - rr, Y - rr, X + rr, Y + rr], fill=v)

    def stroke(self, pts, w0=0.07, w1=None, jitter=0.012, v=255, steps=8):
        """붓질: 점들을 이은 선을 굵기를 바꿔 가며 동그라미로 찍음 (살짝 떨림)"""
        w1 = w0 if w1 is None else w1
        w0, w1 = w0 * 1.25, w1 * 1.25
        seg = []
        for i in range(len(pts) - 1):
            (x0, y0), (x1, y1) = pts[i], pts[i + 1]
            n = max(2, int(math.hypot(x1 - x0, y1 - y0) * steps * 6))
            for k in range(n):
                t = k / n
                seg.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
        seg.append(pts[-1])
        N = len(seg)
        for i, (x, y) in enumerate(seg):
            t = i / max(1, N - 1)
            w = w0 + (w1 - w0) * t
            w *= 1 + random.uniform(-0.15, 0.15)
            self.dot(x + random.uniform(-jitter, jitter), y + random.uniform(-jitter, jitter), max(0.012, w / 2), v)

    def taper(self, pts, w=0.1, **kw):
        """양끝이 가늘고 가운데가 굵은 붓질"""
        n = len(pts)
        for i in range(n - 1):
            t0, t1 = i / (n - 1), (i + 1) / (n - 1)
            self.stroke([pts[i], pts[i + 1]], w * math.sin(math.pi * t0) + 0.02, w * math.sin(math.pi * t1) + 0.02, **kw)

    def curve(self, f, t0, t1, n=24):
        return [f(t0 + (t1 - t0) * i / n) for i in range(n + 1)]

    def poly(self, pts, v=255):
        self.d.polygon([self.P(x, y) for x, y in pts], fill=v)

    def outline(self, pts, w=0.06, closed=True, **kw):
        self.stroke(pts + ([pts[0]] if closed else []), w, **kw)

    def ellipse(self, x, y, rx, ry, w=0.06, a0=0, a1=360, rot=0, **kw):
        pts = []
        for i in range(33):
            a = math.radians(a0 + (a1 - a0) * i / 32)
            px, py = math.cos(a) * rx, math.sin(a) * ry
            c, s = math.cos(rot), math.sin(rot)
            pts.append((x + px * c - py * s, y + px * s + py * c))
        self.stroke(pts, w, **kw)

    def render(self, dry=0.07, specks=6):
        small = self.im.filter(ImageFilter.GaussianBlur(K * 0.35)).resize((W, H), Image.BOX)
        out = Image.new('RGBA', (W, H), PAPER)
        # 종이: 가장자리로 갈수록 살짝 어둡게 + 결
        for y in range(H):
            for x in range(W):
                dx, dy = (x - W / 2) / (W / 2), (y - H / 2) / (H / 2)
                d = max(abs(dx) ** 4, abs(dy) ** 4) ** 0.25          # 네모난 비네트 (가장자리만 어둡게)
                t = max(0.0, (d - 0.62) / 0.38) * 2                   # 0..2
                b = BAYER[y % 4][x % 4] / 16
                c = PAPER if t < b else (PAPER_M if t - 1 < b else PAPER_D)
                out.putpixel((x, y), c)
        s = small.load()
        for y in range(H):
            for x in range(W):
                v = s[x, y] / 255
                if v > 0.55:
                    out.putpixel((x, y), GREY if random.random() < dry else INK)
                elif v > 0.28:
                    out.putpixel((x, y), GREY)
        for _ in range(specks):   # 튄 먹 점
            x, y = random.randrange(6, W - 6), random.randrange(4, H - 4)
            if s[x, y] < 40:
                out.putpixel((x, y), GREY if random.random() < 0.6 else INK)
        # 그림 칸 안쪽 어두운 테 (흰 종이가 끼워진 느낌)
        for x in range(W):
            out.putpixel((x, 0), PAPER_D); out.putpixel((x, H - 1), (190, 184, 172, 255))
        for y in range(H):
            out.putpixel((0, y), PAPER_D); out.putpixel((W - 1, y), (190, 184, 172, 255))
        return out


# ═════════ 상징 ═════════

def hunter(p):           # 할퀸 자국 세 줄 + 핏방울
    for k in (-0.45, 0, 0.45):
        pts = p.curve(lambda t: (0.55 + k - t * 1.15 + 0.12 * math.sin(t * 3), -0.95 + t * 1.85), 0, 1, 14)
        p.taper(pts, 0.2)
    for x, y, r in ((-0.55, 0.75, 0.05), (-0.25, 0.88, 0.035), (0.75, -0.75, 0.03)):
        p.dot(x, y, r)


def rhythm(p):           # 이어진 음표 둘 + 울림
    p.ellipse(-0.45, 0.55, 0.2, 0.13, 0.05, rot=-0.4)
    p.ellipse(0.3, 0.35, 0.2, 0.13, 0.05, rot=-0.4)
    p.dot(-0.45, 0.55, 0.12); p.dot(0.3, 0.35, 0.12)
    p.stroke([(-0.28, 0.5), (-0.28, -0.6)], 0.06)
    p.stroke([(0.47, 0.3), (0.47, -0.8)], 0.06)
    p.stroke([(-0.28, -0.6), (0.47, -0.8)], 0.16, 0.14)
    p.stroke([(-0.28, -0.38), (0.47, -0.58)], 0.09, 0.08)
    for r in (0.25, 0.4):
        p.ellipse(0.85, -0.1, r * 0.6, r, 0.035, a0=-60, a1=60)
        p.ellipse(-0.95, 0.0, r * 0.6, r, 0.035, a0=120, a1=240)


def heavy_finish(p):     # 쥔 주먹 + 충격선
    p.outline([(-0.75, -0.45), (0.25, -0.62), (0.45, -0.45), (0.5, 0.25), (0.25, 0.48), (-0.55, 0.48), (-0.8, 0.2)], 0.07)
    for y in (-0.2, 0.05, 0.28):
        p.stroke([(-0.05, y - 0.08), (0.42, y - 0.04)], 0.045)
    p.stroke([(-0.75, -0.1), (-0.35, -0.2), (-0.1, -0.05), (-0.25, 0.2)], 0.06)   # 엄지
    p.stroke([(-0.8, 0.2), (-1.0, 0.75)], 0.07); p.stroke([(-0.55, 0.48), (-0.6, 0.85)], 0.07)   # 손목
    for a in (-0.5, -0.15, 0.2, 0.55):
        p.taper([(0.7 + math.cos(a) * 0.05, math.sin(a) * 0.5), (1.15 + math.cos(a) * 0.3, math.sin(a) * 0.9)], 0.08)


def sharp_switch(p):     # 엇갈린 칼 두 자루 + 돌아가는 화살표
    for sgn in (-1, 1):
        a = sgn * 0.75
        c, s = math.cos(a), math.sin(a)
        R = lambda x, y: (x * c - y * s, x * s + y * c)
        p.outline([R(-0.06, 0.5), R(-0.06, -0.85), R(0, -1.05), R(0.06, -0.85), R(0.06, 0.5)], 0.04)
        p.stroke([R(-0.25, 0.5), R(0.25, 0.5)], 0.07)
        p.stroke([R(0, 0.55), R(0, 0.85)], 0.08)
        p.dot(*R(0, 0.92), 0.06)
    p.ellipse(0, 0, 0.95, 0.75, 0.04, a0=200, a1=330)
    p.ellipse(0, 0, 0.95, 0.75, 0.04, a0=20, a1=150)
    for (x, y), d in (((0.5, -0.65), 1), ((-0.5, 0.65), -1)):
        p.stroke([(x, y), (x - 0.18 * d, y - 0.1 * d)], 0.05); p.stroke([(x, y), (x - 0.12 * d, y + 0.12 * d)], 0.05)


def provoker(p):         # 금 간 방패 + 충격
    right = [(0.62, -0.78), (0.64, -0.2)] + p.curve(lambda t: (0.64 * (1 - t ** 1.6), -0.2 + t * 1.15), 0, 1, 10)[1:]
    left = [(-x, y) for x, y in reversed(right)]
    p.outline([(-0.62, -0.78), (0.62, -0.78)] + right[1:] + left[1:-1], 0.07)
    p.stroke([(0.1, -0.78), (-0.06, -0.45), (0.14, -0.15), (-0.08, 0.2), (0.04, 0.5)], 0.05)
    for a in (-2.5, -1.57, -0.65):
        p.taper([(math.cos(a) * 0.95, -0.15 + math.sin(a) * 0.85), (math.cos(a) * 1.2, -0.15 + math.sin(a) * 1.05)], 0.07)


def breath(p):           # 소용돌이 바람
    pts = p.curve(lambda t: (math.cos(t) * (0.08 + t * 0.07), math.sin(t) * (0.08 + t * 0.07) - 0.05), 0, 4.2 * math.pi, 60)
    p.stroke(pts, 0.06)
    for y, x0, x1 in ((-0.55, -1.1, -0.2), (0.15, 0.55, 1.15), (0.65, -0.9, -0.1)):
        pts = p.curve(lambda t: (x0 + (x1 - x0) * t, y + 0.08 * math.sin(t * 6)), 0, 1, 12)
        p.taper(pts, 0.09)


def thick_skin(p):       # 거북 등딱지
    p.ellipse(0, 0, 1.0, 0.82, 0.08)
    hexa = [(math.cos(a) * 0.32, math.sin(a) * 0.27) for a in [i * math.pi / 3 + math.pi / 6 for i in range(6)]]
    p.outline(hexa, 0.06)
    for (x, y) in hexa:
        p.stroke([(x, y), (x * 2.85, y * 2.85)], 0.055)
    p.ellipse(0, 0, 0.68, 0.56, 0.035, a0=0, a1=360)


def leftover(p):         # 모래시계
    p.stroke([(-0.55, -0.95), (0.55, -0.95)], 0.1)
    p.stroke([(-0.55, 0.95), (0.55, 0.95)], 0.1)
    for s in (-1, 1):
        p.stroke(p.curve(lambda t: (s * (0.42 - 0.36 * math.sin(t * math.pi / 2)), -0.88 + t * 0.88), 0, 1, 10), 0.05)
        p.stroke(p.curve(lambda t: (s * (0.06 + 0.36 * math.sin(t * math.pi / 2)), t * 0.88), 0, 1, 10), 0.05)
        p.stroke([(s * 0.62, -0.95), (s * 0.62, 0.95)], 0.05)
    p.poly([(-0.3, -0.4), (0.3, -0.4), (0.05, -0.08), (-0.05, -0.08)])
    p.poly([(-0.36, 0.86), (0.36, 0.86), (0.15, 0.6), (-0.15, 0.6)])
    p.stroke([(0, -0.05), (0, 0.55)], 0.025)


def tumbler(p):          # 오뚝이 + 흔들림
    p.ellipse(0, 0.35, 0.55, 0.52, 0.07)
    p.ellipse(0, -0.5, 0.32, 0.3, 0.065)
    p.dot(-0.11, -0.53, 0.05); p.dot(0.11, -0.53, 0.05)
    p.ellipse(0, -0.42, 0.1, 0.06, 0.035, a0=20, a1=160)
    p.ellipse(0, 0.25, 0.32, 0.18, 0.04, a0=10, a1=170)
    for s in (-1, 1):
        p.ellipse(s * 0.2, 0.35, 0.95, 0.75, 0.04, a0=(70 if s > 0 else 95), a1=(85 if s > 0 else 110))
        p.taper(p.curve(lambda t: (s * (0.75 + t * 0.15), 0.1 + t * 0.6), 0, 1, 6), 0.06)


def shadow_step(p):      # 발자국 셋 (뒤로 갈수록 흐려짐)
    def foot(x, y, a, v):
        c, s_ = math.cos(a), math.sin(a)
        R = lambda px, py: (x + px * c - py * s_, y + px * s_ + py * c)
        p.outline([R(math.cos(t) * 0.17, math.sin(t) * 0.3 + 0.08) for t in [i * math.pi / 10 for i in range(20)]], 0.05, v=v)
        p.stroke([R(-0.08, 0.15), R(0.08, 0.15)], 0.03, v=v)
        for tx, tr in ((-0.12, 0.05), (-0.03, 0.055), (0.06, 0.05), (0.14, 0.04)):
            X, Y = R(tx, -0.32 + abs(tx) * 0.45)
            p.dot(X, Y, tr, v)
    foot(-0.85, 0.42, -0.35, 120)
    foot(-0.25, -0.02, -0.15, 185)
    foot(0.35, -0.45, 0.1, 255)
    for k in range(3):
        p.taper([(0.7, -0.15 + k * 0.25), (1.15, -0.2 + k * 0.25)], 0.06)


def once_more(p):        # 금 간 하트를 꿰맨 자국
    pts = []
    for t in [i * 2 * math.pi / 60 for i in range(61)]:
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((x / 17 * 1.05, y / 17 * 1.05 + 0.05))
    p.stroke(pts, 0.08)
    crack = [(0.05, -0.55), (-0.12, -0.25), (0.1, 0.0), (-0.1, 0.3), (0.02, 0.62)]
    p.stroke(crack, 0.05)
    for (x, y) in ((-0.02, -0.4), (0.0, -0.12), (0.0, 0.15), (-0.04, 0.45)):
        p.stroke([(x - 0.16, y - 0.05), (x + 0.16, y + 0.05)], 0.035)


def lumberjack(p):       # 그루터기에 박힌 도끼
    p.ellipse(0, 0.4, 0.8, 0.26, 0.06)
    p.ellipse(0, 0.4, 0.48, 0.15, 0.035)
    p.ellipse(0, 0.4, 0.2, 0.06, 0.03)
    p.stroke([(-0.8, 0.4), (-0.84, 0.9)], 0.06); p.stroke([(0.8, 0.4), (0.84, 0.9)], 0.06)
    p.stroke(p.curve(lambda t: (-0.84 + 1.68 * t, 0.9 + 0.08 * math.sin(t * math.pi)), 0, 1, 10), 0.06)
    p.stroke([(0.05, 0.25), (0.75, -0.95)], 0.09)                     # 자루
    head = [(0.1, 0.28), (-0.55, 0.05), (-0.62, -0.42), (-0.3, -0.3), (0.28, -0.05)]
    p.poly(head, 255)
    p.poly([(x * 0.7 + 0.02, y * 0.7 - 0.02) for x, y in head], 120)
    for x, y in ((-0.85, -0.55), (-0.95, -0.2), (-0.6, -0.75)):
        p.stroke([(x, y), (x - 0.1, y - 0.07)], 0.05)


def self_taught(p):      # 펼친 책 + 빛
    for s in (-1, 1):
        p.outline([(0, -0.25), (s * 0.95, -0.4), (s * 0.95, 0.55), (0, 0.7)], 0.06, closed=False)
        p.stroke([(0, 0.7), (0, -0.25)], 0.05)
        for k in range(4):
            y = -0.15 + k * 0.18
            p.stroke([(s * 0.18, y + 0.02), (s * 0.78, y - 0.08)], 0.035)
        p.stroke(p.curve(lambda t: (s * 0.95 * t, 0.62 + 0.12 * math.sin(t * math.pi) + 0.1), 0, 1, 8), 0.04)
    for a in (-2.3, -1.9, -1.57, -1.25, -0.85):
        p.taper([(math.cos(a) * 0.45, -0.3 + math.sin(a) * 0.25), (math.cos(a) * 0.8, -0.3 + math.sin(a) * 0.6)], 0.06)


def i_am_ahead(p):       # 왕관
    p.outline([(-0.8, 0.55), (0.8, 0.55), (0.8, 0.2), (-0.8, 0.2)], 0.07)
    p.stroke([(-0.8, 0.2), (-0.95, -0.55), (-0.45, -0.05), (0, -0.75), (0.45, -0.05), (0.95, -0.55), (0.8, 0.2)], 0.07)
    for x, y in ((-0.95, -0.55), (0, -0.75), (0.95, -0.55)):
        p.dot(x, y - 0.12, 0.09)
    for x in (-0.45, 0, 0.45):
        p.ellipse(x, 0.37, 0.08, 0.08, 0.035)
    p.dot(0, 0.37, 0.05)


ART = {
    'hunter': hunter, 'rhythm': rhythm, 'heavy_finish': heavy_finish, 'sharp_switch': sharp_switch, 'provoker': provoker,
    'breath': breath, 'thick_skin': thick_skin, 'leftover': leftover, 'tumbler': tumbler, 'shadow_step': shadow_step,
    'once_more': once_more, 'lumberjack': lumberjack, 'self_taught': self_taught, 'i_am_ahead': i_am_ahead,
}


def main():
    os.makedirs(f'{GUI}/card', exist_ok=True)
    sheet = Image.new('RGBA', (7 * 88, 2 * 56), (40, 40, 46, 255))
    for i, (k, fn) in enumerate(ART.items()):
        random.seed(k)
        p = Ink()
        fn(p)
        im = p.render()
        im.save(f'{GUI}/card/{k}.png')
        sheet.alpha_composite(im, ((i % 7) * 88 + 2, (i // 7) * 56 + 2))
    os.makedirs('/tmp/pv', exist_ok=True)
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save('/tmp/pv/card_art.png')


if __name__ == '__main__':
    main()
