package bbc.grow;

import bbc.core.PlayerData;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.Formatting;

import java.util.function.Predicate;

/**
 * 재능 카드 (Deepwoken 탤런트). 파워가 오를 때 조건을 만족하는 카드 중 3장(인간 4장)이 나오고 1장을 고른다. 전부 패시브.
 * 등급별 확률 일반 70 · 희귀 25 · 전설 5. 효과는 각 시스템이 Growth.has(p, 카드) 로 확인해서 적용.
 * 첫 묶음 (지금 효과가 들어가는 것만). 직업·종족 전용 카드는 해당 직업이 들어올 때 추가.
 */
public enum Talent {
    HUNTER("hunter", "사냥꾼의 허기", "전투", 0, "적을 쓰러뜨리면 체력 5% 회복", "힘 5 · 내구 5", e -> Growth.stat(e, Stat.STR) >= 5 && Growth.stat(e, Stat.END) >= 5),
    RHYTHM("rhythm", "연계의 리듬", "전투", 0, "콤보 5타마다 스태미나 5 회복", "민첩 10", e -> Growth.stat(e, Stat.AGI) >= 10),
    HEAVY_FINISH("heavy_finish", "무거운 마무리", "전투", 0, "날려보내는 공격 거리 +25%", "힘 15", e -> Growth.stat(e, Stat.STR) >= 15),
    SHARP_SWITCH("sharp_switch", "날카로운 스위치", "전투", 1, "스위치 캔슬 콤보당 3번", "무기 숙련 아무거나 20",
            e -> Growth.stat(e, Stat.LIGHT) >= 20 || Growth.stat(e, Stat.MEDIUM) >= 20 || Growth.stat(e, Stat.HEAVY) >= 20),
    PROVOKER("provoker", "도발자", "전투", 1, "가드 중인 상대에게 주는 가드 게이지 피해 +30%", "힘 20", e -> Growth.stat(e, Stat.STR) >= 20),
    BREATH("breath", "숨 고르기", "생존", 0, "3초 가만히 있으면 초당 체력 0.5 회복", "내구 5", e -> Growth.stat(e, Stat.END) >= 5),
    THICK_SKIN("thick_skin", "두꺼운 피부", "생존", 0, "최대 체력 +8%", "내구 10", e -> Growth.stat(e, Stat.END) >= 10),
    LEFTOVER("leftover", "남은 힘", "생존", 1, "회피기 재사용 30 → 22초", "민첩 15", e -> Growth.stat(e, Stat.AGI) >= 15),
    TUMBLER("tumbler", "오뚝이", "생존", 1, "다운 후 기상 무적 +6틱", "내구 20", e -> Growth.stat(e, Stat.END) >= 20),
    SHADOW_STEP("shadow_step", "그림자 발놀림", "생존", 1, "대시 중 0.3초 투명", "민첩 20", e -> Growth.stat(e, Stat.AGI) >= 20),
    ONCE_MORE("once_more", "한 번 더", "생존", 2, "체력이 0이 될 때 1로 버티고 1초 무적 (10분에 1번)", "내구 30", e -> Growth.stat(e, Stat.END) >= 30),
    LUMBERJACK("lumberjack", "나무꾼", "생활", 0, "나무·광석 채집 시 힘 경험치 2배", "없음", e -> true),
    SELF_TAUGHT("self_taught", "독학", "생활", 1, "독서로 얻는 지능 경험치 +50%", "지능 15", e -> Growth.stat(e, Stat.INT) >= 15),
    I_AM_AHEAD("i_am_ahead", "내가 우위다", "전투", 2, "콤보 15타 이상에서 피해 보정이 없어짐", "민첩 30", e -> Growth.stat(e, Stat.AGI) >= 30);

    public final String id, name, group, effect, cond;
    /** 0 일반 · 1 희귀 · 2 전설 */
    public final int rarity;
    public final Predicate<PlayerData.Entry> ok;
    Talent(String id, String name, String group, int rarity, String effect, String cond, Predicate<PlayerData.Entry> ok) {
        this.id = id; this.name = name; this.group = group; this.rarity = rarity; this.effect = effect; this.cond = cond; this.ok = ok;
    }

    public static Talent byId(String id) { for (Talent t : values()) if (t.id.equals(id)) return t; return null; }
    public static final int[] WEIGHT = {70, 25, 5};
    public Formatting color() { return rarity == 2 ? Formatting.GOLD : rarity == 1 ? Formatting.AQUA : Formatting.WHITE; }
    public Item icon() { return rarity == 2 ? Items.NETHER_STAR : rarity == 1 ? Items.ENCHANTED_BOOK : Items.PAPER; }
    public String rarityName() { return rarity == 2 ? "전설" : rarity == 1 ? "희귀" : "일반"; }
}
