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
# 에픽파이트 맨손 공격(fist_auto1~3)을 관절 각도로 재 보고 따른 원칙 (그쪽 파일을 옮긴 게 아니라 원칙만 따라 새로 만듦):
#   - 몸 전체를 옆으로 약 25° 돌린 싸움 자세를 계속 유지하고, 머리는 그만큼 반대로 돌려 정면을 봄
#   - 예비 동작은 거의 없음. 판정 틱에 1틱 만에 팔을 쭉 뻗고(약 100°), 그대로 잠깐 멈췄다가 천천히 자세로 돌아옴
#   - 허리·가슴은 15° 안쪽으로만 움직임. 크게 움직이는 건 팔 하나와 몸 전체 회전뿐
EX, BK, CONST = "EASEOUTEXPO", "EASEOUTBACK", "CONSTANT"

def P(t, ease, **parts):
    """한 틱에 여러 부위 자세. 값은 (pitch, yaw, roll) 튜플, None 은 건드리지 않음"""
    out = []
    for part, v in parts.items():
        ax = {}
        for name, val in zip(("pitch", "yaw", "roll"), v):
            if val is not None: ax[name] = val
        out.append(k(t, part, ease, **ax))
    return out

# 싸움 자세: 왼발·왼어깨가 앞 (몸 +22°), 머리는 정면, 두 주먹은 턱 앞
STANCE = dict(body=(0, 22, 0), head=(0, -22, 0), torso=(4, 0, 0),
              rightArm=(-62, -12, 0), leftArm=(-72, 14, 0), rightLeg=(10, 0, 0), leftLeg=(-12, 0, 0))

def strike(t_hit, end, prep, hit, hold=2):
    """자세 → (t_hit-1) 살짝 모음 → t_hit 1틱 만에 뻗음 → hold 틱 유지 → end 에 자세로"""
    return (P(0, OQ, **STANCE) + P(t_hit - 1, IQ, **prep) + P(t_hit, EX, **hit)
            + P(t_hit + hold, LIN, **hit) + P(end, IO, **STANCE))

anim("m1_1", 10, strike(4, 10,  # 왼손 잽
    dict(leftArm=(-66, 12, 0)),
    dict(leftArm=(-98, 4, 0), body=(0, 26, 0), head=(0, -26, 0), torso=(7, 0, 0))), stop=12)
anim("m1_2", 10, strike(4, 10,  # 오른손 스트레이트: 몸이 반대로 돌아감
    dict(rightArm=(-58, -16, 0), body=(0, 24, 0)),
    dict(rightArm=(-100, 4, 0), leftArm=(-58, 22, 0), body=(0, -12, 0), head=(0, 12, 0), torso=(8, 0, 0),
         rightLeg=(4, 0, 0), leftLeg=(-16, 0, 0))), stop=12)
anim("m1_3", 10, strike(4, 10,  # 왼손 훅: 팔이 옆에서 안으로
    dict(leftArm=(-80, 32, 0), body=(0, 28, 0)),
    dict(leftArm=(-88, -34, 0), body=(0, 2, 0), head=(0, -2, 0), torso=(6, 0, 0))), stop=12)
anim("m1_4", 10, strike(4, 10,  # 오른손 어퍼: 살짝 낮췄다가 올려침
    dict(rightArm=(-28, -6, 0), torso=(14, 0, 0), rightLeg=(18, 0, 0), leftLeg=(-24, 0, 0)),
    dict(rightArm=(-138, 0, 0), torso=(-8, 0, 0), body=(0, -10, 0), head=(0, 10, 0),
         rightLeg=(6, 0, 0), leftLeg=(-14, 0, 0))), stop=12)
anim("m1_5", 18, strike(5, 18,  # 넉백: 한 발 내딛는 오른손 정권 (선딜 5, 오래 멈춤)
    dict(rightArm=(-50, -22, 0), body=(0, 34, 0), torso=(2, 0, 0)),
    dict(rightArm=(-100, 0, 0), leftArm=(-50, 24, 0), body=(0, -24, 0), head=(0, 24, 0), torso=(12, 0, 0),
         rightLeg=(24, 0, 0), leftLeg=(-30, 0, 0)), hold=5), stop=21)
anim("launch", 12, strike(4, 12,  # 올려치기 (뜨기·업 플링·래더)
    dict(rightArm=(-22, 0, 0), torso=(14, 0, 0), rightLeg=(20, 0, 0), leftLeg=(-26, 0, 0)),
    dict(rightArm=(-165, 0, 0), leftArm=(-40, 18, 0), torso=(-12, 0, 0), body=(0, -10, 0), head=(-12, 10, 0),
         rightLeg=(6, 0, 0), leftLeg=(-16, 0, 0)), hold=3), stop=14)

AIR = dict(STANCE, rightLeg=(-38, 0, 0), leftLeg=(-14, 0, 0))
def air_strike(prep, hit):
    return (P(0, OQ, **AIR) + P(3, IQ, **prep) + P(4, EX, **hit) + P(6, LIN, **hit) + P(10, IO, **AIR))
anim("air_1", 10, air_strike(dict(leftArm=(-66, 12, 0)),
    dict(leftArm=(-100, 4, 0), body=(0, 26, 0), head=(0, -26, 0))), stop=12)
anim("air_2", 10, air_strike(dict(rightArm=(-58, -16, 0)),
    dict(rightArm=(-102, 4, 0), leftArm=(-58, 22, 0), body=(0, -12, 0), head=(0, 12, 0))), stop=12)
anim("air_3", 10, air_strike(dict(rightLeg=(-10, 0, 0), torso=(-6, 0, 0)),  # 공중 앞차기
    dict(rightLeg=(-88, 0, 0), leftLeg=(-8, 0, 0), torso=(-10, 0, 0), body=(0, 0, 0), head=(0, 0, 0),
         rightArm=(-30, 0, 30), leftArm=(-30, 0, -30))), stop=12)

# 다운 슬램: 공중에서 앞으로 한 바퀴 구르며 내리찍기 (멀리서도 보이게 몸 전체가 돈다)
# 에픽파이트 내리찍기처럼 머리 위로 든 채 잠깐 멈췄다가 1~2틱 만에 몸을 앞으로 크게 숙이며 내려치고, 그대로 오래 멈춘다.
# 360°를 돈 뒤에는 같은 모양인 0°로 순간 바꿔서(CONSTANT) 끝날 때 거꾸로 감기지 않게 한다.
anim("slam", 16,
    P(0, OQ, body=(0, 0, 0), rightArm=(-170, -10, 10), leftArm=(-170, 10, -10), torso=(-14, 0, 0), head=(-10, 0, 0),
      rightLeg=(-55, 0, 0), leftLeg=(-55, 0, 0))
    + P(2, IQ, body=(150, None, None), rightLeg=(-80, 0, 0), leftLeg=(-80, 0, 0))
    + P(4, EX, body=(395, None, None), rightArm=(-30, -8, 0), leftArm=(-30, 8, 0), torso=(30, 0, 0), head=(20, 0, 0),
        rightLeg=(-20, 0, 0), leftLeg=(-35, 0, 0))
    + P(10, LIN, body=(392, None, None), rightArm=(-28, -8, 0), leftArm=(-28, 8, 0), torso=(28, 0, 0))
    + P(11, CONST, body=(32, None, None))
    + P(16, IO, body=(0, None, None), rightArm=(-40, 0, 0), leftArm=(-40, 0, 0), torso=(5, 0, 0), head=(0, 0, 0),
        rightLeg=(-5, 0, 0), leftLeg=(-5, 0, 0)),
    stop=18)

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
    k(0, "body", LIN, pitch=0), k(8, "body", LIN, pitch=-360), k(9, "body", "CONSTANT", pitch=0),
    k(2, "rightLeg", OQ, pitch=-70), k(2, "leftLeg", OQ, pitch=-70),
    k(2, "rightArm", OQ, pitch=-60), k(2, "leftArm", OQ, pitch=-60),
], stop=9)

# ── 맞을 때 ── 에픽파이트 hit_short 처럼: 1틱 만에 몸 전체가 30° 비틀리고 상체·고개가 뒤로 젖혀짐, 팔은 조금만
hit_r = (
    P(1, EX, body=(0, 24, 0), torso=(-22, 0, 0), head=(-24, -10, 0), rightArm=(14, 0, 8), leftArm=(26, 0, -6),
      rightLeg=(10, 0, 0), leftLeg=(-6, 0, 0))
    + P(4, LIN, body=(0, 22, 0), torso=(-20, 0, 0), head=(-20, -8, 0))
    + P(11, IO, body=(0, 0, 0), torso=(0, 0, 0), head=(0, 0, 0), rightArm=(0, 0, 0), leftArm=(0, 0, 0),
        rightLeg=(0, 0, 0), leftLeg=(0, 0, 0))
)
anim("hit", 11, hit_r, stop=13)
anim("hit_2", 11, mirror(hit_r), stop=13)
anim("hit_air", 14,
    P(1, EX, torso=(-24, 0, 0), head=(-26, 0, 0), rightArm=(-30, 0, 38), leftArm=(-30, 0, -38), rightLeg=(26, 0, 6), leftLeg=(-14, 0, -6))
    + P(14, LIN, torso=(-20, 0, 0), head=(-22, 0, 0), rightArm=(-26, 0, 34), leftArm=(-26, 0, -34), rightLeg=(22, 0, 6), leftLeg=(-12, 0, -6)),
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
