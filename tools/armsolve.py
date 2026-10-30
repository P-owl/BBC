"""방향으로 팔 자세 정하기: 위팔이 가리킬 방향 u, 칼끝이 가리킬 방향 v (몸 기준: x=오른쪽, y=위, z=뒤, 앞=-z) → (pitch, yaw, roll, bend)
bend 가 있으면 칼과 위팔 사이 각이 정해지므로 v 는 u 둘레의 '면'만 쓰임."""
import math
import numpy as np

def _n(v):
    v = np.array(v, float); return v / np.linalg.norm(v)

def arm(u, v, bend=0.0, z=None):
    F = np.diag([-1.0, -1.0, 1.0])
    U = F @ _n(u)
    V = F @ _n(v)
    b = math.radians(bend)
    # RB (x축 둘레 bend) 를 -e_z 에 적용한 것
    c, s = math.cos(b), math.sin(b)
    w = np.array([0.0, s, -c])          # Rx(b) @ (0,0,-1) = (0, s, -c)
    perp = V - (V @ U) * U
    if np.linalg.norm(perp) < 1e-6: perp = np.cross(U, [1.0, 0, 0])
    nrm = perp / np.linalg.norm(perp)
    # R e_y = U, R w = V' 로 두고 V' = wy U + wz Ez  →  Ez = R e_z
    # w 의 z 성분 -c 는 e_z 계수 → R e_z = -(V' - s U)/c, 여기서 V' 를 U 와 이루는 각이 맞게 만듦: V' = s U + c * nrm
    Ez = -nrm if c >= 0 else nrm
    Ey = U
    Ex = np.cross(Ey, Ez)
    R = np.column_stack([Ex, Ey, Ez])
    yaw = math.asin(max(-1, min(1, -R[2, 0])))
    pitch = math.atan2(R[2, 1], R[2, 2])
    roll = math.atan2(R[1, 0], R[0, 0])
    out = (round(math.degrees(pitch), 1), round(math.degrees(yaw), 1), round(math.degrees(roll), 1), bend)
    return out if z is None else out + (None, z)

def check(t):
    """(pitch,yaw,roll,bend) → (위팔 방향, 칼끝 방향) 몸 기준"""
    import preview_motions as PM
    p, y, r, bd = [math.radians(a) for a in t[:4]]
    R = PM.Rz(r) @ PM.Ry(y) @ PM.Rx(p)
    RB = PM.Rx(bd)
    F = np.diag([-1.0, -1.0, 1.0])
    return np.round(F @ R @ np.array([0, 1.0, 0]), 2), np.round(F @ R @ RB @ np.array([0, 1.0, 0]), 2), np.round(F @ R @ RB @ np.array([0, 0, -1.0]), 2)

if __name__ == "__main__":
    for u, v, bd in [((0, -1, 0), (0, 0, -1), 0), ((0, 0, -1), (0, 1, 0), 0), ((1, 0, 0), (0, 0, -1), 0),
                     ((0, 0, -1), (-1, 0, 0), 0), ((0.7, 0, -0.7), (-0.7, 0.3, -0.7), 0), ((0, -1, 0), (0, 0.5, -1), -40)]:
        t = arm(u, v, bd); print(u, v, bd, '->', t, check(t))


def _wrap_near(a, ref):
    while a - ref > 180: a -= 360
    while a - ref < -180: a += 360
    return a

def near(t, prev):
    """같은 자세를 나타내는 두 오일러 각 중 이전 키와 가장 가까운 것 (보간이 엉뚱하게 돌지 않게)"""
    if prev is None: return t
    p, y, r = t[:3]
    cands = [(p, y, r), (p + 180, 180 - y, r + 180)]
    best = None
    for c in cands:
        c = tuple(_wrap_near(a, b) for a, b in zip(c, prev[:3]))
        d = sum(abs(a - b) for a, b in zip(c, prev[:3]))
        if best is None or d < best[0]: best = (d, c)
    return tuple(round(a, 1) for a in best[1]) + tuple(t[3:])
