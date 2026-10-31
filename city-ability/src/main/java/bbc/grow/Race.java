package bbc.grow;

import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;

/**
 * 종족 (처음 접속하면 랜덤, 한 번 무료로 다시 돌림). 효과는 지금 들어간 것만 적용, 나머지는 해당 직업·시스템이 들어올 때.
 */
public enum Race {
    HUMAN("human", "인간", 47, Formatting.WHITE, "재능 카드 선택지 +1, 스탯 경험치 +10%", "없음"),
    MAGE_BLOOD("mage_blood", "마술 가계의 피", 8, Formatting.LIGHT_PURPLE, "마술회로 20~60개 (마술 연구 +20%, 마술사 직업이 들어오면 적용)", "회로 과열 (마술을 연달아 쓰면 체력 감소)"),
    BEAST("beast", "수인", 13, Formatting.GOLD, "밤눈 (밤에 야간 투시), 고기 음식 버프 2배", "지능 경험치 −20%"),
    HALF_BLOOD("half_blood", "혼혈", 10, Formatting.DARK_RED, "체력 30% 이하에서 '반전' (주는 피해 +25%)", "반전 중 가드 불가"),
    HOMUNCULUS("homunculus", "호문쿨루스", 8, Formatting.DARK_AQUA, "연성형(돌 핵 재생) 또는 마술형(뛰어난 마술회로)", "마술형: 최대 체력 −15%"),
    STIGMATA("stigmata", "성흔자", 6, Formatting.YELLOW, "성흔: 신성 피해 +15%, 흡혈 계열 감지 (신부 직업이 들어오면 적용)", "흡혈 계열이 될 수 없음"),
    HAMON("hamon", "파문 계승자", 5, Formatting.AQUA, "파문 호흡(R)·파문 기술 2개 (파문이 들어오면 적용)", "호흡이 끊기면 파문 불가"),
    MYSTIC_EYES("mystic_eyes", "마안 보유자", 3, Formatting.BLUE, "랜덤 마안 1개 (마안이 들어오면 적용)", "켜는 동안 정신력 소모");

    public final String id, name, good, bad;
    public final int weight;
    public final Formatting color;
    Race(String id, String name, int weight, Formatting color, String good, String bad) {
        this.id = id; this.name = name; this.weight = weight; this.color = color; this.good = good; this.bad = bad;
    }

    public static Race byId(String id) { for (Race r : values()) if (r.id.equals(id)) return r; return HUMAN; }

    public static Race roll(Random rnd) {
        int total = 0;
        for (Race r : values()) total += r.weight;
        int x = rnd.nextInt(total);
        for (Race r : values()) { x -= r.weight; if (x < 0) return r; }
        return HUMAN;
    }

    /** 종족 안의 갈래 (수인 3종, 호문쿨루스 2종, 마안 종류) */
    public static String rollSub(Race r, Random rnd) {
        return switch (r) {
            case BEAST -> new String[]{"코알라", "원숭이", "친칠라"}[rnd.nextInt(3)];
            case HOMUNCULUS -> rnd.nextBoolean() ? "연성형" : "마술형";
            case MYSTIC_EYES -> new String[]{"매혹", "속박", "투시", "발화", "석화"}[rnd.nextInt(5)];
            case MAGE_BLOOD -> "회로 " + (20 + rnd.nextInt(41)) + "개";
            default -> "";
        };
    }
}
