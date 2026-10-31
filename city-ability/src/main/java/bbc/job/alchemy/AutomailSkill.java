package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 오토메일 연성 (전투 모드 8번 키, 진리 + 오토메일 장착자).
 *   의수: 팔에서 칼날을 연성 — 20초 공격력 +6 (칼날 모델 표시)
 *   의족: 다리에서 칼날을 연성해 앞으로 돌진하며 발차기 — 앞쪽 상대에게 14 피해 + 크게 날려 보냄
 *   강철 심장: 과부하 — 20초 신속 II · 힘 II, 몸에서 증기가 뿜어져 나옴
 * 비용: 연성 에너지 16,384 E · 재사용 60초
 * 그리고 몸 상태(잃은 팔·다리, 오토메일, 칼날)를 모든 클라이언트에 알려서 모델에 그리게 함.
 */
public final class AutomailSkill {
    private AutomailSkill() {}

    public static final double COST = 16_384;           // 의수·심장
    public static final double KICK_COST = 8_192;       // 의족 발차기
    public static final int DURATION = 20 * 20, COOLDOWN_MS = 60_000, KICK_COOLDOWN_MS = 20_000;

    public static double cost(AlchemyData.Entry e) {
        return "leg".equals(e.toll) ? KICK_COST : COST;
    }
    private static final Identifier BUFF_ID = Identifier.of("bbc", "automail_blade");
    private static final Identifier SPEED_ID = Identifier.of("bbc", "automail_leg_speed");

    private static final Map<UUID, Integer> BUFF = new HashMap<>();   // 켜진 기술 종류
    private static final Map<UUID, Long> CD = new HashMap<>();

    public static String name(String part) {
        return switch (part) {
            case "arm" -> "의수 칼날 연성";
            case "leg" -> "의족 칼날 차기";
            case "heart" -> "강철 심장 과부하";
            default -> "오토메일 연성";
        };
    }

    public static boolean available(AlchemyData.Entry e) { return e.truth && !e.toll.isEmpty() && e.toll.equals(e.automail); }

    public static int cooldownLeft(UUID id) {
        Long until = CD.get(id);
        if (until == null) return 0;
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000 + 1);
    }

    public static void resetCooldown(UUID id) { CD.remove(id); }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(BodyPayload.ID, BodyPayload.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity joiner = handler.getPlayer();
            // 들어온 사람에게 다른 사람들 몸 상태를 알려줌 (자기 것은 Alch.applyToll 에서 방송)
            Tasks.later(6, () -> {
                if (joiner.isDisconnected()) return;
                for (ServerPlayerEntity o : server.getPlayerManager().getPlayerList()) {
                    if (o != joiner) ServerPlayNetworking.send(joiner, payload(o));
                }
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> { BUFF.remove(handler.getPlayer().getUuid()); FORGED.remove(handler.getPlayer().getUuid()); });
        ServerTickEvents.END_SERVER_TICK.register(AutomailSkill::tick);
    }

    static BodyPayload payload(ServerPlayerEntity p) {
        AlchemyData.Entry e = Alch.entry(p);
        int buff = BUFF.getOrDefault(p.getUuid(), 0) | (FORGED.contains(p.getUuid()) ? FORGED_BIT : 0);
        return new BodyPayload(p.getUuid(), e.toll, e.automail, buff);
    }

    /** 무기 연성(공중) 칼날: 오토메일 의수(없으면 장갑 낀 팔) 바깥에 칼날을 그림. buff 의 8 비트 */
    public static final int FORGED_BIT = 8;
    private static final java.util.Set<UUID> FORGED = new java.util.HashSet<>();

    public static void setForgedBlade(ServerPlayerEntity p, boolean on) {
        boolean changed = on ? FORGED.add(p.getUuid()) : FORGED.remove(p.getUuid());
        if (changed) broadcast(p);
    }

    /** 이 플레이어의 몸 상태를 접속한 모두에게 */
    public static void broadcast(ServerPlayerEntity p) {
        if (p.getServer() == null) return;
        BodyPayload pl = payload(p);
        for (ServerPlayerEntity o : p.getServer().getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(o, BodyPayload.ID)) ServerPlayNetworking.send(o, pl);
        }
    }

    public static void use(ServerPlayerEntity p) {
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        if (!available(e)) { bar(p, "오토메일을 단 진리의 연금술사만 쓸 수 있습니다", Formatting.RED); return; }
        int left = cooldownLeft(p.getUuid());
        if (left > 0) { bar(p, name(e.toll) + " 재사용까지 " + left + "초", Formatting.GRAY); return; }
        double cost = cost(e);
        if (e.energy + 1e-6 < cost) { bar(p, "연성 에너지 부족 (" + Alchemy.fmt(cost) + " E 필요)", Formatting.RED); return; }
        e.energy -= cost;
        data.markDirty();
        boolean kick = "leg".equals(e.toll);
        CD.put(p.getUuid(), System.currentTimeMillis() + (kick ? KICK_COOLDOWN_MS : COOLDOWN_MS));
        if (kick) { kick(p, cost); return; }

        ServerWorld w = p.getServerWorld();
        int kind;
        switch (e.toll) {
            case "arm" -> {
                kind = 1;   // 의수에서 칼날이 튀어나옴
                var f = bbc.combat.Combat.of(p);   // 새 전투: 20초 주는 피해 ×1.3
                f.dmgBuffUntil = bbc.combat.Combat.now() + DURATION;
                f.dmgBuffMul = 1.3f;
            }
            default -> {
                kind = 3;
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, DURATION, 1));
                var f = bbc.combat.Combat.of(p);   // 강철 심장 과부하: 신속 II + 주는 피해 ×1.2
                f.dmgBuffUntil = bbc.combat.Combat.now() + DURATION;
                f.dmgBuffMul = 1.2f;
            }
        }
        BUFF.put(p.getUuid(), kind);
        broadcast(p);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 1.4f);
        w.playSound(null, p.getBlockPos(), kind == 3 ? SoundEvents.BLOCK_FIRE_EXTINGUISH : SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), SoundCategory.PLAYERS, 1.0f, 0.8f);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.6, 0.4, 0.3);
        bar(p, "⚙ " + name(e.toll) + "!  (-" + Alchemy.fmt(cost) + " E, 20초)", Formatting.GOLD);

        final UUID id = p.getUuid();
        Tasks.later(DURATION, () -> end(p, id));
    }

    private static void end(ServerPlayerEntity p, UUID id) {
        Integer was = BUFF.remove(id);
        if (was == null) return;
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (a != null) a.removeModifier(BUFF_ID);
        EntityAttributeInstance s = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (s != null) s.removeModifier(SPEED_ID);
        if (!p.isDisconnected()) {
            broadcast(p);
            if (was != 2) bar(p, "오토메일 연성이 풀렸습니다", Formatting.GRAY);
        }
    }

    /** 의족 칼날 차기: 칼날을 세우고 앞으로 돌진 → 앞쪽 3칸 안의 상대를 걷어참 */
    private static void kick(ServerPlayerEntity p, double cost) {
        ServerWorld w = p.getServerWorld();
        BUFF.put(p.getUuid(), 2);               // 다리 칼날 모델 표시
        broadcast(p);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.7f, 1.6f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), SoundCategory.PLAYERS, 1.0f, 1.2f);
        Vec3d look = new Vec3d(p.getRotationVector().x, 0, p.getRotationVector().z).normalize();
        p.setVelocity(look.x * 1.15, 0.28, look.z * 1.15);
        p.velocityModified = true;
        bar(p, "⚙ 의족 칼날 차기!  (-" + Alchemy.fmt(cost) + " E)", Formatting.GOLD);

        java.util.Set<UUID> hit = new java.util.HashSet<>();
        for (int t = 2; t <= 8; t += 2) {
            final int tick = t;
            Tasks.later(t, () -> {
                if (p.isDisconnected()) return;
                Vec3d c = p.getPos().add(look.multiply(1.2)).add(0, 0.6, 0);
                w.spawnParticles(BLUE_DUST, c.x, c.y, c.z, 8, 0.4, 0.3, 0.4, 0);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 0.2);
                for (net.minecraft.entity.Entity e : w.getOtherEntities(p, p.getBoundingBox().expand(3.2), x -> Targets.canHit(p, x))) {
                    Vec3d to = e.getPos().subtract(p.getPos());
                    if (to.horizontalLength() > 3.2 || new Vec3d(to.x, 0, to.z).normalize().dotProduct(look) < 0.35) continue;
                    if (!hit.add(e.getUuid())) continue;
                    bbc.combat.Combat.hit(p, (net.minecraft.entity.LivingEntity) e, LEG_KICK);
                    w.spawnParticles(ParticleTypes.SWEEP_ATTACK, e.getX(), e.getY() + 1, e.getZ(), 1, 0, 0, 0, 0);
                    w.spawnParticles(ParticleTypes.CRIT, e.getX(), e.getY() + 1, e.getZ(), 20, 0.4, 0.5, 0.4, 0.4);
                    w.playSound(null, e.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.2f, 0.7f);
                    w.playSound(null, e.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.5f, 1.4f);
                }
            });
        }
        final UUID id = p.getUuid();
        Tasks.later(30, () -> end(p, id));       // 칼날은 1.5초 뒤 접힘
    }

    /** 의족 칼날 차기: 날려보내기 (힘 기준) */
    static final bbc.combat.Hit LEG_KICK = new bbc.combat.Hit(2.0f, bbc.combat.Hit.Kind.KNOCK, 16, 1.4, 0.35, 30, false, false);

    private static final net.minecraft.particle.DustParticleEffect BLUE_DUST =
            new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.35f, 0.75f, 1.0f), 1.0f);

    private static int clock = 0;

    /** 강철 심장 과부하: 가슴에서 증기 */
    private static void tick(MinecraftServer server) {
        if (BUFF.isEmpty() || ++clock % 3 != 0) return;
        for (Map.Entry<UUID, Integer> en : BUFF.entrySet()) {
            if (en.getValue() != 3) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(en.getKey());
            if (p == null) continue;
            ServerWorld w = p.getServerWorld();
            double yaw = Math.toRadians(p.getBodyYaw());
            double bx = -Math.sin(yaw) * 0.25, bz = Math.cos(yaw) * 0.25; // 가슴 앞쪽
            w.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.getX() + bx, p.getY() + 1.3, p.getZ() + bz, 1, 0.08, 0.05, 0.08, 0.01);
            w.spawnParticles(ParticleTypes.CLOUD, p.getX() - bx, p.getY() + 1.4, p.getZ() - bz, 2, 0.15, 0.1, 0.15, 0.02);
            if (clock % 30 == 0) w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.3f, 1.6f);
        }
    }

    private static void attr(ServerPlayerEntity p, net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute> a,
                             Identifier id, double v, EntityAttributeModifier.Operation op) {
        EntityAttributeInstance inst = p.getAttributeInstance(a);
        if (inst == null) return;
        inst.removeModifier(id);
        inst.addTemporaryModifier(new EntityAttributeModifier(id, v, op));
    }

    private static void bar(ServerPlayerEntity p, String m, Formatting f) { p.sendMessage(Text.literal(m).formatted(f), true); }
}
