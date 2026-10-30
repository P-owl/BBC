package bbc.combat;

/**
 * 공격 한 번의 성질. 평타·기술 모두 이걸로 Combat.hit 을 부른다.
 *
 * @param damage     기본 피해 (콤보 보정 전)
 * @param kind       맞은 쪽이 들어가는 상태
 * @param stun       경직 틱 (kind 가 DOWN/KNOCK 이면 다운 틱)
 * @param push       앞으로 미는 힘 (블록/틱)
 * @param lift       위로 띄우는 힘 (블록/틱, 음수면 내리꽂음)
 * @param guardDmg   막혔을 때 가드 게이지를 깎는 양
 * @param guardBreak 막혀도 가드를 깨뜨리는가
 * @param downHit    다운된 상대에게도 들어가는가 (다운 추가타)
 * @param unparriable 패링 불가 (보스 패턴 같은 특별한 공격에만 따로 붙임. 막거나 피해야 함)
 */
public record Hit(float damage, Kind kind, int stun, double push, double lift,
                  double guardDmg, boolean guardBreak, boolean downHit, boolean unparriable) {

    /** 보통 공격 (패링 가능) */
    public Hit(float damage, Kind kind, int stun, double push, double lift, double guardDmg, boolean guardBreak, boolean downHit) {
        this(damage, kind, stun, push, lift, guardDmg, guardBreak, downHit, false);
    }

    /** 같은 공격을 패링 불가로 */
    public Hit noParry() { return new Hit(damage, kind, stun, push, lift, guardDmg, guardBreak, downHit, true); }

    public enum Kind {
        /** 지상 경직 */
        HIT,
        /** 공중 경직 (낙하가 느려짐) */
        AIR,
        /** 바로 다운 (내려찍기) */
        DOWN,
        /** 날아갔다가 다운 (5타) */
        KNOCK
    }

    public Hit withDamage(float d) { return new Hit(d, kind, stun, push, lift, guardDmg, guardBreak, downHit, unparriable); }
    public Hit withStun(int s) { return new Hit(damage, kind, s, push, lift, guardDmg, guardBreak, downHit, unparriable); }
}
