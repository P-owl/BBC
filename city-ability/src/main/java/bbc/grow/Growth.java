package bbc.grow;

import bbc.core.PlayerData;
import bbc.job.JobSkills;
import bbc.job.Jobs;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 3단계 성장: 레벨 없이 존재치(EP) + 파워(1~20) + 행동 스탯 + 재능 카드 + 특성 + 스킬 숙련 + 종족.
 *
 *  - 존재치: 몬스터 처치·새 바이옴 발견(·나중에 퀘스트)으로 오름
 *  - 파워(1~50): 투자 한도 1~20 은 15점, 21~50 은 3점. 한도를 다 확정하고 존재치가 문턱(60×(p−1)^2.3)을 넘으면 다음 파워
 *  - 스탯: 행동하면 경험치 → 다 차면 '확정 대기' 1점 → 수련 도구를 쓰면 확정 (스탯 하나 최대 100)
 *  - 파워가 오르면 재능 카드 3장(인간 4장) 중 1장, 짝수 파워마다 특성 1점 (최대 24, 한 곳 6)
 *  - 직업 기술은 쓸수록 숙련 0→100%, 100% 면 숙달 (재사용·비용 −20%)
 * 데이터는 PlayerData.Entry.jd 의 "g." 키 (캐릭터 슬롯이 생기면 슬롯마다 따로).
 */
public final class Growth {
    private Growth() {}

    public static final int MAX_POWER = 50, STAT_CAP = 100, TRAIT_CAP = 6, PENDING_CAP = 9;
    /** 파워별 투자 한도: 1~20 은 15점, 21~50 은 3점 (총 285 + 90 = 375점 → 스탯 8개 합 800 중 일부만, 빌드가 갈림) */
    public static int perPower(int power) { return power < 20 ? 15 : 3; }
    public static final String[] TRAITS = {"life", "know", "mastery", "reso"};
    public static final String[] TRAIT_NAMES = {"생명력", "지식", "숙달", "공명"};
    public static final String[] TRAIT_DESC = {"최대 체력 +5%", "최대 스태미나 +10", "평타·무기 피해 +3%", "직업 기술 피해 +3%"};

    // ═════════ 읽기 ═════════

    public static PlayerData.Entry e(ServerPlayerEntity p) { return Jobs.entry(p); }
    public static int power(PlayerData.Entry e) { return Math.max(1, (int) e.num("g.power")); }
    public static double ep(PlayerData.Entry e) { return e.num("g.ep"); }
    public static int invested(PlayerData.Entry e) { return (int) e.num("g.inv"); }
    public static int stat(PlayerData.Entry e, Stat s) { return (int) e.num("g.s." + s.id); }
    public static int pending(PlayerData.Entry e, Stat s) { return (int) e.num("g.p." + s.id); }
    public static double exp(PlayerData.Entry e, Stat s) { return e.num("g.x." + s.id); }
    /** 스탯 1점에 필요한 경험치: 40 × (1 + 스탯/12)^1.5 — 0점 40, 10점 98, 30점 248, 60점 547, 99점 1156 (갈수록 크게 느려짐) */
    public static double need(PlayerData.Entry e, Stat s) { return 40 * Math.pow(1 + stat(e, s) / 12.0, 1.5); }
    /** 이 파워에 오르는 데 필요한 누적 존재치 */
    /** 60 × (p−1)^2.3 — 파워 2 = 60, 5 = 1450, 10 = 9800, 20 = 52000, 30 = 140000, 50 = 470000 (뒤로 갈수록 보스·퀘스트가 주 수입) */
    public static double epFor(int power) { return power <= 1 ? 0 : 60 * Math.pow(power - 1, 2.3); }
    public static int trait(PlayerData.Entry e, int i) { return (int) e.num("g.t." + TRAITS[i]); }
    public static int traitPoints(PlayerData.Entry e) {
        int spent = 0;
        for (int i = 0; i < TRAITS.length; i++) spent += trait(e, i);
        return Math.min(24, power(e) / 2) - spent;
    }
    public static Race race(PlayerData.Entry e) { return Race.byId(e.str("g.race")); }
    public static String raceSub(PlayerData.Entry e) { return e.str("g.race_sub"); }

    public static List<Talent> cards(PlayerData.Entry e) {
        List<Talent> l = new ArrayList<>();
        for (String id : e.str("g.cards").split(",")) { Talent t = Talent.byId(id); if (t != null) l.add(t); }
        return l;
    }
    public static boolean has(PlayerData.Entry e, Talent t) { return ("," + e.str("g.cards") + ",").contains("," + t.id + ","); }
    public static boolean has(LivingEntity le, Talent t) { return le instanceof ServerPlayerEntity p && has(e(p), t); }
    public static List<Talent> pick(PlayerData.Entry e) {
        List<Talent> l = new ArrayList<>();
        for (String id : e.str("g.pick").split(",")) { Talent t = Talent.byId(id); if (t != null) l.add(t); }
        return l;
    }

    // ═════════ 경험치·존재치 ═════════

    /** 행동 스탯 경험치. 종족·카드 배율 적용, 다 차면 확정 대기 +1 */
    public static void gainExp(ServerPlayerEntity p, Stat s, double amount) {
        PlayerData.Entry e = e(p);
        if (stat(e, s) >= STAT_CAP) return;
        Race r = race(e);
        if (r == Race.HUMAN) amount *= 1.1;
        if (s == Stat.INT && r == Race.BEAST) amount *= 0.8;
        double x = exp(e, s) + amount;
        int pend = pending(e, s);
        boolean got = false;
        while (x >= need(e, s) && pend < PENDING_CAP && stat(e, s) + pend < STAT_CAP) {
            x -= need(e, s);
            pend++;
            got = true;
        }
        if (pend >= PENDING_CAP) x = Math.min(x, need(e, s));
        e.num("g.x." + s.id, x);
        e.num("g.p." + s.id, pend);
        if (got) {
            p.sendMessage(Text.literal("✦ " + s.name + " 1점 확정 대기 (" + s.tool + "로 확정, 대기 " + pend + ")").formatted(Formatting.GREEN), true);
            p.playSoundToPlayer(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.4f);
        }
        Jobs.dirty(p);
    }

    public static void gainEp(ServerPlayerEntity p, double amount, String why) {
        if (amount <= 0) return;
        PlayerData.Entry e = e(p);
        e.addNum("g.ep", amount);
        Jobs.dirty(p);
        if (amount >= 5) p.sendMessage(Text.literal("존재치 +" + (int) amount + (why.isEmpty() ? "" : " (" + why + ")")).formatted(Formatting.LIGHT_PURPLE), true);
        tryPowerUp(p);
    }

    // ═════════ 확정·파워 ═════════

    /** 수련 도구로 1점 확정. 썼으면 true (도구 소모) */
    public static boolean confirm(ServerPlayerEntity p, Stat s) {
        PlayerData.Entry e = e(p);
        if (pending(e, s) <= 0) { p.sendMessage(Text.literal(s.name + " 확정 대기 점수가 없습니다 (" + s.how + ")").formatted(Formatting.GRAY), true); return false; }
        if (power(e) >= MAX_POWER && invested(e) >= perPower(power(e))) { p.sendMessage(Text.literal("최고 파워입니다").formatted(Formatting.GRAY), true); return false; }
        if (invested(e) >= perPower(power(e))) {
            p.sendMessage(Text.literal("이번 파워의 투자 한도(" + perPower(power(e)) + ")를 다 썼습니다 — 존재치 " + (int) ep(e) + "/" + (int) epFor(power(e) + 1) + " 를 채우면 파워업").formatted(Formatting.YELLOW), true);
            return false;
        }
        if (stat(e, s) >= STAT_CAP) { p.sendMessage(Text.literal(s.name + " 최대치").formatted(Formatting.GRAY), true); return false; }
        e.addNum("g.p." + s.id, -1);
        e.addNum("g.s." + s.id, 1);
        e.addNum("g.inv", 1);
        Jobs.dirty(p);
        p.sendMessage(Text.literal("✔ " + s.name + " " + stat(e, s) + " (이번 파워 투자 " + invested(e) + "/" + perPower(power(e)) + ")").formatted(Formatting.AQUA), true);
        p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.4f, 1.6f);
        apply(p);
        tryPowerUp(p);
        return true;
    }

    public static void tryPowerUp(ServerPlayerEntity p) {
        PlayerData.Entry e = e(p);
        int pw = power(e);
        if (pw >= MAX_POWER || invested(e) < perPower(pw) || ep(e) < epFor(pw + 1)) return;
        e.num("g.power", pw + 1);
        e.num("g.inv", 0);
        Jobs.dirty(p);
        p.sendMessage(Text.literal("★ 파워 " + (pw + 1) + "!").formatted(Formatting.GOLD, Formatting.BOLD), false);
        if ((pw + 1) % 2 == 0 && traitPoints(e) > 0)
            p.sendMessage(Text.literal("특성 1점을 얻었습니다 (/bbc grow 또는 K)").formatted(Formatting.YELLOW), false);
        p.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.6f, 1.2f);
        rollCards(p);
        apply(p);
    }

    /** 재능 카드 후보를 뽑아 두고 고르는 창을 엶 */
    public static void rollCards(ServerPlayerEntity p) {
        PlayerData.Entry e = e(p);
        List<Talent> pool = new ArrayList<>();
        for (Talent t : Talent.values()) if (!has(e, t) && t.ok.test(e)) pool.add(t);
        int want = race(e) == Race.HUMAN ? 4 : 3;
        List<String> got = new ArrayList<>();
        var rnd = p.getRandom();
        while (got.size() < want && !pool.isEmpty()) {
            int total = 0;
            for (Talent t : pool) total += Talent.WEIGHT[t.rarity];
            int x = rnd.nextInt(total);
            for (Talent t : pool) { x -= Talent.WEIGHT[t.rarity]; if (x < 0) { got.add(t.id); pool.remove(t); break; } }
        }
        if (got.isEmpty()) { p.sendMessage(Text.literal("지금 조건을 만족하는 새 재능 카드가 없습니다 (스탯을 올리면 늘어남)").formatted(Formatting.GRAY), false); return; }
        e.str("g.pick", String.join(",", got));
        Jobs.dirty(p);
        GrowthMenu.openCards(p);
    }

    public static void choose(ServerPlayerEntity p, Talent t) {
        PlayerData.Entry e = e(p);
        if (!pick(e).contains(t)) return;
        String cur = e.str("g.cards");
        e.str("g.cards", cur.isEmpty() ? t.id : cur + "," + t.id);
        e.str("g.pick", "");
        Jobs.dirty(p);
        p.sendMessage(Text.literal("재능 카드: " + t.name + " — " + t.effect).formatted(t.color()), false);
        apply(p);
    }

    public static boolean spendTrait(ServerPlayerEntity p, int i) {
        PlayerData.Entry e = e(p);
        if (traitPoints(e) <= 0 || trait(e, i) >= TRAIT_CAP) return false;
        e.addNum("g.t." + TRAITS[i], 1);
        Jobs.dirty(p);
        apply(p);
        return true;
    }

    // ═════════ 종족 ═════════

    public static void ensureRace(ServerPlayerEntity p) {
        PlayerData.Entry e = e(p);
        if (!e.str("g.race").isEmpty()) return;
        Race r = Race.roll(p.getRandom());
        e.str("g.race", r.id);
        e.str("g.race_sub", Race.rollSub(r, p.getRandom()));
        if (e.num("g.power") < 1) e.num("g.power", 1);
        Jobs.dirty(p);
        p.sendMessage(Text.literal("당신의 종족: " + r.name + (raceSub(e).isEmpty() ? "" : " (" + raceSub(e) + ")") + " — 한 번 무료로 다시 돌릴 수 있습니다 (/bbc grow)").formatted(r.color), false);
    }

    public static boolean rerollRace(ServerPlayerEntity p) {
        PlayerData.Entry e = e(p);
        if (e.flag("g.rerolled")) return false;
        Race r = Race.roll(p.getRandom());
        e.str("g.race", r.id);
        e.str("g.race_sub", Race.rollSub(r, p.getRandom()));
        e.flag("g.rerolled", true);
        Jobs.dirty(p);
        p.sendMessage(Text.literal("새 종족: " + r.name + (raceSub(e).isEmpty() ? "" : " (" + raceSub(e) + ")")).formatted(r.color), false);
        apply(p);
        return true;
    }

    // ═════════ 스킬 숙련 ═════════

    public static double mastery(PlayerData.Entry e, int skill) { return e.num("g.m." + e.job + "." + skill); }
    public static boolean mastered(PlayerData.Entry e, int skill) { return mastery(e, skill) >= 100; }

    /** 직업 기술을 썼을 때: 숙련 +2% (50번이면 숙달), 직업 숙련 경험치 */
    public static void onSkillUsed(ServerPlayerEntity p, int skill, String name) {
        PlayerData.Entry e = e(p);
        double before = mastery(e, skill);
        if (before < 100) {
            double now = Math.min(100, before + 2);
            e.num("g.m." + e.job + "." + skill, now);
            if (now >= 100) p.sendMessage(Text.literal("★ " + name + " 숙달! (재사용·비용 −20%)").formatted(Formatting.GOLD), false);
        }
        gainExp(p, Stat.CLASS, 4);
    }

    // ═════════ 효과 ═════════

    private static final Identifier HP_ID = Identifier.of("bbc", "grow_hp"), SPEED_ID = Identifier.of("bbc", "grow_speed");

    /** 최대 체력·이동 속도 반영 (접속·부활·스탯 변화 때) */
    public static void apply(ServerPlayerEntity p) {
        PlayerData.Entry e = e(p);
        double base = 20;
        double hp = stat(e, Stat.END) * 0.15 + base * (0.05 * trait(e, 0) + (has(e, Talent.THICK_SKIN) ? 0.08 : 0)
                - (race(e) == Race.HOMUNCULUS && raceSub(e).equals("마술형") ? 0.15 : 0));
        set(p, EntityAttributes.GENERIC_MAX_HEALTH, HP_ID, hp, EntityAttributeModifier.Operation.ADD_VALUE);
        set(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED_ID, stat(e, Stat.AGI) * 0.0005, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static void set(ServerPlayerEntity p, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> attr, Identifier id, double v, EntityAttributeModifier.Operation op) {
        EntityAttributeInstance inst = p.getAttributeInstance(attr);
        if (inst == null) return;
        inst.removeModifier(id);
        if (Math.abs(v) > 1e-6) inst.addPersistentModifier(new EntityAttributeModifier(id, v, op));
    }

    public static double staminaBonus(ServerPlayerEntity p) {
        PlayerData.Entry e = e(p);
        return stat(e, Stat.INT) * 0.5 + trait(e, 1) * 10;
    }

    public static double staminaRegenMul(ServerPlayerEntity p) { return 1 + stat(e(p), Stat.AGI) * 0.005; }

    /**
     * 주는 피해 배율. m1 = 평타, weapon = 무기 칸이면 그 무기, 아니면 null (맨손·직업 기술)
     *  맨손 평타: 힘 1점당 +0.4% / 무기 평타·무기 기술: 힘 +0.2%, 그 무기 숙련 +0.4% / 직업 기술: 직업 숙련 +0.4%, 지능 +0.1%
     *  특성 숙달 = 평타·무기 +3%/점, 공명 = 직업 기술 +3%/점. 혼혈 반전(체력 30% 이하) +25%
     */
    public static float damageMul(LivingEntity atk, boolean m1, bbc.combat.Weapon.Type weapon) {
        if (!(atk instanceof ServerPlayerEntity p)) return 1f;
        PlayerData.Entry e = e(p);
        double m;
        if (weapon != null) {
            Stat ws = weaponStat(weapon);
            m = 1 + stat(e, Stat.STR) * 0.002 + stat(e, ws) * 0.004 + trait(e, 2) * 0.03;
        } else if (m1) {
            m = 1 + stat(e, Stat.STR) * 0.004 + trait(e, 2) * 0.03;
        } else {
            m = 1 + stat(e, Stat.CLASS) * 0.004 + stat(e, Stat.INT) * 0.001 + trait(e, 3) * 0.03;
        }
        if (reversal(p)) m *= 1.25;
        return (float) m;
    }

    /** 혼혈 '반전' 중인가 (체력 30% 이하) */
    public static boolean reversal(LivingEntity le) {
        return le instanceof ServerPlayerEntity p && race(e(p)) == Race.HALF_BLOOD && p.getHealth() <= p.getMaxHealth() * 0.3f;
    }

    public static Stat weaponStat(bbc.combat.Weapon.Type w) {
        return switch (w) { case SWORD -> Stat.LIGHT; case SPEAR -> Stat.MEDIUM; case GREATSWORD -> Stat.HEAVY; };
    }

    /** 전투에서 때렸을 때 경험치 (Combat.hit 에서) */
    public static void onHit(LivingEntity atk, boolean m1, int comboHits) {
        if (!(atk instanceof ServerPlayerEntity p)) return;
        bbc.combat.Weapon.Type w = bbc.combat.Weapon.current(p);
        if (w != null) gainExp(p, weaponStat(w), 3);
        if (m1) gainExp(p, Stat.STR, w == null ? 3 : 1);
        if (has(e(p), Talent.RHYTHM) && comboHits > 0 && comboHits % 5 == 0) bbc.job.Stamina.give(p, 5);
    }

    /** 표시용 한 줄 */
    public static String summary(PlayerData.Entry e) {
        return "파워 " + power(e) + " · 존재치 " + (int) ep(e) + "/" + (int) epFor(power(e) + 1) + " · 투자 " + invested(e) + "/" + perPower(power(e));
    }

    static String bar(double v, double max, int n) {
        int f = (int) Math.round(n * Math.max(0, Math.min(1, v / Math.max(1e-9, max))));
        char[] c = new char[n];
        Arrays.fill(c, 0, f, '█');
        Arrays.fill(c, f, n, '░');
        return new String(c);
    }

}
