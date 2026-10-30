"""
일본 만화식 효과음(描き文字) 만들기.
만화 효과음은 글자 하나하나가 아니라 '단어 전체를 한 장의 그림'으로 그린다: 첫 글자가 크고, 글자마다 높이·크기가 들쭉날쭉,
비스듬히 기울고, 잉크 가장자리가 거칠다. 그래서 단어마다 그림 한 장을 만들고, 마인크래프트 bitmap 글꼴의
사적 영역 글자(U+E000~) 하나에 단어 하나를 붙인다. 게임에선 그 글자 하나를 텍스트 디스플레이로 띄우면 단어 그림이 나온다.

  ink   = 검은 먹 + 흰 테두리 + 얇은 검은 바깥선 (타격·폭발)
  white = 흰 글자 + 검은 테두리 (베기·쇳소리)
글꼴: Dela Gothic One (OFL). 순서를 바꾸면 Fx.java 의 Sfx 순서도 같이 바꿔야 함.
    python tools/make_sfx_font.py
"""
import json, os, random
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "city-ability", "src", "main", "resources", "assets", "bbc")
JP = os.path.join(HERE, "fonts", "dela-gothic-one-jp.ttf")
LATIN = os.path.join(HERE, "fonts", "dela-gothic-one-latin.ttf")

# (이름, 글자, 모양, 첫 글자 배율)  ← Fx.Sfx 와 같은 순서
WORDS = [
    ("DON", "ドン!", "ink", 1.35),
    ("BAKI", "バキッ!", "ink", 1.3),
    ("DOGO", "ドゴォ!", "ink", 1.3),
    ("GA", "ガッ!", "ink", 1.35),
    ("ZUDON", "ズドォン!!", "ink", 1.35),
    ("DOGON", "ドゴォン!!", "ink", 1.3),
    ("ZAN", "ザンッ!", "white", 1.35),
    ("ZUBA", "ズバァッ!", "white", 1.3),
    ("GAGIN", "ガギィン!!", "white", 1.25),
    ("BAKIN", "バキィン!!", "ink", 1.25),
    ("GOGO", "ゴゴゴゴ", "ink", 1.0),
    ("DODODO", "ドドドドッ!!", "ink", 1.2),
    ("BUON", "ブォン!", "white", 1.3),
    ("DOGOOON", "ドゴォォォン!!!", "ink", 1.35),
]
SIZE = 110
SMALL = set("ァィゥェォッャュョ")


def font_for(ch, size):
    f = ImageFont.truetype(JP, size)
    try:
        if f.getmask(ch).getbbox(): return f
    except Exception:
        pass
    return ImageFont.truetype(LATIN, size)


def grow(m, r):
    return m.filter(ImageFilter.MaxFilter(r * 2 + 1)) if r > 0 else m


def rough(m, seed, amount=1.4):
    """잉크 가장자리를 거칠게: 흐린 뒤 잡음을 더해 다시 문턱값"""
    rnd = random.Random(seed)
    noise = Image.effect_noise(m.size, 70).filter(ImageFilter.GaussianBlur(2.2))
    b = m.filter(ImageFilter.GaussianBlur(amount))
    out = ImageChops.add(b, noise, scale=1.0, offset=-128 + rnd.randint(-8, 8))
    return out.point(lambda v: 255 if v > 128 else 0)


def render(text, style, first, seed):
    rnd = random.Random(seed)
    W, H = int(SIZE * 1.15 * len(text)) + 200, int(SIZE * 2.6)
    m = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(m)
    x = 50
    base = int(SIZE * 0.7)
    for i, ch in enumerate(text):
        k = first if i == 0 else rnd.uniform(0.92, 1.05)
        if ch in SMALL: k *= 0.9
        if ch == "!": k *= 1.05
        sz = int(SIZE * k)
        f = font_for(ch, sz)
        y = base + (SIZE - sz) + rnd.randint(-int(SIZE * 0.08), int(SIZE * 0.08)) - (int(SIZE * 0.12) if i == 0 else 0)
        d.text((x, y), ch, font=f, fill=255)
        bb = f.getbbox(ch)
        x += int((bb[2] - bb[0]) * (0.86 if ch != "!" else 0.95)) + 4
    # 기울임 (오른쪽 위로 치솟는 느낌) + 살짝 회전
    m = m.transform(m.size, Image.AFFINE, (1, 0.28, -SIZE * 0.45, 0, 1, 0), resample=Image.BICUBIC)
    m = m.rotate(rnd.uniform(4, 8), resample=Image.BICUBIC, expand=False)
    m = rough(m, seed)
    out = Image.new("RGBA", m.size, (0, 0, 0, 0))
    # 뒤로 끌리는 먹 번짐 (속도감): 왼쪽 아래로 밀린 회색 잔상 몇 장
    for k, a in ((3, 70), (2, 110), (1, 150)):
        sm = grow(m, 9).transform(m.size, Image.AFFINE, (1, 0, 9 * k, 0, 1, -6 * k))
        out.paste((40, 40, 45, a) if style == "ink" else (255, 255, 255, a), (0, 0), sm)
    if style == "ink":
        out.paste((0, 0, 0, 255), (0, 0), rough(grow(m, 12), seed + 1, 1.2))
        out.paste((255, 255, 255, 255), (0, 0), grow(m, 9))
        out.paste((8, 8, 8, 255), (0, 0), m)
    else:
        out.paste((255, 255, 255, 255), (0, 0), rough(grow(m, 13), seed + 1, 1.2))
        out.paste((0, 0, 0, 255), (0, 0), grow(m, 9))
        out.paste((255, 255, 255, 255), (0, 0), m)
        # 흰 글자 안에 가는 회색 결 (먹 결)
        lines = Image.new("L", m.size, 0)
        ld = ImageDraw.Draw(lines)
        for yy in range(0, m.size[1], 9): ld.line([(0, yy + 6), (m.size[0], yy - 30)], fill=255, width=2)
        out.paste((200, 200, 205, 255), (0, 0), ImageChops.multiply(lines, m))
    bb = out.getbbox()
    out = out.crop((bb[0] - 4, bb[1] - 4, bb[2] + 4, bb[3] + 4))
    return out


def build():
    os.makedirs(os.path.join(OUT, "textures", "font", "sfx"), exist_ok=True)
    providers = [{"type": "space", "advances": {" ": 4}}]
    for i, (name, text, style, first) in enumerate(WORDS):
        img = render(text, style, first, 100 + i)
        # bitmap 글꼴은 높이를 같은 비율로 줄이므로 모든 그림 높이를 맞춤 (가로는 비율대로)
        h = 160
        img = img.resize((max(1, int(img.width * h / img.height)), h), Image.LANCZOS)
        fn = name.lower() + ".png"
        img.save(os.path.join(OUT, "textures", "font", "sfx", fn))
        providers.append({"type": "bitmap", "file": "bbc:font/sfx/" + fn, "height": 18, "ascent": 15,
                          "chars": [chr(0xE000 + i)]})
    with open(os.path.join(OUT, "font", "sfx_jp.json"), "w") as f:
        json.dump({"providers": providers}, f, indent=1)
    print(len(WORDS), "sfx ->", [w[0] for w in WORDS])


def preview(path):
    ims = [render(t, s, k, 100 + i) for i, (_, t, s, k) in enumerate(WORDS)]
    cols = 2
    cw = max(i.width for i in ims) + 20
    rh = max(i.height for i in ims) + 10
    out = Image.new("RGBA", (cw * cols, rh * ((len(ims) + 1) // cols)), (150, 150, 150, 255))
    for j, im in enumerate(ims): out.alpha_composite(im, ((j % cols) * cw + 10, (j // cols) * rh + 5))
    out.save(path)


if __name__ == "__main__":
    build()
