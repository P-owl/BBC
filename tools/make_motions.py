"""
도시 능력자 모션 생성기 — playerAnimator(Emotecraft JSON 형식) 파일을 만든다.
    python tools/make_motions.py
결과: city-ability/src/main/resources/assets/bbc/player_animation/<이름>.json
각도는 도(degree). 팔 pitch 음수 = 앞으로 올림 (-90 = 앞으로 쭉, -170 = 머리 위).
나중에 Blockbench로 만든 모션으로 하나씩 바꾸면 된다 (같은 이름으로 덮어쓰기).
"""
import json, os

OUT = os.path.join(os.path.dirname(__file__), "..", "city-ability", "src", "main", "resources",
                   "assets", "bbc", "player_animation")
OQ, IQ, IO, LIN = "EASEOUTQUAD", "EASEINQUAD", "EASEINOUTSINE", "LINEAR"

def k(t, part, ease=OQ, **ax):
    return {"tick": t, "easing": ease, "turn": 0, part: ax}

def mirror(keys):
    swap = {"rightArm": "leftArm", "leftArm": "rightArm", "rightLeg": "leftLeg", "leftLeg": "rightLeg"}
    out = []
    for m in keys:
        m2 = {"tick": m["tick"], "easing": m["easing"], "turn": 0}
        for p, ax in m.items():
            if p in ("tick", "easing", "turn"): continue
            ax2 = dict(ax)
            for a in ("yaw", "roll"):
                if a in ax2: ax2[a] = -ax2[a]
            m2[swap.get(p, p)] = ax2
        out.append(m2)
    return out

A = {}

def anim(name, end, keys, stop=None, loop=False, ret=0):
    A[name] = {"name": name, "author": "bbc", "description": "",
               "emote": {"isLoop": loop, "returnTick": ret, "beginTick": 0, "endTick": end,
                         "stopTick": stop if stop is not None else end + 3, "degrees": True,
                         "nsfw": False, "moves": keys}}

# ── 평타 (7틱: 선딜 3 → 판정 → 후딜 3) ──
jab_r = [
    k(0, "rightArm", pitch=-30, yaw=10), k(0, "torso", yaw=12),
    k(2, "rightArm", IQ, pitch=-60, yaw=20, roll=10),
    k(3, "rightArm", OQ, pitch=-100, yaw=-15), k(3, "torso", OQ, yaw=-18),
    k(3, "leftArm", OQ, pitch=-35, yaw=25),
    k(7, "rightArm", IO, pitch=-70, yaw=-5), k(7, "torso", IO, yaw=-6),
]
anim("m1_1", 7, jab_r)
anim("m1_2", 7, mirror(jab_r))
anim("m1_3", 7, [  # 오른손 가로 베기
    k(0, "rightArm", pitch=-80, yaw=55, roll=-20), k(0, "torso", yaw=25),
    k(3, "rightArm", OQ, pitch=-90, yaw=-45, roll=10), k(3, "torso", OQ, yaw=-28),
    k(3, "leftArm", OQ, pitch=-30),
    k(7, "rightArm", IO, pitch=-60, yaw=-30), k(7, "torso", IO, yaw=-10),
])
anim("m1_4", 7, [  # 왼손 올려 치기
    k(0, "leftArm", pitch=-20, yaw=-10), k(0, "torso", pitch=8),
    k(3, "leftArm", OQ, pitch=-140, yaw=10), k(3, "torso", OQ, pitch=-8, yaw=10),
    k(3, "rightArm", OQ, pitch=-40),
    k(7, "leftArm", IO, pitch=-100), k(7, "torso", IO, pitch=0),
])
anim("m1_5", 18, [  # 두 손 큰 일격
    k(0, "rightArm", pitch=-60), k(0, "leftArm", pitch=-60),
    k(4, "rightArm", IQ, pitch=-165, yaw=-10), k(4, "leftArm", IQ, pitch=-165, yaw=10),
    k(4, "torso", IQ, pitch=-12),
    k(6, "rightArm", OQ, pitch=-55, yaw=-15), k(6, "leftArm", OQ, pitch=-55, yaw=15),
    k(6, "torso", OQ, pitch=22), k(6, "rightLeg", OQ, pitch=-25), k(6, "leftLeg", OQ, pitch=18),
    k(14, "rightArm", IO, pitch=-50), k(14, "leftArm", IO, pitch=-50), k(14, "torso", IO, pitch=15),
])
anim("launch", 10, [  # 올려치기
    k(0, "rightArm", pitch=-10, yaw=15), k(0, "torso", pitch=12), k(0, "rightLeg", pitch=-20), k(0, "leftLeg", pitch=15),
    k(3, "rightArm", IQ, pitch=-25, roll=15),
    k(5, "rightArm", OQ, pitch=-175, yaw=-5), k(5, "torso", OQ, pitch=-15),
    k(5, "leftArm", OQ, pitch=10, roll=-20),
    k(5, "rightLeg", OQ, pitch=10), k(5, "leftLeg", OQ, pitch=-30),
    k(10, "rightArm", IO, pitch=-150),
])
tuck = [k(0, "rightLeg", pitch=-45), k(0, "leftLeg", pitch=-20)]
anim("air_1", 7, tuck + jab_r)
anim("air_2", 7, tuck + mirror(jab_r))
anim("air_3", 7, tuck + [
    k(0, "rightArm", pitch=-100, yaw=50), k(0, "leftArm", pitch=-100, yaw=-50), k(0, "torso", yaw=20),
    k(3, "rightArm", OQ, pitch=-95, yaw=-35), k(3, "leftArm", OQ, pitch=-95, yaw=35), k(3, "torso", OQ, yaw=-25),
])
anim("slam", 11, [  # 내려찍기
    k(0, "rightArm", pitch=-120), k(0, "leftArm", pitch=-120),
    k(3, "rightArm", IQ, pitch=-175), k(3, "leftArm", IQ, pitch=-175), k(3, "torso", IQ, pitch=-15),
    k(3, "rightLeg", IQ, pitch=-50), k(3, "leftLeg", IQ, pitch=-50),
    k(5, "rightArm", OQ, pitch=-35), k(5, "leftArm", OQ, pitch=-35), k(5, "torso", OQ, pitch=30),
    k(5, "rightLeg", OQ, pitch=-20), k(5, "leftLeg", OQ, pitch=-10),
    k(11, "torso", IO, pitch=10),
])

# ── 방어·이동 ──
anim("guard", 4, [  # 팔 교차 (누르고 있는 동안 반복)
    k(0, "rightArm", OQ, pitch=-95, yaw=38, roll=5), k(0, "leftArm", OQ, pitch=-100, yaw=-38, roll=-5),
    k(0, "torso", OQ, pitch=10), k(0, "rightLeg", OQ, pitch=-15), k(0, "leftLeg", OQ, pitch=10),
    k(4, "rightArm", LIN, pitch=-95, yaw=38, roll=5), k(4, "leftArm", LIN, pitch=-100, yaw=-38, roll=-5),
    k(4, "torso", LIN, pitch=10), k(4, "rightLeg", LIN, pitch=-15), k(4, "leftLeg", LIN, pitch=10),
], stop=4, loop=True, ret=1)
anim("parry", 9, [
    k(0, "rightArm", pitch=-100, yaw=40), k(2, "rightArm", OQ, pitch=-130, yaw=-40, roll=20),
    k(2, "torso", OQ, yaw=-20), k(9, "rightArm", IO, pitch=-80),
])
anim("dash_f", 7, [
    k(1, "torso", OQ, pitch=28), k(1, "rightArm", OQ, pitch=55, roll=10), k(1, "leftArm", OQ, pitch=55, roll=-10),
    k(1, "rightLeg", OQ, pitch=-35), k(1, "leftLeg", OQ, pitch=30),
    k(6, "torso", IO, pitch=15),
])
anim("dash_b", 7, [
    k(1, "torso", OQ, pitch=-15), k(1, "rightArm", OQ, pitch=-40), k(1, "leftArm", OQ, pitch=-40),
    k(1, "rightLeg", OQ, pitch=25), k(1, "leftLeg", OQ, pitch=-10),
])
anim("dash_l", 7, [k(1, "body", OQ, roll=-14), k(1, "rightArm", OQ, roll=40), k(1, "leftArm", OQ, roll=-15)])
anim("dash_r", 7, [k(1, "body", OQ, roll=14), k(1, "leftArm", OQ, roll=-40), k(1, "rightArm", OQ, roll=15)])
anim("evade", 9, [  # 뒤로 공중제비
    k(0, "body", LIN, pitch=0), k(8, "body", LIN, pitch=-360),
    k(2, "rightLeg", OQ, pitch=-70), k(2, "leftLeg", OQ, pitch=-70),
    k(2, "rightArm", OQ, pitch=-60), k(2, "leftArm", OQ, pitch=-60),
], stop=9)

# ── 맞을 때 ──
anim("hit", 10, [
    k(1, "torso", OQ, pitch=-16, yaw=8), k(1, "rightArm", OQ, pitch=25, roll=20), k(1, "leftArm", OQ, pitch=20, roll=-20),
    k(1, "head", OQ, pitch=-15),
    k(10, "torso", IO, pitch=-4),
])
anim("hit_air", 14, [
    k(1, "torso", OQ, pitch=-25), k(1, "rightArm", OQ, pitch=-30, roll=35), k(1, "leftArm", OQ, pitch=-30, roll=-35),
    k(1, "rightLeg", OQ, pitch=25), k(1, "leftLeg", OQ, pitch=-15), k(1, "head", OQ, pitch=-20),
])
anim("knock", 30, [  # 날아가서 쓰러짐 → 누워 있음
    k(0, "body", pitch=0),
    k(6, "body", OQ, pitch=-80), k(6, "rightArm", OQ, pitch=-150, roll=20), k(6, "leftArm", OQ, pitch=-150, roll=-20),
    k(6, "rightLeg", OQ, pitch=-10), k(6, "leftLeg", OQ, pitch=10),
    k(26, "body", LIN, pitch=-80),
], stop=34)
anim("getup", 8, [
    k(0, "body", pitch=-60), k(0, "rightLeg", pitch=-60), k(0, "leftLeg", pitch=-30),
    k(6, "body", OQ, pitch=0), k(6, "rightLeg", OQ, pitch=0), k(6, "leftLeg", OQ, pitch=0),
])

# ── 기술 ──
anim("dash_attack", 9, [
    k(0, "rightArm", pitch=-40, yaw=30), k(0, "torso", pitch=20, yaw=15),
    k(3, "rightArm", OQ, pitch=-95, yaw=-40), k(3, "torso", OQ, pitch=15, yaw=-25),
    k(3, "leftArm", OQ, pitch=40), k(3, "rightLeg", OQ, pitch=-35), k(3, "leftLeg", OQ, pitch=30),
])
anim("spin", 8, [
    k(0, "body", LIN, yaw=0), k(6, "body", LIN, yaw=360),
    k(1, "rightArm", OQ, pitch=-90, roll=80), k(1, "leftArm", OQ, pitch=-90, roll=-80),
], stop=8)
anim("jump_air", 8, [
    k(1, "rightArm", OQ, pitch=-160, roll=20), k(1, "leftArm", OQ, pitch=-160, roll=-20),
    k(1, "rightLeg", OQ, pitch=-60), k(1, "leftLeg", OQ, pitch=-20),
])
anim("heal", 20, [
    k(3, "torso", OQ, pitch=12), k(3, "rightArm", OQ, pitch=-20, roll=10), k(3, "leftArm", OQ, pitch=-20, roll=-10),
    k(3, "head", OQ, pitch=20), k(17, "torso", IO, pitch=10),
])
anim("focus", 12, [
    k(2, "rightArm", OQ, pitch=-70, yaw=45), k(2, "leftArm", OQ, pitch=-70, yaw=-45), k(2, "torso", OQ, pitch=8),
    k(10, "rightArm", IO, pitch=-70, yaw=45), k(10, "leftArm", IO, pitch=-70, yaw=-45),
])

os.makedirs(OUT, exist_ok=True)
for name, data in A.items():
    with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
print(len(A), "motions ->", os.path.normpath(OUT))
