package bbc.combat;

import bbc.core.Tasks;
import bbc.job.JobSkills;
import bbc.job.Stamina;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import bbc.net.ComboPayload;
import bbc.net.StunPayload;
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
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import org.joml.Vector3f;
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
    /** 지금 맞히는 게 평타인가 (튕김 창에는 기술만 들어감) */
    private static final ThreadLocal<Boolean> FROM_M1 = ThreadLocal.withInitial(() -> false);
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

    public static boolean channeling(LivingEntity e) { Fighter f = peek(e); return f != null && f.channel != null; }

    /** 복사본을 돈다 (도는 중에 hit() 이 새 Fighter 를 만들어도 안전하게) */
    static void forEachFighter(java.util.function.Consumer<Fighter> c) { new ArrayList<>(FIGHTERS.values()).forEach(c); }

    /** 기술·평타를 쓸 수 있는 상태인가 (경직·다운·가드 깨짐이 아님) */
    public static boolean canAct(LivingEntity e) {
        Fighter f = peek(e);
        return f == null || !f.stunned();
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
        long pl = f.parryLockUntil - now;
        if (pl > 0) t.append(Text.literal("패링 " + (pl / 20 + 1) + "s  ").formatted(Formatting.DARK_GRAY));
        long ev = f.evadeCdUntil - now;
        t.append(ev > 0 ? Text.literal("회피 " + (ev / 20 + 1) + "s  ").formatted(Formatting.DARK_GRAY)
                : Text.literal("회피 ✔  ").formatted(Formatting.GREEN));
        return t;
    }

    /** 공중 콤보 중인가 (기술이 공중판으로 바뀌는 기준) */
    public static boolean inAirCombo(ServerPlayerEntity p) {
        Fighter f = peek(p);
        return f != null && !p.isOnGround() && now < f.airComboUntil;
    }

    /**
     * 기술을 쓴 뒤 호출. 평타 사슬을 이어 주고(기술 → 평타 → 기술 연계), 공중이면 체공을 늘리고 공중 평타 사슬을 처음부터 다시 쓸 수 있게 한다.
     */
    public static void afterSkill(ServerPlayerEntity p, int recovery) {
        Fighter f = of(p);
        f.m1ReadyAt = Math.max(f.m1ReadyAt, now + recovery);
        f.recoveryUntil = f.m1ReadyAt;
        f.m1ChainUntil = f.m1ReadyAt + Tuning.CHAIN_RESET;
        // ABA처럼 '평타 4 → 기술 → 평타 4 → 기술'로 이어지게 기술 뒤엔 평타 사슬을 처음부터 (땅·공중 모두)
        f.m1Index = 0;
        if (!p.isOnGround() && now < f.airComboUntil + 10) f.airComboUntil = Math.max(f.airComboUntil, now + 30);
    }

    /** 기술용: 속도를 바로 넣음 (플레이어면 즉시 전송) */
    public static void move(LivingEntity e, Vec3d v) { setVel(e, v); }

    /** 전투 모드를 끌 때 가드 같은 입력 상태를 지움 */
    public static void releaseInputs(ServerPlayerEntity p) {
        Fighter f = peek(p);
        if (f != null) { f.guardHeld = false; f.buffered = 0; }
    }

    // ═════════ 평타 ═════════

    /** 평타 입력. up = 스페이스를 누른 채 (띄우기·래더) */
    public static void m1(LivingEntity p, boolean up) { // 플레이어 입력과 몬스터 AI 가 같이 쓴다
        Fighter a = of(p);
        if (a.stunned() || a.guardHeld || a.dashTicks > 0 || a.channel != null) return;
        if (now < a.m1ReadyAt) {
            if (a.m1ReadyAt - now <= Tuning.INPUT_BUFFER) { a.buffered = up ? 2 : 1; a.bufferedAt = now; }
            return;
        }
        doM1(p, a, up);
    }

    /**
     * 평타 한 사슬 = 5타 (땅·공중 섞어서 셈). ABA 규칙:
     *   1~4타: 땅이면 평타, 2~4타째에 스페이스를 누르고 있으면 상대와 같이 살짝 뜸, 공중이면 공중 평타
     *   5타째: 땅 + 스페이스 = 업 플링 / 땅 = 넉백 / 공중 + 스페이스 = 래더(다시 띄움) / 공중 = 다운 슬램
     */
    private static void doM1(LivingEntity p, Fighter a, boolean up) {
        a.buffered = 0;
        if (now > a.m1ChainUntil) a.m1Index = 0;
        boolean air = !p.isOnGround() && now < a.airComboUntil;
        int idx = a.m1Index;
        Weapon.M1Profile pr = Weapon.profile(p);   // 맨손·검·창·대검마다 사슬 길이·속도가 다름
        boolean weapon = pr != Weapon.FIST;
        if (idx < pr.hits() - 1) {
            a.m1Index++;
            if (air) {
                swing(p, a, weapon ? pr.motion() + (idx % 3 + 1) : "air_" + (idx % 3 + 1), pr.startup(), pr.recovery(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, () -> {
                    hitArea(p, Tuning.AIR_M1, false);
                    a.airComboUntil = now + 24;
                    setVel(p, new Vec3d(p.getVelocity().x * 0.3, 0.12, p.getVelocity().z * 0.3));
                });
            } else if (up && idx >= 1) {
                swing(p, a, "launch", pr.startup(), pr.recovery(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, () -> {
                    if (hitArea(p, Tuning.HOP, false) > 0) {
                        a.airComboUntil = now + 30;
                        setVel(p, new Vec3d(0, Tuning.HOP_VY, 0));
                    }
                });
            } else {
                track(p);
                swing(p, a, pr.motion() + (idx + 1), pr.startup(), pr.recovery(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP,
                        () -> hitArea(p, Tuning.M1, false));
            }
            return;
        }
        a.m1Index = 0;
        if (air && up) {
            swing(p, a, "launch", Tuning.M1_STARTUP, Tuning.M1_RECOVERY + 2, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, () -> {
                if (hitArea(p, Tuning.LADDER, true) > 0) {
                    a.airComboUntil = now + 40;
                    setVel(p, new Vec3d(0, Tuning.LADDER_VY, 0));
                } else { // 래더를 이미 썼으면 슬램으로
                    hitArea(p, Tuning.SLAM, false);
                    a.airComboUntil = 0;
                }
            });
        } else if (air) {
            swing(p, a, weapon ? pr.motion() + "slam" : "slam", Tuning.SLAM_STARTUP, Tuning.SLAM_RECOVERY, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, () -> {
                boolean slammed = hitArea(p, Tuning.SLAM, false) > 0;
                if (slammed) { // 내리꽂는 줄기
                    ServerWorld w = ((ServerWorld) p.getWorld());
                    Vec3d c = p.getPos().add(horizontal(p.getRotationVec(1f)).multiply(1.6));
                    for (int i = 0; i < 10; i++) w.spawnParticles(ParticleTypes.CLOUD, c.x, c.y + 1.5 - i * 0.3, c.z, 0, 0, -1, 0, 0.6);
                }
                a.airComboUntil = 0;
                if (slammed) { // ABA: 상대가 먼저 꽂히고 나는 살짝 떴다가 늦게 착지 + 평타 잠깐 막힘
                    setVel(p, new Vec3d(0, Tuning.SLAM_HANG_VY, 0));
                    if (now < a.freezeUntil) a.resumeVel = new Vec3d(0, Tuning.SLAM_HANG_VY, 0);   // 히트스톱 뒤에 이 속도로
                    a.m1ReadyAt = Math.max(a.m1ReadyAt, now + Tuning.SLAM_M1_LOCK);
                } else setVel(p, new Vec3d(0, -0.6, 0));
            });
        } else if (up) {
            swing(p, a, "launch", Tuning.FLING_STARTUP, Tuning.FLING_RECOVERY, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, () -> {
                if (hitArea(p, Tuning.UP_FLING, false) > 0) {
                    ServerWorld w = ((ServerWorld) p.getWorld());
                    Vec3d c = p.getPos().add(horizontal(p.getRotationVec(1f)).multiply(1.5));
                    ring(w, c.add(0, 0.2, 0), 1.6, 20);
                    w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y + 1.5, c.z, 16, 0.15, 1.5, 0.15, 0.05);
                }
            });
        } else {
            track(p);
            swing(p, a, pr.motion() + pr.hits(), pr.finishStartup(), Tuning.FINISH_NEXT - Tuning.FINISH_STARTUP - 1, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK,
                    () -> { if (hitArea(p, Tuning.FINISH, false) > 0) ring(((ServerWorld) p.getWorld()), p.getPos().add(horizontal(p.getRotationVec(1f)).multiply(1.5)).add(0, 1, 0), 1.3, 18); });
        }
    }

    /** 평타마다 앞으로 붙기: 앞에 상대가 있으면 그쪽으로 조금 미끄러짐 */
    private static void track(LivingEntity p) {
        if (!p.isOnGround()) return;
        List<LivingEntity> ts = sphere(p, Tuning.M1_REACH + 1.2, Tuning.M1_RADIUS + 0.6, 1);
        if (ts.isEmpty()) return;
        Vec3d to = ts.get(0).getPos().subtract(p.getPos());
        double d = Math.sqrt(to.x * to.x + to.z * to.z);
        if (d < 1.3) return;
        Vec3d dir = horizontal(to);
        double sp = Math.min(Tuning.M1_TRACK, (d - 1.2) * 0.3);
        setVel(p, new Vec3d(dir.x * sp, p.getVelocity().y, dir.z * sp));
    }

    /** 휘두르기 시작 → 선딜 뒤 판정. 그사이 맞아서 경직되면 취소된다 */
    private static void swing(LivingEntity p, Fighter a, String motion, int startup, int recovery, SoundEvent sfx, Runnable resolve) {
        int tok = ++a.token;
        Motion.play(p, motion);
        a.m1ReadyAt = now + startup + 1 + recovery;
        a.recoveryUntil = a.m1ReadyAt;
        a.m1ChainUntil = a.m1ReadyAt + Tuning.CHAIN_RESET;
        p.swingHand(Hand.MAIN_HAND, true);
        sound(p, sfx, 0.5f, 1.3f);
        Tasks.later(startup, () -> {
            if (a.token != tok || a.stunned() || !p.isAlive() || p.isRemoved()) return;
            Weapon.trail(p, motion);   // 무기 모션이면 칼자국·찌르기 줄기
            resolve.run();
        });
    }

    /**
     * 기술 한 방 (몬스터 AI·직업 기술 공용): motion 을 틀고 startup 틱 뒤 앞쪽 range 안을 h 로 때림. 평타가 아니라서 지상 연계 규칙이 걸린다.
     */
    public static void special(LivingEntity p, String motion, int startup, int recovery, Hit h, double range) {
        Fighter a = of(p);
        if (a.stunned()) return;
        swing(p, a, motion, startup, recovery, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, () -> {
            for (LivingEntity t : targets(p, range, 55, 2)) hit(p, t, h);
        });
        a.m1Index = 0; // 기술 뒤엔 평타 사슬 처음부터
    }

    /** 평타 판정 안의 대상들을 때림. 맞힌 수를 돌려줌 */
    private static int hitArea(LivingEntity p, Hit h, boolean ladder) {
        int n = 0;
        Weapon.M1Profile pr = Weapon.profile(p);
        if (pr.dmgMul() != 1f) h = h.withDamage(h.damage() * pr.dmgMul());
        FROM_M1.set(true);
        try {
            for (LivingEntity t : sphere(p, Tuning.M1_REACH + pr.reachAdd(), Tuning.M1_RADIUS, Tuning.M1_MAX_TARGETS)) {
                if (ladder) {
                    Fighter v = peek(t);
                    if (v == null || v.ladderUsed || v.state != Fighter.State.AIR) continue;
                    v.ladderUsed = true;
                }
                if (hit(p, t, h) != Result.MISS) n++;
            }
        } finally { FROM_M1.set(false); }
        return n;
    }

    /** 평타 판정: 내 몸 중심에서 바라보는 쪽으로 reach 만큼 앞에 둔 구 (ABA 히트박스와 같은 방식). 위아래는 반만 따라감 */
    private static List<LivingEntity> sphere(LivingEntity a, double reach, double radius, int max) {
        Vec3d look = a.getRotationVec(1f);
        Vec3d c = a.getBoundingBox().getCenter().add(look.x * reach, look.y * reach * 0.5, look.z * reach);
        Box box = new Box(c, c).expand(radius + 0.5);
        List<LivingEntity> out = new ArrayList<>();
        for (Entity en : a.getWorld().getOtherEntities(a, box, x -> x instanceof LivingEntity && x.isAlive() && !(x instanceof ArmorStandEntity))) {
            LivingEntity t = (LivingEntity) en;
            if (closest(t.getBoundingBox(), c).squaredDistanceTo(c) <= radius * radius) out.add(t);
        }
        out.sort(Comparator.comparingDouble(t -> t.squaredDistanceTo(a)));
        return out.size() > max ? out.subList(0, max) : out;
    }

    /** 기술용: 앞쪽 부채꼴 안의 대상 (가까운 순) */
    public static List<LivingEntity> targets(LivingEntity a, double range, double halfAngleDeg, int max) {
        List<LivingEntity> out = new ArrayList<>(cone(a, range, halfAngleDeg, 99));
        // 부채꼴이 놓치는 바로 앞·옆도 (평타와 같은 구)
        for (LivingEntity t : sphere(a, range * 0.45, Math.max(1.6, range * 0.6), 99)) if (!out.contains(t)) out.add(t);
        // 공중에선 시선이 엇나가기 쉬우니 내 몸 주위도
        if (!a.isOnGround()) for (LivingEntity t : sphere(a, 0, range * 0.75, 99)) if (!out.contains(t)) out.add(t);
        out.sort(Comparator.comparingDouble(t -> t.squaredDistanceTo(a)));
        return out.size() > max ? out.subList(0, max) : out;
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
        // 쓰러진 상대: 슬램 착지 뒤 튕김 창 안이면 무엇이든 맞음 (기술 = 다시 띄움, 평타 = 일으켜 세움)
        boolean downOpen = v.state == Fighter.State.DOWN && now < v.bounceUntil;
        boolean bounce = downOpen && !FROM_M1.get();
        if (v.state == Fighter.State.DOWN && !h.downHit() && !downOpen) return Result.MISS;

        // 가드·패링 (앞에서 온 공격만, 평상 상태이고 후딜이 아닐 때)
        if (v.guardHeld && v.state == Fighter.State.IDLE && now >= v.recoveryUntil && front(t, atk.getPos())) {
            if (now <= v.parryUntil && parryable(h)) { parry(v, atk); return Result.PARRIED; }
            v.guardUsedAt = now;
            v.guardGauge -= h.guardDmg();
            if (h.guardBreak() || v.guardGauge <= 0) {
                breakGuard(v);
                ServerWorld bw = (ServerWorld) t.getWorld();
                Fx.word(bw, t.getEyePos().add(0, 1.0, 0), Fx.Sfx.BAKIN, 1.1f);
                Fx.burst(bw, t.getEyePos(), t.getPos().subtract(atk.getPos()).normalize(), 1.3f);
                Fx.lines(t, 0.9f); Fx.lines(atk, 0.6f);
                if (!deal(atk, t, h.damage())) return Result.MISS;
                setVel(t, away(atk, t).multiply(0.3).add(0, t.getVelocity().y, 0));
                return Result.HIT;
            }
            if (!deal(atk, t, h.damage() * Tuning.GUARD_DAMAGE_TAKEN)) return Result.MISS;
            setVel(t, away(atk, t).multiply(0.15).add(0, t.getVelocity().y, 0));
            sound(t, SoundEvents.ITEM_SHIELD_BLOCK, 0.8f, 1.1f);
            return Result.BLOCKED;
        }

        // 공중에 떠 있는 상대에게 지상 경직 기술이 맞으면 공중 경직으로 (기술로 공중 콤보 연계)
        boolean airborne = v.state == Fighter.State.AIR || (v.stunned() && !t.isOnGround() && v.state != Fighter.State.DOWN);
        if (h.kind() == Hit.Kind.HIT && airborne) h = new Hit(h.damage(), Hit.Kind.AIR, h.stun(), h.push() * 0.6, 0.12, h.guardDmg(), h.guardBreak(), h.downHit());
        // 튕김 중인 상대에게 들어간 기술은 다시 띄움 (익스텐더)
        if (bounce && (h.kind() == Hit.Kind.HIT || h.kind() == Hit.Kind.DOWN))
            h = new Hit(h.damage(), Hit.Kind.AIR, Math.max(h.stun(), 16), h.push() * 0.5, Math.max(h.lift(), 0.45), h.guardDmg(), h.guardBreak(), h.downHit());
        if (downOpen) v.bounceUntil = 0;

        // 콤보 보정
        Fighter a = of(atk);
        if (v.comboHits == 0) { v.comboDamage = 0; v.ladderUsed = false; }
        v.comboHits++;
        float scale = v.comboHits <= Tuning.SCALE_FREE_HITS ? 1f
                : Math.max(Tuning.SCALE_MIN, 1f - Tuning.SCALE_PER_HIT * (v.comboHits - Tuning.SCALE_FREE_HITS));
        int stun = h.stun();
        if (h.kind() == Hit.Kind.HIT || h.kind() == Hit.Kind.AIR)
            stun = Math.max(Tuning.STUN_MIN, stun - (v.comboHits - 1) / Tuning.STUN_DECAY_EVERY);
        // 땅에서 기술(평타 말고)로 맞힌 경직은 기술 후딜 + 평타 선딜보다 길게 → 기술 뒤 평타가 이어짐 (지상 익스텐더)
        boolean groundSkill = !FROM_M1.get() && h.kind() == Hit.Kind.HIT;
        if (groundSkill) stun = Math.max(stun, Tuning.SKILL_MIN_STUN);
        float dmg = h.damage() * scale;
        if (FROM_M1.get() && now < a.awakenUntil) dmg *= Awaken.M1_BONUS; // 각성 중 평타 강화

        if (!deal(atk, t, dmg)) { v.comboHits--; return Result.MISS; }
        Awaken.onHit(atk, t, dmg);
        v.comboDamage += dmg;
        v.lastHitAt = now;
        v.lastAttacker = atk.getUuid();
        a.lastTarget = t.getUuid();
        a.lastTargetAt = now;
        v.knocked = h.kind() == Hit.Kind.KNOCK;
        v.freshHit = true;
        v.slammed = h.kind() == Hit.Kind.DOWN;

        // 속도 (바닐라 넉백을 덮어씀)
        Vec3d dir = away(atk, t);
        Vec3d vel = switch (h.kind()) {
            case HIT -> new Vec3d(dir.x * h.push(), Math.min(t.getVelocity().y, 0.0), dir.z * h.push());
            case AIR, KNOCK, DOWN -> new Vec3d(dir.x * h.push(), h.lift(), dir.z * h.push());
        };
        boolean atkInAir = !atk.isOnGround();
        if (h.kind() == Hit.Kind.AIR && atkInAir && h.lift() < 0.3) {
            // 공중에서 때릴 땐 상대를 내 앞 1.7칸에 붙잡아 둔다 (간격이 벌어져 콤보가 끊기지 않게)
            Vec3d want = atk.getPos().add(horizontal(atk.getRotationVec(1f)).multiply(2.0));
            Vec3d pull = want.subtract(t.getPos()).multiply(0.35);
            vel = new Vec3d(clamp(pull.x, 0.6), h.lift(), clamp(pull.z, 0.6));
            setVel(atk, new Vec3d(atk.getVelocity().x * 0.3, Math.max(atk.getVelocity().y, h.lift()), atk.getVelocity().z * 0.3));
        }
        if (groundSkill && h.push() < 0.4) {
            // 기술로 맞힌 상대를 내 앞 1.9칸에 붙잡아 둔다 (멀어져서 다음 평타가 헛치지 않게)
            Vec3d want = atk.getPos().add(horizontal(atk.getRotationVec(1f)).multiply(1.9));
            Vec3d pull = want.subtract(t.getPos()).multiply(0.4);
            vel = new Vec3d(clamp(pull.x, 0.6), Math.min(t.getVelocity().y, 0.0), clamp(pull.z, 0.6));
        }
        setVel(t, vel);

        // 띄우기 정렬: 크게 띄운 공격(올려치기·뛰어 띄우기·래더·튕김 띄우기)은 잠깐 상대를 내 앞에 묶음
        if (h.kind() == Hit.Kind.AIR && h.lift() >= Tuning.TETHER_LIFT) { v.tetherUntil = now + Tuning.TETHER_TICKS; v.tetherBy = atk; }
        else if (h.kind() == Hit.Kind.DOWN) v.tetherUntil = 0;   // 내리찍으면 풀림

        // 상태
        switch (h.kind()) {
            case HIT -> setState(v, Fighter.State.HITSTUN, stun);
            case AIR -> {
                setState(v, Fighter.State.AIR, stun);
                // 띄운 쪽은 점프해서 바로 공중 평타로 이을 수 있음
                a.airComboUntil = Math.max(a.airComboUntil, now + 40);
            }
            case DOWN -> setState(v, Fighter.State.DOWN, stun + 8); // 떨어지는 시간 포함
            case KNOCK -> {
                // 날아가는 동안은 공중 경직 (대시로 쫓아가 잡을 수 있음), 떨어지면 다운
                setState(v, Fighter.State.AIR, stun + 20);
                a.airComboUntil = Math.max(a.airComboUntil, now + 40);
            }
        }
        impact(atk, t, a, v, h);
        Motion.play(t, switch (h.kind()) { case HIT -> v.comboHits % 2 == 0 ? "hit_2" : "hit"; case AIR -> "hit_air"; default -> "knock"; });

        // 연출
        ServerWorld w = (ServerWorld) t.getWorld();
        Vec3d c = t.getBoundingBox().getCenter();
        // 맞은 자리에 번쩍임 (상대 몸통에서 때린 쪽으로 조금)
        Vec3d spark = c.add(horizontal(atk.getPos().subtract(t.getPos())).multiply(t.getWidth() * 0.5));
        w.spawnParticles(new DustParticleEffect(new Vector3f(1f, 0.95f, 0.8f), 0.9f), spark.x, spark.y, spark.z, 6, 0.12, 0.12, 0.12, 0);
        w.spawnParticles(ParticleTypes.END_ROD, spark.x, spark.y, spark.z, 3, 0.02, 0.02, 0.02, 0.12);
        ParticleEffect fx = h.kind() == Hit.Kind.HIT ? ParticleTypes.CRIT : ParticleTypes.SWEEP_ATTACK;
        w.spawnParticles(fx, c.x, c.y, c.z, h.kind() == Hit.Kind.HIT ? 6 : 2, 0.25, 0.25, 0.25, 0.1);
        if (h.kind() == Hit.Kind.DOWN) sound(t, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 0.7f);
        sound(t, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 0.7f, 0.9f + Math.min(v.comboHits, 20) * 0.03f);
        damageNumber(w, t, dmg);

        if (atk instanceof ServerPlayerEntity sp && ServerPlayNetworking.canSend(sp, ComboPayload.ID))
            ServerPlayNetworking.send(sp, new ComboPayload(v.comboHits, v.comboDamage));
        return Result.HIT;
    }

    private static boolean deal(LivingEntity atk, LivingEntity t, float dmg) {
        DamageSource src = atk instanceof PlayerEntity pl ? atk.getDamageSources().playerAttack(pl) : atk.getDamageSources().mobAttack(atk);
        t.timeUntilRegen = 0;
        OURS.set(true);
        try { return t.damage(src, dmg); }
        finally { OURS.set(false); }
    }

    /**
     * 큰 타격 연출 (파티클 말고): 히트스톱·화면 흔들림·충격파·타격 글자.
     *  넉백(마무리) = 3틱 멈춤 + 쾅! / 다운 = 2틱 멈춤 / 띄우기 = 살짝 흔들림 / 평타 = 맞은 플레이어만 아주 약하게
     */
    private static void impact(LivingEntity atk, LivingEntity t, Fighter a, Fighter v, Hit h) {
        ServerWorld w = (ServerWorld) t.getWorld();
        Vec3d c = t.getBoundingBox().getCenter();
        switch (h.kind()) {
            case KNOCK -> {
                hitStop(a, v, 3);
                Fx.shake(atk, 0.45f); Fx.shake(t, 0.8f);
                Fx.wave(w, c, 0.4, 2.2, Fx.WHITE, 6);
                Fx.mangaHit(w, atk, t, bladed(atk) ? Fx.BLADE : Fx.BLUNT, 1.0f);
            }
            case DOWN -> { hitStop(a, v, 2); Fx.shake(atk, 0.35f); Fx.shake(t, 0.6f); Fx.burst(w, c, new Vec3d(0, -1, 0), 1.2f); Fx.impactFlash(w, c, 0.8f); Fx.lines(atk, 0.5f); Fx.streak(t, 5); }
            case AIR -> { if (h.lift() >= 0.7) { Fx.shake(atk, 0.25f); Fx.shake(t, 0.4f); Fx.burst(w, c, new Vec3d(0, 1, 0), 0.9f); Fx.impactFlash(w, c, 0.6f); Fx.rings(w, c, new Vec3d(0, 1, 0), 2.2f, 1); Fx.streak(t, 5); } else Fx.shake(t, 0.12f); }
            case HIT -> { Fx.shake(t, h.damage() >= 2f ? 0.35f : 0.12f); Fx.impactFlash(w, c, h.damage() >= 2f ? 0.45f : 0.25f); }
        }
    }

    /** 가드 자세는 든 무기마다 다름 (맨손 = 하이 가드, 검 = 칼을 눕혀 막기, 대검 = 날을 세워 방패처럼, 창 = 자루를 가로로) */
    static String guardMotion(LivingEntity e) {
        Weapon.Type t = Weapon.current(e);
        if (t == null) return "guard";
        return switch (t) { case SWORD -> "guard_sword"; case SPEAR -> "guard_spear"; case GREATSWORD -> "guard_gs"; };
    }

    /**
     * 띄우기 정렬 한 틱: 상대 가로 속도를 '띄운 사람 앞 TETHER_DIST 칸' 쪽으로 덮어씀 (세로는 그대로 → 포물선은 유지).
     * 그 사이 들어온 넉백의 가로 성분도 여기서 덮이므로, 넉백 기술이 띄운 직후에 맞으면 상대가 내 앞에 남는다.
     * 띄운 사람이 점프 중이면 상대 높이 근처까지 따라 올라가게 위로 밀어 줌 (공중 콤보 진입)
     */
    private static void tether(LivingEntity e, Fighter f) {
        LivingEntity by = f.tetherBy;
        if (by == null || !by.isAlive() || by.getWorld() != e.getWorld() || by.squaredDistanceTo(e) > 100) { f.tetherUntil = 0; return; }
        Vec3d want = by.getPos().add(horizontal(by.getRotationVec(1f)).multiply(Tuning.TETHER_DIST));
        Vec3d d = want.subtract(e.getPos()).multiply(Tuning.TETHER_PULL);
        Vec3d v = e.getVelocity();
        setVel(e, new Vec3d(clamp(d.x, Tuning.TETHER_MAX), v.y, clamp(d.z, Tuning.TETHER_MAX)));
        Vec3d bv = by.getVelocity();
        double dh = e.getY() - 0.4 - by.getY();
        if (!by.isOnGround() && bv.y > 0.15 && dh > 0.8) // 따라 점프: 남은 높이를 올라갈 만큼 (중력 0.08/틱)
            setVel(by, new Vec3d(bv.x, Math.max(bv.y, Math.min(1.25, Math.sqrt(2 * 0.08 * dh) + 0.08)), bv.z));
    }

    /** 칼·창·도끼를 든 공격인지 (효과음을 ザン 계열로) */
    private static boolean bladed(LivingEntity e) {
        var it = e.getMainHandStack().getItem();
        return it instanceof net.minecraft.item.SwordItem || it instanceof net.minecraft.item.AxeItem || it instanceof net.minecraft.item.TridentItem;
    }

    /** 히트스톱: 둘 다 ticks 동안 멈춤. 그만큼 후딜·경직을 늘려서 손해 보는 쪽이 없게 */
    static void hitStop(Fighter a, Fighter v, int ticks) {
        // 멈춘 동안 속도를 0 으로 붙잡으므로, 멈추기 직전 속도(넉백 포함)를 기억했다가 풀릴 때 돌려줌
        if (now >= a.freezeUntil) a.resumeVel = a.e.getVelocity();
        if (now >= v.freezeUntil) v.resumeVel = v.e.getVelocity();
        a.freezeUntil = now + ticks; v.freezeUntil = now + ticks;
        a.m1ReadyAt += ticks; a.recoveryUntil += ticks;
        if (v.stunned()) v.stateUntil += ticks;
    }

    /** 패링 불가는 공격마다 따로 붙인다 (Hit.noParry, 보스 패턴 등) */
    private static boolean parryable(Hit h) {
        return !h.unparriable();
    }

    private static void parry(Fighter v, LivingEntity atk) {
        v.parryUntil = 0;
        v.parryLockUntil = now + Tuning.PARRY_SUCCESS_LOCK; // 연달아 패링 못 하게
        Fighter a = of(atk);
        setState(a, Fighter.State.HITSTUN, Tuning.PARRY_STUN);
        ServerWorld w = (ServerWorld) v.e.getWorld();
        Vec3d c = v.e.getEyePos();
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 14, 0.3, 0.3, 0.3, 0.3);
        sound(v.e, SoundEvents.ITEM_SHIELD_BLOCK, 1f, 1.8f);
        Motion.play(v.e, "parry");
        Vec3d mid = c.lerp(atk.getEyePos(), 0.5);
        Fx.word(w, mid.add(0, 0.9, 0), Fx.Sfx.GAGIN, 1.1f);
        Fx.burst(w, mid, null, 1.0f);
        Fx.impactFlash(w, mid, 0.8f);
        Fx.rings(w, mid, atk.getPos().subtract(v.e.getPos()), 2.2f, 1);
        Fx.lines(v.e, 0.8f); Fx.lines(atk, 0.6f);
        Motion.play(atk, "hit");
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
        if (s == Fighter.State.IDLE) { f.stateEndedAt = now; f.knocked = false; f.slammed = false; }
        else { f.token++; f.dashTicks = 0; f.buffered = 0; }
        syncStun(f);
    }

    /** 경직이 바뀌면 주변 클라이언트에 알림 → 경직 중인 몸을 붉게 칠함 (ABA 경직 더미처럼) */
    private static void syncStun(Fighter f) {
        int left = f.stunned() ? (int) Math.max(1, f.stateUntil - now) : 0;
        if (left == 0 && f.sentStun == 0) { f.freshHit = false; return; }
        f.sentStun = left;
        StunPayload pkt = new StunPayload(f.e.getId(), left, f.state.ordinal(), f.freshHit);
        f.freshHit = false;
        for (ServerPlayerEntity o : PlayerLookup.tracking(f.e))
            if (ServerPlayNetworking.canSend(o, StunPayload.ID)) ServerPlayNetworking.send(o, pkt);
        if (f.e instanceof ServerPlayerEntity sp && ServerPlayNetworking.canSend(sp, StunPayload.ID)) ServerPlayNetworking.send(sp, pkt);
    }

    // ═════════ 가드·대시·회피기 ═════════

    public static void guard(LivingEntity p, boolean down) {
        Fighter f = of(p);
        if (!down) {
            if (f.guardHeld && !f.stunned()) Motion.play(p, "stop");
            f.guardHeld = false;
            return;
        }
        if (f.guardHeld) return;
        f.guardHeld = true;
        if (!f.stunned()) Motion.play(p, guardMotion(p));
        if (now >= f.parryLockUntil) {
            f.parryUntil = now + Tuning.PARRY_WINDOW;
            f.parryLockUntil = now + Tuning.PARRY_WINDOW + Tuning.PARRY_FAIL_LOCK;
        }
    }

    /** dir: 0 앞 1 뒤 2 왼쪽 3 오른쪽. 경직 중이면 회피기 */
    public static void dash(LivingEntity p, int dir) {
        Fighter f = of(p);
        if (f.state == Fighter.State.HITSTUN || f.state == Fighter.State.AIR || f.state == Fighter.State.GUARD_BROKEN) { evade(p, f); return; }
        if (f.state == Fighter.State.DOWN) return;
        if (now < f.dashCdUntil) return;
        boolean cancel = now < f.recoveryUntil;
        double cost = cancel ? Tuning.DASH_CANCEL_COST : Tuning.DASH_COST;
        if (p instanceof ServerPlayerEntity sp && !Stamina.spend(sp, cost)) { JobSkills.bar(sp, "스태미나 부족", Formatting.RED); return; }
        if (cancel) { f.token++; f.m1ReadyAt = now; f.recoveryUntil = now; }
        if (dir == 0 && chase(p, f)) return;
        double yaw = Math.toRadians(p.getYaw());
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3d right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d d = switch (dir) { case 1 -> fwd.negate(); case 2 -> right.negate(); case 3 -> right; default -> fwd; };
        f.dashVel = d.multiply(Tuning.DASH_SPEED);
        f.dashChase = false;
        f.dashTicks = Tuning.DASH_TICKS;
        f.invulnUntil = Math.max(f.invulnUntil, now + Tuning.DASH_INVULN);
        f.dashCdUntil = now + Tuning.DASH_COOLDOWN;
        Motion.play(p, switch (dir) { case 1 -> "dash_b"; case 2 -> "dash_l"; case 3 -> "dash_r"; default -> "dash_f"; });
        sound(p, SoundEvents.ENTITY_PHANTOM_FLAP, 0.6f, 1.6f);
        ((ServerWorld) p.getWorld()).spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.2, p.getZ(), 6, 0.3, 0.05, 0.3, 0.02);
    }

    /**
     * 추격 대시: 방금 날리거나 띄운 상대가 앞쪽에 있으면 그 앞까지 날아가 붙는다 (ABA: 넉백 → Q로 쫓아가 공중에서 다시 잡기).
     */
    private static boolean chase(LivingEntity p, Fighter f) {
        if (f.lastTarget == null || now - f.lastTargetAt > Tuning.CHASE_MEMORY) return false;
        if (!(((ServerWorld) p.getWorld()).getEntity(f.lastTarget) instanceof LivingEntity t) || !t.isAlive()) return false;
        Fighter v = peek(t);
        if (v == null || (v.state != Fighter.State.AIR && v.state != Fighter.State.DOWN)) return false;
        Vec3d to = t.getPos().subtract(p.getPos());
        if (to.length() > Tuning.CHASE_RANGE || to.length() < 2) return false;
        if (horizontal(to).dotProduct(horizontal(p.getRotationVec(1f))) < 0.3) return false;
        Vec3d dest = t.getPos().subtract(horizontal(to).multiply(1.4));
        f.dashVel = dest.subtract(p.getPos()).multiply(1.0 / Tuning.DASH_TICKS);
        f.dashChase = true;
        f.dashTicks = Tuning.DASH_TICKS;
        f.invulnUntil = Math.max(f.invulnUntil, now + Tuning.DASH_INVULN);
        f.dashCdUntil = now + Tuning.DASH_COOLDOWN;
        if (v.state == Fighter.State.AIR) f.airComboUntil = Math.max(f.airComboUntil, now + Tuning.DASH_TICKS + 24);
        Motion.play(p, "dash_f");
        sound(p, SoundEvents.ENTITY_PHANTOM_FLAP, 0.7f, 1.9f);
        ((ServerWorld) p.getWorld()).spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 0.5, p.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
        return true;
    }

    private static void evade(LivingEntity p, Fighter f) {
        long left = f.evadeCdUntil - now;
        if (left > 0) { if (p instanceof ServerPlayerEntity sp) JobSkills.bar(sp, "회피기 재사용까지 " + (left / 20 + 1) + "초", Formatting.GRAY); return; }
        setState(f, Fighter.State.IDLE, 0);
        f.invulnUntil = now + Tuning.EVADE_INVULN;
        f.evadeCdUntil = now + Tuning.EVADE_COOLDOWN;
        Entity from = f.lastAttacker == null ? null : ((ServerWorld) p.getWorld()).getEntity(f.lastAttacker);
        Vec3d away = from != null ? horizontal(p.getPos().subtract(from.getPos())) : p.getRotationVec(1f).negate().multiply(1, 0, 1).normalize();
        setVel(p, away.multiply(Tuning.EVADE_PUSH).add(0, 0.3, 0));
        endCombo(f);
        Motion.play(p, "evade");
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
                    if (f.knocked) { // 넉백으로 날아가다 떨어짐 → 다운
                        f.knocked = false;
                        f.state = Fighter.State.DOWN;
                        f.stateUntil = now + Tuning.KNOCK_DOWN;
                        Motion.play(e, "knock");
                    } else { // 공중 경직 중 착지 → 남은 경직은 짧게
                        f.state = Fighter.State.HITSTUN;
                        f.stateUntil = Math.min(f.stateUntil, now + 8);
                    }
                    syncStun(f);
                }
                if (f.state == Fighter.State.DOWN && f.slammed && e.isOnGround() && now - f.lastHitAt >= 2) {
                    // 다운 슬램 착지 → 충격파 + 튕김. 튕기는 동안 기술이 들어감
                    f.slammed = false;
                    f.bounceUntil = now + Tuning.BOUNCE_WINDOW;
                    setVel(e, new Vec3d(e.getVelocity().x * 0.2, Tuning.BOUNCE_VY, e.getVelocity().z * 0.2));
                    ServerWorld w = (ServerWorld) e.getWorld();
                    Vec3d at = e.getPos().add(0, 0.15, 0);
                    ring(w, at, 1.4, 20);
                    ring(w, at, 2.8, 32);
                    Tasks.later(2, () -> ring(w, at, 4.0, 40));
                    var ground = w.getBlockState(e.getBlockPos().down());
                    if (!ground.isAir()) w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), e.getX(), e.getY() + 0.1, e.getZ(), 40, 1.0, 0.1, 1.0, 0.25);
                    w.spawnParticles(ParticleTypes.EXPLOSION, e.getX(), e.getY(), e.getZ(), 1, 0, 0, 0, 0);
                    sound(e, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.5f, 1.4f);
                    sound(e, SoundEvents.BLOCK_ANVIL_LAND, 0.35f, 1.6f);
                    Fx.wave(w, e.getPos().add(0, 0.05, 0), 0.6, 4.6, Fx.WHITE, 8);
                    Fx.word(w, e.getPos().add(0, 2.0, 0), Fx.SLAM, 1.3f);
                    Fx.debris(w, e.getPos().add(0, 0.1, 0), 9, 1.8);
                    Fx.rings(w, e.getPos().add(0, 0.15, 0), new Vec3d(0, 1, 0), 4.5f, 2);
                    Fx.smoke(w, e.getPos(), 22, 1.6);
                    Fx.lines(e, 0.8f);
                    Fx.shakeAround(w, e.getPos(), 10, 0.9f);
                }
                if (now >= f.stateUntil) {
                    Fighter.State was = f.state;
                    setState(f, Fighter.State.IDLE, 0);
                    if (was == Fighter.State.DOWN) { f.invulnUntil = now + Tuning.WAKEUP_INVULN; Motion.play(e, "getup"); }
                    else if (f.guardHeld) Motion.play(e, guardMotion(e));
                    if (was == Fighter.State.GUARD_BROKEN) f.guardGauge = Tuning.GUARD_MAX;
                }
            }

            // 콤보 끝
            if (f.state == Fighter.State.IDLE && f.comboHits > 0
                    && now - Math.max(f.stateEndedAt, f.lastHitAt) > Tuning.COMBO_END_GAP) endCombo(f);

            // 가드 게이지 회복
            if (!f.guardHeld && f.guardGauge < Tuning.GUARD_MAX && now - f.guardUsedAt > Tuning.GUARD_REGEN_DELAY)
                f.guardGauge = Math.min(Tuning.GUARD_MAX, f.guardGauge + Tuning.GUARD_REGEN_PER_TICK);

            // 히트스톱: 멈춘 동안 제자리
            if (now < f.tetherUntil) tether(e, f);
            if (now < f.freezeUntil) setVel(e, Vec3d.ZERO);
            else if (f.resumeVel != null) { setVel(e, f.resumeVel); f.resumeVel = null; }

            // 대시
            if (f.dashTicks > 0) {
                f.dashTicks--;
                setVel(e, f.dashChase ? f.dashVel : new Vec3d(f.dashVel.x, Math.min(e.getVelocity().y, 0.1), f.dashVel.z));
                if (f.dashTicks == 0 && f.dashChase) setVel(e, new Vec3d(0, Math.max(0.05, Math.min(f.dashVel.y, 0.1)), 0));
            }

            // 미뤄둔 평타
            if (f.buffered != 0 && e instanceof ServerPlayerEntity sp && now >= f.m1ReadyAt && !f.stunned()) {
                if (now - f.bufferedAt <= Tuning.INPUT_BUFFER + 2) doM1(sp, f, f.buffered == 2);
                else f.buffered = 0;
            }

            // 허수아비: 3초 안 맞으면 체력 회복
            if (e.getCommandTags().contains(DUMMY_TAG)) {
                if (now - f.lastHitAt > 60 && e.getHealth() < e.getMaxHealth()) e.setHealth(e.getMaxHealth());
                dummyName(f);
            }

            applyAttrs(f);

            // 오래 아무 일 없는 몬스터는 정리
            if (!(e instanceof ServerPlayerEntity) && f.state == Fighter.State.IDLE && f.comboHits == 0
                    && now - Math.max(f.touchedAt, f.lastHitAt) > 200) { clearAttrs(f); it.remove(); }
        }
    }

    /** 허수아비 이름표: 경직 중엔 빨간 글씨로 남은 틱, 아니면 초록 '대기' + 최근 콤보 */
    private static void dummyName(Fighter f) {
        MutableText name = f.stunned()
                ? Text.literal("경직 " + (f.stateUntil - now)).formatted(Formatting.RED, Formatting.BOLD)
                : Text.literal("대기").formatted(Formatting.GREEN, Formatting.BOLD);
        if (!f.stunned() && f.dummyLast != null) name.append(Text.literal(" · " + f.dummyLast).formatted(Formatting.YELLOW));
        f.e.setCustomName(name);
    }

    private static void endCombo(Fighter f) {
        if (f.e.getCommandTags().contains(DUMMY_TAG) && f.comboHits > 0)
            f.dummyLast = "최근 " + f.comboHits + "타";
        f.comboHits = 0;
        f.comboDamage = 0;
        f.ladderUsed = false;
    }

    // ═════════ 바닐라 피해 처리 ═════════

    private static boolean allowDamage(LivingEntity e, DamageSource src, float amount) {
        if (OURS.get()) return true;
        // 전투 중 낙하 피해 없음 (띄우기·슬램·추격 대시로 높이 오르내리니까)
        if (src.isIn(DamageTypeTags.IS_FALL)) {
            if (e instanceof ServerPlayerEntity sp && CombatMode.active(sp)) return false;
            Fighter ff = peek(e);
            if (ff != null && now - Math.max(ff.lastHitAt, ff.touchedAt) < 200) return false;
        }
        Entity atk = src.getAttacker();
        if (atk instanceof LivingEntity la) {
            Fighter fa = peek(la);
            if (fa != null && fa.stunned()) return false;                                  // 경직 중엔 못 때림
            if (la.getCommandTags().contains(DUMMY_TAG)) return false;                    // 허수아비는 안 때림
            if (MobAI.tier(la) != null && src.getSource() == la) return false;            // 전투 AI 몹은 바닐라 근접 대신 평타로만 때림
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
        double speed = locked ? -1.0 : f.channel != null ? f.channel.speed : (f.guardHeld ? Tuning.GUARD_SPEED : 0);
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

    private static double clamp(double v, double m) { return Math.max(-m, Math.min(m, v)); }

    public static Vec3d horizontal(Vec3d v) {
        Vec3d h = new Vec3d(v.x, 0, v.z);
        double l = h.length();
        return l < 1e-6 ? Vec3d.ZERO : h.multiply(1 / l);
    }

    /** 바닥에 퍼지는 흰 원 (충격파) */
    static void ring(ServerWorld w, Vec3d c, double r, int n) {
        DustParticleEffect white = new DustParticleEffect(new Vector3f(1f, 1f, 1f), 1.6f);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
            w.spawnParticles(white, x, c.y, z, 1, 0, 0, 0, 0);
            w.spawnParticles(ParticleTypes.CLOUD, x, c.y, z, 0, Math.cos(a), 0.02, Math.sin(a), 0.15);
        }
    }

    /** 머리 위에 피해 숫자 (빨간 글씨, 0.9초 뒤 사라짐) */
    private static void damageNumber(ServerWorld w, LivingEntity t, float dmg) {
        // NBT 로 읽히던 방식은 "Not a string" 오류가 나서 세터(접근자)로 바로 설정
        DisplayEntity.TextDisplayEntity d = EntityType.TEXT_DISPLAY.create(w);
        if (d == null) return;
        double ox = (t.getRandom().nextDouble() - 0.5) * 0.8, oy = t.getRandom().nextDouble() * 0.4;
        d.refreshPositionAndAngles(t.getX() + ox, t.getY() + t.getHeight() + 0.3 + oy, t.getZ() + (t.getRandom().nextDouble() - 0.5) * 0.8, 0, 0);
        var acc = (bbc.mixin.DisplayEntityAccessor) d;
        ((bbc.mixin.TextDisplayAccessor) d).bbc$setText(Text.literal(String.format("-%.1f", dmg)).formatted(Formatting.RED, Formatting.BOLD));
        ((bbc.mixin.TextDisplayAccessor) d).bbc$setBackground(0);
        acc.bbc$setBillboardMode(DisplayEntity.BillboardMode.CENTER);
        acc.bbc$setTransformation(new net.minecraft.util.math.AffineTransformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                new org.joml.Vector3f(1.4f, 1.4f, 1.4f), new org.joml.Quaternionf()));
        w.spawnEntity(d);
        Tasks.later(18, d::discard);
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
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
