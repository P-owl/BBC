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
import bbc.net.CutscenePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
 * 대기: 옆으로 서서 왼팔을 상대 쪽으로 뻗고 오른 주먹(오토메일)은 가슴 앞. 걸을 땐 팔만, 달리면 달리기 모션 (클라이언트 Anim)
 * 평타 (다른 게임의 에드워드 평타 영상 프레임 분석): 오토메일 칼날 사선 베기 → 왼손 올려 훅 → 오른 스트레이트 → 돌며 올려차기
 *   → 손뼉·땅 짚고 쳐올리기(바위 기둥이 솟아 날려보냄). 띄우기 = 땅 짚고 올려치기(가시)
 * 기술 (ABA: 1 Pillar · 2 Transmute Weapon · 3 Spikes · 4 Wall) + 고를 거리 대지 붕괴
 * 각성 '손뼉 연성' (컷신: 오토메일 팔 클로즈업 → 코트가 찢겨 날아감) → 기술 칸이 통째로 바뀜 (HUD 이름도)
 *   창조의 기둥 · 신의 손 · 가시밭 · 최후의 일격 (ABA: Pillars of Creation · Hand of God · Spike Field · Final Punch)
 * 각성기 '대지의 심판' (컷신): 땅을 내리치면 대상마다 양옆에서 거대한 바위 손이 솟아 박수로 짓누름. 현자의 돌 → 붉은 연성 (각성 30초 연장 · 재사용 초기화)
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

    /** 5타 = 땅을 짚은 자리에 연성진 + 상대 발밑에서 바위 기둥, 띄우기 = 가시 (연출만, 판정은 평타 그대로) */
    @Override
    public void m1Fx(ServerPlayerEntity p, String motion) {
        if (!motion.startsWith("al_")) return;
        ServerWorld w = p.getServerWorld();
        Vec3d at = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(1.8));
        Double gy = AlchemyFx.ground(w, at);
        if (gy == null) return;
        switch (motion) {
            case "al_5" -> { Alchemy.fx(w, p.getBlockPos(), false); AlchemyFx.pillar(w, at.x, gy, at.z, 0.9f, 2.2f, 14); Fx.debris(w, at, 8, 0.8); }
            case "al_rise" -> AlchemyFx.spike(w, at.x, gy, at.z, 0.6f, 2.0f, 12);
            default -> { }
        }
    }

    // ═════════ 기술 ═════════
    // ABA 에드워드 (영상 분석): 평소 1 Pillar · 2 Transmute Weapon · 3 Spikes · 4 Wall
    //   각성하면 카드가 통째로 바뀜 → 1 Pillars of Creation · 2 Hand of God(가드 깨기) · 3 Spike Field(가드 깨기) · 4 Final Punch(가드 깨기)
    // 같은 칸 번호 = 같은 재사용 시간 (각성 전에 쓴 기술은 각성판도 재사용 중)

    private static final JobSkills.Skill[] BASE = {
            new JobSkills.Skill("기둥", "기둥", 1, 20, 8, "손뼉 → 바위 기둥이 땅을 타고 상대를 쫓아가 들이받음 (각성 → 창조의 기둥)"),
            new JobSkills.Skill("무기 연성", "창", 2, 25, 12, "땅에서 창을 뽑아 20초 동안 창 평타 (피해 ×1.5). 한 번 더 누르면 돌진 찌르기로 마무리 (각성 → 신의 손)"),
            new JobSkills.Skill("가시", "가시", 1, 25, 10, "앞 땅에서 가시 무더기가 솟아 띄움, 가드 깨기 (각성 → 가시밭)"),
            new JobSkills.Skill("벽", "벽", 2, 20, 12, "상대 발밑에서 벽이 솟아 띄우고, 4초 동안 길을 막음 (각성 → 최후의 일격)"),
            new JobSkills.Skill("대지 붕괴", "붕괴", 4, 50, 45, "주위로 가시 물결 세 번, 가드 깨기 (광역)"),
    };
    private static final JobSkills.Skill[] AWAKE = {
            new JobSkills.Skill("창조의 기둥", "창조", 1, 30, 10, "[각성] 손바닥을 내밀면 거대한 바위 팔이 땅에서 뻗어 나가 주먹으로 날려버림, 옆에 바위 기둥이 솟음"),
            new JobSkills.Skill("신의 손", "신의손", 2, 35, 14, "[각성] 상대 양옆에서 거대한 바위 손 둘이 솟아 박수로 가두고 짓눌러 다운, 가드 깨기"),
            new JobSkills.Skill("가시밭", "가시밭", 1, 35, 14, "[각성] 충격파와 먼지 속 연타 5번 → 거대한 가시 숲이 솟아 하늘 높이 띄움, 가드 깨기"),
            new JobSkills.Skill("최후의 일격", "일격", 2, 40, 20, "[각성] 오토메일 주먹을 끝까지 당겼다가 꽂는 일격, 가드 깨기 · 멀리 날림"),
            BASE[4],
    };

    private static final Hit PILLAR = new Hit(3.0f, Hit.Kind.KNOCK, 16, 1.3, 0.35, 20, false, false);
    private static final Hit THRUST = new Hit(2.9f, Hit.Kind.HIT, 20, 0.3, 0, 25, false, false);
    private static final Hit SPIKES = new Hit(2.6f, Hit.Kind.AIR, 26, 0.1, 0.95, 30, true, false);
    private static final Hit WALL = new Hit(2.2f, Hit.Kind.AIR, 30, 0, 1.05, 20, false, false);
    private static final Hit COLLAPSE = new Hit(3.6f, Hit.Kind.AIR, 18, 0.2, 0.55, 25, true, false);
    // 각성판 (ABA 수치 ÷ 4.1 ≈ 우리 평타 1.0 기준, 큰 건 조금 깎음)
    private static final Hit CREATION = new Hit(4.5f, Hit.Kind.KNOCK, 24, 2.3, 0.55, 40, false, false);
    private static final Hit HAND_CLAP = new Hit(1.6f, Hit.Kind.HIT, 30, 0, 0, 30, true, false);
    private static final Hit HAND_CRUSH = new Hit(4.0f, Hit.Kind.DOWN, 24, 0, -1.2, 40, true, true);
    private static final Hit FIELD_TICK = new Hit(1.0f, Hit.Kind.HIT, 12, 0, 0, 12, false, false);
    private static final Hit FIELD_END = new Hit(5.0f, Hit.Kind.AIR, 34, 0.05, 1.7, 50, true, false);
    private static final Hit FINAL_PUNCH = new Hit(9.0f, Hit.Kind.KNOCK, 30, 3.2, 0.55, 80, true, false);
    private static final Hit JUDGMENT = new Hit(6.0f, Hit.Kind.DOWN, 30, 0.1, -1.3, 60, true, true);

    private static boolean awake(ServerPlayerEntity p) { return Awaken.active(p); }

    /** 기술 칸 저장 형식이 바뀌었을 때(예전 7·6가지 기술 순서) 장착을 처음 상태로 되돌림 */
    private static final String EQUIP_VER = "3";

    private static final JobSkills.Set SET = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, PlayerData.Entry e) {
            if (!EQUIP_VER.equals(e.str(PREFIX + "eqv"))) {
                e.str(PREFIX + "eqv", EQUIP_VER);
                e.str(PREFIX + "equip", "");
                Jobs.dirty(p);
            }
            return awake(p) ? AWAKE : BASE;   // 각성 중엔 HUD·기술 목록 이름도 각성판으로
        }

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
                case 0 -> aw ? creation(p) : pillar(p);
                case 1 -> aw ? handOfGod(p) : weaponForge(p);
                case 2 -> aw ? spikeField(p) : spikes(p);
                case 3 -> aw ? finalPunch(p) : wall(p);
                case 4 -> earthCollapse(p);
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

    private static LivingEntity pickTarget(ServerPlayerEntity p, double aim, double near) {
        LivingEntity t = aimTarget(p, aim);
        return t != null ? t : Combat.targets(p, near, 45, 1).stream().findFirst().orElse(null);
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

    // ①' 창조의 기둥: 손뼉 → 손바닥을 내밀면 발 앞 땅에서 거대한 바위 팔이 솟아 일직선으로 뻗어 나가고 끝의 주먹이 들이받음.
    //    ABA 처럼 시전자 양옆에 바위 기둥도 솟음 (잠깐 몸을 가려 줌)
    private static boolean creation(ServerPlayerEntity p) {
        LivingEntity t = pickTarget(p, 22, 18);
        Vec3d look = Combat.horizontal(p.getRotationVec(1f));
        Vec3d dir = t != null ? Combat.horizontal(t.getPos().subtract(p.getPos())) : look;
        if (dir.lengthSquared() < 1e-4) dir = look;
        final Vec3d d = dir.normalize();
        double len = t == null ? 14 : Math.max(4, Math.min(20, t.getPos().subtract(p.getPos()).horizontalLength() + 2));
        Motion.play(p, "al_creation");
        Combat.afterSkill(p, 14);
        ServerWorld w = p.getServerWorld();
        Tasks.later(3, () -> w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.9f));
        Tasks.later(6, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            clapFx(p);
            Double g = AlchemyFx.ground(w, p.getPos());
            double gy = g == null ? p.getY() : g;
            Vec3d side = d.crossProduct(new Vec3d(0, 1, 0)).normalize();
            // 양옆 기둥
            for (int s = -1; s <= 1; s += 2) {
                Vec3d q = p.getPos().add(side.multiply(1.8 * s)).add(d.multiply(-0.4));
                Double gq = AlchemyFx.ground(w, q);
                if (gq != null) AlchemyFx.pillar(w, q.x, gq, q.z, 1.1f, 2.8f, 50);
            }
            // 바위 팔: 땅에서 솟아 가슴 높이로 → 틱마다 2칸씩 뻗음. 주먹은 끝을 따라감
            Vec3d root = new Vec3d(p.getX() + d.x * 1.0, gy - 0.6, p.getZ() + d.z * 1.0);
            List<Entity> parts = new ArrayList<>();
            Set<UUID> hit = new HashSet<>();
            int steps = (int) Math.ceil(len / 2.0);
            Vec3d[] tip = {root};
            Entity[] fist = {null};
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.4f, 1.2f);
            for (int i = 1; i <= steps; i++) {
                final int k = i;
                Tasks.later(i - 1, () -> {
                    Vec3d from = tip[0];
                    double along = Math.min(len, 1.0 + k * 2.0);
                    Vec3d to = new Vec3d(p.getX() + d.x * along, gy + 1.15, p.getZ() + d.z * along);
                    if (k == 1) to = new Vec3d(root.x + d.x * 1.6, gy + 1.15, root.z + d.z * 1.6);
                    Entity seg = StonePillarFx.segment(w, from, to, Blocks.POLISHED_ANDESITE.getDefaultState(), 1.15f);
                    if (seg != null) parts.add(seg);
                    tip[0] = to;
                    if (fist[0] == null) { fist[0] = StonePillarFx.segment(w, to, to.add(d.multiply(1.5)), Blocks.STONE.getDefaultState(), 2.0f); if (fist[0] != null) parts.add(fist[0]); }
                    else fist[0].requestTeleport(to.x, to.y, to.z);
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), to.x, gy + 0.2, to.z, 10, 0.6, 0.1, 0.6, 0.2);
                    w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 4, 0.5, 0.5, 0.5, 0.1);
                    Box box = new Box(from, to.add(d.multiply(1.6))).expand(1.3, 1.2, 1.3);
                    for (Entity en : w.getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
                        if (!hit.add(en.getUuid())) continue;
                        LivingEntity v = (LivingEntity) en;
                        Combat.hit(p, v, CREATION);
                        Vec3d c = v.getBoundingBox().getCenter();
                        Fx.word(w, c.add(0, 1.4, 0), Fx.BLUNT, 1.4f);
                        Fx.burst(w, c, d, 1.6f);
                        Fx.debris(w, v.getPos(), 14, 1.2);
                        Fx.shakeAround(w, c, 10, 0.7f);
                        w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.5f, 0.4f);
                    }
                    if (k % 2 == 1) w.playSound(null, BlockPos.ofFloored(to), SoundEvents.BLOCK_DEEPSLATE_PLACE, SoundCategory.PLAYERS, 1.2f, 0.6f);
                });
            }
            // 무너짐
            Tasks.later(steps + 22, () -> {
                for (Entity e : parts) {
                    if (e == null || e.isRemoved()) continue;
                    w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.POLISHED_ANDESITE.getDefaultState()), e.getX(), e.getY() + 0.5, e.getZ(), 12, 0.6, 0.6, 0.6, 0.1);
                    e.discard();
                }
                w.playSound(null, BlockPos.ofFloored(tip[0]), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.4f, 0.5f);
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

    // ②' 신의 손: 손뼉 → 두 팔을 벌리면 상대 양옆에서 거대한 바위 손 둘이 솟고 → 두 손을 맞부딪치는 순간 박수로 가둠 → 짓눌러 다운 (가드 깨기)
    private static boolean handOfGod(ServerPlayerEntity p) {
        LivingEntity t = pickTarget(p, 22, 16);
        if (t == null) { JobSkills.bar(p, "주변에 공격할 대상이 없습니다", Formatting.RED); return false; }
        Motion.play(p, "al_godhand");
        Combat.afterSkill(p, 16);
        ServerWorld w = p.getServerWorld();
        Tasks.later(3, () -> clapFx(p));
        Tasks.later(4, () -> {
            if (p.isDisconnected() || !p.isAlive() || !t.isAlive()) return;
            Vec3d at = t.getPos();
            Double g = AlchemyFx.ground(w, at);
            double gy = g == null ? at.y : g;
            Vec3d s = Combat.horizontal(at.subtract(p.getPos())).crossProduct(new Vec3d(0, 1, 0)).normalize();
            GodHandFx.play(w, at, gy, 6, 14, Math.atan2(-s.z, -s.x));
            GodHandFx.play(w, at, gy, 6, 14, Math.atan2(s.z, s.x));
            hold(t, 18);
            Alchemy.fx(w, BlockPos.ofFloored(at), Alch.entry(p).truth);
        });
        Tasks.later(10, () -> {
            if (!t.isAlive()) return;
            Combat.hit(p, t, HAND_CLAP);
            Vec3d c = t.getBoundingBox().getCenter();
            Fx.rings(w, c, Combat.horizontal(c.subtract(p.getPos())).crossProduct(new Vec3d(0, 1, 0)), 4f, 3);
            Fx.wave(w, c, 0.4, 2.6, Fx.WHITE, 6);
            Fx.shakeAround(w, c, 12, 0.8f);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.8f, 1.5f);
        });
        Tasks.later(20, () -> {
            if (!t.isAlive()) return;
            Combat.hit(p, t, HAND_CRUSH);
            Fx.word(w, t.getPos().add(0, 2.2, 0), Fx.SLAM, 1.4f);
            Fx.debris(w, t.getPos(), 18, 1.4);
            Fx.smoke(w, t.getPos(), 20, 1.4);
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

    // ③' 가시밭: 손뼉·땅 짚기 → 상대 자리에 충격파 + 먼지 속에서 작은 가시가 5번 연달아 찌르고 →
    //    거대한 가시 숲이 솟아 하늘 높이 띄움 (가드 깨기). 마지막엔 시전자 뒤에서 올려다보는 짧은 컷신
    private static boolean spikeField(ServerPlayerEntity p) {
        Vec3d at0 = frontSpot(p, 4, 10);
        Motion.play(p, "al_clap");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(3, () -> w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.7f, 1.9f));
        Tasks.later(6, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            clapFx(p);
            Double g = AlchemyFx.ground(w, at0);
            Vec3d at = new Vec3d(at0.x, g == null ? at0.y : g, at0.z);
            Fx.wave(w, at.add(0, 0.15, 0), 0.5, 4.2, Fx.WHITE, 8);
            Fx.rings(w, at.add(0, 0.3, 0), new Vec3d(0, 1, 0), 4f, 2);
            Alchemy.fx(w, BlockPos.ofFloored(at), Alch.entry(p).truth);
            w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.6f, 1.4f);
            for (int k = 0; k < 5; k++) {
                Tasks.later(k * 3, () -> {
                    Fx.smoke(w, at.add(0, 0.4, 0), 14, 2.4);
                    for (int i = 0; i < 4; i++) {
                        double a = Math.random() * Math.PI * 2, r = Math.random() * 2.6;
                        double x = at.x + Math.cos(a) * r, z = at.z + Math.sin(a) * r;
                        Double gg = AlchemyFx.ground(w, new Vec3d(x, at.y, z));
                        if (gg != null) AlchemyFx.spike(w, x, gg, z, 0.5f, 1.4f + (float) Math.random(), 8);
                    }
                    for (Entity en : w.getOtherEntities(p, new Box(at, at).expand(3.0, 2.5, 3.0), x -> Targets.canHit(p, x)))
                        Combat.hit(p, (LivingEntity) en, FIELD_TICK);
                });
            }
            Tasks.later(16, () -> {
                LivingEntity first = null;
                for (int i = 0; i < 18; i++) {
                    double a = Math.PI * 2 * i / 18 + Math.random() * 0.3, r = i < 4 ? Math.random() * 0.8 : 1.0 + Math.random() * 2.8;
                    double x = at.x + Math.cos(a) * r, z = at.z + Math.sin(a) * r;
                    Double gg = AlchemyFx.ground(w, new Vec3d(x, at.y, z));
                    if (gg != null) AlchemyFx.spike(w, x, gg, z, 1.0f + (float) Math.random() * 0.7f, 3.6f + (float) Math.random() * 2.6f, 40);
                }
                for (Entity en : w.getOtherEntities(p, new Box(at, at).expand(3.2, 3.0, 3.2), x -> Targets.canHit(p, x))) {
                    if (Combat.hit(p, (LivingEntity) en, FIELD_END) == Combat.Result.HIT && first == null) first = (LivingEntity) en;
                }
                Fx.word(w, at.add(0, 3.4, 0), Fx.Sfx.ZUDON, 1.6f);
                Fx.debris(w, at, 26, 2.2);
                Fx.shakeAround(w, at, 14, 1.0f);
                w.playSound(null, BlockPos.ofFloored(at), SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 2.0f, 0.4f);
                w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.8f, 0.7f);
                if (first != null) {
                    Combat.invuln(p, 20);
                    cutscene(p, "al_forest", first, List.of(p));
                }
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

    // ④' 최후의 일격: 오토메일 주먹을 끝까지 뒤로 당겨 몸을 꼬고(스파크가 모임) → 깊게 내딛으며 꽂음. 가드 깨기, 멀리 날림 (선딜 8)
    private static boolean finalPunch(ServerPlayerEntity p) {
        Motion.play(p, "al_punch");
        Combat.afterSkill(p, 20);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.PLAYERS, 0.8f, 1.6f);
        for (int i = 1; i <= 6; i += 2) {
            Tasks.later(i, () -> {
                Vec3d back = Combat.horizontal(p.getRotationVec(1f)).multiply(-0.5);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX() + back.x, p.getY() + 1.2, p.getZ() + back.z, 6, 0.25, 0.25, 0.25, 0.15);
            });
        }
        Tasks.later(7, () -> { Vec3d l = Combat.horizontal(p.getRotationVec(1f)); Combat.move(p, new Vec3d(l.x * 1.1, 0.02, l.z * 1.1)); });
        Tasks.later(8, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            Vec3d look = Combat.horizontal(p.getRotationVec(1f));
            List<LivingEntity> ts = Combat.targets(p, 3.8, 50, 3);
            for (LivingEntity t : ts) {
                if (Combat.hit(p, t, FINAL_PUNCH) != Combat.Result.HIT) continue;
                Vec3d c = t.getBoundingBox().getCenter();
                w.spawnParticles(ParticleTypes.SONIC_BOOM, c.x, c.y, c.z, 1, 0, 0, 0, 0);
                Fx.rings(w, c, look, 5f, 3);
                Fx.wave(w, c, 0.4, 3.2, Fx.WHITE, 7);
                Fx.word(w, c.add(0, 1.5, 0), Fx.Sfx.DOGOOON, 1.7f);
                Fx.burst(w, c, look, 2.6f);
                Fx.impactFlash(w, c, 1.6f);
                Fx.lines(t, 1.2f);
                Fx.streak(t, 12);
                Fx.smoke(w, t.getPos(), 20, 1.6);
            }
            if (!ts.isEmpty()) { Fx.lines(p, 1.0f); Fx.flash(p, 0.3f); Fx.shakeAround(w, p.getPos(), 14, 1.1f); }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.9f, 1.1f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.8f, 0.6f);
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

    /** 컷신 길이 (클라이언트 Cutscene 대본과 맞춤) */
    private static final int AWAKEN_SCENE = 40, ULT_SCENE = 44;

    static void cutscene(ServerPlayerEntity actor, String shot, LivingEntity target, List<ServerPlayerEntity> to) {
        CutscenePayload pl = new CutscenePayload(shot, actor.getId(), target == null ? -1 : target.getId());
        for (ServerPlayerEntity v : to)
            if (ServerPlayNetworking.canSend(v, CutscenePayload.ID)) ServerPlayNetworking.send(v, pl);
    }

    @Override
    public void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        // ABA: 오른 어깨 너머로 오토메일 팔 클로즈업 → 주먹을 쥐는 순간 코트가 검은 조각으로 찢겨 날아가고 오토메일이 드러남
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "al_awaken");
        cutscene(p, "al_awaken", null, List.of(p));
        Combat.invuln(p, AWAKEN_SCENE);
        Combat.afterSkill(p, AWAKEN_SCENE - 6);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 0.7f);
        for (int t = 2; t < 15; t += 2) {
            final int tt = t;
            Tasks.later(t, () -> {
                if (p.isDisconnected()) return;
                Vec3d r = rightSide(p, 0.45);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, r.x, p.getY() + 1.3, r.z, 2 + tt / 3, 0.15, 0.2, 0.15, 0.08);
                if (tt % 6 == 0) w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 0.7f, 1.4f);
            });
        }
        Tasks.later(16, () -> { if (!p.isDisconnected()) coatBurst(p); });
        Tasks.later(AWAKEN_SCENE - 6, () -> {
            if (!p.isDisconnected() && awake(p))
                JobSkills.bar(p, "✦ 손뼉 연성 — 기술이 바뀜: 창조의 기둥 · 신의 손 · 가시밭 · 최후의 일격  (G = 대지의 심판)", Formatting.AQUA);
        });
    }

    /** 내 오른쪽 어깨 쪽 위치 */
    private static Vec3d rightSide(ServerPlayerEntity p, double d) {
        double yr = Math.toRadians(p.getBodyYaw());
        return new Vec3d(p.getX() - Math.cos(yr) * d, p.getY(), p.getZ() - Math.sin(yr) * d);
    }

    /** 코트가 찢겨 날아감: 검은 천 조각(블록 디스플레이)이 사방으로 흩어지고 붉은·검은 먼지 */
    private static void coatBurst(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos().add(0, 1.1, 0);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 14; i++) {
            Entity sh = GodHandFx.display(w, (i % 4 == 0 ? Blocks.RED_WOOL : Blocks.BLACK_WOOL).getDefaultState(), c.x, c.y - 0.4 + r.nextDouble() * 0.9, c.z);
            if (sh == null) continue;
            float sx = 0.18f + r.nextFloat() * 0.25f, sz = 0.14f + r.nextFloat() * 0.2f;
            org.joml.Quaternionf q0 = new org.joml.Quaternionf().rotateXYZ(r.nextFloat() * 6.28f, r.nextFloat() * 6.28f, r.nextFloat() * 6.28f);
            GodHandFx.transform(sh, q0, new org.joml.Vector3f(-sx / 2, 0, -sz / 2), new org.joml.Vector3f(sx, 0.04f, sz), 0);
            double a = r.nextDouble() * Math.PI * 2;
            org.joml.Vector3f to = new org.joml.Vector3f((float) (Math.cos(a) * (1.6 + r.nextDouble() * 1.6)), (float) (0.4 + r.nextDouble() * 1.6), (float) (Math.sin(a) * (1.6 + r.nextDouble() * 1.6)));
            org.joml.Quaternionf q1 = new org.joml.Quaternionf(q0).rotateXYZ(r.nextFloat() * 9f, r.nextFloat() * 9f, r.nextFloat() * 9f);
            Tasks.later(1, () -> GodHandFx.transform(sh, q1, to, new org.joml.Vector3f(sx * 0.6f, 0.03f, sz * 0.6f), 10));
            Tasks.later(12 + r.nextInt(5), sh::discard);
        }
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BLACK_WOOL.getDefaultState()), c.x, c.y, c.z, 50, 0.4, 0.5, 0.4, 0.5);
        w.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.06f, 0.06f, 0.08f), 2.0f), c.x, c.y, c.z, 40, 0.6, 0.7, 0.6, 0.3);
        w.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.75f, 0.08f, 0.08f), 1.6f), c.x, c.y, c.z, 24, 0.6, 0.6, 0.6, 0.3);
        w.spawnParticles(ParticleTypes.SQUID_INK, c.x, c.y, c.z, 24, 0.3, 0.4, 0.3, 0.3);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.2, c.z, 30, 0.4, 0.5, 0.4, 0.35);
        Fx.rings(w, c, new Vec3d(0, 1, 0), 3.5f, 2);
        Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.4, 4.0, Fx.WHITE, 7);
        Fx.shakeAround(w, c, 10, 0.7f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.PLAYERS, 1.4f, 0.6f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.PLAYERS, 1.2f, 0.7f);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.PLAYERS, 0.5f, 1.6f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 0.6f);
    }

    // 각성기 '대지의 심판' (컷신 44틱): 두 팔을 머리 위로 → 손뼉 → 두 손바닥으로 땅을 내리침 → 대상 발밑마다 연성진, 양옆에서 거대한 바위 손이
    //   솟아 박수로 짓누름(가드 깨기·다운) → 둘레로 가시가 솟음. 걸린 상대는 박수까지 묶임. 현자의 돌을 지니면 붉은 연성 (각성 30초 연장 · 재사용 초기화)
    @Override
    public boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        List<LivingEntity> ts = Combat.targets(p, 10, 70, 6);
        if (ts.isEmpty()) { JobSkills.bar(p, "앞에 대상이 없습니다", Formatting.GRAY); return false; }
        if (Alchemy.hasStone(p) && PhilosopherStoneItem.consumeOne(p)) {
            Awaken.extend(p, 600);
            JobSkills.resetCooldown(p.getUuid());
            StoneFx.big(p);
            JobSkills.bar(p, "✦ 붉은 연성! 각성 30초 연장 · 재사용 초기화", Formatting.RED);
        }
        Motion.play(p, "al_ult");
        Combat.invuln(p, ULT_SCENE);
        Combat.afterSkill(p, ULT_SCENE - 8);
        List<ServerPlayerEntity> viewers = new ArrayList<>();
        viewers.add(p);
        for (LivingEntity t : ts) { hold(t, 24); if (t instanceof ServerPlayerEntity sp) viewers.add(sp); }
        cutscene(p, "al_ult", ts.get(0), viewers);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        for (int i = 0; i < 11; i += 2) {
            Tasks.later(i, () -> w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 0.2, p.getZ(), 12, 1.2, 0.1, 1.2, 0.2));
        }
        Tasks.later(8, () -> clapFx(p));
        Tasks.later(12, () -> {
            if (p.isDisconnected()) return;
            Alchemy.bigFx(p);
            Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.5, 9.0, Fx.WHITE, 10);
            Fx.shakeAround(w, p.getPos(), 14, 0.8f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 1.0f, 0.5f);
            for (LivingEntity t : ts) Alchemy.fx(w, t.getBlockPos(), true);
        });
        Tasks.later(14, () -> {
            Vec3d fwd = Combat.horizontal(p.getRotationVec(1f));
            Vec3d s = fwd.crossProduct(new Vec3d(0, 1, 0)).normalize();
            for (LivingEntity t : ts) {
                if (!t.isAlive()) continue;
                Vec3d at = t.getPos();
                Double gy = AlchemyFx.ground(w, at);
                double y = gy == null ? at.y : gy;
                GodHandFx.play(w, at, y, 10, 16, Math.atan2(-s.z, -s.x));
                GodHandFx.play(w, at, y, 10, 16, Math.atan2(s.z, s.x));
            }
        });
        Tasks.later(24, () -> {
            for (LivingEntity t : ts) {
                if (!t.isAlive()) continue;
                Combat.hit(p, t, JUDGMENT);
                Vec3d c = t.getBoundingBox().getCenter();
                Fx.word(w, c.add(0, 1.6, 0), Fx.Sfx.DOGOOON, 1.7f);
                Fx.rings(w, c, new Vec3d(0, 1, 0), 4f, 3);
                Fx.debris(w, t.getPos(), 22, 1.8);
                Fx.impactFlash(w, c, 1.8f);
            }
            Fx.shakeAround(w, p.getPos(), 16, 1.0f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.2f, 0.6f);
        });
        Tasks.later(30, () -> {
            for (LivingEntity t : ts) {
                Vec3d at = t.getPos();
                for (int i = 0; i < 12; i++) {
                    double a = Math.PI * 2 * i / 12, r = 2.6;
                    Double gg = AlchemyFx.ground(w, new Vec3d(at.x + Math.cos(a) * r, at.y, at.z + Math.sin(a) * r));
                    if (gg != null) AlchemyFx.spike(w, at.x + Math.cos(a) * r, gg, at.z + Math.sin(a) * r, 0.9f, 3.0f, 30);
                }
            }
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
