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

# ── 평타 (선딜 4 → 판정 → 후딜 2, 다음 평타까지 7틱) ──
# 에픽파이트식으로: 몸 전체를 씀 (발 내딛기 + 허리 비틀기), 예비 동작 → 1틱 만에 확 뻗기(OUTEXPO) → 잠깐 멈춤 → 천천히 회수
EX, BK = "EASEOUTEXPO", "EASEOUTBACK"

def P(t, ease, **parts):
    """한 틱에 여러 부위 자세. 값은 (pitch, yaw, roll) 튜플"""
    out = []
    for part, v in parts.items():
        ax = {}
        for name, val in zip(("pitch", "yaw", "roll"), v):
            if val is not None: ax[name] = val
        out.append(k(t, part, ease, **ax))
    return out

STANCE = dict(rightLeg=(14, 0, 3), leftLeg=(-18, 0, -3))          # 왼발 앞, 오른발 뒤
LUNGE = dict(rightLeg=(26, 0, 4), leftLeg=(-34, 0, -4))           # 크게 내딛음

jab_r = (  # 오른손 스트레이트
    P(0, OQ, rightArm=(-45, 28, 8), leftArm=(-75, -22, 0), torso=(6, 24, 0), head=(0, -18, 0), **STANCE)
    + P(3, IQ, rightArm=(-38, 42, 14), torso=(4, 32, 0))
    + P(4, EX, rightArm=(-96, -12, 0), leftArm=(-38, 32, -8), torso=(12, -28, 0), head=(0, 22, 0), **LUNGE)
    + P(6, LIN, rightArm=(-93, -10, 0), torso=(11, -26, 0))
    + P(9, IO, rightArm=(-60, 10, 0), leftArm=(-68, -18, 0), torso=(6, -4, 0), head=(0, 0, 0), **STANCE)
)
anim("m1_1", 9, jab_r, stop=11)
anim("m1_2", 9, mirror(jab_r), stop=11)
anim("m1_3", 9,  # 오른손 등주먹 가로 휘두르기
    P(0, OQ, rightArm=(-70, 62, -25), leftArm=(-55, -10, 0), torso=(4, 38, 0), head=(0, -25, 0), rightLeg=(-10, 0, 0), leftLeg=(12, 0, 0))
    + P(3, IQ, rightArm=(-78, 75, -30), torso=(4, 45, 0))
    + P(4, EX, rightArm=(-88, -72, 18), leftArm=(-30, 25, -15), torso=(8, -42, 0), head=(0, 28, 0), rightLeg=(-32, 0, 0), leftLeg=(24, 0, 0))
    + P(6, LIN, rightArm=(-85, -68, 15), torso=(8, -40, 0))
    + P(9, IO, rightArm=(-55, -20, 0), leftArm=(-60, -10, 0), torso=(4, -8, 0), head=(0, 0, 0), **STANCE),
    stop=11)
anim("m1_4", 9,  # 왼손 어퍼컷 (몸을 낮췄다가 솟구침)
    P(0, OQ, leftArm=(15, -10, -20), rightArm=(-70, 20, 0), torso=(22, -18, 0), head=(-15, 0, 0), rightLeg=(-28, 0, 0), leftLeg=(22, 0, 0))
    + P(3, IQ, leftArm=(25, -15, -25), torso=(28, -22, 0))
    + P(4, EX, leftArm=(-158, 12, 8), rightArm=(-30, 25, 10), torso=(-14, 18, 0), head=(-22, 0, 0), rightLeg=(8, 0, 0), leftLeg=(-14, 0, 0))
    + P(6, LIN, leftArm=(-152, 10, 8), torso=(-12, 16, 0))
    + P(9, IO, leftArm=(-80, 0, 0), rightArm=(-65, 15, 0), torso=(4, 0, 0), head=(0, 0, 0), **STANCE),
    stop=11)
anim("m1_5", 18,  # 넉백: 크게 젖혔다가 두 손바닥으로 내지르기 (선딜 5)
    P(0, OQ, rightArm=(25, 20, 15), leftArm=(25, -20, -15), torso=(-10, 30, 0), head=(0, -20, 0), rightLeg=(-15, 0, 0), leftLeg=(15, 0, 0))
    + P(4, IQ, rightArm=(40, 25, 20), leftArm=(40, -25, -20), torso=(-14, 38, 0))
    + P(5, EX, rightArm=(-92, -8, 0), leftArm=(-92, 8, 0), torso=(24, -12, 0), head=(-8, 10, 0), rightLeg=(32, 0, 0), leftLeg=(-48, 0, 0))
    + P(10, LIN, rightArm=(-88, -6, 0), leftArm=(-88, 6, 0), torso=(22, -10, 0))
    + P(18, IO, rightArm=(-40, 0, 0), leftArm=(-40, 0, 0), torso=(5, 0, 0), head=(0, 0, 0), **STANCE),
    stop=21)
anim("launch", 12,  # 올려치기 (뜨기·업 플링·래더). 쪼그렸다가 온몸으로 솟구치는 어퍼
    P(0, OQ, rightArm=(20, 25, 20), leftArm=(-60, -20, 0), torso=(26, 20, 0), head=(-20, 0, 0), rightLeg=(-35, 0, 0), leftLeg=(25, 0, 0))
    + P(3, IQ, rightArm=(30, 30, 25), torso=(30, 25, 0))
    + P(4, EX, rightArm=(-178, -8, -5), leftArm=(20, 0, -35), torso=(-20, -15, 0), head=(-30, 0, 0), rightLeg=(15, 0, 0), leftLeg=(-55, 0, 0))
    + P(7, LIN, rightArm=(-172, -6, -5), torso=(-18, -12, 0))
    + P(12, IO, rightArm=(-120, 0, 0), leftArm=(-20, 0, -15), torso=(-5, 0, 0), head=(-10, 0, 0), rightLeg=(0, 0, 0), leftLeg=(-20, 0, 0)),
    stop=14)
TUCK = dict(rightLeg=(-50, 0, 5), leftLeg=(-22, 0, -5))
air_jab = (
    P(0, OQ, rightArm=(-45, 28, 8), leftArm=(-75, -22, 0), torso=(-6, 24, 0), **TUCK)
    + P(3, IQ, rightArm=(-35, 42, 14), torso=(-6, 32, 0))
    + P(4, EX, rightArm=(-100, -12, 0), leftArm=(-30, 30, -20), torso=(10, -28, 0), rightLeg=(-15, 0, 5), leftLeg=(-60, 0, -5))
    + P(9, IO, rightArm=(-70, 0, 0), torso=(0, -5, 0), **TUCK)
)
anim("air_1", 9, air_jab, stop=11)
anim("air_2", 9, mirror(air_jab), stop=11)
anim("air_3", 9,  # 공중 앞차기
    P(0, OQ, rightLeg=(-20, 0, 0), leftLeg=(-60, 0, 0), torso=(-12, 0, 0), rightArm=(-30, 0, 40), leftArm=(-30, 0, -40))
    + P(3, IQ, rightLeg=(20, 0, 0), torso=(-18, 0, 0))
    + P(4, EX, rightLeg=(-105, 0, 0), leftLeg=(20, 0, 0), torso=(-26, 0, 0), rightArm=(10, 0, 70), leftArm=(10, 0, -70))
    + P(9, IO, rightLeg=(-40, 0, 0), leftLeg=(-30, 0, 0), torso=(-5, 0, 0), rightArm=(-30, 0, 20), leftArm=(-30, 0, -20)),
    stop=11)
anim("slam", 14,  # 다운 슬램: 몸을 웅크려 두 손을 머리 위로 → 온몸으로 내리찍기
    P(0, OQ, rightArm=(-175, -10, 10), leftArm=(-175, 10, -10), torso=(-22, 0, 0), head=(-15, 0, 0), rightLeg=(-60, 0, 0), leftLeg=(-60, 0, 0))
    + P(3, IQ, rightArm=(-195, -12, 12), leftArm=(-195, 12, -12), torso=(-30, 0, 0))
    + P(4, EX, rightArm=(-25, -8, 0), leftArm=(-25, 8, 0), torso=(42, 0, 0), head=(25, 0, 0), rightLeg=(-25, 0, 0), leftLeg=(-35, 0, 0), body=(18, None, None))
    + P(9, LIN, rightArm=(-22, -8, 0), leftArm=(-22, 8, 0), torso=(40, 0, 0), body=(16, None, None))
    + P(14, IO, rightArm=(-40, 0, 0), leftArm=(-40, 0, 0), torso=(8, 0, 0), head=(0, 0, 0), rightLeg=(-10, 0, 0), leftLeg=(-5, 0, 0), body=(0, None, None)),
    stop=16)

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

# ── 맞을 때 ── (1틱 만에 확 젖혀졌다가 천천히 돌아옴. 오른쪽·왼쪽을 번갈아)
hit_r = (
    P(1, EX, head=(-24, 14, 0), torso=(-18, 14, 6), rightArm=(28, 0, 28), leftArm=(18, 0, -16), rightLeg=(14, 0, 0), leftLeg=(-8, 0, 0))
    + P(3, LIN, head=(-20, 12, 0), torso=(-15, 12, 5))
    + P(10, IO, head=(-4, 0, 0), torso=(-4, 0, 0), rightArm=(0, 0, 4), leftArm=(0, 0, -4), rightLeg=(0, 0, 0), leftLeg=(0, 0, 0))
)
anim("hit", 10, hit_r, stop=12)
anim("hit_2", 10, mirror(hit_r), stop=12)
anim("hit_air", 14,
    P(1, EX, head=(-28, 0, 0), torso=(-26, 0, 0), rightArm=(-35, 0, 55), leftArm=(-35, 0, -55), rightLeg=(30, 0, 8), leftLeg=(-18, 0, -8))
    + P(14, LIN, head=(-22, 0, 0), torso=(-20, 0, 0), rightArm=(-30, 0, 48), leftArm=(-30, 0, -48), rightLeg=(25, 0, 8), leftLeg=(-15, 0, -8)),
    stop=16)
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
