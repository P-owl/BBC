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
    /** 지금 이 플레이어의 평타 세트 (예: 연금술사 무기 연성 중엔 창). 기본은 m1() */
    default bbc.combat.Weapon.M1Profile m1(ServerPlayerEntity p) { return m1(); }
    /** 직업 대기 자세 모션 (맨손일 때 전투 모드 아래층). null 이면 공통 'stance' */
    default String stance() { return null; }
    /** 평타 판정 순간 (모션 이름으로) — 직업별 연출 (예: 연금술사 4·5타 연성) */
    default void m1Fx(ServerPlayerEntity p, String motion) {}
    /** 전투 기술 (없으면 null) */
    default JobSkills.Set skills() { return null; }
    default void onJoin(ServerPlayerEntity p, PlayerData.Entry e) {}
    default void onQuit(ServerPlayerEntity p, PlayerData.Entry e) {}
    /** 각성 시작 (강화형은 버프, 교체형은 스킬 묶음 바꾸기 등) */
    default void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {}
    default void onAwakenEnd(ServerPlayerEntity p, PlayerData.Entry e) {}
    /** 평타 모션을 튼 순간 (판정 전). 판정 순간은 m1Fx */
    default void m1Start(ServerPlayerEntity p, String motion) {}
    /** 각성기. 썼으면 true. false 를 돌려주면 공통 각성기 ‘난무’가 나간다 */
    default boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) { return false; }
    /** 임플란트 키(H). 썼으면 true (연금술사: 오토메일 칼날) */
    default boolean implant(ServerPlayerEntity p, PlayerData.Entry e) { return false; }
    /** 직업 기술 피해의 기준 스탯 (null 이면 직업 숙련만). 예: 연금술사 = 지능 */
    default bbc.grow.Stat skillStat() { return null; }
    /** 궁극기 이름 (HUD 기술 목록 맨 아래). 직업 궁극기가 없으면 공통 '난무' */
    default String ultimateName() { return "난무"; }
}
