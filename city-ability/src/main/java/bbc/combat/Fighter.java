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

    // ── 때리는 쪽 ──
    int m1Index;          // 0~4 (다음에 나갈 지상 평타)
    int airHits;          // 공중 평타 몇 대 쳤나
    long m1ReadyAt;       // 이 틱부터 다음 평타 가능
    long m1ChainUntil;    // 이 틱이 지나면 평타 사슬 처음부터
    long recoveryUntil;   // 후딜이 끝나는 틱 (대시 캔슬 판정)
    long airComboUntil;   // 공중 콤보 중 (나도 낙하가 느려짐)
    long lastAirHitAt;
    int buffered;         // 0 없음, 1 평타, 2 스페이스+평타
    long bufferedAt;
    int token;            // 선딜 중인 공격을 취소할 때 올림
    long comboShowUntil;  // 액션바에 콤보 표시 중

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

    // ── 적용 중인 속성 (바뀔 때만 다시 보냄) ──
    double curSpeed, curJump, curGrav;
    long touchedAt;

    public State state() { return state; }
    public boolean stunned() { return state != State.IDLE; }
    public int comboHits() { return comboHits; }
    public double guardGauge() { return guardGauge; }
    public boolean guarding() { return guardHeld; }
}
