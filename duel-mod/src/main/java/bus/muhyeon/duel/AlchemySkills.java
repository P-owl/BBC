package bus.muhyeon.duel;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 연금술사 전투 기술. 연성 에너지를 써서 발동, 등급마다 하나씩 해금.
 *   V = 고른 기술 사용, B = 기술 바꾸기, G = 손뼉 연성 (진리를 본 자)
 */
public final class AlchemySkills {
    private AlchemySkills() {}

    public record Skill(String name, String shortName, int rank, double cost, int cooldown, String desc) {} // cost = 스태미나

    /** 전투 모드에서 1~7 키 순서 */
    public static final Skill[] SKILLS = {
            new Skill("대지 가시", "가시", 1, 20, 6, "앞으로 8칸, 땅에서 가시가 솟아 10 피해 + 띄움"),
            new Skill("대지 방벽", "방벽", 2, 25, 12, "눈앞에 5×3 돌벽 (8초 뒤 사라짐)"),
            new Skill("무기 연성", "창", 3, 35, 25, "땅에서 강철 창을 뽑아 손에 듦 (공격력 12 · 사거리 +1.5 · 5번 때리면 부서짐)"),
            new Skill("바위 기둥", "기둥", 4, 40, 20, "상대(24칸)를 추적해 날아가는 바위 기둥, 16 피해 + 넉백 · 지나간 궤적에 기둥이 남음"),
            new Skill("신의 손", "신의손", 4, 55, 30, "조준한 상대(30칸)를 추적, 바닥에서 바위 손이 움켜쥐어 13 피해 + 4초 구속"),
            new Skill("대지 붕괴", "붕괴", 5, 80, 60, "주위 7칸 가시 물결, 22 피해 + 둔화"),
            new Skill("최후의 일격", "일격", 5, 100, 90, "10초 동안 공격력 +14, 때린 상대의 갑옷을 모두 파괴"),
    };

    private static final Map<UUID, long[]> COOLDOWN = new HashMap<>();
    private static final Identifier BLADE_ID = Identifier.of(DuelMod.MOD_ID, "steel_blade");
    private static final Identifier FINISH_ID = Identifier.of(DuelMod.MOD_ID, "final_strike");
    /** 최후의 일격이 켜져 있는 플레이어 → 끝나는 시각 */
    private static final Map<UUID, Long> FINISHER = new HashMap<>();

    public static void resetCooldown(UUID id) { COOLDOWN.remove(id); }

    /** 남은 재사용 시간(초), 0 이면 사용 가능 */
    public static int cooldownLeft(UUID id, int idx) {
        long[] cd = COOLDOWN.get(id);
        if (cd == null) return 0;
        long left = cd[idx] - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) (left / 1000 + 1);
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(AlchKeyPayload.ID, AlchKeyPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(AlchKeyPayload.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            p.getServer().execute(() -> handle(p, payload.action()));
        });
        // 최후의 일격: 실제로 피해를 준 근접 공격이면 상대 갑옷 전부 파괴
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken <= 0 || !(source.getAttacker() instanceof ServerPlayerEntity atk)) return;
            if (source.getSource() != atk) return; // 직접 휘두른 공격만
            Long until = FINISHER.get(atk.getUuid());
            if (until == null || until < System.currentTimeMillis()) return;
            shatterArmor(atk, entity);
        });
    }

    private static void handle(ServerPlayerEntity p, int action) {
        if (p.isDisconnected() || p.isSpectator()) return;
        AlchemyData.Entry e = Jobs.entry(p);
        if (action == AlchKeyPayload.CLAP) {
            if (!e.truth) { bar(p, "손뼉 연성은 진리를 본 자만 쓸 수 있습니다", Formatting.RED); return; }
            if (p.currentScreenHandler != p.playerScreenHandler) return;
            AlchemyMenu.openPortable(p);
        } else if (action == AlchKeyPayload.TOGGLE) {
            CombatMode.toggle(p);
        } else if (action >= AlchKeyPayload.SKILL_BASE && !Jobs.isAlchemist(e)) {
            if (!CombatMode.active(p)) return;
            JobSkills.use(p, e, action - AlchKeyPayload.SKILL_BASE);
        } else if (action == AlchKeyPayload.SKILL_BASE + SKILLS.length) {
            if (!CombatMode.active(p)) return;
            AutomailSkill.use(p);
        } else if (action >= AlchKeyPayload.SKILL_BASE && action < AlchKeyPayload.SKILL_BASE + SKILLS.length) {
            if (!CombatMode.active(p)) return;
            use(p, e, action - AlchKeyPayload.SKILL_BASE);
        }
    }

    private static void use(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
        Skill s = SKILLS[idx];
        if (!e.truth && !AlchemyGloveItem.wearing(p)) { bar(p, "연성 장갑을 손에 껴야 합니다", Formatting.RED); return; }
        if (Jobs.rank(e) < s.rank()) { bar(p, s.name() + " — " + Jobs.RANK_NAMES[s.rank()] + "부터 쓸 수 있습니다", Formatting.RED); return; }
        long now = System.currentTimeMillis();
        long[] cd = COOLDOWN.computeIfAbsent(p.getUuid(), k -> new long[SKILLS.length]);
        if (cd[idx] > now) { bar(p, s.name() + " 재사용까지 " + ((cd[idx] - now) / 1000 + 1) + "초", Formatting.GRAY); return; }
        if (Stamina.get(p) + 1e-6 < s.cost()) { bar(p, "스태미나 부족 (" + (int) s.cost() + " 필요, 현재 " + (int) Stamina.get(p) + ")", Formatting.RED); return; }

        boolean ok = switch (idx) {
            case 0 -> earthSpike(p);
            case 1 -> earthWall(p);
            case 2 -> weaponForge(p);
            case 3 -> stonePillar(p);
            case 4 -> godHand(p);
            case 5 -> earthCollapse(p);
            case 6 -> finalStrike(p);
            default -> false;
        };
        if (!ok) return;
        Stamina.spend(p, s.cost());
        cd[idx] = now + s.cooldown() * 1000L;
        clapFx(p);
        bar(p, "✦ " + s.name() + "!  (스태미나 -" + (int) s.cost() + ")", Formatting.GOLD);
    }

    // ───────── 기술 ─────────

    /** ① 앞으로 한 줄로 가시가 차례로 솟음 */
    private static boolean earthSpike(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d dir = flatLook(p);
        Vec3d start = p.getPos();
        Set<UUID> hit = new HashSet<>();
        for (int i = 1; i <= 8; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                Vec3d at = start.add(dir.multiply(k));
                Double gy = ground(w, at);
                if (gy == null) return;
                spike(w, at.x, gy, at.z, 0.7f, 1.8f, 24);
                hurt(p, new Box(at.x - 0.9, gy, at.z - 0.9, at.x + 0.9, gy + 2.2, at.z + 0.9), 10f, 0.7, hit);
            });
        }
        return true;
    }

    /** ② 바라보는 방향 2칸 앞에 5×3 벽. 비어 있는 칸에만 세우고 8초 뒤 그대로면 치움 */
    private static boolean earthWall(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Direction facing = p.getHorizontalFacing();
        Direction side = facing.rotateYClockwise();
        BlockPos base = p.getBlockPos().offset(facing, 2);
        BlockState wall = Blocks.TUFF_BRICKS.getDefaultState();
        List<BlockPos> placed = new ArrayList<>();
        for (int s = -2; s <= 2; s++) {
            for (int y = 0; y < 3; y++) {
                BlockPos bp = base.offset(side, s).up(y);
                if (!w.getBlockState(bp).isReplaceable()) continue;
                if (!w.getOtherEntities(null, new Box(bp)).isEmpty()) continue;
                final BlockPos at = bp;
                Tasks.later(y * 2, () -> {
                    if (!w.getBlockState(at).isReplaceable()) return;
                    w.setBlockState(at, wall);
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wall), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.05);
                });
                placed.add(bp);
            }
        }
        if (placed.isEmpty()) { bar(p, "벽을 세울 공간이 없습니다", Formatting.RED); return false; }
        w.playSound(null, base, SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 1.0f, 0.5f);
        Tasks.later(160, () -> {
            for (BlockPos bp : placed) {
                if (w.getBlockState(bp).isOf(wall.getBlock())) {
                    w.setBlockState(bp, Blocks.AIR.getDefaultState());
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wall), bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.05);
                }
            }
        });
        return true;
    }

    /** ③ 무기 연성: 발밑 땅에서 창을 뽑아 주 손에 쥠. 주 손에 있던 것은 보조 손이나 가방으로 */
    static boolean weaponForge(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        // 이미 든 연성 창은 새로 교체
        for (int i = 0; i < p.getInventory().size(); i++)
            if (p.getInventory().getStack(i).isOf(DuelMod.ALCHEMY_SPEAR)) p.getInventory().setStack(i, net.minecraft.item.ItemStack.EMPTY);
        if (p.getOffHandStack().isOf(DuelMod.ALCHEMY_SPEAR)) p.setStackInHand(net.minecraft.util.Hand.OFF_HAND, net.minecraft.item.ItemStack.EMPTY);

        net.minecraft.item.ItemStack main = p.getMainHandStack();
        if (!main.isEmpty()) {
            net.minecraft.item.ItemStack moved = main.copy();
            p.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, net.minecraft.item.ItemStack.EMPTY);
            if (p.getOffHandStack().isEmpty()) p.setStackInHand(net.minecraft.util.Hand.OFF_HAND, moved);
            else p.getInventory().offerOrDrop(moved);
        }
        p.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(DuelMod.ALCHEMY_SPEAR));

        // 연출: 발밑에서 창이 솟아올라 손에 들어옴
        Vec3d c = p.getPos();
        for (int i = 0; i < 6; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                w.spawnParticles(SPEAR_DUST, c.x, c.y + k * 0.35, c.z, 6, 0.15, 0.1, 0.15, 0);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + k * 0.35, c.z, 3, 0.1, 0.1, 0.1, 0.1);
            });
        }
        w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.STONE.getDefaultState()),
                c.x, c.y + 0.1, c.z, 25, 0.5, 0.1, 0.5, 0.2);
        Alchemy.fx(w, p.getBlockPos(), false);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.7f, 1.3f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 0.8f);
        bar(p, "⚒ 무기 연성 — 강철 창!  (" + AlchemySpearItem.HITS + "번 때리면 부서짐)", Formatting.GOLD);
        return true;
    }

    private static final net.minecraft.particle.DustParticleEffect SPEAR_DUST =
            new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.35f, 0.75f, 1.0f), 1.0f);

    /** ④ 바위 기둥: 상대를 추적해 날아가는 네모난 바위 기둥 (조준한 상대 우선, 없으면 앞쪽 가장 가까운 상대) */
    private static boolean stonePillar(ServerPlayerEntity p) {
        LivingEntity t = aimTarget(p, 24);
        if (t == null) t = nearestAhead(p, 20);
        if (t == null) { bar(p, "주변에 공격할 대상이 없습니다", Formatting.RED); return false; }
        if (Targets.pvpBlocked(p, t)) { bar(p, "PvP가 꺼져 있어 플레이어에게는 효과가 없습니다", Formatting.GRAY); return false; }
        Double gy = ground(p.getServerWorld(), p.getPos());
        StonePillarFx.launch(p, t, gy == null ? p.getY() : gy, 16f);
        return true;
    }

    /** 앞쪽(좌우 약 70도) 가장 가까운, 눈에 보이는 대상 */
    private static LivingEntity nearestAhead(ServerPlayerEntity p, double range) {
        Vec3d look = p.getRotationVector();
        LivingEntity best = null;
        double bestD = range * range;
        for (Entity e : p.getServerWorld().getOtherEntities(p, p.getBoundingBox().expand(range), x -> Targets.canHit(p, x))) {
            Vec3d to = e.getPos().subtract(p.getPos());
            double d = to.lengthSquared();
            if (d > bestD || to.normalize().dotProduct(look) < 0.35 || !p.canSee(e)) continue;
            bestD = d;
            best = (LivingEntity) e;
        }
        return best;
    }

    /** ⑤ 진리: 주위로 가시 물결이 세 번 퍼짐 */
    private static boolean earthCollapse(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        Set<UUID> hit = new HashSet<>();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.8f, 0.6f);
        for (int wave = 0; wave < 3; wave++) {
            final double r = 3 + wave * 2;
            Tasks.later(wave * 6, () -> {
                int n = (int) (r * 5);
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
                    Double gy = ground(w, new Vec3d(x, c.y, z));
                    if (gy != null) spike(w, x, gy, z, 0.9f, 2.6f, 30);
                }
                hurtRing(p, c, r - 1.5, r + 1.0, 22f, hit);
            });
        }
        return true;
    }

    /** ⑤ 신의 손: 조준한 상대 발밑에서 바위 손이 솟아 움켜쥠 → 4초 구속 */
    private static boolean godHand(ServerPlayerEntity p) {
        LivingEntity t = aimTarget(p, 30);
        if (t == null) { bar(p, "조준한 대상이 없습니다 (30칸 이내의 몹·포켓몬·플레이어를 바라보세요)", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        // 추적선: 시전자 → 상대
        Vec3d from = p.getEyePos(), to = t.getPos().add(0, t.getHeight() / 2, 0);
        for (int i = 0; i < 24; i++) {
            Vec3d q = from.lerp(to, i / 24.0);
            w.spawnParticles(ParticleTypes.ENCHANTED_HIT, q.x, q.y, q.z, 1, 0, 0, 0, 0);
        }
        Tasks.later(6, () -> {
            if (!t.isAlive()) return;
            Vec3d at = t.getPos();
            Double gy = ground(w, at);
            double y = gy == null ? at.y : gy;
            GodHandFx.play(w, at, y, 8, 80);
            // 손이 다 오므라드는 순간 피해 + 구속
            Tasks.later(8, () -> {
                if (!t.isAlive()) return;
                if (Targets.pvpBlocked(p, t)) { bar(p, "PvP가 꺼져 있어 플레이어에게는 효과가 없습니다", Formatting.GRAY); return; }
                Targets.damage(p, t, 13f);
                bind(t, 80);
            });
        });
        return true;
    }

    /** 구속: 움직일 수도 뛸 수도 없고, 순간이동·밀려남도 제자리로 되돌림 */
    private static final Identifier BIND_SPEED = Identifier.of(DuelMod.MOD_ID, "bind_speed");
    private static final Identifier BIND_JUMP = Identifier.of(DuelMod.MOD_ID, "bind_jump");

    private static void bind(LivingEntity t, int ticks) {
        Vec3d lock = t.getPos();
        EntityAttributeInstance sp = t.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        EntityAttributeInstance jp = t.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH);
        if (sp != null) { sp.removeModifier(BIND_SPEED); sp.addTemporaryModifier(new EntityAttributeModifier(BIND_SPEED, -1, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)); }
        if (jp != null) { jp.removeModifier(BIND_JUMP); jp.addTemporaryModifier(new EntityAttributeModifier(BIND_JUMP, -1, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)); }
        if (t instanceof ServerPlayerEntity sp2) sp2.sendMessage(Text.literal("✋ 신의 손에 붙잡혔다! (4초)").formatted(Formatting.DARK_RED), true);
        for (int i = 1; i <= ticks; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!t.isAlive()) return;
                if (k < ticks) {
                    if (t.squaredDistanceTo(lock) > 0.04) t.requestTeleport(lock.x, lock.y, lock.z);
                    t.setVelocity(0, Math.min(0, t.getVelocity().y), 0);
                    t.velocityModified = true;
                    if (k % 10 == 0 && t.getWorld() instanceof ServerWorld sw)
                        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), lock.x, lock.y + 0.5, lock.z, 6, 0.4, 0.5, 0.4, 0.05);
                } else {
                    EntityAttributeInstance a = t.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                    EntityAttributeInstance b = t.getAttributeInstance(EntityAttributes.GENERIC_JUMP_STRENGTH);
                    if (a != null) a.removeModifier(BIND_SPEED);
                    if (b != null) b.removeModifier(BIND_JUMP);
                }
            });
        }
    }

    /** ⑦ 최후의 일격: 10초 공격력 +10, 때리면 상대 갑옷 전부 파괴 */
    private static boolean finalStrike(ServerPlayerEntity p) {
        EntityAttributeInstance inst = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (inst == null) return false;
        inst.removeModifier(FINISH_ID);
        inst.addTemporaryModifier(new EntityAttributeModifier(FINISH_ID, 14, EntityAttributeModifier.Operation.ADD_VALUE));
        FINISHER.put(p.getUuid(), System.currentTimeMillis() + 10_000);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_THUNDER.value(), SoundCategory.PLAYERS, 0.6f, 1.4f);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 0.6f);
        for (int t = 0; t < 200; t += 5) {
            Tasks.later(t, () -> {
                if (p.isDisconnected()) return;
                Vec3d hand = p.getPos().add(0, 1.2, 0).add(p.getRotationVector().multiply(0.5));
                p.getServerWorld().spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.35f, 0.75f, 1.0f), 1.0f),
                        hand.x, hand.y, hand.z, 4, 0.2, 0.2, 0.2, 0);
            });
        }
        Tasks.later(200, () -> {
            FINISHER.remove(p.getUuid());
            EntityAttributeInstance i2 = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            if (i2 != null) i2.removeModifier(FINISH_ID);
            if (!p.isDisconnected()) bar(p, "최후의 일격이 풀렸습니다", Formatting.GRAY);
        });
        return true;
    }

    private static void shatterArmor(ServerPlayerEntity atk, LivingEntity t) {
        boolean any = false;
        for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[]{
                net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
                net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET}) {
            net.minecraft.item.ItemStack st = t.getEquippedStack(slot);
            if (st.isEmpty()) continue;
            t.sendEquipmentBreakStatus(st.getItem(), slot);
            t.equipStack(slot, net.minecraft.item.ItemStack.EMPTY);
            any = true;
        }
        if (!any) return;
        ServerWorld w = atk.getServerWorld();
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.spawnParticles(ParticleTypes.CRIT, t.getX(), t.getY() + 1, t.getZ(), 30, 0.4, 0.6, 0.4, 0.3);
        bar(atk, "✦ 최후의 일격! 상대의 갑옷을 분해했다", Formatting.DARK_RED);
        if (t instanceof ServerPlayerEntity v) v.sendMessage(Text.literal("💥 갑옷이 전부 분해되었다!").formatted(Formatting.DARK_RED), true);
    }

    /** 조준 대상: 먼저 시선과 정확히 겹치는 대상, 없으면 시선 주변 약 14도 안의 가장 가까운 대상 */
    static LivingEntity aimTarget(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), look = p.getRotationVector(), end = eye.add(look.multiply(range));
        Box box = p.getBoundingBox().stretch(look.multiply(range)).expand(2);
        // 블록에 막히는 거리
        double wall = range;
        HitResult block = p.raycast(range, 0, false);
        if (block.getType() != HitResult.Type.MISS) wall = block.getPos().distanceTo(eye);
        var hit = net.minecraft.entity.projectile.ProjectileUtil.raycast(p, eye, end, box, x -> Targets.canHit(p, x), range * range);
        if (hit != null && hit.getEntity() instanceof LivingEntity le && hit.getPos().distanceTo(eye) <= wall + 0.5) return le;

        LivingEntity best = null;
        double bestD = range;
        for (Entity t : p.getServerWorld().getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
            Vec3d to = t.getPos().add(0, t.getHeight() / 2, 0).subtract(eye);
            double d = to.length();
            if (d > range || d < 0.5) continue;
            if (to.normalize().dotProduct(look) < 0.97) continue;
            if (!p.canSee(t)) continue;
            if (d < bestD) { bestD = d; best = (LivingEntity) t; }
        }
        return best;
    }

    // ───────── 공용 ─────────

    private static void hurt(ServerPlayerEntity p, Box box, float dmg, double up, Set<UUID> already) {
        for (Entity t : p.getServerWorld().getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
            if (!already.add(t.getUuid())) continue;
            if (Targets.damage(p, t, dmg)) t.addVelocity(0, up, 0);
            t.velocityModified = true;
        }
    }

    private static void hurtRing(ServerPlayerEntity p, Vec3d c, double rMin, double rMax, float dmg, Set<UUID> already) {
        Box box = new Box(c.x - rMax, c.y - 2, c.z - rMax, c.x + rMax, c.y + 3, c.z + rMax);
        for (Entity t : p.getServerWorld().getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
            double d = Math.sqrt(t.squaredDistanceTo(c.x, t.getY(), c.z));
            if (d < rMin || d > rMax || !already.add(t.getUuid())) continue;
            if (Targets.damage(p, t, dmg)) {
                t.addVelocity(0, 0.8, 0);
                if (t instanceof LivingEntity le) le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 2));
            }
            t.velocityModified = true;
        }
    }


    /** 발밑에서 위아래 3칸 안의 땅 높이 */
    private static Double ground(ServerWorld w, Vec3d at) {
        BlockPos bp = BlockPos.ofFloored(at.x, at.y + 1, at.z);
        for (int i = 0; i < 5; i++) {
            BlockPos below = bp.down();
            if (!w.getBlockState(bp).isSolidBlock(w, bp) && w.getBlockState(below).isSolidBlock(w, below)) return (double) bp.getY();
            bp = below;
        }
        return null;
    }

    /** 뾰족한 돌기둥 연출 (블록 디스플레이, 실제 블록 아님). 솟았다가 life 틱 뒤 가라앉음 */
    private static void spike(ServerWorld w, double x, double y, double z, float width, float height, int life) {
        display(w, Blocks.POINTED_DRIPSTONE.getDefaultState(), x, y, z, width, height, width, 0, life, 3);
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), x, y + 0.1, z, 8, 0.3, 0.1, 0.3, 0.1);
        w.playSound(null, BlockPos.ofFloored(x, y, z), SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 0.8f, 0.7f);
    }

    /** 땅속(y - 높이)에서 delay 틱 뒤 솟아올라 life 틱 동안 있다가 가라앉는 블록 디스플레이 */
    static Entity display(ServerWorld w, BlockState state, double x, double y, double z, float sx, float sy, float sz, int delay, int life, int glide) {
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound tr = new NbtCompound();
        tr.put("left_rotation", floats(0, 0, 0, 1));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        tr.put("translation", floats(-sx / 2, 0, -sz / 2));
        tr.put("scale", floats(sx, sy, sz));
        n.put("transformation", tr);
        n.putInt("teleport_duration", glide);
        float yaw = (float) (Math.random() * 360);
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> {
            ent.refreshPositionAndAngles(x, y - sy, z, yaw, 0);
            return ent;
        });
        if (e == null) return null;
        w.spawnEntity(e);
        Tasks.later(delay + 1, () -> e.requestTeleport(x, y, z));
        Tasks.later(delay + life, () -> e.requestTeleport(x, y - sy, z));
        Tasks.later(delay + life + glide + 1, e::discard);
        return e;
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }

    private static Vec3d flatLook(ServerPlayerEntity p) {
        float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-MathHelper.sin(yaw), 0, MathHelper.cos(yaw));
    }

    /** 손뼉 → 바닥에 푸른 원 */
    private static void clapFx(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.6f, 1.8f);
        Alchemy.fx(w, p.getBlockPos(), Jobs.entry(p).truth);
    }

    private static void bar(ServerPlayerEntity p, String msg, Formatting f) {
        p.sendMessage(Text.literal(msg).formatted(f), true);
    }

    /** 직업 화면 설명용 */
    public static String[] describe(int rank) {
        String[] out = new String[SKILLS.length];
        for (int i = 0; i < SKILLS.length; i++) {
            Skill s = SKILLS[i];
            out[i] = (s.rank() <= rank ? "✔ " : "🔒 ") + (i + 1) + "키 [" + Jobs.RANK_NAMES[s.rank()] + "] " + s.name()
                    + " — " + s.desc() + " (스태미나 " + (int) s.cost() + ", " + s.cooldown() + "초)";
        }
        return out;
    }
}
