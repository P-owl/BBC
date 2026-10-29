package bus.muhyeon.duel;

import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static bus.muhyeon.duel.Pillar.hit;
import static bus.muhyeon.duel.Pillar.flat;

/**
 * 기둥 속 사내 4루트 기술 (비용 = 스태미나). 이름은 무현버스 자체 이름, 원작 모티브는 주석에.
 */
public final class Routes {
    private Routes() {}

    private static JobSkills.Skill s(String n, String sh, int cost, int cd, String d) { return new JobSkills.Skill(n, sh, 3, cost, cd, d); }

    // 바람 (와무우 모티브)
    static final JobSkills.Skill[] WIND = {
            s("풍압 장권", "풍압", 20, 5, "압축한 공기를 날려 앞 8칸 10 피해 + 밀쳐냄"),
            s("바람의 장막", "장막", 35, 25, "6초 동안 투명 + 날아오는 투사체를 튕겨냄"),
            s("기압 칼날", "칼날", 30, 10, "숨으로 만든 기압 칼날, 직선 16칸 12 피해"),
            s("상승 기류", "기류", 20, 12, "발밑 회오리로 높이 솟구치고 낙하 피해 없음"),
            s("진공의 울타리", "진공", 40, 22, "6칸 안 상대를 끌어당기고 4초 숨을 막음"),
            s("신의 모래폭풍", "폭풍", 60, 30, "양팔을 반대로 돌려 직선 25칸 토네이도, 18 피해 + 휘말림"),
            s("최후의 대기", "대기", 80, 120, "체력 절반을 내고 몸 전체를 바람 칼날로 — 주위 8칸 30 피해"),
    };
    // 열 (에시디시 모티브)
    static final JobSkills.Skill[] HEAT = {
            s("끓는 혈관", "혈관", 25, 6, "혈관 바늘 5개, 맞은 상대 4초 동안 초당 3 + 불"),
            s("열사 방벽", "방벽", 30, 20, "5초 동안 나를 때린 상대에게 불 + 4 피해"),
            s("혈관 거미", "거미", 30, 15, "떼어낸 살점이 상대를 쫓아가 붙고 6초 뒤 터져 12 피해"),
            s("울어서 식히기", "울음", 10, 60, "체력 절반 이하일 때만 — 2초 울고 체력 10 + 10초 공격력 +6"),
            s("용암 분출", "용암", 45, 25, "발밑 5칸을 8초 동안 끓여 올림, 밟으면 불 + 초당 2"),
            s("분노의 열 (패시브)", "분노", 0, 1, "맞을 때마다 열이 쌓여 최대 5중첩, 중첩당 공격력 +2 (10초 유지)"),
            s("전신 비등", "비등", 50, 40, "쌓인 열을 모두 터뜨려 주위 7칸에 중첩당 6 피해 + 불"),
    };
    // 빛 (카즈 모티브)
    static final JobSkills.Skill[] LIGHT = {
            s("빛의 칼날", "칼날", 25, 25, "팔에서 빛나는 칼날 20초, 공격력 +10, 벤 상대 출혈 (초당 2, 3초)"),
            s("난무", "난무", 30, 8, "칼날이 나와 있을 때 앞으로 6칸 돌진하며 3번 베기 (각 8)"),
            s("눈부신 반사", "반사", 25, 18, "칼날에 빛을 반사해 앞 12칸 상대 3초 실명 + 발광"),
            s("생물 융합", "융합", 20, 20, "물속이면 20초 수중 호흡·헤엄, 아니면 10초 날개 활공"),
            s("지략", "지략", 30, 25, "10초 안에 받는 공격 하나를 읽고 피한 뒤 상대 뒤로 이동"),
            s("가시 뼈", "가시", 40, 15, "몸에서 뼈 가시를 솟구쳐 주위 5칸 14 피해"),
            s("만개의 칼날", "만개", 60, 45, "8초 동안 모든 근접 공격이 주위 3칸까지 같은 피해로 베어짐"),
    };
    // 흡수 (산타나 모티브)
    static final JobSkills.Skill[] ABSORB = {
            s("육체 흡수", "흡수", 25, 8, "3칸 상대를 붙잡고 3초 동안 초당 5 흡수 (멀어지면 끊김)"),
            s("늑골 칼날", "늑골", 25, 6, "갈비뼈를 펼쳐 앞 3칸 4번 베기 (각 5)"),
            s("틈새 침투", "틈새", 20, 10, "몸을 접어 앞 3칸 안의 벽을 뚫고 지나감"),
            s("체내 잠입", "잠입", 45, 25, "4칸 상대 몸속에 파고들어 3초 무적, 나올 때 16 피해"),
            s("혈관 실", "혈관", 20, 10, "손가락 혈관을 늘려 8칸 상대를 끌어옴"),
            s("탈피", "탈피", 30, 30, "몸 일부를 버리고 모든 해로운 효과 제거 + 2초 무적"),
            s("포식", "포식", 60, 45, "5초 동안 주위 6칸 모든 생명체에게서 초당 4씩 빨아들임"),
    };

    static JobSkills.Skill[] list(String route) {
        return switch (route) {
            case Pillar.WIND -> WIND;
            case Pillar.HEAT -> HEAT;
            case Pillar.LIGHT -> LIGHT;
            case Pillar.ABSORB -> ABSORB;
            default -> new JobSkills.Skill[0];
        };
    }

    static boolean use(ServerPlayerEntity p, AlchemyData.Entry e, String route, int i) {
        return switch (route) {
            case Pillar.WIND -> switch (i) { case 0 -> windFist(p); case 1 -> windVeil(p); case 2 -> pressureBlade(p); case 3 -> updraft(p);
                case 4 -> vacuum(p); case 5 -> sandstorm(p); case 6 -> lastBreath(p); default -> false; };
            case Pillar.HEAT -> switch (i) { case 0 -> veins(p); case 1 -> heatWall(p); case 2 -> spider(p); case 3 -> cry(p);
                case 4 -> lava(p); case 5 -> passive(p); case 6 -> boil(p); default -> false; };
            case Pillar.LIGHT -> switch (i) { case 0 -> blade(p); case 1 -> rampage(p); case 2 -> dazzle(p); case 3 -> fusion(p);
                case 4 -> scheme(p); case 5 -> boneSpikes(p); case 6 -> bloom(p); default -> false; };
            case Pillar.ABSORB -> switch (i) { case 0 -> absorbGrab(p); case 1 -> ribs(p); case 2 -> slip(p); case 3 -> burrow(p);
                case 4 -> veinThread(p); case 5 -> molt(p); case 6 -> devour(p); default -> false; };
            default -> false;
        };
    }

    // ───────── 공용 ─────────

    static final DustParticleEffect WIND_DUST = new DustParticleEffect(new Vector3f(0.85f, 0.95f, 1f), 1.0f);
    static final DustParticleEffect HEAT_DUST = new DustParticleEffect(new Vector3f(1f, 0.35f, 0.05f), 1.2f);
    static final DustParticleEffect LIGHT_DUST = new DustParticleEffect(new Vector3f(1f, 0.95f, 0.6f), 1.0f);
    static final DustParticleEffect FLESH = new DustParticleEffect(new Vector3f(0.75f, 0.25f, 0.3f), 1.0f);

    static List<Entity> around(ServerPlayerEntity p, Vec3d c, double r) {
        return p.getServerWorld().getOtherEntities(p, new Box(c, c).expand(r), x -> Targets.canHit(p, x) && x.squaredDistanceTo(c) <= r * r);
    }

    /** 앞쪽 원뿔 (cos = 최소 내적) */
    static List<Entity> cone(ServerPlayerEntity p, double range, double cos) {
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        return p.getServerWorld().getOtherEntities(p, p.getBoundingBox().expand(range), x -> {
            if (!Targets.canHit(p, x)) return false;
            Vec3d to = x.getPos().add(0, x.getHeight() / 2, 0).subtract(eye);
            return to.length() <= range && to.normalize().dotProduct(look) >= cos;
        });
    }

    /** 직선 (폭 w) */
    static List<Entity> line(ServerPlayerEntity p, Vec3d from, Vec3d dir, double len, double w) {
        return p.getServerWorld().getOtherEntities(p, new Box(from, from.add(dir.multiply(len))).expand(w + 1), x -> {
            if (!Targets.canHit(p, x)) return false;
            Vec3d c = x.getPos().add(0, x.getHeight() / 2, 0);
            double along = c.subtract(from).dotProduct(dir);
            return along >= 0 && along <= len && c.distanceTo(from.add(dir.multiply(along))) <= w + x.getWidth() / 2;
        });
    }

    static void push(Entity t, Vec3d from, double h, double up) {
        Vec3d d = t.getPos().subtract(from);
        d = new Vec3d(d.x, 0, d.z);
        if (d.lengthSquared() < 1e-4) d = new Vec3d(0, 0, 1);
        d = d.normalize();
        t.addVelocity(d.x * h, up, d.z * h);
        t.velocityModified = true;
    }

    static void sound(ServerPlayerEntity p, net.minecraft.sound.SoundEvent s, float vol, float pitch) {
        p.getServerWorld().playSound(null, p.getBlockPos(), s, SoundCategory.PLAYERS, vol, pitch);
    }

    static void dot(ServerPlayerEntity p, Entity t, float perSec, int seconds, DustParticleEffect fx) {
        for (int i = 1; i <= seconds; i++) Tasks.later(i * 20, () -> {
            if (!t.isAlive() || p.isDisconnected()) return;
            t.timeUntilRegen = 0;
            hit(p, t, perSec);
            if (t.getWorld() instanceof ServerWorld w) w.spawnParticles(fx, t.getX(), t.getY() + 1, t.getZ(), 6, 0.3, 0.4, 0.3, 0);
        });
    }

    // ═════════ 바람 ═════════

    private static boolean windFist(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        for (int i = 0; i < 6; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                Vec3d q = eye.add(look.multiply(1.3 * (k + 1)));
                w.spawnParticles(ParticleTypes.CLOUD, q.x, q.y, q.z, 6, 0.3 + k * 0.1, 0.3 + k * 0.1, 0.3 + k * 0.1, 0.02);
                w.spawnParticles(ParticleTypes.SWEEP_ATTACK, q.x, q.y, q.z, 1, 0, 0, 0, 0);
            });
        }
        for (Entity t : cone(p, 8, 0.85)) { if (hit(p, t, 10)) push(t, p.getPos(), 1.6, 0.4); }
        sound(p, SoundEvents.ENTITY_BREEZE_SHOOT, 1.5f, 0.8f);
        return true;
    }

    private static final Map<UUID, Long> VEIL = new HashMap<>();

    private static boolean windVeil(ServerPlayerEntity p) {
        VEIL.put(p.getUuid(), System.currentTimeMillis() + 6000);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 120, 0, false, false));
        ServerWorld w = p.getServerWorld();
        for (int t = 0; t < 120; t += 2) {
            Tasks.later(t, () -> {
                if (p.isDisconnected()) return;
                for (int i = 0; i < 4; i++) {
                    double a = Math.random() * Math.PI * 2;
                    w.spawnParticles(WIND_DUST, p.getX() + Math.cos(a) * 1.2, p.getY() + Math.random() * 2, p.getZ() + Math.sin(a) * 1.2, 1, 0, 0, 0, 0);
                }
                for (ProjectileEntity pr : w.getEntitiesByClass(ProjectileEntity.class, p.getBoundingBox().expand(2.5), x -> x.getOwner() != p)) {
                    pr.setVelocity(pr.getVelocity().multiply(-1.2));
                    pr.velocityModified = true;
                    w.spawnParticles(ParticleTypes.CLOUD, pr.getX(), pr.getY(), pr.getZ(), 4, 0.1, 0.1, 0.1, 0.05);
                }
            });
        }
        sound(p, SoundEvents.ENTITY_BREEZE_IDLE_GROUND, 1.5f, 0.7f);
        return true;
    }

    private static boolean pressureBlade(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        for (int i = 0; i < 16; i += 2) {
            final int k = i;
            Tasks.later(i / 4, () -> {
                Vec3d q = eye.add(look.multiply(k + 1));
                w.spawnParticles(ParticleTypes.SWEEP_ATTACK, q.x, q.y, q.z, 1, 0, 0, 0, 0);
                w.spawnParticles(WIND_DUST, q.x, q.y, q.z, 4, 0.4, 0.05, 0.4, 0);
            });
        }
        for (Entity t : line(p, eye, look, 16, 0.8)) hit(p, t, 12);
        sound(p, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 1.6f);
        return true;
    }

    private static boolean updraft(ServerPlayerEntity p) {
        p.setVelocity(p.getVelocity().x, 1.6, p.getVelocity().z);
        p.velocityModified = true;
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 100, 0, false, false));
        ServerWorld w = p.getServerWorld();
        for (int t = 0; t < 20; t += 2) Tasks.later(t, () -> {
            for (int i = 0; i < 10; i++) {
                double a = Math.PI * 2 * i / 10;
                w.spawnParticles(ParticleTypes.CLOUD, p.getX() + Math.cos(a), p.getY() - 1, p.getZ() + Math.sin(a), 1, 0, 0.3, 0, 0.05);
            }
        });
        sound(p, SoundEvents.ENTITY_BREEZE_JUMP, 1.5f, 0.8f);
        return true;
    }

    private static boolean vacuum(ServerPlayerEntity p) {
        List<Entity> ts = around(p, p.getPos(), 6);
        if (ts.isEmpty()) { JobSkills.bar(p, "6칸 안에 상대가 없습니다", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        for (Entity t : ts) {
            Vec3d d = p.getPos().subtract(t.getPos()).normalize();
            t.setVelocity(d.x * 1.2, 0.3, d.z * 1.2);
            t.velocityModified = true;
            if (t instanceof LivingEntity le) {
                le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 2));
                if (t instanceof ServerPlayerEntity sp) sp.setAir(0);
                dot(p, t, 1.5f, 4, WIND_DUST);
            }
        }
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2 * i / 40;
            w.spawnParticles(ParticleTypes.CLOUD, p.getX() + Math.cos(a) * 6, p.getY() + 1, p.getZ() + Math.sin(a) * 6, 0, -Math.cos(a), 0, -Math.sin(a), 0.4);
        }
        sound(p, SoundEvents.ENTITY_BREEZE_INHALE, 2f, 0.6f);
        return true;
    }

    private static boolean sandstorm(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d dir = flat(p), start = p.getPos();
        Set<UUID> done = new HashSet<>();
        sound(p, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 2f, 0.5f);
        for (int k = 0; k < 25; k++) {
            final int step = k;
            Tasks.later(k, () -> {
                Vec3d c = start.add(dir.multiply(step + 1));
                for (int i = 0; i < 14; i++) {
                    double a = step * 0.7 + i * Math.PI * 2 / 14, h = (i % 7) * 0.45, r = 0.5 + h * 0.35;
                    w.spawnParticles(ParticleTypes.CLOUD, c.x + Math.cos(a) * r, c.y + h, c.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
                    if (i % 3 == 0) w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.SAND.getDefaultState()), c.x, c.y + h, c.z, 1, 0.3, 0.1, 0.3, 0);
                }
                for (Entity t : around(p, c.add(0, 1, 0), 2.0)) {
                    if (!done.add(t.getUuid())) continue;
                    if (hit(p, t, 18)) { t.setVelocity(dir.x * 1.2, 0.9, dir.z * 1.2); t.velocityModified = true; }
                }
            });
        }
        return true;
    }

    private static boolean lastBreath(ServerPlayerEntity p) {
        p.setHealth(Math.max(1, p.getHealth() / 2));
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, p.getX(), p.getY() + 1, p.getZ(), 1, 0, 0, 0, 0);
        for (int r = 1; r <= 8; r++) {
            final int rr = r;
            Tasks.later(r, () -> {
                for (int i = 0; i < 24; i++) {
                    double a = Math.PI * 2 * i / 24;
                    w.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX() + Math.cos(a) * rr, p.getY() + 1, p.getZ() + Math.sin(a) * rr, 1, 0, 0, 0, 0);
                }
            });
        }
        for (Entity t : around(p, p.getPos(), 8)) { if (hit(p, t, 30)) push(t, p.getPos(), 2, 0.8); }
        sound(p, SoundEvents.ENTITY_BREEZE_WIND_BURST.value(), 3f, 0.4f);
        sound(p, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.2f);
        return true;
    }

    // ═════════ 열 ═════════

    private static boolean veins(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        Vec3d side = new Vec3d(-look.z, 0, look.x).normalize();
        Set<UUID> done = new HashSet<>();
        for (int n = -2; n <= 2; n++) {
            Vec3d d = look.add(side.multiply(n * 0.08)).normalize();
            for (int i = 1; i <= 8; i++) {
                Vec3d q = eye.add(d.multiply(i));
                Tasks.later(i / 3, () -> w.spawnParticles(HEAT_DUST, q.x, q.y, q.z, 2, 0.02, 0.02, 0.02, 0));
            }
            for (Entity t : line(p, eye, d, 8, 0.3)) {
                if (!done.add(t.getUuid())) continue;
                if (hit(p, t, 3)) { t.setOnFireFor(4); dot(p, t, 3, 4, HEAT_DUST); }
            }
        }
        sound(p, SoundEvents.ENTITY_BLAZE_SHOOT, 1f, 0.6f);
        return true;
    }

    private static final Map<UUID, Long> HEAT_WALL = new HashMap<>();
    private static final Map<UUID, Integer> RAGE = new HashMap<>();
    private static final Map<UUID, Long> RAGE_UNTIL = new HashMap<>();
    private static final Identifier RAGE_ID = Identifier.of(DuelMod.MOD_ID, "pillar_rage");

    private static boolean heatWall(ServerPlayerEntity p) {
        HEAT_WALL.put(p.getUuid(), System.currentTimeMillis() + 5000);
        ServerWorld w = p.getServerWorld();
        for (int t = 0; t < 100; t += 4) Tasks.later(t, () -> w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 6, 0.4, 0.7, 0.4, 0.01));
        sound(p, SoundEvents.ITEM_FIRECHARGE_USE, 1.2f, 0.6f);
        return true;
    }

    private static boolean spider(ServerPlayerEntity p) {
        LivingEntity t = AlchemySkills.aimTarget(p, 16);
        if (t == null) { JobSkills.bar(p, "바라보는 상대가 없습니다 (16칸)", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        Vec3d[] pos = {p.getPos().add(0, 0.2, 0)};
        boolean[] stuck = {false};
        for (int i = 0; i < 160; i += 2) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!t.isAlive()) return;
                if (!stuck[0]) {
                    Vec3d to = t.getPos().add(0, 0.2, 0).subtract(pos[0]);
                    if (to.length() < 0.8) { stuck[0] = true; JobSkills.bar(p, "살점이 붙었다", Formatting.RED); }
                    else pos[0] = pos[0].add(to.normalize().multiply(0.45));
                } else pos[0] = t.getPos().add(0, t.getHeight() * 0.5, 0);
                w.spawnParticles(FLESH, pos[0].x, pos[0].y, pos[0].z, 3, 0.08, 0.04, 0.08, 0);
                if (stuck[0] && k % 20 == 0) w.spawnParticles(HEAT_DUST, pos[0].x, pos[0].y, pos[0].z, 4, 0.1, 0.1, 0.1, 0);
            });
        }
        Tasks.later(160, () -> {
            if (!stuck[0] || !t.isAlive()) return;
            t.timeUntilRegen = 0;
            hit(p, t, 12);
            t.setOnFireFor(3);
            w.spawnParticles(ParticleTypes.EXPLOSION, t.getX(), t.getY() + 1, t.getZ(), 1, 0, 0, 0, 0);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.8f, 1.5f);
        });
        sound(p, SoundEvents.ENTITY_SPIDER_AMBIENT, 1f, 0.6f);
        return true;
    }

    private static final Identifier CRY_ID = Identifier.of(DuelMod.MOD_ID, "pillar_cry");

    private static boolean cry(ServerPlayerEntity p) {
        if (p.getHealth() > p.getMaxHealth() / 2) { JobSkills.bar(p, "체력이 절반 이하일 때만 울 수 있다", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 5, false, false));
        for (int t = 0; t < 40; t += 4) Tasks.later(t, () -> w.spawnParticles(ParticleTypes.FALLING_WATER, p.getX(), p.getEyeY(), p.getZ(), 8, 0.2, 0.05, 0.2, 0));
        p.getServer().getPlayerManager().broadcast(Text.literal("💧 " + p.getName().getString() + "이(가) 엉엉 운다…").formatted(Formatting.GRAY), false);
        Tasks.later(40, () -> {
            if (!p.isAlive()) return;
            p.heal(10);
            EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            if (a != null) { a.removeModifier(CRY_ID); a.addTemporaryModifier(new EntityAttributeModifier(CRY_ID, 6, EntityAttributeModifier.Operation.ADD_VALUE)); }
            Tasks.later(200, () -> { EntityAttributeInstance b = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE); if (b != null) b.removeModifier(CRY_ID); });
            p.sendMessage(Text.literal("…후우. 열이 식었다. 이제 냉정하다.").formatted(Formatting.GOLD), false);
            w.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
        });
        return true;
    }

    private static boolean lava(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        AlchemySkills.display(w, Blocks.MAGMA_BLOCK.getDefaultState(), c.x, Math.floor(c.y) - 0.9, c.z, 10f, 1f, 10f, 0, 160, 3);
        for (int t = 0; t < 160; t += 10) {
            Tasks.later(t, () -> {
                w.spawnParticles(ParticleTypes.LAVA, c.x, c.y + 0.2, c.z, 10, 3, 0.1, 3, 0);
                for (Entity e : around(p, c, 5)) {
                    if (Math.abs(e.getY() - c.y) > 2) continue;
                    e.setOnFireFor(2);
                    e.timeUntilRegen = 0;
                    hit(p, e, 1);
                }
            });
        }
        sound(p, SoundEvents.BLOCK_LAVA_POP, 2f, 0.6f);
        return true;
    }

    private static boolean passive(ServerPlayerEntity p) {
        JobSkills.bar(p, "분노의 열은 맞을 때마다 저절로 쌓입니다 (지금 " + RAGE.getOrDefault(p.getUuid(), 0) + "중첩)", Formatting.GOLD);
        return false;
    }

    private static boolean boil(ServerPlayerEntity p) {
        int n = Math.max(1, RAGE.getOrDefault(p.getUuid(), 0));
        setRage(p, 0);
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.FLAME, p.getX(), p.getY() + 1, p.getZ(), 80 * n / 2, 2, 1, 2, 0.2);
        w.spawnParticles(ParticleTypes.EXPLOSION, p.getX(), p.getY() + 1, p.getZ(), n, 1.5, 0.5, 1.5, 0);
        for (Entity t : around(p, p.getPos(), 7)) { if (hit(p, t, 6 * n)) { t.setOnFireFor(4); push(t, p.getPos(), 1.0, 0.5); } }
        sound(p, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.5f, 0.7f);
        p.sendMessage(Text.literal("전신 비등! (" + n + "중첩)").formatted(Formatting.RED), true);
        return true;
    }

    static void setRage(ServerPlayerEntity p, int n) {
        RAGE.put(p.getUuid(), n);
        RAGE_UNTIL.put(p.getUuid(), System.currentTimeMillis() + 10_000);
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (a == null) return;
        a.removeModifier(RAGE_ID);
        if (n > 0) a.addTemporaryModifier(new EntityAttributeModifier(RAGE_ID, 2.0 * n, EntityAttributeModifier.Operation.ADD_VALUE));
    }

    // ═════════ 빛 ═════════

    private static final Map<UUID, Long> BLADE = new HashMap<>();
    private static final Map<UUID, Long> BLOOM = new HashMap<>();
    private static final Map<UUID, Long> SCHEME = new HashMap<>();
    private static final Identifier BLADE_ID = Identifier.of(DuelMod.MOD_ID, "pillar_light_blade");

    static boolean bladeOn(ServerPlayerEntity p) { Long u = BLADE.get(p.getUuid()); return u != null && u > System.currentTimeMillis(); }

    private static boolean blade(ServerPlayerEntity p) {
        BLADE.put(p.getUuid(), System.currentTimeMillis() + 20_000);
        EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (a != null) { a.removeModifier(BLADE_ID); a.addTemporaryModifier(new EntityAttributeModifier(BLADE_ID, 10, EntityAttributeModifier.Operation.ADD_VALUE)); }
        ServerWorld w = p.getServerWorld();
        for (int t = 0; t < 400; t += 5) Tasks.later(t, () -> {
            if (p.isDisconnected()) return;
            Vec3d arm = p.getPos().add(0, 1.1, 0).add(flat(p).rotateY(-0.6f).multiply(0.45)).add(p.getRotationVector().multiply(0.4));
            for (int i = 0; i < 4; i++) {
                Vec3d q = arm.add(p.getRotationVector().multiply(i * 0.25));
                w.spawnParticles(LIGHT_DUST, q.x, q.y, q.z, 1, 0, 0, 0, 0);
            }
        });
        Tasks.later(400, () -> { EntityAttributeInstance b = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE); if (b != null) b.removeModifier(BLADE_ID); });
        sound(p, SoundEvents.ITEM_TRIDENT_RIPTIDE_2.value(), 1f, 1.8f);
        sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 1.5f, 1.6f);
        return true;
    }

    private static boolean rampage(ServerPlayerEntity p) {
        if (!bladeOn(p)) { JobSkills.bar(p, "빛의 칼날을 먼저 꺼내세요", Formatting.RED); return false; }
        Vec3d d = flat(p);
        p.setVelocity(d.x * 1.8, 0.15, d.z * 1.8);
        p.velocityModified = true;
        ServerWorld w = p.getServerWorld();
        Set<UUID> hitTwice = new HashSet<>();
        for (int i = 1; i <= 3; i++) {
            Tasks.later(i * 3, () -> {
                w.spawnParticles(ParticleTypes.SWEEP_ATTACK, p.getX() + d.x, p.getY() + 1, p.getZ() + d.z, 3, 0.5, 0.3, 0.5, 0);
                w.spawnParticles(LIGHT_DUST, p.getX(), p.getY() + 1, p.getZ(), 10, 0.8, 0.5, 0.8, 0);
                for (Entity t : around(p, p.getPos().add(d), 2.5)) { t.timeUntilRegen = 0; hit(p, t, 8); bleed(p, t); hitTwice.add(t.getUuid()); }
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1f, 1.5f);
            });
        }
        return true;
    }

    private static boolean dazzle(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        List<Entity> ts = cone(p, 12, 0.8);
        for (Entity t : ts) if (t instanceof LivingEntity le) {
            le.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0));
            le.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 60, 0));
        }
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        for (int i = 1; i <= 12; i++) { Vec3d q = eye.add(look.multiply(i)); w.spawnParticles(ParticleTypes.END_ROD, q.x, q.y, q.z, 3, i * 0.08, i * 0.08, i * 0.08, 0.01); }
        w.spawnParticles(ParticleTypes.FLASH, eye.x + look.x, eye.y + look.y, eye.z + look.z, 1, 0, 0, 0, 0);
        sound(p, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 2f, 2f);
        return true;
    }

    private static boolean fusion(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        if (p.isTouchingWater()) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 400, 0));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DOLPHINS_GRACE, 400, 1));
            w.spawnParticles(ParticleTypes.BUBBLE, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.6, 0.4, 0.1);
            p.sendMessage(Text.literal("몸에 아가미가 돋는다").formatted(Formatting.AQUA), true);
        } else {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false));
            p.setVelocity(p.getVelocity().add(0, 0.9, 0));
            p.velocityModified = true;
            for (int t = 2; t < 200; t += 2) Tasks.later(t, () -> {
                if (p.isOnGround() || p.isDisconnected()) return;
                Vec3d d = p.getRotationVector();
                p.addVelocity(d.x * 0.06, 0, d.z * 0.06);
                p.velocityModified = true;
                Vec3d back = p.getPos().add(0, 1.3, 0).subtract(flat(p).multiply(0.4));
                Vec3d side = new Vec3d(-flat(p).z, 0, flat(p).x);
                w.spawnParticles(FLESH, back.x + side.x * 0.8, back.y, back.z + side.z * 0.8, 2, 0.1, 0.05, 0.1, 0);
                w.spawnParticles(FLESH, back.x - side.x * 0.8, back.y, back.z - side.z * 0.8, 2, 0.1, 0.05, 0.1, 0);
            });
            p.sendMessage(Text.literal("등에서 날개가 돋는다").formatted(Formatting.DARK_PURPLE), true);
        }
        sound(p, SoundEvents.ENTITY_PHANTOM_FLAP, 1.5f, 0.6f);
        return true;
    }

    private static boolean scheme(ServerPlayerEntity p) {
        SCHEME.put(p.getUuid(), System.currentTimeMillis() + 10_000);
        p.sendMessage(Text.literal("…다음 수는 이미 읽었다.").formatted(Formatting.DARK_PURPLE), true);
        sound(p, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 0.6f);
        return true;
    }

    private static boolean boneSpikes(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2 * i / 12, r = 1.5 + (i % 3) * 1.2;
            AlchemySkills.display(w, Blocks.BONE_BLOCK.getDefaultState(), p.getX() + Math.cos(a) * r, p.getY(), p.getZ() + Math.sin(a) * r, 0.3f, 2.2f, 0.3f, i % 3, 14, 2);
        }
        for (Entity t : around(p, p.getPos(), 5)) { if (hit(p, t, 14)) push(t, p.getPos(), 0.6, 0.6); }
        sound(p, SoundEvents.ENTITY_SKELETON_HURT, 1.5f, 0.5f);
        sound(p, SoundEvents.BLOCK_BONE_BLOCK_BREAK, 2f, 0.6f);
        return true;
    }

    private static boolean bloom(ServerPlayerEntity p) {
        BLOOM.put(p.getUuid(), System.currentTimeMillis() + 8000);
        if (!bladeOn(p)) blade(p);
        ServerWorld w = p.getServerWorld();
        for (int t = 0; t < 160; t += 4) Tasks.later(t, () -> w.spawnParticles(LIGHT_DUST, p.getX(), p.getY() + 1, p.getZ(), 8, 1.2, 0.8, 1.2, 0));
        sound(p, SoundEvents.ITEM_TRIDENT_THUNDER.value(), 0.8f, 1.8f);
        return true;
    }

    static final Map<UUID, Long> BLEED_GAP = new HashMap<>();

    static void bleed(ServerPlayerEntity p, Entity t) {
        Long g = BLEED_GAP.get(t.getUuid());
        if (g != null && g > System.currentTimeMillis()) return;
        BLEED_GAP.put(t.getUuid(), System.currentTimeMillis() + 3000);
        dot(p, t, 2, 3, Vampire.BLOOD);
    }

    // ═════════ 흡수 ═════════

    private static boolean absorbGrab(ServerPlayerEntity p) {
        LivingEntity t = AlchemySkills.aimTarget(p, 3);
        if (t == null) { JobSkills.bar(p, "붙잡을 상대가 없습니다 (3칸)", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        for (int i = 1; i <= 3; i++) {
            Tasks.later(i * 20, () -> {
                if (!t.isAlive() || p.isDisconnected() || t.squaredDistanceTo(p) > 16) return;
                t.timeUntilRegen = 0;
                if (hit(p, t, 5)) p.heal(5);
                w.spawnParticles(FLESH, t.getX(), t.getY() + 1, t.getZ(), 20, 0.3, 0.5, 0.3, 0);
                w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_HONEY_BLOCK_SLIDE, SoundCategory.PLAYERS, 1f, 0.5f);
            });
        }
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 3));
        return true;
    }

    private static boolean ribs(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 4; i++) {
            Tasks.later(i * 3, () -> {
                Vec3d c = p.getPos().add(flat(p).multiply(1.6)).add(0, 1, 0);
                w.spawnParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y, c.z, 2, 0.5, 0.3, 0.5, 0);
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BONE_BLOCK.getDefaultState()), c.x, c.y, c.z, 6, 0.4, 0.4, 0.4, 0.1);
                for (Entity t : cone(p, 3.2, 0.5)) { t.timeUntilRegen = 0; hit(p, t, 5); }
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.8f, 1.3f);
            });
        }
        return true;
    }

    private static boolean slip(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d d = flat(p);
        for (int dist = 3; dist >= 1; dist--) {
            Vec3d to = p.getPos().add(d.multiply(dist));
            BlockPos b = BlockPos.ofFloored(to);
            if (w.isSpaceEmpty(p, p.getBoundingBox().offset(to.subtract(p.getPos())))) {
                w.spawnParticles(FLESH, p.getX(), p.getY() + 1, p.getZ(), 20, 0.3, 0.5, 0.3, 0);
                p.requestTeleport(to.x, to.y, to.z);
                w.spawnParticles(FLESH, to.x, to.y + 1, to.z, 20, 0.3, 0.5, 0.3, 0);
                w.playSound(null, b, SoundEvents.BLOCK_SLIME_BLOCK_BREAK, SoundCategory.PLAYERS, 1f, 0.5f);
                return true;
            }
        }
        JobSkills.bar(p, "빠져나갈 틈이 없습니다", Formatting.RED);
        return false;
    }

    private static final Map<UUID, Long> INSIDE = new HashMap<>();

    private static boolean burrow(ServerPlayerEntity p) {
        LivingEntity t = AlchemySkills.aimTarget(p, 4);
        if (t == null) { JobSkills.bar(p, "파고들 상대가 없습니다 (4칸)", Formatting.RED); return false; }
        INSIDE.put(p.getUuid(), System.currentTimeMillis() + 3000);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 60, 0, false, false));
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 60; i += 2) Tasks.later(i, () -> {
            if (!t.isAlive() || p.isDisconnected()) return;
            p.requestTeleport(t.getX(), t.getY(), t.getZ());
            w.spawnParticles(FLESH, t.getX(), t.getY() + 1, t.getZ(), 3, 0.2, 0.4, 0.2, 0);
        });
        Tasks.later(60, () -> {
            INSIDE.remove(p.getUuid());
            p.removeStatusEffect(StatusEffects.INVISIBILITY);
            if (!t.isAlive()) return;
            t.timeUntilRegen = 0;
            hit(p, t, 16);
            Vec3d out = t.getPos().add(flat(p).multiply(-1.5));
            p.requestTeleport(out.x, t.getY(), out.z);
            w.spawnParticles(Vampire.BLOOD, t.getX(), t.getY() + 1, t.getZ(), 60, 0.4, 0.7, 0.4, 0);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 1.5f, 0.5f);
        });
        return true;
    }

    private static boolean veinThread(ServerPlayerEntity p) {
        LivingEntity t = AlchemySkills.aimTarget(p, 8);
        if (t == null) { JobSkills.bar(p, "끌어올 상대가 없습니다 (8칸)", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        Vec3d from = p.getPos().add(0, 1.2, 0), to = t.getPos().add(0, t.getHeight() / 2, 0);
        for (int i = 0; i <= 12; i++) { Vec3d q = from.lerp(to, i / 12.0); w.spawnParticles(Vampire.BLOOD, q.x, q.y, q.z, 2, 0.02, 0.02, 0.02, 0); }
        Vec3d d = p.getPos().subtract(t.getPos());
        t.setVelocity(d.x * 0.25, 0.4, d.z * 0.25);
        t.velocityModified = true;
        hit(p, t, 3);
        sound(p, SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, 1.5f, 0.6f);
        return true;
    }

    private static final Map<UUID, Long> MOLT = new HashMap<>();

    private static boolean molt(ServerPlayerEntity p) {
        for (var inst : List.copyOf(p.getStatusEffects())) if (!inst.getEffectType().value().isBeneficial()) p.removeStatusEffect(inst.getEffectType());
        p.extinguish();
        p.setFrozenTicks(0);
        MOLT.put(p.getUuid(), System.currentTimeMillis() + 2000);
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(FLESH, p.getX(), p.getY() + 1, p.getZ(), 50, 0.4, 0.8, 0.4, 0.05);
        sound(p, SoundEvents.ENTITY_SLIME_SQUISH, 1.5f, 0.6f);
        return true;
    }

    private static boolean devour(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        for (int i = 1; i <= 5; i++) {
            Tasks.later(i * 20, () -> {
                if (p.isDisconnected() || !p.isAlive()) return;
                float total = 0;
                for (Entity t : around(p, p.getPos(), 6)) {
                    t.timeUntilRegen = 0;
                    if (hit(p, t, 4)) total += 4;
                    Vec3d from = t.getPos().add(0, 1, 0), to = p.getPos().add(0, 1, 0);
                    for (int k = 0; k <= 6; k++) { Vec3d q = from.lerp(to, k / 6.0); w.spawnParticles(FLESH, q.x, q.y, q.z, 1, 0.02, 0.02, 0.02, 0); }
                }
                p.heal(total * 0.5f);
            });
        }
        sound(p, SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2f, 0.6f);
        return true;
    }

    // ───────── 이벤트 연결 (Pillar.register 에서) ─────────

    /** 내가 근접으로 때림 */
    static void onMelee(ServerPlayerEntity atk, AlchemyData.Entry e, LivingEntity victim, float taken) {
        if (Vampire.stage(e) < 3) return;
        String r = e.str(Vampire.P + "route");
        if ((Pillar.LIGHT.equals(r) || Vampire.stage(e) == 4) && bladeOn(atk)) bleed(atk, victim);
        Long bl = BLOOM.get(atk.getUuid());
        if (bl != null && bl > System.currentTimeMillis()) {
            for (Entity t : around(atk, victim.getPos(), 3)) {
                if (t == victim) continue;
                t.timeUntilRegen = 0;
                hit(atk, t, taken);
            }
            atk.getServerWorld().spawnParticles(ParticleTypes.SWEEP_ATTACK, victim.getX(), victim.getY() + 1, victim.getZ(), 3, 1.5, 0.3, 1.5, 0);
        }
    }

    /** 내가 맞음 */
    static void onHurt(ServerPlayerEntity v, AlchemyData.Entry e, DamageSource src) {
        if (Vampire.stage(e) < 3) return;
        String r = e.str(Vampire.P + "route");
        if (Pillar.HEAT.equals(r) || Vampire.stage(e) == 4) {
            Long until = RAGE_UNTIL.get(v.getUuid());
            int n = until != null && until > System.currentTimeMillis() ? RAGE.getOrDefault(v.getUuid(), 0) : 0;
            setRage(v, Math.min(5, n + 1));
        }
        Long hw = HEAT_WALL.get(v.getUuid());
        if (hw != null && hw > System.currentTimeMillis() && src.getAttacker() instanceof LivingEntity a && a != v) {
            a.setOnFireFor(3);
            a.timeUntilRegen = 0;
            Targets.damage(v, a, 4);
        }
    }

    /** 피해를 막을지 (false = 막음) */
    static boolean allowDamage(ServerPlayerEntity v, DamageSource src) {
        long now = System.currentTimeMillis();
        Long in = INSIDE.get(v.getUuid());
        if (in != null && in > now) return false;
        Long m = MOLT.get(v.getUuid());
        if (m != null && m > now) return false;
        Long sc = SCHEME.get(v.getUuid());
        if (sc != null && sc > now && src.getAttacker() instanceof LivingEntity a && a != v) {
            SCHEME.remove(v.getUuid());
            Vec3d behind = a.getPos().subtract(a.getRotationVector().multiply(1.5));
            v.requestTeleport(behind.x, a.getY(), behind.z);
            v.getServerWorld().spawnParticles(ParticleTypes.PORTAL, v.getX(), v.getY() + 1, v.getZ(), 30, 0.3, 0.6, 0.3, 0.3);
            v.sendMessage(Text.literal("읽었다.").formatted(Formatting.DARK_PURPLE), true);
            return false;
        }
        Long veil = VEIL.get(v.getUuid());
        if (veil != null && veil > now && src.isOf(net.minecraft.entity.damage.DamageTypes.ARROW)) return false;
        return true;
    }
}
