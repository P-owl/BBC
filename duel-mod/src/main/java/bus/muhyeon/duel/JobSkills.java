package bus.muhyeon.duel;

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
 * 연금술사 외 직업들의 전투 모드 기술 (1~8번 키).
 * 기술마다 이름·필요 등급·비용·재사용 시간이 있고, 실제 효과는 각 직업의 Set.use 가 처리한다.
 * 비용은 기본적으로 스태미나. 신부처럼 다른 자원을 쓰는 직업은 Set.pay 를 바꾼다.
 */
public final class JobSkills {
    private JobSkills() {}

    public record Skill(String name, String shortName, int rank, double cost, int cooldown, String desc) {}

    public interface Set {
        Skill[] skills(ServerPlayerEntity p, AlchemyData.Entry e);
        /** 효과 실행. 실패(대상 없음 등)면 false → 비용·재사용 시간 소모 안 함 */
        boolean use(ServerPlayerEntity p, AlchemyData.Entry e, int idx);
        /** 전투 모드 배경음 (없으면 "") */
        default String theme() { return ""; }
        /** 비용 이름 (HUD·메시지) */
        default String costName() { return "스태미나"; }
        default double resource(ServerPlayerEntity p, AlchemyData.Entry e) { return Stamina.get(p); }
        default boolean pay(ServerPlayerEntity p, AlchemyData.Entry e, Skill s) { return Stamina.spend(p, s.cost()); }
        /** 이 기술을 지금 쓸 수 있는가 (추가 조건). null 이면 가능, 아니면 이유 */
        default String blocked(ServerPlayerEntity p, AlchemyData.Entry e, int idx) { return null; }
    }

    private static final Map<UUID, long[]> COOLDOWN = new HashMap<>();

    public static void resetCooldown(UUID id) { COOLDOWN.remove(id); }

    public static int cooldownLeft(UUID id, int idx) {
        long[] cd = COOLDOWN.get(id);
        if (cd == null || idx >= cd.length) return 0;
        long left = cd[idx] - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000 + 1);
    }

    /** 재사용 시간 강제 설정 (다른 효과가 기술을 봉인할 때 등) */
    public static void setCooldown(UUID id, int idx, long untilMs) {
        long[] cd = COOLDOWN.computeIfAbsent(id, k -> new long[16]);
        cd[idx] = Math.max(cd[idx], untilMs);
    }

    public static Set of(AlchemyData.Entry e) {
        JobType t = Jobs.type(e);
        return t == null ? null : t.skills();
    }

    public static void use(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
        Set set = of(e);
        if (set == null) return;
        Skill[] skills = set.skills(p, e);
        if (idx < 0 || idx >= skills.length || skills[idx] == null) return;
        Skill s = skills[idx];
        int rank = Jobs.jobRank(e);
        if (rank < s.rank()) { bar(p, s.name() + " — " + Jobs.type(e).rankName(s.rank()) + "부터 쓸 수 있습니다", Formatting.RED); return; }
        int left = cooldownLeft(p.getUuid(), idx);
        if (left > 0) { bar(p, s.name() + " 재사용까지 " + left + "초", Formatting.GRAY); return; }
        String why = set.blocked(p, e, idx);
        if (why != null) { bar(p, why, Formatting.RED); return; }
        if (set.resource(p, e) + 1e-6 < s.cost()) {
            bar(p, set.costName() + " 부족 (" + (int) s.cost() + " 필요, 현재 " + (int) set.resource(p, e) + ")", Formatting.RED);
            return;
        }
        if (!set.use(p, e, idx)) return;
        set.pay(p, e, s);
        setCooldown(p.getUuid(), idx, System.currentTimeMillis() + s.cooldown() * 1000L);
        AlchemyData.get(p.getServer()).markDirty();
        bar(p, "✦ " + s.name() + "!" + (s.cost() > 0 ? "  (" + set.costName() + " -" + (int) s.cost() + ")" : ""), Formatting.GOLD);
    }

    /** 액션바: 기술별 재사용 상태 */
    public static MutableText hud(ServerPlayerEntity p, AlchemyData.Entry e) {
        Set set = of(e);
        MutableText t = Text.literal("⚔ ").formatted(Formatting.GOLD);
        if (set == null) return t;
        Skill[] skills = set.skills(p, e);
        int rank = Jobs.jobRank(e);
        double res = set.resource(p, e);
        for (int i = 0; i < skills.length; i++) {
            Skill s = skills[i];
            if (s == null || s.rank() > rank) continue;
            int left = cooldownLeft(p.getUuid(), i);
            boolean poor = res + 1e-6 < s.cost();
            Formatting c = left > 0 ? Formatting.DARK_GRAY : poor ? Formatting.RED : Formatting.GREEN;
            t.append(Text.literal((i + 1) + s.shortName() + (left > 0 ? " " + left + "s" : "") + "  ").formatted(c));
        }
        return t;
    }

    public static String[] describe(Set set, ServerPlayerEntity p, AlchemyData.Entry e, int rank) {
        List<String> l = new ArrayList<>();
        if (set == null) return new String[]{"전투 기술 없음"};
        Skill[] skills = set.skills(p, e);
        for (int i = 0; i < skills.length; i++) {
            Skill s = skills[i];
            if (s == null) continue;
            String lock = s.rank() > rank ? " (등급 " + s.rank() + ")" : "";
            l.add((i + 1) + ". " + s.name() + lock + " — " + (s.cost() > 0 ? set.costName() + " " + (int) s.cost() + " · " : "") + "재사용 " + s.cooldown() + "초");
            l.add("    " + s.desc());
        }
        return l.toArray(new String[0]);
    }

    static void bar(ServerPlayerEntity p, String m, Formatting f) { p.sendMessage(Text.literal(m).formatted(f), true); }
}
