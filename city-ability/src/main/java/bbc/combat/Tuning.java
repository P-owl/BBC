package bbc.combat;

/**
 * 전투 수치표 (기획안 1부 ‘수치표’). 단위는 틱 (20틱 = 1초). 테스트하면서 여기 숫자만 고치면 된다.
 * ABA 영상(60fps, 키 표시·경직 더미)에서 잰 값을 기준으로 맞췄다.
 *   평타 간격 0.37초 · 첫 평타 선딜 약 0.22초 · 평타 1대 경직 약 0.5초 · 다운 슬램 후 다시 움직이기까지 약 0.93초
 */
public final class Tuning {
    private Tuning() {}

    // ── 평타 (한 사슬 = 5타, 땅·공중 섞어서 셈) ──
    /** 평타: 선딜 4 · 판정 1 · 후딜 2 → 다음 평타까지 7틱 (ABA 0.37초). 경직 10 (ABA 0.5초) → 여유 3틱 */
    public static final int M1_STARTUP = 4, M1_RECOVERY = 2;
    public static final Hit M1 = new Hit(1.0f, Hit.Kind.HIT, 10, 0.12, 0.0, 12, false, false);
    /** 평타마다 앞으로 붙는 속도 (상대가 멀어지지 않게) */
    public static final double M1_TRACK = 0.28;
    /** 5타 넉백: 선딜 5 · 후딜 14. 날려보내고 떨어지면 다운. 가드는 못 깸 (ABA처럼 가드 깨기는 기술 몫) */
    public static final int FINISH_STARTUP = 5, FINISH_NEXT = 20;
    public static final Hit FINISH = new Hit(2.0f, Hit.Kind.KNOCK, 16, 1.25, 0.35, 30, false, false);
    /** 업 플링: 5타째에 스페이스를 누르고 있으면. 상대만 높이 띄움 (약 6칸). 떨어지는 상대를 땅에서 평타로 다시 잡는다 (래더) */
    public static final int FLING_STARTUP = 5, FLING_RECOVERY = 8;
    public static final Hit UP_FLING = new Hit(1.5f, Hit.Kind.AIR, 40, 0.0, 1.15, 12, false, false);
    /** 뜨기: 2~4타째에 스페이스를 누르고 있으면 상대와 같이 살짝 뜸 → 공중 평타로 이어짐 */
    public static final double HOP_VY = 0.5;
    public static final Hit HOP = new Hit(1.0f, Hit.Kind.AIR, 14, 0.05, 0.52, 12, false, false);
    /** 공중 평타: 경직 12, 둘 다 낙하가 느려짐 */
    public static final Hit AIR_M1 = new Hit(1.0f, Hit.Kind.AIR, 12, 0.08, 0.12, 12, false, false);
    /** 다운 슬램: 공중에서 스페이스를 떼고 5타째. 땅에 꽂히고 튕김. 다운 11 + 떨어지는 시간 8 ≈ 0.95초 */
    public static final int SLAM_STARTUP = 4, SLAM_RECOVERY = 6;
    public static final Hit SLAM = new Hit(2.0f, Hit.Kind.DOWN, 11, 0.1, -1.6, 20, false, false);
    /** 튕김: 꽂힌 상대가 이만큼 튀어 오르고, 이 틱 동안은 기술(평타 말고)이 들어감 = 익스텐더 자리 */
    public static final double BOUNCE_VY = 0.32;
    public static final int BOUNCE_WINDOW = 10;
    /** 공중 래더: 공중에서 스페이스를 누른 채 5타째 → 둘 다 다시 띄우고 사슬 처음부터 (상대당 콤보 1번) */
    public static final Hit LADDER = new Hit(1.0f, Hit.Kind.AIR, 24, 0.05, 0.7, 12, false, false);
    public static final double LADDER_VY = 0.62;
    /** 넉백으로 날아간 상대가 땅에 떨어진 뒤 다운 */
    public static final int KNOCK_DOWN = 12;
    /** 평타 사슬이 끊기는 시간 (마지막 평타 후). 박자를 일부러 늦춰도 이어지게 넉넉히 */
    public static final int CHAIN_RESET = 14;
    /** 입력 버퍼: 다음 평타가 가능해지기 이 틱 전부터 누른 건 기억했다가 바로 나감 */
    public static final int INPUT_BUFFER = 4;
    /** 평타 판정: 내 앞 REACH 칸에 중심을 둔 구 (반지름 RADIUS). 직업마다 바꿀 수 있게 나중에 직업 쪽으로 옮긴다 */
    public static final double M1_REACH = 1.4, M1_RADIUS = 1.6;
    public static final int M1_MAX_TARGETS = 3;

    // ── 대시 추격 ──
    /** 앞 대시: 최근 이만큼 안에 내가 날리거나 띄운 상대가 앞쪽 이 거리 안에 있으면 그쪽으로 붙는다 */
    public static final int CHASE_MEMORY = 40;
    public static final double CHASE_RANGE = 14;

    // ── 콤보 제한 ──
    /** 같은 콤보에서 3타마다 경직 -1 (최소 8, 평타 간격 7보다 길게) */
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
