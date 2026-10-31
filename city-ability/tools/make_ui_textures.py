"""재능 카드용 도트 텍스처 (마인크래프트 GUI 처럼 1칸 = 1픽셀, 부드럽게 하지 않음).
 - gui/art_<분류>.png  84x52 : 카드 그림 칸 배경 (가운데에 바닐라 아이템을 3배로 그림)
 - gui/card_back.png  100x136 : 카드 뒷면
python3 tools/make_ui_textures.py  (city-ability 폴더에서)"""
import os, random
from PIL import Image

OUT = 'src/main/resources/assets/bbc/textures/gui'
random.seed(3)
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def hx(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def sky(im, y0, y1, cols):
    """세로 그러데이션을 몇 가지 색으로 디더링 (바닐라 하늘·노을 느낌)"""
    w = im.width
    n = len(cols) - 1
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0 - 1) * n
        i = min(n - 1, int(t))
        f = t - i
        for x in range(w):
            im.putpixel((x, y), cols[i + 1] if f * 16 > BAYER[y % 4][x % 4] else cols[i])


def noise_rows(im, y0, y1, pal, top=None):
    for y in range(y0, y1):
        for x in range(im.width):
            im.putpixel((x, y), random.choice(pal))
    if top:
        for x in range(im.width):
            im.putpixel((x, y0), random.choice(top))
            if random.random() < 0.5:
                im.putpixel((x, y0 + 1), random.choice(top))


def art(kind):
    W, H = 84, 52
    im = Image.new('RGBA', (W, H))
    if kind == 'combat':      # 네더: 붉은 하늘 + 네더랙
        sky(im, 0, 40, [hx('2a0606'), hx('4a0c0c'), hx('7a1a12'), hx('a8341c')])
        noise_rows(im, 40, H, [hx('6f2a2a'), hx('5a1e1e'), hx('874040'), hx('4a1616')])
        for _ in range(14):   # 불씨
            x, y = random.randrange(W), random.randrange(4, 38)
            im.putpixel((x, y), hx('ffaa00') if random.random() < 0.5 else hx('ff5500'))
        for x in range(W):    # 용암 줄
            if 30 <= x < 46:
                im.putpixel((x, 40), hx('ff8a00'))
                im.putpixel((x, 41), hx('e05a00'))
    elif kind == 'survival':  # 낮 하늘 + 잔디
        sky(im, 0, 40, [hx('6f97f0'), hx('86aaf6'), hx('a3c0fb'), hx('c4d8ff')])
        for cx, cy, cw in ((12, 8, 14), (58, 14, 18), (34, 4, 10)):   # 구름
            for x in range(cx, cx + cw):
                for y in range(cy, cy + 3):
                    if not ((x in (cx, cx + cw - 1)) and y == cy):
                        im.putpixel((x % W, y), hx('ffffff') if y < cy + 2 else hx('dfe8f8'))
        noise_rows(im, 40, H, [hx('866043'), hx('6b4b33'), hx('9a7050'), hx('5c3f2a')],
                   top=[hx('5da03a'), hx('79c05a'), hx('4a8a2e')])
    elif kind == 'life':      # 노을 + 밀밭
        sky(im, 0, 38, [hx('f08a40'), hx('ffb060'), hx('ffd890'), hx('fff0c0')])
        for x in range(36, 48):   # 해
            for y in range(28, 38):
                if (x - 42) ** 2 + (y - 38) ** 2 <= 36:
                    im.putpixel((x, y), hx('fff8d8'))
        noise_rows(im, 38, H, [hx('d9b53c'), hx('b08a28'), hx('e8cc5a'), hx('9c7820')])
        for x in range(0, W, 3):  # 밀 이삭
            h = random.randint(2, 4)
            for y in range(38 - h, 38):
                im.putpixel((x, y), hx('c8a030') if y > 38 - h else hx('e8d070'))
    else:                     # 엔드
        sky(im, 0, 40, [hx('0e0818'), hx('1c1030'), hx('2c1a48'), hx('3a2460')])
        noise_rows(im, 40, H, [hx('dbdba0'), hx('c8c890'), hx('e8e8b8'), hx('b8b880')])
        for _ in range(20):
            im.putpixel((random.randrange(W), random.randrange(36)), hx('ffffff'))
    return im


def card_back():
    W, H = 100, 136
    im = Image.new('RGBA', (W, H))
    blk, hl, base, sh = hx('000000'), hx('6a6a74'), hx('3a3a42'), hx('18181c')
    # 바닐라식 테두리: 검은 외곽(모서리 깎음) → 밝은 위왼쪽/어두운 아래오른쪽 → 바탕색 고리
    for x in range(1, W - 1):
        im.putpixel((x, 0), blk); im.putpixel((x, H - 1), blk)
    for y in range(1, H - 1):
        im.putpixel((0, y), blk); im.putpixel((W - 1, y), blk)
    for x in range(1, W - 1):
        for y in range(1, H - 1):
            im.putpixel((x, y), base)
    for x in range(1, W - 2):
        im.putpixel((x, 1), hl)
    for y in range(1, H - 2):
        im.putpixel((1, y), hl)
    for x in range(2, W - 1):
        im.putpixel((x, H - 2), sh)
    for y in range(2, H - 1):
        im.putpixel((W - 2, y), sh)
    # 안쪽: 어두운 심층암 + 금색 격자
    in0, in1, line, dot = hx('1a1a1f'), hx('222228'), hx('5a4a1e'), hx('d9a62a')
    for x in range(4, W - 4):
        for y in range(4, H - 4):
            im.putpixel((x, y), in1 if (x + y) % 2 == 0 and random.random() < 0.3 else in0)
            if (x - 4 + y - 4) % 10 == 0 or (x - 4 - (y - 4)) % 10 == 0:
                im.putpixel((x, y), line)
    for x in range(4, W - 4):
        for y in range(4, H - 4):
            if (x - 4) % 10 == 5 and (y - 4) % 10 == 0:
                im.putpixel((x, y), dot)
    for x in range(3, W - 3):
        im.putpixel((x, 3), hx('0a0a0c')); im.putpixel((x, H - 4), hx('4a4a52'))
    for y in range(3, H - 3):
        im.putpixel((3, y), hx('0a0a0c')); im.putpixel((W - 4, y), hx('4a4a52'))
    return im


def main():
    # 예전 매끈한 텍스처 정리
    if os.path.isdir(OUT + '/art'):   # 예전 매끈한 카드 그림 폴더
        for f in os.listdir(OUT + '/art'):
            os.remove(os.path.join(OUT + '/art', f))
        os.rmdir(OUT + '/art')
    os.makedirs(OUT, exist_ok=True)
    for k in ('combat', 'survival', 'life', 'other'):
        art(k).save(f'{OUT}/art_{k}.png')
    card_back().save(f'{OUT}/card_back.png')


if __name__ == '__main__':
    main()
