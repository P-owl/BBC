# 빛 칼날 모델(LightBladeRender.java) 만들기: python3 tools/gen_light_blade.py [미리보기용 상자 json]
# 치수는 crescent.py. 상자 둘 종류: 돌린 상자 ROT [구역, 가운데 x, y, z, 폭, 길이, 두께, 각도°] · 곧은 상자 BOX [구역, x0, y0, z0, sx, sy, sz]
import sys, os, json, math
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from crescent import blade, inner_x_at
T = 0.45
SEG, _ = blade()
ROT_BLADE = []   # 칼날 (각성 때 따로 키움)
for s in SEG:
    x, y, w, L, a = s
    ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
    def at(lx): return x + lx * ca, y + lx * sa
    ROT_BLADE.append(['S', x, y, 0, w, L, T, a])                                     # 칼몸
    ex, ey = at(w / 2 - 0.12); ROT_BLADE.append(['E', ex, ey, 0, 0.26, L, T + 0.06, a])   # 바깥 날 (밝게)
    if w > 0.45:
        ix, iy = at(-w / 2 + 0.07); ROT_BLADE.append(['D', ix, iy, 0, 0.16, L, T + 0.04, a])   # 안쪽 등 (짙은 파랑)
def rod(a, b, wdt, region, z=0):
    dx, dy = b[0] - a[0], b[1] - a[1]
    return [region, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, z, wdt, math.hypot(dx, dy) + 0.1, wdt, math.degrees(math.atan2(-dx, dy)) ]
A = (-3.15, 3.9)                                   # 이음대가 팔에 붙는 자리 (팔꿈치 바깥)
B1, B2 = (inner_x_at(2.8) + 0.1, 2.8), (inner_x_at(4.6) + 0.1, 4.6)
ROT = [rod(A, B1, 0.22, 'K'), rod(A, B2, 0.22, 'K')]
BOX = [
    ['M', -3.4, 5.4, -2.4, 4.8, 3.4, 4.8],             # 토시 몸통
    ['B', -3.5, 5.1, -2.5, 5.0, 0.4, 5.0],             # 윗테
    ['B', -3.5, 8.75, -2.5, 5.0, 0.45, 5.0],           # 아랫테
    ['D', -3.45, 6.05, -2.45, 4.9, 0.12, 4.9],         # 윗줄
    ['D', -3.45, 8.35, -2.45, 4.9, 0.12, 4.9],         # 아랫줄
    ['B', -1.7, 6.55, -2.65, 1.4, 1.0, 0.3],           # 앞 둥근 장식 (십자로 겹쳐 둥글게)
    ['B', -1.5, 6.35, -2.65, 1.0, 1.4, 0.3],
    ['D', -1.4, 6.65, -2.72, 0.8, 0.8, 0.1],           # 장식 안쪽 테
    ['G', -1.25, 6.8, -2.82, 0.5, 0.5, 0.2],           # 금빛 가운데
    ['B', -1.15, 7.9, -2.6, 0.3, 0.3, 0.2],            # 아래 작은 못
    ['M', -3.85, 6.6, -0.5, 0.5, 2.0, 1.0],            # 칼날 뿌리 받침 (토시 바깥)
    ['B', -3.95, 6.9, -0.6, 0.15, 1.4, 1.2],
    ['B', A[0] - 0.3, A[1] - 0.3, -0.3, 0.6, 0.6, 0.6],   # 이음대 팔 쪽 꼭지
    ['K', B1[0] - 0.25, B1[1] - 0.25, -0.3, 0.5, 0.5, 0.6],   # 칼날 쪽 꼭지
    ['K', B2[0] - 0.25, B2[1] - 0.25, -0.3, 0.5, 0.5, 0.6],
]
def f(v): s = ('%.3f' % v).rstrip('0').rstrip('.'); return (s if s not in ('-0', '') else '0') + 'f'
def rows(lst): return ',\n'.join('            {%s, %s}' % (r[0], ', '.join(f(x) for x in r[1:])) for r in lst)
tmpl = open(os.path.join(HERE, 'LightBladeRender.tmpl')).read()
java = tmpl.replace('%BLADE%', rows(ROT_BLADE)).replace('%ROT%', rows(ROT)).replace('%BOX%', rows(BOX))
open(os.path.join(HERE, '..', 'city-ability', 'src', 'main', 'java', 'bbc', 'client', 'LightBladeRender.java'), 'w').write(java)
COL = {'S': 0x7d9ad8, 'E': 0xeef4ff, 'D': 0x23356e, 'M': 0x2a3150, 'B': 0x9fb0cc, 'K': 0x121318, 'G': 0xd2a23c}
out = []
for r in ROT_BLADE + ROT: out.append(dict(c=[r[1], r[2], r[3]], s=[r[4], r[5], r[6]], a=r[7], col=COL[r[0]]))
for b in BOX: out.append(dict(c=[b[1] + b[4] / 2, b[2] + b[5] / 2, b[3] + b[6] / 2], s=[b[4], b[5], b[6]], a=0, col=COL[b[0]]))
if len(sys.argv) > 1: json.dump(out, open(sys.argv[1], 'w'))
print(len(ROT_BLADE), len(ROT), len(BOX))
