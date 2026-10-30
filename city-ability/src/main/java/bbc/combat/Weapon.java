package bbc.combat;

import bbc.job.JobSkills;
import bbc.job.Stamina;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

/**
 * 스위치 — 클래스와 무기 전환 (기획안 1부 ‘스위치’).
 *  핫바 1번 칸 = 클래스(직업 평타·직업 기술 1~5), 2번 칸 = 무기 A, 3번 칸 = 무기 B.
 *  E 로 클래스 → 무기 A → 무기 B 순서로 돌림 (무기가 없는 칸은 건너뜀). 전환 5틱.
 *  무기 칸에선 평타가 무기 평타로 바뀌고, 숫자 1·2 = 무기 기술 2개.
 *  스위치 캔슬: 평타·기술 후딜 중에 바꾸면 후딜이 취소됨. 콤보당 2번, 스태미나 15.
 *
 *  무기 종류 (지금은 바닐라 아이템으로 판별, 나중에 모드 무기 태그로)
 *   검(모든 검)   : 5타, 기본 평타보다 조금 셈
 *   창(삼지창)    : 4타, 사거리 +1칸
 *   대검(모든 도끼): 느린 3타, 한 대가 아주 셈
 */
public final class Weapon {
    private Weapon() {}

    /** 평타 성질: 사슬 길이(마지막이 마무리), 선딜·후딜, 피해 배율, 사거리 추가, 모션 앞머리 */
    public record M1Profile(int hits, int startup, int recovery, float dmgMul, double reachAdd, String motion, int finishStartup) {}

    public static final M1Profile FIST = new M1Profile(5, Tuning.M1_STARTUP, Tuning.M1_RECOVERY, 1f, 0, "m1_", Tuning.FINISH_STARTUP);

    public record WSkill(String name, String shortName, double cost, int cooldown) {}

    public enum Type {
        SWORD("검", new M1Profile(5, 4, 2, 1.2f, 0.2, "sw_", 5),
                new WSkill("올려베기", "올려", 15, 5), new WSkill("질풍베기", "질풍", 15, 4)),
        SPEAR("창", new M1Profile(4, 4, 3, 1.15f, 1.0, "sp_", 5),
                new WSkill("꿰뚫기", "꿰뚫", 15, 5), new WSkill("연속 찌르기 (꾹)", "연찌", 8, 5)),
        GREATSWORD("대검", new M1Profile(3, 7, 4, 2.0f, 0.4, "gs_", 8),
                new WSkill("가르기", "가르", 25, 8), new WSkill("휠윈드 (꾹)", "휠윈", 8, 6));

        public final String name;
        public final M1Profile m1;
        public final WSkill[] skills;
        Type(String name, M1Profile m1, WSkill... skills) { this.name = name; this.m1 = m1; this.skills = skills; }
    }

    public static Type of(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        if (s.isIn(ItemTags.SWORDS)) return Type.SWORD;
        if (s.isOf(Items.TRIDENT)) return Type.SPEAR;
        if (s.isIn(ItemTags.AXES)) return Type.GREATSWORD;
        return null;
    }

    /** 지금 쓰는 무기 (클래스 칸이면 null). 플레이어는 선택한 핫바 칸, 몬스터는 손에 든 것 */
    public static Type current(LivingEntity e) {
        if (e instanceof ServerPlayerEntity p) {
            int sel = p.getInventory().selectedSlot;
            return sel == 1 || sel == 2 ? of(p.getInventory().getStack(sel)) : null;
        }
        return of(e.getMainHandStack());
    }

    public static M1Profile profile(LivingEntity e) {
        Type t = current(e);
        return t == null ? FIST : t.m1;
    }

    // ═════════ 전환 ═════════

    public static void cycle(ServerPlayerEntity p) {
        Fighter f = Combat.of(p);
        long now = Combat.now;
        if (f.stunned() || now < f.switchReadyAt) return;
        int sel = p.getInventory().selectedSlot;
        int cur = sel == 1 || sel == 2 ? sel : 0;
        int next = -1;
        for (int i = 1; i <= 2; i++) {
            int c = (cur + i) % 3;
            if (c == 0 || of(p.getInventory().getStack(c)) != null) { next = c; break; }
        }
        if (next < 0 || next == cur) { JobSkills.bar(p, "핫바 2·3번 칸에 무기(검·삼지창·도끼)를 넣으면 E로 바꿀 수 있습니다", Formatting.GRAY); return; }
        // 스위치 캔슬: 후딜 중이면 후딜을 지움 (콤보당 2번, 스태미나 15)
        if (now < f.recoveryUntil) {
            if (now > f.switchCancelResetAt) f.switchCancels = 0;
            if (f.switchCancels >= 2) { JobSkills.bar(p, "이번 콤보에선 스위치 캔슬을 다 썼습니다", Formatting.GRAY); return; }
            if (!Stamina.spend(p, 15)) { JobSkills.bar(p, "스태미나 부족", Formatting.RED); return; }
            f.switchCancels++;
            f.switchCancelResetAt = now + 60;
            f.recoveryUntil = now;
        }
        f.m1ReadyAt = Math.max(Math.min(f.m1ReadyAt, now), now) + 5; // 전환 5틱
        f.switchReadyAt = now + 5;
        f.m1Index = 0;
        p.getInventory().selectedSlot = next;
        p.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(next));
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 0.7f, 1.5f);
        w.spawnParticles(ParticleTypes.ENCHANTED_HIT, p.getX(), p.getY() + 1, p.getZ(), 8, 0.3, 0.4, 0.3, 0.1);
        Type t = next == 0 ? null : of(p.getInventory().getStack(next));
        JobSkills.bar(p, t == null ? "⇄ 클래스" : "⇄ " + t.name, Formatting.AQUA);
    }

    // ═════════ 무기 기술 (숫자 1·2) ═════════

    static final Hit RISE = new Hit(2f, Hit.Kind.AIR, 24, 0.05, 0.85, 20, false, false);
    static final Hit GALE = new Hit(1.8f, Hit.Kind.HIT, 16, 0.15, 0, 15, false, false);
    static final Hit PIERCE = new Hit(2.5f, Hit.Kind.HIT, 18, 0.3, 0, 25, false, false);
    static final Hit SWEEP = new Hit(2f, Hit.Kind.KNOCK, 14, 1.0, 0.35, 25, false, false);
    static final Hit CLEAVE = new Hit(4f, Hit.Kind.DOWN, 14, 0.2, -0.6, 60, true, false);   // 가드 깨기
    static final Hit WHIRL = new Hit(2.2f, Hit.Kind.HIT, 16, 0.4, 0.1, 25, false, false);

    /** 무기 기술 사용. 썼으면 true */
    public static boolean useSkill(ServerPlayerEntity p, int idx) {
        Type t = current(p);
        if (t == null || idx < 0 || idx >= t.skills.length) return false;
        Fighter f = Combat.of(p);
        long now = Combat.now;
        String key = t.name() + idx;
        long cd = f.weaponCd.getOrDefault(key, 0L);
        if (now < cd) { JobSkills.bar(p, t.skills[idx].name() + " 재사용까지 " + ((cd - now) / 20 + 1) + "초", Formatting.GRAY); return false; }
        if (!Stamina.spend(p, t.skills[idx].cost())) { JobSkills.bar(p, "스태미나 부족", Formatting.RED); return false; }
        Vec3d look = Combat.horizontal(p.getRotationVec(1f));
        boolean air = !p.isOnGround();
        switch (t) {
            case SWORD -> {
                if (idx == 0) { Combat.special(p, "sw_rise", 4, 6, RISE, 3.3); if (air) Combat.move(p, new Vec3d(0, 0.55, 0)); }
                else { Combat.move(p, new Vec3d(look.x * 1.1, air ? 0.1 : 0.05, look.z * 1.1)); Combat.special(p, "sw_3", 3, 5, GALE, 3.0); }
            }
            case SPEAR -> {
                if (idx == 0) { Combat.move(p, new Vec3d(look.x * 0.5, 0, look.z * 0.5)); Combat.special(p, "sp_thrust", 5, 6, PIERCE, 5.2); }
                else return Channel.start(p, Channel.Kind.RAPID, idx, key);   // 꾹 누르는 동안 (재사용은 끝난 뒤부터)
            }
            case GREATSWORD -> {
                if (idx == 0) Combat.special(p, "gs_cleave", 10, 8, CLEAVE, 3.6);
                else return Channel.start(p, Channel.Kind.WHIRL, idx, key);  // 꾹 누르는 동안 (재사용은 끝난 뒤부터)
            }
        }
        f.weaponCd.put(key, now + t.skills[idx].cooldown() * 20L);
        return true;
    }

    // ═════════ 연출: 칼자국 ═════════

    /**
     * 무기 휘두를 때 판정 순간의 궤적. 파티클 대신 반투명 판으로 칼자국을 그린다 (Fx.slash, 에픽파이트의 트레일 역할).
     *  검·대검 = 휘두른 방향대로 호 (가로/세로, 왼쪽·오른쪽·위·아래), 창 = 앞으로 뻗는 찌르기 줄기
     */
    public static void trail(LivingEntity p, String motion) {
        if (!(motion.startsWith("sw_") || motion.startsWith("sp_") || motion.startsWith("gs_"))) return;
        ServerWorld w = (ServerWorld) p.getWorld();
        Vec3d f = Combat.horizontal(p.getRotationVec(1f));
        Vec3d r = new Vec3d(-f.z, 0, f.x);
        Vec3d o = p.getPos().add(0, 1.15, 0);
        if (motion.equals("sp_thrust")) { // 찌르기: 앞으로 곧게
            double len = motion.equals("sp_thrust") ? 5.0 : 3.8;
            Fx.plate(w, o.add(f.multiply(len / 2 + 0.5)), f, new Vec3d(0, 1, 0), r, (float) len, 0.05f, 0.16f, Fx.WHITE, 4);
            w.spawnParticles(ParticleTypes.CRIT, o.x + f.x * len, o.y - 0.1, o.z + f.z * len, 6, 0.1, 0.1, 0.1, 0.2);
            return;
        }
        // 모션마다 휘두른 방향 그대로 (make_motions.py 와 맞춤): 기울기, 반대 방향(왼→오 / 아래→위)
        double tilt = switch (motion) {
            case "sw_3", "sw_4", "sp_1", "sp_2", "gs_1" -> 50;
            case "sw_5", "sw_rise", "gs_3", "gs_cleave", "sw_slam", "sp_slam", "gs_slam" -> 90;
            default -> 0;   // sw_1, sw_2, sp_sweep, gs_2: 가로
        };
        boolean reverse = switch (motion) { case "sw_2", "sw_4", "sw_rise", "sp_2", "gs_2" -> true; default -> false; };
        boolean heavy = motion.startsWith("gs_");
        boolean finisher = motion.equals("sw_5") || motion.equals("sp_4") || motion.equals("gs_3") || motion.equals("gs_cleave") || motion.endsWith("slam");
        boolean vertical = tilt >= 90;
        Fx.slash(w, p, heavy || motion.startsWith("sp_") ? 2.7 : 2.1, tilt, reverse, finisher ? Fx.GOLD : Fx.WHITE);
        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, o.x + f.x * 1.6, o.y, o.z + f.z * 1.6, 1, 0, 0, 0, 0);
        if (heavy && vertical) { // 대검 내리치기: 땅이 갈라짐
            Vec3d g = p.getPos().add(f.multiply(2.2));
            var ground = w.getBlockState(net.minecraft.util.math.BlockPos.ofFloored(g).down());
            if (!ground.isAir()) w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, ground), g.x, g.y + 0.1, g.z, 25, 0.6, 0.05, 0.6, 0.2);
            Fx.wave(w, g.add(0, 0.05, 0), 0.3, 2.0, Fx.WHITE, 6);
            Fx.shakeAround(w, g, 6, 0.35f);
            w.playSound(null, g.x, g.y, g.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.3f, 1.6f);
        }
    }

    /** 앞쪽 반원 호. vertical 이면 위에서 아래로, 아니면 오른쪽에서 왼쪽으로 */
    private static void arc(ServerWorld w, Vec3d o, Vec3d f, Vec3d r, double rad, boolean vertical, net.minecraft.particle.ParticleEffect fx) {
        for (int i = 0; i <= 14; i++) {
            double a = Math.PI * (i / 14.0 - 0.5);          // -90° ~ +90°
            double fw = Math.cos(a) * rad;
            Vec3d pt = vertical ? o.add(f.multiply(fw)).add(0, Math.sin(-a) * rad * 0.8, 0)
                                : o.add(f.multiply(fw)).add(r.multiply(Math.sin(a) * rad));
            w.spawnParticles(fx, pt.x, pt.y, pt.z, 1, 0, 0, 0, 0);
        }
    }

    /** 액션바: 무기 칸일 때 */
    public static MutableText hud(ServerPlayerEntity p, Type t) {
        Fighter f = Combat.of(p);
        MutableText m = Text.literal("🗡 " + t.name + "  ").formatted(Formatting.AQUA);
        for (int i = 0; i < t.skills.length; i++) {
            long left = f.weaponCd.getOrDefault(t.name() + i, 0L) - Combat.now;
            WSkill s = t.skills[i];
            m.append(Text.literal((i + 1) + " " + s.shortName() + (left > 0 ? " " + (left / 20 + 1) + "s" : "") + "  ")
                    .formatted(left > 0 ? Formatting.DARK_GRAY : Formatting.GREEN));
        }
        return m;
    }
}
