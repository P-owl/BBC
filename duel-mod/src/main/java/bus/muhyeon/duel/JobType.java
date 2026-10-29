package bus.muhyeon.duel;

import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;

/**
 * 연금술사 이후에 추가되는 직업들의 공통 틀.
 * 등급은 각자 jd 의 "<prefix>rank" 에 저장한다 (승급은 각 직업이 활동 이벤트에서 직접 처리).
 */
public interface JobType {
    String id();
    String name();
    /** 데이터 키 앞머리 (예: "doc.") */
    String prefix();
    Item icon();
    Formatting color();
    int maxRank();
    String rankName(int rank);
    /** 직업 선택 창 설명 */
    String[] summary();
    /** /job 직업 선택 창에서 바로 고를 수 있는가 (뱀파이어는 석가면으로만) */
    default boolean selectable() { return true; }
    /** 입문 화면 (조건·되기 버튼) */
    void openDetail(ServerPlayerEntity p);
    /** 내 직업 화면 */
    void openStatus(ServerPlayerEntity p);
    /** 전투 모드 기술 (없으면 null) */
    default JobSkills.Set skills() { return null; }
    /** 직업을 얻은 직후 */
    default void onJoin(ServerPlayerEntity p, AlchemyData.Entry e) {}
    /** 직업을 그만두기 직전 (버프 해제 등) */
    default void onQuit(ServerPlayerEntity p, AlchemyData.Entry e) {}
    /** 관리자 명령: 등급 바로 설정 */
    default void adminRank(ServerPlayerEntity p, AlchemyData.Entry e, int rank) { e.num(prefix() + "rank", rank); }
    /** 관리자 명령: 등급 지표(포인트) 더하기 */
    default void adminPoints(ServerPlayerEntity p, AlchemyData.Entry e, double amount) {}
    /** 관리자 info 에 덧붙일 줄 */
    default String adminInfo(AlchemyData.Entry e) { return ""; }
}
