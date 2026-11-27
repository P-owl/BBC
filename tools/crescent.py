# 빛 칼날 모델 치수 (gen_light_blade.py 가 LightBladeRender.java 와 렌더러 미리보기를 만듦).
# 오른팔 좌표: 픽셀, x 바깥 = - (팔 -3~1), y 아래 = + (어깨 위 -2 · 팔꿈치 4 · 손끝 10), z 앞 = -
# 참고 그림(팔 내린 정면)을 재서 옮김 — 팔 높이 415px = 12픽셀, 팔 폭 195px = 4픽셀:
#   토시: 아래팔 아래쪽 (y 5.2~9.0), 팔보다 조금 두꺼움, 위아래 은빛 테, 앞면에 둥근 장식(금빛 가운데)
#   칼날: 토시 바깥 아래쪽(y≈7.8)에서 돋아 팔에서 떨어져 바깥으로 완만히 휘며 어깨 위(y≈-2.4)까지 — 가장 바깥이 팔에서 2.6픽셀,
#         폭은 팔꿈치 높이에서 가장 넓고(≈1) 위 끝은 뾰족, 오목한 쪽이 팔을 향함
#   이음대: 팔꿈치 높이에서 검은 가는 막대 둘이 V 로 팔과 칼날 안쪽을 이음 (끝마다 작은 꼭지)
#   토시 아래 안쪽: 손 앞쪽으로 휘어 내려가는 은빛 갈고리 덮개 둘
import math, json

P0, P1, P2 = (-3.45, 7.8), (-4.75, 2.4), (-4.3, -2.4)   # 칼날 안쪽 가장자리: 뿌리 · 가운데 · 끝

def circle(a, b, c):
    ax, ay = a; bx, by = b; cx, cy = c
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return ux, uy, math.hypot(ax - ux, ay - uy)

def blade(n=20, w0=0.7, wmax=1.25, wtip=0.1, peak=0.42):
    """[가운데 x, 가운데 y, 폭(바깥으로), 길이, 각도°(폭 방향 = 반지름 바깥)] — 뿌리에서 끝까지"""
    cx, cy, R = circle(P0, P1, P2)
    a0, a2 = math.atan2(P0[1] - cy, P0[0] - cx), math.atan2(P2[1] - cy, P2[0] - cx)
    if a2 < a0: a2 += 2 * math.pi
    span = a2 - a0
    out = []
    for i in range(n):
        u = (i + 0.5) / n
        ang = a0 + span * u
        w = w0 + (wmax - w0) * math.sin(math.pi / 2 * u / peak) if u < peak else wtip + (wmax - wtip) * math.cos(math.pi / 2 * (u - peak) / (1 - peak)) ** 0.85
        rm = R + w / 2
        out.append([round(cx + rm * math.cos(ang), 3), round(cy + rm * math.sin(ang), 3), round(w, 3), round(R * abs(span) / n * 1.2, 3), round(math.degrees(ang), 2)])
    return out, (cx, cy, R)

def inner_x_at(y):
    cx, cy, R = circle(P0, P1, P2)
    return cx - math.sqrt(max(0, R * R - (y - cy) ** 2))

if __name__ == '__main__':
    s, c = blade()
    print('원', [round(v, 2) for v in c], '가장 바깥', round(min(p[0] - p[2] / 2 for p in s), 2))
    print(json.dumps(s))
