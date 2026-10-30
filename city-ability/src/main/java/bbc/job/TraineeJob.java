package bbc.job;

import bbc.combat.Combat;
import bbc.combat.Hit;
import bbc.combat.Motion;
import bbc.core.PlayerData;
import bbc.core.Tasks;
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
import net.minecraft.util.math.Vec3d;

/**
 * 수련생 — 시험용 직업. 기술이 지상판/공중판으로 나뉘어서 ABA처럼 공중 콤보 중에 기술을 끼워 넣을 수 있다.
 *   예) 평타 2 → 스페이스 누른 채 평타(같이 뜸) → 공중 평타 → X(공중 올려치기) → 공중 평타 → 스페이스 떼고 평타(다운 슬램) → 튕길 때 Z(돌진 베기)
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
    @Override public String[] summary() { return new String[]{"시험용 직업 (전투 엔진 확인용)"}; }

    private static final JobSkills.Skill[] SKILLS = {
            new JobSkills.Skill("돌진 베기", "돌진", 1, 15, 3, "앞으로 돌진하며 첫 대상을 벰 (공중에서도 됨, 공중 상대는 계속 떠 있음)"),
            new JobSkills.Skill("올려치기", "올려", 1, 20, 5, "지상: 띄움 / 공중: 한 번 더 띄워 공중 콤보를 이어 감"),
            new JobSkills.Skill("밀쳐내기", "밀침", 1, 25, 8, "지상: 주위를 날려 다운 / 공중: 앞 대상을 땅에 내리꽂음"),
            new JobSkills.Skill("숨 고르기", "회복", 1, 30, 15, "체력 6 회복"),
            new JobSkills.Skill("도약", "도약", 1, 15, 4, "지상: 높이 뜀 / 공중: 2단 점프로 띄운 상대를 쫓아감"),
            new JobSkills.Skill("집중", "집중", 2, 40, 30, "10초 동안 신속 II (숙련 수련생부터)"),
    };

    // 기술 판정
    private static final Hit DASH_CUT = new Hit(1.5f, Hit.Kind.HIT, 16, 0.15, 0, 15, false, false);
    private static final Hit RISE = new Hit(2.5f, Hit.Kind.AIR, 24, 0.05, 0.85, 25, false, false);
    private static final Hit AIR_RISE = new Hit(2f, Hit.Kind.AIR, 22, 0.05, 0.55, 20, false, false);
    private static final Hit BLAST = new Hit(2f, Hit.Kind.KNOCK, 12, 1.0, 0.4, 25, false, false);
    private static final Hit SPIKE = new Hit(3f, Hit.Kind.DOWN, 20, 0.1, -1.7, 25, false, false);

    private static final JobSkills.Set SET = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, PlayerData.Entry e) { return SKILLS; }

        @Override
        public boolean use(ServerPlayerEntity p, PlayerData.Entry e, int skill) {
            ServerWorld w = p.getServerWorld();
            boolean air = !p.isOnGround();
            Vec3d look = Combat.horizontal(p.getRotationVec(1f));
            switch (skill) {
                case 0 -> { // 돌진 베기
                    Motion.play(p, "dash_attack");
                    Combat.move(p, new Vec3d(look.x * 1.1, air ? 0.12 : 0.1, look.z * 1.1));
                    Tasks.later(3, () -> {
                        for (LivingEntity t : Combat.targets(p, 2.8, 50, 1)) Combat.hit(p, t, DASH_CUT);
                        if (!p.isOnGround()) Combat.move(p, new Vec3d(p.getVelocity().x * 0.3, 0.12, p.getVelocity().z * 0.3));
                    });
                }
                case 1 -> { // 올려치기 / 공중 올려치기
                    var ts = Combat.targets(p, 3.3, 40, 2);
                    if (ts.isEmpty()) { JobSkills.bar(p, "앞에 대상이 없습니다", Formatting.GRAY); return false; }
                    Motion.play(p, "launch");
                    Hit h = air ? AIR_RISE : RISE;
                    for (LivingEntity t : ts) Combat.hit(p, t, h);
                    if (air) Combat.move(p, new Vec3d(0, AIR_RISE.lift(), 0));
                }
                case 2 -> {
                    if (air) { // 내려꽂기
                        var ts = Combat.targets(p, 3.3, 45, 2);
                        if (ts.isEmpty()) { JobSkills.bar(p, "앞에 대상이 없습니다", Formatting.GRAY); return false; }
                        Motion.play(p, "slam");
                        for (LivingEntity t : ts) Combat.hit(p, t, SPIKE);
                        Combat.move(p, new Vec3d(0, -0.8, 0));
                    } else { // 밀쳐내기
                        Motion.play(p, "spin");
                        for (Entity en : w.getOtherEntities(p, p.getBoundingBox().expand(4), x -> x instanceof LivingEntity && x.isAlive()))
                            Combat.hit(p, (LivingEntity) en, BLAST);
                        w.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX(), p.getY() + 1, p.getZ(), 6, 1.5, 0.2, 1.5, 0);
                    }
                }
                case 3 -> { Motion.play(p, "heal"); p.heal(6f); }
                case 4 -> { // 도약 / 2단 점프
                    Motion.play(p, "jump_air");
                    Combat.move(p, air ? new Vec3d(look.x * 0.4, 0.65, look.z * 0.4) : new Vec3d(look.x * 0.3, 1.1, look.z * 0.3));
                }
                case 5 -> { Motion.play(p, "focus"); p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 200, 1)); }
                default -> { return false; }
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.2f);
            return true;
        }
    };

    @Override public JobSkills.Set skills() { return SET; }
}
