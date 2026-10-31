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
 * 연금술사 (무현버스에서 이식, ABA 에드워드식으로 리메이크). 등가교환: 연성진에서 아이템을 분해해 에너지로, 분해해 본 아이템만 다시 연성.
 * 등급 = 연성에 쓴 에너지 누적 (Alch.rank). 진리를 보기 전에는 연성 장갑을 껴야 기술을 씀. 기준 스탯 = 지능.
 *
 * 평타 (al_1~5, 참고: 다른 게임의 에드워드 평타): 오토메일 팔 칼날 사선 베기 → 왼손 훅 → 돌려차기 → 웅크려 땅 짚기(작은 가시)
 *   → 손뼉·땅 짚고 쳐올리기(바위 기둥이 솟아 날려보냄). 띄우기 = 땅 짚고 올려치기(가시). 대기 자세 = 옆으로 서서 왼손바닥을 앞으로.
 * 기술 (ABA 에드워드: 1 Pillar · 2 Transmute Weapon · 3 Spikes · 4 Wall)
 *   기둥: 손뼉 → 바위 기둥이 땅을 타고 쫓아가 날려보냄 (거리 시작기)
 *   무기 연성: 땅에서 창을 뽑음 → 20초 동안 평타가 창(피해 ×1.5). 한 번 더 누르면 돌진 찌르기로 마무리하고 창이 부서짐
 *   가시: 앞 땅에서 가시 무더기가 솟아 띄움 + 가드 깨기
 *   벽: 앞 땅에서 벽이 솟아 위에 선 상대를 띄우고 4초 동안 길을 막음
 *   신의 손 (잡기) · 대지 붕괴 (광역 PvE) 는 남겨 둔 고를 거리
 * 각성 '손뼉 연성' (ABA: 코트가 찢겨 날아가고 오토메일이 드러남): 장갑 없이·선딜 절반, 그리고
 *   기둥 → 거대한 바위 주먹 · 무기 연성 → 신의 양손(잡고 짓눌러 다운) · 가시 → 가시 숲(연타 후 높이 띄움) · 벽 → 오토메일 일격(가드 깨기, 멀리 날림)
 * 각성기 '대지의 심판': 거대한 바위 손 두 개가 박수로 짓눌러 가드 깨기 + 다운. 현자의 돌을 지니면 붉은 연성 (각성 30초 연장 · 재사용 초기화)
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

    // ═════════ 평타 · 대기 ═════════

    /** 맨손 평타: 5타, 선딜 4, 마무리 선딜 6 */
    private static final bbc.combat.Weapon.M1Profile M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1f, 0.2, "al_", 6);
    /** 무기 연성 중: 창 평타 (ABA 처럼 맨손 4.1 → 창 6.3 ≈ ×1.5) */
    private static final bbc.combat.Weapon.M1Profile SPEAR_M1 = new bbc.combat.Weapon.M1Profile(4, 4, 3, 1.5f, 1.0, "sp_", 5);

    @Override public bbc.combat.Weapon.M1Profile m1() { return M1; }
    @Override public bbc.combat.Weapon.M1Profile m1(ServerPlayerEntity p) { return spearOn(p) ? SPEAR_M1 : M1; }
    @Override public String stance() { return "stance_al"; }

    /** 4타 = 상대 발밑 작은 가시, 5타·띄우기 = 바위 기둥이 솟음 (연출만, 판정은 평타 그대로) */
    @Override
    public void m1Fx(ServerPlayerEntity p, String motion) {
        if (!motion.startsWith("al_")) return;
        ServerWorld w = p.getServerWorld();
        Vec3d at = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(1.8));
        Double gy = AlchemyFx.ground(w, at);
        if (gy == null) return;
        switch (motion) {
            case "al_4" -> { AlchemyFx.spike(w, at.x, gy, at.z, 0.45f, 1.0f, 10); Alchemy.fx(w, BlockPos.ofFloored(at), false); }
            case "al_5" -> { AlchemyFx.pillar(w, at.x, gy, at.z, 0.9f, 2.2f, 14); Fx.debris(w, at, 8, 0.8); }
            case "al_rise" -> AlchemyFx.spike(w, at.x, gy, at.z, 0.6f, 2.0f, 12);
            default -> { }
        }
    }

    // ═════════ 기술 ═════════

    private static final JobSkills.Skill[] SKILLS = {
            new JobSkills.Skill("기둥", "기둥", 1, 20, 8, "손뼉 → 바위 기둥이 땅을 타고 상대를 쫓아가 날려보냄 (각성: 거대한 바위 주먹)"),
            new JobSkills.Skill("무기 연성", "창", 2, 25, 12, "땅에서 창을 뽑아 20초 동안 창 평타. 한 번 더 누르면 돌진 찌르기 (각성: 신의 양손)"),
            new JobSkills.Skill("가시", "가시", 1, 25, 10, "앞 땅에서 가시 무더기가 솟아 띄움, 가드 깨기 (각성: 가시 숲)"),
            new JobSkills.Skill("벽", "벽", 2, 20, 12, "앞 땅에서 벽이 솟아 상대를 띄우고 4초 동안 길을 막음 (각성: 오토메일 일격)"),
            new JobSkills.Skill("신의 손", "신의손", 4, 40, 25, "조준한 상대(30칸)를 바위 손이 움켜쥠, 1.5초 잡기 (원거리 시작기)"),
            new JobSkills.Skill("대지 붕괴", "붕괴", 4, 50, 45, "주위로 가시 물결 세 번, 가드 깨기 (광역)"),
    };

    private static final Hit PILLAR = new Hit(3.0f, Hit.Kind.KNOCK, 16, 1.3, 0.35, 20, false, false);
    private static final Hit GIANT_FIST = new Hit(4.5f, Hit.Kind.KNOCK, 22, 2.0, 0.5, 40, false, false);
    private static final Hit THRUST = new Hit(2.9f, Hit.Kind.HIT, 20, 0.3, 0, 25, false, false);
    private static final Hit SPIKES = new Hit(2.6f, Hit.Kind.AIR, 26, 0.1, 0.95, 30, true, false);
    private static final Hit FOREST_TICK = new Hit(0.9f, Hit.Kind.AIR, 14, 0, 0.22, 10, false, false);
    private static final Hit FOREST_END = new Hit(3.5f, Hit.Kind.AIR, 30, 0.1, 1.3, 40, true, false);
    private static final Hit WALL = new Hit(2.2f, Hit.Kind.AIR, 30, 0, 1.05, 20, false, false);
    private static final Hit STEEL_PUNCH = new Hit(5.0f, Hit.Kind.KNOCK, 22, 2.2, 0.4, 60, true, false);
    private static final Hit GOD_HAND = new Hit(2.4f, Hit.Kind.HIT, 30, 0, 0, 20, false, false);
    private static final Hit GOD_SLAM = new Hit(2.4f, Hit.Kind.DOWN, 20, 0.1, -1.4, 20, false, true);
    private static final Hit CRUSH = new Hit(4.0f, Hit.Kind.DOWN, 24, 0, -1.2, 40, true, true);
    private static final Hit COLLAPSE = new Hit(3.6f, Hit.Kind.AIR, 18, 0.2, 0.55, 25, true, false);
    private static final Hit JUDGMENT = new Hit(5.0f, Hit.Kind.DOWN, 30, 0.1, -1.3, 60, true, true);

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
            boolean aw = awake(p);
            return switch (skill) {
                case 0 -> aw ? giantFist(p) : pillar(p);
                case 1 -> aw ? godHands(p) : weaponForge(p);
                case 2 -> aw ? spikeForest(p) : spikes(p);
                case 3 -> aw ? steelPunch(p) : wall(p);
                case 4 -> godHand(p);
                case 5 -> earthCollapse(p);
                default -> false;
            };
        }
    };

    @Override public JobSkills.Set skills() { return SET; }

    /** 각성 중엔 선딜 절반 */
    private static int startup(ServerPlayerEntity p, int t) { return awake(p) ? Math.max(1, t / 2) : t; }

    /** 손뼉 → 땅 짚기 모션을 틀고, 땅을 짚는 틱에 할 일 */
    private static void clapThen(ServerPlayerEntity p, Runnable onPlant) {
        Motion.play(p, "al_clap");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Tasks.later(startup(p, 3), () -> w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.7f, 1.9f));
        Tasks.later(startup(p, 6), () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            clapFx(p);
            onPlant.run();
        });
    }

    /** 앞쪽 지점: 조준한(또는 앞의 가장 가까운) 상대 발밑, 없으면 dist 칸 앞 */
    private static Vec3d frontSpot(ServerPlayerEntity p, double dist, double range) {
        LivingEntity t = aimTarget(p, range);
        if (t == null) t = Combat.targets(p, range, 40, 1).stream().findFirst().orElse(null);
        if (t != null) return t.getPos();
        return p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(dist));
    }

    // ① 기둥: 손뼉 → 바위 기둥이 땅을 타고 쫓아가 날려보냄
    private static boolean pillar(ServerPlayerEntity p) {
        LivingEntity t = aimTarget(p, 20);
        if (t == null) t = Combat.targets(p, 16, 45, 1).stream().findFirst().orElse(null);
        if (t == null) { JobSkills.bar(p, "주변에 공격할 대상이 없습니다", Formatting.RED); return false; }
        final LivingEntity tt = t;
        clapThen(p, () -> {
            Double gy = AlchemyFx.ground(p.getServerWorld(), p.getPos());
            StonePillarFx.launch(p, tt, gy == null ? p.getY() : gy, v -> Combat.hit(p, v, PILLAR));
        });
        return true;
    }

    // ①' 각성: 거대한 바위 주먹 — 옆 땅에서 큰 주먹이 솟아 후려치고, 그 줄을 따라 기둥이 줄지어 솟음
    private static boolean giantFist(ServerPlayerEntity p) {
        Vec3d at = frontSpot(p, 6, 16);
        clapThen(p, () -> {
            ServerWorld w = p.getServerWorld();
            Vec3d dir = Combat.horizontal(at.subtract(p.getPos()));
            double len = Math.max(2, at.subtract(p.getPos()).horizontalLength());
            for (int i = 1; i <= (int) len; i++) {
                final Vec3d q = p.getPos().add(dir.multiply(i));
                Tasks.later(i, () -> { Double g = AlchemyFx.ground(w, q); if (g != null) AlchemyFx.pillar(w, q.x, g, q.z, 0.8f, 1.2f + 0.15f * (float) Math.random(), 18); });
            }
            Tasks.later((int) len, () -> {
                Double g = AlchemyFx.ground(w, at);
                double y = g == null ? at.y : g;
                Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0)).normalize().multiply(1.4);
                AlchemyFx.display(w, Blocks.TUFF_BRICKS.getDefaultState(), at.x - side.x, y, at.z - side.z, 2.2f, 2.6f, 2.2f, 0, 20, 2);
                w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.4f, 0.4f);
                for (Entity en : w.getOtherEntities(p, new Box(at, at).expand(2.2, 2.5, 2.2), x -> Targets.canHit(p, x)))
                    Combat.hit(p, (LivingEntity) en, GIANT_FIST);
                Fx.word(w, at.add(0, 2.6, 0), Fx.BLUNT, 1.3f);
                Fx.debris(w, at, 18, 1.4);
                Fx.shakeAround(w, at, 10, 0.6f);
            });
        });
        return true;
    }

    // ② 무기 연성: 처음 = 창을 뽑음 (20초 창 평타) / 다시 = 돌진 찌르기로 마무리
    private static final Map<UUID, Long> SPEAR_UNTIL = new HashMap<>();
    private static final Map<UUID, Integer> SPEAR_SLOT = new HashMap<>();
    private static final int SPEAR_TICKS = 400, WEAPON_SKILL = 1;

    static boolean spearOn(ServerPlayerEntity p) { return SPEAR_UNTIL.getOrDefault(p.getUuid(), 0L) > Combat.now(); }

    private static boolean weaponForge(ServerPlayerEntity p) {
        if (spearOn(p)) return spearThrust(p);
        Motion.play(p, "al_forge");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(3, () -> clapFx(p));
        Tasks.later(startup(p, 10), () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            SPEAR_UNTIL.put(p.getUuid(), Combat.now() + SPEAR_TICKS);
            // 보이는 창: 지금 든 칸(클래스 칸)이 비어 있으면 그 칸에 (무기 칸이 아니라서 평타는 직업 세트 = 창)
            int sel = p.getInventory().selectedSlot;
            if (sel != 1 && sel != 2 && p.getInventory().getStack(sel).isEmpty()) {
                p.getInventory().setStack(sel, new ItemStack(AlchemyItems.ALCHEMY_SPEAR));
                SPEAR_SLOT.put(p.getUuid(), sel);
            }
            Vec3d c = p.getPos();
            for (int i = 0; i < 6; i++) {
                final int k = i;
                Tasks.later(i, () -> w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + k * 0.35, c.z, 4, 0.12, 0.1, 0.12, 0.1));
            }
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), c.x, c.y + 0.1, c.z, 25, 0.5, 0.1, 0.5, 0.2);
            w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 0.8f);
            JobSkills.bar(p, "⚒ 무기 연성 — 20초 동안 창 평타 (다시 누르면 돌진 찌르기)", Formatting.GOLD);
            UUID id = p.getUuid();
            Tasks.later(SPEAR_TICKS, () -> { if (SPEAR_UNTIL.getOrDefault(id, 0L) <= Combat.now() + 1) endSpear(p, true); });
        });
        // 뽑는 것만으로는 재사용이 안 돌게 (찌르기·시간이 끝날 때 돎)
        Tasks.later(1, () -> JobSkills.clearCooldown(p.getUuid(), WEAPON_SKILL));
        return true;
    }

    private static boolean spearThrust(ServerPlayerEntity p) {
        Motion.play(p, "sp_thrust");
        Combat.afterSkill(p, 10);
        Vec3d look = Combat.horizontal(p.getRotationVec(1f));
        Combat.move(p, new Vec3d(look.x * 0.9, 0.05, look.z * 0.9));
        Tasks.later(5, () -> {
            for (LivingEntity t : Combat.targets(p, 3.6, 40, 2)) Combat.hit(p, t, THRUST);
            p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 1.0f, 0.9f);
            endSpear(p, false);
        });
        return true;
    }

    private static void endSpear(ServerPlayerEntity p, boolean timeout) {
        SPEAR_UNTIL.remove(p.getUuid());
        Integer slot = SPEAR_SLOT.remove(p.getUuid());
        if (slot != null && p.getInventory().getStack(slot).isOf(AlchemyItems.ALCHEMY_SPEAR)) p.getInventory().setStack(slot, ItemStack.EMPTY);
        for (int i = 0; i < p.getInventory().size(); i++) if (p.getInventory().getStack(i).isOf(AlchemyItems.ALCHEMY_SPEAR)) p.getInventory().setStack(i, ItemStack.EMPTY);
        JobSkills.setCooldown(p.getUuid(), WEAPON_SKILL, System.currentTimeMillis() + 12_000L);
        if (!p.isDisconnected()) {
            p.getServerWorld().spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.getDefaultState()), p.getX(), p.getY() + 1.2, p.getZ(), 20, 0.4, 0.4, 0.4, 0.2);
            if (timeout) JobSkills.bar(p, "연성한 창이 부서졌습니다", Formatting.GRAY);
        }
    }

    // ②' 각성: 신의 양손 — 거대한 바위 손 둘이 상대를 감싸 짓눌러 다운
    private static boolean godHands(ServerPlayerEntity p) {
        LivingEntity t = aimTarget(p, 20);
        if (t == null) t = Combat.targets(p, 14, 45, 1).stream().findFirst().orElse(null);
        if (t == null) { JobSkills.bar(p, "주변에 공격할 대상이 없습니다", Formatting.RED); return false; }
        final LivingEntity tt = t;
        clapThen(p, () -> {
            ServerWorld w = p.getServerWorld();
            Vec3d at = tt.getPos();
            Double gy = AlchemyFx.ground(w, at);
            double y = gy == null ? at.y : gy;
            Vec3d side = Combat.horizontal(at.subtract(p.getPos())).crossProduct(new Vec3d(0, 1, 0)).normalize().multiply(1.5);
            GodHandFx.play(w, at.add(side), y, 8, 28);
            GodHandFx.play(w, at.subtract(side), y, 8, 28);
            Tasks.later(8, () -> { if (tt.isAlive()) { Combat.hit(p, tt, GOD_HAND); hold(tt, 20); } });
            Tasks.later(24, () -> {
                if (!tt.isAlive()) return;
                Combat.hit(p, tt, CRUSH);
                Fx.word(w, tt.getPos().add(0, 2.2, 0), Fx.SLAM, 1.3f);
                Fx.debris(w, tt.getPos(), 16, 1.2);
            });
        });
        return true;
    }

    // ③ 가시: 앞 땅에서 가시 무더기가 솟아 띄움 (가드 깨기)
    private static boolean spikes(ServerPlayerEntity p) {
        Vec3d at = frontSpot(p, 3.5, 7);
        clapThen(p, () -> spikeCluster(p, at, 2.2, 9, SPIKES, 2.2f));
        return true;
    }

    private static void spikeCluster(ServerPlayerEntity p, Vec3d at, double r, int n, Hit h, float height) {
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + Math.random() * 0.4, d = i == 0 ? 0 : r * (0.4 + Math.random() * 0.6);
            double x = at.x + Math.cos(a) * d, z = at.z + Math.sin(a) * d;
            Double g = AlchemyFx.ground(w, new Vec3d(x, at.y, z));
            if (g != null) AlchemyFx.spike(w, x, g, z, 0.8f, height * (0.7f + 0.5f * (float) Math.random()), 22);
        }
        for (Entity en : w.getOtherEntities(p, new Box(at, at).expand(r + 0.4, 2.0, r + 0.4), x -> Targets.canHit(p, x)))
            Combat.hit(p, (LivingEntity) en, h);
        w.spawnParticles(ParticleTypes.CLOUD, at.x, at.y + 0.3, at.z, 12, r * 0.6, 0.2, r * 0.6, 0.02);
    }

    // ③' 각성: 가시 숲 — 넓게 가시가 연달아 솟아 4번 때리고 마지막에 크게 띄움
    private static boolean spikeForest(ServerPlayerEntity p) {
        Vec3d at = frontSpot(p, 4, 9);
        clapThen(p, () -> {
            for (int k = 0; k < 4; k++) {
                final int kk = k;
                Tasks.later(k * 4, () -> spikeCluster(p, at, 3.2, 10, FOREST_TICK, 1.8f + kk * 0.3f));
            }
            Tasks.later(18, () -> {
                spikeCluster(p, at, 1.6, 7, FOREST_END, 4.0f);
                ServerWorld w = p.getServerWorld();
                Fx.rings(w, at.add(0, 0.3, 0), new Vec3d(0, 1, 0), 3f, 2);
                Fx.word(w, at.add(0, 3.2, 0), Fx.Sfx.ZUDON, 1.4f);
            });
        });
        return true;
    }

    // ④ 벽: 앞 땅에서 벽이 솟아 위에 선 상대를 띄우고 4초 동안 길을 막음
    private static boolean wall(ServerPlayerEntity p) {
        Vec3d spot = frontSpot(p, 4, 7);
        clapThen(p, () -> {
            ServerWorld w = p.getServerWorld();
            Vec3d dir = Combat.horizontal(p.getRotationVec(1f));
            Vec3d side = dir.crossProduct(new Vec3d(0, 1, 0)).normalize();
            Double g0 = AlchemyFx.ground(w, spot);
            double gy = g0 == null ? spot.y : g0;
            for (Entity en : w.getOtherEntities(p, new Box(spot.x - 2.6, gy - 0.5, spot.z - 2.6, spot.x + 2.6, gy + 2.5, spot.z + 2.6), x -> Targets.canHit(p, x)))
                Combat.hit(p, (LivingEntity) en, WALL);
            BlockState wallState = Blocks.TUFF_BRICKS.getDefaultState();
            List<BlockPos> placed = new ArrayList<>();
            for (int s2 = -2; s2 <= 2; s2++) {
                for (int y = 0; y < 3; y++) {
                    BlockPos bp = BlockPos.ofFloored(spot.x + side.x * s2, gy + y, spot.z + side.z * s2);
                    if (!w.getBlockState(bp).isReplaceable()) continue;
                    placed.add(bp);
                    final int yy = y;
                    Tasks.later(2 + yy, () -> {   // 띄운 상대가 떠오른 뒤에 쌓음
                        if (!w.getBlockState(bp).isReplaceable() || !w.getOtherEntities(null, new Box(bp)).isEmpty()) return;
                        w.setBlockState(bp, wallState);
                        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wallState), bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 5, 0.3, 0.3, 0.3, 0.05);
                    });
                }
            }
            w.playSound(null, BlockPos.ofFloored(spot), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 1.0f, 0.5f);
            Tasks.later(80, () -> {
                for (BlockPos bp : placed) if (w.getBlockState(bp).isOf(wallState.getBlock())) {
                    w.setBlockState(bp, Blocks.AIR.getDefaultState());
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, wallState), bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.05);
                }
            });
        });
        return true;
    }

    // ④' 각성: 오토메일 일격 — 깊게 내딛으며 강철 주먹, 가드 깨기 + 멀리 날림
    private static boolean steelPunch(ServerPlayerEntity p) {
        Motion.play(p, "al_punch");
        Combat.afterSkill(p, 12);
        Vec3d look = Combat.horizontal(p.getRotationVec(1f));
        Tasks.later(3, () -> Combat.move(p, new Vec3d(look.x * 0.8, 0.02, look.z * 0.8)));
        Tasks.later(6, () -> {
            ServerWorld w = p.getServerWorld();
            List<LivingEntity> ts = Combat.targets(p, 3.4, 45, 2);
            for (LivingEntity t : ts) Combat.hit(p, t, STEEL_PUNCH);
            if (!ts.isEmpty()) {
                Vec3d at = ts.get(0).getPos().add(0, 1, 0);
                Fx.word(w, at.add(0, 1.6, 0), Fx.Sfx.DOGOOON, 1.4f);
                Fx.burst(w, at, look, 1.4f);
                Fx.shakeAround(w, at, 10, 0.7f);
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.8f, 0.7f);
        });
        return true;
    }

    // ⑤ 신의 손: 바위 손이 움켜쥐어 30틱 잡기
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
        Tasks.later(startup(p, 6), () -> {
            if (!t.isAlive()) return;
            Vec3d at = t.getPos();
            Double gy = AlchemyFx.ground(w, at);
            GodHandFx.play(w, at, gy == null ? at.y : gy, 8, 34);
            Tasks.later(8, () -> { if (t.isAlive()) { Combat.hit(p, t, GOD_HAND); hold(t, 30); } });
        });
        clapFx(p);
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
        Motion.play(p, "al_clap");
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.8f, 0.6f);
        for (int wave = 0; wave < 3; wave++) {
            final double r = 2.5 + wave * 2;
            Tasks.later(startup(p, 6) + wave * 6, () -> {
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

    // ═════════ 각성 · 각성기 · 임플란트 ═════════

    @Override
    public void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        clapFx(p);
        // ABA: 붉은 코트가 찢겨 날아가고 오토메일 팔이 드러남 → 붉은·검은 천 조각이 흩날림
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.75f, 0.08f, 0.08f), 1.6f), p.getX(), p.getY() + 1.2, p.getZ(), 40, 0.5, 0.6, 0.5, 0.3);
        w.spawnParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1.2, p.getZ(), 20, 0.5, 0.5, 0.5, 0.15);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 1.4, p.getZ(), 20, 0.3, 0.4, 0.3, 0.3);
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
        // 무기 연성 중에 나가면 창 정리
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((h, srv) -> {
            if (SPEAR_UNTIL.containsKey(h.getPlayer().getUuid())) endSpear(h.getPlayer(), false);
        });
    }
}
