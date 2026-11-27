# 빛 칼날(초승달) 마디: 오른팔 좌표 (x 바깥 = -, y 아래 = +). 안쪽 가장자리는 반지름 R 원, 바깥은 R + 폭.
# 참고 그림: 아래팔 팔찌 바깥에서 돋아 바깥으로 휘며 어깨 높이까지 올라가는 가는 낫 — 아래(뿌리)가 두껍고 위 끝이 뾰족, 오목한 쪽이 팔을 향함
import math, json
def segs(cx=-0.6, cy=3.2, R=5.6, a0=118, a1=238, n=18, wmax=1.25, wmin=0.18):
    out = []
    span = math.radians(a1 - a0)
    for i in range(n):
        um = (i + 0.5) / n
        ang = math.radians(a0) + span * um
        w = wmin + wmax * math.sin(math.pi * (0.25 + 0.75 * um))   # 뿌리 두껍게 → 1/3 지점 가장 두껍고 → 끝 뾰족
        rm = R + w / 2
        px, py = cx + rm * math.cos(ang), cy + rm * math.sin(ang)
        L = rm * span / n * 1.18
        out.append([round(px, 3), round(py, 3), round(w, 3), round(L, 3), round(math.degrees(ang), 2)])
    return out
if __name__ == '__main__':
    print(json.dumps(segs()))
