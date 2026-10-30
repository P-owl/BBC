package bbc.combat;

import bbc.core.Tasks;
import bbc.job.JobSkills;
import bbc.job.Stamina;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 전투 엔진 (1단계). 판정·상태는 전부 서버가 결정한다.
 *
 *  상태: 평상 / 경직 / 공중 경직 / 다운 / 가드 깨짐  (+ 기상·대시·회피기 무적)
 *  경직 중에는 이동 속도·점프를 0으로 만드는 속성 수정자를 걸어 플레이어도 몬스터도 못 움직이게 하고,
 *  경직 중인 쪽이 주는 피해는 막는다 (몬스터가 경직 중에 때리지 못함).
 *  우리 공격은 넣기 직전 피격 무적을 0으로 만들고, 바닐라 넉백 대신 속도를 직접 넣는다.
 */
public final class Combat {
    private Combat() {}

    private static final Map<UUID, Fighter> FIGHTERS = new HashMap<>();
    private static final ThreadLocal<Boolean> OURS = ThreadLocal.withInitial(() -> false);
    static long now = 0;

    private static final Identifier SPEED_ID = Identifier.of("bbc", "combat_speed");
    private static final Identifier JUMP_ID = Identifier.of("bbc", "combat_jump");
    private static final Identifier GRAV_ID = Identifier.of("bbc", "combat_gravity");

    public static final String DUMMY_TAG = "bbc_dummy";

    // ═════════ 조회 ═════════

    public static Fighter of(LivingEntity e) {
        Fighter f = FIGHTERS.computeIfAbsent(e.getUuid(), k -> new Fighter(e));
        f.touchedAt = now;
        return f;
    }

    public static Fighter peek(LivingEntity e) { return FIGHTERS.get(e.getUuid()); }

    /** 기술·평타를 쓸 수 있는 상태인가 (경직·다운·가드 깨짐이 아님) */
    public static boolean canAct(LivingEntity e) {
        Fighter f = peek(e);
        return f == null || !f.stunned();
    }

    /** 액션바에 콤보를 보여주는 중이면 직업 HUD로 덮지 않는다 */
    public static boolean showingCombo(ServerPlayerEntity p) {
        Fighter f = peek(p);
        return f != null && now < f.comboShowUntil;
    }

    /** 직업 HUD 앞에 붙는 전투 상태 (가드 게이지·회피기) */
    public static MutableText hudPrefix(ServerPlayerEntity p) {
        Fighter f = peek(p);
        MutableText t = Text.empty();
        if (f == null) return t;
        if (f.guardHeld || f.guardGauge < Tuning.GUARD_MAX) {
            Formatting c = f.guardGauge < 30 ? Formatting.RED : f.guardHeld ? Formatting.AQUA : Formatting.GRAY;
            t.append(Text.literal("🛡" + (int) f.guardGauge + " ").formatted(c));
        }
        long ev = f.evadeCdUntil - now;
        t.append(ev > 0 ? Text.literal("회피 " + (ev / 20 + 1) + "s  ").formatted(Formatting.DARK_GRAY)
                : Text.literal("회피 ✔  ").formatted(Formatting.GREEN));
        return t;
    }

    /** 전투 모드를 끌 때 가드 같은 입력 상태를 지움 */
    public static void releaseInputs(ServerPlayerEntity p) {
        Fighter f = peek(p);
        if (f != null) { f.guardHeld = false; f.buffered = 0; }
    }

    // ═════════ 평타 ═════════

    /** 평타 입력. up = 스페이스를 누른 채 (띄우기·래더) */
    public static void m1(ServerPlayerEntity p, boolean up) {
        Fighter a = of(p);
        if (a.stunned() || a.guardHeld || a.dashTicks > 0) return;
        if (now < a.m1ReadyAt) {
            if (a.m1ReadyAt - now <= Tuning.INPUT_BUFFER) { a.buffered = up ? 2 : 1; a.bufferedAt = now; }
            return;
        }
        doM1(p, a, up);
    }

    private static void doM1(ServerPlayerEntity p, Fighter a, boolean up) {
        a.buffered = 0;
        if (now > a.m1ChainUntil) { a.m1Index = 0; a.airHits = 0; }
        boolean air = !p.isOnGround() && now < a.airComboUntil;
        if (air) {
            if (a.airHits < 3) {
                swing(p, a, Tuning.M1_STARTUP, Tuning.M1_RECOVERY, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, () -> {
                    hitCone(p, Tuning.AIR_M1, false);
                    a.airHits++;
                    a.lastAirHitAt = now;
                    a.airComboUntil = now + 24;
                    setVel(p, new Vec3d(p.getVelocity().x * 0.3, 0.12, p.getVelocity().z * 0.3));
                });
            } else if (up && now - a.lastAirHitAt >= Tuning.LADDER_PAUSE) {
                swing(p, a, Tuning.LAUNCH_STARTUP, Tuning.LAUNCH_RECOVERY, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, () -> {
                    if (hitCone(p, Tuning.LADDER, true) > 0) {
                        a.airHits = 0;
                        a.airComboUntil = now + 40;
                        setVel(p, new Vec3d(0, Tuning.LADDER.lift(), 0));
                    }
                });
            } else {
                swing(p, a, Tuning.SLAM_STARTUP, Tuning.SLAM_RECOVERY, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, () -> {
                    hitCone(p, Tuning.SLAM, false);
                    a.airHits = 0;
                    a.airComboUntil = 0;
                    a.m1Index = 0;
                    setVel(p, new Vec3d(0, -0.6, 0));
                });
            }
            return;
        }
        int idx = a.m1Index;
        if (idx == 3 && up) {
            a.m1Index = 0;
            swing(p, a, Tuning.LAUNCH_STARTUP, Tuning.LAUNCH_RECOVERY, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, () -> {
                if (hitCone(p, Tuning.LAUNCH, false) > 0) {
                    a.airHits = 0;
                    a.airComboUntil = now + 40;
                    setVel(p, new Vec3d(0, Tuning.LAUNCH_VY, 0));
                }
            });
        } else if (idx >= 4) {
            a.m1Index = 0;
            swing(p, a, Tuning.FINISH_STARTUP, Tuning.FINISH_NEXT - Tuning.FINISH_STARTUP - 1, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK,
                    () -> hitCone(p, Tuning.FINISH, false));
        } else {
            a.m1Index++;
            swing(p, a, Tuning.M1_STARTUP, Tuning.M1_RECOVERY, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                    () -> hitCone(p, Tuning.M1, false));
        }
    }

    /** 휘두르기 시작 → 선딜 뒤 판정. 그사이 맞아서 경직되면 취소된다 */
    private static void swing(ServerPlayerEntity p, Fighter a, int startup, int recovery, SoundEvent sfx, Runnable resolve) {
        int tok = ++a.token;
        a.m1ReadyAt = now + startup + 1 + recovery;
        a.recoveryUntil = a.m1ReadyAt;
        a.m1ChainUntil = a.m1ReadyAt + Tuning.CHAIN_RESET;
        p.swingHand(Hand.MAIN_HAND, true);
        sound(p, sfx, 0.5f, 1.3f);
        Tasks.later(startup, () -> {
            if (a.token != tok || a.stunned() || !p.isAlive() || p.isRemoved()) return;
            resolve.run();
        });
    }

    /** 앞쪽 부채꼴 안의 대상들을 때림. 맞힌 수를 돌려줌 */
    private static int hitCone(ServerPlayerEntity p, Hit h, boolean ladder) {
        int n = 0;
        for (LivingEntity t : cone(p, Tuning.M1_RANGE, Tuning.M1_HALF_ANGLE_DEG, Tuning.M1_MAX_TARGETS)) {
            if (ladder) {
                Fighter v = peek(t);
                if (v == null || v.ladderUsed || v.state != Fighter.State.AIR) continue;
                v.ladderUsed = true;
            }
            if (hit(p, t, h) != Result.MISS) n++;
        }
        return n;
    }

    private static List<LivingEntity> cone(LivingEntity a, double range, double halfAngleDeg, int max) {
        Vec3d eye = a.getEyePos(), look = a.getRotationVec(1f);
        double cos = Math.cos(Math.toRadians(halfAngleDeg));
        Box box = a.getBoundingBox().expand(range + 1);
        List<LivingEntity> out = new ArrayList<>();
        for (Entity en : a.getWorld().getOtherEntities(a, box, x -> x instanceof LivingEntity && x.isAlive() && !(x instanceof ArmorStandEntity))) {
            LivingEntity t = (LivingEntity) en;
            // 대상 몸통에서 가장 가까운 점까지
            Vec3d c = closest(t.getBoundingBox(), eye);
            Vec3d to = c.subtract(eye);
            double d = to.length();
            if (d > range) continue;
            Vec3d mid = t.getBoundingBox().getCenter().subtract(eye);
            if (d > 0.6 && mid.normalize().dotProduct(look) < cos) continue;
            out.add(t);
        }
        out.sort(Comparator.comparingDouble(t -> t.squaredDistanceTo(a)));
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static Vec3d closest(Box b, Vec3d p) {
        return new Vec3d(Math.max(b.minX, Math.min(p.x, b.maxX)), Math.max(b.minY, Math.min(p.y, b.maxY)), Math.max(b.minZ, Math.min(p.z, b.maxZ)));
    }

    // ═════════ 맞히기 (기술도 이걸 부른다) ═════════

    public enum Result { MISS, BLOCKED, PARRIED, HIT }

    public static Result hit(LivingEntity atk, LivingEntity t, Hit h) {
        if (t == atk || !t.isAlive() || t.isRemoved()) return Result.MISS;
        Fighter v = of(t);
        if (now < v.invulnUntil) return Result.MISS;
        if (v.state == Fighter.State.DOWN && !h.downHit()) return Result.MISS;

        // 가드·패링 (앞에서 온 공격만, 평상 상태이고 후딜이 아닐 때)
        if (v.guardHeld && v.state == Fighter.State.IDLE && now >= v.recoveryUntil && front(t, atk.getPos())) {
            if (now <= v.parryUntil) { parry(v, atk); return Result.PARRIED; }
            v.guardUsedAt = now;
            v.guardGauge -= h.guardDmg();
            if (h.guardBreak() || v.guardGauge <= 0) {
                breakGuard(v);
                if (!deal(atk, t, h.damage())) return Result.MISS;
                setVel(t, away(atk, t).multiply(0.3).add(0, t.getVelocity().y, 0));
                return Result.HIT;
            }
            if (!deal(atk, t, h.damage() * Tuning.GUARD_DAMAGE_TAKEN)) return Result.MISS;
            setVel(t, away(atk, t).multiply(0.15).add(0, t.getVelocity().y, 0));
            sound(t, SoundEvents.ITEM_SHIELD_BLOCK, 0.8f, 1.1f);
            return Result.BLOCKED;
        }

        // 콤보 보정
        Fighter a = of(atk);
        if (v.comboHits == 0) { v.comboDamage = 0; v.ladderUsed = false; }
        v.comboHits++;
        float scale = v.comboHits <= Tuning.SCALE_FREE_HITS ? 1f
                : Math.max(Tuning.SCALE_MIN, 1f - Tuning.SCALE_PER_HIT * (v.comboHits - Tuning.SCALE_FREE_HITS));
        int stun = h.stun();
        if (h.kind() == Hit.Kind.HIT || h.kind() == Hit.Kind.AIR)
            stun = Math.max(Tuning.STUN_MIN, stun - (v.comboHits - 1) / Tuning.STUN_DECAY_EVERY);
        float dmg = h.damage() * scale;

        if (!deal(atk, t, dmg)) { v.comboHits--; return Result.MISS; }
        v.comboDamage += dmg;
        v.lastHitAt = now;
        v.lastAttacker = atk.getUuid();

        // 속도 (바닐라 넉백을 덮어씀)
        Vec3d dir = away(atk, t);
        Vec3d vel = switch (h.kind()) {
            case HIT -> new Vec3d(dir.x * h.push(), Math.min(t.getVelocity().y, 0.0), dir.z * h.push());
            case AIR, KNOCK, DOWN -> new Vec3d(dir.x * h.push(), h.lift(), dir.z * h.push());
        };
        setVel(t, vel);

        // 상태
        switch (h.kind()) {
            case HIT -> setState(v, Fighter.State.HITSTUN, stun);
            case AIR -> {
                setState(v, Fighter.State.AIR, stun);
                // 띄운 쪽은 점프해서 바로 공중 평타로 이을 수 있음
                if (atk instanceof ServerPlayerEntity) a.airComboUntil = Math.max(a.airComboUntil, now + 40);
            }
            case DOWN -> setState(v, Fighter.State.DOWN, stun + 8); // 떨어지는 시간 포함
            case KNOCK -> setState(v, Fighter.State.DOWN, stun + 10);
        }

        // 연출
        ServerWorld w = (ServerWorld) t.getWorld();
        Vec3d c = t.getBoundingBox().getCenter();
        ParticleEffect fx = h.kind() == Hit.Kind.HIT ? ParticleTypes.CRIT : ParticleTypes.SWEEP_ATTACK;
        w.spawnParticles(fx, c.x, c.y, c.z, h.kind() == Hit.Kind.HIT ? 6 : 2, 0.25, 0.25, 0.25, 0.1);
        if (h.kind() == Hit.Kind.DOWN) {
            w.spawnParticles(ParticleTypes.EXPLOSION, t.getX(), t.getY(), t.getZ(), 1, 0, 0, 0, 0);
            sound(t, SoundEvents.BLOCK_ANVIL_LAND, 0.35f, 1.6f);
        }
        sound(t, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 0.9f + Math.min(v.comboHits, 20) * 0.03f);

        if (atk instanceof ServerPlayerEntity sp) {
            a.comboShowUntil = now + 30;
            sp.sendMessage(Text.literal("콤보 " + v.comboHits + "  ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(String.format("피해 %.1f", v.comboDamage)).formatted(Formatting.YELLOW)), true);
        }
        return Result.HIT;
    }

    private static boolean deal(LivingEntity atk, LivingEntity t, float dmg) {
        DamageSource src = atk instanceof PlayerEntity pl ? atk.getDamageSources().playerAttack(pl) : atk.getDamageSources().mobAttack(atk);
        t.timeUntilRegen = 0;
        OURS.set(true);
        try { return t.damage(src, dmg); }
        finally { OURS.set(false); }
    }

    private static void parry(Fighter v, LivingEntity atk) {
        v.parryUntil = 0;
        v.parryLockUntil = now;
        Fighter a = of(atk);
        setState(a, Fighter.State.HITSTUN, Tuning.PARRY_STUN);
        ServerWorld w = (ServerWorld) v.e.getWorld();
        Vec3d c = v.e.getEyePos();
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 14, 0.3, 0.3, 0.3, 0.3);
        sound(v.e, SoundEvents.ITEM_SHIELD_BLOCK, 1f, 1.8f);
        if (v.e instanceof ServerPlayerEntity sp) JobSkills.bar(sp, "✦ 패링!", Formatting.AQUA);
        if (atk instanceof ServerPlayerEntity sp) JobSkills.bar(sp, "패링당했다", Formatting.RED);
    }

    private static void breakGuard(Fighter v) {
        v.guardGauge = 0;
        setState(v, Fighter.State.GUARD_BROKEN, Tuning.GUARD_BROKEN_STUN);
        sound(v.e, SoundEvents.ITEM_SHIELD_BREAK, 1f, 1f);
        if (v.e instanceof ServerPlayerEntity sp) JobSkills.bar(sp, "가드가 깨졌다!", Formatting.RED);
    }

    private static void setState(Fighter f, Fighter.State s, int ticks) {
        f.state = s;
        f.stateUntil = now + ticks;
        if (s == Fighter.State.IDLE) f.stateEndedAt = now;
        else { f.token++; f.dashTicks = 0; f.buffered = 0; }
    }

    // ═════════ 가드·대시·회피기 ═════════

    public static void guard(ServerPlayerEntity p, boolean down) {
        Fighter f = of(p);
        if (!down) { f.guardHeld = false; return; }
        if (f.guardHeld) return;
        f.guardHeld = true;
        if (now >= f.parryLockUntil) {
            f.parryUntil = now + Tuning.PARRY_WINDOW;
            f.parryLockUntil = now + Tuning.PARRY_WINDOW + Tuning.PARRY_FAIL_LOCK;
        }
    }

    /** dir: 0 앞 1 뒤 2 왼쪽 3 오른쪽. 경직 중이면 회피기 */
    public static void dash(ServerPlayerEntity p, int dir) {
        Fighter f = of(p);
        if (f.state == Fighter.State.HITSTUN || f.state == Fighter.State.AIR || f.state == Fighter.State.GUARD_BROKEN) { evade(p, f); return; }
        if (f.state == Fighter.State.DOWN) return;
        if (now < f.dashCdUntil) return;
        boolean cancel = now < f.recoveryUntil;
        double cost = cancel ? Tuning.DASH_CANCEL_COST : Tuning.DASH_COST;
        if (!Stamina.spend(p, cost)) { JobSkills.bar(p, "스태미나 부족", Formatting.RED); return; }
        if (cancel) { f.token++; f.m1ReadyAt = now; f.recoveryUntil = now; }
        double yaw = Math.toRadians(p.getYaw());
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d d = switch (dir) { case 1 -> fwd.negate(); case 2 -> right.negate(); case 3 -> right; default -> fwd; };
        f.dashVel = d.multiply(Tuning.DASH_SPEED);
        f.dashTicks = Tuning.DASH_TICKS;
        f.invulnUntil = Math.max(f.invulnUntil, now + Tuning.DASH_INVULN);
        f.dashCdUntil = now + Tuning.DASH_COOLDOWN;
        sound(p, SoundEvents.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f);
        ((ServerWorld) p.getWorld()).spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.2, p.getZ(), 6, 0.3, 0.05, 0.3, 0.02);
    }

    private static void evade(ServerPlayerEntity p, Fighter f) {
        long left = f.evadeCdUntil - now;
        if (left > 0) { JobSkills.bar(p, "회피기 재사용까지 " + (left / 20 + 1) + "초", Formatting.GRAY); return; }
        setState(f, Fighter.State.IDLE, 0);
        f.invulnUntil = now + Tuning.EVADE_INVULN;
        f.evadeCdUntil = now + Tuning.EVADE_COOLDOWN;
        Entity from = f.lastAttacker == null ? null : ((ServerWorld) p.getWorld()).getEntity(f.lastAttacker);
        Vec3d away = from != null ? horizontal(p.getPos().subtract(from.getPos())) : p.getRotationVec(1f).negate().multiply(1, 0, 1).normalize();
        setVel(p, away.multiply(Tuning.EVADE_PUSH).add(0, 0.3, 0));
        endCombo(f);
        sound(p, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f);
        ((ServerWorld) p.getWorld()).spawnParticles(ParticleTypes.POOF, p.getX(), p.getY() + 1, p.getZ(), 12, 0.3, 0.5, 0.3, 0.05);
    }

    // ═════════ 틱 ═════════

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Combat::tick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(Combat::allowDamage);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            Fighter f = FIGHTERS.remove(h.getPlayer().getUuid());
            if (f != null) clearAttrs(f);
        });
    }

    private static void tick(MinecraftServer server) {
        now++;
        Iterator<Fighter> it = FIGHTERS.values().iterator();
        while (it.hasNext()) {
            Fighter f = it.next();
            LivingEntity e = f.e;
            if (e.isRemoved() || !e.isAlive()) { clearAttrs(f); it.remove(); continue; }

            // 상태 끝
            if (f.state != Fighter.State.IDLE) {
                if (f.state == Fighter.State.AIR && e.isOnGround() && now - f.lastHitAt >= 4) {
                    // 공중 경직 중 착지 → 남은 경직은 짧게
                    f.state = Fighter.State.HITSTUN;
                    f.stateUntil = Math.min(f.stateUntil, now + 8);
                }
                if (now >= f.stateUntil) {
                    Fighter.State was = f.state;
                    setState(f, Fighter.State.IDLE, 0);
                    if (was == Fighter.State.DOWN) f.invulnUntil = now + Tuning.WAKEUP_INVULN;
                    if (was == Fighter.State.GUARD_BROKEN) f.guardGauge = Tuning.GUARD_MAX;
                }
            }

            // 콤보 끝
            if (f.state == Fighter.State.IDLE && f.comboHits > 0
                    && now - Math.max(f.stateEndedAt, f.lastHitAt) > Tuning.COMBO_END_GAP) endCombo(f);

            // 가드 게이지 회복
            if (!f.guardHeld && f.guardGauge < Tuning.GUARD_MAX && now - f.guardUsedAt > Tuning.GUARD_REGEN_DELAY)
                f.guardGauge = Math.min(Tuning.GUARD_MAX, f.guardGauge + Tuning.GUARD_REGEN_PER_TICK);

            // 대시
            if (f.dashTicks > 0) {
                f.dashTicks--;
                setVel(e, new Vec3d(f.dashVel.x, Math.min(e.getVelocity().y, 0.1), f.dashVel.z));
            }

            // 미뤄둔 평타
            if (f.buffered != 0 && e instanceof ServerPlayerEntity sp && now >= f.m1ReadyAt && !f.stunned()) {
                if (now - f.bufferedAt <= Tuning.INPUT_BUFFER + 2) doM1(sp, f, f.buffered == 2);
                else f.buffered = 0;
            }

            // 허수아비: 3초 안 맞으면 체력 회복
            if (e.getCommandTags().contains(DUMMY_TAG) && now - f.lastHitAt > 60 && e.getHealth() < e.getMaxHealth()) e.setHealth(e.getMaxHealth());

            applyAttrs(f);

            // 오래 아무 일 없는 몬스터는 정리
            if (!(e instanceof ServerPlayerEntity) && f.state == Fighter.State.IDLE && f.comboHits == 0
                    && now - Math.max(f.touchedAt, f.lastHitAt) > 200) { clearAttrs(f); it.remove(); }
        }
    }

    private static void endCombo(Fighter f) {
        if (f.e.getCommandTags().contains(DUMMY_TAG) && f.comboHits > 0)
            f.e.setCustomName(Text.literal("허수아비 · 최근 " + f.comboHits + "타 / " + String.format("%.1f", f.comboDamage)).formatted(Formatting.YELLOW));
        f.comboHits = 0;
        f.comboDamage = 0;
        f.ladderUsed = false;
    }

    // ═════════ 바닐라 피해 처리 ═════════

    private static boolean allowDamage(LivingEntity e, DamageSource src, float amount) {
        if (OURS.get()) return true;
        Entity atk = src.getAttacker();
        if (atk instanceof LivingEntity la) {
            Fighter fa = peek(la);
            if (fa != null && fa.stunned()) return false;                                  // 경직 중엔 못 때림
            if (la.getCommandTags().contains(DUMMY_TAG)) return false;                    // 허수아비는 안 때림
            if (la instanceof ServerPlayerEntity sp && CombatMode.active(sp) && src.getSource() == la) return false; // 전투 모드에선 바닐라 근접 꺼짐
        }
        if (e.getCommandTags().contains(DUMMY_TAG) && amount >= e.getHealth() - 1) e.setHealth(e.getMaxHealth());
        Fighter fv = peek(e);
        if (fv == null) return true;
        if (now < fv.invulnUntil) return false;
        Vec3d from = src.getPosition() != null ? src.getPosition() : atk != null ? atk.getPos() : null;
        if (fv.guardHeld && fv.state == Fighter.State.IDLE && from != null && front(e, from)) {
            if (now <= fv.parryUntil && atk instanceof LivingEntity la) { parry(fv, la); return false; }
            fv.guardUsedAt = now;
            fv.guardGauge -= Tuning.VANILLA_GUARD_DMG;
            if (fv.guardGauge <= 0) { breakGuard(fv); return true; }
            OURS.set(true);
            try { e.damage(src, amount * Tuning.GUARD_DAMAGE_TAKEN); } finally { OURS.set(false); }
            sound(e, SoundEvents.ITEM_SHIELD_BLOCK, 0.8f, 1.1f);
            return false;
        }
        return true;
    }

    // ═════════ 속성 수정자 (못 움직이게·낙하 느리게) ═════════

    private static void applyAttrs(Fighter f) {
        LivingEntity e = f.e;
        boolean locked = f.stunned();
        double speed = locked ? -1.0 : (f.guardHeld ? Tuning.GUARD_SPEED : 0);
        double jump = locked ? -1.0 : 0;
        boolean floating = (f.state == Fighter.State.AIR || (now < f.airComboUntil && !e.isOnGround())) && e.getVelocity().y < 0.1;
        double grav = floating ? Tuning.AIR_GRAVITY - 1.0 : 0;
        if (speed != f.curSpeed) { attr(e, EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED_ID, speed); f.curSpeed = speed; }
        if (jump != f.curJump) { attr(e, EntityAttributes.GENERIC_JUMP_STRENGTH, JUMP_ID, jump); f.curJump = jump; }
        if (grav != f.curGrav) { attr(e, EntityAttributes.GENERIC_GRAVITY, GRAV_ID, grav); f.curGrav = grav; }
    }

    private static void clearAttrs(Fighter f) {
        attr(f.e, EntityAttributes.GENERIC_MOVEMENT_SPEED, SPEED_ID, 0);
        attr(f.e, EntityAttributes.GENERIC_JUMP_STRENGTH, JUMP_ID, 0);
        attr(f.e, EntityAttributes.GENERIC_GRAVITY, GRAV_ID, 0);
        f.curSpeed = f.curJump = f.curGrav = 0;
    }

    private static void attr(LivingEntity e, RegistryEntry<EntityAttribute> a, Identifier id, double v) {
        EntityAttributeInstance inst = e.getAttributeInstance(a);
        if (inst == null) return;
        inst.removeModifier(id);
        if (v != 0) inst.addTemporaryModifier(new EntityAttributeModifier(id, v, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    // ═════════ 도우미 ═════════

    /** from 이 e 의 앞쪽(반원)에 있는가 */
    private static boolean front(LivingEntity e, Vec3d from) {
        Vec3d to = horizontal(from.subtract(e.getPos()));
        Vec3d face = horizontal(e.getRotationVector(0, e.getYaw()));
        return to.lengthSquared() < 1e-6 || to.dotProduct(face) > 0;
    }

    private static Vec3d away(LivingEntity atk, LivingEntity t) {
        Vec3d d = horizontal(t.getPos().subtract(atk.getPos()));
        return d.lengthSquared() < 1e-6 ? horizontal(atk.getRotationVec(1f)) : d;
    }

    private static Vec3d horizontal(Vec3d v) {
        Vec3d h = new Vec3d(v.x, 0, v.z);
        double l = h.length();
        return l < 1e-6 ? Vec3d.ZERO : h.multiply(1 / l);
    }

    static void setVel(LivingEntity e, Vec3d v) {
        e.setVelocity(v);
        e.velocityModified = true;
        if (e instanceof ServerPlayerEntity sp) {
            sp.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(sp));
            sp.velocityModified = false;
        }
    }

    private static void sound(Entity e, SoundEvent s, float vol, float pitch) {
        e.getWorld().playSound(null, e.getX(), e.getY(), e.getZ(), s, SoundCategory.PLAYERS, vol, pitch);
    }

    // ═════════ 허수아비 ═════════

    /** 콤보 연습용 허수아비. 움직이지 않고, 때리지 않고, 죽지 않는다 */
    public static void spawnDummy(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        ZombieEntity z = EntityType.ZOMBIE.create(w);
        if (z == null) return;
        Vec3d look = horizontal(p.getRotationVec(1f));
        Vec3d pos = p.getPos().add(look.multiply(3));
        z.refreshPositionAndAngles(pos.x, pos.y, pos.z, p.getYaw() + 180, 0);
        z.setPersistent();
        z.addCommandTag(DUMMY_TAG);
        z.setCustomName(Text.literal("허수아비").formatted(Formatting.YELLOW));
        z.setCustomNameVisible(true);
        z.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        EntityAttributeInstance hp = z.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (hp != null) hp.setBaseValue(200);
        EntityAttributeInstance sp = z.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (sp != null) sp.setBaseValue(0);
        EntityAttributeInstance fr = z.getAttributeInstance(EntityAttributes.GENERIC_FOLLOW_RANGE);
        if (fr != null) fr.setBaseValue(0);
        z.setHealth(200);
        w.spawnEntity(z);
    }
}
