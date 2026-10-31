package bbc.grow;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

/**
 * 행동으로 쌓는 스탯 (Deepwoken식). 행동하면 경험치가 차고, 다 차면 '확정 대기' 1점 → 수련 도구를 쓰면 1점 확정.
 * 확정은 파워마다 투자 한도(파워 1~19 는 15점, 20부터 3점) 안에서만.
 */
public enum Stat {
    STR("str", "힘", "나무 베기·광질·근접 타격", "아령", Items.IRON_INGOT),
    END("end", "내구", "피해 받기·가드", "바위 들기 세트", Items.COBBLESTONE),
    AGI("agi", "민첩", "달리기·대시·회피", "모래주머니", Items.SAND),
    INT("int", "지능", "독서(책 우클릭)·직업 기술", "백과사전", Items.BOOK),
    LIGHT("light", "가벼운 무기 숙련", "검으로 타격", "검술 교본", Items.IRON_SWORD),
    MEDIUM("medium", "중간 무기 숙련", "창으로 타격", "창술 교본", Items.TRIDENT),
    HEAVY("heavy", "무거운 무기 숙련", "대검·도끼로 타격", "대검술 교본", Items.IRON_AXE),
    CLASS("class", "직업 숙련", "직업 기술 사용", "직업 수련서", Items.WRITABLE_BOOK);

    public final String id, name, how, tool;
    public final Item icon;
    Stat(String id, String name, String how, String tool, Item icon) { this.id = id; this.name = name; this.how = how; this.tool = tool; this.icon = icon; }

    public static Stat byId(String id) { for (Stat s : values()) if (s.id.equals(id)) return s; return null; }
}
