package bbc.job;

import bbc.core.PlayerData;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;

/** 모든 직업의 공통 틀. 등급은 jd 의 "<prefix>rank" 에 저장한다. */
public interface JobType {
    String id();
    String name();
    /** 데이터 키 앞머리 (예: "tr.") */
    String prefix();
    Item icon();
    Formatting color();
    int maxRank();
    String rankName(int rank);
    /** 직업 선택 창 설명 */
    String[] summary();
    /** 직업 선택 창에서 바로 고를 수 있는가 (특수한 방법으로만 되는 직업은 false) */
    default boolean selectable() { return true; }
    /** 직업 평타 세트 (사슬 길이·선딜·피해·사거리·모션). null 이면 맨손 기본 */
    default bbc.combat.Weapon.M1Profile m1() { return null; }
    /** 전투 기술 (없으면 null) */
    default JobSkills.Set skills() { return null; }
    default void onJoin(ServerPlayerEntity p, PlayerData.Entry e) {}
    default void onQuit(ServerPlayerEntity p, PlayerData.Entry e) {}
    /** 각성 시작 (강화형은 버프, 교체형은 스킬 묶음 바꾸기 등) */
    default void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {}
    default void onAwakenEnd(ServerPlayerEntity p, PlayerData.Entry e) {}
    /** 각성기. 썼으면 true. false 를 돌려주면 공통 각성기 ‘난무’가 나간다 */
    default boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) { return false; }
}
