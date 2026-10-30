"""
코믹스 효과음 글꼴 만들기: 붓글씨 글꼴(Knewave, OFL)을 글자마다 그림으로 그려 마인크래프트 bitmap 글꼴로 만든다.
TTF 글꼴은 한 가지 색만 되지만, 그림 글꼴은 그라데이션 채움 + 흰 테두리 + 검은 바깥 테두리 + 그림자를 그대로 담을 수 있다.
색 조합마다 글꼴 하나 (bbc:sfx_fire, sfx_blood, sfx_ice, sfx_toxic, sfx_gold). 글자 색은 흰색으로 두고 쓴다.
    python tools/make_sfx_font.py
"""
import json, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "city-ability", "src", "main", "resources", "assets", "bbc")
FONT = os.path.join(HERE, "fonts", "knewave.ttf")
CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ!?-~.'"
COLS = 8
CELL = 128           # 글자 칸 (픽셀)
SIZE = 84            # 글꼴 크기
STYLES = {           # 위 → 아래 그라데이션, 안쪽 테두리
    "sfx_fire":  ((255, 240, 90), (255, 70, 20), (255, 255, 255)),
    "sfx_blood": ((255, 120, 150), (190, 0, 40), (255, 255, 255)),
    "sfx_ice":   ((150, 255, 255), (40, 70, 230), (255, 255, 255)),
    "sfx_toxic": ((220, 255, 120), (20, 170, 90), (255, 255, 255)),
    "sfx_gold":  ((255, 255, 210), (230, 150, 0), (60, 20, 0)),
}

def glyph(ch, top, bot, inner):
    font = ImageFont.truetype(FONT, SIZE)
    img = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    x, y = 14, 6
    mask = Image.new("L", (CELL, CELL), 0)
    ImageDraw.Draw(mask).text((x, y), ch, font=font, fill=255)
    def grow(m, r): return m.filter(ImageFilter.MaxFilter(r * 2 + 1)) if r else m
    outer = grow(mask, 7)        # 검은 바깥 테두리
    white = grow(mask, 4)        # 흰(안쪽) 테두리
    shadow = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 150), (5, 6), outer.crop((0, 0, CELL - 5, CELL - 6)))   # 오른쪽 아래 그림자
    img.alpha_composite(shadow)
    img.paste((20, 12, 24, 255), (0, 0), outer)
    img.paste(inner + (255,), (0, 0), white)
    # 세로 그라데이션 채움 + 위쪽 하이라이트 줄
    bb = mask.getbbox() or (0, 0, CELL, CELL)
    grad = Image.new("RGBA", (CELL, CELL))
    for yy in range(CELL):
        f = min(1, max(0, (yy - bb[1]) / max(1, bb[3] - bb[1])))
        f = f ** 0.8
        c = tuple(int(top[i] + (bot[i] - top[i]) * f) for i in range(3))
        if bb[1] + (bb[3] - bb[1]) * 0.18 < yy < bb[1] + (bb[3] - bb[1]) * 0.27:
            c = tuple(min(255, int(v + (255 - v) * 0.55)) for v in c)
        ImageDraw.Draw(grad).line([(0, yy), (CELL, yy)], fill=c + (255,))
    img.paste(grad, (0, 0), mask)
    return img

def build():
    os.makedirs(os.path.join(OUT, "textures", "font"), exist_ok=True)
    rows = [CHARS[i:i + COLS] for i in range(0, len(CHARS), COLS)]
    rows[-1] = rows[-1].ljust(COLS, "\u0000")
    for name, (top, bot, inner) in STYLES.items():
        sheet = Image.new("RGBA", (CELL * COLS, CELL * len(rows)), (0, 0, 0, 0))
        for r, row in enumerate(rows):
            for c, ch in enumerate(row):
                if ch != "\u0000": sheet.alpha_composite(glyph(ch, top, bot, inner), (c * CELL, r * CELL))
        sheet.save(os.path.join(OUT, "textures", "font", name + ".png"))
        font = {"providers": [
            {"type": "space", "advances": {" ": 4}},
            {"type": "bitmap", "file": "bbc:font/" + name + ".png", "height": 14, "ascent": 11, "chars": rows},
        ]}
        with open(os.path.join(OUT, "font", name + ".json"), "w") as f: json.dump(font, f, indent=1)
    print("sfx fonts:", list(STYLES))

def preview(words, path):
    """게임처럼 글자를 이어 붙여 미리보기"""
    imgs = []
    for (w, st) in words:
        top, bot, inner = STYLES[st]
        gl = []
        for ch in w:
            if ch == " ": gl.append(Image.new("RGBA", (40, CELL))); continue
            g = glyph(ch, top, bot, inner); bb = g.getbbox(); gl.append(g.crop((0, 0, bb[2] + 2, CELL)))
        W = sum(g.width for g in gl) - 14 * (len(gl) - 1)
        im = Image.new("RGBA", (W + 20, CELL), (60, 70, 90, 255)); x = 0
        for g in gl: im.alpha_composite(g, (x, 0)); x += g.width - 14
        imgs.append(im)
    H = sum(i.height for i in imgs); Wm = max(i.width for i in imgs)
    out = Image.new("RGBA", (Wm, H), (60, 70, 90, 255)); y = 0
    for i in imgs: out.alpha_composite(i, (0, y)); y += i.height
    out.save(path)

if __name__ == "__main__":
    build()
