# 초승달 칼날 마디: 오른팔 좌표 (x 바깥 = -, y 아래 = +). 안쪽 가장자리는 반지름 R 원, 바깥은 R + 폭(가운데가 가장 두껍고 양끝이 뾰족)
import math, json
def segs(cx=-3.4, cy=4.2, R=3.8, a0=80, a1=255, n=16, wmax=3.0, wmin=0.4):
    out = []
    span = math.radians(a1 - a0)
    for i in range(n):
        u0, u1 = i / n, (i + 1) / n
        um = (u0 + u1) / 2
        ang = math.radians(a0) + span * um
        w = wmin + (wmax - wmin) * math.sin(math.pi * um) ** 0.9
        rm = R + w / 2
        px, py = cx + rm * math.cos(ang), cy + rm * math.sin(ang)
        L = rm * span / n * 1.18
        roll = math.degrees(ang)      # 상자 x 축(폭) = 반지름 방향, y 축(길이) = 접선
        out.append([round(px, 3), round(py, 3), round(w, 3), round(L, 3), round(roll, 2)])
    return out
if __name__ == '__main__':
    print(json.dumps(segs()))
