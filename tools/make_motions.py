"""
도시 능력자 모션 생성기 — playerAnimator(Emotecraft JSON 형식 3판) 파일을 만든다.
    python tools/make_motions.py            # JSON 만들기
    python tools/preview_motions.py         # 미리보기 그림 (tools/preview/)
결과: city-ability/src/main/resources/assets/bbc/player_animation/<이름>.json

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
import json, os

OUT = os.path.join(os.path.dirname(__file__), "..", "city-ability", "src", "main", "resources",
                   "assets", "bbc", "player_animation")
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
# 바닐라 모델에서 손에 든 무기는 항상 '아래팔에 수직'으로 붙는다: 아래팔이 늘어지면(0°) 칼끝이 앞을, 아래팔이 앞으로(-90°) 들리면 칼끝이 위를 본다.
# 그래서 칼끝 방향 = 아래팔 각도(팔 pitch + 팔꿈치 bend) - 90°. 앞쪽 비스듬히 위(-120°)를 보게 하려면 아래팔이 -30° 근처여야 한다.
#  - 가로 베기: 아래팔을 -30° 근처로 두고, 위팔 yaw + 몸 회전으로 쓸어 넘김 (칼날이 수평에 가깝게 지나감)
#  - 내려베기: 팔을 머리 위로(칼끝이 등 뒤로 → 크게 젖힌 예비 동작) → 앞 아래로 내리침 (칼끝이 앞을 봄)
#  - 찌르기: 팔을 거의 늘어뜨린 채(칼끝이 앞) 팔 자체를 앞으로 밀어냄 (z 위치) + 몸을 숙임
SWORD_STANCE = dict(body=(0, -16, 0), head=(0, -16, 0),
                    rightArm=(-22, -6, 6, -10, None, 0), leftArm=(-52, 20, -6, -85, None, 0))
SPEAR_STANCE = dict(body=(0, -24, 0), head=(0, -24, 0),
                    rightArm=(-12, 4, 6, -4, None, 1), leftArm=(-48, 26, -4, -26, None, -2))
GS_STANCE = dict(body=(0, -20, 0), head=(0, -20, 0),
                 rightArm=(-34, -22, 4, -12, None, -1), leftArm=(-40, 26, -4, -12, None, -1))
anim("stance_sword", 40, P(0, LIN, **SWORD_STANCE) + P(20, IO, rightArm=(-25, -6, 6, -10, None, 0)) + P(40, IO, **SWORD_STANCE), loop=True)
anim("stance_spear", 40, P(0, LIN, **SPEAR_STANCE) + P(20, IO, leftArm=(-51, 26, -4, -26, None, -2)) + P(40, IO, **SPEAR_STANCE), loop=True)
anim("stance_gs", 40, P(0, LIN, **GS_STANCE) + P(20, IO, rightArm=(-37, -22, 4, -12, None, -1)) + P(40, IO, **GS_STANCE), loop=True)


# ── 에픽파이트 무기 공격을 관절 위치로 재 본 결과 (검 auto1·도끼 auto1 순운동학) ──
#  1) 예비 동작은 '천천히 끌어올리는' 게 아니라 첫 1틱에 이미 감긴 자세다. 그 뒤 판정 전까지는 거의 멈춘 채 긴장(조금만 더 감음)
#  2) 휘두르기는 1~2틱. 손이 먼저 가고 칼끝이 뒤따라오다(손목이 늦게 따라옴) 마지막에 확 따라잡는다 → 채찍 같은 속도감
#     → 우리는 손목이 없어서, 감을 때 팔꿈치를 접어 칼끝을 뒤로 남겨 두고, 칠 때 팔꿈치를 펴서 칼끝이 따라오게 만든다
#  3) 검(가벼움): 친 자세로 딱 멈춰서 오래 버팀. 도끼·대검(무거움): 친 뒤에도 무게에 끌려 몸이 같은 방향으로 계속 돌아감(관성)
#  4) 몸통 비틀기 20~30°가 일을 하고, 발은 거의 그대로. 마무리 타만 크게 내딛음
def wswing(base, t_hit, end, coil, hit, follow, hold_to, coil2=None, legs_coil=None, legs_hit=None):
    keys = P(0, LIN, **base) + P(1, EX, **coil)
    keys += P(t_hit - 1, LIN, **(coil2 or coil))
    keys += P(t_hit, EX, **hit) + P(hold_to, OS, **follow) + P(end, IO, **base)
    z = dict(rightLeg=(0, 0, 0, 0), leftLeg=(0, 0, 0, 0))
    if legs_coil or legs_hit:
        keys += P(0, LIN, **z)
        if legs_coil: keys += P(1, EX, **legs_coil) + P(t_hit - 1, LIN, **legs_coil)
        if legs_hit: keys += P(t_hit, EX, **legs_hit) + P(hold_to, OS, **legs_hit)
        keys += P(end, IO, **z)
    return keys

LUNGE_R2 = dict(rightLeg=(-30, 0, 0, 26), leftLeg=(20, 0, 0, 8))
LUNGE_L2 = dict(leftLeg=(-30, 0, 0, 26), rightLeg=(20, 0, 0, 8))
SET = dict(rightLeg=(8, 0, 0, 16), leftLeg=(-12, 0, 0, 16))           # 무릎을 살짝 굽혀 버팀

# 검 (판정 4틱, 친 자세로 4틱 버팀)
anim("sw_1", 11, wswing(SWORD_STANCE, 4, 11,  # 오른쪽 → 왼쪽 가로 베기
    coil=dict(rightArm=(-45, 70, 25, -60), body=(0, -36, 0), head=(0, -24, 0)),
    coil2=dict(rightArm=(-46, 78, 28, -66), body=(0, -40, 0)),
    hit=dict(rightArm=(-24, -50, -12, -4), leftArm=(-30, 30, -20, -70), body=(-6, 26, 0), head=(-4, 10, 0)),
    follow=dict(rightArm=(-22, -60, -14, -4), body=(-6, 30, 0)), hold_to=8, legs_coil=SET, legs_hit=LUNGE_L2))
anim("sw_2", 11, wswing(SWORD_STANCE, 4, 11,  # 왼쪽 → 오른쪽 되돌려 베기
    coil=dict(rightArm=(-55, -70, -25, -70), body=(0, 30, 0), head=(0, 12, 0)),
    coil2=dict(rightArm=(-56, -76, -28, -74), body=(0, 34, 0)),
    hit=dict(rightArm=(-24, 55, 14, -4), leftArm=(-40, 10, -10, -80), body=(-6, -36, 0), head=(-4, -22, 0)),
    follow=dict(rightArm=(-22, 64, 16, -4), body=(-6, -40, 0)), hold_to=8, legs_coil=SET, legs_hit=LUNGE_R2))
anim("sw_3", 11, wswing(SWORD_STANCE, 4, 11,  # 머리 위 → 앞 아래로 내려베기
    coil=dict(rightArm=(-168, 15, 10, -55), leftArm=(-150, -10, -10, -60), body=(8, -14, 0), head=(-10, -14, 0)),
    coil2=dict(rightArm=(-176, 15, 10, -60), body=(10, -16, 0)),
    hit=dict(rightArm=(-22, -5, 0, -4), leftArm=(-30, 20, -10, -40), body=(-20, 8, 0), head=(-8, 8, 0)),
    follow=dict(rightArm=(-14, -5, 0, -4), body=(-22, 10, 0)), hold_to=8, legs_coil=SET, legs_hit=LUNGE_R2))
anim("sw_4", 11, wswing(SWORD_STANCE, 4, 11,  # 아래 → 위로 올려베기
    coil=dict(rightArm=(15, 25, 15, -30), body=(-12, -26, 0), head=(-4, -26, 0)),
    hit=dict(rightArm=(-152, -10, -5, -8), leftArm=(-40, 25, -20, -60), body=(8, 16, 0), head=(-18, 16, 0)),
    follow=dict(rightArm=(-164, -12, -5, -8), body=(10, 18, 0)), hold_to=8,
    legs_coil=dict(rightLeg=(12, 0, 0, 34), leftLeg=(-22, 0, 0, 34)), legs_hit=dict(rightLeg=(6, 0, 0, 4), leftLeg=(-14, 0, 0, 6))))
anim("sw_5", 18, wswing(SWORD_STANCE, 5, 18,  # 마무리: 두 손으로 머리 위까지 → 내딛으며 내리쳐 날려보냄
    coil=dict(rightArm=(-178, 10, 0, -55), leftArm=(-172, -10, 0, -55), body=(12, -28, 0), head=(-14, -28, 0)),
    hit=dict(rightArm=(-18, 0, 0, 0), leftArm=(-22, 10, 0, -10), body=(-28, 14, 0), head=(-10, 14, 0)),
    follow=dict(rightArm=(-8, 0, 0, 0), body=(-30, 16, 0)), hold_to=13,
    legs_coil=dict(rightLeg=(14, 0, 0, 30), leftLeg=(-14, 0, 0, 20)), legs_hit=dict(rightLeg=(30, 0, 0, 10), leftLeg=(-46, 0, 0, 44))))
anim("sw_rise", 12, wswing(SWORD_STANCE, 4, 12,
    coil=dict(rightArm=(18, 20, 15, -35), body=(-16, -24, 0), head=(-6, -24, 0)),
    hit=dict(rightArm=(-175, -5, 0, -5), leftArm=(-20, 20, -30, -40), body=(14, 14, 0), head=(-28, 14, 0)),
    follow=dict(rightArm=(-182, -5, 0, -5), body=(16, 14, 0)), hold_to=9,
    legs_coil=dict(rightLeg=(14, 0, 0, 38), leftLeg=(-26, 0, 0, 38)), legs_hit=dict(rightLeg=(10, 0, 0, 4), leftLeg=(-20, 0, 0, 8))))

# 무기 다운 슬램 (ABA 대검 캐릭터 영상: 공중에서 무기를 머리 위로 세웠다가 그대로 아래로 쪼개듯 내리찍음)
TUCK2 = dict(rightLeg=(-40, 0, 0, 70), leftLeg=(-30, 0, 0, 70))
def wslam(base):
    return wswing(base, 4, 14,
        coil=dict(rightArm=(-176, 8, 0, -45), leftArm=(-176, 20, 0, -45), body=(12, 0, 0), head=(-18, 0, 0)),
        coil2=dict(rightArm=(-184, 8, 0, -50), leftArm=(-184, 20, 0, -50), body=(14, 0, 0)),
        hit=dict(rightArm=(-18, 4, 0, -4), leftArm=(-22, 20, 0, -6), body=(-36, 0, 0), head=(18, 0, 0)),
        follow=dict(rightArm=(-10, 4, 0, -4), leftArm=(-14, 20, 0, -6), body=(-40, 0, 0)), hold_to=10,
        legs_coil=TUCK2, legs_hit=dict(rightLeg=(10, 0, 0, 40), leftLeg=(-10, 0, 0, 30)))
anim("sw_slam", 14, wslam(SWORD_STANCE))
anim("sp_slam", 14, wslam(SPEAR_STANCE))
anim("gs_slam", 14, wslam(GS_STANCE))

# 창: 팔을 늘어뜨린 채(창끝이 앞) 첫 틱에 뒤로 당겨 두고 → 판정 틱에 두 팔을 앞으로 쭉 밀며 깊게 내딛음
def thrust(t_hit, end, legs, hold_to, push=-9):
    return wswing(SPEAR_STANCE, t_hit, end,
        coil=dict(rightArm=(-4, 4, 6, -2, None, 5), leftArm=(-40, 26, -4, -20, None, 2), body=(4, -34, 0), head=(0, -30, 0)),
        hit=dict(rightArm=(-22, 2, 0, 0, None, push), leftArm=(-58, 18, 0, -6, None, push - 2), body=(-16, -10, 0), head=(-10, -10, 0)),
        follow=dict(rightArm=(-22, 2, 0, 0, None, push - 1), body=(-17, -10, 0)), hold_to=hold_to, legs_coil=SET, legs_hit=legs)
anim("sp_1", 11, thrust(4, 11, LUNGE_L2, 8))
anim("sp_2", 11, thrust(4, 11, LUNGE_L2, 8, push=-8))
anim("sp_3", 11, thrust(4, 11, dict(leftLeg=(-38, 0, 0, 34), rightLeg=(26, 0, 0, 8)), 8))
anim("sp_4", 17, thrust(5, 17, dict(leftLeg=(-46, 0, 0, 42), rightLeg=(30, 0, 0, 10)), 12, push=-11))
anim("sp_thrust", 13, thrust(5, 13, dict(leftLeg=(-50, 0, 0, 46), rightLeg=(34, 0, 0, 10)), 10, push=-12))
anim("sp_rapid", 4,  # 연속 찌르기 (꾹 누르는 동안 4틱마다 반복 재생)
    P(0, LIN, rightArm=(-4, 4, 6, -2, None, 4), leftArm=(-40, 26, -4, -20, None, 2), body=(0, -30, 0), head=(0, -26, 0))
    + P(1, EX, rightArm=(-22, 2, 0, 0, None, -9), leftArm=(-58, 18, 0, -6, None, -11), body=(-14, -12, 0), head=(-10, -12, 0))
    + P(4, IO, rightArm=(-4, 4, 6, -2, None, 4), leftArm=(-40, 26, -4, -20, None, 2), body=(0, -30, 0), head=(0, -26, 0)), stop=5)
anim("sp_sweep", 15, wswing(SPEAR_STANCE, 5, 15,  # 창을 낮게 들고 크게 휩쓸기
    coil=dict(rightArm=(-30, 70, 20, -5), leftArm=(-40, 60, 0, -20), body=(0, -55, 0), head=(0, -30, 0)),
    hit=dict(rightArm=(-30, -60, -20, -5), leftArm=(-40, -40, 0, -20), body=(-10, 45, 0), head=(-6, 20, 0)),
    follow=dict(rightArm=(-28, -75, -24, -5), body=(-10, 60, 0)), hold_to=11, legs_coil=SET, legs_hit=LUNGE_R2))

# 대검·도끼 (판정 7틱): 첫 틱에 날을 등 뒤로 넘겨 두고 6틱 동안 버티다 → 온몸으로 내리침 → 무게에 끌려 계속 숙여짐(관성)
def heavy(t_hit, end, legs, hold_to, low=-15):
    return wswing(GS_STANCE, t_hit, end,
        coil=dict(rightArm=(-176, 6, 0, -45), leftArm=(-176, 20, 0, -45), body=(12, -24, 0), head=(-14, -24, 0)),
        coil2=dict(rightArm=(-184, 6, 0, -50), leftArm=(-184, 20, 0, -50), body=(14, -26, 0)),
        hit=dict(rightArm=(low, 4, 0, -5), leftArm=(low - 4, 22, 0, -8), body=(-26, 4, 0), head=(-14, 4, 0)),
        follow=dict(rightArm=(low + 10, 4, 0, -4), leftArm=(low + 6, 22, 0, -6), body=(-34, 6, 0), head=(-10, 6, 0)),
        hold_to=hold_to, legs_coil=SET, legs_hit=legs)
anim("gs_1", 20, heavy(7, 20, LUNGE_L2, 15))
anim("gs_2", 20, wswing(GS_STANCE, 7, 20,  # 옆으로 크게 후리기 → 관성으로 몸이 더 돌아감
    coil=dict(rightArm=(-50, 80, 20, -50), leftArm=(-55, 70, 0, -50), body=(0, -60, 0), head=(0, -30, 0)),
    hit=dict(rightArm=(-40, -60, -20, -5), leftArm=(-45, -45, 0, -10), body=(-10, 40, 0), head=(-6, 16, 0)),
    follow=dict(rightArm=(-34, -85, -26, -5), leftArm=(-40, -70, 0, -10), body=(-12, 75, 0), head=(-6, 28, 0)), hold_to=15,
    legs_coil=SET, legs_hit=LUNGE_R2))
anim("gs_3", 24, heavy(8, 24, dict(rightLeg=(32, 0, 0, 10), leftLeg=(-48, 0, 0, 46)), 18, low=-5))
anim("gs_cleave", 26, heavy(10, 26, dict(rightLeg=(36, 0, 0, 12), leftLeg=(-52, 0, 0, 52)), 20, low=0))
anim("gs_whirl", 8,  # 휠윈드 (꾹 누르는 동안 반복): 대검을 옆으로 뻗고 몸 전체가 한 바퀴
    P(0, LIN, body=(-6, 0, 0), rightArm=(-22, -10, 0, -4), leftArm=(-26, 20, 0, -6), head=(0, 0, 0),
      rightLeg=(8, 0, 0, 16), leftLeg=(-12, 0, 0, 16))
    + P(7, LIN, body=(-6, -360, 0)) + P(8, CONST, body=(-6, 0, 0)), loop=True, ret=0)

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
GUARD = dict(body=(0, -10, 0), head=(0, -10, 0), rightArm=(-62, -28, 6, -110), leftArm=(-66, 28, -6, -112))  # 두 팔뚝을 얼굴 앞에 세움
anim("guard", 20, P(0, OS, **GUARD) + P(20, LIN, **GUARD), loop=True, ret=1)
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
