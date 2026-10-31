"""재능 카드 그림 14장 (84x52 도트, 1픽셀 = GUI 1칸 = 아이템 텍스처와 같은 밀도).
효과가 한눈에 보이게: 인물(스티브 비슷한 플레이어 / 좀비) + 하트·번개·숫자 같은 기호.
배경은 make_ui_textures.py 의 분류별 배경을 어둡게 깐 것.
python3 tools/make_card_art.py  (city-ability 폴더에서, make_ui_textures.py 다음에)"""
import math, os, random
from PIL import Image

GUI = 'src/main/resources/assets/bbc/textures/gui'
W, H = 84, 52
GROUND = 40
OUTLINE = (16, 12, 14, 255)


def hx(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def layer():
    return Image.new('RGBA', (W, H))


def px(l, x, y, c):
    if 0 <= x < l.width and 0 <= y < l.height:
        l.putpixel((int(x), int(y)), c)


def rect(l, x0, y0, x1, y1, c):
    """x0..x1, y0..y1 포함"""
    for y in range(int(y0), int(y1) + 1):
        for x in range(int(x0), int(x1) + 1):
            px(l, x, y, c)


def line(l, x0, y0, x1, y1, c):
    n = int(max(abs(x1 - x0), abs(y1 - y0)))
    for i in range(n + 1):
        t = i / max(1, n)
        px(l, round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), c)


def grid(l, x0, y0, rows, pal):
    for j, r in enumerate(rows):
        for i, ch in enumerate(r):
            if ch in pal:
                px(l, x0 + i, y0 + j, pal[ch])


def outline(l, c=OUTLINE):
    out = l.copy()
    a = l.load()
    o = out.load()
    for y in range(l.height):
        for x in range(l.width):
            if a[x, y][3] == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    xx, yy = x + dx, y + dy
                    if 0 <= xx < l.width and 0 <= yy < l.height and a[xx, yy][3] > 0:
                        o[x, y] = c
                        break
    return out


def comp(base, l, alpha=1.0, ol=True):
    if ol:
        l = outline(l)
    if alpha < 1:
        r, g, b, a = l.split()
        l = Image.merge('RGBA', (r, g, b, a.point(lambda v: int(v * alpha))))
    base.alpha_composite(l)
    return base


# ── 기호
HEART = [".RR.RR.", "RWRRRRR", "RRRRRRR", ".RRRRR.", "..RRR..", "...R..."]
BOLT = ["..YY", ".YY.", "YYYY", ".YY.", "YY..", "Y..."]
NOTE = ["..KK", "..KK", "..K.", "..K.", "KKK.", "KKK."]
SPARK = ["..W..", ".W.W.", "W...W", ".W.W.", "..W.."]
STAR4 = [".W.", "WYW", ".W."]
ORB = [".GG.", "GLGG", "GGGG", ".GG."]
DIG = {
    '0': ["XXX", "X.X", "X.X", "X.X", "XXX"], '1': [".X.", "XX.", ".X.", ".X.", "XXX"],
    '2': ["XXX", "..X", "XXX", "X..", "XXX"], '3': ["XXX", "..X", ".XX", "..X", "XXX"],
    '5': ["XXX", "X..", "XXX", "..X", "XXX"], '4': ["X.X", "X.X", "XXX", "..X", "..X"],
    '8': ["XXX", "X.X", "XXX", "X.X", "XXX"], 'x': ["...", "X.X", ".X.", "X.X", "..."],
    '+': ["...", ".X.", "XXX", ".X.", "..."], '%': ["X.X", "..X", ".X.", "X..", "X.X"],
    '!': ["X", "X", "X", ".", "X"], '-': ["...", "...", "XXX", "...", "..."],
}


def text(l, x, y, s, c, scale=1):
    for ch in s:
        g = DIG[ch]
        for j, r in enumerate(g):
            for i, v in enumerate(r):
                if v == 'X':
                    rect(l, x + i * scale, y + j * scale, x + i * scale + scale - 1, y + j * scale + scale - 1, c)
        x += (len(g[0]) + 1) * scale
    return x


def heart(l, x, y, gold=False, half=False):
    pal = {'R': hx('ffb020') if gold else hx('e0202a'), 'W': hx('ffffff')}
    grid(l, x, y, HEART, pal)
    if half:
        for j in range(6):
            for i in range(4, 7):
                px(l, x + i, y + j, (0, 0, 0, 0))


# ── 인물 (오른쪽 보는 옆모습, 발바닥 = y)
STEVE = dict(hair=hx('4a2f1e'), skin=hx('c89a72'), eye=hx('3a2a6a'), shirt=hx('16a5a5'), pants=hx('3a3aa0'), shoe=hx('5a5a5a'))
ZOMBIE = dict(hair=hx('3c6a28'), skin=hx('5f9a40'), eye=hx('101010'), shirt=hx('2a8a8a'), pants=hx('42328a'), shoe=hx('3a3a3a'))


def figure(l, x, y, pose='stand', p=STEVE, flip=False):
    """x = 몸 왼쪽, y = 발 아래. 크기 약 8x22"""
    f = layer()
    bx, by = 20, 40      # 임시 위치에 그린 뒤 옮김
    hd, sk = p['hair'], p['skin']

    def head(hx0, hy0):
        rect(f, hx0, hy0, hx0 + 5, hy0 + 5, sk)
        rect(f, hx0, hy0, hx0 + 5, hy0 + 1, hd)
        rect(f, hx0, hy0, hx0 + 1, hy0 + 3, hd)
        px(f, hx0 + 4, hy0 + 3, p['eye'])

    if pose == 'lie':     # 누운 자세 (머리 왼쪽)
        head(bx - 6, by - 6)
        rect(f, bx, by - 5, bx + 7, by - 1, p['shirt'])
        rect(f, bx + 8, by - 5, bx + 15, by - 1, p['pants'])
        rect(f, bx + 16, by - 5, bx + 17, by - 1, p['shoe'])
    else:
        lean = 2 if pose in ('dash', 'punch', 'kick') else 0
        head(bx + lean, by - 22)
        rect(f, bx + 1 + lean // 2, by - 16, bx + 4 + lean // 2, by - 8, p['shirt'])
        # 다리
        if pose in ('dash', 'run'):
            line(f, bx + 2, by - 8, bx - 2, by - 1, p['pants']); line(f, bx + 3, by - 8, bx - 1, by - 1, p['pants'])
            line(f, bx + 3, by - 8, bx + 7, by - 2, p['pants']); line(f, bx + 4, by - 8, bx + 8, by - 2, p['pants'])
            rect(f, bx - 3, by - 1, bx - 1, by - 1, p['shoe']); rect(f, bx + 7, by - 2, bx + 9, by - 2, p['shoe'])
        elif pose == 'kneel':
            rect(f, bx + 1, by - 8, bx + 6, by - 6, p['pants']); rect(f, bx + 5, by - 6, bx + 6, by - 1, p['pants'])
            rect(f, bx, by - 6, bx + 2, by - 1, p['pants'])
        else:
            rect(f, bx + 1, by - 8, bx + 2, by - 2, p['pants']); rect(f, bx + 3, by - 8, bx + 4, by - 2, p['pants'])
            rect(f, bx + 1, by - 1, bx + 2, by - 1, p['shoe']); rect(f, bx + 3, by - 1, bx + 4, by - 1, p['shoe'])
        # 팔
        ax, ay = bx + 2 + lean // 2, by - 15
        if pose in ('punch', 'zombie'):
            rect(f, ax, ay, ax + 3, ay + 1, p['shirt']); rect(f, ax + 4, ay, ax + 7, ay + 1, sk)
            if pose == 'zombie':
                rect(f, ax, ay + 2, ax + 7, ay + 3, sk)
        elif pose == 'sword_up':
            rect(f, ax + 1, ay - 5, ax + 2, ay + 1, sk); rect(f, ax + 1, ay, ax + 2, ay + 1, p['shirt'])
        elif pose == 'dash':
            line(f, ax, ay, ax - 4, ay + 4, sk); line(f, ax + 1, ay, ax - 3, ay + 4, sk)
        elif pose == 'shout':
            rect(f, ax, ay, ax + 1, ay + 6, sk)
        else:
            rect(f, ax + 1, ay, ax + 2, ay + 2, p['shirt']); rect(f, ax + 1, ay + 3, ax + 2, ay + 6, sk)
    if flip:
        f = f.transpose(Image.FLIP_LEFT_RIGHT)
        dx = x - (W - 1 - bx - 8)
    else:
        dx = x - bx
    out = layer()
    out.alpha_composite(f, (0, 0)) if False else None
    out.paste(f, (dx, y - by), f)
    l.alpha_composite(out)


def sword(l, x0, y0, x1, y1, blade=hx('dfe6ee'), hilt=hx('6a4a2a')):
    line(l, x0, y0, x1, y1, blade)
    n = max(abs(x1 - x0), abs(y1 - y0))
    ux, uy = (x1 - x0) / n, (y1 - y0) / n
    gx, gy = x0 + ux * 3, y0 + uy * 3
    line(l, gx - uy * 2, gy + ux * 2, gx + uy * 2, gy - ux * 2, hx('8a8a8a'))
    line(l, x0, y0, x0 + ux * 2, y0 + uy * 2, hilt)


def speed_lines(l, x0, x1, ys, c=hx('ffffff', 200)):
    for y in ys:
        a = random.randint(0, 6)
        line(l, x0 + a, y, x1 - random.randint(0, 6), y, c)


def smoke(l, cx, cy, n=8, r=6):
    for _ in range(n):
        x, y, s = cx + random.randint(-r, r), cy + random.randint(-r, r // 2), random.randint(1, 3)
        rect(l, x, y, x + s, y + s, random.choice([hx('d0d0d0'), hx('a0a0a0'), hx('ffffff')]))


def bg(group):
    b = Image.open(f'{GUI}/art_{group}.png').convert('RGBA')
    d = b.load()
    for y in range(H):
        for x in range(W):
            r, g, bb, a = d[x, y]
            k = 0.5 if y < GROUND else 0.62
            d[x, y] = (int(r * k), int(g * k), int(bb * k), 255)
    return b


# ═════════ 카드별 장면 ═════════

def hunter():
    b = bg('combat')
    l = layer(); figure(l, 16, GROUND, 'sword_up'); sword(l, 19, 20, 19, 6); comp(b, l)
    z = layer(); figure(z, 52, GROUND, 'zombie', ZOMBIE, flip=True)
    zp = z.load()
    for y in range(H):            # 아래부터 사라지는 좀비 (위쪽만 남기고 점점 흩어짐)
        for x in range(W):
            if zp[x, y][3] and (y > 30 or random.random() < (y - 16) / 30):
                zp[x, y] = (0, 0, 0, 0)
    comp(b, z, 0.85)
    s = layer(); smoke(s, 58, 30, 14, 7); comp(b, s, 0.9, ol=False)
    h = layer(); heart(h, 30, 6); heart(h, 38, 2); text(h, 46, 4, '+', hx('55ff55')); comp(b, h)
    return b


def rhythm():
    b = bg('combat')
    l = layer(); figure(l, 10, GROUND, 'punch'); comp(b, l)
    z = layer(); figure(z, 26, GROUND, 'stand', ZOMBIE, flip=True); comp(b, z, 0.9)
    s = layer(); grid(s, 23, 22, SPARK, {'W': hx('ffffff')}); comp(b, s, ol=False)
    t = layer(); x = text(t, 40, 6, '5', hx('ffdd33'), 3); comp(b, t)
    for i, (nx, ny) in enumerate(((52, 4), (60, 10), (48, 18))):
        n = layer(); grid(n, nx, ny, NOTE, {'K': hx('ffffff')}); comp(b, n)
    bo = layer(); grid(bo, 66, 22, BOLT, {'Y': hx('ffe040')}); grid(bo, 71, 26, BOLT, {'Y': hx('ffe040')})
    text(bo, 64, 32, '+', hx('55ff55')); comp(b, bo)
    return b


def heavy_finish():
    b = bg('combat')
    l = layer(); figure(l, 6, GROUND, 'punch'); comp(b, l)
    s = layer(); grid(s, 18, 21, SPARK, {'W': hx('ffe080')}); comp(b, s, ol=False)
    sp = layer(); speed_lines(sp, 22, 60, [14, 18, 22, 26, 30], hx('ffffff', 170)); comp(b, sp, ol=False)
    z = layer(); figure(z, 30, GROUND + 4, 'stand', ZOMBIE, flip=True)
    z = z.rotate(-35, resample=Image.NEAREST, center=(36, 30))
    sh = layer(); sh.paste(z, (34, -12), z); comp(b, sh)
    a = layer(); line(a, 22, 46, 76, 46, hx('ffffff')); grid(a, 74, 44, ["X..", "XX.", "XXX", "XX.", "X.."], {'X': hx('ffffff')})
    comp(b, a)
    return b


def sharp_switch():
    b = bg('combat')
    l = layer()
    sword(l, 22, 44, 50, 8, hx('dfe6ee'))
    sword(l, 62, 44, 34, 8, hx('6ff0e0'))
    comp(b, l)
    r = layer()
    for i in range(28):           # 둥근 화살표 (스위치)
        a = math.pi * 2 * i / 36 - math.pi * 0.9
        px(r, 42 + math.cos(a) * 18, 26 + math.sin(a) * 14, hx('ffdd33'))
    grid(r, 56, 32, ["XXX", ".XX", "..X"], {'X': hx('ffdd33')})
    comp(b, r)
    t = layer(); text(t, 64, 4, 'x3' if False else 'x', hx('ffffff'), 2); text(t, 72, 4, '3', hx('ffdd33'), 2); comp(b, t)
    return b


def provoker():
    b = bg('combat')
    l = layer(); figure(l, 8, GROUND, 'shout'); comp(b, l)
    s = layer()
    for i, (dx, dy) in enumerate(((0, -3), (2, 0), (0, 3))):
        line(s, 18, 19 + dy * 2, 24, 17 + dy * 3, hx('ffffff'))
    comp(b, s, ol=False)
    z = layer(); figure(z, 54, GROUND, 'stand', ZOMBIE, flip=True); comp(b, z)
    sh = layer()                  # 방패 (금 간)
    grid(sh, 44, 18, ["WWWWWWW", "WBBBBBW", "WBBBBBW", "WBBBBBW", "WBBBBBW", ".WBBBW.", "..WBW..", "...W..."],
         {'W': hx('8a6a3a'), 'B': hx('b08a50')})
    comp(b, sh)
    c = layer(); line(c, 46, 18, 48, 21, hx('101010')); line(c, 48, 21, 46, 23, hx('101010')); line(c, 46, 23, 49, 26, hx('101010'))
    comp(b, c, ol=False)
    st = layer(); grid(st, 38, 14, SPARK, {'W': hx('ffe060')}); comp(b, st, ol=False)
    t = layer(); text(t, 30, 3, '+', hx('ff5555'), 2); text(t, 38, 3, '3', hx('ff5555'), 2); text(t, 46, 3, '0', hx('ff5555'), 2); text(t, 54, 3, '%', hx('ff5555'), 2)
    comp(b, t)
    return b


def breath():
    b = bg('survival')
    l = layer(); figure(l, 36, GROUND, 'stand'); comp(b, l)
    w = layer()
    for cx, cy, r in ((26, 26, 5), (54, 22, 5), (30, 12, 4)):
        for i in range(20):
            a = math.pi * 2 * i / 26
            px(w, cx + math.cos(a) * r, cy + math.sin(a) * r * 0.6, hx('e8f8ff', 200))
        line(w, cx - r - 6, cy, cx - r, cy, hx('e8f8ff', 200))
    comp(b, w, ol=False)
    h = layer(); heart(h, 52, 6); text(h, 62, 6, '+', hx('55ff55')); heart(h, 20, 2); comp(b, h)
    return b


def thick_skin():
    b = bg('survival')
    h = layer()
    for i in range(4):
        heart(h, 6 + i * 9, 26)
    comp(b, h)
    g = layer(); heart(g, 42, 26, gold=True); comp(b, g)
    gl = layer()
    for x, y in ((40, 21), (50, 22), (47, 33), (39, 32)):
        grid(gl, x, y, STAR4, {'W': hx('ffffff'), 'Y': hx('ffe060')})
    comp(b, gl, ol=False)
    t = layer(); text(t, 54, 25, '+', hx('55ff55'), 2); text(t, 62, 25, '8', hx('55ff55'), 2); text(t, 70, 25, '%', hx('55ff55'), 2)
    comp(b, t)
    return b


def leftover():
    b = bg('survival')
    for k, a in ((0, 0.25), (6, 0.5)):
        l = layer(); figure(l, 6 + k, GROUND, 'dash'); comp(b, l, a, ol=False)
    l = layer(); figure(l, 18, GROUND, 'dash'); comp(b, l)
    hg = layer()
    grid(hg, 50, 8, ["WWWWWWWWW", ".G.....G.", ".GSSSSSG.", "..GSSSG..", "...GSG...", "....G....", "...G.G...", "..G...G..",
                     ".G..S..G.", ".G.SSS.G.", "WWWWWWWWW"], {'W': hx('8a6a3a'), 'G': hx('cfe8f0'), 'S': hx('e0c070')})
    comp(b, hg)
    t = layer(); x = text(t, 44, 26, '3', hx('aaaaaa'), 2); x = text(t, x, 26, '0', hx('aaaaaa'), 2)
    line(t, x, 30, x + 5, 30, hx('ffffff')); px(t, x + 4, 29, hx('ffffff')); px(t, x + 4, 31, hx('ffffff'))
    x = text(t, x + 8, 26, '2', hx('55ff55'), 2); text(t, x, 26, '2', hx('55ff55'), 2)
    comp(b, t)
    return b


def tumbler():
    b = bg('survival')
    d = layer(); figure(d, 18, GROUND, 'lie'); comp(b, d, 0.3, ol=False)
    l = layer(); figure(l, 40, GROUND, 'stand'); comp(b, l)
    au = layer()
    for i in range(40):
        a = math.pi * 2 * i / 40
        px(au, 44 + math.cos(a) * 10, 28 + math.sin(a) * 14, hx('ffe060'))
    comp(b, au, ol=False)
    a = layer(); grid(a, 60, 12, ["..X..", ".XXX.", "XXXXX", "..X..", "..X..", "..X..", "..X.."], {'X': hx('55ff55')}); comp(b, a)
    s = layer(); smoke(s, 30, 38, 8, 6); comp(b, s, 0.8, ol=False)
    return b


def shadow_step():
    b = bg('survival')
    for k, a in ((0, 0.15), (8, 0.3), (16, 0.45)):
        l = layer(); figure(l, 8 + k, GROUND, 'dash'); comp(b, l, a, ol=False)
    l = layer(); figure(l, 34, GROUND, 'dash')
    comp(b, l, 0.6)
    sp = layer(); speed_lines(sp, 4, 30, [22, 28, 34], hx('ffffff', 140)); comp(b, sp, ol=False)
    for x, y in ((56, 10), (64, 18), (60, 30)):
        s = layer(); grid(s, x, y, STAR4, {'W': hx('d0b0ff'), 'Y': hx('ffffff')}); comp(b, s, ol=False)
    return b


def once_more():
    b = bg('survival')
    h = layer()
    big = ["..RRR...RRR..", ".RWWRR.RRRRR.", "RRWRRRRRRRRRR", "RRRRRRRRRRRRR", "RRRRRRRRRRRRR", ".RRRRRRRRRRR.",
           "..RRRRRRRRR..", "...RRRRRRR...", "....RRRRR....", ".....RRR.....", "......R......"]
    grid(h, 20, 14, [r.replace('R', 'R') for r in big], {'R': hx('e0202a'), 'W': hx('ffffff')})
    h = h.resize((W * 2, H * 2), Image.NEAREST).crop((20, 14, 20 + W, 14 + H))
    comp(b, h)
    c = layer(); line(c, 33, 15, 30, 21, hx('101010')); line(c, 30, 21, 35, 27, hx('101010')); line(c, 35, 27, 32, 33, hx('101010'))
    comp(b, c, ol=False)
    g = layer()
    for x, y in ((14, 10), (52, 8), (50, 30), (16, 32), (34, 4)):
        grid(g, x, y, STAR4, {'W': hx('ffe060'), 'Y': hx('ffffff')})
    comp(b, g, ol=False)
    t = layer(); text(t, 62, 18, '1', hx('ffdd33'), 3); comp(b, t)
    return b


def lumberjack():
    b = bg('life')
    lg = layer()
    for y in range(18, 40):
        for x in range(30, 46):
            c = hx('6b5030') if (x - 30) % 4 else hx('4f3a22')
            px(lg, x, y, c)
    rect(lg, 30, 18, 45, 19, hx('b0884e'))
    comp(b, lg)
    l = layer(); figure(l, 10, GROUND, 'punch'); comp(b, l)
    ax = layer()
    line(ax, 18, 28, 28, 18, hx('6a4a2a'))
    grid(ax, 25, 13, [".XXX", "XXXX", "XXX.", "XX.."], {'X': hx('cfd6de')})
    comp(b, ax)
    ch = layer()
    for x, y in ((48, 14), (52, 20), (47, 24)):
        rect(ch, x, y, x + 1, y + 1, hx('b0884e'))
    comp(b, ch, ol=False)
    o = layer(); grid(o, 58, 8, ORB, {'G': hx('7ad83a'), 'L': hx('e0ff80')}); grid(o, 64, 14, ORB, {'G': hx('7ad83a'), 'L': hx('e0ff80')})
    comp(b, o)
    t = layer(); text(t, 60, 24, 'x', hx('ffffff'), 2); text(t, 68, 24, '2', hx('55ff55'), 2); comp(b, t)
    return b


def self_taught():
    b = bg('life')
    bk = layer()
    grid(bk, 14, 22, ["PPPPPPPPPP.PPPPPPPPPP", "PLLLLLLLPP.PPLLLLLLLP", "PPPPPPPPPP.PPPPPPPPPP", "PLLLLLLPPP.PPPLLLLLLP",
                      "PPPPPPPPPP.PPPPPPPPPP", "PLLLLLLLPP.PPLLLLLLP.", "PPPPPPPPPPBPPPPPPPPPP", "BBBBBBBBBBBBBBBBBBBBB"],
         {'P': hx('f0e6c8'), 'L': hx('8a7a60'), 'B': hx('7a3a22')})
    rect(bk, 22, 30, 26, 39, hx('6a4a2a'))
    comp(b, bk)
    g = layer()
    for x, y in ((18, 12), (26, 6), (34, 14), (40, 8), (22, 2)):
        grid(g, x, y, ["X.X", ".X.", "X.X"] if (x // 2) % 2 else [".X.", "XXX", ".X."], {'X': hx('c080ff')})
    comp(b, g, ol=False)
    t = layer(); x = text(t, 50, 24, '+', hx('55ffff'), 2); x = text(t, x, 24, '5', hx('55ffff'), 2); x = text(t, x, 24, '0', hx('55ffff'), 2)
    text(t, x, 24, '%', hx('55ffff'), 2)
    comp(b, t)
    return b


def i_am_ahead():
    b = bg('combat')
    l = layer(); figure(l, 8, GROUND, 'sword_up'); sword(l, 11, 20, 11, 6); comp(b, l)
    s = layer()
    for k in range(3):
        line(s, 22 + k * 4, 36, 34 + k * 4, 14, hx('ffffff', 210))
    comp(b, s, ol=False)
    t = layer(); x = text(t, 42, 16, '1', hx('ffdd33'), 4); text(t, x, 16, '5', hx('ffdd33'), 4); comp(b, t)
    cr = layer(); grid(cr, 50, 4, ["X..X..X", "XX.X.XX", "XXXXXXX", "XRXGXRX", "XXXXXXX"], {'X': hx('ffd030'), 'R': hx('e02020'), 'G': hx('30c0ff')})
    comp(b, cr)
    return b


ART = {
    'hunter': hunter, 'rhythm': rhythm, 'heavy_finish': heavy_finish, 'sharp_switch': sharp_switch, 'provoker': provoker,
    'breath': breath, 'thick_skin': thick_skin, 'leftover': leftover, 'tumbler': tumbler, 'shadow_step': shadow_step,
    'once_more': once_more, 'lumberjack': lumberjack, 'self_taught': self_taught, 'i_am_ahead': i_am_ahead,
}


def main():
    os.makedirs(f'{GUI}/card', exist_ok=True)
    sheet = Image.new('RGBA', (7 * 88, 2 * 56), (20, 20, 24, 255))
    for i, (k, fn) in enumerate(ART.items()):
        random.seed(k)
        im = fn()
        im.save(f'{GUI}/card/{k}.png')
        sheet.alpha_composite(im, ((i % 7) * 88 + 2, (i // 7) * 56 + 2))
    os.makedirs('/tmp/pv', exist_ok=True)
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save('/tmp/pv/card_art.png')


if __name__ == '__main__':
    main()
