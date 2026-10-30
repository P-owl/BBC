package bbc.job;

import bbc.combat.Combat;
import bbc.combat.Hit;
import bbc.core.PlayerData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * 수련생 — 0단계 시험용 직업. 직업 틀·장착 칸·스태미나·전투 모드가 도는지 확인하는 용도.
 * 실제 직업들이 들어오면 지운다.
 */
public final class TraineeJob implements JobType {
    public static final TraineeJob INSTANCE = new TraineeJob();
    private TraineeJob() {}

    @Override public String id() { return "trainee"; }
    @Override public String name() { return "수련생"; }
    @Override public String prefix() { return "tr."; }
    @Override public Item icon() { return Items.WOODEN_SWORD; }
    @Override public Formatting color() { return Formatting.WHITE; }
    @Override public int maxRank() { return 2; }
    @Override public String rankName(int rank) { return rank >= 2 ? "숙련 수련생" : "수련생"; }
    @Override public String[] summary() { return new String[]{"시험용 직업 (0단계 확인용)"}; }

    private static final JobSkills.Skill[] SKILLS = {
            new JobSkills.Skill("돌진", "돌진", 1, 15, 3, "바라보는 방향으로 짧게 돌진"),
            new JobSkills.Skill("올려치기", "올려", 1, 20, 5, "앞 3칸 대상을 띄움 (공중 경직, 평타로 이어짐)"),
            new JobSkills.Skill("밀쳐내기", "밀침", 1, 25, 8, "주위 4칸을 날려보내 다운"),
            new JobSkills.Skill("숨 고르기", "회복", 1, 30, 15, "체력 6 회복"),
            new JobSkills.Skill("도약", "도약", 1, 15, 4, "높이 뛰어오름"),
            new JobSkills.Skill("집중", "집중", 2, 40, 30, "10초 동안 신속 II (숙련 수련생부터)"),
    };

    private static final JobSkills.Set SET = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, PlayerData.Entry e) { return SKILLS; }

        @Override
        public boolean use(ServerPlayerEntity p, PlayerData.Entry e, int skill) {
            ServerWorld w = p.getServerWorld();
            Vec3d look = p.getRotationVec(1f);
            switch (skill) {
                case 0 -> push(p, new Vec3d(look.x * 1.6, 0.2, look.z * 1.6));
                case 1 -> {
                    LivingEntity t = front(p, 3);
                    if (t == null) { JobSkills.bar(p, "앞에 대상이 없습니다", Formatting.GRAY); return false; }
                    // 평타 콤보에 끼워 넣는 시작기: 띄우고 공중 경직 24틱
                    Combat.hit(p, t, new Hit(3f, Hit.Kind.AIR, 24, 0.05, 0.85, 25, false, false));
                }
                case 2 -> {
                    // 주위를 날려보내고 다운 (가드는 깨지지 않음)
                    for (Entity en : w.getOtherEntities(p, p.getBoundingBox().expand(4), x -> x instanceof LivingEntity && x.isAlive()))
                        Combat.hit(p, (LivingEntity) en, new Hit(2f, Hit.Kind.KNOCK, 12, 1.0, 0.4, 25, false, false));
                    w.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 1, p.getZ(), 6, 1.5, 0.2, 1.5, 0);
                }
                case 3 -> p.heal(6f);
                case 4 -> push(p, new Vec3d(look.x * 0.3, 1.1, look.z * 0.3));
                case 5 -> p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 1));
                default -> { return false; }
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.2f);
            return true;
        }
    };

    @Override public JobSkills.Set skills() { return SET; }

    private static void push(ServerPlayerEntity p, Vec3d v) {
        p.setVelocity(v);
        p.velocityModified = true;
    }

    private static LivingEntity front(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), look = p.getRotationVec(1f);
        Box box = p.getBoundingBox().stretch(look.multiply(range)).expand(1);
        List<Entity> list = p.getServerWorld().getOtherEntities(p, box, x -> x instanceof LivingEntity && x.isAlive());
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity en : list) {
            Vec3d to = en.getPos().add(0, en.getHeight() / 2, 0).subtract(eye);
            if (to.length() > range + 1 || to.normalize().dotProduct(look) < 0.5) continue;
            if (to.length() < bestD) { bestD = to.length(); best = (LivingEntity) en; }
        }
        return best;
    }
}
