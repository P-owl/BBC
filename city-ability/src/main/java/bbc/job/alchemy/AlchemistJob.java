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

    /** 무기 연성(공중) 중: 오토메일 칼날 평타 (빠르고 조금 셈, 사거리 조금 김) */
    private static final bbc.combat.Weapon.M1Profile BLADE_M1 = new bbc.combat.Weapon.M1Profile(4, 3, 2, 1.3f, 0.5, "ab_", 5);

    /** 의족이면 평타가 발차기 위주 (alk_*). 의족 칼날을 연성하면 더 세고 길게 */
    private static final bbc.combat.Weapon.M1Profile LEG_M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1.05f, 0.4, "alk_", 6);
    private static final bbc.combat.Weapon.M1Profile LEG_BLADE_M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1.35f, 0.6, "alk_", 6);
    /** 강철 심장 과부하 중: 같은 평타가 후딜이 짧고 셈 */
    private static final bbc.combat.Weapon.M1Profile HEART_M1 = new bbc.combat.Weapon.M1Profile(5, 4, 1, 1.2f, 0.2, "al_", 6);

    /** 단 오토메일 종류: "arm"(의수) · "leg"(의족) · "heart"(강철 심장) · ""(없음) */
    static String mail(ServerPlayerEntity p) {
        AlchemyData.Entry e = Alch.entry(p);
        return AutomailSkill.available(e) ? e.automail : "";
    }

    @Override public bbc.combat.Weapon.M1Profile m1() { return M1; }
    @Override public bbc.combat.Weapon.M1Profile m1(ServerPlayerEntity p) {
        if (spearOn(p)) return SPEAR_M1;
        String m = mail(p);
        if (m.equals("leg")) return bladeOn(p) ? LEG_BLADE_M1 : LEG_M1;
        if (bladeOn(p)) return m.equals("heart") ? HEART_M1 : BLADE_M1;
        return M1;
    }
    @Override public String stance() { return "stance_al"; }

    /** 다운 슬램: 공중에서 손뼉 → 그 순간 손(오토메일·장갑)에 칼을 연성해 내리찍고 사라짐 */
    @Override
    public void m1Start(ServerPlayerEntity p, String motion) {
        if (!motion.equals("al_slam") && !motion.equals("alk_slam")) return;   // 칼날 평타 중(ab_slam)이면 이미 칼이 있음
        if (bladeOn(p) && motion.equals("alk_slam")) return;
        ServerWorld w = p.getServerWorld();
        UUID id = p.getUuid();
        if (mail(p).equals("heart")) {   // 강철 심장: 칼 대신 심장을 한 번 펌핑하고 그 힘으로 내리침
            Tasks.later(1, () -> { if (!p.isDisconnected()) { clapFx(p); heartbeat(p, 0.8f); } });
            return;
        }
        Tasks.later(1, () -> {
            if (p.isDisconnected()) return;
            clapFx(p);
            AutomailSkill.setForgedBlade(p, true);   // 의족이면 다리에, 아니면 손에
            Vec3d r = rightSide(p, 0.4);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, r.x, p.getY() + 1.2, r.z, 10, 0.15, 0.3, 0.15, 0.15);
        });
        Tasks.later(13, () -> { if (!p.isDisconnected() && !BLADE_UNTIL.containsKey(id)) AutomailSkill.setForgedBlade(p, false); });
    }

    /** 5타 = 땅을 짚은 자리에 연성진 + 상대 발밑에서 바위 기둥, 띄우기 = 가시 (연출만, 판정은 평타 그대로) */
    @Override
    public void m1Fx(ServerPlayerEntity p, String motion) {
        ServerWorld w = p.getServerWorld();
        if (motion.startsWith("ab_")) {   // 칼날: 휘두른 방향대로 하얀 베기 자국
            switch (motion) {
                case "ab_1" -> Fx.slash(w, p, 2.6, 50, true, Fx.WHITE);
                case "ab_2" -> Fx.slash(w, p, 2.6, 50, false, Fx.WHITE);
                case "ab_3" -> Fx.slash(w, p, 2.8, 0, true, Fx.WHITE);
                case "ab_rise" -> Fx.slash(w, p, 2.6, 90, false, Fx.WHITE);
                case "ab_4" -> { Vec3d f = Combat.horizontal(p.getRotationVec(1f)); w.spawnParticles(ParticleTypes.CRIT, p.getX() + f.x * 3, p.getY() + 1.2, p.getZ() + f.z * 3, 10, 0.2, 0.2, 0.2, 0.3); }
                default -> { }
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.6f, 1.5f);
        }
        if (motion.startsWith("alk_")) {   // 발차기: 찬 방향으로 흰 줄기
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Fx.kickStreak(w, p.getPos().add(f.multiply(1.6)).add(0, motion.equals("alk_rise") ? 1.6 : 1.0, 0), f);
            Vec3d at = p.getPos().add(f.multiply(1.8));
            Double gy = AlchemyFx.ground(w, at);
            if (motion.equals("alk_5") && gy != null) { Alchemy.fx(w, BlockPos.ofFloored(at), false); AlchemyFx.pillar(w, at.x, gy, at.z, 0.9f, 2.2f, 14); Fx.debris(w, at, 10, 0.9); }
            if (motion.equals("alk_rise") && gy != null) AlchemyFx.spike(w, at.x, gy, at.z, 0.6f, 2.0f, 12);
            if (motion.equals("alk_slam")) Fx.debris(w, at, 10, 0.9);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 0.7f, 1.2f);
            return;
        }
        if (motion.equals("al_slam") && mail(p).equals("heart")) {   // 강철 심장 슬램: 붉은 충격파
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Vec3d c = p.getPos().add(f.multiply(1.6)).add(0, 0.6, 0);
            Fx.wave(w, c, 0.3, 2.6, Fx.RED, 6);
            w.spawnParticles(HEART_RED, c.x, c.y, c.z, 20, 0.6, 0.4, 0.6, 0.1);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.4f, 1.2f);
            return;
        }
        if (motion.equals("al_slam") || motion.equals("ab_slam")) {   // 다운 슬램: 연성한 칼로 내리찍은 자리에 하얀 세로 베기 + 흙먼지
            Fx.slash(w, p, 2.8, 90, true, Fx.WHITE);
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Fx.debris(w, p.getPos().add(f.multiply(1.6)), 8, 0.8);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.9f, 0.7f);
            return;
        }
        if (!motion.startsWith("al_")) return;
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
            new JobSkills.Skill("무기 연성", "무기", 2, 25, 12, "땅: 창을 뽑아 20초 창 평타, 다시 누르면 돌진 찌르기 · 공중: 오토메일(없으면 장갑)에 칼날, 20초 칼날 평타, 다시 누르면 기둥을 밟고 솟구쳐 내리꽂음(다운·가드 깨기) (각성 → 신의 손)"),
            new JobSkills.Skill("가시", "가시", 1, 25, 10, "앞 땅에서 가시 무더기가 솟아 띄움, 가드 깨기 (각성 → 가시밭)"),
            new JobSkills.Skill("기둥 벽", "기둥벽", 2, 25, 12, "뒤로 백덤블링 → 착지하며 땅을 짚으면 앞으로 기둥들이 줄지어 솟아 띄움, 가드 깨기 (각성 → 최후의 일격)"),
            new JobSkills.Skill("대지 붕괴", "붕괴", 4, 50, 45, "주위로 가시 물결 세 번, 가드 깨기 (광역)"),
            new JobSkills.Skill("돌 산탄", "산탄", 1, 20, 7, "땅을 긁어 올려 돌 파편 7개를 부채꼴로 쏨 (견제, 12칸)"),
            new JobSkills.Skill("바위 족쇄", "족쇄", 2, 25, 14, "조준한 상대(14칸) 발목을 바위가 감싸 1.5초 묶음"),
            new JobSkills.Skill("연성 방벽", "방벽", 3, 20, 12, "앞에 순간 벽을 세움 — 0.6초 안에 맞으면 막고(패링) 기둥으로 되받아침"),
    };
    private static final JobSkills.Skill[] AWAKE = {
            new JobSkills.Skill("창조의 기둥", "창조", 1, 30, 10, "[각성] 백덤블링하며 거대한 바위 기둥 다섯 개를 상대에게 연달아 날림, 마지막 기둥은 가드 깨기"),
            new JobSkills.Skill("신의 손", "신의손", 2, 35, 14, "[각성] 땅: 양옆에서 바위 손 둘이 솟아 박수로 가두고 짓눌러 다운 · 공중: 거대한 바위 주먹이 날아가 꽂힘 — 둘 다 가드 깨기"),
            new JobSkills.Skill("가시밭", "가시밭", 1, 35, 14, "[각성] 충격파와 먼지 속 연타 5번 → 거대한 가시 숲이 솟아 하늘 높이 띄움, 가드 깨기"),
            new JobSkills.Skill("최후의 일격", "일격", 2, 40, 20, "[각성] 오토메일 주먹을 끝까지 당겼다가 꽂는 일격, 가드 깨기 · 멀리 날림"),
            BASE[4], BASE[5], BASE[6], BASE[7],
    };

    private static final Hit PILLAR = new Hit(3.0f, Hit.Kind.KNOCK, 16, 1.3, 0.35, 20, false, false);
    private static final Hit THRUST = new Hit(2.9f, Hit.Kind.HIT, 20, 0.3, 0, 25, false, false);
    private static final Hit SPIKES = new Hit(2.6f, Hit.Kind.AIR, 26, 0.1, 0.95, 30, true, false);
    private static final Hit PILLAR_WALL = new Hit(2.4f, Hit.Kind.AIR, 30, 0.15, 1.1, 30, true, false);
    private static final Hit PLUNGE = new Hit(3.4f, Hit.Kind.DOWN, 26, 0.2, -1.0, 40, true, true);
    private static final Hit SHARD = new Hit(0.6f, Hit.Kind.HIT, 8, 0.15, 0, 6, false, false);
    private static final Hit SHACKLE = new Hit(0.8f, Hit.Kind.HIT, 10, 0, 0, 10, false, false);
    private static final Hit COUNTER = new Hit(2.6f, Hit.Kind.KNOCK, 20, 1.5, 0.5, 30, false, false);
    private static final Hit COLLAPSE = new Hit(3.6f, Hit.Kind.AIR, 18, 0.2, 0.55, 25, true, false);
    // 각성판 (ABA 수치 ÷ 4.1 ≈ 우리 평타 1.0 기준, 큰 건 조금 깎음)
    private static final Hit CREATION = new Hit(1.3f, Hit.Kind.HIT, 16, 0.25, 0.1, 20, false, false);
    private static final Hit CREATION_END = new Hit(3.6f, Hit.Kind.KNOCK, 26, 2.4, 0.6, 50, true, false);
    private static final Hit GIANT_PUNCH_UP = new Hit(4.6f, Hit.Kind.AIR, 34, 0.3, 1.8, 50, true, false);   // 땅에서 솟은 주먹이 하늘로 올려침
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
                case 1 -> aw ? (p.isOnGround() ? handOfGod(p) : airFist(p)) : weaponForge(p);
                case 2 -> aw ? spikeField(p) : spikes(p);
                case 3 -> aw ? finalPunch(p) : pillarWall(p);
                case 4 -> earthCollapse(p);
                case 5 -> stoneShot(p);
                case 6 -> shackle(p);
                case 7 -> barrier(p);
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

    // ①' 창조의 기둥 (ABA Pillars of Creation, 99.2~101.2초): 웅크렸다 뛰어오르며 빠르게 뒤로 반 바퀴 → 머리가 아래로 거꾸로 뜬 채 잠깐 멈춰
    //    연성 — 1차로 바위 기둥 셋이 땅에서 비스듬히 솟아 상대에게 꽂힘(8) → 나머지 반 바퀴 돌아 착지하며 땅을 짚음 —
    //    2차로 기둥 셋(가운데 큰 기둥이 날려보냄 + 가드 깨기)과 앞을 막는 바위 기둥들(20). 덤블링 동안 뒤로 약 5칸 이동 (거리를 벌리며 쏨)
    private static boolean creation(ServerPlayerEntity p) {
        LivingEntity t = pickTarget(p, 22, 18);
        Vec3d look = Combat.horizontal(p.getRotationVec(1f));
        Vec3d dir = t != null ? Combat.horizontal(t.getPos().subtract(p.getPos())) : look;
        if (dir.lengthSquared() < 1e-4) dir = look;
        final Vec3d d = dir.normalize();
        Motion.play(p, "al_flip");
        Combat.afterSkill(p, 26);
        ServerWorld w = p.getServerWorld();
        AlchemySounds.play(p, AlchemySounds.ATTACK, 1.0f);
        Vec3d side = d.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Tasks.later(3, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            Combat.move(p, new Vec3d(-d.x * 0.55, 0.62, -d.z * 0.55));   // 덤블링하며 뒤로 크게 이동
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.5f);
        });
        for (int i = 7; i <= 15; i++) Tasks.later(i, () -> {   // 거꾸로 뜬 채 뒤로 미끄러지듯 (높이는 유지)
            if (p.isDisconnected() || !p.isAlive()) return;
            Combat.move(p, new Vec3d(-d.x * 0.3, 0.0, -d.z * 0.3));
            p.fallDistance = 0;
        });
        Tasks.later(16, () -> { if (!p.isDisconnected()) { Combat.move(p, new Vec3d(-d.x * 0.25, -0.7, -d.z * 0.25)); p.fallDistance = 0; } });
        // 1차: 공중에서
        Tasks.later(8, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            AlchemySounds.play(p, AlchemySounds.SPARK, 1.0f);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 0.5, p.getZ(), 20, 0.4, 0.4, 0.4, 0.2);
            Double g = AlchemyFx.ground(w, p.getPos());
            if (g == null) g = AlchemyFx.ground(w, p.getPos().add(0, -5, 0));
            double gy = g == null ? p.getY() - 2 : g;
            Alchemy.fx(w, BlockPos.ofFloored(p.getX(), gy, p.getZ()), Alch.entry(p).truth);
            for (int k = 0; k < 3; k++) {
                final int kk = k;
                Tasks.later(k * 2, () -> creationPillar(p, w, t, d, side, gy, (kk - 1) * 1.8, 1.3f, CREATION, 30));
            }
        });
        // 2차: 착지하며
        Tasks.later(20, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            clapFx(p);
            Double g = AlchemyFx.ground(w, p.getPos());
            double gy = g == null ? p.getY() : g;
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_DIG, SoundCategory.PLAYERS, 1.4f, 1.1f);
            creationPillar(p, w, t, d, side, gy, -2.4, 1.3f, CREATION, 26);
            Tasks.later(2, () -> creationPillar(p, w, t, d, side, gy, 2.4, 1.3f, CREATION, 26));
            Tasks.later(4, () -> creationPillar(p, w, t, d, side, gy, 0, 1.9f, CREATION_END, 26));
            for (int i = -2; i <= 2; i++) {   // 앞을 막는 바위 기둥 (ABA 착지 뒤 솟는 판)
                Vec3d q = p.getPos().add(d.multiply(2.2)).add(side.multiply(i * 1.1));
                Double gq = AlchemyFx.ground(w, q);
                if (gq != null) AlchemyFx.pillar(w, q.x, gq, q.z, 1.0f, 2.4f + Math.abs(i) * 0.3f, 40);
            }
        });
        return true;
    }

    private static void creationPillar(ServerPlayerEntity p, ServerWorld w, LivingEntity t, Vec3d d, Vec3d side, double gy, double off, float width, Hit h, int life) {
        if (p.isDisconnected()) return;
        Vec3d aim = t != null && t.isAlive() ? t.getPos().add(0, 1.0, 0) : p.getPos().add(d.multiply(12)).add(0, 1.0, 0);
        Vec3d base = new Vec3d(p.getX() + d.x * 1.4 + side.x * off, gy - 1.0, p.getZ() + d.z * 1.4 + side.z * off);
        Vec3d end = aim.add(Combat.horizontal(aim.subtract(base)).multiply(1.6));
        growPillar(p, w, base, end, width, h, life);
    }

    /** 백덤블링 (al_backflip 과 맞춤): 3틱 웅크렸다가 뒤로·위로 솟구침, 16틱에 착지하며 땅을 짚음 */
    private static final int BACKFLIP_LAND = 16;
    private static void backflipJump(ServerPlayerEntity p, Vec3d d, double back, double up) {
        Tasks.later(3, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            Combat.move(p, new Vec3d(-d.x * back, up, -d.z * back));
            p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.4f);
        });
        Tasks.later(9, () -> { if (!p.isDisconnected()) p.fallDistance = 0; });
    }

    /** base → end 로 4틱 동안 자라는 비스듬한 바위 기둥. 자라는 동안 닿은 상대를 한 번 때림, life 틱 뒤 부서짐 */
    private static void growPillar(ServerPlayerEntity p, ServerWorld w, Vec3d base, Vec3d end, float width, Hit h, int life) {
        List<Entity> parts = new ArrayList<>();
        Set<UUID> hit = new HashSet<>();
        int n = 4;
        Vec3d[] prev = {base};
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()), base.x, base.y + 1.1, base.z, 16, 0.6, 0.1, 0.6, 0.25);
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, base.x, base.y + 1.1, base.z, 8, 0.6, 0.2, 0.6, 0.15);
        w.playSound(null, BlockPos.ofFloored(base), SoundEvents.BLOCK_DEEPSLATE_PLACE, SoundCategory.PLAYERS, 1.4f, 0.5f);
        for (int i = 1; i <= n; i++) {
            final Vec3d to = base.lerp(end, i / (double) n);
            Tasks.later(i - 1, () -> {
                Vec3d from = prev[0];
                Entity seg = StonePillarFx.segment(w, from, to, Blocks.POLISHED_ANDESITE.getDefaultState(), width);
                if (seg != null) parts.add(seg);
                prev[0] = to;
                Box box = new Box(from, to).expand(width * 0.5 + 0.5);
                for (Entity en : w.getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
                    if (!hit.add(en.getUuid())) continue;
                    LivingEntity v = (LivingEntity) en;
                    Combat.hit(p, v, h);
                    Vec3d c = v.getBoundingBox().getCenter();
                    Fx.debris(w, v.getPos(), 8, 0.8);
                    if (h == CREATION_END) {
                        Fx.word(w, c.add(0, 1.4, 0), Fx.BLUNT, 1.5f);
                        Fx.burst(w, c, end.subtract(base).normalize(), 1.8f);
                        Fx.shakeAround(w, c, 12, 0.8f);
                        w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.5f, 0.4f);
                    }
                }
            });
        }
        Tasks.later(n + life, () -> {
            for (Entity e : parts) {
                if (e == null || e.isRemoved()) continue;
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.POLISHED_ANDESITE.getDefaultState()), e.getX(), e.getY() + 0.5, e.getZ(), 10, 0.6, 0.6, 0.6, 0.1);
                e.discard();
            }
        });
    }

    // ② 무기 연성 (ABA Transmute Weapon)
    //    땅: 창을 뽑음 → 20초 창 평타 (×1.5), 다시 누르면 돌진 찌르기로 마무리
    //    공중: 오토메일 의수(없으면 장갑 낀 팔) 바깥에 칼날을 연성 → 20초 칼날 평타, 다시 누르면 발밑 기둥을 밟고 솟구쳤다가
    //          칼날을 꽂으며 낙하 → 착지 충격 (다운 슬램 + 가드 깨기)
    //    알림 메시지는 안 띄움 (모션·소리·창/칼날로 보임). 재사용은 마무리하거나 시간이 끝날 때 돎
    private static final Map<UUID, Long> SPEAR_UNTIL = new HashMap<>(), BLADE_UNTIL = new HashMap<>();
    private static final Map<UUID, Integer> SPEAR_SLOT = new HashMap<>();
    private static final int SPEAR_TICKS = 400, BLADE_TICKS = 400, WEAPON_SKILL = 1;

    static boolean spearOn(ServerPlayerEntity p) { return SPEAR_UNTIL.getOrDefault(p.getUuid(), 0L) > Combat.now(); }
    static boolean bladeOn(ServerPlayerEntity p) { return BLADE_UNTIL.getOrDefault(p.getUuid(), 0L) > Combat.now(); }

    private static boolean weaponForge(ServerPlayerEntity p) {
        if (spearOn(p)) return spearThrust(p);
        if (bladeOn(p)) return pillarPlunge(p);
        AlchemySounds.play(p, AlchemySounds.WEAPON, 1.0f);
        if (!p.isOnGround()) return bladeForge(p);
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
            UUID id = p.getUuid();
            Tasks.later(SPEAR_TICKS, () -> { if (SPEAR_UNTIL.getOrDefault(id, 0L) <= Combat.now() + 1) endSpear(p); });
        });
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
            endSpear(p);
        });
        return true;
    }

    private static void endSpear(ServerPlayerEntity p) {
        SPEAR_UNTIL.remove(p.getUuid());
        Integer slot = SPEAR_SLOT.remove(p.getUuid());
        if (slot != null && p.getInventory().getStack(slot).isOf(AlchemyItems.ALCHEMY_SPEAR)) p.getInventory().setStack(slot, ItemStack.EMPTY);
        for (int i = 0; i < p.getInventory().size(); i++) if (p.getInventory().getStack(i).isOf(AlchemyItems.ALCHEMY_SPEAR)) p.getInventory().setStack(i, ItemStack.EMPTY);
        JobSkills.setCooldown(p.getUuid(), WEAPON_SKILL, System.currentTimeMillis() + 12_000L);
        if (!p.isDisconnected())
            p.getServerWorld().spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.getDefaultState()), p.getX(), p.getY() + 1.2, p.getZ(), 20, 0.4, 0.4, 0.4, 0.2);
    }

    /** 공중 무기 연성: 손뼉 → 오른 아래팔을 세우면 칼날이 솟음 (오토메일이면 의수에서, 아니면 장갑 낀 팔에) */
    private static boolean bladeForge(ServerPlayerEntity p) {
        String m = mail(p);
        if (m.equals("heart")) return heartPump(p);
        Motion.play(p, m.equals("leg") ? "alk_bladeforge" : "al_bladeforge");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        UUID id = p.getUuid();
        Vec3d v = p.getVelocity();
        Combat.move(p, new Vec3d(v.x * 0.5, Math.max(v.y, 0.18), v.z * 0.5));   // 잠깐 공중에 뜸
        Tasks.later(3, () -> clapFx(p));
        Tasks.later(6, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            BLADE_UNTIL.put(id, Combat.now() + BLADE_TICKS);
            AutomailSkill.setForgedBlade(p, true);   // 의족이면 왼다리 정강이에
            Vec3d r = m.equals("leg") ? rightSide(p, -0.2) : rightSide(p, 0.4);
            double ry = m.equals("leg") ? 0.5 : 1.1;
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, r.x, p.getY() + ry, r.z, 16, 0.15, 0.3, 0.15, 0.2);
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.getDefaultState()), r.x, p.getY() + 1.1, r.z, 10, 0.15, 0.3, 0.15, 0.1);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.6f, 1.7f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_IRON.value(), SoundCategory.PLAYERS, 1.0f, 1.2f);
            Tasks.later(BLADE_TICKS, () -> { if (BLADE_UNTIL.containsKey(id) && BLADE_UNTIL.get(id) <= Combat.now() + 1) endBlade(p); });
        });
        Tasks.later(1, () -> JobSkills.clearCooldown(id, WEAPON_SKILL));
        return true;
    }

    // ── 강철 심장: 공중 무기 연성 = 심장 펌핑 (20초 과부하: 평타 후딜 짧고 ×1.2), 다시 = 심장 폭주 (붉은 충격파, 가드 깨기) ──
    private static final net.minecraft.particle.DustParticleEffect HEART_RED = new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.85f, 0.08f, 0.1f), 1.4f);
    private static final Hit HEART_BURST = new Hit(3.6f, Hit.Kind.KNOCK, 26, 2.2, 0.6, 50, true, false);

    /** 심장이 한 번 뜀: 가슴에서 붉은 고리 + 심장 소리 */
    private static void heartbeat(ServerPlayerEntity p, float strength) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos().add(0, 1.2, 0);
        Fx.wave(w, c, 0.2, 1.2 + 1.4 * strength, Fx.RED, 5);
        w.spawnParticles(HEART_RED, c.x, c.y, c.z, (int) (14 * strength) + 4, 0.25, 0.25, 0.25, 0.05);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.2f + strength, 1.0f + 0.2f * strength);
    }

    private static boolean heartPump(ServerPlayerEntity p) {
        Motion.play(p, "al_pump");
        Combat.afterSkill(p, 8);
        UUID id = p.getUuid();
        Vec3d v = p.getVelocity();
        Combat.move(p, new Vec3d(v.x * 0.5, Math.max(v.y, 0.18), v.z * 0.5));
        Tasks.later(5, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            BLADE_UNTIL.put(id, Combat.now() + BLADE_TICKS);
            heartbeat(p, 1f);
            Tasks.later(BLADE_TICKS, () -> { if (BLADE_UNTIL.containsKey(id) && BLADE_UNTIL.get(id) <= Combat.now() + 1) endBlade(p); });
            for (int t = 20; t < BLADE_TICKS; t += 20) Tasks.later(t, () -> { if (bladeOn(p)) heartbeat(p, 0.2f); });   // 과부하 동안 1초마다 뜀
        });
        Tasks.later(1, () -> JobSkills.clearCooldown(id, WEAPON_SKILL));
        return true;
    }

    private static boolean heartBurst(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "al_pump_burst");
        Combat.afterSkill(p, 20);
        BLADE_UNTIL.put(p.getUuid(), Combat.now() + 30);
        Tasks.later(5, () -> { if (!p.isDisconnected()) heartbeat(p, 0.6f); });
        Tasks.later(8, () -> { if (!p.isDisconnected()) heartbeat(p, 1.0f); });
        Tasks.later(10, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            Vec3d c = p.getPos().add(0, 1.0, 0);
            for (Entity en : w.getOtherEntities(p, new Box(c, c).expand(3.6, 2.4, 3.6), x -> Targets.canHit(p, x)))
                Combat.hit(p, (LivingEntity) en, HEART_BURST);
            Fx.wave(w, c, 0.4, 5.0, Fx.RED, 9);
            Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.4, 4.0, Fx.RED, 8);
            Fx.rings(w, c, new Vec3d(0, 1, 0), 4f, 3);
            Fx.word(w, c.add(0, 1.6, 0), Fx.Sfx.DOGOOON, 1.5f);
            w.spawnParticles(HEART_RED, c.x, c.y, c.z, 60, 1.2, 0.8, 1.2, 0.3);
            Fx.shakeAround(w, c, 12, 0.9f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.9f, 1.3f);
            endBlade(p);
        });
        return true;
    }

    private static void endBlade(ServerPlayerEntity p) {
        BLADE_UNTIL.remove(p.getUuid());
        AutomailSkill.setForgedBlade(p, false);
        JobSkills.setCooldown(p.getUuid(), WEAPON_SKILL, System.currentTimeMillis() + 12_000L);
        if (!p.isDisconnected()) {
            Vec3d r = rightSide(p, 0.4);
            p.getServerWorld().spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.IRON_BLOCK.getDefaultState()), r.x, p.getY() + 1.1, r.z, 14, 0.2, 0.3, 0.2, 0.15);
        }
    }

    /** 칼날 마무리 (ABA 무기 연성 공중 마무리): 발밑에서 기둥이 솟아 나를 띄움 → 꼭대기에서 칼날을 아래로 꽂으며 내리꽂힘 → 착지 충격 */
    private static boolean pillarPlunge(ServerPlayerEntity p) {
        if (mail(p).equals("heart")) return heartBurst(p);
        ServerWorld w = p.getServerWorld();
        UUID id = p.getUuid();
        Motion.play(p, mail(p).equals("leg") ? "alk_plunge" : "ab_plunge");   // 의족: 칼날 단 왼발로 드롭킥
        Combat.afterSkill(p, 28);
        BLADE_UNTIL.put(id, Combat.now() + 60);   // 낙하하는 동안 칼날 유지
        Tasks.later(3, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            clapFx(p);
            Vec3d at = p.getPos();
            Double g = AlchemyFx.ground(w, at);
            if (g != null) {
                AlchemyFx.pillar(w, at.x, g, at.z, 1.1f, (float) Math.max(1.6, at.y - g + 1.2), 26);
                Fx.debris(w, new Vec3d(at.x, g, at.z), 10, 0.8);
            }
            Vec3d l = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(l.x * 0.3, 1.15, l.z * 0.3));
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 1.0f, 0.6f);
        });
        Tasks.later(11, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            Vec3d l = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(l.x * 0.45, -2.4, l.z * 0.45));
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 0.6f);
        });
        boolean[] done = {false};
        for (int t = 12; t <= 44; t++) {
            final boolean last = t == 44;
            Tasks.later(t, () -> {
                if (done[0] || p.isDisconnected() || !p.isAlive()) return;
                p.fallDistance = 0;
                if (!p.isOnGround() && !last) {
                    if (p.getVelocity().y > -1.2) Combat.move(p, new Vec3d(p.getVelocity().x, -2.0, p.getVelocity().z));
                    return;
                }
                done[0] = true;
                plungeImpact(p);
            });
        }
        return true;
    }

    private static void plungeImpact(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        for (Entity en : w.getOtherEntities(p, new Box(c, c).expand(3.0, 2.0, 3.0), x -> Targets.canHit(p, x)))
            Combat.hit(p, (LivingEntity) en, PLUNGE);
        Fx.wave(w, c.add(0, 0.1, 0), 0.5, 4.0, Fx.WHITE, 8);
        Fx.rings(w, c.add(0, 0.2, 0), new Vec3d(0, 1, 0), 3.5f, 2);
        Fx.word(w, c.add(0, 2.4, 0), Fx.SLAM, 1.4f);
        Fx.debris(w, c, 20, 1.6);
        Fx.shakeAround(w, c, 12, 0.9f);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8;
            Double g = AlchemyFx.ground(w, new Vec3d(c.x + Math.cos(a) * 2.0, c.y, c.z + Math.sin(a) * 2.0));
            if (g != null) AlchemyFx.spike(w, c.x + Math.cos(a) * 2.0, g, c.z + Math.sin(a) * 2.0, 0.6f, 1.6f, 16);
        }
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.8f, 1.0f);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.7f, 0.8f);
        endBlade(p);
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

    // ②'' 신의 손 (공중, ABA Hand of God 공중판): 손바닥을 내밀면 공중의 상대 아래 땅을 뚫고 관절까지 있는 거대한 바위 주먹이
    //     솟구쳐 올려침 → 하늘로 날려버림, 가드 깨기. 솟는 동안 상대는 그 자리에 묶임
    private static boolean airFist(ServerPlayerEntity p) {
        LivingEntity t = pickTarget(p, 22, 16);
        if (t == null) { JobSkills.bar(p, "주변에 공격할 대상이 없습니다", Formatting.RED); return false; }
        Motion.play(p, "al_creation");
        Combat.afterSkill(p, 14);
        ServerWorld w = p.getServerWorld();
        Vec3d v = p.getVelocity();
        Combat.move(p, new Vec3d(v.x * 0.3, Math.max(v.y, 0.12), v.z * 0.3));   // 쏘는 동안 살짝 뜸
        Tasks.later(3, () -> clapFx(p));
        Tasks.later(6, () -> {
            if (p.isDisconnected() || !p.isAlive() || !t.isAlive()) return;
            Vec3d at = t.getPos();
            Double g = AlchemyFx.ground(w, at);
            if (g == null) g = AlchemyFx.ground(w, at.add(0, -6, 0));
            double gy = g == null ? at.y - 3 : g;
            Alchemy.fx(w, BlockPos.ofFloored(at.x, gy, at.z), Alch.entry(p).truth);
            hold(t, 6);
            GodHandFx.fist(w, at, gy, p.getPos(), 16);
        });
        Tasks.later(12, () -> {
            if (!t.isAlive()) return;
            Combat.hit(p, t, GIANT_PUNCH_UP);
            Vec3d c = t.getBoundingBox().getCenter();
            Fx.rings(w, c, new Vec3d(0, 1, 0), 4.5f, 3);
            Fx.word(w, c.add(0, 1.6, 0), Fx.Sfx.DOGOOON, 1.6f);
            Fx.burst(w, c, new Vec3d(0, 1, 0), 2.2f);
            Fx.impactFlash(w, c, 1.6f);
            Fx.shakeAround(w, c, 14, 1.0f);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.9f, 0.8f);
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

    // ④ 기둥 벽 (ABA Spike 모션 참고): 뒤로 백덤블링 → 착지하며 땅을 짚으면 발 앞에서 상대 쪽으로 바위 기둥들이
    //    부채꼴로 줄지어(1틱에 한 줄) 솟아 띄움, 가드 깨기. 띄운 뒤 평타·기술로 이어 감
    private static boolean pillarWall(ServerPlayerEntity p) {
        LivingEntity t = pickTarget(p, 14, 10);
        Vec3d look = Combat.horizontal(p.getRotationVec(1f));
        Vec3d dir = t != null ? Combat.horizontal(t.getPos().subtract(p.getPos())) : look;
        if (dir.lengthSquared() < 1e-4) dir = look;
        final Vec3d d = dir.normalize();
        Motion.play(p, "al_backflip");
        Combat.afterSkill(p, 22);
        ServerWorld w = p.getServerWorld();
        backflipJump(p, d, 0.32, 0.48);
        Tasks.later(BACKFLIP_LAND, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            clapFx(p);
            Vec3d origin = p.getPos();
            Vec3d side = d.crossProduct(new Vec3d(0, 1, 0)).normalize();
            Set<UUID> hit = new HashSet<>();
            java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
            for (int row = 0; row < 6; row++) {
                final int rr = row;
                Tasks.later(row, () -> {
                    double dist = 1.8 + rr * 1.4, spread = 0.5 + rr * 0.45;
                    int cnt = 2 + rr / 2;
                    for (int i = 0; i < cnt; i++) {
                        double o = (i / (double) (cnt - 1) - 0.5) * 2 * spread;
                        Vec3d q = origin.add(d.multiply(dist + r.nextDouble(-0.3, 0.3))).add(side.multiply(o + r.nextDouble(-0.25, 0.25)));
                        Double g = AlchemyFx.ground(w, q);
                        if (g != null) AlchemyFx.pillar(w, q.x, g, q.z, 0.85f + r.nextFloat() * 0.3f, 2.0f + rr * 0.25f + r.nextFloat() * 0.6f, 30);
                    }
                    Vec3d c = origin.add(d.multiply(dist));
                    for (Entity en : w.getOtherEntities(p, new Box(c, c).expand(spread + 0.9, 2.5, spread + 0.9), x -> Targets.canHit(p, x))) {
                        if (!hit.add(en.getUuid())) continue;
                        Combat.hit(p, (LivingEntity) en, PILLAR_WALL);
                        Fx.debris(w, en.getPos(), 10, 1.0);
                    }
                    if (rr % 2 == 0) w.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 1.0f, 0.5f + rr * 0.05f);
                });
            }
        });
        return true;
    }

    // ④' 최후의 일격: 오토메일 주먹을 끝까지 뒤로 당겨 몸을 꼬고(스파크가 모임) → 깊게 내딛으며 꽂음. 가드 깨기, 멀리 날림 (선딜 8)
    private static boolean finalPunch(ServerPlayerEntity p) {
        Motion.play(p, "al_punch");
        Combat.afterSkill(p, 20);
        AlchemySounds.play(p, AlchemySounds.RUSH, 1.0f);
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

    // ⑥ 돌 산탄: 손뼉 → 오른손으로 땅을 긁어 올리듯 앞으로 → 돌 파편 7개가 부채꼴(±30°)로 날아감. 한 상대에 최대 3개 (견제)
    private static boolean stoneShot(ServerPlayerEntity p) {
        Motion.play(p, "al_creation");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Tasks.later(3, () -> clapFx(p));
        Tasks.later(6, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            Vec3d o = p.getPos().add(0, 1.1, 0);
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Map<UUID, Integer> hits = new HashMap<>();
            java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
            for (int i = 0; i < 7; i++) {
                Vec3d d = f.rotateY((float) Math.toRadians(-30 + i * 10 + r.nextDouble(-3, 3)));
                Entity shard = GodHandFx.display(w, (i % 2 == 0 ? Blocks.COBBLESTONE : Blocks.ANDESITE).getDefaultState(), o.x, o.y, o.z);
                if (shard == null) continue;
                GodHandFx.transform(shard, new org.joml.Quaternionf().rotateXYZ(r.nextFloat() * 6f, r.nextFloat() * 6f, r.nextFloat() * 6f),
                        new org.joml.Vector3f(-0.15f, -0.15f, -0.15f), new org.joml.Vector3f(0.3f, 0.3f, 0.3f), 0);
                boolean[] stop = {false};
                for (int t = 1; t <= 6; t++) {
                    final Vec3d at = o.add(d.multiply(2.0 * t));
                    Tasks.later(t, () -> {
                        if (stop[0]) return;
                        shard.requestTeleport(at.x, at.y, at.z);
                        if (!w.getBlockState(BlockPos.ofFloored(at)).isAir()) { stop[0] = true; return; }
                        for (Entity en : w.getOtherEntities(p, new Box(at, at).expand(0.9), x -> Targets.canHit(p, x))) {
                            if (hits.merge(en.getUuid(), 1, Integer::sum) > 3) continue;
                            Combat.hit(p, (LivingEntity) en, SHARD);
                            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()), at.x, at.y, at.z, 6, 0.2, 0.2, 0.2, 0.1);
                            stop[0] = true;
                            break;
                        }
                    });
                }
                Tasks.later(9, shard::discard);
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_STONE_BREAK, SoundCategory.PLAYERS, 1.2f, 0.7f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 0.8f, 0.6f);
        });
        return true;
    }

    // ⑦ 바위 족쇄: 조준한 상대 발밑에서 바위 고리가 솟아 발목을 감쌈 → 1.5초 제자리에 묶임 (경직은 짧아서 묶인 채 반격은 됨)
    private static boolean shackle(ServerPlayerEntity p) {
        LivingEntity t = pickTarget(p, 14, 10);
        if (t == null) { JobSkills.bar(p, "주변에 대상이 없습니다", Formatting.RED); return false; }
        Motion.play(p, "al_clap");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            clapFx(p);
            if (!t.isAlive()) return;
            Vec3d at = t.getPos();
            Double g = AlchemyFx.ground(w, at);
            double gy = g == null ? at.y : g;
            for (int i = 0; i < 6; i++) {
                double a = Math.PI * 2 * i / 6;
                AlchemyFx.pillar(w, at.x + Math.cos(a) * 0.7, gy, at.z + Math.sin(a) * 0.7, 0.42f, 0.95f, 30);
            }
            Alchemy.fx(w, BlockPos.ofFloored(at), Alch.entry(p).truth);
            Combat.hit(p, t, SHACKLE);
            hold(t, 30);
            w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.2f, 0.6f);
        });
        return true;
    }

    // ⑧ 연성 방벽 (반격): 손뼉 한 번에 앞 땅에서 벽이 솟음 → 12틱(0.6초) 동안 맞으면 패링(공격한 쪽 경직) →
    //    막아내면 벽에서 기둥이 튀어나와 앞의 상대를 날려버림. 헛치면 빈틈
    private static boolean barrier(ServerPlayerEntity p) {
        Motion.play(p, "al_godhand");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Vec3d f = Combat.horizontal(p.getRotationVec(1f));
        Vec3d side = f.crossProduct(new Vec3d(0, 1, 0)).normalize();
        Vec3d at = p.getPos().add(f.multiply(1.3));
        Double g = AlchemyFx.ground(w, at);
        if (g != null) for (int s2 = -1; s2 <= 1; s2++)
            AlchemyFx.pillar(w, at.x + side.x * s2 * 0.9, g, at.z + side.z * s2 * 0.9, 0.9f, 2.2f, 24);
        clapFx(p);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 1.0f, 0.8f);
        Combat.parryWindow(p, 12);
        long before = Combat.parryLock(p);
        boolean[] done = {false};
        for (int t = 1; t <= 13; t++) {
            Tasks.later(t, () -> {
                if (done[0] || p.isDisconnected() || !p.isAlive() || Combat.parryLock(p) <= before) return;
                done[0] = true;
                for (LivingEntity v : Combat.targets(p, 4.5, 70, 3)) {
                    Double gv = AlchemyFx.ground(w, v.getPos());
                    if (gv != null) AlchemyFx.pillar(w, v.getX(), gv, v.getZ(), 1.0f, 2.4f, 18);
                    Combat.hit(p, v, COUNTER);
                    Fx.word(w, v.getPos().add(0, 2.4, 0), Fx.BLUNT, 1.3f);
                }
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.PLAYERS, 1.2f, 0.6f);
            });
        }
        return true;
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
    private static final int AWAKEN_SCENE = 170, ULT_SCENE = 100;

    static void cutscene(ServerPlayerEntity actor, String shot, LivingEntity target, List<ServerPlayerEntity> to) {
        CutscenePayload pl = new CutscenePayload(shot, actor.getId(), target == null ? -1 : target.getId());
        for (ServerPlayerEntity v : to)
            if (ServerPlayNetworking.canSend(v, CutscenePayload.ID)) ServerPlayNetworking.send(v, pl);
    }

    @Override
    public void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        // 컷신 170틱 ≈ 8.5초 (참고: 다른 게임의 에드워드 연출 + 각성 음성): 먼지 속 발 → 얼굴까지 훑어 올라감 → 얼굴 앞 합장 손뼉(40) →
        //   두 팔을 들었다가 땅을 내리침(66, 거대한 연성진) → 사방에서 가시 숲이 솟고 먼지 속에서 코트가 찢겨 날아감(82) →
        //   천천히 일어나(100~114) 한 발 내딛으며 오토메일 손바닥을 앞으로(138). 가시는 연출만 (피해 없음)
        // 컷신 동안 무적이고 각성 시간은 그만큼 늘려 줌. 각성 중엔 테마곡 (주변 24칸 사람도)
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "al_awaken");
        cutscene(p, "al_awaken", null, List.of(p));
        Combat.invuln(p, AWAKEN_SCENE);
        Combat.afterSkill(p, AWAKEN_SCENE - 6);
        Awaken.extend(p, AWAKEN_SCENE);
        AlchemySounds.voice(p, AlchemySounds.AWAKEN, List.of(p));   // 컷신 카메라가 멀어도 크게
        startTheme(p);
        for (int t = 0; t <= 28; t += 4) Tasks.later(t, () -> { if (!p.isDisconnected()) Fx.smoke(w, p.getPos().add(0, 0.1, 0), 8, 1.0); });
        Tasks.later(40, () -> {
            if (p.isDisconnected()) return;
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.9f, 1.9f);
            AlchemySounds.play(p, AlchemySounds.SPARK, 1.0f);
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX() + f.x * 0.35, p.getY() + 1.6, p.getZ() + f.z * 0.35, 16, 0.1, 0.1, 0.1, 0.15);
        });
        for (int t = 42; t < 64; t += 3) {   // 합장한 손에 연성 에너지가 모임
            Tasks.later(t, () -> {
                if (p.isDisconnected()) return;
                Vec3d f = Combat.horizontal(p.getRotationVec(1f));
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX() + f.x * 0.35, p.getY() + 1.6, p.getZ() + f.z * 0.35, 3, 0.15, 0.15, 0.15, 0.05);
            });
        }
        Tasks.later(66, () -> {
            if (p.isDisconnected()) return;
            Alchemy.bigFx(p);
            Alchemy.fx(w, p.getBlockPos(), Alch.entry(p).truth);
            AlchemySounds.play(p, AlchemySounds.SPARK, 1.4f);
            Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.5, 8.0, Fx.WHITE, 12);
            Fx.shakeAround(w, p.getPos(), 12, 0.8f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.9f, 0.5f);
        });
        Tasks.later(82, () -> {
            if (p.isDisconnected()) return;
            Vec3d c = p.getPos();
            java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
            for (int ring = 0; ring < 5; ring++) {
                final double rad = 2.2 + ring * 1.4;
                final int rg = ring;
                Tasks.later(ring * 2, () -> {
                    int n = (int) (rad * 3.2);
                    for (int i = 0; i < n; i++) {
                        double a = Math.PI * 2 * i / n + r.nextDouble(0.3);
                        double x = c.x + Math.cos(a) * rad, z = c.z + Math.sin(a) * rad;
                        Double g = AlchemyFx.ground(w, new Vec3d(x, c.y, z));
                        if (g != null) AlchemyFx.spike(w, x, g, z, 0.8f + r.nextFloat() * 0.5f, 2.8f + rg * 0.7f + r.nextFloat() * 1.6f, 80 - rg * 2);
                    }
                    Fx.smoke(w, c.add(0, 0.5, 0), 22, rad);
                    w.playSound(null, BlockPos.ofFloored(c), SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 1.6f, 0.5f + rg * 0.08f);
                });
            }
            coatBurst(p);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.7f, 0.6f);
        });
        for (int t = 90; t <= 110; t += 5) Tasks.later(t, () -> { if (!p.isDisconnected()) Fx.smoke(w, p.getPos().add(0, 0.4, 0), 14, 3.0); });
        Tasks.later(138, () -> {
            if (p.isDisconnected()) return;
            Vec3d r = rightSide(p, 0.3).add(Combat.horizontal(p.getRotationVec(1f)).multiply(0.7));
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, r.x, p.getY() + 1.4, r.z, 12, 0.1, 0.1, 0.1, 0.12);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.0f, 1.3f);
        });
        Tasks.later(AWAKEN_SCENE - 6, () -> {
            if (!p.isDisconnected() && awake(p))
                JobSkills.bar(p, "✦ 손뼉 연성 — 기술이 바뀜: 창조의 기둥 · 신의 손 · 가시밭 · 최후의 일격  (G = 대지의 심판)", Formatting.AQUA);
        });
    }

    @Override
    public void onAwakenEnd(ServerPlayerEntity p, PlayerData.Entry e) { stopTheme(p); }

    /** 각성 테마곡: 각성한 사람과 주변 24칸 사람에게 (각성이 끝나면 같은 사람들에게서 멈춤) */
    private static final Map<UUID, List<UUID>> THEME_LISTENERS = new HashMap<>();
    private static void startTheme(ServerPlayerEntity p) {
        List<UUID> ls = new ArrayList<>();
        CutscenePayload pl = new CutscenePayload("#music:" + AlchemySounds.THEME, p.getId(), -1);
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
            if (o != p && o.squaredDistanceTo(p) > 24 * 24) continue;
            if (!ServerPlayNetworking.canSend(o, CutscenePayload.ID)) continue;
            ServerPlayNetworking.send(o, pl);
            ls.add(o.getUuid());
        }
        THEME_LISTENERS.put(p.getUuid(), ls);
    }
    private static void stopTheme(ServerPlayerEntity p) {
        List<UUID> ls = THEME_LISTENERS.remove(p.getUuid());
        if (ls == null || p.getServer() == null) return;
        CutscenePayload pl = new CutscenePayload("#music_stop", p.getId(), -1);
        for (UUID id : ls) {
            ServerPlayerEntity o = p.getServer().getPlayerManager().getPlayer(id);
            if (o != null && ServerPlayNetworking.canSend(o, CutscenePayload.ID)) ServerPlayNetworking.send(o, pl);
        }
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

    // 각성기 '대지의 심판' (컷신 100틱, 대사 "망할 진리와 함께.. 네놈들도 날려주마!" 에 맞춤): 두 팔을 머리 위로 들고 버팀 — 연성 에너지가 모이고
    //   발밑 연성진이 커짐 → 크게 손뼉(50) → 땅을 내리침(60): 대상 발밑마다 연성진 → 양옆에서 거대한 바위 손이 솟아(62)
    //   박수로 짓누름(74, 가드 깨기·다운) → 둘레로 가시. 걸린 상대는 박수까지 묶이고 (플레이어면) 같은 컷신을 봄
    //   현자의 돌을 지니면 붉은 연성 (각성 30초 연장 · 재사용 초기화)
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
        Awaken.extend(p, ULT_SCENE);
        List<ServerPlayerEntity> viewers = new ArrayList<>();
        viewers.add(p);
        for (LivingEntity t : ts) { hold(t, 74); if (t instanceof ServerPlayerEntity sp) viewers.add(sp); }
        cutscene(p, "al_ult", ts.get(0), viewers);
        AlchemySounds.voice(p, AlchemySounds.GROUND, viewers);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        for (int i = 8; i < 50; i += 3) {
            final int ii = i;
            Tasks.later(i, () -> {
                if (p.isDisconnected()) return;
                double r = 1.0 + ii * 0.05;
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 0.2, p.getZ(), 10, r, 0.1, r, 0.2);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.getX(), p.getY() + 2.4, p.getZ(), 4, 0.2, 0.2, 0.2, 0.1);
                if (ii % 12 == 8) Alchemy.fx(w, p.getBlockPos(), true);
            });
        }
        Tasks.later(50, () -> { clapFx(p); w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.5f, 1.8f); });
        Tasks.later(60, () -> {
            if (p.isDisconnected()) return;
            Alchemy.bigFx(p);
            AlchemySounds.play(p, AlchemySounds.SPARK, 1.4f);
            Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.5, 9.0, Fx.WHITE, 10);
            Fx.shakeAround(w, p.getPos(), 14, 0.8f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 1.0f, 0.5f);
            for (LivingEntity t : ts) Alchemy.fx(w, t.getBlockPos(), true);
        });
        Tasks.later(62, () -> {
            Vec3d fwd = Combat.horizontal(p.getRotationVec(1f));
            Vec3d s = fwd.crossProduct(new Vec3d(0, 1, 0)).normalize();
            for (LivingEntity t : ts) {
                if (!t.isAlive()) continue;
                Vec3d at = t.getPos();
                Double gy = AlchemyFx.ground(w, at);
                double y = gy == null ? at.y : gy;
                GodHandFx.play(w, at, y, 12, 16, Math.atan2(-s.z, -s.x));
                GodHandFx.play(w, at, y, 12, 16, Math.atan2(s.z, s.x));
            }
        });
        Tasks.later(74, () -> {
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
        Tasks.later(80, () -> {
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
        AlchemySounds.play(p, AlchemySounds.SPARK, 1.0f);   // 연성음
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
            if (SPEAR_UNTIL.containsKey(h.getPlayer().getUuid())) endSpear(h.getPlayer());
            if (BLADE_UNTIL.containsKey(h.getPlayer().getUuid())) endBlade(h.getPlayer());
            stopTheme(h.getPlayer());
        });
    }
}
