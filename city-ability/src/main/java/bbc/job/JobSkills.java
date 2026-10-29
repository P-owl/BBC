package bbc.job;

import bbc.core.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 클래스 슬롯 기술. 직업마다 기술 목록(풀)이 있고, 그중 최대 5개를 Z·X·C·V·B 칸에 장착한다.
 * 장착 정보는 jd 의 "<prefix>equip" (예: "0,1,2,3,4") 에 저장.
 */
public final class JobSkills {
    private JobSkills() {}

    public static final int SLOTS = 5;
    public static final String[] KEYS = {"Z", "X", "C", "V", "B"};

    /** cooldown = 초, cost = 기본 자원(스태미나) */
    public record Skill(String name, String shortName, int rank, double cost, int cooldown, String desc) {}

    public interface Set {
        /** 이 직업의 기술 풀 (순서 = 기술 번호) */
        Skill[] skills(ServerPlayerEntity p, PlayerData.Entry e);
        /** 효과 실행. 실패(대상 없음 등)면 false → 비용·재사용 시간 소모 안 함 */
        boolean use(ServerPlayerEntity p, PlayerData.Entry e, int skill);
        default String costName() { return "스태미나"; }
        default double resource(ServerPlayerEntity p, PlayerData.Entry e) { return Stamina.get(p); }
        default boolean pay(ServerPlayerEntity p, PlayerData.Entry e, Skill s) { return Stamina.spend(p, s.cost()); }
        /** 추가 조건. null 이면 사용 가능, 아니면 이유 */
        default String blocked(ServerPlayerEntity p, PlayerData.Entry e, int skill) { return null; }
    }

    private static final Map<UUID, Map<Integer, Long>> COOLDOWN = new HashMap<>();

    public static void resetCooldown(UUID id) { COOLDOWN.remove(id); }

    /** 기술 번호 기준 남은 재사용 시간(초) */
    public static int cooldownLeft(UUID id, int skill) {
        Long until = COOLDOWN.getOrDefault(id, Map.of()).get(skill);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000 + 1);
    }

    public static void setCooldown(UUID id, int skill, long untilMs) {
        COOLDOWN.computeIfAbsent(id, k -> new HashMap<>()).merge(skill, untilMs, Math::max);
    }

    public static Set of(PlayerData.Entry e) {
        JobType t = Jobs.type(e);
        return t == null ? null : t.skills();
    }

    /** 칸(0~4) → 기술 번호. 비어 있으면 -1 */
    public static int[] equipped(PlayerData.Entry e) {
        int[] out = {-1, -1, -1, -1, -1};
        JobType t = Jobs.type(e);
        if (t == null) return out;
        String raw = e.str(t.prefix() + "equip");
        if (raw.isEmpty()) {
            for (int i = 0; i < SLOTS; i++) out[i] = i; // 처음엔 앞에서부터 5개
            return out;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < SLOTS && i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].trim()); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static void setEquipped(PlayerData.Entry e, int[] slots) {
        JobType t = Jobs.type(e);
        if (t == null) return;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) { if (i > 0) b.append(','); b.append(i < slots.length ? slots[i] : -1); }
        e.str(t.prefix() + "equip", b.toString());
    }

    /** 칸 번호(0~4)로 기술 사용 */
    public static void useSlot(ServerPlayerEntity p, int slot) {
        if (slot < 0 || slot >= SLOTS) return;
        PlayerData.Entry e = Jobs.entry(p);
        int skill = equipped(e)[slot];
        if (skill < 0) { bar(p, KEYS[slot] + " 칸이 비어 있습니다 (/bbc skills)", Formatting.GRAY); return; }
        use(p, e, skill);
    }

    public static void use(ServerPlayerEntity p, PlayerData.Entry e, int skill) {
        Set set = of(e);
        if (set == null) return;
        Skill[] skills = set.skills(p, e);
        if (skill < 0 || skill >= skills.length || skills[skill] == null) return;
        Skill s = skills[skill];
        if (Jobs.rank(e) < s.rank()) { bar(p, s.name() + " — " + Jobs.type(e).rankName(s.rank()) + "부터 쓸 수 있습니다", Formatting.RED); return; }
        int left = cooldownLeft(p.getUuid(), skill);
        if (left > 0) { bar(p, s.name() + " 재사용까지 " + left + "초", Formatting.GRAY); return; }
        String why = set.blocked(p, e, skill);
        if (why != null) { bar(p, why, Formatting.RED); return; }
        if (set.resource(p, e) + 1e-6 < s.cost()) {
            bar(p, set.costName() + " 부족 (" + (int) s.cost() + " 필요, 현재 " + (int) set.resource(p, e) + ")", Formatting.RED);
            return;
        }
        if (!set.use(p, e, skill)) return;
        set.pay(p, e, s);
        setCooldown(p.getUuid(), skill, System.currentTimeMillis() + s.cooldown() * 1000L);
        Jobs.dirty(p);
    }

    /** 액션바: 장착한 5칸 상태 */
    public static MutableText hud(ServerPlayerEntity p, PlayerData.Entry e) {
        MutableText t = Text.literal("⚔ ").formatted(Formatting.GOLD);
        Set set = of(e);
        if (set == null) return t;
        Skill[] skills = set.skills(p, e);
        int[] eq = equipped(e);
        int rank = Jobs.rank(e);
        double res = set.resource(p, e);
        for (int i = 0; i < SLOTS; i++) {
            int k = eq[i];
            if (k < 0 || k >= skills.length || skills[k] == null) { t.append(Text.literal(KEYS[i] + " -  ").formatted(Formatting.DARK_GRAY)); continue; }
            Skill s = skills[k];
            int left = cooldownLeft(p.getUuid(), k);
            boolean locked = s.rank() > rank, poor = res + 1e-6 < s.cost();
            Formatting c = locked || left > 0 ? Formatting.DARK_GRAY : poor ? Formatting.RED : Formatting.GREEN;
            t.append(Text.literal(KEYS[i] + " " + s.shortName() + (left > 0 ? " " + left + "s" : "") + "  ").formatted(c));
        }
        return t;
    }

    public static List<String> describe(Skill s, Set set) {
        List<String> l = new ArrayList<>();
        l.add(s.desc());
        l.add((s.cost() > 0 ? set.costName() + " " + (int) s.cost() + " · " : "") + "재사용 " + s.cooldown() + "초" + (s.rank() > 1 ? " · 등급 " + s.rank() : ""));
        return l;
    }

    public static void bar(ServerPlayerEntity p, String m, Formatting f) { p.sendMessage(Text.literal(m).formatted(f), true); }
}
