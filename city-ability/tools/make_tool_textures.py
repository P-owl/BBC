"""수련 도구 8종 16x16 도트 텍스처 + 모델 + warped_fungus_on_a_stick 덮어쓰기 모델.
도형을 칠한 뒤 자동 음영(위 = 밝게, 아래·오른쪽 = 어둡게)과 자동 외곽선을 넣는다.
python3 tools/make_tool_textures.py  (city-ability 폴더에서)"""
import json, os
from PIL import Image

OUT = 'src/main/resources/assets'
TEX = f'{OUT}/bbc/textures/item'
MOD = f'{OUT}/bbc/models/item'
CMD0 = 7101
IDS = ['str', 'end', 'agi', 'int', 'light', 'medium', 'heavy', 'class']
OUTLINE = (26, 20, 16, 255)


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mix(c, k):
    """k>0 밝게, k<0 어둡게"""
    if k >= 0:
        return tuple(int(v + (255 - v) * k) for v in c[:3]) + (255,)
    return tuple(int(v * (1 + k)) for v in c[:3]) + (255,)


class Canvas:
    def __init__(self):
        self.m = {}      # (x,y) -> (material, color)

    def px(self, x, y, mat, col):
        if 0 <= x < 16 and 0 <= y < 16:
            self.m[(x, y)] = (mat, col)

    def rect(self, x0, y0, x1, y1, mat, col):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, mat, col)

    def ellipse(self, cx, cy, rx, ry, mat, col):
        for y in range(16):
            for x in range(16):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1:
                    self.px(x, y, mat, col)

    def line(self, x0, y0, x1, y1, mat, col):
        n = max(abs(x1 - x0), abs(y1 - y0))
        for i in range(n + 1):
            t = i / max(1, n)
            self.px(round(x0 + (x1 - x0) * t), round(y0 + (y1 - y0) * t), mat, col)

    def grid(self, x0, y0, rows, pal):
        for j, r in enumerate(rows):
            for i, ch in enumerate(r):
                if ch in pal:
                    self.px(x0 + i, y0 + j, *pal[ch])

    def render(self, shade=True):
        im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
        for (x, y), (mat, col) in self.m.items():
            c = col
            if shade and mat != 'flat':
                up = self.m.get((x, y - 1))
                dn = self.m.get((x, y + 1))
                rt = self.m.get((x + 1, y))
                lf = self.m.get((x - 1, y))
                if up is None or up[0] != mat:
                    c = mix(col, 0.35)
                elif dn is None or dn[0] != mat or rt is None or rt[0] != mat:
                    c = mix(col, -0.35)
                elif lf is None or lf[0] != mat:
                    c = mix(col, 0.15)
            im.putpixel((x, y), c)
        # 외곽선
        filled = set(self.m)
        for y in range(16):
            for x in range(16):
                if (x, y) in filled:
                    continue
                if any((x + dx, y + dy) in filled for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    im.putpixel((x, y), OUTLINE)
        return im


# ── 아령 (힘)
def dumbbell():
    c = Canvas()
    iron, dark, grip = hexc('9aa4ae'), hexc('5d6670'), hexc('6b4a2e')
    c.rect(1, 4, 3, 11, 'plateL', dark)
    c.rect(4, 5, 5, 10, 'plateL2', iron)
    c.rect(12, 4, 14, 11, 'plateR', dark)
    c.rect(10, 5, 11, 10, 'plateR2', iron)
    c.rect(6, 7, 9, 8, 'bar', grip)
    c.px(7, 7, 'flat', hexc('8a6440')); c.px(9, 8, 'flat', hexc('4a3220'))
    return c.render()


# ── 바위 들기 세트 (내구)
def rock():
    c = Canvas()
    stone, rope = hexc('8b8b86'), hexc('b08850')
    c.ellipse(8, 10, 6.6, 5.2, 'rock', stone)
    for x, y in ((5, 9), (10, 12), (11, 8), (6, 12)):
        c.px(x, y, 'flat', hexc('6d6d68'))
    c.px(9, 10, 'flat', hexc('a8a8a2'))
    c.line(3, 9, 13, 9, 'rope', rope)
    c.line(8, 5, 8, 14, 'rope2', rope)
    # 손잡이 고리
    for x, y in ((6, 4), (6, 3), (7, 2), (8, 2), (9, 2), (10, 3), (10, 4)):
        c.px(x, y, 'flat', hexc('9a7440'))
    c.px(8, 9, 'flat', hexc('6a4a28'))
    return c.render()


# ── 모래주머니 (민첩)
def sandbag():
    c = Canvas()
    sack, strap = hexc('c8a86a'), hexc('5a3a22')
    c.ellipse(8, 10.5, 6.4, 4.6, 'sack', sack)
    c.rect(5, 6, 11, 7, 'sack', sack)
    c.rect(6, 3, 10, 4, 'neck', hexc('b8985a'))
    c.rect(6, 5, 10, 5, 'tie', hexc('7a5530'))
    c.rect(2, 9, 13, 10, 'strap', strap)
    c.px(13, 9, 'flat', hexc('d8c070')); c.px(13, 10, 'flat', hexc('a89040'))   # 버클
    for x in (4, 7, 10):
        c.px(x, 13, 'flat', hexc('9a7c48'))
    return c.render()


# ── 책 틀 (지능·무기 교본·직업)
BOOK = [
    "................",
    "..OOOOOOOOOOOO..",
    "..ODLLLLLLLLLO..",
    "..ODCGCCCCCGCO..",
    "..ODCCCCCCCCCO..",
    "..ODCCCCCCCCCO..",
    "..ODCCCCCCCCCO..",
    "..ODCCCCCCCCCO..",
    "..ODCCCCCCCCCO..",
    "..ODCCCCCCCCCO..",
    "..ODCCCCCCCCCO..",
    "..ODCGCCCCCGCO..",
    "..ODSSSSSSSSSO..",
    "..OOPPPPPPPPPPO.",
    "...OOOOOOOOOOOO.",
    "................",
]

EMB = {
    'eye': [".......", "..GGG..", ".GWWWG.", "GWWKWWG", ".GWWWG.", "..GGG..", "......."],
    'sword': ["...W...", "...W...", "...W...", "...W...", ".GGGGG.", "...H...", "...H..."],
    'spear': ["...W...", "..WWW..", "...W...", "...H...", "...H...", "...H...", "...H..."],
    'axe': ["..WH...", ".WWH...", "WWWH...", ".WWH...", "..WH...", "...H...", "...H..."],
    'star': ["...G...", "...G...", "GGGGGGG", ".GGGGG.", "..GGG..", ".GG.GG.", "G.....G"],
}


def book(cover, emb, gold='e8c050'):
    C = hexc(cover)
    pal = {'O': OUTLINE, 'D': mix(C, -0.45), 'L': mix(C, 0.3), 'C': C, 'S': mix(C, -0.3),
           'G': hexc(gold), 'P': hexc('ece4cc')}
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, r in enumerate(BOOK):
        for x, ch in enumerate(r):
            if ch in pal:
                im.putpixel((x, y), pal[ch])
    ep = {'G': hexc(gold), 'W': hexc('e8eef2'), 'H': hexc('7a5030'), 'K': hexc('202830')}
    for j, r in enumerate(EMB[emb]):
        for i, ch in enumerate(r):
            if ch in ep:
                im.putpixel((5 + i, 4 + j), ep[ch])
    # 페이지 줄무늬
    for x in (6, 9, 12):
        im.putpixel((x, 13), hexc('cfc5a8'))
    return im


def main():
    os.makedirs(TEX, exist_ok=True)
    os.makedirs(MOD, exist_ok=True)
    imgs = {
        'str': dumbbell(), 'end': rock(), 'agi': sandbag(),
        'int': book('2f5a9a', 'eye'), 'light': book('9a2f2f', 'sword'),
        'medium': book('2f7a4a', 'spear'), 'heavy': book('4a3a30', 'axe'),
        'class': book('6a3a9a', 'star'),
    }
    for k, im in imgs.items():
        im.save(f'{TEX}/tool_{k}.png')
        json.dump({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'bbc:item/tool_{k}'}},
                  open(f'{MOD}/tool_{k}.json', 'w'), indent=1)
    os.makedirs(f'{OUT}/minecraft/models/item', exist_ok=True)
    json.dump({'parent': 'minecraft:item/handheld_rod',
               'textures': {'layer0': 'minecraft:item/warped_fungus_on_a_stick'},
               'overrides': [{'predicate': {'custom_model_data': CMD0 + i}, 'model': f'bbc:item/tool_{k}'}
                             for i, k in enumerate(IDS)]},
              open(f'{OUT}/minecraft/models/item/warped_fungus_on_a_stick.json', 'w'), indent=1)
    # 미리보기 (8배)
    sheet = Image.new('RGBA', (16 * 8 * 8 + 70, 16 * 8 + 20), (60, 70, 68, 255))
    for i, k in enumerate(IDS):
        sheet.paste(imgs[k].resize((128, 128), Image.NEAREST), (10 + i * 136, 10), imgs[k].resize((128, 128), Image.NEAREST))
    os.makedirs('/tmp/pv', exist_ok=True)
    sheet.save('/tmp/pv/tools.png')


if __name__ == '__main__':
    main()
