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


def _fix_euler(moves):
    """팔 키마다 같은 자세의 다른 오일러 표기 중, 앞 키에서 최단 회전에 가장 가깝게 보간되는 것을 고름
    (playerAnimator 는 각을 숫자로 보간하므로 표기에 따라 팔이 엉뚱하게 한 바퀴 돌 수 있음)"""
    import math as _m
    from scipy.spatial.transform import Rotation as _R, Slerp as _S
    def rot(e): return _R.from_euler("ZYX", [e[2], e[1], e[0]], degrees=True)
    for part in ("rightArm", "leftArm"):
        ks = sorted([m for m in moves if part in m], key=lambda m: m["tick"])
        prev = None
        for m in ks:
            a = m[part]
            if "pitch" not in a or "yaw" not in a or "roll" not in a: prev = None; continue
            e = (a["pitch"], a["yaw"], a["roll"])
            if prev is not None:
                Ra, Rb = rot(prev), rot(e)
                sl = _S([0, 1], _R.concatenate([Ra, Rb]))
                cands = []
                for b in (e, (e[0] + 180, 180 - e[1], e[2] + 180)):
                    for dx in (-360, 0, 360):
                        for dy in (-360, 0, 360):
                            for dz in (-360, 0, 360):
                                cands.append((b[0] + dx, b[1] + dy, b[2] + dz))
                def cost(c):
                    w = max((rot(tuple(x + (y - x) * f for x, y in zip(prev, c))) * sl(f).inv()).magnitude() for f in (0.25, 0.5, 0.75))
                    return w + 0.0005 * sum(abs(y - x) for x, y in zip(prev, c))
                e = min(cands, key=cost)
                a["pitch"], a["yaw"], a["roll"] = (round(x, 2) for x in e)
            prev = e
    return moves


def anim(name, end, keys, stop=None, loop=False, ret=0):
    if name.startswith(("vm_", "run_vm", "stance_vm", "guard_vm", "ph_", "run_ph", "stance_ph", "guard_ph")): keys = _fix_euler(keys)
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

# ═════════ 뱀파이어 (석가면, 죠죠 1부 참고 — 올스타 배틀 R 1부 디오 필살기·아이즈 오브 헤븐 디오 플레이 영상의 기술·자세 원칙만) ═════════
# 3차 (모션 렌더러로 보며 다시 만듦). 에픽파이트 바이패드 애니메이션을 뼈대 계산으로 재서 얻은 원칙 (수치만 재고 파일은 옮기지 않음):
#   ① 판정 자세까지는 1틱 만에 튀어나감(EX), 예비 동작은 짧고 작게. 판정 자세를 2~3틱 버틴 뒤 '조금만' 풀린 자세로 오래 머물다 대기로
#   ② 힘은 엉덩이·몸통 회전에서: 한 방에 몸 전체가 40~60° 돌고(연타는 좌우 ±70~80°) 팔은 그 회전 끝에 뻗음. 상체는 판정 1~2틱 뒤에 가장 앞으로 숙여짐(+10~20°)
#   ③ 체중: 때릴 때마다 반 칸쯤 내딛고(엉덩이가 앞으로 0.3~0.4) 엉덩이를 0.05~0.1 낮춘 채 유지 — 몸을 통째로 기울이지 않음
#   ④ 다리는 땅에 붙어 있음: 앞다리 30~50° 앞으로, 뒷다리 20° 뒤로, 무릎을 굽혀 버팀. 몸을 기울이면 다리를 반대로 돌려 발이 그대로 땅에
#   ⑤ 시선 고정: 몸이 돌아도 머리는 상대를 봄 / 안 쓰는 손은 턱 앞 가드
#   ⑥ 달리기: 상체만 30~40° 숙이고 다리는 따로 ±50° 휘두름, 손은 엉덩이 높이에서 작게, 가슴이 다리와 반대로 ±15° 비틀림, 걸음마다 엉덩이가 들썩
# playerAnimator 의 body 는 다리까지 통째로 돌리므로, 땅을 딛는 자세는 G() 로 다리 각을 '세계 기준'으로 적고 몸 기울기만큼 되돌려 발을 땅에 붙임
#   (엉덩이 높이 bodyPos y 도 발끝이 땅에 닿게 자동 계산)


def DF(u, f, bend=None, z=None):
    """맨손용: 위팔 방향 u, 아래팔(손) 방향 f 로 팔 자세 (팔꿈치 각은 u·f 사이 각으로 자동)"""
    import numpy as _np, math as _m
    u = _np.array(u, float); u /= _np.linalg.norm(u)
    f = _np.array(f, float); f /= _np.linalg.norm(f)
    th = _m.acos(max(-1, min(1, float(u @ f))))
    # 거의 편 팔은 굽는 면이 정해지지 않아 키마다 팔이 제멋대로 비틀림 → 팔꿈치가 늘 뒤·아래를 향하는 기준 면을 씀
    r = _np.array([0, 0.5, -1.0]); nr = r - (r @ u) * u
    if _np.linalg.norm(nr) < 1e-3: nr = _np.array([1.0, 0, 0]) - u[0] * u
    nr /= _np.linalg.norm(nr)
    if th < _m.radians(20): n = nr
    else:
        na = f - (f @ u) * u; na /= _np.linalg.norm(na)
        k = min(1.0, (_m.degrees(th) - 20) / 15)
        n = (1 - k) * nr + k * na; n /= _np.linalg.norm(n)
    v = -_m.sin(th) * u + _m.cos(th) * n
    return D(tuple(u), tuple(v), -round(_m.degrees(th), 1), z)


SH_R, SH_L = (0.31, 1.375, 0.0), (-0.31, 1.375, 0.0)   # 어깨 (몸 기준 블록: x 오른쪽, y 발에서 위, -z 앞)


def HT(sh, u, hand, z=None):
    """손 위치로 팔 자세: 위팔 방향 u 로 4픽셀 뻗은 팔꿈치에서 손(hand, 몸 기준 블록 좌표)을 향해 아래팔"""
    import numpy as _np
    u = _np.array(u, float); u /= _np.linalg.norm(u)
    elbow = _np.array(sh) + 0.25 * u
    return DF(tuple(u), tuple(_np.array(hand) - elbow), z=z)


def HR(u, hand, z=None): return HT(SH_R, u, hand, z)
def HL(u, hand, z=None): return HT(SH_L, u, hand, z)


def _leg_low(body, leg, piv_x):
    """다리 한쪽의 가장 낮은 점 높이 (bodyPos 0 기준). playerAnimator·렌더러와 같은 계산"""
    import numpy as _np, math as _m
    def R3(p, y, r):
        p, y, r = _m.radians(p), _m.radians(y), _m.radians(r)
        Rx = _np.array([[1, 0, 0], [0, _m.cos(p), -_m.sin(p)], [0, _m.sin(p), _m.cos(p)]])
        Ry = _np.array([[_m.cos(y), 0, _m.sin(y)], [0, 1, 0], [-_m.sin(y), 0, _m.cos(y)]])
        Rz = _np.array([[_m.cos(r), -_m.sin(r), 0], [_m.sin(r), _m.cos(r), 0], [0, 0, 1]])
        return Rz @ Ry @ Rx
    W = R3(*body)
    R = R3(leg[0], leg[1], leg[2])
    b = _m.radians(leg[3] if len(leg) > 3 else 0)
    RB = _np.array([[1, 0, 0], [0, _m.cos(b), -_m.sin(b)], [0, _m.sin(b), _m.cos(b)]])
    mid = _np.array([0, 6, 0.0])
    low = 9.9
    for x in (-2, 2):
        for zz in (-2, 2):
            for yy in (6, 12):
                v = _np.array([x, yy, zz], float)
                if yy > 6: v = RB @ (v - mid) + mid
                v = R @ v + _np.array([piv_x, 12, 0])
                w = _np.array([-v[0], -v[1], v[2]]) / 16 + _np.array([0, 1.501, 0])
                w = W @ (w - _np.array([0, 0.7, 0])) + _np.array([0, 0.7, 0])
                low = min(low, w[1])
    return low


def G(body=(0, 0, 0), rl=(0, 0, 0), ll=(0, 0, 0), look=(0, 0), sink=0.0, z=0.0, **arms):
    """땅을 딛는 자세. rl·ll = 다리 (세계 기준 앞뒤 각, 바깥 벌림, 무릎 굽힘): 몸 기울기만큼 되돌려 발이 땅에 붙게.
    look = 머리 (위아래, 좌우) — 0 이면 몸이 돌거나 기울어도 정면 수평을 봄. sink = 더 주저앉음, z = 몸을 앞으로 내밈(블록)"""
    import math as _m
    bp = body[0]
    ly = round(-0.7 * body[1], 1)   # 엉덩이만 돌고 발끝은 대체로 앞을 향함 (원칙 ②④)
    # 다리 각으로 적은 걸 '발 위치'로 바꾼 뒤 두 발이 같은 높이(땅)에 닿게 다시 풂 (2마디 IK, 마디 0.375)
    L = 0.375
    def _fk(w):
        p, b = _m.radians(w[0]), _m.radians(w[2])
        return -L * (_m.sin(p) + _m.sin(p + b)), L * (_m.cos(p) + _m.cos(p + b))   # (앞으로, 아래로)
    fr, fl = _fk(rl), _fk(ll)
    h = max(0.5, min(fr[1], fl[1]) - 0.3 * sink)   # 다리 각이 이미 낮춘 높이를 정함, sink 는 조금만 더
    h = min(h, 0.985 * _m.sqrt(max(0.0, (2 * L) ** 2 - max(fr[0] ** 2, fl[0] ** 2))))
    def _ik(f, w, sgn):
        d = min(2 * L * 0.999, _m.hypot(f[0], h))
        bend = 2 * _m.degrees(_m.acos(d / (2 * L)))
        phi = _m.degrees(_m.atan2(f[0], h))   # 발 쪽 방향 (앞 +)
        wp = -(phi + bend / 2)
        return (round(wp + bp, 1), ly, sgn * w[1], round(bend, 1))
    r = _ik(fr, rl, 1); l = _ik(fl, ll, -1)
    low = min(_leg_low(body, r, -1.9), _leg_low(body, l, 1.9))
    out = dict(body=body, head=(bp + look[0], body[1] + look[1], 0), rightLeg=r, leftLeg=l, bodyPos=(0, round(-low, 3), z))
    out.update(arms)
    return out


def AIR(body=(0, 0, 0), rl=(-34, 22, 56), ll=(14, 20, 34), look=(0, 0), **arms):
    """공중 자세 (다리는 몸 기준, 땅에 안 붙임)"""
    out = dict(body=body, head=(body[0] + look[0], body[1] + look[1], 0), rightLeg=(rl[0], 0, rl[1], rl[2]), leftLeg=(ll[0], 0, -ll[1], ll[2]), bodyPos=(0, 0, 0))
    out.update(arms)
    return out


# ── 손 위치 모음 (몸 기준 블록) ──
R_HIP = HR((0.55, -0.65, 0.5), (0.27, 0.93, 0.02))          # 오른손 허리에 짚기
L_HIP = HL((-0.55, -0.65, 0.5), (-0.27, 0.93, 0.02))
L_LOOSE = HL((-0.15, -0.97, 0.12), (-0.37, 0.8, -0.12))      # 왼팔 힘 빼고 늘어뜨림
R_LOOSE = HR((0.15, -0.97, 0.12), (0.37, 0.8, -0.12))
VR_GUARD = HR((0.35, -0.55, -0.75), (0.16, 1.32, -0.42))      # 턱 앞 가드
VL_GUARD = HL((-0.35, -0.55, -0.75), (-0.16, 1.32, -0.42))
R_NECK_HI = HR((0.12, 0.55, -0.83), (0.1, 2.02, -0.52))     # 상대 목을 쥐어 머리 위로
R_NECK_HI2 = HR((0.12, 0.6, -0.8), (0.1, 2.06, -0.5))
L_BACK_LO = HL((-0.5, -0.6, 0.6), (-0.5, 1.0, 0.38))        # 팔을 뒤로 낮게 펼침
R_BACK_LO = HR((0.5, -0.6, 0.6), (0.5, 1.0, 0.38))
R_THRUST = HR((0.06, -0.12, -1), (0.12, 1.3, -0.6))          # 펼친 손끝을 곧게 앞으로
L_THRUST = HL((-0.06, -0.12, -1), (-0.12, 1.3, -0.6))
R_CHAMBER = HR((0.4, -0.65, 0.65), (0.28, 1.02, -0.18))      # 허리로 당김
L_CHAMBER = HL((-0.4, -0.65, 0.65), (-0.28, 1.02, -0.18))
R_TEMPLE = HR((0.85, 0.15, -0.5), (0.19, 1.76, -0.13))       # 관자놀이
L_TEMPLE = HL((-0.85, 0.15, -0.5), (-0.19, 1.76, -0.13))
R_CLAW_FRONT = HR((0.6, -0.45, -0.65), (0.42, 1.25, -0.45))  # 갈고리 손을 가슴 앞 바깥에
L_CLAW_FRONT = HL((-0.6, -0.45, -0.65), (-0.42, 1.3, -0.42))
R_UP_HIGH = HR((0.3, 0.95, -0.1), (0.3, 2.05, -0.15))        # 팔을 머리 위로
R_CHOP_UP = HR((0.6, 0.6, 0.5), (0.45, 1.85, 0.05))          # 수도: 손날을 오른어깨 위로
R_CHOP_DOWN = HR((-0.05, -0.3, -0.95), (-0.25, 0.95, -0.55))
L_BACKHAND_IN = HL((0.6, -0.2, -0.75), (0.3, 1.45, -0.3))    # 손등: 오른어깨 앞까지 감음
L_BACKHAND_OUT = HL((-0.6, 0.0, -0.8), (-0.75, 1.35, -0.4))
L_COLLAR = HL((-0.05, -0.1, -1), (-0.05, 1.45, -0.62))       # 멱살 잡기

# 다리 자세 (세계 기준 앞뒤 각, 벌림, 무릎)
STAND_RL, STAND_LL = (4, 4, 4), (-10, 3, 18)                 # 오른다리에 체중, 왼무릎을 살짝
FIGHT_RL, FIGHT_LL = (18, 8, 22), (-22, 8, 26)               # 낮춘 엇갈린 자세
LUNGE_RL, LUNGE_LL = (24, 8, 12), (-38, 8, 46)               # 왼발 내딛음
LUNGE_RL2, LUNGE_LL2 = (-38, 8, 46), (24, 8, 12)             # 오른발 내딛음
DEEP_RL, DEEP_LL = (32, 8, 10), (-50, 8, 66)                 # 깊게 내딛음
WIDE_RL, WIDE_LL = (8, 20, 34), (-8, 20, 34)                 # 넓게 벌려 주저앉음
KNEEL_RL, KNEEL_LL = (30, 22, 100), (-70, 22, 100)           # 한쪽 무릎

# ── 대기: 키를 다 펴고 선 자세 — 오른손 허리, 왼팔 늘어뜨림, 오른다리에 체중, 턱을 살짝 듦 (참고: 대전 중 선 자세) ──
VM_STANCE = solved(G(body=(3, -14, 0), rl=STAND_RL, ll=STAND_LL, look=(-8, 2), rightArm=R_HIP, leftArm=L_LOOSE))
anim("stance_vm", 100, seq([(0, LIN, VM_STANCE),
                            (25, IO, dict(body=(4, -14, 0), leftArm=HL((-0.17, -0.97, 0.1), (-0.38, 0.81, -0.1)))),
                            (50, IO, dict(body=(3, -14, 0), head=(-7, -18, 4))),
                            (75, IO, dict(body=(4, -14, 0), head=(-6, -14, 2))),
                            (100, IO, VM_STANCE)]), loop=True, ret=0)
# 걷기: 다리·왼팔은 바닐라, 오른손만 허리. 상체를 살짝 젖히고 느긋하게
VM_WALK = dict(body=(5, -8, 0), head=(-3, -8, 0), rightArm=R_HIP)
anim("stance_vm_walk", 40, seq([(0, LIN, VM_WALK), (20, IO, dict(body=(6, -8, 0))), (40, IO, VM_WALK)]), loop=True, ret=0)

# 달리기 (참고: 아이즈 오브 헤븐 디오의 달리기 — 뒤에서 본 장면을 0.067초 간격으로 다시 떼어 봄. 앞선 판은 앞뒤를 거꾸로 읽어 팔을 앞으로 내민 좀비 같았음):
#   상체를 40° 앞으로 숙여 어깨·등이 보이고 고개는 숙인 채 앞을 봄, 두 팔은 몸 뒤 아래로 비스듬히 벌려(날개처럼) 거의 편 채 조금만 흔듦,
#   뒷발은 차고 나서 뒤꿈치를 엉덩이 쪽으로 높이 접어 올림, 앞 무릎을 끌어올려 성큼 내딛음. 20틱에 두 걸음씩, 걸음마다 살짝 뜸
RUN_LEG = [(0, (-42, 20)), (2, (-12, 24)), (4, (32, 10)), (5, (40, 60)), (6, (26, 128)), (8, (-46, 104)), (9, (-52, 46)), (10, (-42, 20))]   # (세계 앞뒤 각, 무릎) 6틱에 뒤꿈치를 높이 접음
def _run_leg(t):
    t %= 10
    for (t0, a), (t1, b) in zip(RUN_LEG, RUN_LEG[1:]):
        if t0 <= t <= t1:
            f = (t - t0) / (t1 - t0)
            return tuple(a[i] + (b[i] - a[i]) * f for i in range(2))
    return RUN_LEG[0][1]
_run = []
RUN_BP = -40
for t in range(0, 21):
    rw, lw = _run_leg(t), _run_leg(t + 5)
    sw = max(-1.0, min(1.0, -(rw[0] - lw[0]) / 90.0))   # 오른다리가 앞이면 +
    body = (RUN_BP, round(-8 * sw, 1), 0)
    rl = (round(rw[0] + RUN_BP, 1), round(5 * sw, 1), 4, round(rw[1], 1)); ll = (round(lw[0] + RUN_BP, 1), round(5 * sw, 1), -4, round(lw[1], 1))
    low = min(_leg_low(body, rl, -1.9), _leg_low(body, ll, 1.9))
    fly = 0.07 if t % 5 == 0 else 0.04 if t % 5 in (1, 4) else 0.0   # 걸음마다 살짝 뜸
    _run.append((t, LIN, dict(body=body, head=(RUN_BP - 6, round(8 * sw, 1), 0), rightLeg=rl, leftLeg=ll, bodyPos=(0, round(-low + fly, 3), 0),
                              rightArm=(round(8 + 8 * sw, 1), 0, 18, -10), leftArm=(round(8 - 8 * sw, 1), 0, -18, -10))))
anim("run_vm", 20, seq(_run), loop=True, ret=0)

# 가드: 상체를 젖힌 채 왼팔뚝을 얼굴 앞에 세워 받아냄, 오른손은 허리 높이에서 받아칠 준비
VM_GUARD = G(body=(4, -20, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(-6, 0), sink=0.02, leftArm=HL((0.25, -0.4, -0.88), (-0.05, 1.62, -0.32)),
             rightArm=HR((0.45, -0.7, 0.4), (0.28, 1.0, -0.32)))
guard_loop("guard_vm", VM_GUARD, dict(body=(5, -20, 0)))


def vstrike(end, frames, hold_to=None, settle=None):
    """원칙 ①: 대기 → (짧은 예비) → 판정 1틱 만에 → hold_to 까지 버팀 → settle(조금 풀린 자세) → end 에 대기"""
    fr = [(0, LIN, VM_STANCE)] + frames
    t_hit, _, hit = frames[-1]
    if hold_to: fr.append((hold_to, LIN, hit))
    if settle: fr.append(settle)
    fr.append((end, IO, VM_STANCE))
    return seq(fr)


# ── 평타 (참고: KUA! '갈비뼈 대여섯은 부러졌구나' 파생 — 0.17초 간격으로 떼어 봄). 판정 4틱 ──
#   뛰어들며 무릎 → 몸을 던지듯 기울여 오른팔을 크게 가로로 휘두름 → 반대로 왼팔을 바깥으로 후려침 → 두 갈고리 손으로 내려 할큄 →
#   막타: 무릎을 끌어올렸다가 몸을 젖히며 곧게 내지르는 앞차기(날려보냄)
def _hop(pose, dy, dz=None):
    bp = pose["bodyPos"]
    return dict(pose, bodyPos=(bp[0], round(bp[1] + dy, 3), bp[2] if dz is None else dz))
VM_KNEE = _hop(dict(G(body=(6, -12, 0), rl=(-6, 10, 18), ll=(-6, 10, 18), look=(-4, 0), leftArm=L_COLLAR, rightArm=R_CHAMBER), rightLeg=(-100, 0, 10, 106)), 0.12, 0.18)
anim("vm_1", 10, vstrike(10, [   # 뛰어들며 무릎: 왼손으로 멱살을 당기며 오른 무릎을 명치에
    (2, IO, G(body=(-12, -20, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(8, 4), sink=0.12, rightArm=R_CHAMBER, leftArm=L_COLLAR)),
    (4, EX, VM_KNEE),
], hold_to=6, settle=(8, IO, G(body=(2, -14, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(0, 0), sink=0.05, z=0.1, leftArm=L_COLLAR, rightArm=R_CHAMBER))))
anim("vm_2", 10, vstrike(10, [   # 오른팔 가로 휘두르기: 몸을 오른쪽으로 감았다가 앞으로 던지듯 기울이며 오른팔을 크게 가로질러 휘두름
    (2, IO, G(body=(0, -50, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(0, 10), sink=0.06, rightArm=HR((0.85, 0.0, 0.5), (0.7, 1.4, 0.45)), leftArm=VL_GUARD)),
    (4, EX, G(body=(-24, 40, 0), rl=DEEP_RL, ll=DEEP_LL, look=(10, -10), sink=0.12, z=0.22, rightArm=HR((-0.3, 0.05, -0.95), (-0.55, 1.35, -0.55)), leftArm=L_BACK_LO)),
], hold_to=6, settle=(8, IO, G(body=(-18, 32, 0), rl=DEEP_RL, ll=DEEP_LL, look=(8, -8), sink=0.1, z=0.16, rightArm=HR((-0.3, 0.05, -0.95), (-0.55, 1.35, -0.55)), leftArm=L_BACK_LO))))
anim("vm_3", 10, vstrike(10, [   # 왼팔 되받아치기: 왼손을 오른어깨 앞까지 감았다가 몸을 반대로 크게 틀며 바깥으로 후려침
    (2, IO, G(body=(-10, 34, 0), rl=DEEP_RL, ll=DEEP_LL, look=(6, -10), sink=0.1, z=0.12, leftArm=L_BACKHAND_IN, rightArm=R_CHAMBER)),
    (4, EX, G(body=(-22, -42, 0), rl=LUNGE_RL2, ll=LUNGE_LL2, look=(8, 12), sink=0.12, z=0.22, leftArm=HL((-0.85, 0.05, -0.55), (-0.9, 1.4, -0.3)), rightArm=R_CHAMBER)),
], hold_to=6, settle=(8, IO, G(body=(-16, -34, 0), rl=LUNGE_RL2, ll=LUNGE_LL2, look=(6, 10), sink=0.1, z=0.16, leftArm=HL((-0.85, 0.05, -0.55), (-0.9, 1.4, -0.3)), rightArm=VR_GUARD))))
anim("vm_4", 10, vstrike(10, [   # 두 갈고리 손 내려 할퀴기: 몸을 펴며 두 손을 머리 위로 들었다가 숙이며 가슴을 할퀴어 내림
    (2, IO, G(body=(10, 0, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(-12, 0), sink=0.02, rightArm=HR((0.5, 0.85, -0.2), (0.5, 2.0, -0.3)), leftArm=HL((-0.5, 0.85, -0.2), (-0.5, 2.0, -0.3)))),
    (4, EX, G(body=(-28, 4, 0), rl=WIDE_RL, ll=WIDE_LL, look=(14, 0), sink=0.16, z=0.15, rightArm=HR((0.2, -0.4, -0.9), (0.25, 0.85, -0.62)), leftArm=HL((-0.2, -0.4, -0.9), (-0.25, 0.85, -0.62)))),
], hold_to=6, settle=(8, IO, G(body=(-22, 4, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, 0), sink=0.14, z=0.12, rightArm=HR((0.25, -0.5, -0.83), (0.3, 0.8, -0.55)), leftArm=HL((-0.25, -0.5, -0.83), (-0.3, 0.8, -0.55))))))
VM_KICK_CH = dict(G(body=(8, -10, 0), rl=(-4, 8, 14), ll=(-4, 8, 14), look=(-6, 0), rightArm=HR((0.6, -0.6, 0.5), (0.65, 0.95, 0.3)), leftArm=VL_GUARD), rightLeg=(-87, 0, 6, 112))
VM_KICK = dict(G(body=(20, -6, 0), rl=(-8, 8, 16), ll=(-8, 8, 16), look=(-14, 0), z=0.1, rightArm=HR((0.65, -0.55, 0.52), (0.75, 0.95, 0.35)), leftArm=HL((-0.65, -0.55, 0.52), (-0.75, 0.95, 0.35))), rightLeg=(-78, 0, 6, 0))
anim("vm_5", 17, vstrike(17, [   # 막타 앞차기: 무릎을 가슴까지 끌어올렸다가(3) 몸을 젖히며 발바닥을 곧게 내질러 날려보냄(6)
    (3, IO, VM_KICK_CH),
    (6, EX, VM_KICK),
], hold_to=10, settle=(13, IO, dict(G(body=(12, -8, 0), rl=(-6, 8, 14), ll=(-6, 8, 14), look=(-8, 0), rightArm=HR((0.6, -0.6, 0.5), (0.65, 0.95, 0.3)), leftArm=VL_GUARD), rightLeg=(-60, 0, 6, 70)))))
anim("vm_rise", 11, vstrike(11, [   # 띄우기 (참고: '이 디오를 쓰러트릴 수 있겠느냐' — 손바닥을 대 얼리고 → 깊게 웅크렸다가 → 솟구치며 팔을 곧게 위로)
    (1, IO, G(body=(-6, 22, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(2, -6), sink=0.06, z=0.1, rightArm=HR((0.1, -0.2, -0.97), (0.12, 1.3, -0.62)), leftArm=VL_GUARD)),
    (2, IO, G(body=(-26, -30, 0), rl=WIDE_RL, ll=WIDE_LL, look=(16, 10), sink=0.24, rightArm=HR((0.4, -0.8, 0.45), (0.45, 0.7, 0.1)), leftArm=VL_GUARD)),
    (4, EX, _hop(G(body=(12, 20, 0), rl=(6, 6, 2), ll=(-24, 6, 8), look=(-30, -6), rightArm=HR((0.1, 0.98, -0.15), (0.12, 2.2, -0.2)), leftArm=L_BACK_LO), 0.22)),
], hold_to=7))
# 공중 평타: 벌린 다리 + 손톱 (오른손 가로 → 왼손 가로 → 두 손 엑스자로 내려 할큄)
VM_AIR0 = solved(AIR(body=(0, -12, 0), look=(0, 0), rightArm=R_CLAW_FRONT, leftArm=VL_GUARD))


def vma(t_hit, end, frames, hold=2):
    fr = [(0, LIN, VM_AIR0)] + frames
    hit = next((p for t, e, p in frames if t == t_hit), None)
    if hit and hold: fr.append((t_hit + hold, LIN, hit))
    fr.append((end, IO, VM_AIR0))
    return seq(fr)


anim("vm_a1", 10, vma(4, 10, [
    (2, IO, AIR(body=(0, -40, 0), look=(0, 10), rightArm=HR((0.8, 0.2, 0.55), (0.75, 1.5, 0.3)), leftArm=VL_GUARD)),
    (4, EX, AIR(body=(-10, 26, 0), rl=(-50, 20, 80), look=(4, -8), rightArm=HR((-0.2, -0.05, -0.97), (-0.45, 1.35, -0.45)), leftArm=VL_GUARD)),
]))
anim("vm_a2", 10, vma(4, 10, [
    (2, IO, AIR(body=(0, 28, 0), look=(0, -8), leftArm=HL((-0.8, 0.2, 0.55), (-0.75, 1.5, 0.3)), rightArm=VR_GUARD)),
    (4, EX, AIR(body=(-10, -30, 0), ll=(-40, 20, 70), look=(4, 10), leftArm=HL((0.2, -0.05, -0.97), (0.45, 1.35, -0.45)), rightArm=VR_GUARD)),
]))
anim("vm_a3", 10, vma(4, 10, [
    (2, IO, AIR(body=(18, 0, 0), look=(-6, 0), rightArm=HR((0.5, 0.85, 0.0), (0.45, 2.0, -0.2)), leftArm=HL((-0.5, 0.85, 0.0), (-0.45, 2.0, -0.2)))),
    (4, EX, AIR(body=(-24, 0, 0), rl=(10, 20, 40), ll=(20, 20, 30), look=(14, 0), rightArm=HR((-0.25, -0.35, -0.9), (-0.15, 0.85, -0.55)), leftArm=HL((0.25, -0.35, -0.9), (0.15, 0.85, -0.55)))),
]))
# 다운 슬램 (참고: 'URYYY!!' — 상대 머리를 움켜쥐고 몸을 웅크려 덮쳤다가 온몸으로 바닥에 처박음): 두 손을 머리 위 앞으로 뻗어 움켜쥐고
#   무릎을 당겨 웅크림(2) → 상체를 앞으로 고꾸라뜨리며 두 손으로 아래 앞을 내리꽂음(4) → 그대로 버팀
anim("vm_slam", 15, seq([(0, LIN, VM_AIR0),
     (2, OQ, AIR(body=(14, 0, 0), rl=(-72, 15, 112), ll=(-62, 15, 104), look=(-12, 0), rightArm=HR((0.2, 0.75, -0.62), (0.15, 1.95, -0.58)), leftArm=HL((-0.2, 0.75, -0.62), (-0.15, 1.95, -0.58)))),
     (4, EX, AIR(body=(-56, 0, 0), rl=(18, 15, 64), ll=(8, 15, 52), look=(32, 0), rightArm=HR((0.15, -0.2, -0.96), (0.13, 0.8, -0.76)), leftArm=HL((-0.15, -0.2, -0.96), (-0.13, 0.8, -0.76)))),
     (9, LIN, dict(body=(-52, 0, 0))),
     (15, IO, VM_STANCE)]))

# ── 잡아 들어 올리는 공용 자세 (무다 연타·흡혈 돌진, 참고: '무다무다'·'빈약하다' — 목을 쥐어 머리 위로, 왼팔은 뒤로 낮게) ──
VM_DASH = G(body=(-30, -4, 0), rl=(-40, 4, 30), ll=(30, 4, 40), look=(-10, 0), sink=0.1, rightArm=HR((0.3, -0.9, 0.2), (0.3, 0.88, 0.2)), leftArm=HL((-0.3, -0.9, 0.2), (-0.3, 0.88, 0.2)))
VM_RAM = G(body=(-26, -8, 0), rl=(30, 8, 20), ll=(-44, 8, 60), look=(-6, 4), sink=0.18, z=0.25,
           rightArm=HR((0.3, -0.5, -0.8), (0.15, 1.0, -0.62)), leftArm=HL((-0.3, -0.5, -0.8), (-0.15, 1.0, -0.62)))   # 어깨로 들이받음
VM_LIFT = G(body=(4, -6, 0), rl=(22, 12, 18), ll=(-30, 12, 40), look=(-20, 4), sink=0.08, z=0.1, rightArm=R_NECK_HI, leftArm=L_BACK_LO)
VM_LIFT2 = G(body=(7, -6, 0), rl=(22, 12, 18), ll=(-30, 12, 40), look=(-22, 4), sink=0.1, z=0.1, rightArm=R_NECK_HI2, leftArm=L_BACK_LO)
VM_SLAMDOWN = G(body=(-34, -6, 0), rl=(34, 10, 14), ll=(-48, 10, 70), look=(16, 4), sink=0.2, z=0.3,
                rightArm=HR((0.1, -0.5, -0.86), (0.1, 0.45, -0.6)), leftArm=L_BACK_LO)   # 땅에 내리꽂음


def lift_hold(t0, t1, every=3):
    """들어 올린 채 every 틱마다 들썩 (손가락을 꽂아 흡혈)"""
    out = []
    for k, t in enumerate(range(t0, t1 + 1, every)):
        out.append((t, EX if k % 2 else IO, VM_LIFT2 if k % 2 else VM_LIFT))
    return out


# 흡혈 돌진: 낮게 달려들어 들이받고 → 목을 쥐어 들어 올린 채 다섯 번 흡혈 → 옆으로 내던짐 (들이받기 6, 들기 9, 던짐 35)
anim("vm_lunge", 42, seq([(0, LIN, VM_STANCE), (3, OQ, VM_DASH), (6, EX, VM_RAM), (9, OQ, VM_LIFT)] + lift_hold(11, 30, 4)
     + [(33, IO, G(body=(10, 28, 0), rl=(22, 12, 18), ll=(-30, 12, 40), look=(-16, -10), sink=0.08, rightArm=HR((-0.3, 0.75, -0.6), (-0.4, 1.95, -0.25)), leftArm=L_BACK_LO)),
        (35, EX, G(body=(-6, -46, 0), rl=(30, 12, 18), ll=(-36, 12, 46), look=(4, 20), sink=0.12, rightArm=HR((0.8, 0.2, 0.55), (0.85, 1.2, 0.25)), leftArm=VL_GUARD)),
        (38, LIN, dict(body=(-4, -44, 0))),
        (42, IO, VM_STANCE)]))
# 내리꽂기 흡혈 (공중): 머리부터 고꾸라져 내리꽂히며 상대를 깔아뭉개고, 한쪽 무릎을 꿇은 채 손가락을 꽂아 흡혈
VM_PIN = G(body=(-30, -6, 0), rl=KNEEL_RL, ll=KNEEL_LL, look=(20, 4), sink=0.0, rightArm=HR((0.1, -0.7, -0.7), (0.1, 0.55, -0.62)), leftArm=HL((-0.4, -0.7, -0.55), (-0.3, 0.75, -0.5)))
anim("vm_dive", 32, seq([(0, LIN, VM_AIR0),
     (3, OQ, AIR(body=(-76, 0, 0), rl=(30, 14, 20), ll=(24, 14, 16), look=(30, 0), rightArm=HR((0.15, -0.3, -0.94), (0.2, 1.1, -0.6)), leftArm=HL((-0.15, -0.3, -0.94), (-0.2, 1.1, -0.6)))),
     (8, EX, VM_PIN),
     (14, IO, dict(body=(-26, -6, 0))), (19, EX, dict(body=(-32, -6, 0))), (24, IO, dict(body=(-26, -6, 0))),
     (32, IO, VM_STANCE)]))
# 손끝 찌르기 (평타 중): 허리로 당겼다가 손끝을 창처럼 쭉 (판정 3)
anim("vm_stab", 10, vstrike(10, [
    (1, IO, G(body=(2, -44, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(0, 8), sink=0.06, rightArm=R_CHAMBER, leftArm=HL((-0.1, -0.1, -1), (-0.1, 1.35, -0.6)))),
    (3, EX, G(body=(-12, 28, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(4, -6), sink=0.1, z=0.2, rightArm=R_THRUST, leftArm=L_BACK_LO)),
], hold_to=6, settle=(8, IO, G(body=(-8, 20, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(2, -4), sink=0.08, z=0.12, rightArm=R_THRUST, leftArm=VL_GUARD))))

# 기화냉동법 (참고: 낮게 파고들어 오른손바닥을 대면 상대가 하얗게 얼어 떠오름 → 손을 들어 올렸다가 내던짐) 판정 4, 들어올림 8~18, 던짐 21
anim("vm_freeze", 32, seq([(0, LIN, VM_STANCE),
     (2, IO, G(body=(-4, -36, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(2, 6), sink=0.1, rightArm=R_CHAMBER, leftArm=VL_GUARD)),
     (4, EX, G(body=(-20, 22, 0), rl=DEEP_RL, ll=DEEP_LL, look=(10, -4), sink=0.18, z=0.3, rightArm=HR((0.1, -0.35, -0.93), (0.1, 1.05, -0.62)), leftArm=L_BACK_LO)),
     (8, IO, G(body=(-12, 18, 0), rl=DEEP_RL, ll=DEEP_LL, look=(4, -4), sink=0.16, z=0.26, rightArm=HR((0.1, -0.15, -0.98), (0.1, 1.25, -0.62)), leftArm=L_BACK_LO)),
     (14, IO, G(body=(12, 10, 0), rl=(20, 14, 20), ll=(-26, 14, 36), look=(-26, -2), sink=0.06, z=0.12, rightArm=HR((0.12, 0.6, -0.8), (0.12, 2.05, -0.45)), leftArm=L_BACK_LO)),
     (18, LIN, dict(body=(14, 10, 0))),
     (21, EX, G(body=(-10, -30, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(6, 12), sink=0.1, z=0.15, rightArm=HR((0.5, -0.2, -0.85), (0.6, 1.0, -0.4)), leftArm=VL_GUARD)),
     (25, LIN, dict(body=(-8, -30, 0))),
     (32, IO, VM_STANCE)]))
# 얼음 손톱 (평타 중): 오른손을 왼어깨 위로 감았다가 손등 쪽으로 사선 할퀴기
anim("vm_iceclaw", 12, vstrike(12, [
    (2, IO, G(body=(0, 28, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(0, -8), sink=0.06, rightArm=HR((-0.4, 0.55, -0.7), (-0.35, 1.8, -0.2)), leftArm=VL_GUARD)),
    (4, EX, G(body=(-10, -36, 0), rl=LUNGE_RL2, ll=LUNGE_LL2, look=(4, 10), sink=0.1, z=0.15, rightArm=HR((0.6, -0.4, -0.7), (0.62, 0.95, -0.35)), leftArm=VL_GUARD)),
], hold_to=6, settle=(9, IO, G(body=(-6, -26, 0), rl=LUNGE_RL2, ll=LUNGE_LL2, look=(2, 8), sink=0.08, z=0.1, rightArm=HR((0.6, -0.4, -0.7), (0.62, 0.95, -0.35)), leftArm=VL_GUARD))))
# 얼려 내리꽂기 (공중): 두 손으로 붙잡아 얼린 뒤 머리 위로 들었다가 내리꽂음
anim("vm_icedrop", 18, seq([(0, LIN, VM_AIR0),
     (2, OQ, AIR(body=(-10, 0, 0), look=(6, 0), rightArm=HR((0.1, 0.0, -1), (0.12, 1.5, -0.6)), leftArm=HL((-0.1, 0.0, -1), (-0.12, 1.5, -0.6)))),
     (6, IO, AIR(body=(22, 0, 0), look=(-20, 0), rightArm=HR((0.25, 0.95, 0.1), (0.15, 2.05, 0.05)), leftArm=HL((-0.25, 0.95, 0.1), (-0.15, 2.05, 0.05)))),
     (8, EX, AIR(body=(-56, 0, 0), rl=(30, 20, 40), ll=(20, 20, 30), look=(24, 0), rightArm=HR((0.15, -0.4, -0.9), (0.12, 0.85, -0.55)), leftArm=HL((-0.15, -0.4, -0.9), (-0.12, 0.85, -0.55)))),
     (12, LIN, dict(body=(-52, 0, 0))),
     (18, IO, VM_STANCE)]))

# 냉동 반격 (참고: '순식간에 얼린 것이다'): 상체를 젖힌 채 오른손바닥을 앞으로 내밀고 1.5초 기다림
VM_COUNTER = G(body=(8, -16, 0), rl=STAND_RL, ll=STAND_LL, look=(-8, 0), rightArm=HR((0.12, -0.2, -0.97), (0.14, 1.42, -0.6)), leftArm=L_HIP)
anim("vm_counter", 32, seq([(0, LIN, VM_STANCE), (3, OQ, VM_COUNTER), (16, IO, dict(body=(10, -16, 0))), (30, IO, dict(body=(8, -16, 0))), (32, IO, VM_STANCE)]))
anim("vm_counter_hit", 22, seq([(0, LIN, VM_COUNTER),
     (2, EX, G(body=(-12, 24, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(6, -4), sink=0.1, z=0.25, rightArm=R_THRUST, leftArm=L_BACK_LO)),
     (10, LIN, dict(body=(-10, 24, 0))),
     (14, IO, VM_COUNTER),
     (22, IO, VM_STANCE)]))

# 괴력 연격 (참고: KUA! — 짧게 뛰어들며 무릎). 맞으면 1.5초 동안 기술 카드가 선택지 넷으로 바뀌고(ABA 식) 1~4 로 고름
#   무릎 5 → 10~34 갈고리 손을 펼친 채 고르기를 기다림 (살짝 숨 쉼) → 40 대기
VM_KUA = dict(G(body=(8, -16, 0), rl=(0, 0, 0), ll=(-6, 10, 18), look=(-4, 0), leftArm=L_COLLAR, rightArm=R_CHAMBER), rightLeg=(-100, 0, 10, 106))
VM_KUA_WAIT = G(body=(4, -16, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(-4, 0), sink=0.06, rightArm=R_CLAW_FRONT, leftArm=L_CLAW_FRONT)   # 파생 대기
VM_KUA_WAIT2 = G(body=(6, -16, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(-6, 0), sink=0.06, rightArm=R_CLAW_FRONT, leftArm=L_CLAW_FRONT)
anim("vm_kua", 40, seq([(0, LIN, VM_STANCE),
     (2, IO, G(body=(-12, -18, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(8, 0), sink=0.12, rightArm=R_CHAMBER, leftArm=L_CHAMBER)),
     (5, EX, VM_KUA), (8, LIN, dict(body=(6, -16, 0))),
     (11, IO, VM_KUA_WAIT), (20, IO, VM_KUA_WAIT2), (28, IO, VM_KUA_WAIT), (34, LIN, VM_KUA_WAIT),
     (40, IO, VM_STANCE)]))
# ④ 목 물어 흡혈: 목을 움켜쥐고(3) 들어 올려(6) 세 번 손가락을 꽂아 빨아들임(9·13·17) → 옆으로 내던짐(22)
anim("vm_kua_d", 28, seq([(0, LIN, VM_KUA_WAIT),
     (3, EX, G(body=(-8, -8, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(2, 2), sink=0.08, z=0.15, rightArm=HR((0.1, 0.0, -1), (0.1, 1.5, -0.62)), leftArm=L_BACK_LO)),
     (6, OQ, VM_LIFT)] + lift_hold(9, 17, 4)
     + [(20, IO, G(body=(8, 20, 0), rl=(22, 12, 18), ll=(-30, 12, 40), look=(-16, -10), sink=0.08, rightArm=HR((0.0, 0.85, -0.5), (-0.1, 2.0, -0.35)), leftArm=L_BACK_LO)),
        (22, EX, G(body=(-6, -40, 0), rl=(30, 12, 18), ll=(-36, 12, 46), look=(4, 20), sink=0.12, rightArm=HR((0.75, 0.1, -0.65), (0.85, 1.25, -0.2)), leftArm=VL_GUARD)),
        (28, IO, VM_STANCE)]))
anim("vm_kua_l", 18, seq([(0, LIN, VM_KUA_WAIT),   # 약: 수도(3) → 손등(8)
     (2, IO, G(body=(2, -42, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(0, 6), sink=0.05, rightArm=R_CHOP_UP, leftArm=VL_GUARD)),
     (3, EX, G(body=(-12, 20, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(4, -4), sink=0.08, z=0.15, rightArm=R_CHOP_DOWN, leftArm=VL_GUARD)),
     (6, IO, G(body=(0, 26, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(0, -8), sink=0.06, z=0.1, leftArm=L_BACKHAND_IN, rightArm=VR_GUARD)),
     (8, EX, G(body=(-12, -36, 0), rl=LUNGE_RL2, ll=LUNGE_LL2, look=(4, 10), sink=0.1, z=0.22, leftArm=L_BACKHAND_OUT, rightArm=VR_GUARD)),
     (12, LIN, dict(body=(-10, -32, 0))), (18, IO, VM_STANCE)]))
anim("vm_kua_m", 24, seq([(0, LIN, VM_KUA_WAIT),   # 중: 무릎(3) → 얼리는 손바닥(9) → 올려치기(14)
     (3, EX, VM_KUA),
     (7, IO, G(body=(-4, -30, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(2, 4), sink=0.08, rightArm=R_CHAMBER, leftArm=L_CHAMBER)),
     (9, EX, G(body=(-14, -36, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(6, 8), sink=0.1, z=0.15, leftArm=HL((-0.1, -0.1, -1), (-0.1, 1.4, -0.62)), rightArm=R_CHAMBER)),
     (12, IO, G(body=(-18, -10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, 2), sink=0.12, rightArm=HR((0.3, -0.85, 0.4), (0.38, 0.75, -0.25)), leftArm=VL_GUARD)),
     (14, EX, G(body=(14, 8, 0), rl=(8, 6, 4), ll=(-18, 6, 10), look=(-26, -2), rightArm=R_UP_HIGH, leftArm=L_BACK_LO)),
     (18, LIN, dict(body=(16, 8, 0))), (24, IO, VM_STANCE)]))
anim("vm_kua_h", 28, seq([(0, LIN, VM_KUA_WAIT),   # 강: 머리를 움켜쥐고(3) 들어 올렸다가(8~12) 땅에 내리꽂음(14)
     (3, EX, G(body=(-6, -10, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(2, 2), sink=0.08, z=0.15, rightArm=HR((0.1, 0.0, -1), (0.1, 1.55, -0.62)), leftArm=L_BACK_LO)),
     (8, IO, G(body=(20, -6, 0), rl=(22, 12, 18), ll=(-30, 12, 40), look=(-30, 2), sink=0.06, z=0.1, rightArm=HR((0.25, 0.9, -0.35), (0.2, 2.1, -0.15)), leftArm=L_BACK_LO)),
     (12, LIN, dict(body=(22, -6, 0))),
     (14, EX, VM_SLAMDOWN),
     (20, LIN, dict(body=(-30, -6, 0))), (28, IO, VM_STANCE)]))

# 재생: 상처 난 가슴에 오른손을 대고 고개를 젖힘
anim("vm_regen", 20, seq([(0, LIN, VM_STANCE),
     (5, IO, G(body=(12, -8, 0), rl=STAND_RL, ll=STAND_LL, look=(-30, 0), rightArm=HR((0.3, -0.75, -0.55), (-0.08, 1.22, -0.2)), leftArm=L_LOOSE)),
     (14, LIN, dict(body=(14, -8, 0))),
     (20, IO, VM_STANCE)]))
# 피의 하인: 손바닥을 위로 펼친 오른손을 천천히 들어 올리며 '일어나라' (상체를 젖히고 내려다봄)
anim("vm_thrall", 24, seq([(0, LIN, VM_STANCE),
     (5, IO, G(body=(6, 10, 0), rl=STAND_RL, ll=STAND_LL, look=(-6, -4), rightArm=HR((0.3, -0.6, -0.75), (0.32, 1.1, -0.6)), leftArm=L_HIP)),
     (12, IO, G(body=(10, 10, 0), rl=STAND_RL, ll=STAND_LL, look=(-14, -4), rightArm=HR((0.3, -0.1, -0.95), (0.35, 1.6, -0.55)), leftArm=L_HIP)),
     (18, LIN, dict(body=(11, 10, 0))),
     (24, IO, VM_STANCE)]))
# 육아 지배: 오른손을 이마에 댔다가(육아를 떼어 냄) 두 손가락으로 상대를 향해 튕겨 날림 (7)
anim("vm_budshot", 18, seq([(0, LIN, VM_STANCE),
     (3, IO, G(body=(4, -10, 0), rl=STAND_RL, ll=STAND_LL, look=(6, 0), rightArm=HR((0.6, 0.05, -0.8), (0.05, 1.82, -0.22)), leftArm=L_LOOSE)),
     (7, EX, G(body=(-6, 20, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(0, -8), sink=0.06, z=0.12, rightArm=HR((0.05, 0.1, -1), (0.08, 1.48, -0.62)), leftArm=L_LOOSE)),
     (12, LIN, dict(body=(-4, 20, 0))),
     (18, IO, VM_STANCE)]))

# 혈안 광선 (참고: 공열안자경 · 아이즈 오브 헤븐 눈빛 광선 — 프레임을 0.17초 간격으로 떼어 봄)
#   0~2 몸을 틀며 낮춤, 오른 갈고리 손을 앞 아래로 · 4 손을 얼굴 옆으로 올리고 고개를 숙임(모음) · 7 고개를 확 들어 노려봄 ·
#   8 두 팔을 머리 위 양옆으로 활짝 · 9 확 웅크리며 두 팔을 아래로 벌림 · 10 발사: 깊게 내딛고 상체를 낮게 숙여 고개만 들고 두 팔을 뒤로 뿌림 →
#   20까지 낮게 버팀 → 26 대기. (서버: 4~10 모음, 10 발사)
R_FACE_CLAW = HR((0.75, -0.2, -0.65), (0.22, 1.72, -0.3))
VM_BEAM_TURN = G(body=(-8, 34, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(6, -20), sink=0.1, rightArm=HR((0.55, -0.6, -0.6), (0.55, 0.92, -0.5)), leftArm=VL_GUARD)
VM_BEAM_CHARGE = G(body=(-4, 22, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(30, -18), sink=0.08, rightArm=R_FACE_CLAW, leftArm=L_CHAMBER)
VM_BEAM_GLARE = G(body=(-2, 16, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(-6, -14), sink=0.08, rightArm=R_FACE_CLAW, leftArm=L_CHAMBER)
VM_BEAM_SPREAD = G(body=(10, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-8, 0), sink=0.04, rightArm=HR((0.8, 0.55, 0.1), (0.7, 2.0, 0.0)), leftArm=HL((-0.8, 0.55, 0.1), (-0.7, 2.0, 0.0)))
VM_BEAM_CROUCH = G(body=(-26, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(14, 0), sink=0.2, rightArm=HR((0.75, -0.6, 0.2), (0.8, 0.8, 0.0)), leftArm=HL((-0.75, -0.6, 0.2), (-0.8, 0.8, 0.0)))
VM_BEAM_FIRE = G(body=(-42, -4, 0), rl=(36, 8, 6), ll=(-54, 8, 74), look=(-4, 0), sink=0.16, z=0.25, rightArm=HR((0.45, -0.45, 0.77), (0.55, 1.05, 0.62)), leftArm=HL((-0.45, -0.45, 0.77), (-0.55, 1.05, 0.62)))
anim("vm_beam", 26, seq([(0, LIN, VM_STANCE), (2, IO, VM_BEAM_TURN), (4, IO, VM_BEAM_CHARGE), (6, LIN, dict(body=(-5, 22, 0))),
                         (7, EX, VM_BEAM_GLARE), (8, EX, VM_BEAM_SPREAD), (9, EX, VM_BEAM_CROUCH),
                         (10, EX, VM_BEAM_FIRE), (14, LIN, dict(body=(-40, -4, 0))), (20, LIN, dict(body=(-38, -4, 0))), (26, IO, VM_STANCE)]))
# 공중: 고개를 젖혔다가(3, 모음) 확 숙이며 아래를 내려다보고 두 팔을 뒤로 뿌리며 쏨(8)
VM_BEAM_AIR = AIR(body=(-44, -6, 0), look=(40, 0), rightArm=HR((0.45, -0.45, 0.77), (0.55, 1.05, 0.62)), leftArm=HL((-0.45, -0.45, 0.77), (-0.55, 1.05, 0.62)))
anim("vm_beam_air", 24, seq([(0, LIN, VM_AIR0), (3, IO, AIR(body=(12, -6, 0), look=(-30, 0), rightArm=R_FACE_CLAW, leftArm=L_CHAMBER)),
                             (6, LIN, dict(body=(14, -6, 0))),
                             (8, EX, VM_BEAM_AIR), (18, LIN, dict(body=(-42, -6, 0))), (24, IO, VM_STANCE)]))
anim("vm_beam_quick", 11, vstrike(11, [   # 단발 (평타 중): 오른손을 관자놀이에 대며 고개를 앞으로 확 내밀어 쏨 (4)
    (2, IO, G(body=(2, -14, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(18, 0), sink=0.04, rightArm=R_TEMPLE, leftArm=VL_GUARD)),
    (4, EX, G(body=(-10, -12, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(2, 0), sink=0.08, z=0.12, rightArm=R_TEMPLE, leftArm=L_BACK_LO)),
], hold_to=7))

# 무다 연타: 낮게 달려들어(1~3) 어깨로 들이받아 띄우고(4) → 목을 쥐어 머리 위로(7) → 3틱마다 손가락을 꽂음(9~30) → 머리 위로 휘둘러(36) 땅에 내리꽂음(39)
anim("vm_muda", 50, seq([(0, LIN, VM_STANCE), (2, OQ, VM_DASH), (4, EX, VM_RAM), (7, OQ, VM_LIFT)] + lift_hold(9, 32, 3)
     + [(36, IO, G(body=(24, -4, 0), rl=(22, 12, 18), ll=(-30, 12, 40), look=(-34, 0), sink=0.04, z=0.1, rightArm=HR((0.2, 0.9, 0.38), (0.15, 2.1, 0.25)), leftArm=L_BACK_LO)),
        (39, EX, VM_SLAMDOWN),
        (44, LIN, dict(body=(-30, -6, 0))),
        (50, IO, VM_STANCE)]))

# ── 각성 '혈광 해방' (116틱 = 자료 영상 첫 등장 장면 5.8초, 0.29초 간격으로 떼어 봄. 구도는 컷신 vm_awaken) ──
#   0~17 만화 칸 속 전신: 등을 반쯤 돌린 채 서 있다가 어깨 너머로 천천히 고개를 돌림 →
#   18~33 얼굴 옆 클로즈업: 고개를 뒤로 젖히고 외침 ("해는 저물었다. 네놈의 생명도 저물 때다!") →
#   34~39 오른손으로 가슴을 쓸어내림 → 40~112 발밑에서 올려다보는 구도: 키를 다 펴고 상체를 조금 앞으로 숙여 내려다봄,
#   두 팔은 힘을 빼고 몸에서 조금 떨어뜨려 늘어뜨린 채 손가락을 벌림, 고개를 비스듬히 기울임 (아주 천천히 숨 쉬듯)
VM_AW_BACK = dict(G(body=(4, -8, 0), rl=STAND_RL, ll=STAND_LL, look=(-4, 0), rightArm=R_LOOSE, leftArm=L_LOOSE))
VM_AW_TURN = dict(VM_AW_BACK); VM_AW_TURN["head"] = (VM_AW_BACK["head"][0] - 4, VM_AW_BACK["head"][1] - 55, 0)   # 오른어깨 너머로 (뒤쪽 카메라를) 돌아봄
VM_AW_ROAR = G(body=(16, -6, 0), rl=(6, 9, 8), ll=(-6, 9, 8), look=(-58, 0), rightArm=HR((0.35, -0.9, 0.2), (0.55, 0.74, 0.1)), leftArm=HL((-0.35, -0.9, 0.2), (-0.55, 0.74, 0.1)))
VM_AW_HANG = dict(G(body=(-8, 0, 0), rl=(4, 9, 4), ll=(-6, 9, 8), look=(24, 0), rightArm=HR((0.45, -0.88, 0.08), (0.66, 0.74, -0.08)), leftArm=HL((-0.45, -0.88, 0.08), (-0.66, 0.74, -0.08))))
VM_AW_HANG["head"] = (VM_AW_HANG["head"][0], 6, -12)
VM_AW_HANG2 = dict(VM_AW_HANG); VM_AW_HANG2["body"] = (-9, 0, 0); VM_AW_HANG2["head"] = (VM_AW_HANG["head"][0] - 1, 6, -13)
anim("vm_awaken", 116, seq([(0, LIN, VM_STANCE),
     (3, IO, VM_AW_BACK),
     (10, IO, VM_AW_TURN),
     (17, LIN, VM_AW_TURN),
     (20, EX, VM_AW_ROAR),
     (33, LIN, dict(body=(18, -6, 0))),
     (36, IO, G(body=(6, -4, 0), rl=STAND_RL, ll=STAND_LL, look=(6, 0), rightArm=HR((0.0, -0.3, -0.95), (-0.28, 1.5, -0.3)), leftArm=L_LOOSE)),
     (39, IO, G(body=(0, -2, 0), rl=STAND_RL, ll=STAND_LL, look=(14, 0), rightArm=HR((0.2, -0.85, -0.45), (-0.2, 0.98, -0.25)), leftArm=L_LOOSE)),
     (43, IO, VM_AW_HANG),
     (70, IO, VM_AW_HANG2),
     (96, IO, VM_AW_HANG),
     (110, LIN, VM_AW_HANG),
     (116, IO, VM_STANCE)]))

# ── 각성기 '피의 만찬' (290틱 ≈ 자료 영상 1부 디오 GHA 14.5초, 0.4초 간격으로 떼어 봄. 대사는 우리 것) ──
#   0 기운을 터뜨리며 웅크림 → 6~20 씩 웃는 얼굴 → 22~34 손을 뒤로 당겼다가 카메라 쪽으로 손끝을 내지름 → 37~44 몸을 눕혀 낮게 날아듦 →
#   46 손끝을 가슴에 꽂아 얼림 → 52 꽂은 손으로 들어 올림 → 60~85 웅크려 올려다보며 씩 웃음 ("조금이라도 내게 이길 줄 알았더냐! 머저리!") →
#   88 폭발하듯 위로 한 방 → 96·100·104 번갈아 올려침 → 110~132 고개를 젖히고 웃음 ("빈약하다 빈약해...") →
#   136~166 떠 있는 얼음 덩어리를 두 손으로 번갈아 3틱마다 위로 꽂음(11번) → 168 두 손 모아 한 방 → 173 뛰어오르며 올려침 → 182 착지 →
#   190~228 웅크린 채 올려다보며 씩 웃음 ("죽음의 망각을 맞이해라!") → 230 손을 당겼다가 236 손끝을 내지르고 →
#   244 떨어져 내려오는 덩어리를 손끝으로 곧게 위로 찔러 250 산산조각 → 258 팔을 든 채 섬 → 274 두 팔을 펴고 웃음 → 290
L_UP_HIGH = HL((-0.3, 0.95, -0.1), (-0.3, 2.05, -0.15))
R_UPTHRUST = HR((0.1, 0.72, -0.69), (0.14, 2.1, -0.46))
L_UPTHRUST = HL((-0.1, 0.72, -0.69), (-0.14, 2.1, -0.46))
R_COCK = HR((0.5, -0.75, 0.2), (0.24, 1.3, -0.32))      # 연타 사이: 팔꿈치를 뒤로 당겨 손끝을 가슴 옆에
L_COCK = HL((-0.5, -0.75, 0.2), (-0.24, 1.3, -0.32))
R_LOW_CLAW = HR((0.5, -0.8, -0.3), (0.45, 0.78, -0.45))
L_LOW_CLAW = HL((-0.5, -0.8, -0.3), (-0.45, 0.78, -0.45))
U_HUNCH = G(body=(-22, -10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-26, 6), sink=0.22, rightArm=R_LOW_CLAW, leftArm=L_LOW_CLAW)
U_GRIN = G(body=(-8, -10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-34, -6), sink=0.2, rightArm=R_CLAW_FRONT, leftArm=L_CLAW_FRONT)
U_LAUGH = G(body=(16, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-46, 0), sink=0.06, rightArm=HR((0.6, -0.7, 0.2), (0.72, 0.95, -0.05)), leftArm=HL((-0.6, -0.7, 0.2), (-0.72, 0.95, -0.05)))
U_UPR = G(body=(2, 28, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-42, -12), sink=0.14, rightArm=R_UPTHRUST, leftArm=L_COCK)
U_UPL = G(body=(2, -28, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-42, 12), sink=0.14, leftArm=L_UPTHRUST, rightArm=R_COCK)
_ult = [(0, LIN, VM_STANCE),
        (3, EX, G(body=(-12, -10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(12, 0), sink=0.12, rightArm=R_LOW_CLAW, leftArm=L_LOW_CLAW)),
        (8, IO, G(body=(-16, -14, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(-12, 6), sink=0.1, rightArm=R_COCK, leftArm=L_COCK)),
        (19, LIN, G(body=(-17, -14, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(-13, 6), sink=0.1, rightArm=R_COCK, leftArm=L_COCK)),
        (23, IO, G(body=(-4, -34, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(0, 10), sink=0.1, rightArm=R_CHAMBER, leftArm=HL((-0.1, -0.1, -1), (-0.1, 1.35, -0.6)))),
        (26, EX, G(body=(-20, 26, 0), rl=DEEP_RL, ll=DEEP_LL, look=(6, -8), sink=0.14, z=0.2, rightArm=R_THRUST, leftArm=L_BACK_LO)),
        (34, LIN, G(body=(-18, 24, 0), rl=DEEP_RL, ll=DEEP_LL, look=(5, -8), sink=0.14, z=0.2, rightArm=R_THRUST, leftArm=L_BACK_LO)),
        (37, OQ, AIR(body=(-64, 0, 0), rl=(34, 8, 16), ll=(52, 8, 30), look=(46, 0), rightArm=HR((0.1, 0.12, -0.99), (0.12, 1.55, -0.62)), leftArm=L_BACK_LO)),
        (44, LIN, dict(body=(-60, 0, 0))),
        (46, EX, G(body=(-22, 24, 0), rl=DEEP_RL, ll=DEEP_LL, look=(-8, -8), sink=0.14, z=0.25, rightArm=HR((0.1, 0.3, -0.95), (0.12, 1.7, -0.58)), leftArm=L_BACK_LO)),
        (52, IO, G(body=(-14, 8, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-30, -4), sink=0.2, rightArm=R_UPTHRUST, leftArm=L_CHAMBER)),
        (60, IO, U_HUNCH),
        (72, IO, dict(body=(-20, -10, 0))),
        (84, IO, dict(body=(-22, -10, 0))),
        (86, IO, G(body=(-10, -22, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-20, 8), sink=0.2, rightArm=R_COCK, leftArm=L_COCK)),
        (88, EX, G(body=(0, 26, 0), rl=DEEP_RL, ll=DEEP_LL, look=(-40, -6), sink=0.1, z=0.1, rightArm=R_UPTHRUST, leftArm=L_COCK)),
        (93, LIN, dict(body=(0, 22, 0))),
        (96, EX, U_UPL), (100, EX, U_UPR), (104, EX, U_UPL),
        (110, IO, U_LAUGH),
        (120, IO, dict(body=(18, 0, 0))),
        (131, IO, dict(body=(16, 0, 0))),
        (134, IO, G(body=(-6, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-36, 0), sink=0.18, rightArm=R_COCK, leftArm=L_COCK))]
for i in range(11):
    t = 136 + i * 3
    pose = U_UPR if i % 2 == 0 else U_UPL
    _ult.append((t, EX, pose))
_ult += [(168, EX, G(body=(14, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-46, 0), sink=0.08, rightArm=HR((0.12, 0.97, -0.2), (0.06, 2.2, -0.3)), leftArm=HL((-0.12, 0.97, -0.2), (-0.06, 2.2, -0.3)))),
         (171, IO, G(body=(-24, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, 0), sink=0.22, rightArm=R_BACK_LO, leftArm=L_BACK_LO)),
         (173, OQ, AIR(body=(10, 12, 0), rl=(-62, 18, 92), ll=(8, 12, 24), look=(-44, -6), rightArm=R_UP_HIGH, leftArm=L_BACK_LO)),
         (178, LIN, dict(body=(12, 12, 0))),
         (182, EX, G(body=(-10, -6, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-28, 0), sink=0.2, rightArm=R_CLAW_FRONT, leftArm=L_CLAW_FRONT)),
         (190, IO, U_GRIN),
         (210, IO, dict(body=(-9, -10, 0))),
         (227, IO, dict(body=(-8, -10, 0))),
         (231, IO, G(body=(-6, -26, 0), rl=DEEP_RL, ll=DEEP_LL, look=(-30, 8), sink=0.12, rightArm=R_COCK, leftArm=L_CHAMBER)),
         (236, EX, G(body=(-10, 20, 0), rl=DEEP_RL, ll=DEEP_LL, look=(-24, -6), sink=0.12, z=0.1, rightArm=HR((0.05, 0.5, -0.86), (0.08, 2.0, -0.6)), leftArm=L_BACK_LO)),
         (241, LIN, dict(body=(-9, 20, 0))),
         (244, EX, G(body=(-2, 22, 0), rl=DEEP_RL, ll=DEEP_LL, look=(-42, -4), sink=0.12, z=0.15, rightArm=HR((0.05, 0.96, -0.28), (0.08, 2.25, -0.25)), leftArm=L_BACK_LO)),
         (252, LIN, dict(body=(0, 22, 0))),
         (258, IO, G(body=(6, 10, 0), rl=STAND_RL, ll=STAND_LL, look=(-36, 0), rightArm=HR((0.2, 0.97, -0.1), (0.25, 2.05, -0.15)), leftArm=L_LOOSE)),
         (268, LIN, dict(body=(7, 10, 0))),
         (274, IO, G(body=(16, 0, 0), rl=(8, 14, 12), ll=(-8, 14, 12), look=(-44, 0), rightArm=HR((0.8, 0.5, 0.3), (0.8, 1.95, -0.05)), leftArm=HL((-0.8, 0.5, 0.3), (-0.8, 1.95, -0.05)))),
         (284, LIN, dict(body=(18, 0, 0))),
         (290, IO, VM_STANCE)]
anim("vm_ult", 290, seq(_ult))


# ═════════ 기둥 속 사내 · 열 루트 (참고: 올스타 배틀 R 1부 '열의 기둥 속 사내' 플레이 영상 — 0.17~0.25초 간격으로 떼어 봄. 이름·연출은 우리 것) ═════════
# 영상에서 잰 자세:
#   대기 = 키를 다 편 채 가슴을 내밀고 두 주먹을 턱·얼굴 앞으로 높이 (복서처럼 팔꿈치를 벌림), 왼발을 앞으로 넓게, 고개를 숙여 노려봄
#   달리기 = 상체를 25° 숙이고 주먹 쥔 두 팔을 크게 앞뒤로 흔드는 힘찬 질주
#   평타 = 왼 잽 → 오른 훅(혈관이 따라 휘어짐) → 왼 올려치기 → 몸을 크게 돌리는 오른 손등 후려치기 → 낮게 쓸어 차는 발차기(다운)
#   기술 = 혈관 채찍(몸을 크게 틀며 팔을 휘두르면 붉은 혈관 여러 가닥이 둥글게 휘감김) · 끓는 피를 손끝으로 뿌림 · 주먹을 꽂아 혈관으로 끓임 →
#          폭발 · 두 손으로 얼굴을 감싸고 울다가 고개를 젖혀 울부짖음 · 웅크려 손바닥을 땅에 짚고 열을 끌어올림(ゴゴゴ)
#   각성기 = 만화 칸 속 얼굴 → 가슴·등에서 혈관이 사방으로 터져 나옴(팔을 위로 활짝) → 혈관이 상대에게 꽂힘 → 웅크려 혈관을 움켜쥐고
#           끓는 피를 펌프질 → 상대가 거듭 폭발 (약 12.5초)
R_CHIN = HR((0.22, -0.8, -0.56), (0.17, 1.48, -0.4))        # 오른주먹 턱 앞 (팔꿈치를 몸에 붙임 — 벌리면 마크 몸에선 팔이 W 자로 꺾여 보임)
L_FACE = HL((-0.18, -0.62, -0.77), (-0.12, 1.55, -0.52))     # 왼주먹 얼굴 앞 (조금 더 앞)
R_PUNCH = HR((0.05, -0.05, -1), (0.08, 1.38, -0.62))          # 곧게 뻗은 주먹
L_PUNCH = HL((-0.05, -0.05, -1), (-0.08, 1.38, -0.62))
R_HOOK = HR((0.8, 0.05, -0.6), (-0.05, 1.45, -0.52))          # 팔꿈치를 옆으로 든 훅 (주먹이 가운데로)
L_UPPER = HL((-0.3, -0.6, -0.74), (-0.15, 1.75, -0.45))       # 올려치기
R_SWING_IN = HR((-0.5, -0.1, -0.85), (-0.45, 1.4, -0.35))     # 손등 치기 전: 오른팔을 왼어깨 앞까지 감음
R_SWING_OUT = HR((0.85, 0.1, -0.5), (0.85, 1.42, -0.3))       # 손등 치기: 팔을 오른쪽 바깥으로 쭉
R_FLEX = HR((0.8, -0.35, 0.2), (0.45, 1.12, -0.28))           # 힘을 준 주먹 (엉덩이 앞)
L_FLEX = HL((-0.8, -0.35, 0.2), (-0.45, 1.12, -0.28))
R_FACE_COVER = HR((0.45, -0.2, -0.87), (0.07, 1.62, -0.3))    # 두 손으로 얼굴을 감쌈
L_FACE_COVER = HL((-0.45, -0.2, -0.87), (-0.07, 1.62, -0.3))
R_WAIL = HR((0.85, -0.35, -0.4), (0.95, 1.05, -0.4))          # 울부짖으며 팔을 벌림
L_WAIL = HL((-0.85, -0.35, -0.4), (-0.95, 1.05, -0.4))
R_SPREAD_UP = HR((0.6, 0.75, 0.25), (0.75, 1.95, 0.1))        # 팔을 위로 활짝 (혈관이 터져 나옴)
L_SPREAD_UP = HL((-0.6, 0.75, 0.25), (-0.75, 1.95, 0.1))
R_GROUND = HR((0.15, -0.75, -0.65), (0.2, 0.62, -0.62))       # 손바닥을 앞 땅에
L_KNEE = HL((-0.2, -0.85, -0.45), (-0.25, 0.85, -0.45))       # 무릎 위에 팔
R_PUMP = HR((0.35, -0.7, -0.6), (0.18, 1.0, -0.52))           # 혈관을 움켜쥐고 가슴 쪽으로 당김
L_PUMP = HL((-0.35, -0.7, -0.6), (-0.18, 1.05, -0.5))
PH_RL, PH_LL = (20, 9, 12), (-22, 9, 20)                     # 왼발 앞, 넓게

PH_STANCE = solved(G(body=(2, -18, 0), rl=PH_RL, ll=PH_LL, look=(10, 4), rightArm=R_CHIN, leftArm=L_FACE))
anim("stance_ph", 40, seq([(0, LIN, PH_STANCE), (20, IO, dict(body=(3, -18, 0), rightArm=HR((0.22, -0.82, -0.53), (0.17, 1.46, -0.4)))), (40, IO, PH_STANCE)]), loop=True, ret=0)
PH_WALK = dict(body=(4, -10, 0), head=(10, -10, 0), rightArm=R_CHIN, leftArm=L_FACE)
anim("stance_ph_walk", 40, seq([(0, LIN, PH_WALK), (20, IO, dict(body=(5, -10, 0))), (40, IO, PH_WALK)]), loop=True, ret=0)

# 달리기: 상체 25°, 주먹 쥔 두 팔을 다리와 반대로 크게 흔듦 (팔꿈치 90°), 걸음마다 살짝 뜸. 20틱에 두 걸음
PH_RUN_LEG = [(0, (-40, 18)), (2, (-12, 24)), (4, (30, 10)), (5, (38, 56)), (6, (24, 120)), (8, (-44, 100)), (9, (-50, 44)), (10, (-40, 18))]
def _ph_leg(t):
    t %= 10
    for (t0, a), (t1, b) in zip(PH_RUN_LEG, PH_RUN_LEG[1:]):
        if t0 <= t <= t1:
            f = (t - t0) / (t1 - t0)
            return tuple(a[i] + (b[i] - a[i]) * f for i in range(2))
    return PH_RUN_LEG[0][1]
_prun = []
for t in range(0, 21):
    rw, lw = _ph_leg(t), _ph_leg(t + 5)
    sw = max(-1.0, min(1.0, -(rw[0] - lw[0]) / 90.0))   # 오른다리가 앞이면 +
    BP = -22
    body = (BP, round(-10 * sw, 1), 0)
    rl = (round(rw[0] + BP, 1), round(5 * sw, 1), 4, round(rw[1], 1)); ll = (round(lw[0] + BP, 1), round(5 * sw, 1), -4, round(lw[1], 1))
    low = min(_leg_low(body, rl, -1.9), _leg_low(body, ll, 1.9))
    fly = 0.07 if t % 5 == 0 else 0.04 if t % 5 in (1, 4) else 0.0
    # 팔: 오른다리가 앞(sw>0)이면 왼팔이 앞. 팔꿈치 90°로 굽혀 주먹을 크게 흔듦
    _prun.append((t, LIN, dict(body=body, head=(BP + 18, round(10 * sw, 1), 0), rightLeg=rl, leftLeg=ll, bodyPos=(0, round(-low + fly, 3), 0),
                               rightArm=(round(-15 + 45 * sw, 1), 0, 6, round(-95 + 25 * sw, 1)), leftArm=(round(-15 - 45 * sw, 1), 0, -6, round(-95 - 25 * sw, 1)))))
anim("run_ph", 20, seq(_prun), loop=True, ret=0)

# 가드: 두 팔뚝을 얼굴 앞에 세로로 붙여 세움
PH_GUARD = G(body=(-4, -14, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(10, 0), sink=0.04,
             rightArm=HR((0.35, -0.45, -0.82), (0.08, 1.7, -0.42)), leftArm=HL((-0.35, -0.45, -0.82), (-0.08, 1.7, -0.45)))
guard_loop("guard_ph", PH_GUARD, dict(body=(-5, -14, 0)))


def pstrike(end, frames, hold_to=None, settle=None):
    fr = [(0, LIN, PH_STANCE)] + frames
    t_hit, _, hit = frames[-1]
    if hold_to: fr.append((hold_to, LIN, hit))
    if settle: fr.append(settle)
    fr.append((end, IO, PH_STANCE))
    return seq(fr)


# ── 평타 (판정 4, 막타 6) ──
anim("ph_1", 10, pstrike(10, [   # 왼 잽
    (2, IO, G(body=(0, -24, 0), rl=PH_RL, ll=PH_LL, look=(10, 6), sink=0.04, rightArm=R_CHIN, leftArm=HL((-0.5, -0.5, -0.7), (-0.15, 1.45, -0.38)))),
    (4, EX, G(body=(-8, -38, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(10, 14), sink=0.08, z=0.15, rightArm=R_CHIN, leftArm=L_PUNCH)),
], hold_to=6, settle=(8, IO, G(body=(-5, -32, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(10, 12), sink=0.07, z=0.1, rightArm=R_CHIN, leftArm=L_PUNCH))))
anim("ph_2", 10, pstrike(10, [   # 오른 훅: 몸을 오른쪽으로 감았다가 크게 돌려 (혈관이 따라 휘어짐)
    (2, IO, G(body=(0, -46, 0), rl=PH_RL, ll=PH_LL, look=(8, 10), sink=0.06, rightArm=R_CHAMBER, leftArm=L_FACE)),
    (4, EX, G(body=(-12, 34, 0), rl=DEEP_RL, ll=DEEP_LL, look=(10, -10), sink=0.12, z=0.2, rightArm=R_HOOK, leftArm=L_CHAMBER)),
], hold_to=6, settle=(8, IO, G(body=(-8, 26, 0), rl=DEEP_RL, ll=DEEP_LL, look=(10, -8), sink=0.1, z=0.14, rightArm=R_HOOK, leftArm=L_FACE))))
anim("ph_3", 10, pstrike(10, [   # 왼 올려치기: 무릎을 굽혀 낮췄다가 몸을 펴며
    (2, IO, G(body=(-16, 20, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(14, -4), sink=0.16, rightArm=R_CHIN, leftArm=L_CHAMBER)),
    (4, EX, G(body=(10, -30, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(-12, 10), sink=0.02, z=0.12, rightArm=R_CHIN, leftArm=L_UPPER)),
], hold_to=6, settle=(8, IO, G(body=(6, -24, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(-6, 8), sink=0.04, z=0.1, rightArm=R_CHIN, leftArm=L_UPPER))))
anim("ph_4", 10, pstrike(10, [   # 오른 손등 후려치기: 오른팔을 왼어깨 앞까지 감고 몸을 왼쪽으로 돌렸다가 반대로 크게 휘둘러
    (2, IO, G(body=(0, 46, 0), rl=PH_RL, ll=PH_LL, look=(6, -14), sink=0.06, rightArm=R_SWING_IN, leftArm=L_CHAMBER)),
    (4, EX, G(body=(-10, -50, 0), rl=LUNGE_RL2, ll=LUNGE_LL2, look=(10, 18), sink=0.1, z=0.18, rightArm=R_SWING_OUT, leftArm=L_FACE)),
], hold_to=6, settle=(8, IO, G(body=(-6, -40, 0), rl=LUNGE_RL2, ll=LUNGE_LL2, look=(8, 14), sink=0.08, z=0.12, rightArm=R_SWING_OUT, leftArm=L_FACE))))
PH_SWEEP_CH = dict(G(body=(-14, 34, 0), rl=(0, 0, 0), ll=(-10, 12, 36), look=(18, -10), sink=0.14, rightArm=R_CHIN, leftArm=L_BACK_LO), rightLeg=(-40, 0, 30, 70))
PH_SWEEP = dict(G(body=(-18, -46, 0), rl=(0, 0, 0), ll=(-14, 14, 50), look=(22, 20), sink=0.22, rightArm=R_CHAMBER, leftArm=L_BACK_LO), rightLeg=(-62, 0, 40, 4))
anim("ph_5", 17, pstrike(17, [   # 막타: 무릎을 굽혀 낮추며 오른다리를 감았다가 몸을 크게 돌려 낮게 쓸어 참 (다운)
    (3, IO, PH_SWEEP_CH),
    (6, EX, PH_SWEEP),
], hold_to=10, settle=(13, IO, dict(G(body=(-12, -38, 0), rl=(0, 0, 0), ll=(-12, 14, 44), look=(18, 16), sink=0.18, rightArm=R_CHAMBER, leftArm=L_FACE), rightLeg=(-40, 0, 30, 20)))))
anim("ph_rise", 11, pstrike(11, [   # 띄우기: 깊게 웅크렸다가 몸을 펴며 오른주먹을 하늘로
    (2, IO, G(body=(-22, -20, 0), rl=WIDE_RL, ll=WIDE_LL, look=(20, 6), sink=0.22, rightArm=R_CHAMBER, leftArm=L_FACE)),
    (4, EX, _hop(G(body=(14, 16, 0), rl=(6, 6, 2), ll=(-24, 6, 8), look=(-30, -6), rightArm=HR((0.15, 0.96, -0.2), (0.18, 2.2, -0.26)), leftArm=L_CHAMBER), 0.2)),
], hold_to=7))
PH_AIR0 = solved(AIR(body=(0, -14, 0), rightArm=R_CHIN, leftArm=L_FACE))


def pma(t_hit, end, frames, hold=2):
    fr = [(0, LIN, PH_AIR0)] + frames
    hit = next((p for t, e, p in frames if t == t_hit), None)
    if hit and hold: fr.append((t_hit + hold, LIN, hit))
    fr.append((end, IO, PH_AIR0))
    return seq(fr)


anim("ph_a1", 10, pma(4, 10, [(2, IO, AIR(body=(0, -26, 0), rightArm=R_CHIN, leftArm=L_CHAMBER)), (4, EX, AIR(body=(-8, -36, 0), look=(6, 12), rightArm=R_CHIN, leftArm=L_PUNCH))]))
anim("ph_a2", 10, pma(4, 10, [(2, IO, AIR(body=(0, -40, 0), rightArm=R_CHAMBER, leftArm=L_FACE)), (4, EX, AIR(body=(-10, 30, 0), look=(8, -10), rightArm=R_HOOK, leftArm=L_CHAMBER))]))
anim("ph_a3", 10, pma(4, 10, [(2, IO, AIR(body=(18, 0, 0), look=(-8, 0), rightArm=R_SPREAD_UP, leftArm=L_SPREAD_UP)),
                              (4, EX, AIR(body=(-26, 0, 0), rl=(10, 20, 40), ll=(20, 20, 30), look=(16, 0), rightArm=HR((0.12, -0.25, -0.96), (0.08, 0.95, -0.6)), leftArm=HL((-0.12, -0.25, -0.96), (-0.08, 0.95, -0.6))))]))
# 다운 슬램: 깍지 낀 두 주먹을 머리 위로 들었다가 몸을 고꾸라뜨리며 내리찍음
anim("ph_slam", 15, seq([(0, LIN, PH_AIR0),
     (2, OQ, AIR(body=(24, 0, 0), rl=(-60, 18, 90), ll=(-40, 18, 80), look=(-14, 0), rightArm=HR((0.1, 0.95, 0.25), (0.05, 2.15, 0.12)), leftArm=HL((-0.1, 0.95, 0.25), (-0.05, 2.15, 0.12)))),
     (4, EX, AIR(body=(-56, 0, 0), rl=(30, 18, 30), ll=(22, 18, 24), look=(30, 0), rightArm=HR((0.06, -0.15, -0.98), (0.03, 0.85, -0.7)), leftArm=HL((-0.06, -0.15, -0.98), (-0.03, 0.85, -0.7)))),
     (9, LIN, dict(body=(-52, 0, 0))),
     (15, IO, PH_STANCE)]))

# ── 기술 ──
# ① 끓는 혈관 (땅, 참고: '대차옥' 혈관 채찍 59.5~61초): 몸을 왼쪽으로 감아 오른팔을 왼어깨 앞에 → 6틱에 몸을 크게 돌리며 팔을 바깥으로 휘두름
#    (혈관 여러 가닥이 둥글게 휘감김, 6·10·14 판정) → 10에 되감아 왼팔로 한 번 더 → 26 대기
anim("ph_veins", 26, seq([(0, LIN, PH_STANCE),
     (3, IO, G(body=(0, 50, 0), rl=PH_RL, ll=PH_LL, look=(8, -16), sink=0.06, rightArm=R_SWING_IN, leftArm=L_CHAMBER)),
     (6, EX, G(body=(-10, -50, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, 18), sink=0.14, rightArm=R_SWING_OUT, leftArm=L_CHAMBER)),
     (9, IO, G(body=(-8, -30, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, 12), sink=0.14, rightArm=R_FLEX, leftArm=HL((0.5, -0.1, -0.85), (0.45, 1.4, -0.35)))),
     (11, EX, G(body=(-10, 44, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, -16), sink=0.14, rightArm=R_FLEX, leftArm=HL((-0.85, 0.1, -0.5), (-0.85, 1.42, -0.3)))),
     (14, LIN, dict(body=(-8, 40, 0))),
     (20, IO, G(body=(-4, 10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, 0), sink=0.1, rightArm=R_FLEX, leftArm=L_FLEX)),
     (26, IO, PH_STANCE)]))
anim("ph_veins_q", 11, pstrike(11, [   # 평타 중: 손끝을 곧게 찔러 혈관 한 가닥을 꽂음 (4)
    (2, IO, G(body=(0, -38, 0), rl=PH_RL, ll=PH_LL, look=(8, 8), sink=0.05, rightArm=R_CHAMBER, leftArm=L_FACE)),
    (4, EX, G(body=(-10, 26, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(10, -6), sink=0.1, z=0.18, rightArm=R_THRUST, leftArm=L_CHAMBER)),
], hold_to=7))
anim("ph_veins_air", 20, seq([(0, LIN, PH_AIR0),   # 공중: 팔을 위로 활짝 → 아래로 내리쳐 혈관을 땅으로 (6)
     (3, OQ, AIR(body=(14, 0, 0), look=(-10, 0), rightArm=R_SPREAD_UP, leftArm=L_SPREAD_UP)),
     (6, EX, AIR(body=(-40, 0, 0), rl=(30, 18, 30), ll=(20, 18, 24), look=(26, 0), rightArm=HR((0.45, -0.6, -0.65), (0.5, 0.75, -0.55)), leftArm=HL((-0.45, -0.6, -0.65), (-0.5, 0.75, -0.55)))),
     (12, LIN, dict(body=(-36, 0, 0))), (20, IO, PH_STANCE)]))
# ② 열사 방벽 (참고: '간파했다' 반격): 가슴을 내밀고 두 팔을 낮게 벌린 채 열을 뿜음 (받아내는 창 3~17) → 맞으면 손끝을 꽂아 혈관으로 끓임
PH_COUNTER = G(body=(8, -10, 0), rl=PH_RL, ll=PH_LL, look=(-6, 4), rightArm=HR((0.75, -0.6, -0.25), (0.7, 0.95, -0.38)), leftArm=HL((-0.75, -0.6, -0.25), (-0.7, 0.95, -0.38)))
anim("ph_counter", 32, seq([(0, LIN, PH_STANCE), (3, OQ, PH_COUNTER), (16, IO, dict(body=(10, -10, 0))), (30, IO, dict(body=(8, -10, 0))), (32, IO, PH_STANCE)]))
anim("ph_counter_hit", 22, seq([(0, LIN, PH_COUNTER),
     (2, EX, G(body=(-14, 26, 0), rl=DEEP_RL, ll=DEEP_LL, look=(8, -6), sink=0.12, z=0.25, rightArm=R_THRUST, leftArm=L_CHAMBER)),
     (10, LIN, dict(body=(-12, 26, 0))),
     (14, IO, G(body=(4, -6, 0), rl=PH_RL, ll=PH_LL, look=(0, 0), rightArm=R_FLEX, leftArm=L_FLEX)),
     (22, IO, PH_STANCE)]))
# ③ 끓는 피탄 (참고: '용암탄 같은 혈액' 77.5~80초): 오른팔을 왼어깨 쪽으로 감았다가 앞으로 뿌려 끓는 피 세 덩이 (6)
anim("ph_bullet", 18, pstrike(18, [
    (3, IO, G(body=(0, 40, 0), rl=PH_RL, ll=PH_LL, look=(8, -12), sink=0.05, rightArm=R_SWING_IN, leftArm=L_FACE)),
    (6, EX, G(body=(-8, -28, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(8, 10), sink=0.08, z=0.12, rightArm=HR((0.4, 0.0, -0.92), (0.55, 1.4, -0.55)), leftArm=L_CHAMBER)),
], hold_to=10))
# ④ 눈 깜짝할 사이의 일격 (참고 1:40~1:44): 주먹을 낮게 쥐고 버텨 선 채 노려봄(3~9, 컷인) → 몸을 눕혀 낮게 쏘아 나감(11) → 오른손 찌르기(14)
PH_GLARE = G(body=(4, -12, 0), rl=PH_RL, ll=PH_LL, look=(-4, 6), sink=0.06, rightArm=R_FLEX, leftArm=L_FLEX)
anim("ph_blink", 26, seq([(0, LIN, PH_STANCE), (3, IO, PH_GLARE), (9, LIN, dict(PH_GLARE, body=(5, -12, 0))),
     (11, OQ, G(body=(-40, -4, 0), rl=(-30, 4, 40), ll=(36, 4, 30), look=(-6, 0), sink=0.08, rightArm=R_BACK_LO, leftArm=L_BACK_LO)),
     (14, EX, G(body=(-16, 24, 0), rl=DEEP_RL, ll=DEEP_LL, look=(10, -6), sink=0.12, z=0.25, rightArm=R_THRUST, leftArm=L_CHAMBER)),
     (18, LIN, dict(body=(-14, 24, 0))), (26, IO, PH_STANCE)]))
#    맞으면: 왼손으로 멱살을 잡고(0) 무릎·주먹 넷(4·10·16·22, '놓치지 않는다!') → 두 손으로 머리 위로 들어 올렸다가(28) 앞으로 내던짐(32) → 폭발(34)
PH_HOLD = G(body=(-6, 10, 0), rl=PH_RL, ll=PH_LL, look=(6, -4), sink=0.06, z=0.1, rightArm=R_CHAMBER, leftArm=L_COLLAR)
PH_HKNEE = dict(G(body=(8, 6, 0), rl=(0, 0, 0), ll=(-6, 10, 18), look=(-2, -2), leftArm=L_COLLAR, rightArm=R_CHAMBER), rightLeg=(-100, 0, 10, 106))
PH_HPUNCH = G(body=(-12, -24, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(10, 8), sink=0.1, z=0.15, rightArm=HR((0.3, -0.05, -0.95), (0.15, 1.4, -0.62)), leftArm=L_COLLAR)
_bh = [(0, LIN, PH_HOLD)]
for k, t in enumerate((4, 10, 16, 22)):
    _bh += [(t - 2, IO, PH_HOLD), (t, EX, PH_HKNEE if k % 2 == 0 else PH_HPUNCH)]
_bh += [(28, OQ, G(body=(12, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-34, 0), sink=0.06, rightArm=HR((0.25, 0.95, -0.1), (0.25, 2.25, -0.2)), leftArm=HL((-0.25, 0.95, -0.1), (-0.25, 2.25, -0.2)))),
        (32, EX, G(body=(-28, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(16, 0), sink=0.14, z=0.2, rightArm=HR((0.2, 0.2, -0.96), (0.25, 1.6, -0.6)), leftArm=HL((-0.2, 0.2, -0.96), (-0.25, 1.6, -0.6)))),
        (38, LIN, dict(body=(-26, 0, 0))), (44, IO, PH_STANCE)]
anim("ph_blink_hit", 44, seq(_bh))
# ⑤ 용암 분출 (참고: 모드 발동 클로즈업 — 웅크려 손바닥을 땅에 짚고 ゴゴゴ): 무릎을 꿇듯 깊게 웅크려 오른손바닥을 땅에 짚음 (8) → 18까지 누름
PH_PRESS = G(body=(-34, -10, 0), rl=KNEEL_RL, ll=KNEEL_LL, look=(-10, 6), rightArm=R_GROUND, leftArm=L_KNEE)
anim("ph_lava", 24, seq([(0, LIN, PH_STANCE), (4, IO, G(body=(-14, -10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(10, 4), sink=0.12, rightArm=R_UP_HIGH, leftArm=L_FLEX)),
                         (8, EX, PH_PRESS), (18, LIN, dict(body=(-32, -10, 0), rightArm=R_GROUND, leftArm=L_KNEE)), (24, IO, PH_STANCE)]))
# ⑤ 이미 오래전에 간파했다 (참고 1:08~1:11): 한 무릎을 꿇을 듯 깊게 주저앉아 손바닥을 땅에 꽂음(5) — 혈관이 땅을 타고 달려가 상대 발밑에서
#    터져 불붙임(≈12) → 손을 꽂은 채 버팀 → 24 일어섬 → 32
R_SEE = HR((0.2, -0.85, -0.5), (0.24, 0.3, -0.72))   # 손바닥을 땅에 꽂음
PH_SEE = G(body=(-36, -14, 0), rl=KNEEL_RL, ll=KNEEL_LL, look=(-16, 8), rightArm=R_SEE, leftArm=L_KNEE)
anim("ph_see", 32, seq([(0, LIN, PH_STANCE), (3, IO, G(body=(-12, -14, 0), rl=WIDE_RL, ll=WIDE_LL, look=(6, 6), sink=0.12, rightArm=R_UP_HIGH, leftArm=L_FLEX)),
                        (5, EX, PH_SEE), (20, LIN, dict(PH_SEE, body=(-34, -14, 0))), (26, IO, G(body=(-8, -14, 0), rl=WIDE_RL, ll=WIDE_LL, look=(6, 6), sink=0.1, rightArm=R_FLEX, leftArm=L_FLEX)), (32, IO, PH_STANCE)]))
# ⑥ 분노의 열 (모드, 참고: '괴염왕의 모드'): 웅크려 두 주먹에 힘을 줬다가(5) 몸을 펴며 가슴을 내밀고 포효 — 온몸에서 김이 터짐 (14)
anim("ph_rage", 30, seq([(0, LIN, PH_STANCE),
     (5, IO, G(body=(-18, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(16, 0), sink=0.16, rightArm=R_FLEX, leftArm=L_FLEX)),
     (12, LIN, G(body=(-20, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(18, 0), sink=0.16, rightArm=R_FLEX, leftArm=L_FLEX)),
     (14, EX, G(body=(14, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-40, 0), sink=0.06, rightArm=HR((0.85, -0.2, 0.3), (0.6, 1.25, -0.15)), leftArm=HL((-0.85, -0.2, 0.3), (-0.6, 1.25, -0.15)))),
     (24, LIN, dict(body=(15, 0, 0))),
     (30, IO, PH_STANCE)]))
# ⑦ 전신 비등 (참고: '눈 깜짝할 사이의 일격' 1:40~1:45): 웅크렸다(3) 낮게 달려들어(6) 무릎을 꽂고(10) 손바닥을 갖다 대 쌓인 열을 터뜨림(18)
PH_KNEE = dict(G(body=(6, -12, 0), rl=(0, 0, 0), ll=(-6, 10, 18), look=(-4, 0), leftArm=L_COLLAR, rightArm=R_CHAMBER), rightLeg=(-100, 0, 10, 106))
anim("ph_boil", 40, seq([(0, LIN, PH_STANCE),
     (3, IO, G(body=(-24, -14, 0), rl=WIDE_RL, ll=WIDE_LL, look=(20, 6), sink=0.2, rightArm=R_CHAMBER, leftArm=L_CHAMBER)),
     (6, OQ, G(body=(-34, -4, 0), rl=(-40, 4, 30), ll=(30, 4, 40), look=(-8, 0), sink=0.1, rightArm=R_BACK_LO, leftArm=L_BACK_LO)),
     (10, EX, PH_KNEE),
     (14, IO, G(body=(-4, -30, 0), rl=FIGHT_RL, ll=FIGHT_LL, look=(6, 8), sink=0.08, rightArm=R_CHAMBER, leftArm=L_COLLAR)),
     (18, EX, G(body=(-14, 30, 0), rl=DEEP_RL, ll=DEEP_LL, look=(8, -8), sink=0.14, z=0.28, rightArm=HR((0.1, 0.0, -1), (0.12, 1.42, -0.62)), leftArm=L_BACK_LO)),
     (28, LIN, dict(body=(-12, 28, 0))),
     (40, IO, PH_STANCE)]))
# ①' 부글부글 끓는 스튜 (모드 중 끓는 혈관, 참고 1:29.5~1:31.5): 주먹을 상대 가슴에 꽂고(4) 웅크려 왼손으로 받쳐 혈관으로 펌프질(10~28) → 두 손바닥으로 밀어 터뜨림(34)
PH_PLUNGE = G(body=(-14, 18, 0), rl=DEEP_RL, ll=DEEP_LL, look=(6, -4), sink=0.14, z=0.25, rightArm=R_THRUST, leftArm=L_PUMP)
PH_PLUNGE2 = G(body=(-20, 18, 0), rl=DEEP_RL, ll=DEEP_LL, look=(10, -4), sink=0.18, z=0.25, rightArm=R_THRUST, leftArm=L_PUMP)
anim("ph_stew", 50, seq([(0, LIN, PH_STANCE), (2, IO, G(body=(0, -34, 0), rl=PH_RL, ll=PH_LL, look=(8, 8), sink=0.06, rightArm=R_CHAMBER, leftArm=L_FACE)),
     (4, EX, PH_PLUNGE)] + [(10 + 6 * k, IO if k % 2 else EX, PH_PLUNGE if k % 2 else PH_PLUNGE2) for k in range(4)]
     + [(31, IO, G(body=(-4, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(4, 0), sink=0.1, z=0.15, rightArm=R_CHAMBER, leftArm=L_CHAMBER)),
        (34, EX, G(body=(-16, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(8, 0), sink=0.14, z=0.3, rightArm=HR((0.2, 0.0, -0.98), (0.2, 1.4, -0.62)), leftArm=HL((-0.2, 0.0, -0.98), (-0.2, 1.4, -0.62)))),
        (42, LIN, dict(body=(-14, 0, 0))), (50, IO, PH_STANCE)]))

# ── 각성(비등점) 중 바뀌는 기술 · 공중/평타 중 변형 ──
L_SWING_IN = HL((0.5, -0.1, -0.85), (0.45, 1.4, -0.35))
L_SWING_OUT = HL((-0.85, 0.1, -0.5), (-0.85, 1.42, -0.3))
R_PALM = HR((0.2, 0.0, -0.98), (0.2, 1.4, -0.62))
L_PALM = HL((-0.2, 0.0, -0.98), (-0.2, 1.4, -0.62))
# 증기 분사: 웅크렸다(2) 등에서 김을 뿜으며 몸을 눕혀 쏘아 나감(4~10) → 12 버팀 → 16
anim("ph_dash", 16, seq([(0, LIN, PH_STANCE),
     (2, IO, G(body=(-24, -8, 0), rl=WIDE_RL, ll=WIDE_LL, look=(18, 4), sink=0.18, rightArm=R_CHAMBER, leftArm=L_CHAMBER)),
     (4, EX, G(body=(-42, 0, 0), rl=(-30, 4, 40), ll=(36, 4, 30), look=(-6, 0), sink=0.08, rightArm=R_BACK_LO, leftArm=L_BACK_LO)),
     (10, LIN, G(body=(-40, 0, 0), rl=(-30, 4, 40), ll=(36, 4, 30), look=(-6, 0), sink=0.08, rightArm=R_BACK_LO, leftArm=L_BACK_LO)),
     (12, IO, G(body=(-12, -10, 0), rl=DEEP_RL, ll=DEEP_LL, look=(10, 4), sink=0.12, rightArm=R_CHIN, leftArm=L_FACE)),
     (16, IO, PH_STANCE)]))
# 혈관 포박: 두 팔을 가슴 앞에 엇갈렸다가(3) 앞으로 활짝 뿌려 혈관을 뻗음(6) → 몸을 젖히며 당김(12) → 두 팔을 위로 휘둘러 띄움(16)
anim("ph_bind", 26, seq([(0, LIN, PH_STANCE),
     (3, IO, G(body=(-6, 0, 0), rl=PH_RL, ll=PH_LL, look=(8, 0), sink=0.06, rightArm=R_SWING_IN, leftArm=L_SWING_IN)),
     (6, EX, G(body=(-14, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(8, 0), sink=0.12, z=0.12, rightArm=HR((0.45, 0.05, -0.9), (0.6, 1.45, -0.5)), leftArm=HL((-0.45, 0.05, -0.9), (-0.6, 1.45, -0.5)))),
     (12, IO, G(body=(12, 0, 0), rl=PH_RL, ll=PH_LL, look=(-6, 0), sink=0.06, rightArm=R_PUMP, leftArm=L_PUMP)),
     (16, EX, G(body=(16, 0, 0), rl=PH_RL, ll=PH_LL, look=(-36, 0), sink=0.02, rightArm=R_SPREAD_UP, leftArm=L_SPREAD_UP)),
     (20, LIN, dict(body=(17, 0, 0))),
     (26, IO, PH_STANCE)]))
# 용암탄 난사: 오른팔(5) → 왼팔(11) → 오른팔(17)로 번갈아 뿌림
_bar = [(0, LIN, PH_STANCE)]
for k, t in enumerate((5, 11, 17)):
    right = k % 2 == 0
    _bar.append((t - 2, IO, G(body=(0, 38 if right else -38, 0), rl=PH_RL, ll=PH_LL, look=(8, -10 if right else 10), sink=0.05,
                             rightArm=R_SWING_IN if right else R_CHIN, leftArm=L_FACE if right else L_SWING_IN)))
    _bar.append((t, EX, G(body=(-8, -26 if right else 26, 0), rl=LUNGE_RL, ll=LUNGE_LL, look=(8, 10 if right else -10), sink=0.08, z=0.1,
                         rightArm=HR((0.4, 0.0, -0.92), (0.55, 1.4, -0.55)) if right else R_CHAMBER, leftArm=L_CHAMBER if right else HL((-0.4, 0.0, -0.92), (-0.55, 1.4, -0.55)))))
_bar.append((24, IO, PH_STANCE))
anim("ph_barrage", 24, seq(_bar))
# 손바닥 폭발 (평타 중 열사 방벽·전신 비등): 두 손을 허리로 당겼다가(2) 앞으로 밀어 터뜨림(4)
anim("ph_palm", 11, pstrike(11, [
    (2, IO, G(body=(-4, -10, 0), rl=PH_RL, ll=PH_LL, look=(8, 4), sink=0.08, rightArm=R_CHAMBER, leftArm=L_CHAMBER)),
    (4, EX, G(body=(-16, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(8, 0), sink=0.14, z=0.25, rightArm=R_PALM, leftArm=L_PALM)),
], hold_to=7))

# ── 각성 '비등점' (84틱, 참고 0:29.8~0:34.3 '너어어무해에에에~!'): 움찔(3) → 고개를 뒤로 젖히고 두 팔을 늘어뜨려 울부짖음(6~18, 얼굴 클로즈업) →
#   선 채로 두 팔뚝을 얼굴 앞에 올려 엉엉 욺 (8틱마다 어깨가 들썩, 팔을 번갈아 비빔, 22~72) → 팔을 내리고 열이 다시 오름(76) → 84
R_SOB_HI = HR((0.85, 0.15, -0.5), (-0.08, 1.66, -0.36))     # 오른팔뚝을 눈앞에 가로로
L_SOB_LO = HL((-0.45, -0.45, -0.77), (-0.12, 1.6, -0.34))    # 왼주먹을 눈가에
R_SOB_LO = HR((0.45, -0.45, -0.77), (0.12, 1.6, -0.34))
L_SOB_HI = HL((-0.85, 0.15, -0.5), (0.08, 1.66, -0.36))
PH_SOB_A = G(body=(8, -8, 0), rl=STAND_RL, ll=STAND_LL, look=(-24, 12), sink=0.02, rightArm=R_SOB_HI, leftArm=L_SOB_LO)
PH_SOB_B = G(body=(4, 8, 0), rl=STAND_RL, ll=STAND_LL, look=(-18, -10), sink=0.05, rightArm=R_SOB_LO, leftArm=L_SOB_HI)
_aw = [(0, LIN, PH_STANCE),
       (3, EX, G(body=(-6, 0, 0), rl=PH_RL, ll=PH_LL, look=(10, 0), sink=0.06, rightArm=R_FLEX, leftArm=L_FLEX)),
       (6, EX, G(body=(16, 0, 0), rl=STAND_RL, ll=STAND_LL, look=(-56, 8), rightArm=R_WAIL, leftArm=L_WAIL)),
       (12, LIN, G(body=(18, 0, 0), rl=STAND_RL, ll=STAND_LL, look=(-58, 6), rightArm=R_WAIL, leftArm=L_WAIL)),
       (18, LIN, G(body=(17, 0, 0), rl=STAND_RL, ll=STAND_LL, look=(-56, 10), rightArm=R_WAIL, leftArm=L_WAIL))]
for k, t in enumerate(range(22, 72, 8)):
    _aw.append((t, IO, PH_SOB_A if k % 2 == 0 else PH_SOB_B))
    _aw.append((t + 3, EX, dict(PH_SOB_A if k % 2 == 0 else PH_SOB_B, body=(12, -8 if k % 2 == 0 else 8, 0))))   # 흐느낄 때 어깨가 들썩
_aw += [(76, IO, G(body=(-4, -14, 0), rl=WIDE_RL, ll=WIDE_LL, look=(8, 6), sink=0.1, rightArm=R_FLEX, leftArm=L_FLEX)),
        (84, IO, PH_STANCE)]
anim("ph_awaken", 84, seq(_aw))

# ── 각성기 '증기 폭발' (256틱, 참고 1:59~2:12 '불을 붙여 태워주마!' 같은 시간·흐름) ──
#   0~12 웅크려 힘을 모음 → 12~21 만화 칸 속 얼굴 → 22 두 팔을 위로 활짝 — 몸에서 혈관이 사방으로 터짐(バババ) → 36 팔을 당겼다 40 앞으로 내지르면
#   혈관이 날아가 56에 꽂힘 → 100까지 팔을 뻗어 혈관을 붙들고 → 104 웅크려 두 손으로 혈관을 움켜쥠 → 112·128·144 크게 세 번 펌프질 (ドォ) →
#   150~230 서서 혈관을 당긴 채 끓는 피가 흘러 들어가 상대가 타며 거듭 터짐 → 234 팔을 활짝 뿌리며 마지막 대폭발 → 256
PH_HUNCH = G(body=(-30, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(18, 0), sink=0.24, rightArm=R_PUMP, leftArm=L_PUMP)
PH_HEAVE = G(body=(-8, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-4, 0), sink=0.12, rightArm=HR((0.6, -0.5, 0.62), (0.3, 1.3, -0.15)), leftArm=HL((-0.6, -0.5, 0.62), (-0.3, 1.32, -0.15)))
PH_PULL = G(body=(4, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(-2, 0), sink=0.1, rightArm=HR((0.3, -0.3, -0.9), (0.22, 1.25, -0.55)), leftArm=HL((-0.3, -0.3, -0.9), (-0.22, 1.27, -0.55)))
_pu = [(0, LIN, PH_STANCE),
       (6, IO, G(body=(-18, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(14, 0), sink=0.16, rightArm=R_FLEX, leftArm=L_FLEX)),
       (12, LIN, G(body=(-20, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(16, 0), sink=0.16, rightArm=R_FLEX, leftArm=L_FLEX)),
       (15, IO, G(body=(-6, -10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-8, 6), sink=0.1, rightArm=R_FLEX, leftArm=L_FLEX)),
       (21, LIN, G(body=(-5, -10, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-8, 6), sink=0.1, rightArm=R_FLEX, leftArm=L_FLEX)),
       (23, EX, G(body=(18, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-48, 0), sink=0.04, rightArm=R_SPREAD_UP, leftArm=L_SPREAD_UP)),
       (34, LIN, G(body=(20, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-46, 0), sink=0.04, rightArm=R_SPREAD_UP, leftArm=L_SPREAD_UP)),
       (37, IO, G(body=(8, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(-10, 0), sink=0.1, rightArm=R_CHAMBER, leftArm=L_CHAMBER)),
       (40, EX, G(body=(-14, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(6, 0), sink=0.14, z=0.15, rightArm=HR((0.2, 0.05, -0.98), (0.22, 1.42, -0.62)), leftArm=HL((-0.2, 0.05, -0.98), (-0.22, 1.42, -0.62)))),
       (56, LIN, G(body=(-12, 0, 0), rl=DEEP_RL, ll=DEEP_LL, look=(6, 0), sink=0.14, z=0.15, rightArm=HR((0.2, 0.05, -0.98), (0.22, 1.42, -0.62)), leftArm=HL((-0.2, 0.05, -0.98), (-0.22, 1.42, -0.62)))),
       (64, IO, PH_PULL), (100, LIN, dict(PH_PULL, body=(6, 0, 0))),
       (104, IO, PH_HUNCH)]
for t in (112, 128, 144):   # 크게 세 번: 몸을 펴며 혈관을 가슴으로 끌어당김 → 다시 웅크림
    _pu += [(t - 4, IO, PH_HUNCH), (t, EX, PH_HEAVE), (t + 6, IO, dict(PH_HEAVE, body=(-14, 0, 0)))]
_pu += [(154, IO, PH_PULL)]
for k, t in enumerate(range(160, 232, 6)):   # 끓는 동안 버티며 몸이 떨림
    _pu.append((t, IO, dict(PH_PULL, body=(4 + (k % 2) * 3, (k % 3 - 1) * 3, 0))))
_pu += [(234, EX, G(body=(20, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-50, 0), sink=0.04, rightArm=R_SPREAD_UP, leftArm=L_SPREAD_UP)),
        (246, LIN, G(body=(18, 0, 0), rl=WIDE_RL, ll=WIDE_LL, look=(-46, 0), sink=0.04, rightArm=R_SPREAD_UP, leftArm=L_SPREAD_UP)),
        (256, IO, PH_STANCE)]
anim("ph_ult", 256, seq(_pu))


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
