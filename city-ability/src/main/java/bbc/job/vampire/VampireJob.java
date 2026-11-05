package bbc.job.vampire;

import bbc.Bbc;
import bbc.combat.Combat;
import bbc.combat.CombatMode;
import bbc.combat.Fx;
import bbc.combat.Hit;
import bbc.combat.Motion;
import bbc.core.PlayerData;
import bbc.core.Tasks;
import bbc.grow.Stat;
import bbc.job.JobSkills;
import bbc.job.JobType;
import bbc.job.Jobs;
import bbc.job.alchemy.AlchemyFx;
import bbc.job.alchemy.Targets;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.Item;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 뱀파이어 (석가면, 무현버스에서 이식 → 새 전투 수치). 단계: 1 일반 뱀파이어 · 2 숙련된 뱀파이어 (3 기둥 속 사내 · 4 완전생물은 다음 이식)
 *
 * 되는 법: 밤에 석가면을 머리에 쓰고, 쓴 채로 피해를 입으면(피를 묻히면) 가면에서 가시가 튀어나와 뱀파이어가 됨. 기존 직업은 사라짐.
 * 석가면: 사막 피라미드·정글 사원 상자 3%, 고대 도시 5%
 * 종족 규칙
 *   햇빛: 낮에 하늘이 보이는 곳에 서 있으면 1초마다 4 피해 + 불 (비·그늘·지하는 안전, 갈증이면 2배)
 *   피 게이지(0~100, 전투 HUD 제목에 표시): 1분에 2씩(낮엔 5씩) 줄고 흡혈로만 참. 자연 재생은 배고픔이 아니라 피를 씀(체력 1 = 피 1)
 *     밤이나 피 70 이상 → 주는 피해 ×1.15 · 0 '갈증' → 최대 체력 -6, 이동 -15%, 재생 멈춤, 피해 ×0.85, 흡혈 말고는 기술 못 씀
 *   밤: 야간 투시, 2초마다 체력 1 (피 소모 없음)
 * 기술 (기준 스탯: 힘, 비용 = 피): 손끝 흡혈 · 괴력 · 재생 · 피의 하인 / 숙련: 혈동 · 혈안 광선 · 육아 지배
 * 숙련 조건: 흡혈량 5,000 + 플레이어 흡혈 3회(같은 사람 하루 1번) + 뱀파이어로 밤 7번 → 하루 한 번 죽음에서 되살아남 (햇빛 제외)
 */
public final class VampireJob implements JobType {
    public static final VampireJob INSTANCE = new VampireJob();
    private VampireJob() {}

    public static final String ID = "vampire";
    public static final String P = "vam.";
    static final String[] RANKS = {"인간", "일반 뱀파이어", "숙련된 뱀파이어"};
    public static final double SKILLED_DRINK = 5_000;
    public static final int SKILLED_PLAYERS = 3, SKILLED_NIGHTS = 7;

    public static final Item STONE_MASK = Registry.register(Registries.ITEM, Identifier.of(Bbc.MOD_ID, "stone_mask"),
            new StoneMaskItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));

    // ───────── JobType ─────────
    @Override public String id() { return ID; }
    @Override public String name() { return "뱀파이어"; }
    @Override public String prefix() { return P; }
    @Override public Item icon() { return STONE_MASK; }
    @Override public Formatting color() { return Formatting.DARK_RED; }
    @Override public int maxRank() { return 2; }
    @Override public String rankName(int r) { return RANKS[Math.max(0, Math.min(2, r))]; }
    @Override public boolean selectable() { return false; }
    @Override public Stat skillStat() { return Stat.STR; }
    @Override public String stance() { return "stance_vm"; }
    @Override public String[] summary() {
        return new String[]{"석가면을 쓰고 피를 묻힌 자", "밤의 지배자, 햇빛이 약점 · 피로 살아감",
                "숙련 → 기둥 속 사내 → 완전생물", "직업 선택으로는 될 수 없음 (석가면)"};
    }

    @Override public void onJoin(ServerPlayerEntity p, PlayerData.Entry e) {
        e.num(P + "rank", 1);
        e.num(P + "blood", 60);
    }

    @Override public void onQuit(ServerPlayerEntity p, PlayerData.Entry e) {
        setThirst(p, false);
        Thralls.releaseAll(p.getUuid());
        p.removeStatusEffect(StatusEffects.NIGHT_VISION);
        p.getHungerManager().setFoodLevel(20);
        p.extinguish();
    }

    @Override public String hudTitle(ServerPlayerEntity p, PlayerData.Entry e) {
        return name() + " · 피 " + (int) blood(e) + (thirsty(e) ? " (갈증)" : "");
    }

    @Override public float damageMul(ServerPlayerEntity p) {
        PlayerData.Entry e = Jobs.entry(p);
        if (thirsty(e)) return 0.85f;
        return isNight(p.getServerWorld()) || blood(e) >= 70 ? 1.15f : 1f;
    }

    public static boolean is(PlayerData.Entry e) { return ID.equals(e.job); }
    public static int stage(PlayerData.Entry e) { return is(e) ? Math.max(1, (int) e.num(P + "rank")) : 0; }

    // ═════════ 평타 ═════════
    /** 손톱 후리기 → 올려 베기 → 손끝 찌르기 → 붙잡고 무릎 → 높이 차기 (5타 선딜 6) */
    private static final bbc.combat.Weapon.M1Profile M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1.05f, 0.3, "vm_", 6);
    @Override public bbc.combat.Weapon.M1Profile m1() { return M1; }

    @Override public void m1Fx(ServerPlayerEntity p, String motion) {
        ServerWorld w = p.getServerWorld();
        switch (motion) {
            case "vm_1" -> Fx.slash(w, p, 2.4, 10, true, Fx.RED);
            case "vm_2" -> Fx.slash(w, p, 2.4, 70, false, Fx.RED);
            case "vm_rise" -> Fx.slash(w, p, 2.4, 90, false, Fx.RED);
            case "vm_5" -> { Vec3d f = Combat.horizontal(p.getRotationVec(1f)); Fx.kickStreak(w, p.getPos().add(f.multiply(1.6)).add(0, 1.4, 0), f); }
            default -> { }
        }
    }

    // ═════════ 피 ═════════
    public static double blood(PlayerData.Entry e) { return e.num(P + "blood"); }
    public static void addBlood(PlayerData.Entry e, double v) { e.num(P + "blood", Math.max(0, Math.min(100, blood(e) + v))); }
    public static boolean thirsty(PlayerData.Entry e) { return blood(e) <= 0; }
    private static long day(ServerWorld w) { return w.getTimeOfDay() / 24000; }
    static boolean isNight(ServerWorld w) { return w.isNight() || !w.getDimension().hasSkyLight(); }

    static void addDrink(ServerPlayerEntity p, PlayerData.Entry e, double amount, boolean player) {
        e.addNum(P + "drink", amount);
        if (player) e.addNum(P + "pdrink", 1);
        checkRank(p, e);
        Jobs.dirty(p);
    }

    static void checkRank(ServerPlayerEntity p, PlayerData.Entry e) {
        if (stage(e) == 1 && e.num(P + "drink") >= SKILLED_DRINK && e.num(P + "pdrink") >= SKILLED_PLAYERS && e.num(P + "nights") >= SKILLED_NIGHTS) {
            e.num(P + "rank", 2);
            p.sendMessage(Text.literal("🩸 숙련된 뱀파이어가 되었습니다").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
            p.sendMessage(Text.literal("  새 기술: 혈동 · 혈안 광선 · 육아 지배, 하루 한 번 죽음에서 되살아남").formatted(Formatting.DARK_RED), false);
            p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.8f, 1.4f);
        }
    }

    /** 흡혈: 대상 종류에 따라 피·흡혈량. 몬스터면 30초 동안 '피의 하인' 으로 만들 수 있음 */
    static void drink(ServerPlayerEntity p, PlayerData.Entry e, LivingEntity t) {
        if (t instanceof ServerPlayerEntity tp) {
            String key = P + "pd_" + tp.getUuid();
            boolean count = e.num(key) != day(p.getServerWorld()) + 1;
            e.num(key, day(p.getServerWorld()) + 1);
            addBlood(e, 20);
            addDrink(p, e, 300, count);
            if (count) JobSkills.bar(p, "🩸 " + tp.getName().getString() + "의 피 (플레이어 흡혈 " + (int) e.num(P + "pdrink") + "회)", Formatting.DARK_RED);
        } else if (t instanceof MerchantEntity) {
            addBlood(e, 15);
            addDrink(p, e, 100, false);
        } else {
            double hp = Math.min(60, t.getMaxHealth());
            addBlood(e, 5 + hp / 6);
            addDrink(p, e, 10 + hp * 3, false);
            if (t instanceof MobEntity && !(t instanceof WitherEntity) && !(t instanceof EnderDragonEntity)) {
                LAST_PREY.put(p.getUuid(), t.getUuid());
                LAST_PREY_AT.put(p.getUuid(), Combat.now());
            }
        }
    }

    // ═════════ 등록 · 매초 규칙 ═════════
    private static final Identifier THIRST_ID = Identifier.of(Bbc.MOD_ID, "vampire_thirst");
    private static final Identifier THIRST_SPEED = Identifier.of(Bbc.MOD_ID, "vampire_thirst_speed");
    static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.0f, 0.02f), 1.0f);
    private static final Map<UUID, Long> SUN_HURT = new HashMap<>();
    private static final Map<UUID, UUID> LAST_PREY = new HashMap<>();
    private static final Map<UUID, Long> LAST_PREY_AT = new HashMap<>();
    private static final Set<UUID> RITUAL = new HashSet<>();
    private static int clock = 0;

    public static void register() {
        Jobs.register(INSTANCE);
        Thralls.register();

        // 석가면: 사막 피라미드·정글 사원 3%, 고대 도시 5%
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            String id = key.getValue().toString();
            float chance = switch (id) {
                case "minecraft:chests/desert_pyramid", "minecraft:chests/jungle_temple" -> 0.03f;
                case "minecraft:chests/ancient_city" -> 0.05f;
                default -> 0f;
            };
            if (chance <= 0) return;
            builder.pool(LootPool.builder().rolls(ConstantLootNumberProvider.create(1))
                    .with(ItemEntry.builder(STONE_MASK)).conditionally(RandomChanceLootCondition.builder(chance)));
        });

        // 석가면을 쓴 채(밤) 피해를 입으면 → 뱀파이어
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayerEntity p) || taken <= 0) return;
            if (!p.getEquippedStack(EquipmentSlot.HEAD).isOf(STONE_MASK)) return;
            PlayerData.Entry e = Jobs.entry(p);
            if (is(e) || RITUAL.contains(p.getUuid())) return;
            if (!isNight(p.getServerWorld())) {
                p.sendMessage(Text.literal("가면이 꿈틀거리지만… 아무 일도 일어나지 않는다. (밤이어야 한다)").formatted(Formatting.GRAY), true);
                return;
            }
            transform(p);
        });

        // 숙련된 뱀파이어: 하루 한 번 되살아남 (햇빛으로 죽으면 안 됨)
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return true;
            PlayerData.Entry e = Jobs.entry(p);
            if (stage(e) != 2) return true;
            Long sun = SUN_HURT.get(p.getUuid());
            if (sun != null && clock - sun < 30) return true;
            long today = day(p.getServerWorld()) + 1;
            if (e.num(P + "revive") == today) return true;
            e.num(P + "revive", today);
            p.setHealth(6);
            p.extinguish();
            Combat.invuln(p, 30);
            ServerWorld w = p.getServerWorld();
            w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 60, 0.4, 0.8, 0.4, 0);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE, SoundCategory.PLAYERS, 1.0f, 0.6f);
            p.sendMessage(Text.literal("🩸 찢어진 살이 다시 붙는다… (오늘의 되살아남을 썼다)").formatted(Formatting.DARK_RED), false);
            Jobs.dirty(p);
            return false;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> Thralls.releaseAll(h.getPlayer().getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(VampireJob::tick);
    }

    /** 석가면 의식: 가면 둘레에서 가시가 튀어나와 머리에 박힘 → 2초 뒤 뱀파이어 */
    static void transform(ServerPlayerEntity p) {
        RITUAL.add(p.getUuid());
        ServerWorld w = p.getServerWorld();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 6, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, false, false));
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.6f);
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("가면이 피를 마신다").formatted(Formatting.DARK_RED)));
        for (int i = 0; i < 8; i++) {
            final int k = i;
            Tasks.later(10 + i * 2, () -> {
                double a = Math.PI * 2 * k / 8;
                Vec3d head = p.getEyePos();
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()),
                        head.x + Math.cos(a) * 0.35, head.y, head.z + Math.sin(a) * 0.35, 6, 0.02, 0.02, 0.02, 0.1);
                w.spawnParticles(BLOOD, head.x + Math.cos(a) * 0.45, head.y, head.z + Math.sin(a) * 0.45, 4, 0.05, 0.05, 0.05, 0);
                w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.PLAYERS, 1.0f, 1.4f);
            });
        }
        Tasks.later(40, () -> {
            RITUAL.remove(p.getUuid());
            if (p.isDisconnected() || !p.isAlive()) return;
            CombatMode.set(p, false);
            Jobs.set(p, INSTANCE);
            w.spawnParticles(ParticleTypes.FLASH, p.getX(), p.getEyeY(), p.getZ(), 1, 0, 0, 0, 0);
            w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 120, 0.6, 1, 0.6, 0);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.8f, 1.6f);
            p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("인간을 그만두었다").formatted(Formatting.GRAY)));
            p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("뱀파이어").formatted(Formatting.DARK_RED, Formatting.BOLD)));
            p.sendMessage(Text.literal("🩸 일반 뱀파이어가 되었습니다. 햇빛을 피하고, 피로 살아가십시오.").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
            p.getServer().getPlayerManager().broadcast(Text.literal("🩸 어딘가에서 석가면이 깨어났다…").formatted(Formatting.DARK_RED), false);
        });
    }

    private static void tick(MinecraftServer server) {
        clock++;
        if (clock % 20 != 0) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerData.Entry e = Jobs.entry(p);
            int s = stage(e);
            if (s == 0 || p.isSpectator() || p.isCreative()) continue;
            ServerWorld w = p.getServerWorld();
            boolean night = isNight(w);

            // 밤을 넘길 때마다 기록 (숙련 조건)
            long t = w.getTimeOfDay() % 24000;
            long nightNo = w.getTimeOfDay() / 24000 + 1;
            if (t >= 13000 && t < 23000) e.num(P + "curnight", nightNo);
            else if (t < 12000 && e.num(P + "curnight") > 0 && e.num(P + "curnight") != e.num(P + "lastnight")) {
                e.num(P + "lastnight", e.num(P + "curnight"));
                e.addNum(P + "nights", 1);
                checkRank(p, e);
                p.sendMessage(Text.literal("🌅 밤을 하나 더 넘겼다 (" + (int) e.num(P + "nights") + "번째)").formatted(Formatting.DARK_RED), true);
            }

            addBlood(e, -(night ? 2.0 : 5.0) / 60.0);
            boolean thirst = thirsty(e);
            // 배고픔으로 인한 자연 재생을 막고(17 고정) 피로 재생
            p.getHungerManager().setFoodLevel(17);
            p.getHungerManager().setSaturationLevel(0);
            if (p.getHealth() < p.getMaxHealth() && !thirst && clock % 40 == 0) {
                if (night) p.heal(1);
                else if (blood(e) >= 1) { p.heal(1); addBlood(e, -1); }
            }
            if (night) p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 400, 0, true, false));
            setThirst(p, thirst);

            if (sunlit(w, p)) {
                SUN_HURT.put(p.getUuid(), (long) clock);
                p.setOnFireFor(2);
                p.damage(p.getDamageSources().magic(), thirst ? 8 : 4);
                w.spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 10, 0.3, 0.6, 0.3, 0.02);
                if (clock % 60 == 0) p.sendMessage(Text.literal("☀ 햇빛이 몸을 태운다! 그늘로!").formatted(Formatting.GOLD), true);
            }
        }
        if (clock % 1200 == 0) {
            SUN_HURT.values().removeIf(x -> clock - x > 1200);
            LAST_PREY_AT.values().removeIf(x -> Combat.now() - x > 600);
        }
    }

    /** 낮 + 하늘이 보임 + 비가 안 옴 */
    public static boolean sunlit(ServerWorld w, ServerPlayerEntity p) {
        if (!w.getDimension().hasSkyLight() || !w.isDay() || w.isRaining()) return false;
        return w.isSkyVisible(BlockPos.ofFloored(p.getX(), p.getEyeY(), p.getZ()));
    }

    private static void setThirst(ServerPlayerEntity p, boolean on) {
        EntityAttributeInstance hp = p.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        EntityAttributeInstance sp = p.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (hp == null || sp == null) return;
        boolean has = hp.getModifier(THIRST_ID) != null;
        if (on && !has) {
            hp.addTemporaryModifier(new EntityAttributeModifier(THIRST_ID, -6, EntityAttributeModifier.Operation.ADD_VALUE));
            sp.addTemporaryModifier(new EntityAttributeModifier(THIRST_SPEED, -0.15, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
            p.sendMessage(Text.literal("🩸 갈증… 피가 필요하다").formatted(Formatting.DARK_RED), false);
        } else if (!on && has) {
            hp.removeModifier(THIRST_ID);
            sp.removeModifier(THIRST_SPEED);
        }
    }

    // ═════════ 기술 ═════════
    private static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("손끝 흡혈", "흡혈", 1, 0, 4, "근접 대상에 손가락을 꽂아 피해 ×0.8 + 흡혈 (연장기, 피 회복)"),
            new JobSkills.Skill("괴력", "괴력", 1, 10, 20, "10초 동안 주는 피해 ×1.3, 5타 날려보내기 거리 2배"),
            new JobSkills.Skill("재생", "재생", 1, 20, 15, "체력 6 회복 + 불 끄기"),
            new JobSkills.Skill("피의 하인", "하인", 1, 25, 45, "30초 안에 흡혈한 몬스터를 60초 하인으로 (최대 2)"),
            new JobSkills.Skill("혈동", "혈동", 2, 30, 25, "붙잡은 상대의 피를 얼려 피해 ×1.0 + 긴 경직 (잡기: 가드·패링 불가)"),
            new JobSkills.Skill("혈안 광선", "광선", 2, 35, 20, "눈에서 고압 체액을 쏴 직선 20칸 관통, 피해 ×1.5 (원거리 마무리)"),
            new JobSkills.Skill("육아 지배", "육아", 2, 40, 60, "주민이나 몬스터 하나를 60초 하인으로 (플레이어 불가)"),
    };

    private static final Hit DRAIN = new Hit(0.8f, Hit.Kind.HIT, 16, 0.1, 0, 10, false, false);
    private static final Hit FREEZE = new Hit(1.0f, Hit.Kind.HIT, 24, 0, 0, 0, true, false).noParry();
    private static final Hit BEAM = new Hit(1.5f, Hit.Kind.HIT, 12, 0.5, 0, 20, false, false);

    private static final JobSkills.Set SKILLS = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, PlayerData.Entry e) { return LIST; }
        @Override public String costName() { return "피"; }
        @Override public double resource(ServerPlayerEntity p, PlayerData.Entry e) { return blood(e); }
        @Override public boolean pay(ServerPlayerEntity p, PlayerData.Entry e, JobSkills.Skill s) {
            if (blood(e) + 1e-6 < s.cost()) return false;
            addBlood(e, -s.cost());
            return true;
        }
        @Override public String blocked(ServerPlayerEntity p, PlayerData.Entry e, int idx) {
            if (thirsty(e) && idx != 0) return "갈증 상태에서는 흡혈만 할 수 있습니다";
            return null;
        }
        @Override public boolean use(ServerPlayerEntity p, PlayerData.Entry e, int idx) {
            return switch (idx) {
                case 0 -> fingerDrink(p, e);
                case 1 -> might(p);
                case 2 -> regen(p);
                case 3 -> bloodThrall(p);
                case 4 -> bloodFreeze(p);
                case 5 -> eyeBeam(p);
                case 6 -> fleshBud(p);
                default -> false;
            };
        }
    };
    @Override public JobSkills.Set skills() { return SKILLS; }

    static LivingEntity aimTarget(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        Box box = p.getBoundingBox().stretch(look.multiply(range)).expand(2);
        LivingEntity best = null;
        double bestD = range;
        for (Entity t : p.getServerWorld().getOtherEntities(p, box, x -> Targets.canHit(p, x))) {
            Vec3d to = t.getPos().add(0, t.getHeight() / 2, 0).subtract(eye);
            double d = to.length();
            if (d > range || d < 0.3 || to.normalize().dotProduct(look) < 0.94 || !p.canSee(t)) continue;
            if (d < bestD) { bestD = d; best = (LivingEntity) t; }
        }
        return best;
    }

    private static LivingEntity near(ServerPlayerEntity p, double range) {
        LivingEntity t = aimTarget(p, range);
        return t != null ? t : Combat.targets(p, range, 50, 1).stream().findFirst().orElse(null);
    }

    /** 손끝 흡혈: 손가락을 꽂고(4틱) 피를 빨아들임 — 맞으면 흡혈 */
    private static boolean fingerDrink(ServerPlayerEntity p, PlayerData.Entry e) {
        LivingEntity t = near(p, 3.5);
        if (t == null) { JobSkills.bar(p, "손이 닿는 곳(3.5칸)에 대상이 없습니다", Formatting.RED); return false; }
        Motion.play(p, "vm_drain");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (p.isDisconnected() || !p.isAlive() || !t.isAlive()) return;
            if (Combat.hit(p, t, DRAIN) != Combat.Result.HIT) return;
            drink(p, e, t);
            w.spawnParticles(BLOOD, t.getX(), t.getY() + t.getHeight() * 0.6, t.getZ(), 25, 0.2, 0.3, 0.2, 0);
            Vec3d from = t.getPos().add(0, t.getHeight() * 0.6, 0), to = p.getPos().add(0, 1.2, 0);
            for (int i = 0; i <= 10; i++) {
                Vec3d q = from.lerp(to, i / 10.0);
                Tasks.later(i / 2, () -> w.spawnParticles(BLOOD, q.x, q.y, q.z, 2, 0.02, 0.02, 0.02, 0));
            }
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 1.0f, 0.6f);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 1.0f, 0.8f);
        });
        return true;
    }

    /** 괴력: 10초 동안 피해 ×1.3, 5타 날려보내기 2배 */
    private static boolean might(ServerPlayerEntity p) {
        Motion.play(p, "vm_might");
        Combat.afterSkill(p, 8);
        var f = Combat.of(p);
        f.dmgBuffMul = 1.3f;
        f.dmgBuffUntil = Combat.now() + 200;
        f.pushBuffMul = 2f;
        f.pushBuffUntil = Combat.now() + 200;
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> {
            w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.5, 0.7, 0.5, 0);
            Fx.wave(w, p.getPos().add(0, 1, 0), 0.3, 2.4, Fx.RED, 6);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.7f, 1.4f);
        });
        for (int i = 20; i < 200; i += 20) Tasks.later(i, () -> { if (!p.isDisconnected()) w.spawnParticles(BLOOD, p.getX(), p.getY() + 1.2, p.getZ(), 4, 0.3, 0.4, 0.3, 0); });
        return true;
    }

    private static boolean regen(ServerPlayerEntity p) {
        Motion.play(p, "vm_regen");
        Combat.afterSkill(p, 8);
        p.heal(6);
        p.extinguish();
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.7, 0.4, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_SLIME_BLOCK_PLACE, SoundCategory.PLAYERS, 1.0f, 0.5f);
        return true;
    }

    /** 피의 하인: 30초 안에 흡혈한 몬스터 */
    private static boolean bloodThrall(ServerPlayerEntity p) {
        UUID id = LAST_PREY.get(p.getUuid());
        Long at = LAST_PREY_AT.get(p.getUuid());
        if (id == null || at == null || Combat.now() - at > 600) { JobSkills.bar(p, "30초 안에 흡혈한 몬스터가 없습니다", Formatting.RED); return false; }
        Entity t = p.getServerWorld().getEntity(id);
        if (!(t instanceof MobEntity m) || !m.isAlive() || m.squaredDistanceTo(p) > 16 * 16) { JobSkills.bar(p, "흡혈한 몬스터가 근처에 없습니다", Formatting.RED); return false; }
        Motion.play(p, "vm_point");
        Combat.afterSkill(p, 6);
        return Thralls.bind(p, m, 60, 2, "피의 하인");
    }

    /** 혈동: 붙잡아 피를 얼림 (잡기 — 가드·패링 불가) */
    private static boolean bloodFreeze(ServerPlayerEntity p) {
        LivingEntity t = near(p, 4);
        if (t == null) { JobSkills.bar(p, "붙잡을 대상이 없습니다 (4칸)", Formatting.RED); return false; }
        Motion.play(p, "vm_freeze");
        Combat.afterSkill(p, 16);
        ServerWorld w = p.getServerWorld();
        Tasks.later(5, () -> {
            if (p.isDisconnected() || !p.isAlive() || !t.isAlive() || t.squaredDistanceTo(p) > 5 * 5) return;
            if (Combat.hit(p, t, FREEZE) != Combat.Result.HIT) return;
            t.setFrozenTicks(Math.max(t.getFrozenTicks(), 200));
            AlchemyFx.display(w, Blocks.BLUE_ICE.getDefaultState(), t.getX(), t.getY(), t.getZ(), t.getWidth() + 0.4f, t.getHeight() + 0.2f, t.getWidth() + 0.4f, 0, 24, 2);
            for (int i = 0; i < 24; i += 6) Tasks.later(i, () -> w.spawnParticles(ParticleTypes.SNOWFLAKE, t.getX(), t.getY() + 1, t.getZ(), 10, 0.4, 0.6, 0.4, 0.01));
            w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        });
        return true;
    }

    /** 혈안 광선: 6틱에 직선 20칸 관통 */
    private static boolean eyeBeam(ServerPlayerEntity p) {
        Motion.play(p, "vm_beam");
        Combat.afterSkill(p, 14);
        ServerWorld w = p.getServerWorld();
        Tasks.later(6, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            Vec3d eye = p.getEyePos(), look = p.getRotationVector();
            HitResult hit = p.raycast(20, 0, false);
            double len = hit.getType() == HitResult.Type.MISS ? 20 : hit.getPos().distanceTo(eye);
            Vec3d side = new Vec3d(-look.z, 0, look.x).normalize().multiply(0.12);
            Set<UUID> done = new HashSet<>();
            for (int i = 0; i < 4; i++) {
                final int k = i;
                Tasks.later(i, () -> {
                    for (double d = 0.4; d < len; d += 0.35) {
                        Vec3d q = eye.add(look.multiply(d));
                        w.spawnParticles(ParticleTypes.END_ROD, q.x + side.x, q.y - 0.05, q.z + side.z, 1, 0, 0, 0, 0);
                        w.spawnParticles(ParticleTypes.END_ROD, q.x - side.x, q.y - 0.05, q.z - side.z, 1, 0, 0, 0, 0);
                        if (k == 0 && ((int) (d * 3)) % 4 == 0) w.spawnParticles(BLOOD, q.x, q.y - 0.05, q.z, 1, 0.02, 0.02, 0.02, 0);
                    }
                    for (Entity t : w.getOtherEntities(p, p.getBoundingBox().stretch(look.multiply(len)).expand(1), x -> Targets.canHit(p, x))) {
                        if (done.contains(t.getUuid())) continue;
                        Vec3d c = t.getPos().add(0, t.getHeight() / 2, 0);
                        double along = c.subtract(eye).dotProduct(look);
                        if (along < 0 || along > len) continue;
                        if (c.distanceTo(eye.add(look.multiply(along))) > t.getWidth() / 2 + 0.6) continue;
                        done.add(t.getUuid());
                        Combat.hit(p, (LivingEntity) t, BEAM);
                        w.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 15, 0.2, 0.2, 0.2, 0.3);
                    }
                });
            }
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3d hp = hit.getPos();
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, w.getBlockState(BlockPos.ofFloored(hp.add(look.multiply(0.1))))),
                        hp.x, hp.y, hp.z, 25, 0.2, 0.2, 0.2, 0.2);
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GUARDIAN_ATTACK, SoundCategory.PLAYERS, 1.0f, 2.0f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 1.6f);
        });
        return true;
    }

    /** 육아 지배: 주민이나 몬스터 하나 (플레이어·보스 불가) */
    private static boolean fleshBud(ServerPlayerEntity p) {
        LivingEntity t = aimTarget(p, 8);
        if (t == null) {
            for (Entity x : p.getServerWorld().getOtherEntities(p, p.getBoundingBox().expand(8), x -> x instanceof MerchantEntity)) {
                Vec3d to = x.getPos().add(0, 1, 0).subtract(p.getEyePos());
                if (to.normalize().dotProduct(p.getRotationVector()) > 0.95 && p.canSee(x)) { t = (LivingEntity) x; break; }
            }
        }
        if (t == null || t instanceof ServerPlayerEntity || !(t instanceof MobEntity) || t instanceof WitherEntity || t instanceof EnderDragonEntity) {
            JobSkills.bar(p, "주민이나 몬스터를 바라보세요 (8칸)", Formatting.RED);
            return false;
        }
        Motion.play(p, "vm_point");
        Combat.afterSkill(p, 6);
        ServerWorld w = p.getServerWorld();
        Vec3d from = p.getPos().add(0, 1.2, 0), to = t.getPos().add(0, t.getHeight() * 0.8, 0);
        for (int i = 0; i <= 8; i++) {
            Vec3d q = from.lerp(to, i / 8.0);
            Tasks.later(i, () -> w.spawnParticles(BLOOD, q.x, q.y, q.z, 3, 0.03, 0.03, 0.03, 0));
        }
        return Thralls.bind(p, t, 60, 2, "육아에 지배당함");
    }
}
