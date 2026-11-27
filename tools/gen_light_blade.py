import sys, json, math
sys.path.insert(0, '/home/claude/bbc/tools')
from crescent import blade, inner_x_at
T = 0.5
SEG = blade()
jx = inner_x_at(7.4)   # 받침대가 닿는 칼날 안쪽
# 토시·받침대 상자 [구역, x0, y0, z0, sx, sy, sz] (오른팔)
BR = [
    ['M', -3.45, 5.4, -2.45, 4.9, 4.3, 4.9],          # 토시 몸통
    ['B', -3.55, 5.2, -2.55, 5.1, 0.45, 5.1],          # 윗테
    ['B', -3.55, 9.35, -2.55, 5.1, 0.45, 5.1],         # 아랫테
    ['K', -3.5, 7.25, -2.5, 5.0, 0.3, 5.0],            # 가운데 홈
    ['M', -3.0, 5.85, -2.75, 4.0, 3.3, 0.3],           # 앞 판
    ['M', -3.0, 5.85, 2.45, 4.0, 3.3, 0.3],            # 뒤 판
    ['M', -3.85, 5.85, -1.6, 0.4, 3.3, 3.2],           # 바깥 판
    ['K', 1.45, 6.4, -1.1, 0.3, 2.1, 2.2],             # 안쪽 끈
    ['B', 1.7, 7.0, -0.55, 0.2, 0.9, 1.1],             # 끈 고리
    ['B', -4.35, 6.8, -1.1, 0.5, 1.2, 2.2],            # 둥근 장식 (십자로 겹쳐 둥글게)
    ['B', -4.35, 6.6, -0.9, 0.5, 1.6, 1.8],
    ['K', -4.5, 7.15, -0.35, 0.2, 0.5, 0.7],           # 장식 가운데
    ['M', jx - 0.15, 6.85, -0.55, -4.35 - jx + 0.2, 1.1, 1.1],   # 받침대 (장식 → 칼날)
    ['B', jx - 0.15, 6.7, -0.3, -4.35 - jx + 0.2, 0.2, 0.6],     # 받침대 위 날개
    ['B', jx - 0.15, 7.95, -0.3, -4.35 - jx + 0.2, 0.2, 0.6],    # 받침대 아래 날개
    ['B', jx - 0.65, 6.35, -0.5, 0.9, 2.1, 1.0],                 # 칼날을 무는 죔쇠
    ['K', jx - 0.45, 7.1, -0.6, 0.45, 0.55, 1.2],                # 죔쇠 못
]
for x in (-2.6, 0.1):   # 앞뒤 판 못 넷씩
    for y in (6.15, 8.5):
        BR.append(['B', x, y, -2.95, 0.5, 0.5, 0.25]); BR.append(['B', x, y, 2.7, 0.5, 0.5, 0.25])
def seg_boxes(s):
    w, L = s[2], s[3]
    out = [['S', -w / 2, -L / 2, -T / 2, w, L, T], ['E', w / 2 - 0.22, -L / 2, -T / 2 - 0.04, 0.3, L, T + 0.08]]
    if w > 0.5: out.append(['K', -w / 2 - 0.05, -L / 2, -T / 2 - 0.03, 0.2, L, T + 0.06])     # 안쪽 등줄
    if w > 0.85: out.append(['K', -w * 0.1 - 0.06, -L / 2, -T / 2 - 0.02, 0.12, L, T + 0.04])  # 가운데 홈
    return out
def f(v): return ('%.3f' % v).rstrip('0').rstrip('.') + 'f'
segs = ',\n            '.join('{%s}' % ', '.join(f(x) for x in s) for s in SEG)
brs = '\n'.join('            box(%s, %s, %s, %s, %s, %s, %s),' % (b[0], *(f(x) for x in b[1:])) for b in BR)
java = open('/tmp/claude-0/-home-claude/88b26540-202a-5f90-956c-a81d219c7c46/scratchpad/LightBladeRender.tmpl').read()
java = java.replace('%SEGS%', segs).replace('%BRACER%', brs.rstrip(','))
open('/home/claude/bbc/city-ability/src/main/java/bbc/client/LightBladeRender.java', 'w').write(java)
# 렌더러용: 모든 상자를 (가운데, 크기, 각도, 색) 로
COL = {'S': 0x5a6fa6, 'E': 0xd6e2f8, 'M': 0x262c46, 'B': 0xa9b0c4, 'K': 0x14161f}
R = []
for s in SEG:
    a = s[4]
    for b in seg_boxes(s):
        lx, ly = b[1] + b[4] / 2, b[2] + b[5] / 2
        ca, sa = math.cos(math.radians(a)), math.sin(math.radians(a))
        R.append(dict(c=[s[0] + lx * ca - ly * sa, s[1] + lx * sa + ly * ca, b[3] + b[6] / 2], s=[b[4], b[5], b[6]], a=a, col=COL[b[0]]))
for b in BR:
    R.append(dict(c=[b[1] + b[4] / 2, b[2] + b[5] / 2, b[3] + b[6] / 2], s=[b[4], b[5], b[6]], a=0, col=COL[b[0]]))
json.dump(R, open('/tmp/claude-0/-home-claude/88b26540-202a-5f90-956c-a81d219c7c46/scratchpad/blade_boxes.json', 'w'))
print(len(SEG), len(BR), len(R))
