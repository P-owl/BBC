package bbc.job.alchemy;

import bbc.combat.Awaken;
import bbc.combat.Combat;
import bbc.combat.Fx;
import bbc.combat.Hit;
import bbc.combat.Motion;
import bbc.core.PlayerData;
import bbc.core.Tasks;
import bbc.grow.Stat;
import bbc.job.JobSkills;
import bbc.job.JobType;
import bbc.job.Jobs;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 연금술사 (무현버스에서 이식). 등가교환: 연성진에서 아이템을 분해해 에너지로, 분해해 본 아이템만 다시 연성.
 * 등급 = 연성에 쓴 에너지 누적 (Alch.rank). 진리를 보기 전에는 연성 장갑을 껴야 기술을 씀. 기준 스탯 = 지능.
 *
 * 전투 기술 (기획서 '기존 직업 — 새 전투 수치', 피해는 ×1.0 = Hit 2.0)
 *   대지 가시 ×1.0 공중 24 (런처) · 대지 방벽 (거리 벌기) · 무기 연성 (창이 빈 무기 칸에 30초) ·
 *   바위 기둥 ×1.4 날려보내기→다운 16 (마무리) · 신의 손 ×1.2 잡기 30틱 (원거리 시작기) ·
 *   대지 붕괴 ×1.8 경직 18 + 가드 깨기 (광역 PvE) · 최후의 일격 10초 주는 피해 +40% · 때린 상대 갑옷 내구도 큰 피해
 * 각성 '손뼉 연성' (강화+추가): 장갑 없이·선딜 절반, 대지 가시 3연속(마지막은 높이 띄움), 바위 기둥 → 기둥 감옥(경직 30),
 *   신의 손 → 신의 양손(잡고 내리꽂음, 다운 추가타), 무기 연성 → 쌍창(두 무기 칸 모두)
 * 각성기 '대지의 심판': 거대한 바위 손 두 개가 박수로 짓눌러 가드 깨기 + 다운.
 *   2단계: 각성 중 현자의 돌을 지니고 쓰면 돌 1개로 붉은 연성 (각성 30초 연장, 기술 재사용 절반)
 * 임플란트 키(H): 오토메일 칼날 (진리 + 오토메일)
 */
public final class AlchemistJob implements JobType {
    public static final AlchemistJob INSTANCE = new AlchemistJob();
    public static final String PREFIX = "al.";
    private AlchemistJob() {}

    @Override public String id() { return Alch.ALCHEMIST; }
    @Override public String name() { return "연금술사"; }
    @Override public String prefix() { return PREFIX; }
    @Override public Item icon() { return AlchemyItems.ALCHEMY_CIRCLE_ITEM; }
    @Override public Formatting color() { return Formatting.AQUA; }
    @Override public int maxRank() { return 5; }
    @Override public String rankName(int rank) { return Alch.RANK_NAMES[Math.max(0, Math.min(5, rank))]; }
    @Override public Stat skillStat() { return Stat.INT; }
    @Override public String ultimateName() { return "대지의 심판"; }
    @Override public String[] summary() {
        return new String[]{"등가교환: 연성진에서 분해한 아이템을 에너지로 다시 연성", "땅을 다루는 연성 기술 (지능)",
                "대연금술사 → 현자의 돌 · 진리의 문"};
    }
    @Override public boolean selectable() { return false; }   // 연성진에서 입문 조건을 채워야 됨

    @Override
    public void onJoin(ServerPlayerEntity p, PlayerData.Entry e) {
        Alch.syncRank(Alch.entry(p));
    }

    // ═════════ 기술 ═════════

    private static final JobSkills.Skill[] SKILLS = {
            new JobSkills.Skill("대지 가시", "가시", 1, 20, 6, "앞으로 땅에서 가시가 줄지어 솟아 띄움 (런처)"),
            new JobSkills.Skill("대지 방벽", "방벽", 2, 25, 12, "눈앞에 5×3 돌벽 (8초)"),
            new JobSkills.Skill("무기 연성", "창", 3, 30, 25, "땅에서 강철 창을 뽑아 빈 무기 칸에 넣음 (30초, 창 평타·기술)"),
            new JobSkills.Skill("바위 기둥", "기둥", 4, 35, 15, "상대를 쫓아가는 바위 기둥, 날려보낸 뒤 다운 (마무리)"),
            new JobSkills.Skill("신의 손", "신의손", 4, 40, 25, "조준한 상대(30칸)를 바위 손이 움켜쥠, 1.5초 잡기 (원거리 시작기)"),
            new JobSkills.Skill("대지 붕괴", "붕괴", 5, 50, 45, "주위로 가시 물결 세 번, 가드 깨기 (광역)"),
            new JobSkills.Skill("최후의 일격", "일격", 5, 50, 60, "10초 동안 주는 피해 +40%, 때린 상대 갑옷 내구도 크게 깎음"),
    };

    private static final Hit SPIKE = new Hit(2.0f, Hit.Kind.AIR, 24, 0.05, 0.85, 10, false, false);
    private static final Hit SPIKE_HIGH = new Hit(2.0f, Hit.Kind.AIR, 30, 0.05, 1.2, 10, false, false);
    private static final Hit PILLAR = new Hit(2.8f, Hit.Kind.KNOCK, 16, 1.3, 0.4, 20, false, false);
    private static final Hit PRISON = new Hit(2.8f, Hit.Kind.HIT, 30, 0, 0, 20, false, false);
    private static final Hit GOD_HAND = new Hit(2.4f, Hit.Kind.HIT, 30, 0, 0, 20, false, false);
    private static final Hit GOD_SLAM = new Hit(2.4f, Hit.Kind.DOWN, 20, 0.1, -1.4, 20, false, true);
    private static final Hit COLLAPSE = new Hit(3.6f, Hit.Kind.AIR, 18, 0.2, 0.55, 25, true, false);
    private static final Hit JUDGMENT = new Hit(5.0f, Hit.Kind.DOWN, 30, 0.1, -1.3, 60, true, true);

    /** 최후의 일격 중인 플레이어 → 끝나는 시각(ms) */
    private static final Map<UUID, Long> FINISHER = new HashMap<>();

    private static boolean awake(ServerPlayerEntity p) { return Awaken.active(p); }

    private static final JobSkills.Set SET = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, PlayerData.Entry e) { return SKILLS; }

        @Override
        public String blocked(ServerPlayerEntity p, PlayerData.Entry e, int skill) {
            AlchemyData.Entry a = Alch.entry(p);
            if (!a.truth && !awake(p) && !AlchemyGloveItem.wearing(p)) return "연성 장갑을 손에 껴야 합니다 (진리를 보거나 각성하면 필요 없음)";
            return null;
        }

        @Override
        public boolean use(ServerPlayerEntity p, PlayerData.Entry e, int skill) {
            boolean ok = switch (skill) {
                case 0 -> earthSpike(p);
                case 1 -> earthWall(p);
                case 2 -> weaponForge(p);
                case 3 -> stonePillar(p);
                case 4 -> godHand(p);
                case 5 -> earthCollapse(p);
                case 6 -> finalStrike(p);
                default -> false;
            };
            if (ok) clapFx(p);
            return ok;
        }
    };

    @Override public JobSkills.Set skills() { return SET; }

    /** 각성 중엔 선딜 절반 */
    private static int startup(ServerPlayerEntity p, int t) { return awake(p) ? Math.max(1, t / 2) : t; }

    // ① 대지 가시: 앞으로 줄지어 솟는 가시. 각성: 3연속, 마지막은 높이 띄움
    private static boolean earthSpike(ServerPlayerEntity p) {
        Motion.play(p, "launch");
        int waves = awake(p) ? 3 : 1;
        for (int wv = 0; wv < waves; wv++) {
            final boolean last = wv == waves - 1 && waves > 1;
            Tasks.later(startup(p, 3) + wv * 10, () -> spikeLine(p, 7, last ? SPIKE_HIGH : SPIKE, last ? 2.4f : 1.8f));
        }
        return true;
    }

    private static void spikeLine(ServerPlayerEntity p, int len, Hit h, float height) {
        if (p.isDisconnected()) return;
        ServerWorld w = p.getServerWorld();
        Vec3d dir = Combat.horizontal(p.getRotationVec(1f));
        Vec3d start = p.getPos();
        Set<UUID> hit = new HashSet<>();
        for (int i = 1; i <= len; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                Vec3d at = start.add(dir.multiply(k));
                Double gy = AlchemyFx.ground(w, at);
                if (gy == null) return;
                AlchemyFx.spike(w, at.x, gy, at.z, 0.7f, height, 20);
                Box box = new Box(at.x - 0.9, gy, at.z - 0.9, at.x + 0.9, gy + 2.4, at.z + 0.9);
                for (Entity t : w.getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
                    if (hit.add(t.getUuid())) Combat.hit(p, (LivingEntity) t, h);
                }
            });
        }
    }

    // ② 대지 방벽: 2칸 앞 5×3 벽, 비어 있는 칸에만 세우고 8초 뒤 그대로면 치움
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
        if (placed.isEmpty()) { JobSkills.bar(p, "벽을 세울 공간이 없습니다", Formatting.RED); return false; }
        Motion.play(p, "focus");
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

    // ③ 무기 연성: 빈 무기 칸(핫바 2·3번)에 창. 각성 중엔 두 칸 모두 (쌍창)
    private static boolean weaponForge(ServerPlayerEntity p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(AlchemyItems.ALCHEMY_SPEAR)) inv.setStack(i, ItemStack.EMPTY);
        int filled = 0;
        for (int slot = 1; slot <= 2; slot++) {
            if (!inv.getStack(slot).isEmpty()) continue;
            inv.setStack(slot, new ItemStack(AlchemyItems.ALCHEMY_SPEAR));
            filled++;
            if (!awake(p)) break;
        }
        if (filled == 0) { JobSkills.bar(p, "빈 무기 칸(2·3번)이 없습니다", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        for (int i = 0; i < 6; i++) {
            final int k = i;
            Tasks.later(i, () -> w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + k * 0.35, c.z, 4, 0.12, 0.1, 0.12, 0.1));
        }
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), c.x, c.y + 0.1, c.z, 25, 0.5, 0.1, 0.5, 0.2);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.7f, 1.3f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 0.8f);
        JobSkills.bar(p, "⚒ 무기 연성 — 강철 창" + (filled > 1 ? " 두 자루" : "") + " (30초, 무기 칸으로 바꿔 쓰기)", Formatting.GOLD);
        return true;
    }

    // ④ 바위 기둥: 상대를 쫓아가 날려보냄. 각성: 기둥 감옥 (사방에서 솟아 가둠, 경직 30)
    private static boolean stonePillar(ServerPlayerEntity p) {
        LivingEntity t = aimTarget(p, 24);
        if (t == null) t = Combat.targets(p, 20, 45, 1).stream().findFirst().orElse(null);
        if (t == null) { JobSkills.bar(p, "주변에 공격할 대상이 없습니다", Formatting.RED); return false; }
        Motion.play(p, "focus");
        if (awake(p)) {
            final LivingEntity tt = t;
            Tasks.later(startup(p, 6), () -> prison(p, tt));
            return true;
        }
        Double gy = AlchemyFx.ground(p.getServerWorld(), p.getPos());
        StonePillarFx.launch(p, t, gy == null ? p.getY() : gy, v -> Combat.hit(p, v, PILLAR));
        return true;
    }

    private static void prison(ServerPlayerEntity p, LivingEntity t) {
        if (!t.isAlive() || p.isDisconnected()) return;
        ServerWorld w = p.getServerWorld();
        Vec3d c = t.getPos();
        Double gy = AlchemyFx.ground(w, c);
        double y = gy == null ? c.y : gy;
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 2 * i + Math.PI / 4;
            AlchemyFx.pillar(w, c.x + Math.cos(a) * 1.1, y, c.z + Math.sin(a) * 1.1, 0.9f, 2.8f, 34);
        }
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.2f, 0.5f);
        Combat.hit(p, t, PRISON);
        Fx.word(w, c.add(0, 2.6, 0), Fx.SLAM, 1.1f);
    }

    // ⑤ 신의 손: 바위 손이 움켜쥐어 30틱 잡기. 각성: 신의 양손 — 잡은 뒤 내리꽂음 (다운 추가타)
    private static boolean godHand(ServerPlayerEntity p) {
        LivingEntity t = aimTarget(p, 30);
        if (t == null) { JobSkills.bar(p, "조준한 대상이 없습니다 (30칸 안의 상대를 바라보세요)", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "focus");
        Vec3d from = p.getEyePos(), to = t.getPos().add(0, t.getHeight() / 2, 0);
        for (int i = 0; i < 24; i++) {
            Vec3d q = from.lerp(to, i / 24.0);
            w.spawnParticles(ParticleTypes.ENCHANTED_HIT, q.x, q.y, q.z, 1, 0, 0, 0, 0);
        }
        boolean both = awake(p);
        Tasks.later(startup(p, 6), () -> {
            if (!t.isAlive()) return;
            Vec3d at = t.getPos();
            Double gy = AlchemyFx.ground(w, at);
            double y = gy == null ? at.y : gy;
            GodHandFx.play(w, at, y, 8, both ? 26 : 34);
            Tasks.later(8, () -> {
                if (!t.isAlive()) return;
                Combat.hit(p, t, GOD_HAND);
                hold(t, 30);
                if (both) Tasks.later(18, () -> {
                    if (!t.isAlive()) return;
                    Combat.hit(p, t, GOD_SLAM);
                    Fx.word(w, t.getPos().add(0, 2.2, 0), Fx.SLAM, 1.2f);
                    Fx.debris(w, t.getPos(), 14, 1.2);
                });
            });
        });
        return true;
    }

    /** 잡힌 동안 그 자리에 고정 */
    private static void hold(LivingEntity t, int ticks) {
        Vec3d lock = t.getPos();
        for (int i = 1; i <= ticks; i++) {
            Tasks.later(i, () -> {
                if (!t.isAlive()) return;
                if (t.squaredDistanceTo(lock) > 0.04) t.requestTeleport(lock.x, lock.y, lock.z);
                t.setVelocity(0, Math.min(0, t.getVelocity().y), 0);
                t.velocityModified = true;
            });
        }
    }

    // ⑥ 대지 붕괴: 주위로 가시 물결 세 번 (가드 깨기)
    private static boolean earthCollapse(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        Set<UUID> hit = new HashSet<>();
        Motion.play(p, "slam");
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.8f, 0.6f);
        for (int wave = 0; wave < 3; wave++) {
            final double r = 2.5 + wave * 2;
            Tasks.later(startup(p, 4) + wave * 6, () -> {
                int n = (int) (r * 5);
                for (int i = 0; i < n; i++) {
                    double a = Math.PI * 2 * i / n;
                    double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
                    Double gy = AlchemyFx.ground(w, new Vec3d(x, c.y, z));
                    if (gy != null) AlchemyFx.spike(w, x, gy, z, 0.9f, 2.4f, 24);
                }
                Box box = new Box(c.x - r - 1, c.y - 2, c.z - r - 1, c.x + r + 1, c.y + 3, c.z + r + 1);
                for (Entity t : w.getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
                    double d = Math.sqrt(t.squaredDistanceTo(c.x, t.getY(), c.z));
                    if (d < r - 1.6 || d > r + 1.0 || !hit.add(t.getUuid())) continue;
                    Combat.hit(p, (LivingEntity) t, COLLAPSE);
                }
            });
        }
        return true;
    }

    // ⑦ 최후의 일격: 10초 주는 피해 ×1.4, 때린 상대 갑옷 내구도 큰 피해
    private static boolean finalStrike(ServerPlayerEntity p) {
        var f = Combat.of(p);
        f.dmgBuffUntil = Combat.now() + 200;
        f.dmgBuffMul = 1.4f;
        FINISHER.put(p.getUuid(), System.currentTimeMillis() + 10_000);
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "focus");
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_THUNDER.value(), SoundCategory.PLAYERS, 0.6f, 1.4f);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 0.6f);
        for (int t = 0; t < 200; t += 5) {
            Tasks.later(t, () -> {
                if (p.isDisconnected()) return;
                Vec3d hand = p.getPos().add(0, 1.2, 0).add(p.getRotationVector().multiply(0.5));
                p.getServerWorld().spawnParticles(ParticleTypes.ELECTRIC_SPARK, hand.x, hand.y, hand.z, 2, 0.2, 0.2, 0.2, 0.05);
            });
        }
        Tasks.later(200, () -> { FINISHER.remove(p.getUuid()); if (!p.isDisconnected()) JobSkills.bar(p, "최후의 일격이 풀렸습니다", Formatting.GRAY); });
        return true;
    }

    /** 최후의 일격 중에 때린 상대의 갑옷 내구도를 크게 깎음 (부수지는 않고 최대치의 25%) */
    private static void wearArmor(ServerPlayerEntity atk, LivingEntity t) {
        boolean any = false;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = t.getEquippedStack(slot);
            if (st.isEmpty() || !st.isDamageable()) continue;
            st.damage(Math.max(1, st.getMaxDamage() / 4), t, slot);
            any = true;
        }
        if (any) atk.getServerWorld().playSound(null, t.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.8f, 0.7f);
    }

    // ═════════ 각성 · 각성기 · 임플란트 ═════════

    @Override
    public void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        clapFx(p);
        JobSkills.bar(p, "✦ 손뼉 연성! 장갑 없이 · 선딜 절반 · 기술 강화 (45초)  G = 대지의 심판", Formatting.AQUA);
    }

    @Override
    public boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        List<LivingEntity> ts = Combat.targets(p, 8, 60, 6);
        if (ts.isEmpty()) { JobSkills.bar(p, "앞에 대상이 없습니다", Formatting.GRAY); return false; }
        // 2단계: 현자의 돌 → 붉은 연성
        if (Alchemy.hasStone(p) && PhilosopherStoneItem.consumeOne(p)) {
            Awaken.extend(p, 600);
            for (int i = 0; i < SKILLS.length; i++) {
                int left = JobSkills.cooldownLeft(p.getUuid(), i);
                if (left > 0) { JobSkills.resetCooldown(p.getUuid()); break; }
            }
            StoneFx.big(p);
            JobSkills.bar(p, "✦ 붉은 연성! 각성 30초 연장 · 재사용 초기화", Formatting.RED);
        }
        Motion.play(p, "slam");
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        for (LivingEntity t : ts) {
            Vec3d at = t.getPos();
            Double gy = AlchemyFx.ground(w, at);
            double y = gy == null ? at.y : gy;
            Vec3d side = Combat.horizontal(p.getRotationVec(1f)).crossProduct(new Vec3d(0, 1, 0)).normalize().multiply(1.6);
            GodHandFx.play(w, at.add(side), y, 8, 24);
            GodHandFx.play(w, at.subtract(side), y, 8, 24);
        }
        Tasks.later(14, () -> {
            for (LivingEntity t : ts) {
                if (!t.isAlive()) continue;
                Combat.hit(p, t, JUDGMENT);
                Fx.word(w, t.getPos().add(0, 2.4, 0), Fx.Sfx.DOGOOON, 1.5f);
                Fx.debris(w, t.getPos(), 20, 1.6);
                Fx.rings(w, t.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 3f, 2);
            }
            Fx.shakeAround(w, p.getPos(), 14, 0.9f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.0f, 0.6f);
        });
        return true;
    }

    @Override
    public boolean implant(ServerPlayerEntity p, PlayerData.Entry e) {
        if (!AutomailSkill.available(Alch.entry(p))) return false;
        AutomailSkill.use(p);
        return true;
    }

    // ═════════ 공용 ═════════

    /** 조준 대상: 시선과 겹치는 대상, 없으면 시선 주변 약 14도 안의 가장 가까운 대상 */
    static LivingEntity aimTarget(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        Box box = p.getBoundingBox().stretch(look.multiply(range)).expand(2);
        LivingEntity best = null;
        double bestD = range;
        for (Entity t : p.getServerWorld().getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
            Vec3d to = t.getPos().add(0, t.getHeight() / 2, 0).subtract(eye);
            double d = to.length();
            if (d > range || d < 0.5 || to.normalize().dotProduct(look) < 0.97 || !p.canSee(t)) continue;
            if (d < bestD) { bestD = d; best = (LivingEntity) t; }
        }
        return best;
    }

    /** 손뼉 → 바닥에 푸른 원 (진리를 본 자는 붉은 스파크도) */
    private static void clapFx(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.6f, 1.8f);
        Alchemy.fx(w, p.getBlockPos(), Alch.entry(p).truth);
    }

    // ═════════ 입문 ═════════

    /** 연성진의 직업 버튼: 조건(이해 50종 · 누적 분해 50,000 E · 공물)을 채우면 연금술사가 됨 */
    public static void tryJoin(ServerPlayerEntity p) {
        PlayerData.Entry je = Jobs.entry(p);
        if (Alch.ALCHEMIST.equals(je.job)) return;
        AlchemyData.Entry e = Alch.entry(p);
        List<String> miss = new ArrayList<>();
        if (e.known.size() < Alch.JOIN_KNOWN) miss.add("이해한 아이템 " + e.known.size() + " / " + Alch.JOIN_KNOWN + "종");
        if (e.depositedTotal < Alch.JOIN_DEPOSITED) miss.add("누적 분해 " + Alchemy.fmt(e.depositedTotal) + " / " + Alchemy.fmt(Alch.JOIN_DEPOSITED) + " E");
        for (int i = 0; i < Alch.TRIBUTE_ITEMS.length; i++) {
            int have = Alch.count(p, Alch.TRIBUTE_ITEMS[i]);
            if (have < Alch.TRIBUTE_COUNTS[i]) miss.add(new ItemStack(Alch.TRIBUTE_ITEMS[i]).getName().getString() + " " + have + " / " + Alch.TRIBUTE_COUNTS[i]);
        }
        if (!miss.isEmpty()) {
            p.sendMessage(Text.literal("연금술사 입문 조건이 모자랍니다:").formatted(Formatting.RED), false);
            for (String m : miss) p.sendMessage(Text.literal("  ✘ " + m).formatted(Formatting.GRAY), false);
            return;
        }
        if (!je.job.isEmpty()) { p.sendMessage(Text.literal("이미 다른 직업이 있습니다 (/bbc job 으로 그만두고 오세요)").formatted(Formatting.RED), false); return; }
        for (int i = 0; i < Alch.TRIBUTE_ITEMS.length; i++) Alch.take(p, Alch.TRIBUTE_ITEMS[i], Alch.TRIBUTE_COUNTS[i]);
        Jobs.set(p, INSTANCE);
        Alch.syncRank(e);
        Alchemy.bigFx(p);
        p.getInventory().offerOrDrop(new ItemStack(AlchemyItems.ALCHEMY_GLOVE));
        Alch.announceRank(p, "연금술사", Alch.RANK_NAMES[Alch.rank(e)], true);
    }

    public static void register() {
        // 최후의 일격 중 때리면 갑옷 내구도
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((ent, src, base, taken, blocked) -> {
            if (taken <= 0 || !(src.getAttacker() instanceof ServerPlayerEntity atk)) return;
            Long until = FINISHER.get(atk.getUuid());
            if (until == null || until < System.currentTimeMillis()) return;
            wearArmor(atk, ent);
        });
    }
}
