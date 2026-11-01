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
        if part == "bodyPos":   # 몸 전체 위치 (블록 단위, x 오른쪽?·y 위·z). 낮은 자세에서 몸을 내림
            ax = {n: val for n, val in zip(("x", "y", "z"), v) if val is not None}
            out.append({"tick": t, "easing": ease, "turn": 0, "body": ax})
            continue
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
# 창·대검 올려치기 (평타 띄우기·업 플링용): 무기를 아래로 늘어뜨렸다가 하늘로 걷어 올림
anim("sp_rise", 12, cut(SPEAR_STANCE, 4, 12,
    coil=dict(body=(-16, 24, 0), head=(-6, 16, 0), **two((0.1, -0.95, 0.3), (0.05, -0.3, 1), -20), **CROUCH),
    mid=dict(body=(-4, 4, 0), **two((0.1, -0.25, -0.96), (0, -0.96, 0.25), -6)),
    hit=dict(body=(14, -12, 0), head=(-30, -10, 0), **two((0.1, 0.95, -0.3), (0.05, 0.3, 0.95), -6), **RISE),
    follow=dict(body=(16, -14, 0), **two((0.1, 0.98, 0.0), (0.05, 0.0, 1), -6)), hold_to=9))
anim("gs_rise", 16, cut(GS_STANCE, 6, 16,
    coil=dict(body=(-20, 30, 0), head=(-6, 20, 0), rightArm=D((0.2, -0.95, 0.25), (0.1, -0.3, 1), -15), leftArm=(-30, 16, -10, -60), **CROUCH),
    coil2=dict(body=(-22, 34, 0), rightArm=D((0.25, -0.93, 0.3), (0.1, -0.35, 1), -15)),
    mid=dict(body=(-6, 8, 0), rightArm=D((0.15, -0.25, -0.96), (0, -0.96, 0.25), -6)),
    hit=dict(body=(16, -16, 0), head=(-30, -12, 0), rightArm=D((0.15, 0.95, -0.3), (0.05, 0.3, 0.95)), leftArm=(-10, 0, -60, -30), **RISE),
    follow=dict(body=(20, -20, 0), rightArm=D((0.15, 0.98, 0.1), (0.05, -0.1, 1))), hold_to=12))

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

# ═════════ 연금술사 (참고: 다른 게임의 에드워드 평타·대기·연출 영상, ABA 에드워드 기술·각성) ═════════
# 각도 확인 (preview 로 손·발 위치를 재서 맞춤):
#   몸 yaw - = 오른쪽으로 돎(왼어깨가 앞). 이때 '정면(상대)'은 몸 기준 왼쪽 → 팔이 상대를 가리키려면 팔 yaw 도 같은 만큼 -
#   머리 yaw 는 몸 yaw 와 같은 값이면 정면을 봄. 다리 roll: 오른다리 + / 왼다리 - = 바깥으로 벌림
#   bodyPos y = 몸 전체를 내림 (블록). 무릎을 굽힌 낮은 자세에선 그만큼 내려야 발이 땅에 붙음
# ABA 원칙 (기둥·가시·창조의 기둥 프레임): 다리를 오므리지 않고 옆으로 넓게 벌려 낮게 버팀 — 깊은 런지·한쪽 무릎 꿇기·넓은 기마 자세.
#   땅을 짚는 기술은 몸 전체가 1/3 가까이 내려가고, 공중 기술은 몸 전체 각도(앞뒤로 눕기·뒤집기)를 크게 씀
ALZ = dict(rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0), bodyPos=(0, 0, 0))
AL_ARMS = dict(body=(0, -34, 0), head=(0, -34, 0), leftArm=(-78, -30, -4, -26), rightArm=(-34, -10, 14, -112))
AL_LEGS = dict(leftLeg=(-20, 0, -16, 24), rightLeg=(16, 0, 16, 18), bodyPos=(0, -0.07, 0))   # 넓게 벌린 옆 자세
AL_STANCE = dict(**AL_ARMS, **AL_LEGS)
anim("stance_al", 60, P(0, LIN, **AL_STANCE)
     + P(30, IO, body=(2, -35, 0), leftArm=(-74, -30, -4, -32), rightArm=(-37, -10, 14, -116),
         leftLeg=(-21, 0, -16, 28), rightLeg=(16, 0, 16, 22), bodyPos=(0, -0.09, 0))
     + P(60, IO, **AL_STANCE), loop=True, ret=0)
anim("stance_al_walk", 60, P(0, LIN, **AL_ARMS)
     + P(30, IO, body=(2, -35, 0), leftArm=(-74, -30, -4, -32), rightArm=(-37, -10, 14, -116))
     + P(60, IO, **AL_ARMS), loop=True, ret=0)


def al(t_hit, end, frames, hold=3, base=None):
    """연금술사 틀. frames = [(틱, 이징, 부위들)]. 판정 자세를 hold 틱 버티고 end 에 대기 자세로"""
    b = base or AL_STANCE
    keys = P(0, LIN, **b)
    hit = None
    for t, e, parts in frames:
        keys += P(t, e, **parts)
        if t == t_hit: hit = parts
    if hit and hold: keys += P(t_hit + hold, LIN, **hit)
    keys += P(end, IO, **b)
    return keys


# 다리 자세 (모두 옆으로 벌림)
DEEP_L = dict(leftLeg=(-52, 0, -18, 66), rightLeg=(40, 0, 18, 10), bodyPos=(0, -0.2, 0))     # 왼발을 깊게 내딛은 런지
DEEP_R = dict(rightLeg=(-52, 0, 18, 66), leftLeg=(40, 0, -18, 10), bodyPos=(0, -0.2, 0))
KNEEL = dict(leftLeg=(-74, 0, -22, 100), rightLeg=(24, 0, 24, 96), bodyPos=(0, -0.36, 0))    # 한쪽 무릎 꿇기 (ABA 땅 짚기)
SUMO = dict(leftLeg=(-36, 0, -30, 64), rightLeg=(-36, 0, 30, 64), bodyPos=(0, -0.26, 0))     # 넓은 기마 자세
COIL = dict(leftLeg=(-24, 0, -18, 36), rightLeg=(22, 0, 18, 30), bodyPos=(0, -0.12, 0))      # 감을 때 살짝 낮춤
AIRL = dict(rightLeg=(-34, 0, 22, 56), leftLeg=(14, 0, -20, 34), bodyPos=(0, 0, 0))          # 공중: 벌린 다리
TUCK = dict(rightLeg=(-84, 0, 24, 110), leftLeg=(-70, 0, -24, 100), bodyPos=(0, 0, 0))       # 공중에서 무릎 당김 (벌린 채)

# 손뼉: 두 손바닥이 가슴 가운데에서 만나고 팔꿈치는 바깥 아래 (preview IK 로 손끝 위치를 맞춤 — 예전엔 팔꿈치끼리 모였음)
CLAP = dict(rightArm=(-44, -43, 26, -84), leftArm=(-44, 43, -26, -84), body=(-4, -8, 0), head=(4, -8, 0))
PRAY = dict(rightArm=(-80, -28, 48, -90), leftArm=(-80, 28, -48, -90), body=(0, 0, 0), head=(8, 0, 0))     # 얼굴 앞 합장
CLAP_UP = dict(rightArm=(-170, -14, -6, -20), leftArm=(-170, 14, 6, -20))                                  # 머리 위로 손뼉
# 땅 짚기: 한쪽 무릎을 꿇고 몸을 숙여 두 손바닥(또는 오른손)으로 땅을 짚음
PLANT2 = dict(rightArm=(-30, -14, 10, -4), leftArm=(-30, 14, -10, -4), body=(-40, -14, 0), head=(-24, -14, 0), **KNEEL)
PLANT1 = dict(rightArm=(-30, -18, 8, -4), leftArm=(-56, 20, -30, -70), body=(-38, -18, 0), head=(-24, -18, 0), **KNEEL)

# 1타 (3~7프레임): 오토메일 칼날 사선 베기 — 오른팔을 머리 위로 들고 몸을 감았다가 왼무릎 앞까지 내려 벰, 깊은 런지
anim("al_1", 10, al(4, 10, [
    (2, IO, dict(rightArm=(-168, 18, 26, -36), leftArm=(-50, -20, -12, -80), body=(4, -52, 0), head=(0, -44, 0), **COIL)),
    (3, IQ, dict(rightArm=(-120, 0, 10, -10), body=(-4, -20, 0), head=(-2, -14, 0))),
    (4, EX, dict(rightArm=(-34, -8, -6, 0), leftArm=(-36, 30, -16, -96), body=(-20, 26, 0), head=(-12, 22, 0), **DEEP_L)),
]))
# 2타: 왼손 올려 훅 — 왼주먹을 내렸다가 팔꿈치를 접은 채 턱 높이로 휘둘러 올림
anim("al_2", 10, al(4, 10, [
    (2, IO, dict(leftArm=(-18, -40, -34, -70), rightArm=(-30, -14, 12, -116), body=(-6, -56, 0), head=(0, -46, 0), **COIL)),
    (4, EX, dict(leftArm=(-112, 26, -14, -84), rightArm=(-26, 10, 16, -118), body=(-12, 18, 0), head=(-8, 16, 0), **DEEP_L)),
]))
# 3타: 오른 스트레이트 — 주먹을 허리로 당겼다가 몸을 왼쪽으로 크게 틀며 상대 가슴으로 쭉
anim("al_3", 10, al(4, 10, [
    (2, IO, dict(rightArm=(18, 4, 18, -124), leftArm=(-82, -40, -6, -30), body=(4, -54, 0), head=(0, -46, 0), **COIL)),
    (4, EX, dict(rightArm=(-94, 32, 0, -2), leftArm=(-30, 26, -14, -112), body=(-16, 32, 0), head=(-12, 30, 0), **DEEP_L)),
]))
# 4타: 돌며 올려차기 — 몸을 감고 오른무릎을 당겼다가 머리 높이까지 차올림. 몸 전체가 뒤로 젖혀지며 왼쪽으로 크게 돎
anim("al_4", 11, al(4, 11, [
    (1, IO, dict(body=(0, -64, 0), head=(0, -50, 0), rightArm=(-30, -10, 30, -60), leftArm=(-60, -20, -30, -60),
                 rightLeg=(-40, 0, 14, 90), leftLeg=(-8, 0, -14, 30), bodyPos=(0, -0.1, 0))),
    (3, IQ, dict(body=(14, -20, -10), head=(-4, -14, 0), rightLeg=(-80, 0, 20, 50))),
    (4, EX, dict(body=(22, 30, -16), head=(-16, 28, 0), rightArm=(-24, 0, 70, -16), leftArm=(-50, 0, -60, -24),
                 rightLeg=(-120, 0, 26, 0), leftLeg=(6, 0, -12, 20), bodyPos=(0, 0.05, 0))),
    (6, OQ, dict(body=(16, 54, -12), head=(-10, 48, 0), rightLeg=(-98, 0, 22, 12))),
], hold=0))
# 5타 마무리 (선딜 6): 손뼉 → 한쪽 무릎 꿇고 오른손바닥으로 땅을 짚음(연성진) → 일어서며 오른팔을 하늘로 쳐올림 (바위 기둥)
anim("al_5", 17, al(6, 17, [
    (2, OQ, dict(**CLAP, **COIL)),
    (3, LIN, CLAP),
    (5, IO, PLANT1),
    (6, EX, dict(rightArm=(-172, -14, 12, -10), leftArm=(-30, 20, -40, -40), body=(12, 18, 0), head=(-24, 16, 0),
                 rightLeg=(16, 0, 18, 6), leftLeg=(-28, 0, -18, 16), bodyPos=(0, 0, 0))),
], hold=6))
# 띄우기: 땅을 짚었다가 올려치기 (5타의 빠른 판)
anim("al_rise", 12, al(4, 12, [
    (3, IQ, PLANT1),
    (4, EX, dict(rightArm=(-174, -10, 8, -8), leftArm=(-24, 18, -30, -50), body=(12, 16, 0), head=(-24, 16, 0),
                 rightLeg=(14, 0, 18, 6), leftLeg=(-26, 0, -18, 16), bodyPos=(0, 0, 0))),
], hold=4))
# 다운 슬램: 공중에서 손뼉 → 오른손에 칼을 연성(칼날이 솟음) → 몸 전체를 뒤로 젖혀 칼을 머리 위로 크게 들었다가
#   앞으로 고꾸라지듯 몸 전체를 70° 숙이며 내리찍음 (다리는 뒤로 벌려 끌려옴)
anim("al_slam", 15, P(0, LIN, **AL_ARMS, **AIRL)
     + P(1, OQ, **CLAP)
     + P(3, IO, rightArm=(-176, 10, 20, -30), leftArm=(-120, 10, -30, -40), body=(26, -10, 0), head=(-26, -10, 0),
         rightLeg=(-50, 0, 20, 90), leftLeg=(-36, 0, -20, 80))
     + P(4, EX, rightArm=(-60, 4, 6, 0), leftArm=(-10, 20, -50, -30), body=(-72, -6, 0), head=(-34, -6, 0),
         rightLeg=(34, 0, 22, 40), leftLeg=(20, 0, -22, 30))
     + P(9, LIN, rightArm=(-58, 4, 6, 0), body=(-68, -6, 0))
     + P(15, IO, **AL_STANCE))

# ── 연금술사 기술 ──
# 손뼉 연성: 가슴 앞 손뼉 → 한쪽 무릎 꿇고 몸을 숙여 두 손바닥으로 땅을 짚음 (판정 6틱) → 일어남
anim("al_clap", 18, al(6, 18, [(2, OQ, dict(**CLAP, **COIL)), (4, LIN, CLAP), (6, EX, PLANT2)], hold=5))
# 무기 연성: 손뼉 → 한쪽 무릎 꿇고 땅에서 창을 뽑아 올림
anim("al_forge", 20, al(11, 20, [
    (3, EX, dict(**CLAP, **COIL)), (5, LIN, CLAP),
    (8, IO, PLANT1),
    (11, EX, dict(rightArm=(-150, -10, 10, -10), leftArm=(-30, 20, -20, -60), body=(-6, -24, 0), head=(-14, -24, 0), **DEEP_L)),
], hold=3))
# 손바닥 내밀기 (돌 산탄 · 공중 신의 손): 손뼉 → 깊게 내딛으며 오른손바닥을 앞으로
anim("al_creation", 18, al(6, 18, [
    (3, EX, dict(**CLAP, **COIL)), (4, LIN, CLAP),
    (6, EX, dict(rightArm=(-92, 26, 0, 0), leftArm=(-40, 20, -20, -90), body=(-18, 30, 0), head=(-12, 28, 0), **DEEP_L)),
], hold=8))
# 신의 손 (각성 2) · 연성 방벽: 손뼉 → 넓은 기마 자세로 두 팔을 양옆으로 크게 벌렸다가 → 가슴 앞에서 세게 맞부딪침 (판정 10틱)
anim("al_godhand", 20, al(10, 20, [
    (3, EX, dict(**CLAP, **COIL)),
    (7, IO, dict(rightArm=(-100, 0, 70, -10), leftArm=(-100, 0, -70, -10), body=(8, -10, 0), head=(-10, -10, 0), **SUMO)),
    (10, EX, dict(CLAP, body=(-12, -10, 0), head=(-6, -10, 0), **SUMO)),
], hold=5))
# 최후의 일격 (각성 4, 선딜 8): 오토메일 주먹을 끝까지 뒤로 당겨 몸을 꼬고 낮게 버팀(떨림) → 깊게 내딛으며 꽂음
FP_COIL = dict(rightArm=(26, 10, 26, -120), leftArm=(-86, -44, -6, -24), body=(6, -70, 0), head=(0, -56, 0),
               rightLeg=(20, 0, 20, 40), leftLeg=(-26, 0, -20, 40), bodyPos=(0, -0.16, 0))
anim("al_punch", 24, al(8, 24, [
    (4, IO, FP_COIL),
    (6, LIN, dict(FP_COIL, body=(8, -73, 0))),
    (7, LIN, dict(FP_COIL, body=(6, -71, 0))),
    (8, EX, dict(rightArm=(-96, 38, 0, 0), leftArm=(-14, 30, -18, -120), body=(-26, 38, 0), head=(-18, 36, 0),
                 rightLeg=(42, 0, 18, 8), leftLeg=(-62, 0, -18, 70), bodyPos=(0, -0.24, 0))),
], hold=8))

# 각성 (컷신 170틱 ≈ 8.5초, 참고: 다른 게임의 에드워드 연출 + 각성 음성 길이)
#   먼지 속에 가만히 서 있음 → 얼굴 앞에서 합장(손뼉) → 두 팔을 머리 위로 → 한쪽 무릎 꿇고 땅을 내리침(거대한 연성진)
#   → 가시 숲이 솟는 동안 엎드려 버팀 → 천천히 일어섬 → 한 발 내딛으며 오토메일 손바닥을 앞(카메라)으로 쭉
STAND = dict(body=(0, 0, 0), head=(0, 0, 0), rightArm=(0, 0, 6, -8), leftArm=(0, 0, -6, -8),
             rightLeg=(0, 0, 8, 2), leftLeg=(0, 0, -8, 2), bodyPos=(0, 0, 0))
anim("al_awaken", 170, P(0, LIN, **STAND) + P(30, LIN, **STAND)
     + P(36, IO, **PRAY) + P(40, EX, rightArm=(-80, -28, 48, -92), leftArm=(-80, 28, -48, -92))   # 손뼉 (짝)
     + P(58, LIN, **PRAY, bodyPos=(0, 0, 0))
     + P(62, IO, body=(8, 0, 0), head=(-20, 0, 0), **CLAP_UP, rightLeg=(0, 0, 10, 4), leftLeg=(0, 0, -10, 4), bodyPos=(0, 0, 0))
     + P(66, EX, **PLANT2) + P(100, LIN, **dict(PLANT2, body=(-44, -14, 0)))
     + P(114, IO, **STAND)
     + P(130, LIN, body=(0, 4, 0), head=(0, 4, 0))
     + P(138, EX, rightArm=(-92, -14, 0, 0), leftArm=(-12, 0, -12, -24), body=(-4, 22, 0), head=(-2, 22, 0), **DEEP_R)
     + P(160, LIN, rightArm=(-92, -14, 0, -2), body=(-4, 22, 0), head=(-2, 22, 0))
     + P(170, IO, **AL_STANCE))
# 각성기 '대지의 심판' (컷신 100틱, 대사에 맞춤): 가슴 앞에 손을 모으고 낮게 버팀(연성 에너지가 모임) → 크게 손뼉(50) →
#   한쪽 무릎 꿇고 두 손바닥으로 땅을 내리침(60) → 버팀 → 일어섬
anim("al_ult", 100, al(60, 100, [
    (6, IO, dict(CLAP, body=(-4, -6, 0), head=(2, -6, 0), **SUMO)),                 # 가슴 앞에 손을 모으고 낮게 버팀 (에너지가 모임)
    (44, LIN, dict(rightArm=(-46, -43, 26, -86), leftArm=(-46, 43, -26, -86), body=(-6, -6, 0), head=(2, -6, 0))),
    (47, IO, dict(rightArm=(-60, -10, 50, -70), leftArm=(-60, 10, -50, -70), body=(4, -6, 0))),   # 손을 살짝 벌렸다가
    (50, EX, dict(CLAP, body=(-8, -6, 0), head=(0, -6, 0), **SUMO)),               # 크게 손뼉
    (57, LIN, dict(CLAP, body=(-10, -6, 0))),
    (60, EX, PLANT2),
    (88, LIN, dict(PLANT2, body=(-46, -14, 0))),
], hold=0))
# 백덤블링 (기둥 벽 · 창조의 기둥, ABA Spike·Pillars of Creation): 낮게 웅크렸다(3) 뒤로 솟구쳐 → 다리를 벌린 채 무릎을 당기고
#   몸 전체가 뒤로 한 바퀴(12틱에 걸쳐 천천히) → 공중에서 손뼉 → 착지하며 한쪽 무릎 꿇고 땅을 짚음 (판정 16틱: 기둥들이 솟음)
anim("al_backflip", 28, P(0, LIN, **AL_STANCE)
     + P(3, IO, body=(-14, -10, 0), head=(-8, -10, 0), rightArm=(30, 0, 24, -20), leftArm=(30, 0, -24, -20), **SUMO)
     + P(5, OQ, body=(40, 0, 0), head=(-20, 0, 0), rightArm=(-160, 0, 24, -30), leftArm=(-160, 0, -24, -30),
         rightLeg=(-30, 0, 22, 40), leftLeg=(-10, 0, -22, 30), bodyPos=(0, 0, 0))
     + P(8, IO, body=(130, 0, 0), rightArm=(-120, -10, 30, -40), leftArm=(-120, 10, -30, -40), **TUCK)
     + P(11, LIN, body=(220, 0, 0))
     + P(13, IO, body=(300, 0, 0), rightArm=CLAP['rightArm'], leftArm=CLAP['leftArm'])
     + P(15, OQ, body=(360, 0, 0), rightLeg=(-40, 0, 24, 60), leftLeg=(-20, 0, -24, 50))
     + P(16, CONST, body=(0, 0, 0))
     + P(16, EX, **PLANT1)
     + P(22, LIN, **dict(PLANT1, body=(-36, -18, 0)))
     + P(28, IO, **AL_STANCE))

# ── 오토메일 칼날 평타 (무기 연성을 공중에서: 오른 아래팔 바깥에서 칼날이 솟음, 맨팔이면 장갑에) ──
anim("ab_1", 9, al(3, 9, [   # 오른 어깨 위 → 왼무릎 앞 사선 베기
    (1, IO, dict(rightArm=(-166, 18, 26, -20), body=(4, -50, 0), head=(0, -42, 0), **COIL)),
    (2, IQ, dict(rightArm=(-120, 0, 10, -6), body=(-4, -20, 0), head=(-2, -14, 0))),
    (3, EX, dict(rightArm=(-36, -12, -6, 0), leftArm=(-36, 30, -16, -96), body=(-20, 26, 0), head=(-12, 22, 0), **DEEP_L)),
]))
anim("ab_2", 9, al(3, 9, [   # 왼쪽 아래 → 오른쪽 위로 걷어 올려 베기
    (1, IO, dict(rightArm=(-30, -56, -20, -30), body=(-8, 30, 0), head=(-6, 26, 0), **COIL)),
    (3, EX, dict(rightArm=(-150, 34, 30, 0), leftArm=(-40, 20, -20, -90), body=(6, -40, 0), head=(0, -36, 0), **DEEP_R)),
]))
anim("ab_3", 9, al(3, 9, [   # 가슴 높이 오른쪽 → 왼쪽 크게 휩쓸기 (기마 자세로 낮게)
    (1, IO, dict(rightArm=(-90, 70, 24, -10), body=(0, -56, 0), head=(0, -46, 0), **COIL)),
    (3, EX, dict(rightArm=(-92, -44, 0, 0), leftArm=(-30, 30, -20, -100), body=(-10, 44, 0), head=(-8, 40, 0), **SUMO)),
], hold=2))
anim("ab_4", 15, al(5, 15, [   # 마무리: 칼날을 끝까지 당겼다가 깊게 내딛으며 찌르기
    (3, IO, dict(rightArm=(22, 6, 20, -124), leftArm=(-84, -40, -6, -28), body=(6, -60, 0), head=(0, -50, 0), **COIL)),
    (5, EX, dict(rightArm=(-92, 34, 0, 0), leftArm=(-20, 30, -16, -110), body=(-22, 36, 0), head=(-14, 34, 0),
                 rightLeg=(42, 0, 18, 8), leftLeg=(-62, 0, -18, 70), bodyPos=(0, -0.24, 0))),
], hold=5))
anim("ab_rise", 10, al(3, 10, [   # 띄우기: 낮게 무릎 꿇었다가 아래에서 위로 올려 베기
    (1, IO, dict(rightArm=(-10, -10, 10, -10), body=(-30, -20, 0), head=(-16, -20, 0), **KNEEL)),
    (3, EX, dict(rightArm=(-176, -10, 8, 0), leftArm=(-24, 18, -30, -50), body=(12, 16, 0), head=(-24, 16, 0),
                 rightLeg=(14, 0, 18, 6), leftLeg=(-26, 0, -18, 16), bodyPos=(0, 0, 0))),
], hold=3))
anim("ab_slam", 15, [dict(m) for m in A["al_slam"]["emote"]["moves"]])
# 칼날 연성 (공중): 손뼉 → 오른 아래팔을 얼굴 앞에 세움 (칼날이 솟음)
anim("al_bladeforge", 12, al(6, 12, [
    (3, EX, dict(**CLAP, **AIRL)),
    (6, EX, dict(rightArm=(-100, -20, 0, -110), leftArm=(-30, 10, -20, -60), body=(0, -30, 0), head=(0, -30, 0), **AIRL)),
], hold=3))
# 기둥 낙하: 낮게 웅크림 → 발밑 기둥에 튕겨 솟구치며 몸을 뒤로 젖힘 → 공중에서 칼날을 머리 위로 →
#   몸 전체를 앞으로 75° 고꾸라뜨리며 칼날을 아래로 꽂은 채 낙하 (다리는 뒤로 벌려 끌려옴)
anim("ab_plunge", 30, P(0, LIN, **AL_STANCE)
     + P(3, IO, body=(-18, -20, 0), head=(-8, -20, 0), rightArm=(10, 0, 14, -30), leftArm=(10, 0, -14, -30), **SUMO)
     + P(5, OQ, body=(14, 0, 0), head=(-16, 0, 0), rightArm=(-170, 0, 14, -10), leftArm=(-40, 0, -30, -30), **AIRL)
     + P(9, IO, body=(24, 0, 0), head=(-24, 0, 0), rightArm=(-178, -10, 10, -40), leftArm=(-60, 20, -40, -40), **TUCK)
     + P(11, EX, body=(-76, 0, 0), head=(-34, 0, 0), rightArm=(-26, -6, 4, 0), leftArm=(30, 0, -40, -20),
         rightLeg=(36, 0, 24, 40), leftLeg=(18, 0, -24, 60))
     + P(24, LIN, body=(-72, 0, 0), rightArm=(-26, -6, 4, 0))
     + P(30, IO, **AL_STANCE))

# 창조의 기둥 (각성 1, ABA Pillars of Creation 99.2~101.2초 프레임): 웅크렸다 뛰어오르며 빠르게 뒤로 반 바퀴 →
#   머리가 아래로 거꾸로 뜬 채(두 손은 땅 쪽, 다리는 하늘로 벌림) 잠깐 멈춰 연성 — 이때 1차 기둥 →
#   나머지 반 바퀴 돌아 착지하며 한쪽 무릎 꿇고 땅을 짚음 — 2차 기둥 (판정 8 · 20틱)
FLIP_INV = dict(rightArm=(-168, -8, 14, -10), leftArm=(-168, 8, -14, -10), head=(-30, 0, 0),
                rightLeg=(-14, 0, 22, 30), leftLeg=(10, 0, -22, 20))
anim("al_flip", 34, P(0, LIN, **AL_STANCE)
     + P(2, IO, body=(-16, -10, 0), head=(-8, -10, 0), rightArm=(24, 0, 20, -20), leftArm=(24, 0, -20, -20), **SUMO)
     + P(3, LIN, body=(0, 0, 0), bodyPos=(0, 0, 0))
     + P(6, OQ, body=(180, 0, 0), **FLIP_INV)
     + P(12, LIN, body=(186, 0, 6))
     + P(16, IO, body=(182, 0, 0), rightArm=(-160, -10, 20, -20), leftArm=(-160, 10, -20, -20))
     + P(19, IQ, body=(352, 0, 0), rightLeg=(-40, 0, 24, 60), leftLeg=(-24, 0, -24, 50))
     + P(20, CONST, body=(0, 0, 0))
     + P(20, EX, **PLANT1)
     + P(27, LIN, **dict(PLANT1, body=(-36, -18, 0)))
     + P(34, IO, **AL_STANCE))

# ── 의족(왼다리 오토메일)이면 평타가 발차기 위주 (alk_*). 칼날은 왼다리(의족)에 → 마무리는 왼발 ──
LK_ARMS = dict(rightArm=(-60, -20, 20, -100), leftArm=(-40, 30, -40, -60))   # 차는 동안 팔은 균형 (가드)
anim("alk_1", 10, al(4, 10, [   # 왼발 앞차기: 무릎을 당겼다가 쭉 뻗음
    (2, IO, dict(body=(4, -30, 0), head=(0, -30, 0), leftLeg=(-70, 0, -10, 100), rightLeg=(10, 0, 16, 26), bodyPos=(0, -0.08, 0), **LK_ARMS)),
    (4, EX, dict(body=(14, -28, 0), head=(-6, -28, 0), leftLeg=(-96, 0, -6, 0), rightLeg=(12, 0, 16, 22), bodyPos=(0, -0.05, 0))),
]))
anim("alk_2", 10, al(4, 10, [   # 오른발 돌려차기 (옆구리 높이): 몸을 감았다가 왼쪽으로 크게 돌며 정강이로 후림
    (2, IO, dict(body=(0, -60, 0), head=(0, -50, 0), rightLeg=(-30, 0, 40, 80), leftLeg=(-6, 0, -16, 30), bodyPos=(0, -0.08, 0), **LK_ARMS)),
    (4, EX, dict(body=(10, 40, -16), head=(-4, 36, 0), rightLeg=(-78, 0, 58, 6), leftLeg=(6, 0, -14, 24), bodyPos=(0, -0.04, 0),
                 rightArm=(-20, 0, 60, -20), leftArm=(-60, 20, -50, -40))),
]))
anim("alk_3", 10, al(4, 10, [   # 왼발 옆차기: 몸을 옆으로 눕히듯 기울이며 발바닥으로 밀어 참
    (2, IO, dict(body=(0, -70, 10), head=(0, -60, 0), leftLeg=(-60, 0, -30, 110), rightLeg=(8, 0, 18, 30), bodyPos=(0, -0.1, 0), **LK_ARMS)),
    (4, EX, dict(body=(0, -78, 26), head=(0, -64, -14), leftLeg=(-84, 0, -46, 0), rightLeg=(10, 0, 20, 20), bodyPos=(0, -0.08, 0))),
]))
anim("alk_4", 11, al(4, 11, [   # 뒤돌려차기: 등을 보이며 한 바퀴 가까이 돌고 오른발 뒤꿈치로 머리 높이를 후림
    (1, IO, dict(body=(0, -40, 0), head=(0, -30, 0), rightLeg=(-20, 0, 20, 60), leftLeg=(-10, 0, -16, 30), bodyPos=(0, -0.1, 0), **LK_ARMS)),
    (3, IQ, dict(body=(10, 100, -10), head=(0, 60, 0))),
    (4, EX, dict(body=(18, 150, -20), head=(-10, 90, 0), rightLeg=(-110, 0, 40, 0), leftLeg=(6, 0, -14, 20), bodyPos=(0, 0, 0),
                 rightArm=(-30, 0, 70, -20), leftArm=(-40, 0, -70, -20))),
    (6, OQ, dict(body=(14, 180, -10), head=(-6, 100, 0))),
    (7, CONST, dict(body=(14, -180, -10), head=(-6, -100, 0))),
], hold=0))
anim("alk_5", 17, al(6, 17, [   # 마무리 (선딜 6): 손뼉 → 왼발을 머리 위까지 차올렸다가 발뒤꿈치로 내리찍음 (찍은 자리에 연성진 + 기둥)
    (2, OQ, dict(**CLAP, **COIL)),
    (4, IO, dict(body=(16, -30, 0), head=(-10, -30, 0), leftLeg=(-160, 0, -10, 10), rightLeg=(14, 0, 18, 30), bodyPos=(0, -0.06, 0),
                 rightArm=(-40, 0, 50, -40), leftArm=(-40, 0, -50, -40))),
    (6, EX, dict(body=(-24, -30, 0), head=(-20, -30, 0), leftLeg=(-50, 0, -16, 20), rightLeg=(30, 0, 20, 40), bodyPos=(0, -0.22, 0))),
], hold=6))
anim("alk_rise", 11, al(4, 11, [   # 띄우기: 몸을 숙였다가 왼발로 하늘 높이 올려참 (초승달 차기)
    (2, IO, dict(body=(-16, -20, 0), head=(-10, -20, 0), leftLeg=(10, 0, -24, 40), rightLeg=(-30, 0, 20, 60), bodyPos=(0, -0.2, 0), **LK_ARMS)),
    (4, EX, dict(body=(26, -20, -10), head=(-20, -20, 0), leftLeg=(-170, 0, -20, 0), rightLeg=(14, 0, 16, 20), bodyPos=(0, 0, 0),
                 rightArm=(-30, 0, 60, -20), leftArm=(-30, 0, -60, -20))),
], hold=3))
# 다운 슬램 (의족): 공중에서 손뼉 → 왼다리에 칼날 연성 → 몸 전체를 뒤로 젖혀 왼다리를 머리 위로 → 고꾸라지며 발뒤꿈치 칼날로 내리찍음
anim("alk_slam", 15, P(0, LIN, **AL_ARMS, **AIRL)
     + P(1, OQ, **CLAP)
     + P(3, IO, body=(30, -20, 0), head=(-24, -20, 0), leftLeg=(-170, 0, -14, 10), rightLeg=(10, 0, 20, 50),
         rightArm=(-30, 0, 60, -30), leftArm=(-30, 0, -60, -30))
     + P(4, EX, body=(-40, -20, 0), head=(-30, -20, 0), leftLeg=(-40, 0, -16, 6), rightLeg=(40, 0, 22, 60))
     + P(9, LIN, body=(-38, -20, 0))
     + P(15, IO, **AL_STANCE))
# 칼날 연성 (의족·공중): 손뼉 → 왼무릎을 가슴까지 당겨 세움 (정강이에서 칼날이 솟음)
anim("alk_bladeforge", 12, al(6, 12, [
    (3, EX, dict(**CLAP, **AIRL)),
    (6, EX, dict(rightArm=(-40, 0, 50, -40), leftArm=(-40, 0, -50, -40), body=(6, -30, 0), head=(0, -30, 0),
                 leftLeg=(-110, 0, -16, 120), rightLeg=(10, 0, 20, 40))),
], hold=3))
# 기둥 낙하 (의족): 발밑 기둥에 튕겨 솟구침 → 공중에서 몸을 웅크렸다가 → 몸 전체를 앞으로 기울이며 칼날 단 왼발을 앞·아래로 쭉 뻗고 내리꽂힘 (드롭킥)
anim("alk_plunge", 30, P(0, LIN, **AL_STANCE)
     + P(3, IO, body=(-18, -20, 0), head=(-8, -20, 0), rightArm=(10, 0, 14, -30), leftArm=(10, 0, -14, -30), **SUMO)
     + P(5, OQ, body=(14, 0, 0), head=(-16, 0, 0), rightArm=(-150, 0, 20, -20), leftArm=(-150, 0, -20, -20), **AIRL)
     + P(9, IO, body=(20, 0, 0), head=(-20, 0, 0), **TUCK)
     + P(11, EX, body=(-40, -20, 0), head=(-36, -20, 0), leftLeg=(-60, 0, -8, 0), rightLeg=(-20, 0, 24, 100),
         rightArm=(-150, 0, 30, -20), leftArm=(-150, 0, -30, -20))
     + P(24, LIN, body=(-38, -20, 0))
     + P(30, IO, **AL_STANCE))

# ── 강철 심장 ──
# 심장 펌핑 (공중 무기 연성): 오른 주먹으로 왼가슴을 쾅 — 심장이 뛰며 붉은 파동 (20초 과부하)
PUMP = dict(rightArm=(-70, -60, -10, -110), leftArm=(-30, 20, -40, -40), body=(-10, -10, 0), head=(10, -10, 0))
anim("al_pump", 14, al(5, 14, [
    (3, IO, dict(rightArm=(-60, -10, 40, -100), body=(6, -20, 0), head=(-6, -20, 0), **AIRL)),
    (5, EX, dict(**PUMP, **AIRL)),
    (8, LIN, dict(PUMP, body=(-14, -10, 0))),
], hold=0))
# 심장 폭주 (마무리): 몸을 웅크려 심장을 두 번 펌핑 → 두 팔을 벌리고 가슴을 펴며 붉은 충격파를 터뜨림 (판정 10틱)
anim("al_pump_burst", 22, al(10, 22, [
    (3, IO, dict(**PUMP, **COIL)),
    (5, EX, dict(PUMP, body=(-20, -10, 0))),
    (7, IO, dict(PUMP, body=(-8, -10, 0))),
    (8, EX, dict(PUMP, body=(-24, -10, 0), **SUMO)),
    (10, EX, dict(rightArm=(-40, 0, 80, -10), leftArm=(-40, 0, -80, -10), body=(16, 0, 0), head=(-24, 0, 0),
                  rightLeg=(-10, 0, 24, 20), leftLeg=(-10, 0, -24, 20), bodyPos=(0, -0.1, 0))),
], hold=5))

# ── 달리기 (전투 모드 중 달릴 때 자세 층에 깔림) ──
# 다리·팔 흔들기는 바닐라(걸음 속도에 맞춰 흔들림)를 그대로 두고, 몸만 앞으로 숙이고 팔꿈치를 굽혀 달리는 모양만 잡음
# (예전엔 10틱 고정 주기로 다리를 따로 움직여서 실제 속도와 안 맞아 미끄러지듯 어색했음)
RUN = dict(body=(-12, None, None), rightArm=(None, None, 7, -78), leftArm=(None, None, -7, -78),
           rightLeg=(None, None, None, 18), leftLeg=(None, None, None, 18))
anim("run", 20, P(0, LIN, **RUN) + P(10, IO, body=(-13, None, None)) + P(20, IO, **RUN), loop=True, ret=0)
RUN_W = dict(RUN, rightArm=(28, 4, 16, -24))   # 오른손 무기는 허리 뒤로 낮게
anim("run_w", 20, P(0, LIN, **RUN_W) + P(10, IO, body=(-13, None, None)) + P(20, IO, **RUN_W), loop=True, ret=0)

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, data in A.items():
        with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
    print(len(A), "motions ->", os.path.normpath(OUT))
