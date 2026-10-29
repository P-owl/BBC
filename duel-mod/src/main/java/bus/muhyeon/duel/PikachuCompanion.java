package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.net.messages.client.animation.PlayPosableAnimationPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 연금술사(등급 2+)가 전투 모드에 들어가면 파티의 연금술사/현자 피카츄가 나와서 같이 싸움.
 * 피해는 주인의 공격으로 취급(서버 PvP 설정을 따름)하고, 플레이어 PvP 수치에 맞춤 (플레이어 체력 20 기준).
 *
 * 연금술사 폼: 강철 팔칼날(돌진 9) · 그라운드 포지(가시 줄 8) · 번개 펀치(번개 8) · 웨폰 포지(주인 힘 I 8초)
 * 현자 폼:     어스 리빌드(가시 고리 9) · 스톤 포지(폭발 11, 반경 2.5) · 라이프 포지(주인 회복 8) · 소울 배리어(주인 저항 II 6초)
 */
public final class PikachuCompanion {
    private PikachuCompanion() {}

    private static final class State {
        Pokemon mon;
        boolean sage;
        int cooldown = 20;
        int turn = 0;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final double RANGE = 16;     // 주인에게서 이 거리 안의 상대만
    private static final int ATTACK_GAP = 6;     // tick() 이 5틱마다 불리므로 6 = 1.5초

    public static void start(ServerPlayerEntity p) {
        Pokemon pick = null;
        for (Pokemon mon : Cobblemon.INSTANCE.getStorage().getParty(p).toGappyList()) {
            if (mon == null || mon.isFainted()) continue;
            if (AlchemistForm.isAlchemist(mon) || AlchemistForm.isSage(mon)) { pick = mon; break; }
        }
        if (pick == null) {
            p.sendMessage(Text.literal("파티에 연금술사 피카츄가 없어 혼자 싸웁니다").formatted(Formatting.GRAY), false);
            return;
        }
        PokemonEntity ent = pick.getEntity();
        if (ent == null) {
            Vec3d at = p.getPos().add(p.getRotationVector().multiply(-1.2)).add(0, 0.1, 0);
            ent = pick.sendOut(p.getServerWorld(), at, null, e -> kotlin.Unit.INSTANCE);
        }
        if (ent == null) return;
        ent.setInvulnerable(true);
        State s = new State();
        s.mon = pick;
        s.sage = AlchemistForm.isSage(pick);
        STATES.put(p.getUuid(), s);
        Alchemy.sound(p.getServerWorld(), ent.getPos(), s.sage ? "cobblemon:pokemon.pikachu_sage.cry" : "cobblemon:pokemon.pikachu_alchemist.cry", 1.0f);
        p.sendMessage(Text.literal("⚡ " + pick.getSpecies().getTranslatedName().getString() + (s.sage ? "(현자)" : "(연금술사)") + "이(가) 함께 싸웁니다!")
                .formatted(Formatting.YELLOW), false);
    }

    public static void stop(ServerPlayerEntity p) {
        State s = STATES.remove(p.getUuid());
        if (s == null) return;
        PokemonEntity ent = s.mon.getEntity();
        if (ent != null) {
            ent.setInvulnerable(false);
            if (!ent.isBattling()) s.mon.recall();
        }
    }

    static void tick(ServerPlayerEntity p) {
        State s = STATES.get(p.getUuid());
        if (s == null) return;
        PokemonEntity ent = s.mon.getEntity();
        if (ent == null || ent.isRemoved()) {
            STATES.remove(p.getUuid());
            p.sendMessage(Text.literal("피카츄가 볼로 돌아갔습니다").formatted(Formatting.GRAY), true);
            return;
        }
        if (ent.getWorld() != p.getWorld() || ent.squaredDistanceTo(p) > 32 * 32) {
            ent.requestTeleport(p.getX(), p.getY(), p.getZ()); // 너무 멀어지면 주인 곁으로
            return;
        }
        if (--s.cooldown > 0) return;

        LivingEntity target = findTarget(p, ent);
        if (target == null) { s.cooldown = 1; return; }
        s.cooldown = ATTACK_GAP;
        s.turn++;
        ServerWorld w = p.getServerWorld();
        if (s.sage) sageMove(p, ent, target, w, s.turn); else alchemistMove(p, ent, target, w, s.turn);
    }

    // ───────── 연금술사 폼 ─────────

    private static void alchemistMove(ServerPlayerEntity owner, PokemonEntity ent, LivingEntity t, ServerWorld w, int turn) {
        if (turn % 6 == 0) { // 웨폰 포지: 주인 강화
            AlchemyState.blade(s(ent));
            anim(ent, owner, "weaponforge");
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 160, 0));
            Alchemy.sound(w, ent.getPos(), "cobblemon:move.alchemy.weapon_ed", 1.0f);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, owner.getX(), owner.getY() + 1, owner.getZ(), 20, 0.4, 0.6, 0.4, 0.2);
            return;
        }
        switch (turn % 3) {
            case 1 -> { // 강철 팔칼날: 꼬리를 칼날로 연성 → 돌진해서 베기
                AlchemyState.blade(s(ent));
                anim(ent, t, "steelarmblade");
                Alchemy.sound(w, ent.getPos(), "cobblemon:move.alchemy.rush_ed", 1.0f);
                Tasks.later(6, () -> {
                    if (!t.isAlive() || ent.isRemoved()) return;
                    Vec3d dir = t.getPos().subtract(ent.getPos()).normalize();
                    Vec3d to = t.getPos().subtract(dir.multiply(1.0));
                    spark(w, ent.getPos(), to);
                    ent.requestTeleport(to.x, t.getY(), to.z);
                    w.spawnParticles(ParticleTypes.SWEEP_ATTACK, t.getX(), t.getY() + 1, t.getZ(), 1, 0, 0, 0, 0);
                    hit(owner, t, 9f, dir.multiply(0.6));
                });
            }
            case 2 -> { // 그라운드 포지: 피카츄 → 상대까지 가시 줄
                anim(ent, t, "groundforge");
                Alchemy.sound(w, ent.getPos(), "cobblemon:move.alchemy.ground_ed", 1.0f);
                Vec3d a = ent.getPos(), b = t.getPos();
                int n = (int) Math.max(2, Math.min(12, a.distanceTo(b)));
                Set<UUID> done = new HashSet<>();
                for (int i = 1; i <= n; i++) {
                    final Vec3d q = a.lerp(b, i / (double) n);
                    Tasks.later(8 + i, () -> {
                        AlchemySkills.display(w, Blocks.POINTED_DRIPSTONE.getDefaultState(), q.x, q.y, q.z, 0.5f, 1.3f, 0.5f, 0, 16, 2);
                        for (Entity e : w.getOtherEntities(owner, new Box(q.x - 0.8, q.y, q.z - 0.8, q.x + 0.8, q.y + 2, q.z + 0.8), x -> x == t))
                            if (done.add(e.getUuid())) hit(owner, (LivingEntity) e, 8f, new Vec3d(0, 0.5, 0));
                    });
                }
            }
            default -> { // 번개 펀치: 하늘에서 번개 (불 안 붙음). physical 애니메이션에 효과음 포함
                anim(ent, t, "physical");
                Tasks.later(8, () -> {
                if (!t.isAlive()) return;
                LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
                if (bolt != null) {
                    bolt.setCosmetic(true);
                    bolt.refreshPositionAndAngles(t.getX(), t.getY(), t.getZ(), 0, 0);
                    w.spawnEntity(bolt);
                }
                hit(owner, t, 8f, Vec3d.ZERO);
                t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 1));
                });
            }
        }
    }

    // ───────── 현자 폼 ─────────

    private static void sageMove(ServerPlayerEntity owner, PokemonEntity ent, LivingEntity t, ServerWorld w, int turn) {
        if (owner.getHealth() < owner.getMaxHealth() * 0.5f && turn % 2 == 0) { // 라이프 포지
            anim(ent, owner, "lifeforge");
            owner.heal(8f);
            Alchemy.sound(w, ent.getPos(), "cobblemon:move.alchemy.hoen_life", 1.0f);
            w.spawnParticles(ParticleTypes.HEART, owner.getX(), owner.getY() + 1.5, owner.getZ(), 6, 0.4, 0.3, 0.4, 0);
            return;
        }
        if (turn % 5 == 0) { // 소울 배리어
            anim(ent, owner, "soulbarrier");
            owner.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 120, 1));
            w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, owner.getX(), owner.getY() + 1, owner.getZ(), 24, 0.5, 0.7, 0.5, 0.02);
            Alchemy.sound(w, ent.getPos(), "cobblemon:move.alchemy.spark_sage", 1.0f);
            return;
        }
        if (turn % 2 == 1) { // 어스 리빌드: 상대 주위 가시 고리
            anim(ent, t, "earthrebuild");
            Alchemy.sound(w, ent.getPos(), "cobblemon:move.alchemy.ground_hoen", 1.0f);
            Vec3d c = t.getPos();
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                AlchemySkills.display(w, Blocks.DRIPSTONE_BLOCK.getDefaultState(), c.x + Math.cos(a) * 1.4, c.y, c.z + Math.sin(a) * 1.4, 0.5f, 1.8f, 0.5f, 8 + i, 20, 2);
            }
            Tasks.later(12, () -> hit(owner, t, 9f, new Vec3d(0, 0.7, 0)));
        } else { // 스톤 포지: 상대 자리 폭발
            anim(ent, t, "stoneforge");
            Alchemy.sound(w, ent.getPos(), "cobblemon:move.alchemy.burst_hoen", 1.0f);
            Vec3d c = t.getPos();
            Tasks.later(12, () -> {
                w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 3, 0.6, 0.3, 0.6, 0);
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), c.x, c.y + 0.3, c.z, 30, 1, 0.3, 1, 0.2);
                for (Entity e : w.getOtherEntities(owner, new Box(c.x - 2.5, c.y - 1, c.z - 2.5, c.x + 2.5, c.y + 3, c.z + 2.5), x -> valid(owner, x) && x != ent)) {
                    if (e.squaredDistanceTo(c) > 2.5 * 2.5) continue;
                    if (!isHostileOrFoe(owner, e)) continue;
                    hit(owner, (LivingEntity) e, 11f, e.getPos().subtract(c).normalize().multiply(0.7).add(0, 0.3, 0));
                }
            });
        }
    }

    // ───────── 대상 ─────────

    /** 우선순위: 주인을 때린 상대 → 주인이 때린 상대 → 주인 근처 적대 몹 */
    private static LivingEntity findTarget(ServerPlayerEntity owner, PokemonEntity ent) {
        LivingEntity a = owner.getAttacker();
        if (a != null && valid(owner, a) && a.squaredDistanceTo(owner) < RANGE * RANGE) return a;
        LivingEntity b = owner.getAttacking();
        if (b != null && valid(owner, b) && owner.age - owner.getLastAttackTime() < 200 && b.squaredDistanceTo(owner) < RANGE * RANGE) return b;
        LivingEntity best = null;
        double bestD = 10 * 10;
        for (Entity e : owner.getServerWorld().getOtherEntities(owner, owner.getBoundingBox().expand(10), x -> x instanceof HostileEntity && valid(owner, x))) {
            double d = e.squaredDistanceTo(owner);
            if (d < bestD) { bestD = d; best = (LivingEntity) e; }
        }
        return best;
    }

    private static boolean isHostileOrFoe(ServerPlayerEntity owner, Entity e) {
        return e instanceof HostileEntity || e == owner.getAttacker() || e == owner.getAttacking();
    }

    /** 맞힐 수 있는 대상 (Targets 규칙: 야생 포켓몬 포함, 내 포켓몬 제외) */
    private static boolean valid(ServerPlayerEntity owner, Entity e) {
        return Targets.canHit(owner, e);
    }

    private static void hit(ServerPlayerEntity owner, LivingEntity t, float dmg, Vec3d push) {
        if (!t.isAlive()) return;
        if (Targets.damage(owner, t, dmg) && push.lengthSquared() > 0) {
            t.addVelocity(push.x, push.y, push.z);
            t.velocityModified = true;
        }
    }

    /** 코블몬 포즈 애니메이션 재생 (포저의 animations 키: steelarmblade, groundforge, stoneforge …) */
    private static Pokemon s(PokemonEntity ent) { return ent.getPokemon(); }

    private static void anim(PokemonEntity ent, LivingEntity face, String name) {
        if (face != null) {
            Vec3d d = face.getPos().subtract(ent.getPos());
            float yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90);
            ent.setYaw(yaw);
            ent.setBodyYaw(yaw);
            ent.setHeadYaw(yaw);
        }
        if (ent.getWorld() instanceof ServerWorld sw) {
            new PlayPosableAnimationPacket(ent.getId(), java.util.Set.of(name), java.util.List.of()).sendToPlayers(sw.getPlayers());
        }
    }

    private static void spark(ServerWorld w, Vec3d a, Vec3d b) {
        for (int i = 0; i < 12; i++) {
            Vec3d q = a.lerp(b, i / 12.0).add(0, 0.4, 0);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, q.x, q.y, q.z, 1, 0.05, 0.05, 0.05, 0);
        }
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, b.x, b.y + 0.5, b.z, 10, 0.3, 0.3, 0.3, ThreadLocalRandom.current().nextDouble(0.1, 0.3));
    }
}
