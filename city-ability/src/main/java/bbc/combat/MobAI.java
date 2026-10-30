package bbc.combat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 몬스터 전투 AI. 플레이어와 같은 전투 엔진(평타 5타·가드·패링·대시·회피기)을 몬스터가 쓴다.
 * 태그 bbc_ai_grunt / bbc_ai_skilled / bbc_ai_boss 가 붙은 몹이 대상 (바닐라 /tag 로도 붙일 수 있음).
 *
 *  하급: 평타 2~5타만, 가끔 늦게 가드. 반응 0.5초
 *  숙련: 평타 5타·업 플링·뜨기→다운 슬램 콤보, 가드·패링, 대시로 파고들기, 가드 깨기, 콤보 중 회피기. 반응 0.35초
 *  보스: 숙련 전부 + 지상 연계기(평타 4 → 어깨치기 → 평타 4 …), 넉백 뒤 추격 대시 → 공중 콤보,
 *        각성 게이지가 차면 각성, 상대가 경직일 때 각성기(난무, 마지막 일격은 패링 불가). 반응 0.2초
 *
 * 사람처럼 보이게: 반응 시간 뒤에야 막고, 확률로 실수하고, 콤보 뒤엔 잠깐 쉰다. 큰 기술 전엔 빨간 빛·소리로 예고.
 */
public final class MobAI {
    private MobAI() {}

    public enum Tier {
        GRUNT("grunt", "하급", 10, 0.12, 0.0, 0.0, false, false, false, false, 40),
        SKILLED("skilled", "숙련", 7, 0.30, 0.25, 0.45, true, true, false, true, 80),
        BOSS("boss", "보스", 4, 0.40, 0.45, 0.85, true, true, true, true, 250);

        public final String id, name;
        final int reaction;                // 상대 공격을 보고 반응하기까지 (틱)
        final double guardChance, parryChance, evadeChance;
        final boolean dashIn, guardBreak, extender, airRoute;
        final float hp;

        Tier(String id, String name, int reaction, double guard, double parry, double evade,
             boolean dashIn, boolean guardBreak, boolean extender, boolean airRoute, float hp) {
            this.id = id; this.name = name; this.reaction = reaction;
            this.guardChance = guard; this.parryChance = parry; this.evadeChance = evade;
            this.dashIn = dashIn; this.guardBreak = guardBreak; this.extender = extender; this.airRoute = airRoute; this.hp = hp;
        }

        public String tag() { return "bbc_ai_" + id; }

        public static Tier byId(String id) {
            for (Tier t : values()) if (t.id.equals(id)) return t;
            return null;
        }
    }

    /** 가드 깨기 (숙련·보스): 크게 휘두르는 한 방. 선딜 10틱 동안 빨간 빛으로 예고 */
    static final Hit GUARD_BREAK = new Hit(2.5f, Hit.Kind.HIT, 20, 0.6, 0, 100, true, false);
    /** 어깨치기 (보스 지상 연계기): 맞으면 내 앞에 붙잡혀 경직 → 평타로 다시 이어짐 */
    static final Hit SHOULDER = new Hit(2f, Hit.Kind.HIT, 18, 0.1, 0, 20, false, false);

    private static final class Brain {
        final Tier tier;
        Brain(Tier t) { tier = t; }
        final List<String> plan = new ArrayList<>();
        int step;
        long cooldownUntil, guardAt = -1, guardUntil, parryAt = -1, outOfReachSince = -1, nextAt;
        int seenToken = -1;
        long targetGuardSince = -1;
        long lastBreakAt = -1000;
        boolean evadedThisCombo;
    }

    private static final Map<UUID, Brain> BRAINS = new HashMap<>();
    private static long clock;

    public static Tier tier(LivingEntity e) {
        for (String t : e.getCommandTags()) if (t.startsWith("bbc_ai_")) return Tier.byId(t.substring(7));
        return null;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(MobAI::tick);
    }

    private static void tick(MinecraftServer server) {
        clock++;
        if (clock % 20 == 0) { // 1초마다 태그 달린 몹 찾기
            for (ServerWorld w : server.getWorlds())
                for (Entity e : w.iterateEntities())
                    if (e instanceof MobEntity m && m.isAlive() && !BRAINS.containsKey(m.getUuid())) {
                        Tier t = tier(m);
                        if (t != null) BRAINS.put(m.getUuid(), new Brain(t));
                    }
        }
        Iterator<Map.Entry<UUID, Brain>> it = BRAINS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Brain> en = it.next();
            MobEntity m = find(server, en.getKey());
            if (m == null || !m.isAlive() || m.isRemoved()) { it.remove(); continue; }
            think(m, en.getValue());
        }
    }

    private static MobEntity find(MinecraftServer server, UUID id) {
        for (ServerWorld w : server.getWorlds()) if (w.getEntity(id) instanceof MobEntity m) return m;
        return null;
    }

    private static void think(MobEntity m, Brain b) {
        Random r = m.getRandom();
        long now = Combat.now;
        Fighter f = Combat.of(m);
        LivingEntity t = target(m);
        if (t == null) { if (f.guardHeld) Combat.guard(m, false); b.plan.clear(); return; }
        Fighter tf = Combat.peek(t);
        double dist = m.distanceTo(t);

        // ── 맞는 중: 콤보가 길어지면 회피기로 빠져나옴 (콤보당 한 번만 굴려 봄) ──
        if (f.stunned()) {
            b.plan.clear();
            if (f.comboHits == 0) b.evadedThisCombo = false;
            if (!b.evadedThisCombo && f.comboHits >= 3 && f.state != Fighter.State.DOWN) {
                b.evadedThisCombo = true;
                if (r.nextDouble() < b.tier.evadeChance) Combat.dash(m, 1);
            }
            return;
        }
        if (f.comboHits == 0) b.evadedThisCombo = false;
        face(m, t);

        // ── 각성 (보스만): 게이지가 차면 각성, 각성 중 상대가 경직이면 각성기로 마무리 ──
        if (b.tier == Tier.BOSS) {
            if (now >= f.awakenUntil && f.awakenGauge >= Awaken.MAX) { Awaken.press(m); return; }
            if (now < f.awakenUntil && !f.ultUsed && tf != null && tf.stunned() && dist < 3.2 && now >= f.m1ReadyAt) {
                b.plan.clear(); Awaken.press(m); b.cooldownUntil = now + 30; return;
            }
        }

        // ── 방어: 상대가 휘두르기 시작한 걸 보면 굴려서 패링·가드 ──
        boolean attacking = now < f.recoveryUntil || !b.plan.isEmpty();
        if (tf != null && tf.token != b.seenToken && now < tf.recoveryUntil && dist < 4.5) {
            b.seenToken = tf.token;
            if (!attacking) {
                double roll = r.nextDouble();
                if (roll < b.tier.parryChance) { b.parryAt = now + 2; b.guardUntil = now + 14; }
                else if (roll < b.tier.parryChance + b.tier.guardChance) { b.guardAt = now + b.tier.reaction; b.guardUntil = b.guardAt + 20; }
            }
        }
        if (b.parryAt >= 0 && now >= b.parryAt) { // 누르는 순간이 패링 창이므로 떼었다가 다시 누름
            if (f.guardHeld) Combat.guard(m, false);
            Combat.guard(m, true);
            b.parryAt = -1;
        }
        if (b.guardAt >= 0 && now >= b.guardAt) { if (!f.guardHeld) Combat.guard(m, true); b.guardAt = -1; }
        if (f.guardHeld && now >= b.guardUntil) Combat.guard(m, false);
        if (f.guardHeld) return;

        // ── 상대가 가드를 오래 올리고 있으면 가드 깨기 ──
        if (tf != null && tf.guardHeld) { if (b.targetGuardSince < 0) b.targetGuardSince = now; }
        else b.targetGuardSince = -1;
        if (b.tier.guardBreak && b.targetGuardSince >= 0 && now - b.targetGuardSince >= 12 && dist < 3.4
                && now - b.lastBreakAt > 80 && now >= f.m1ReadyAt && b.plan.isEmpty()) {
            b.lastBreakAt = now;
            telegraph(m);
            Combat.special(m, "m1_5", 10, 8, GUARD_BREAK, 3.2);
            b.cooldownUntil = now + 20;
            return;
        }

        // ── 공격 ──
        if (b.plan.isEmpty()) {
            if (b.tier.dashIn && dist > 4 && dist < 8 && now >= f.dashCdUntil && r.nextDouble() < 0.05) Combat.dash(m, 0);
            if (dist <= 2.9 && now >= b.cooldownUntil) { choosePlan(b, r); b.step = 0; }
            return;
        }
        String s = b.plan.get(b.step);
        if (!s.equals("CHASE") && dist > 3.4) { // 콤보 중 상대가 멀어지면 잠깐 기다렸다 포기
            if (b.outOfReachSince < 0) b.outOfReachSince = now;
            if (now - b.outOfReachSince > 10) { b.plan.clear(); b.cooldownUntil = now + 10; }
            return;
        }
        b.outOfReachSince = -1;
        if (f.dashTicks > 0) return;
        // 추격 대시는 넉백이 맞은 직후(후딜 중)에 바로 캔슬해서 나감, 나머지는 다음 평타가 가능해질 때
        if (s.equals("CHASE") ? now < f.recoveryUntil - 9 : now < f.m1ReadyAt || now < b.nextAt) return;
        m.getNavigation().stop();
        switch (s) {
            case "M1" -> Combat.m1(m, false);
            case "UP" -> Combat.m1(m, true);
            case "EXT" -> {
                Combat.special(m, "dash_attack", 3, 6, SHOULDER, 2.8);
                Combat.move(m, Combat.horizontal(t.getPos().subtract(m.getPos())).multiply(0.6));
            }
            case "CHASE" -> Combat.dash(m, 0);
        }
        b.step++;
        // 숙련·보스는 가끔 평타 박자를 일부러 늦춤 (ABA '슬로우 평타': 패링 타이밍을 흔듦). 사슬이 끊기지 않는 범위 안에서
        // 상대가 이미 경직 중이면 늦추지 않음 (콤보가 끊기니까) → 막고 있거나 패링을 노릴 때만
        if (b.tier != Tier.GRUNT && (tf == null || !tf.stunned()) && r.nextDouble() < (b.tier == Tier.BOSS ? 0.4 : 0.25))
            b.nextAt = now + 2 + r.nextInt(5);
        if (b.step >= b.plan.size()) {
            b.plan.clear();
            b.cooldownUntil = now + 18 + r.nextInt(22); // 콤보 뒤엔 잠깐 쉼 (반격 기회)
        }
    }

    private static void choosePlan(Brain b, Random r) {
        List<String> p = b.plan;
        p.clear();
        double x = r.nextDouble();
        switch (b.tier) {
            case GRUNT -> { int n = 2 + r.nextInt(4); for (int i = 0; i < n; i++) p.add("M1"); }
            case SKILLED -> {
                if (x < 0.5) p.addAll(List.of("M1", "M1", "M1", "M1", "M1"));                 // 4타 + 넉백
                else if (x < 0.8) p.addAll(List.of("M1", "M1", "UP", "M1", "M1"));            // 같이 떠서 → 다운 슬램
                else p.addAll(List.of("M1", "M1", "M1", "M1", "UP"));                          // 업 플링
            }
            case BOSS -> {
                if (x < 0.4) p.addAll(List.of("M1", "M1", "M1", "M1", "EXT", "M1", "M1", "M1", "M1", "M1"));  // 지상 연계
                else if (x < 0.7) p.addAll(List.of("M1", "M1", "UP", "M1", "M1"));
                else p.addAll(List.of("M1", "M1", "M1", "M1", "M1", "CHASE", "M1", "M1", "M1", "M1", "M1")); // 넉백 → 추격 → 공중 → 슬램
            }
        }
    }

    private static LivingEntity target(MobEntity m) {
        LivingEntity t = m.getTarget();
        if (t != null && t.isAlive() && m.distanceTo(t) < 24 && !(t instanceof PlayerEntity pl && (pl.isCreative() || pl.isSpectator()))) return t;
        PlayerEntity p = m.getWorld().getClosestPlayer(m, 16);
        if (p == null || p.isCreative() || p.isSpectator()) return null;
        m.setTarget(p);
        return p;
    }

    /** 몸·머리를 상대 쪽으로 (평타 판정이 바라보는 방향 기준이라) */
    private static void face(MobEntity m, LivingEntity t) {
        Vec3d d = t.getEyePos().subtract(m.getEyePos());
        float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180 / Math.PI) - 90f;
        float pitch = (float) -(MathHelper.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * 180 / Math.PI);
        m.setYaw(yaw); m.setHeadYaw(yaw); m.setBodyYaw(yaw); m.setPitch(pitch);
    }

    /** 큰 기술 예고: 빨간 빛 + 소리 */
    private static void telegraph(LivingEntity m) {
        ServerWorld w = (ServerWorld) m.getWorld();
        w.spawnParticles(new DustParticleEffect(new Vector3f(1f, 0.1f, 0.1f), 1.6f), m.getX(), m.getY() + 1.4, m.getZ(), 20, 0.4, 0.5, 0.4, 0);
        w.playSound(null, m.getX(), m.getY(), m.getZ(), SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.HOSTILE, 0.6f, 1.8f);
    }

    // ═════════ 연습 상대 ═════════

    /** /bbc admin opponent <grunt|skilled|boss>: 내 앞에 전투 AI 좀비 소환 */
    public static void spawnOpponent(ServerPlayerEntity p, Tier tier) {
        ServerWorld w = p.getServerWorld();
        ZombieEntity z = EntityType.ZOMBIE.create(w);
        if (z == null) return;
        Vec3d look = Combat.horizontal(p.getRotationVec(1f));
        Vec3d pos = p.getPos().add(look.multiply(5));
        z.refreshPositionAndAngles(pos.x, pos.y, pos.z, p.getYaw() + 180, 0);
        z.setPersistent();
        z.setCanPickUpLoot(false);
        z.addCommandTag(tier.tag());
        Formatting c = switch (tier) { case GRUNT -> Formatting.WHITE; case SKILLED -> Formatting.GOLD; case BOSS -> Formatting.RED; };
        z.setCustomName(Text.literal("연습 상대 · " + tier.name).formatted(c, Formatting.BOLD));
        z.setCustomNameVisible(true);
        z.equipStack(EquipmentSlot.HEAD, new ItemStack(tier == Tier.BOSS ? Items.NETHERITE_HELMET : Items.LEATHER_HELMET)); // 햇빛에 안 타게
        z.setEquipmentDropChance(EquipmentSlot.HEAD, 0f);
        if (tier == Tier.SKILLED) { // 숙련은 검을 들고 검 평타(5타, 조금 셈)를 씀
            z.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            z.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        }
        EntityAttributeInstance hp = z.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (hp != null) hp.setBaseValue(tier.hp);
        z.setHealth(tier.hp);
        EntityAttributeInstance fr = z.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE);
        if (fr != null) fr.setBaseValue(24);
        w.spawnEntity(z);
        BRAINS.put(z.getUuid(), new Brain(tier));
    }
}
