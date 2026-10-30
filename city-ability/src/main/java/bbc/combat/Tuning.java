package bbc.combat;

/**
 * 전투 수치표 (기획안 1부 ‘수치표’). 단위는 틱 (20틱 = 1초). 테스트하면서 여기 숫자만 고치면 된다.
 */
public final class Tuning {
    private Tuning() {}

    // ── 평타 ──
    /** 평타 1~4타: 선딜 3 · 판정 1 · 후딜 3 → 다음 평타까지 7틱, 경직 14 */
    public static final int M1_STARTUP = 3, M1_RECOVERY = 3;
    public static final Hit M1 = new Hit(1.0f, Hit.Kind.HIT, 14, 0.12, 0.0, 12, false, false);
    /** 5타: 선딜 5 · 후딜 16, 6칸 날려보내고 다운 16, 가드 깨기. 다음 평타까지 20틱 */
    public static final int FINISH_STARTUP = 5, FINISH_NEXT = 20;
    public static final Hit FINISH = new Hit(2.0f, Hit.Kind.KNOCK, 16, 1.25, 0.35, 30, true, false);
    /** 띄우기: 3타 뒤 스페이스+좌클릭. 선딜 4 · 후딜 4, 3.5칸 띄움, 공중 경직 24. 나도 같이 뜀 */
    public static final int LAUNCH_STARTUP = 4, LAUNCH_RECOVERY = 4;
    public static final double LAUNCH_VY = 0.78;
    public static final Hit LAUNCH = new Hit(1.5f, Hit.Kind.AIR, 24, 0.05, LAUNCH_VY, 12, false, false);
    /** 공중 평타 3타: 경직 14, 둘 다 낙하가 느려짐 */
    public static final Hit AIR_M1 = new Hit(1.0f, Hit.Kind.AIR, 14, 0.08, 0.12, 12, false, false);
    /** 내려찍기: 공중 4타. 선딜 4 · 착지 후딜 6, 다운 20 */
    public static final int SLAM_STARTUP = 4, SLAM_RECOVERY = 6;
    public static final Hit SLAM = new Hit(2.0f, Hit.Kind.DOWN, 20, 0.1, -1.6, 20, false, false);
    /** 래더: 공중 3타 뒤 0.5초 멈췄다가 스페이스+좌클릭 → 다시 띄움 (콤보당 1번) */
    public static final int LADDER_PAUSE = 10;
    public static final Hit LADDER = new Hit(1.0f, Hit.Kind.AIR, 24, 0.05, 0.7, 12, false, false);
    /** 평타 사슬이 끊기는 시간 (마지막 평타 후) */
    public static final int CHAIN_RESET = 14;
    /** 입력 버퍼: 다음 평타가 가능해지기 이 틱 전부터 누른 건 기억했다가 바로 나감 */
    public static final int INPUT_BUFFER = 4;
    /** 사거리·각도 (핑 여유 0.3칸 포함) */
    public static final double M1_RANGE = 3.3, M1_HALF_ANGLE_DEG = 35;
    public static final int M1_MAX_TARGETS = 3;

    // ── 콤보 제한 ──
    /** 같은 콤보에서 3타마다 경직 -1 (최소 8) */
    public static final int STUN_DECAY_EVERY = 3, STUN_MIN = 8;
    /** 10타까지 100%, 이후 타당 -4%, 최저 60% */
    public static final int SCALE_FREE_HITS = 10;
    public static final float SCALE_PER_HIT = 0.04f, SCALE_MIN = 0.6f;
    /** 경직이 끝나고 이만큼 안 맞으면 콤보 끝 */
    public static final int COMBO_END_GAP = 10;
    /** 기상 무적 */
    public static final int WAKEUP_INVULN = 10;
    /** 공중 경직 중 중력 배율 (내려올 때만) */
    public static final double AIR_GRAVITY = 0.3;

    // ── 가드·패링 ──
    public static final double GUARD_MAX = 100, GUARD_REGEN_PER_TICK = 0.5; // 초당 10
    public static final int GUARD_REGEN_DELAY = 40;                         // 2초 안 막으면 회복
    public static final float GUARD_DAMAGE_TAKEN = 0.2f;                    // 피해 80% 감소
    public static final double GUARD_SPEED = -0.6;                          // 가드 중 이동 -60%
    public static final int GUARD_BROKEN_STUN = 30;
    public static final double VANILLA_GUARD_DMG = 12;                      // 몬스터 공격을 막을 때
    public static final int PARRY_WINDOW = 4, PARRY_FAIL_LOCK = 15, PARRY_STUN = 20;

    // ── 대시·회피기 ──
    public static final int DASH_TICKS = 6, DASH_INVULN = 4, DASH_COOLDOWN = 40;
    public static final double DASH_SPEED = 0.85;                           // 6틱에 약 5칸
    public static final double DASH_COST = 15, DASH_CANCEL_COST = 25;
    public static final int EVADE_INVULN = 8, EVADE_COOLDOWN = 600;
    public static final double EVADE_PUSH = 0.9;                            // 약 4칸
}
