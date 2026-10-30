package bbc.combat;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/** 엔티티 하나의 전투 상태 (플레이어·몬스터 공통). 서버만 들고 있다. */
public final class Fighter {
    public enum State { IDLE, HITSTUN, AIR, DOWN, GUARD_BROKEN }

    final LivingEntity e;
    Fighter(LivingEntity e) { this.e = e; }

    // ── 맞는 쪽 ──
    State state = State.IDLE;
    long stateUntil;
    long stateEndedAt;
    int comboHits;
    float comboDamage;
    long lastHitAt;
    boolean ladderUsed;
    UUID lastAttacker;
    long invulnUntil;
    boolean knocked;      // 넉백으로 날아가는 중 (땅에 닿으면 다운)
    boolean slammed;      // 다운 슬램으로 꽂히는 중 (땅에 닿으면 튕김)
    long bounceUntil;     // 튕김 중: 기술이 들어가는 창
    int sentStun = -1;    // 클라이언트에 마지막으로 보낸 경직 (붉은 칠)
    boolean freshHit;     // 방금 맞음 → 클라이언트 움찔 다시 시작
    String dummyLast;     // 허수아비: 최근 콤보 기록

    // ── 때리는 쪽 ──
    int m1Index;          // 0~4 (다음에 나갈 평타, 땅·공중 합쳐서)
    long m1ReadyAt;       // 이 틱부터 다음 평타 가능
    long m1ChainUntil;    // 이 틱이 지나면 평타 사슬 처음부터
    long recoveryUntil;   // 후딜이 끝나는 틱 (대시 캔슬 판정)
    long airComboUntil;   // 공중 콤보 중 (나도 낙하가 느려짐)
    UUID lastTarget;      // 마지막으로 때린 상대 (대시 추격)
    long lastTargetAt;
    int buffered;         // 0 없음, 1 평타, 2 스페이스+평타
    long bufferedAt;
    int token;            // 선딜 중인 공격을 취소할 때 올림

    // ── 방어·이동 ──
    boolean guardHeld;
    double guardGauge = Tuning.GUARD_MAX;
    long guardUsedAt;
    long parryUntil;
    long parryLockUntil;
    long dashCdUntil;
    long evadeCdUntil;
    int dashTicks;
    Vec3d dashVel = Vec3d.ZERO;
    boolean dashChase;    // 추격 대시 (위아래로도 날아감)

    // ── 적용 중인 속성 (바뀔 때만 다시 보냄) ──
    double curSpeed, curJump, curGrav;
    long touchedAt;

    public State state() { return state; }
    public boolean stunned() { return state != State.IDLE; }
    public int comboHits() { return comboHits; }
    public double guardGauge() { return guardGauge; }
    public boolean guarding() { return guardHeld; }
}
