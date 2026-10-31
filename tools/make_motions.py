"""
도시 능력자 모션 생성기 — playerAnimator(Emotecraft JSON 형식 3판) 파일을 만든다.
    python tools/make_motions.py            # JSON 만들기
    python tools/preview_motions.py         # 미리보기 그림 (tools/preview/)
결과: city-ability/src/main/resources/assets/bbc/player_animations/<이름>.json

■ 축 방향 (playerAnimator·마인크래프트 소스에서 확인)
  body(몸 전체)  pitch + = 뒤로 젖힘   yaw + = 왼쪽으로 돎(오른어깨가 앞으로)   축은 발에서 0.7칸 위
  head           pitch + = 아래를 봄   yaw + = 오른쪽을 봄
  팔·다리        pitch - = 앞으로 올림 (-90 = 앞으로 쭉)   yaw + = 내 오른쪽으로 휘두름
                 오른팔 roll + = 바깥(오른쪽)으로 벌림, 왼팔 roll - = 바깥으로 벌림
  torso          가슴 상자만 돈다 (팔·머리는 안 따라감) → 거의 안 씀
  bend           팔꿈치·무릎 (bendy-lib 를 모드에 같이 넣음). 아래 절반이 가운데를 축으로 돈다
                 팔 bend - = 팔꿈치를 앞으로 굽힘, 다리 bend + = 무릎을 뒤로 굽힘 (bendy-lib 소스로 확인)
■ 이징: 키프레임에 적힌 이징은 '그 키프레임으로 들어가는 구간'에 걸린다 (easeBeforeKeyframe)
■ 에픽파이트 맨손 공격을 관절 각도로 재 보고 따른 원칙 (파일을 옮긴 게 아니라 원칙만):
  - 몸 전체를 옆으로 돌린 싸움 자세를 유지, 머리는 정면을 봄
  - 예비 동작은 거의 없음. 판정 틱에 1틱 만에 팔을 뻗고(약 95°) 잠깐 멈췄다가 천천히 자세로
  - 크게 움직이는 건 뻗는 팔 하나와 몸 전체 회전(10~25°)뿐
  - 맞을 때는 1틱 만에 몸이 비틀리며 젖혀지고 팔은 조금만
"""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

OUT = os.path.join(os.path.dirname(__file__), "..", "city-ability", "src", "main", "resources",
                   "assets", "bbc", "player_animations")
OQ, IQ, IO, LIN, EX, CONST = "EASEOUTQUAD", "EASEINQUAD", "EASEINOUTSINE", "LINEAR", "EASEOUTEXPO", "CONSTANT"
OS = "EASEOUTSINE"

A = {}


def P(t, ease, **parts):
    """한 틱에 여러 부위 자세. 값은 (pitch, yaw, roll[, bend[, axis[, z]]]) 튜플, None 은 건드리지 않음.
    z = 앞뒤 위치(픽셀, - 가 앞). 팔을 돌리지 않고 앞으로 밀 때 (찌르기)
    bend = 팔꿈치·무릎 굽힘 (bendy-lib). 팔은 음수 = 팔꿈치를 앞으로 굽힘, 다리는 양수 = 무릎을 뒤로 굽힘"""
    out = []
    for part, v in parts.items():
        ax = {n: val for n, val in zip(("pitch", "yaw", "roll", "bend", "axis", "z"), v) if val is not None}
        out.append({"tick": t, "easing": ease, "turn": 0, part: ax})
    return out


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


def anim(name, end, keys, stop=None, loop=False, ret=0):
    A[name] = {"version": 3, "name": name, "author": "bbc", "description": "",
               "emote": {"isLoop": loop, "returnTick": ret, "beginTick": 0, "endTick": end,
                         "stopTick": stop if stop is not None else end + 1, "degrees": True,
                         "easeBeforeKeyframe": True, "nsfw": False, "moves": keys}}


# ── 싸움 자세 (전투 모드 동안 아래층에 계속 깔림) ──
# 왼어깨가 앞(몸을 오른쪽으로 16° 돎), 머리는 정면. 위팔은 앞으로 조금, 팔꿈치를 접어 주먹을 턱 앞에.
# 다리는 건드리지 않아서 걷기는 그대로
STANCE = dict(body=(0, -16, 0), head=(0, -16, 0),
              rightArm=(-38, -18, 8, -100), leftArm=(-50, 14, -6, -88))
anim("stance", 40, P(0, LIN, **STANCE) + P(20, IO, rightArm=(-41, -18, 8, -102), leftArm=(-53, 14, -6, -90))
     + P(40, IO, **STANCE), loop=True, ret=0)


def strike(t_hit, end, hit, prep=None, hold=3, legs=None, prep_legs=None):
    """자세 → 판정 1틱 전까지 크게 끌어당김(prep) → 판정 틱에 1틱 만에 hit → hold 틱 유지 → end 에 자세로 (천천히)"""
    keys = P(0, LIN, **STANCE)
    if prep: keys += P(t_hit - 1, IO, **prep)
    keys += P(t_hit, EX, **hit) + P(t_hit + hold, LIN, **hit) + P(end, IO, **STANCE)
    z = dict(rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0))
    if legs or prep_legs:  # 다리는 자세에 없으니 0에서 시작해 0으로 돌아옴
        keys += P(0, LIN, **z)
        if prep_legs: keys += P(t_hit - 1, IO, **prep_legs)
        if legs: keys += P(t_hit, EX, **legs) + P(t_hit + hold, LIN, **legs)
        keys += P(end, IO, **z)
    return keys


# ── 평타 (선딜 4 → 판정 → 후딜 2, 다음 평타까지 7틱) ──
# '툭툭'이 아니라 '제대로 때리는' 느낌: 선딜 동안 주먹을 크게 뒤로 당기고 몸을 반대로 꼬았다가,
# 판정 틱에 몸 전체를 40~60° 돌리며 앞발을 내딛고 상체를 숙여 체중을 싣는다. 판정 뒤 3틱 멈춤
LUNGE_L = dict(leftLeg=(-32, 0, 0, 30), rightLeg=(22, 0, 0, 8))      # 왼발 내딛음
LUNGE_R = dict(rightLeg=(-30, 0, 0, 28), leftLeg=(24, 0, 0, 8))      # 오른발 내딛음
anim("m1_1", 10, strike(4, 10,  # 왼손 잽
    prep=dict(leftArm=(-40, -8, -10, -125), body=(0, -8, 0), head=(0, -8, 0)),
    hit=dict(leftArm=(-92, 8, 0, -2), rightArm=(-30, -20, 10, -115), body=(-10, -34, 0), head=(-8, -34, 0)),
    legs=LUNGE_L))
anim("m1_2", 10, strike(4, 10,  # 오른손 스트레이트: 몸을 오른쪽으로 꼬았다가 왼쪽으로 크게 돌리며 뒷손을 꽂음
    prep=dict(rightArm=(-25, -24, 14, -128), body=(0, -38, 0), head=(0, -38, 0)),
    hit=dict(rightArm=(-94, 10, 0, -2), leftArm=(-30, 20, -10, -118), body=(-12, 26, 0), head=(-10, 26, 0)),
    prep_legs=dict(rightLeg=(10, 0, 0, 14), leftLeg=(-10, 0, 0, 10)),
    legs=dict(rightLeg=(18, 0, 0, 10), leftLeg=(-28, 0, 0, 26))))
anim("m1_3", 10, strike(4, 10,  # 왼손 훅: 팔꿈치를 90°로 접고 몸통째 크게 휘두름
    prep=dict(leftArm=(-70, -50, -30, -60), body=(0, -40, 0), head=(0, -40, 0)),
    hit=dict(leftArm=(-90, 46, -8, -70), rightArm=(-34, -20, 10, -115), body=(-8, 22, 0), head=(-6, 22, 0)),
    legs=dict(leftLeg=(-20, 0, 0, 22), rightLeg=(14, 0, 0, 8))))
anim("m1_4", 10, strike(4, 10,  # 오른손 어퍼: 무릎을 깊게 굽혀 낮췄다가 온몸으로 솟구침
    prep=dict(rightArm=(-5, -10, 6, -115), body=(-16, -24, 0), head=(-6, -24, 0)),
    hit=dict(rightArm=(-150, -6, 0, -40), leftArm=(-30, 18, -10, -115), body=(8, 18, 0), head=(-18, 18, 0)),
    prep_legs=dict(rightLeg=(12, 0, 0, 30), leftLeg=(-24, 0, 0, 32)),
    legs=dict(rightLeg=(6, 0, 0, 8), leftLeg=(-14, 0, 0, 6))))
anim("m1_5", 17, strike(5, 17,  # 넉백: 크게 젖혔다가 한 발 깊게 내딛는 오른손 정권 (선딜 5, 오래 멈춤)
    prep=dict(rightArm=(10, -30, 18, -125), leftArm=(-70, 30, -10, -60), body=(6, -52, 0), head=(0, -44, 0)),
    hit=dict(rightArm=(-96, 8, 0, 0), leftArm=(-20, 24, -14, -120), body=(-18, 34, 0), head=(-14, 30, 0)),
    prep_legs=dict(rightLeg=(14, 0, 0, 30), leftLeg=(-14, 0, 0, 20)),
    legs=dict(rightLeg=(30, 0, 0, 10), leftLeg=(-44, 0, 0, 40)), hold=6))
anim("launch", 12, strike(4, 12,  # 올려치기 (뜨기·업 플링·래더): 무릎을 깊게 굽혔다가 온몸을 펴며 팔을 하늘로
    prep=dict(rightArm=(5, -10, 8, -110), leftArm=(-60, 20, -10, -90), body=(-18, -22, 0), head=(-4, -22, 0)),
    hit=dict(rightArm=(-175, -4, 0, -6), leftArm=(-10, 16, -24, -60), body=(12, 14, 0), head=(-26, 14, 0)),
    prep_legs=dict(rightLeg=(14, 0, 0, 34), leftLeg=(-24, 0, 0, 34)),
    legs=dict(rightLeg=(10, 0, 0, 4), leftLeg=(-20, 0, 0, 10)), hold=4))

# 공중 평타: 같은 손놀림 + 무릎을 당긴 다리
AIR_LEGS = dict(rightLeg=(-50, 0, 4, 70), leftLeg=(-20, 0, -4, 50))
def air(hit, prep=None):
    k = strike(4, 10, hit, prep)
    return k + P(0, LIN, **AIR_LEGS) + P(10, LIN, **AIR_LEGS)
anim("air_1", 10, air(dict(leftArm=(-94, 8, 0, -2), rightArm=(-30, -20, 10, -115), body=(-10, -34, 0), head=(-8, -34, 0)),
                      prep=dict(leftArm=(-40, -8, -10, -125), body=(0, -8, 0), head=(0, -8, 0))))
anim("air_2", 10, air(dict(rightArm=(-96, 10, 0, -2), leftArm=(-30, 20, -10, -118), body=(-12, 26, 0), head=(-10, 26, 0)),
                      prep=dict(rightArm=(-25, -24, 14, -128), body=(0, -38, 0), head=(0, -38, 0))))
anim("air_3", 9,  # 공중 앞차기: 무릎을 접었다가 쭉 폄
    P(0, LIN, **STANCE, **AIR_LEGS)
    + P(3, IO, rightLeg=(-60, 0, 0, 100), body=(4, -6, 0))
    + P(4, EX, rightLeg=(-88, 0, 0, 0), leftLeg=(-10, 0, 0, 30), body=(10, 0, 0), head=(-8, 0, 0),
        rightArm=(-30, 0, 24, -60), leftArm=(-30, 0, -24, -60))
    + P(6, LIN, rightLeg=(-86, 0, 0, 0), body=(10, 0, 0))
    + P(9, IO, **STANCE, **AIR_LEGS))

# 다운 슬램: ABA 기본 내려찍기 = 공중에서 몸을 아래로 기울이며 두 손을 머리 위에서 아래로 내리침 (선딜 4)
# 몸을 앞으로 50° 숙이고(body pitch -) 무릎은 뒤로 접어 몸이 거의 눕듯이, 팔은 머리 위 → 아래로
anim("slam", 14,
    P(0, LIN, **STANCE, rightLeg=(-30, 0, 0, 60), leftLeg=(-30, 0, 0, 60))
    + P(3, IO, body=(-12, 0, 0), head=(-20, 0, 0), rightArm=(-175, -12, 8, -60), leftArm=(-175, 12, -8, -60),
        rightLeg=(10, 0, 0, 80), leftLeg=(10, 0, 0, 80))
    + P(4, EX, body=(-52, 0, 0), head=(-30, 0, 0), rightArm=(-50, -6, 4, -8), leftArm=(-50, 6, -4, -8),
        rightLeg=(25, 0, 0, 70), leftLeg=(18, 0, 0, 60))
    + P(9, LIN, body=(-50, 0, 0), head=(-28, 0, 0), rightArm=(-48, -6, 4, -8), leftArm=(-48, 6, -4, -8))
    + P(14, IO, **STANCE, rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0)))

# 드롭킥 (ABA): 몸을 살짝 옆으로 틀며 무릎을 가슴까지 당겼다가 → 몸을 완전히 눕혀(수평) 두 발을 앞으로 쭉 뻗고 상대를 따라 날아감. 팔은 머리 뒤로
anim("dropkick", 14,
    P(0, LIN, **STANCE, rightLeg=(-30, 0, 0, 60), leftLeg=(-30, 0, 0, 60))
    + P(1, EX, body=(26, 0, 10), head=(10, 0, 0), rightArm=(-150, 0, 22, -40), leftArm=(-150, 0, -22, -40),
        rightLeg=(-100, 0, 4, 120), leftLeg=(-100, 0, -4, 120))
    + P(3, LIN, body=(32, 0, 14), rightLeg=(-105, 0, 4, 125), leftLeg=(-105, 0, -4, 125))
    + P(4, EX, body=(84, 0, 18), head=(62, 0, -10), rightArm=(-175, 0, 26, -4), leftArm=(-165, 0, -36, -8),
        rightLeg=(-14, 0, 4, 0), leftLeg=(-8, 0, -4, 0))
    + P(10, LIN, body=(80, 0, 16), head=(58, 0, -10), rightLeg=(-16, 0, 4, 4), leftLeg=(-10, 0, -4, 4))
    + P(14, IO, **STANCE, rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0)))

# 각성: 웅크렸다가 두 팔을 벌리며 가슴을 펴고 하늘을 봄 (밀어내기와 같이)
anim("awaken", 20,
    P(0, LIN, **STANCE, rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0))
    + P(4, IO, body=(-14, 0, 0), head=(20, 0, 0), rightArm=(-20, -30, 10, -110), leftArm=(-20, 30, -10, -110),
        rightLeg=(-10, 0, 0, 40), leftLeg=(-10, 0, 0, 40))
    + P(6, EX, body=(10, 0, 0), head=(-30, 0, 0), rightArm=(-20, 0, 75, -10), leftArm=(-20, 0, -75, -10),
        rightLeg=(0, 0, 8, 0), leftLeg=(0, 0, -8, 0))
    + P(14, LIN, body=(8, 0, 0), head=(-26, 0, 0), rightArm=(-24, 0, 70, -12), leftArm=(-24, 0, -70, -12))
    + P(20, IO, **STANCE, rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0)))

# ── 무기 (오른손) ──
# 바닐라 모델에서 손에 든 무기는 항상 '아래팔에 수직'으로 붙는다. 그래서 각도를 손으로 맞추지 않고
# "위팔이 가리킬 방향 u + 칼끝이 가리킬 방향 v" 로 자세를 적고 armsolve 가 (pitch, yaw, roll) 을 푼다 (몸 기준 x=오른쪽, y=위, z=뒤).
#
# ABA 무기 영상(검·대검·창)과 에픽파이트 영상을 프레임 단위로 본 결과:
#  1) 팔을 '어깨 높이까지 쭉 뻗어' 몸 앞을 반원 넘게 쓸어 넘긴다. 팔을 늘어뜨린 채 손목만 도는 게 아님 → 칼끝이 그리는 호가 크다
#  2) 상체가 40~60° 감겼다가 반대로 40~60° 풀린다 (합 90~110°). 발은 매 타 반 발씩 내딛고 무릎을 굽혀 자세가 낮다
#  3) 휘두르기는 2틱: 감긴 자세(칼끝이 등 뒤) → 1틱 만에 칼끝이 앞을 지나며 → 판정 틱에 끝까지. 칼끝이 손보다 늦게 따라옴(채찍)
#  4) 검은 친 자세로 멈춰 버팀, 대검은 친 뒤에도 무게에 끌려 몸이 같은 방향으로 더 돌아감, 창은 봉처럼 비스듬히 휘두르다 마지막에 찌름
#  5) 기본 자세: 검은 오른 허리 아래로 칼끝이 앞 아래, 대검은 날을 앞 오른쪽 땅에 끌듯, 창은 허리에서 창끝이 앞
import armsolve as S


class D:
    """방향으로 적은 팔 자세 (u: 위팔 방향, v: 칼끝 방향, bend: 팔꿈치, z: 앞뒤 위치)"""
    def __init__(self, u, v, bend=0, z=None): self.u, self.v, self.bend, self.z = u, v, bend, z


def seq(frames):
    """frames = [(tick, easing, {부위: 튜플 또는 D})]. D 는 풀어서, 같은 부위의 앞 키와 가장 가까운 오일러 각으로 이어 붙임"""
    keys, last = [], {}
    for t, ease, parts in frames:
        out = {}
        for part, v in parts.items():
            if isinstance(v, D):
                tup = S.arm(v.u, v.v, v.bend, v.z)
                tup = S.near(tup, last.get(part))
            else:
                tup = v
            last[part] = tup
            out[part] = tup
        keys += P(t, ease, **out)
    return keys


def cut(base, t_hit, end, coil, mid, hit, follow, hold_to, coil2=None):
    """감기(1틱 만에) → 버팀 → 휘두름 중간(판정 1틱 전) → 판정(끝까지) → 따라감(hold_to) → 자세로.
    각 단계는 부위 dict. 다리는 base 에 없으면 0 에서 시작해 0 으로 돌아감"""
    z = dict(rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0))
    b0 = {**z, **base}
    fr = [(0, LIN, b0), (1, EX, coil)]
    if t_hit - 2 > 1: fr.append((t_hit - 2, LIN, coil2 or coil))
    fr += [(t_hit - 1, IQ, mid), (t_hit, OQ, hit), (hold_to, OS, follow), (end, IO, b0)]
    # 단계마다 빠진 부위는 앞 단계 값을 이어 씀 (팔·몸·다리 모두 같은 틱에 키가 있어야 보간이 같이 감)
    full, cur = [], {}
    for t, e, parts in fr:
        cur = {**cur, **parts}
        full.append((t, e, dict(cur)))
    return seq(full)


def legs(r, l):  # (pitch, roll, bend) 오른다리·왼다리. roll + = 바깥으로 벌림
    return dict(rightLeg=(r[0], 0, r[1], r[2]), leftLeg=(l[0], 0, -l[1], l[2]))

WIDE = legs((10, 6, 20), (-14, 6, 20))          # 무릎 굽혀 넓게 버팀
STEP_L = legs((28, 6, 12), (-42, 4, 40))        # 왼발 깊게 내딛음
STEP_R = legs((-40, 4, 38), (28, 6, 12))        # 오른발 깊게 내딛음
CROUCH = legs((16, 8, 40), (-26, 8, 40))        # 낮게 웅크림
RISE = legs((8, 2, 4), (-16, 2, 8))             # 솟구침

# 기본 자세 (전투 모드 위층: 다리는 건드리지 않아 걷기는 그대로)
SWORD_STANCE = dict(body=(0, -16, 0), head=(0, -16, 0),
                    rightArm=D((0.3, -0.92, -0.2), (0.25, -0.35, -1), -28), leftArm=(-40, 14, -6, -70))
SPEAR_STANCE = dict(body=(0, -28, 0), head=(0, -24, 0),
                    rightArm=D((0.15, -0.97, 0.15), (0.05, 0.05, -1), -20, 1), leftArm=D((0.05, -0.55, -0.8), (0.1, 0.4, -1), -40, -1))
GS_STANCE = dict(body=(0, -18, 0), head=(0, -18, 0),
                 rightArm=D((0.35, -0.93, 0.05), (0.35, -0.45, -0.85), -12), leftArm=(-30, 16, -10, -50))
# 자세 기본값은 seq 로 풀어 둠 (loop 숨쉬기용)
def solved(st): return {k: (tuple(S.arm(v.u, v.v, v.bend, v.z)) if isinstance(v, D) else v) for k, v in st.items()}
anim("stance_sword", 40, seq([(0, LIN, SWORD_STANCE), (20, IO, {"rightArm": D((0.3, -0.92, -0.25), (0.25, -0.3, -1), -32)}), (40, IO, SWORD_STANCE)]), loop=True)
anim("stance_spear", 40, seq([(0, LIN, SPEAR_STANCE), (20, IO, {"body": (0, -30, 0)}), (40, IO, SPEAR_STANCE)]), loop=True)
anim("stance_gs", 40, seq([(0, LIN, GS_STANCE), (20, IO, {"rightArm": D((0.35, -0.93, 0.0), (0.35, -0.5, -0.8), -14)}), (40, IO, GS_STANCE)]), loop=True)

L_BAL = (-10, 30, -40, -40)     # 왼팔: 반대쪽으로 벌려 균형
L_GUARD = (-50, 20, -6, -80)

# ── 검 (판정 4틱, 친 자세로 버팀) ──
anim("sw_1", 11, cut(SWORD_STANCE, 4, 11,  # 오른쪽 뒤 → 왼쪽 앞까지 어깨 높이 가로 베기
    coil=dict(body=(0, -44, 0), head=(0, -28, 0), rightArm=D((0.6, 0.3, 0.75), (0.75, 0.15, -0.6), -30), leftArm=(-70, 40, -10, -80), **WIDE),
    coil2=dict(body=(0, -48, 0), rightArm=D((0.58, 0.32, 0.78), (0.8, 0.15, -0.55), -34)),
    mid=dict(body=(-4, -14, 0), head=(-4, -14, 0), rightArm=D((0.7, 0.12, -0.7), (-0.7, 0.12, -0.7), -6)),
    hit=dict(body=(-8, 26, 0), head=(-6, 12, 0), rightArm=D((-0.35, 0.1, -0.93), (-0.93, 0.15, 0.35)), leftArm=L_BAL, **STEP_L),
    follow=dict(body=(-8, 32, 0), rightArm=D((-0.7, 0.1, -0.7), (-0.7, 0.25, 0.65))), hold_to=8))
anim("sw_2", 11, cut(SWORD_STANCE, 4, 11,  # 되돌려 베기: 팔을 가슴 앞 왼쪽으로 감았다가 오른쪽 끝까지
    coil=dict(body=(0, 20, 0), head=(0, 12, 0), rightArm=D((-0.75, 0.2, -0.4), (-0.4, 0.2, 0.75), -45), leftArm=(-20, 10, -20, -60), **WIDE),
    coil2=dict(body=(0, 24, 0), rightArm=D((-0.77, 0.22, -0.36), (-0.36, 0.2, 0.8), -50)),
    mid=dict(body=(-4, 4, 0), head=(-4, 4, 0), rightArm=D((-0.6, 0.12, -0.8), (0.8, 0.12, -0.6), -6)),
    hit=dict(body=(-8, -28, 0), head=(-6, -16, 0), rightArm=D((0.35, 0.1, -0.93), (0.93, 0.15, 0.35)), leftArm=(-40, 50, -30, -70), **STEP_R),
    follow=dict(body=(-8, -34, 0), rightArm=D((0.7, 0.1, -0.7), (0.7, 0.25, 0.65))), hold_to=8))
anim("sw_3", 11, cut(SWORD_STANCE, 4, 11,  # 오른쪽 위 → 왼쪽 아래 사선 내려베기
    coil=dict(body=(10, -34, 0), head=(-8, -22, 0), rightArm=D((0.4, 0.88, 0.25), (0.15, -0.2, 1), -45), leftArm=(-110, 30, -20, -60), **WIDE),
    coil2=dict(body=(12, -38, 0), rightArm=D((0.38, 0.9, 0.22), (0.1, -0.3, 1), -50)),
    mid=dict(body=(0, -6, 0), head=(-4, -6, 0), rightArm=D((0.3, 0.45, -0.84), (0.25, 0.85, 0.4), -10)),
    hit=dict(body=(-24, 22, 0), head=(-10, 10, 0), rightArm=D((-0.35, -0.85, -0.4), (-0.5, -0.45, -0.75), -10), leftArm=L_BAL, **STEP_R),
    follow=dict(body=(-26, 26, 0), rightArm=D((-0.4, -0.9, -0.15), (-0.45, -0.5, -0.75), -6)), hold_to=8))
anim("sw_4", 11, cut(SWORD_STANCE, 4, 11,  # 왼쪽 아래 → 오른쪽 위 사선 올려베기
    coil=dict(body=(-14, 32, 0), head=(-6, 16, 0), rightArm=D((-0.45, -0.85, -0.25), (-0.45, -0.3, 0.85), -30), leftArm=(-30, 10, -10, -90), **CROUCH),
    mid=dict(body=(-6, 4, 0), head=(-6, 4, 0), rightArm=D((0.05, -0.45, -0.9), (-0.15, -0.9, 0.45), -10)),
    hit=dict(body=(10, -24, 0), head=(-20, -12, 0), rightArm=D((0.45, 0.8, -0.4), (0.75, 0.6, 0.1)), leftArm=(-10, 20, -50, -30), **RISE),
    follow=dict(body=(12, -28, 0), rightArm=D((0.42, 0.9, -0.15), (0.8, 0.55, 0.2))), hold_to=8))
anim("sw_5", 18, cut(SWORD_STANCE, 5, 18,  # 마무리: 두 손으로 머리 위 → 크게 내딛으며 내리쳐 날려보냄
    coil=dict(body=(14, -24, 0), head=(-16, -20, 0), rightArm=D((0.15, 0.95, 0.25), (0.05, -0.1, 1), -50),
              leftArm=D((-0.1, 0.95, 0.25), (0.05, -0.1, 1), -50), **legs((14, 6, 30), (-14, 6, 22))),
    coil2=dict(body=(16, -26, 0), rightArm=D((0.15, 0.95, 0.3), (0.05, -0.2, 1), -55), leftArm=D((-0.1, 0.95, 0.3), (0.05, -0.2, 1), -55)),
    mid=dict(body=(-6, -8, 0), head=(-8, -8, 0), rightArm=D((0.1, 0.3, -0.95), (0, 0.95, 0.3), -10), leftArm=D((-0.05, 0.3, -0.95), (0, 0.95, 0.3), -10)),
    hit=dict(body=(-32, 12, 0), head=(-12, 12, 0), rightArm=D((0.1, -0.8, -0.6), (0, -0.4, -0.95), -6), leftArm=D((-0.05, -0.8, -0.6), (0, -0.4, -0.95), -10),
             **legs((32, 6, 10), (-50, 6, 48))),
    follow=dict(body=(-34, 14, 0), rightArm=D((0.1, -0.88, -0.45), (0, -0.5, -0.9), -6)), hold_to=13))
anim("sw_rise", 12, cut(SWORD_STANCE, 4, 12,  # 올려베기 (띄우기): 웅크렸다가 칼을 하늘로 쳐올림
    coil=dict(body=(-18, 20, 0), head=(-6, 10, 0), rightArm=D((0.1, -0.95, 0.3), (0.05, -0.2, 1), -20), leftArm=(-40, 20, -10, -80), **CROUCH),
    mid=dict(body=(-4, 6, 0), rightArm=D((0.1, -0.2, -0.98), (0, -0.98, 0.2), -6)),
    hit=dict(body=(14, -10, 0), head=(-30, -10, 0), rightArm=D((0.1, 0.97, -0.2), (0.05, 0.2, 0.98)), leftArm=(-10, 0, -60, -30), **RISE),
    follow=dict(body=(16, -12, 0), rightArm=D((0.1, 0.98, 0.05), (0.05, -0.05, 1))), hold_to=9))

# 무기 다운 슬램 (ABA: 공중에서 무기를 머리 위로 세웠다가 그대로 아래로 쪼개듯 내리찍음)
TUCK2 = legs((-40, 4, 70), (-30, 4, 70))
def wslam(base):
    return cut(base, 4, 14,
        coil=dict(body=(14, 0, 0), head=(-18, 0, 0), rightArm=D((0.12, 0.97, 0.15), (0.05, -0.15, 1), -45),
                  leftArm=D((-0.12, 0.97, 0.15), (0.05, -0.15, 1), -45), **TUCK2),
        coil2=dict(body=(16, 0, 0), rightArm=D((0.12, 0.96, 0.25), (0.05, -0.3, 1), -50), leftArm=D((-0.12, 0.96, 0.25), (0.05, -0.3, 1), -50)),
        mid=dict(body=(-10, 0, 0), head=(-6, 0, 0), rightArm=D((0.1, 0.3, -0.95), (0, 0.95, 0.3), -10), leftArm=D((-0.1, 0.3, -0.95), (0, 0.95, 0.3), -10)),
        hit=dict(body=(-40, 0, 0), head=(16, 0, 0), rightArm=D((0.1, -0.75, -0.65), (0, -0.55, -0.85), -4), leftArm=D((-0.1, -0.75, -0.65), (0, -0.55, -0.85), -6),
                 **legs((10, 6, 40), (-10, 6, 30))),
        follow=dict(body=(-44, 0, 0), rightArm=D((0.1, -0.85, -0.5), (0, -0.6, -0.8), -4)), hold_to=10)
anim("sw_slam", 14, wslam(SWORD_STANCE))
anim("sp_slam", 14, wslam(SPEAR_STANCE))
anim("gs_slam", 14, wslam(GS_STANCE))

# ── 창 (판정 4틱): ABA 처럼 봉을 휘두르듯 비스듬히 쳐 내리고·올리고, 3타·4타는 깊게 찌름 ──
def two(r_u, r_v, r_b=-10, l_u=None, l_v=None, l_b=-30):
    """두 손으로 쥔 긴 무기: 왼손은 오른손 앞쪽 자루를 잡음 (왼팔이 오른팔과 비슷한 방향, 조금 더 앞)"""
    return dict(rightArm=D(r_u, r_v, r_b), leftArm=D(l_u or (r_u[0] - 0.35, r_u[1], r_u[2] - 0.3), l_v or r_v, l_b))
# 창은 '휘두르다 마는' 느낌이 나지 않게: 판정 뒤에도 무게에 끌려 몸과 창이 같은 방향으로 계속 돌아가고(관성),
# 다음 타는 앞 타가 끝난 자리에서 이어서 감는다 (1타 끝 = 왼쪽 아래 → 2타 감기, 2타 끝 = 오른 어깨 위 → 3타 감기)
anim("sp_1", 12, cut(SPEAR_STANCE, 4, 12,  # 오른 어깨 위 → 왼쪽 아래로 비스듬히 후려침, 끝까지 따라감
    coil=dict(body=(4, -56, 0), head=(0, -46, 0), **two((0.65, 0.65, 0.4), (0.3, 0.2, 1), -35), **WIDE),
    mid=dict(body=(-4, -16, 0), head=(-4, -14, 0), **two((0.55, 0.45, -0.7), (-0.3, 0.9, 0.3), -15)),
    hit=dict(body=(-14, 26, 0), head=(-8, 18, 0), **two((-0.25, -0.55, -0.8), (-0.75, -0.35, -0.55), -8), **STEP_L),
    follow=dict(body=(-18, 62, 0), head=(-10, 44, 0), **two((-0.6, -0.75, -0.2), (-0.6, -0.4, 0.7), -6)), hold_to=9))
anim("sp_2", 12, cut(SPEAR_STANCE, 4, 12,  # 왼쪽 아래 → 오른쪽 위로 걷어 올려 오른 어깨 너머까지
    coil=dict(body=(-10, 42, 0), head=(-4, 32, 0), **two((-0.5, -0.8, -0.3), (-0.6, -0.4, 0.7), -25), **CROUCH),
    mid=dict(body=(-6, 6, 0), head=(-6, 4, 0), **two((0.05, -0.45, -0.9), (-0.15, -0.9, 0.4), -10)),
    hit=dict(body=(6, -30, 0), head=(-14, -24, 0), **two((0.45, 0.55, -0.7), (0.85, 0.45, 0.2), -6), **RISE),
    follow=dict(body=(8, -56, 0), head=(-12, -44, 0), **two((0.55, 0.8, -0.1), (0.4, 0.3, 0.85), -10)), hold_to=9))
anim("sp_3", 12, cut(SPEAR_STANCE, 4, 12,  # 허리 높이로 오른쪽 → 왼쪽 크게 휩쓸기, 몸이 70° 넘게 따라 돎
    coil=dict(body=(0, -62, 0), head=(0, -50, 0), **two((0.8, 0.1, 0.55), (0.6, 0.05, -0.8), -25), **WIDE),
    mid=dict(body=(-6, -16, 0), head=(-6, -14, 0), **two((0.7, 0.0, -0.7), (-0.7, 0.0, -0.7), -8)),
    hit=dict(body=(-10, 30, 0), head=(-8, 22, 0), **two((-0.35, 0.0, -0.93), (-0.93, 0.05, 0.35), -4), **STEP_L),
    follow=dict(body=(-12, 72, 0), head=(-8, 52, 0), **two((-0.8, 0.0, -0.6), (-0.6, 0.05, 0.8), -4)), hold_to=9))
# 마무리 (판정 5틱): 창을 옆으로 쭉 뻗고 제자리에서 한 바퀴 돌며 후려쳐 날려보냄 → 관성으로 조금 더 돌고 멈춤
SPIN_ARMS = two((0.95, 0.05, -0.2), (0.3, 0.1, -0.95), -4)
anim("sp_4", 18, seq([
    (0, LIN, {**solved(SPEAR_STANCE), "rightLeg": (0, 0, 0, 0), "leftLeg": (0, 0, 0, 0)}),
    (1, EX, dict(body=(-6, -50, 0), head=(0, -40, 0), **SPIN_ARMS, **WIDE)),
    (2, LIN, dict(body=(-6, -56, 0), head=(0, -44, 0))),
    (4, IQ, dict(body=(-8, 150, 0), head=(-4, 120, 0))),
    (5, OQ, dict(body=(-10, 320, 0), head=(-6, 300, 0), **STEP_L)),
    (10, OS, dict(body=(-12, 376, 0), head=(-8, 350, 0))),
    (11, CONST, dict(body=(-12, 16, 0), head=(-8, -10, 0))),
    (18, IO, {**solved(SPEAR_STANCE), "rightLeg": (0, 0, 0, 0), "leftLeg": (0, 0, 0, 0)}),
]))
RP_BACK = D((0.5, -0.85, 0.15), (-0.3, 0, -0.95), -10, 3)      # 오른손: 창을 허리 뒤로 당김 (창끝은 앞)
LP_BACK = D((-0.45, -0.6, -0.65), (0, 1, 0), -30)
RP_OUT = lambda z: D((0.35, -0.2, -0.9), (-0.9, 0, -0.35), -4, z)   # 오른손: 앞으로 쭉 (몸을 옆으로 돌려 창끝이 정면)
LP_OUT = D((-0.6, -0.1, -0.8), (0, 1, 0), -6)
def thrust(t_hit, end, lg, hold_to, push=-3):
    """찌르기 (ABA 창): 몸을 옆으로 돌려 창을 허리 뒤로 당겼다가 → 왼어깨를 앞에 두고 두 팔을 앞으로 쭉 뻗으며 깊게 내딛음"""
    return cut(SPEAR_STANCE, t_hit, end,
        coil=dict(body=(4, -58, 0), head=(0, -50, 0), rightArm=RP_BACK, leftArm=LP_BACK, **WIDE),
        mid=dict(body=(-6, -52, 0), rightArm=D((0.5, -0.45, -0.3), (-0.6, -0.05, -0.8), -12, 0), leftArm=D((-0.55, -0.35, -0.75), (0, 1, 0), -15)),
        hit=dict(body=(-16, -50, 0), head=(-10, -46, 0), rightArm=RP_OUT(push), leftArm=LP_OUT, **lg),
        follow=dict(body=(-17, -50, 0), rightArm=RP_OUT(push - 1)), hold_to=hold_to)
anim("sp_thrust", 13, thrust(5, 13, legs((36, 6, 10), (-54, 6, 50)), 10, push=-5))
anim("sp_rapid", 4,  # 연속 찌르기 (꾹 누르는 동안 4틱마다 반복 재생)
    seq([(0, LIN, dict(body=(0, -56, 0), head=(0, -50, 0), rightArm=RP_BACK, leftArm=LP_BACK)),
         (1, EX, dict(body=(-14, -50, 0), head=(-10, -46, 0), rightArm=RP_OUT(-4), leftArm=LP_OUT)),
         (4, IO, dict(body=(0, -56, 0), head=(0, -50, 0), rightArm=RP_BACK, leftArm=LP_BACK))]), stop=5)
anim("sp_sweep", 15, cut(SPEAR_STANCE, 5, 15,  # 창을 낮게 들고 몸 전체로 크게 휩쓸기
    coil=dict(body=(0, -60, 0), head=(0, -32, 0), **two((0.85, -0.2, 0.45), (0.25, -0.1, 1), -20), **WIDE),
    mid=dict(body=(-6, -16, 0), **two((0.6, -0.2, -0.75), (-0.6, -0.1, -0.8), -10)),
    hit=dict(body=(-10, 48, 0), head=(-6, 20, 0), **two((-0.75, -0.25, -0.6), (-0.6, -0.1, 0.8), -6), **STEP_R),
    follow=dict(body=(-10, 64, 0), **two((-0.9, -0.2, -0.35), (-0.35, -0.1, 0.93), -6)), hold_to=11))

# ── 대검·도끼 (판정 7틱): ABA 대검처럼 한 손으로 무거운 날을 크게 돌려 후림, 친 뒤 무게에 끌려 몸이 더 돎 ──
anim("gs_1", 20, cut(GS_STANCE, 7, 20,  # 오른쪽 어깨 위로 들어 → 왼쪽 아래까지 사선으로 내려 후림
    coil=dict(body=(10, -46, 0), head=(-6, -26, 0), rightArm=D((0.55, 0.8, 0.25), (0.2, -0.15, 1), -45), leftArm=(-70, 40, -10, -80), **WIDE),
    coil2=dict(body=(12, -52, 0), rightArm=D((0.5, 0.82, 0.3), (0.15, -0.3, 1), -50)),
    mid=dict(body=(-2, -14, 0), head=(-4, -10, 0), rightArm=D((0.45, 0.4, -0.8), (0.2, 0.85, 0.45), -10)),
    hit=dict(body=(-22, 30, 0), head=(-10, 10, 0), rightArm=D((-0.4, -0.8, -0.45), (-0.55, -0.4, -0.75), -6), leftArm=L_BAL, **STEP_L),
    follow=dict(body=(-30, 50, 0), head=(-10, 22, 0), rightArm=D((-0.55, -0.82, -0.15), (-0.6, -0.5, -0.6), -4)), hold_to=15))
anim("gs_2", 20, cut(GS_STANCE, 7, 20,  # 왼쪽 아래에서 몸 전체로 가로 후리기 → 관성으로 더 돎
    coil=dict(body=(0, 30, 0), head=(0, 16, 0), rightArm=D((-0.6, -0.45, -0.6), (-0.8, 0.2, 0.1), -35), leftArm=(-20, 10, -20, -60), **WIDE),
    coil2=dict(body=(0, 34, 0), rightArm=D((-0.62, -0.42, -0.6), (-0.8, 0.2, 0.2), -40)),
    mid=dict(body=(-6, 6, 0), rightArm=D((-0.6, -0.05, -0.8), (0.8, 0.05, -0.6), -8)),
    hit=dict(body=(-12, -22, 0), head=(-6, -12, 0), rightArm=D((0.05, 0.05, -1), (1, 0.1, 0.05)), leftArm=(-30, 50, -40, -60), **STEP_R),
    follow=dict(body=(-14, -58, 0), head=(-6, -28, 0), rightArm=D((0.7, 0.0, -0.7), (0.7, 0.1, 0.7))), hold_to=15))
anim("gs_3", 24, cut(GS_STANCE, 8, 24,  # 마무리: 두 손으로 머리 위 → 크게 내딛으며 땅까지 내리찍음
    coil=dict(body=(14, -24, 0), head=(-16, -20, 0), rightArm=D((0.15, 0.95, 0.25), (0.05, -0.1, 1), -50),
              leftArm=D((-0.1, 0.95, 0.25), (0.05, -0.1, 1), -50), **legs((14, 6, 30), (-14, 6, 22))),
    coil2=dict(body=(18, -26, 0), rightArm=D((0.15, 0.93, 0.35), (0.05, -0.3, 1), -55), leftArm=D((-0.1, 0.93, 0.35), (0.05, -0.3, 1), -55)),
    mid=dict(body=(-8, -8, 0), head=(-8, -8, 0), rightArm=D((0.1, 0.3, -0.95), (0, 0.95, 0.3), -10), leftArm=D((-0.05, 0.3, -0.95), (0, 0.95, 0.3), -10)),
    hit=dict(body=(-36, 8, 0), head=(-12, 8, 0), rightArm=D((0.1, -0.8, -0.6), (0, -0.45, -0.9), -4), leftArm=D((-0.05, -0.8, -0.6), (0, -0.45, -0.9), -8),
             **legs((34, 6, 12), (-54, 6, 52))),
    follow=dict(body=(-44, 10, 0), head=(-10, 10, 0), rightArm=D((0.1, -0.9, -0.4), (0, -0.65, -0.75), -4)), hold_to=18))
anim("gs_cleave", 26, cut(GS_STANCE, 10, 26,  # 가르기 (가드 브레이크): 등 뒤까지 크게 젖혔다가 쪼갬
    coil=dict(body=(20, -30, 0), head=(-20, -24, 0), rightArm=D((0.15, 0.9, 0.4), (0.05, -0.4, 1), -55),
              leftArm=D((-0.1, 0.9, 0.4), (0.05, -0.4, 1), -55), **legs((16, 6, 34), (-16, 6, 26))),
    coil2=dict(body=(24, -32, 0), rightArm=D((0.15, 0.85, 0.5), (0.05, -0.5, 0.9), -60), leftArm=D((-0.1, 0.85, 0.5), (0.05, -0.5, 0.9), -60)),
    mid=dict(body=(-8, -8, 0), head=(-8, -8, 0), rightArm=D((0.1, 0.3, -0.95), (0, 0.95, 0.3), -10), leftArm=D((-0.05, 0.3, -0.95), (0, 0.95, 0.3), -10)),
    hit=dict(body=(-40, 6, 0), head=(-12, 6, 0), rightArm=D((0.1, -0.8, -0.6), (0, -0.5, -0.87), -4), leftArm=D((-0.05, -0.8, -0.6), (0, -0.5, -0.87), -8),
             **legs((36, 6, 12), (-58, 6, 56))),
    follow=dict(body=(-46, 8, 0), rightArm=D((0.1, -0.9, -0.4), (0, -0.7, -0.7), -4)), hold_to=20))
anim("gs_whirl", 8,  # 휠윈드 (꾹 누르는 동안 반복): 대검을 옆으로 쭉 뻗고 몸 전체가 한 바퀴
    seq([(0, LIN, dict(body=(-8, 0, 0), head=(0, 0, 0), rightArm=D((0.95, 0.05, -0.2), (0.3, 0.1, -0.95)), leftArm=(-20, 0, -60, -30), **WIDE)),
         (7, LIN, dict(body=(-8, -360, 0))), (8, CONST, dict(body=(-8, 0, 0)))]), loop=True, ret=0)


# ── 연타 (각성기 ‘난무’): 2틱마다 좌우 번갈아 힘껏 지름. 몸이 크게 비틀리고 상체가 앞으로 쏠린 채 ──
# 지르는 손은 1틱 만에 끝까지 뻗고(팔꿈치 완전히 폄), 반대 손은 그 틱에 허리까지 당김. 가끔 훅·어퍼를 섞어 '마구' 때리는 느낌
BARRAGE_LEGS = dict(rightLeg=(26, 0, 4, 14), leftLeg=(-36, 0, -4, 36))
def barrage():
    keys = P(0, LIN, **STANCE, **{k: (0, 0, 0, 0) for k in ("rightLeg", "leftLeg")})
    keys += P(1, EX, body=(-16, -10, 0), head=(-10, -10, 0), **BARRAGE_LEGS)
    kinds = ["str", "str", "hook", "str", "upper", "str", "str", "hook"]
    for i in range(8):
        t = 2 + i * 2
        right = i % 2 == 0
        side = 1 if right else -1
        k = kinds[i]
        if k == "str":
            punch = (-96, 6 * side, 0, 0)
        elif k == "hook":
            punch = (-88, 30 * side, -10 * side, -60)
        else:
            punch = (-150, 0, 0, -40)
        pull = (-20, -10 * side, 6 * side, -120)
        arms = dict(rightArm=punch, leftArm=(-20, 10, -6, -120)) if right else dict(leftArm=(punch[0], -punch[1], -punch[2], punch[3]), rightArm=pull)
        keys += P(t, EX, **arms, body=(-18, 30 * side, 0), head=(-10, 16 * side, 0))
        keys += P(t + 1, OS, body=(-17, 24 * side, 0))
    keys += P(18, IO, body=(-14, 0, 0), head=(-8, 0, 0), rightArm=(-40, -10, 8, -100), leftArm=(-50, 14, -6, -88))
    return keys
anim("barrage", 18, barrage(), stop=19)
anim("barrage_end", 18, strike(4, 18,  # 마지막 일격: 몸을 끝까지 꼬았다가 내딛으며 정권
    prep=dict(rightArm=(15, -30, 20, -125), leftArm=(-80, 30, -10, -50), body=(8, -60, 0), head=(0, -50, 0)),
    hit=dict(rightArm=(-95, 8, 0, 0), leftArm=(-15, 26, -16, -120), body=(-22, 40, 0), head=(-16, 34, 0)),
    prep_legs=dict(rightLeg=(14, 0, 0, 34), leftLeg=(-14, 0, 0, 24)),
    legs=dict(rightLeg=(34, 0, 0, 10), leftLeg=(-52, 0, 0, 46)), hold=8))

# ── 방어·이동 ──
# 가드: 몸을 조금 낮추고(무릎 굽혀 넓게) 턱을 당긴 채 막음. 무기별로 다른 자세, 숨 쉬듯 살짝 들썩임
GUARD_LEGS = legs((8, 6, 20), (-12, 6, 20))
GUARD = dict(body=(-8, -14, 0), head=(10, -14, 0), rightArm=(-78, -32, 8, -118), leftArm=(-88, 30, -6, -124), **GUARD_LEGS)  # 두 팔뚝을 얼굴 앞에 세운 하이 가드
def guard_loop(name, pose, breathe):
    anim(name, 20, seq([(0, OS, pose), (10, IO, breathe), (20, IO, pose)]), loop=True, ret=1)
guard_loop("guard", GUARD, dict(body=(-9, -14, 0), rightArm=(-80, -32, 8, -120), leftArm=(-90, 30, -6, -126)))
# 검: 칼을 가로로 눕혀 얼굴 앞에, 왼손으로 칼등을 받침
SW_GUARD = dict(body=(-6, -22, 0), head=(6, -22, 0), rightArm=D((0.35, 0.15, -0.9), (-0.95, 0.2, -0.25), -35),
                leftArm=D((-0.15, 0.25, -0.95), (0, 1, 0), -60), **GUARD_LEGS)
guard_loop("guard_sword", SW_GUARD, dict(body=(-7, -22, 0), rightArm=D((0.35, 0.13, -0.9), (-0.95, 0.2, -0.25), -36)))
# 대검: 넓은 날을 몸 앞에 세워 방패처럼, 왼손으로 날을 밀어 받침
GS_GUARD = dict(body=(-4, -12, 0), head=(8, -12, 0), rightArm=D((0.25, -0.35, -0.9), (0, 0.95, -0.3), -20),
                leftArm=D((-0.05, -0.1, -1), (0, 1, 0), -50), **legs((10, 8, 26), (-16, 8, 26)))
guard_loop("guard_gs", GS_GUARD, dict(body=(-5, -12, 0), rightArm=D((0.25, -0.37, -0.9), (0, 0.95, -0.3), -21)))
# 창: 두 손으로 자루를 가로로 들어 앞을 막음 (봉 막기)
SP_GUARD = dict(body=(-6, -8, 0), head=(6, -8, 0), rightArm=D((0.45, -0.45, -0.75), (-0.95, 0.2, 0.1), -45),
                leftArm=D((-0.45, -0.45, -0.75), (0, 1, 0), -45), **GUARD_LEGS)
guard_loop("guard_spear", SP_GUARD, dict(body=(-7, -8, 0), rightArm=D((0.45, -0.47, -0.75), (-0.95, 0.2, 0.1), -46)))
anim("parry", 8,
    P(0, LIN, **GUARD)
    + P(2, EX, rightArm=(-130, 30, 10), leftArm=(-70, 20, -8), body=(0, 14, 0), head=(0, 14, 0))
    + P(4, LIN, rightArm=(-128, 28, 10))
    + P(8, IO, **STANCE))
anim("dash_f", 6,
    P(1, OQ, body=(-18, 0, 0), head=(-14, 0, 0), rightArm=(30, 0, 8), leftArm=(30, 0, -8), rightLeg=(-30, 0, 0), leftLeg=(28, 0, 0))
    + P(6, IO, body=(-8, 0, 0), head=(-6, 0, 0)))
anim("dash_b", 6,
    P(1, OQ, body=(12, 0, 0), head=(10, 0, 0), rightArm=(-40, 0, 6), leftArm=(-40, 0, -6), rightLeg=(18, 0, 0), leftLeg=(-12, 0, 0))
    + P(6, IO, body=(5, 0, 0)))
anim("dash_l", 6, P(1, OQ, body=(0, 0, -12), rightArm=(-20, 0, 30), leftArm=(-20, 0, -10)) + P(6, IO, body=(0, 0, -5)))
anim("dash_r", 6, P(1, OQ, body=(0, 0, 12), leftArm=(-20, 0, -30), rightArm=(-20, 0, 10)) + P(6, IO, body=(0, 0, 5)))
anim("evade", 9,  # 뒤로 공중제비 (body pitch +)
    P(0, LIN, body=(0, 0, 0)) + P(2, OQ, rightLeg=(-70, 0, 0), leftLeg=(-70, 0, 0), rightArm=(-60, 0, 0), leftArm=(-60, 0, 0))
    + P(8, LIN, body=(360, 0, 0)) + P(9, CONST, body=(0, 0, 0)), stop=10)

# ── 맞을 때 ── 1틱 만에 몸 전체가 비틀리며 뒤로 젖혀짐(약 20°), 고개도 젖혀짐, 팔은 조금 벌어짐. 좌우 번갈아
hit_r = (
    P(1, EX, body=(24, 30, 0), head=(-30, 12, 0), rightArm=(20, 0, 26, -30), leftArm=(30, 0, -14, -45),
      rightLeg=(14, 0, 0, 34), leftLeg=(-10, 0, 0, 18))
    + P(5, LIN, body=(20, 26, 0), head=(-26, 10, 0))
    + P(11, IO, body=(0, 0, 0), head=(0, 0, 0), rightArm=(0, 0, 0, 0), leftArm=(0, 0, 0, 0), rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0))
)
anim("hit", 11, hit_r)
anim("hit_2", 11, mirror(hit_r))
anim("hit_air", 14,  # 공중 경직: 젖혀진 채 팔다리 벌림
    P(1, EX, body=(22, 0, 0), head=(-20, 0, 0), rightArm=(-30, 0, 40, -30), leftArm=(-30, 0, -40, -30), rightLeg=(22, 0, 6, 40), leftLeg=(-14, 0, -6, 25))
    + P(14, LIN, body=(18, 0, 0), head=(-16, 0, 0), rightArm=(-26, 0, 36, -28), leftArm=(-26, 0, -36, -28), rightLeg=(18, 0, 6, 36), leftLeg=(-12, 0, -6, 22)),
    stop=16)
anim("knock", 30,  # 뒤로 날아가 쓰러짐 → 누워 있음 (body pitch + = 뒤로)
    P(0, LIN, body=(0, 0, 0))
    + P(6, OQ, body=(80, 0, 0), head=(-10, 0, 0), rightArm=(-150, 0, 20), leftArm=(-150, 0, -20), rightLeg=(-10, 0, 0), leftLeg=(10, 0, 0))
    + P(28, LIN, body=(82, 0, 0)), stop=31)
anim("getup", 8,
    P(0, LIN, body=(60, 0, 0), rightLeg=(-60, 0, 0), leftLeg=(-30, 0, 0))
    + P(6, OQ, body=(0, 0, 0), rightLeg=(0, 0, 0), leftLeg=(0, 0, 0)))

# ── 기술 ──
anim("dash_attack", 9,
    P(0, LIN, **STANCE)
    + P(2, IO, body=(-12, -24, 0), head=(-8, -24, 0), rightArm=(-50, -30, 10))
    + P(3, EX, body=(-14, 18, 0), head=(-10, 18, 0), rightArm=(-92, 30, 0), leftArm=(30, 0, -10), rightLeg=(-30, 0, 0), leftLeg=(28, 0, 0))
    + P(5, LIN, body=(-14, 16, 0))
    + P(9, IO, **STANCE, rightLeg=(0, 0, 0), leftLeg=(0, 0, 0)))
anim("spin", 8,
    P(0, LIN, body=(0, 0, 0)) + P(1, OQ, rightArm=(-90, 0, 80), leftArm=(-90, 0, -80))
    + P(6, LIN, body=(0, 360, 0)) + P(7, CONST, body=(0, 0, 0)), stop=9)
anim("jump_air", 8,
    P(1, OQ, rightArm=(-160, 0, 20), leftArm=(-160, 0, -20), rightLeg=(-60, 0, 0), leftLeg=(-20, 0, 0)))
anim("heal", 20,
    P(3, OQ, body=(-8, 0, 0), rightArm=(-20, 0, 10), leftArm=(-20, 0, -10), head=(20, 0, 0)) + P(17, IO, body=(-6, 0, 0)))
anim("focus", 12,
    P(2, OQ, rightArm=(-70, -40, 0), leftArm=(-70, 40, 0)) + P(10, IO, rightArm=(-70, -40, 0), leftArm=(-70, 40, 0)))

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, data in A.items():
        with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
    print(len(A), "motions ->", os.path.normpath(OUT))
