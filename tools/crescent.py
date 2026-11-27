# 빛 칼날 모델 치수 (LightBladeRender 와 렌더러 미리보기가 같이 씀). 오른팔 좌표: 픽셀, x 바깥 = -, y 아래 = + (어깨 피벗 0, 손 끝 10)
# 참고 그림: 아래팔 아래쪽 강철 토시 · 토시 바깥 둥근 장식에서 받침대가 뻗어 칼날 가운데를 붙잡음 ·
#   칼날은 주먹 아래에서 안으로 살짝 휜 갈고리로 시작해 바깥으로 크게 휘며 어깨 위까지 — 가운데가 두껍고 양끝이 뾰족, 오목한 쪽이 팔을 향함
import math, json

def blade(cx=3.0, cy=5.2, R=9.3, a0=131, a1=226, n=22, wmax=1.45, wmin=0.15, peak=0.45):
    """초승달 마디 [가운데 x, 가운데 y, 폭(반지름 방향), 길이(접선), 각도°]. 안쪽 가장자리 = 반지름 R 원"""
    out = []
    span = math.radians(a1 - a0)
    for i in range(n):
        um = (i + 0.5) / n
        ang = math.radians(a0) + span * um
        # 폭: peak 에서 가장 두껍고 양끝 뾰족 (아래 갈고리 쪽이 조금 더 빨리 가늘어짐)
        k = um / peak if um < peak else (1 - um) / (1 - peak)
        w = wmin + (wmax - wmin) * math.sin(math.pi / 2 * min(1, k)) ** 0.8
        rm = R + w / 2
        px, py = cx + rm * math.cos(ang), cy + rm * math.sin(ang)
        L = rm * span / n * 1.2
        out.append([round(px, 3), round(py, 3), round(w, 3), round(L, 3), round(math.degrees(ang), 2)])
    return out

def inner_x_at(y, cx=3.0, cy=5.2, R=9.3):
    """높이 y 에서 칼날 안쪽 가장자리 x"""
    return cx - math.sqrt(R * R - (y - cy) ** 2)

if __name__ == '__main__':
    print(json.dumps(blade()))
    print('받침대가 닿는 칼날 안쪽 x (y=7.4):', round(inner_x_at(7.4), 2))
