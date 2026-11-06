package bbc.job.vampire;

import bbc.Bbc;
import bbc.combat.Awaken;
import bbc.combat.Combat;
import bbc.combat.CombatMode;
import bbc.combat.Fx;
import bbc.combat.Hit;
import bbc.combat.Motion;
import bbc.core.PlayerData;
import bbc.core.Tasks;
import bbc.net.CutscenePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * 기술 (기준 스탯: 힘, 비용 = 피): 흡혈 돌진 · 기화냉동법 · 괴력 · 재생 · 피의 하인 / 숙련: 냉동 반격 · 혈안 광선 · 무다 연타 · 육아 지배
 *   평타를 치는 중(평타 사슬)에 쓰거나 공중에서 쓰면 바뀌는 기술이 있음 (흡혈 돌진·기화냉동법·혈안 광선)
 * 각성 '혈광 해방' (강화형): 주는 피해 ×1.2, 평타가 맞을 때마다 흡혈. 각성기 '피의 만찬' (컷신)
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
    @Override public String ultimateName() { return "피의 만찬"; }
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
        float m = isNight(p.getServerWorld()) || blood(e) >= 70 ? 1.15f : 1f;
        return Awaken.active(p) ? m * 1.2f : m;   // 혈광 해방: 괴력 상시
    }

    public static boolean is(PlayerData.Entry e) { return ID.equals(e.job); }
    public static int stage(PlayerData.Entry e) { return is(e) ? Math.max(1, (int) e.num(P + "rank")) : 0; }

    // ═════════ 평타 ═════════
    /** 수도 → 손등 → 멱살 잡고 무릎 → 손톱 올려 베기 → 손바닥 장타 (5타 선딜 6). 공중은 손톱 셋(vm_a1~3), 슬램은 손끝 내리꽂기 */
    private static final bbc.combat.Weapon.M1Profile M1 = new bbc.combat.Weapon.M1Profile(5, 4, 2, 1.05f, 0.3, "vm_", 6);
    @Override public bbc.combat.Weapon.M1Profile m1() { return M1; }
    @Override public String guardMotion() { return "guard_vm"; }

    @Override public void m1Fx(ServerPlayerEntity p, String motion) {
        ServerWorld w = p.getServerWorld();
        switch (motion) {
            case "vm_1" -> Fx.slash(w, p, 2.4, 55, false, Fx.RED);
            case "vm_2" -> Fx.slash(w, p, 2.4, 0, true, Fx.RED);
            case "vm_4", "vm_rise" -> Fx.slash(w, p, 2.4, 100, true, Fx.RED);
            case "vm_a1" -> Fx.slash(w, p, 2.4, 10, false, Fx.RED);
            case "vm_a2" -> Fx.slash(w, p, 2.4, 10, true, Fx.RED);
            case "vm_a3" -> { Fx.slash(w, p, 2.4, 60, false, Fx.RED); Fx.slash(w, p, 2.4, 60, true, Fx.RED); }
            case "vm_5" -> { Vec3d f = Combat.horizontal(p.getRotationVec(1f)); Fx.kickStreak(w, p.getPos().add(f.multiply(1.6)).add(0, 1.2, 0), f); }
            default -> { }
        }
        // 각성(혈광 해방) 중엔 평타가 맞을 때마다 피를 조금씩 빨아들임
        if (Awaken.active(p) && Combat.of(p).lastTargetAt == Combat.now()) {
            PlayerData.Entry e = Jobs.entry(p);
            addBlood(e, 1.5);
            w.spawnParticles(BLOOD, p.getX(), p.getY() + 1.2, p.getZ(), 4, 0.3, 0.3, 0.3, 0);
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
            p.sendMessage(Text.literal("  새 기술: 냉동 반격 · 혈안 광선 · 무다 연타 · 육아 지배, 하루 한 번 죽음에서 되살아남").formatted(Formatting.DARK_RED), false);
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
    // 참고: 올스타 배틀 R 1부 디오 필살기(쿠아·기화냉동법·순식간에 얼리기·무다무다·빈약하다·공열안자경·GHA), 아이즈 오브 헤븐 디오 플레이.
    //   기술 이름·연출은 우리 것. '평타 중' = 평타 사슬이 이어지는 동안(평타를 치다가 바로), '공중' = 발이 땅에서 떨어져 있을 때
    private static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("흡혈 돌진", "흡혈", 1, 0, 6, "낮게 달려들어 목을 쥐고 들어 올려 흡혈 → 내던짐 (잡기) · 평타 중: 손끝 찌르기 · 공중: 내리꽂아 깔고 흡혈"),
            new JobSkills.Skill("기화냉동법", "냉동", 1, 15, 14, "손바닥을 갖다 대 얼려 버린 뒤 밀어 올림 (잡기) · 평타 중: 얼음 손톱 · 공중: 붙잡아 얼려 내리꽂음"),
            new JobSkills.Skill("괴력 연격", "연격", 1, 10, 10, "뛰어들며 무릎 → 맞으면 0.8초 안에 평타로 파생: 평타 = 수도·손등 · 스페이스+평타 = 얼려 올려치기 · 웅크리고+평타 = 머리를 쥐고 내리꽂기"),
            new JobSkills.Skill("재생", "재생", 1, 20, 15, "체력 6 회복 + 불 끄기"),
            new JobSkills.Skill("피의 하인", "하인", 1, 25, 45, "30초 안에 흡혈한 몬스터를 60초 하인으로 (최대 2)"),
            new JobSkills.Skill("냉동 반격", "반격", 2, 20, 12, "1.5초 동안 손바닥을 내밀고 받아냄 (가드 안 눌러도 됨) — 맞으면 때린 상대를 순식간에 얼리고 산산조각"),
            new JobSkills.Skill("혈안 광선", "광선", 2, 30, 18, "눈에서 고압 체액을 쏴 20칸 관통 · 평타 중: 짧은 단발 · 공중: 내려다보며 쏴 땅이 터짐"),
            new JobSkills.Skill("무다 연타", "무다", 2, 25, 16, "들이받아 목을 쥐고 머리 위로 들어 올린 채 손가락을 여덟 번 꽂아 흡혈 → 휘둘러 땅에 내리꽂음 (잡기)"),
            new JobSkills.Skill("육아 지배", "육아", 2, 40, 60, "주민이나 몬스터 하나를 60초 하인으로 (플레이어 불가)"),
    };
    /** 기술 칸 저장 형식이 바뀌면(순서·개수) 장착을 처음 상태로 */
    private static final String EQUIP_VER = "2";   // 괴력 → 괴력 연격은 같은 칸이라 그대로

    // 잡기는 가드·패링 불가 (가드 깨기 + noParry), 잡힌 동안은 경직을 길게 걸어 붙들어 둠
    private static final Hit GRAB = new Hit(0.6f, Hit.Kind.HIT, 40, 0, 0, 0, true, false).noParry();
    private static final Hit SIP = new Hit(0.35f, Hit.Kind.HIT, 30, 0, 0, 0, true, false).noParry();
    private static final Hit TOSS = new Hit(1.6f, Hit.Kind.KNOCK, 20, 1.2, 0.45, 0, true, false).noParry();
    private static final Hit STAB = new Hit(0.9f, Hit.Kind.HIT, 16, 0.1, 0, 10, false, false);
    private static final Hit DIVE = new Hit(1.8f, Hit.Kind.DOWN, 34, 0, -0.9, 20, true, true);
    private static final Hit FREEZE_GRAB = new Hit(1.2f, Hit.Kind.HIT, 36, 0, 0, 0, true, false).noParry();
    private static final Hit FREEZE_LIFT = new Hit(1.6f, Hit.Kind.AIR, 26, 0.2, 0.9, 0, true, false).noParry();
    private static final Hit ICE_CLAW = new Hit(1.1f, Hit.Kind.HIT, 22, 0.2, 0, 15, false, false);
    private static final Hit ICE_DROP = new Hit(2.2f, Hit.Kind.DOWN, 28, 0, -1.2, 0, true, true).noParry();
        private static final Hit COUNTER_FREEZE = new Hit(2.0f, Hit.Kind.HIT, 40, 0, 0, 0, true, false).noParry();
    private static final Hit SHATTER = new Hit(1.8f, Hit.Kind.KNOCK, 20, 1.0, 0.4, 0, true, false).noParry();
    private static final Hit BEAM = new Hit(1.5f, Hit.Kind.HIT, 12, 0.5, 0, 20, false, false);
    private static final Hit BEAM_QUICK = new Hit(0.9f, Hit.Kind.HIT, 16, 0.15, 0, 10, false, false);
    private static final Hit BEAM_BLAST = new Hit(1.2f, Hit.Kind.AIR, 20, 0.4, 0.6, 15, false, false);
    private static final Hit MUDA = new Hit(0.35f, Hit.Kind.HIT, 30, 0, 0, 0, true, false).noParry();
    private static final Hit MUDA_END = new Hit(2.6f, Hit.Kind.DOWN, 30, 0, -1.2, 0, true, true).noParry();

    private static final JobSkills.Set SKILLS = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, PlayerData.Entry e) {
            if (!EQUIP_VER.equals(e.str(P + "eqv"))) {
                e.str(P + "eqv", EQUIP_VER);
                e.str(P + "equip", "");
                Jobs.dirty(p);
            }
            return LIST;
        }
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
            boolean air = !p.isOnGround(), chain = Combat.inM1Chain(p);
            return switch (idx) {
                case 0 -> air ? diveDrain(p, e) : chain ? fingerStab(p, e) : lungeDrain(p, e);
                case 1 -> air ? iceDrop(p) : chain ? iceClaw(p) : vaporFreeze(p);
                case 2 -> might(p);
                case 3 -> regen(p);
                case 4 -> bloodThrall(p);
                case 5 -> freezeCounter(p);
                case 6 -> air ? beamAir(p) : chain ? beamQuick(p) : eyeBeam(p);
                case 7 -> muda(p, e);
                case 8 -> fleshBud(p);
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

    private static boolean alive(ServerPlayerEntity p) { return !p.isDisconnected() && p.isAlive(); }

    /** 내 앞 d 칸, 높이 up (잡은 상대를 붙들어 두는 자리) */
    private static Vec3d front(ServerPlayerEntity p, double d, double up) {
        return p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(d)).add(0, up, 0);
    }

    /** 잡힌 동안 내 앞 자리에 붙들어 둠 (from 틱부터 ticks 동안). 상대가 죽거나 멀어지면 놓음 */
    private static void pin(ServerPlayerEntity p, LivingEntity t, int from, int ticks, double d, double up) {
        for (int i = from; i < from + ticks; i++) {
            Tasks.later(i, () -> {
                if (!alive(p) || !t.isAlive() || t.squaredDistanceTo(p) > 36) return;
                Vec3d at = front(p, d, up);
                t.requestTeleport(at.x, at.y, at.z);
                t.setVelocity(0, 0, 0);
                t.velocityModified = true;
            });
        }
    }

    /** 나를 제자리에 묶음 (잡기 중 미끄러지지 않게) */
    private static void root(ServerPlayerEntity p, int from, int ticks) {
        for (int i = from; i < from + ticks; i++) Tasks.later(i, () -> { if (alive(p) && p.isOnGround()) Combat.move(p, new Vec3d(0, p.getVelocity().y, 0)); });
    }

    /** 피가 대상에서 내 입으로 흘러드는 줄 */
    private static void bloodLine(ServerWorld w, LivingEntity from, ServerPlayerEntity to) {
        Vec3d a = from.getPos().add(0, from.getHeight() * 0.75, 0), b = to.getPos().add(0, 1.5, 0);
        for (int i = 0; i <= 8; i++) {
            Vec3d q = a.lerp(b, i / 8.0);
            Tasks.later(i / 2, () -> w.spawnParticles(BLOOD, q.x, q.y, q.z, 2, 0.02, 0.02, 0.02, 0));
        }
    }

    /** 얼음: 대상에 푸른 얼음 덩어리를 씌우고 서리 */
    private static void iceFx(ServerWorld w, LivingEntity t, int life) {
        t.setFrozenTicks(Math.max(t.getFrozenTicks(), 200));
        AlchemyFx.display(w, Blocks.BLUE_ICE.getDefaultState(), t.getX(), t.getY(), t.getZ(), t.getWidth() + 0.4f, t.getHeight() + 0.2f, t.getWidth() + 0.4f, 0, life, 2);
        for (int i = 0; i < life; i += 5) Tasks.later(i, () -> w.spawnParticles(ParticleTypes.SNOWFLAKE, t.getX(), t.getY() + 1, t.getZ(), 10, 0.4, 0.6, 0.4, 0.01));
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.PLAYERS, 1.0f, 0.6f);
    }

    /** 얼음이 산산조각 */
    private static void shatterFx(ServerWorld w, LivingEntity t) {
        Vec3d c = t.getBoundingBox().getCenter();
        w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BLUE_ICE.getDefaultState()), c.x, c.y, c.z, 40, 0.4, 0.6, 0.4, 0.3);
        w.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 30, 0.5, 0.7, 0.5, 0.15);
        Fx.impactFlash(w, c, 0.8f);
        w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.6f);
    }

    // ① 흡혈 돌진 (땅, 참고: '빈약하다!'): 낮게 달려들어(1~6틱) 닿으면 어깨로 들이받고 → 목을 쥐어 머리 위로 들어 올림 →
    //   4틱마다 손가락을 꽂아 흡혈(다섯 번) → 옆으로 내던짐. 모션 vm_lunge (붙잡는 자세 6틱, 들어 올림 9틱, 내던짐 35틱)에 맞춤
    private static boolean lungeDrain(ServerPlayerEntity p, PlayerData.Entry e) {
        Motion.play(p, "vm_lunge");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PHANTOM_SWOOP, SoundCategory.PLAYERS, 0.8f, 1.3f);
        LivingEntity[] got = {null};
        for (int i = 1; i <= 6; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (got[0] != null || !alive(p) || !Combat.canAct(p)) return;
                Vec3d f = Combat.horizontal(p.getRotationVec(1f));
                Combat.move(p, new Vec3d(f.x * 0.85, p.getVelocity().y, f.z * 0.85));
                w.spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 0.2, p.getZ(), 2, 0.2, 0.05, 0.2, 0.01);
                LivingEntity t = Combat.targets(p, 2.0, 60, 1).stream().findFirst().orElse(null);
                if (t == null) { if (k == 6) Tasks.later(2, () -> { if (alive(p)) Motion.play(p, "stop"); }); return; }
                if (Combat.hit(p, t, GRAB) != Combat.Result.HIT) { Motion.play(p, "stop"); got[0] = p; return; }
                got[0] = t;
                Combat.move(p, Vec3d.ZERO);
                int base = 6 - k;   // 모션 6틱(들이받기)에 맞춰 나머지를 미룸
                Combat.afterSkill(p, base + 34);
                Combat.invuln(p, 4);
                Fx.word(w, t.getPos().add(0, 2.0, 0), Fx.Sfx.DOGO, 1.1f);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0f, 0.7f);
                pin(p, t, base + 3, 26, 0.75, 0.95);   // 9틱부터 머리 위로 들어 올린 채
                root(p, 1, base + 32);
                for (int s2 = 0; s2 < 5; s2++) {
                    final int ss = s2;
                    Tasks.later(base + 11 + s2 * 4, () -> {
                        if (!alive(p) || !t.isAlive()) return;
                        Combat.hit(p, t, SIP);
                        if (ss == 0) drink(p, e, t); else addBlood(e, 2);
                        bloodSpurt(w, t, p);
                        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 1.0f, 0.5f + ss * 0.06f);
                    });
                }
                Tasks.later(base + 35, () -> {
                    if (!alive(p) || !t.isAlive()) return;
                    Combat.hit(p, t, TOSS);
                    Fx.word(w, t.getPos().add(0, 2.2, 0), Fx.BLUNT, 1.2f);
                    w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.0f, 0.7f);
                });
            });
        }
        return true;
    }

    /** 손가락을 꽂을 때: 목에서 피가 뿜어지고 내 손을 타고 흘러듦 */
    private static void bloodSpurt(ServerWorld w, LivingEntity t, ServerPlayerEntity p) {
        Vec3d c = t.getPos().add(0, t.getHeight() * 0.8, 0);
        w.spawnParticles(BLOOD, c.x, c.y, c.z, 14, 0.18, 0.18, 0.18, 0);
        w.spawnParticles(new DustParticleEffect(new Vector3f(0.85f, 0.02f, 0.05f), 1.6f), c.x, c.y + 0.2, c.z, 6, 0.3, 0.4, 0.3, 0);
        bloodLine(w, t, p);
    }

    // ① 평타 중: 손끝 찌르기 — 빠르게 꽂고 조금 흡혈 (콤보 연장)
    private static boolean fingerStab(ServerPlayerEntity p, PlayerData.Entry e) {
        Motion.play(p, "vm_stab");
        Combat.afterSkill(p, 7);
        ServerWorld w = p.getServerWorld();
        Tasks.later(3, () -> {
            if (!alive(p)) return;
            for (LivingEntity t : Combat.targets(p, 3.2, 45, 1)) {
                if (Combat.hit(p, t, STAB) != Combat.Result.HIT) continue;
                addBlood(e, 4);
                addDrink(p, e, 15, false);
                w.spawnParticles(BLOOD, t.getX(), t.getY() + t.getHeight() * 0.6, t.getZ(), 14, 0.15, 0.2, 0.15, 0);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 1.0f, 0.8f);
            }
        });
        return true;
    }

    // ① 공중: 머리부터 내리꽂혀 상대를 깔아뭉갬(다운 추가타) → 무릎 꿇고 손가락을 꽂아 세 번 흡혈
    private static boolean diveDrain(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        LivingEntity aim = aimTarget(p, 10);
        Motion.play(p, "vm_dive");
        Combat.afterSkill(p, 26);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PHANTOM_SWOOP, SoundCategory.PLAYERS, 1.0f, 0.8f);
        boolean[] done = {false};
        for (int i = 1; i <= 14; i++) {
            Tasks.later(i, () -> {
                if (done[0] || !alive(p)) return;
                Vec3d dir = aim != null && aim.isAlive() ? aim.getPos().subtract(p.getPos()).normalize()
                        : Combat.horizontal(p.getRotationVec(1f)).multiply(0.5).add(0, -1, 0).normalize();
                Combat.move(p, dir.multiply(1.3));
                w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 3, 0.2, 0.3, 0.2, 0);
                List<LivingEntity> ts = Combat.targets(p, 2.2, 80, 2);
                if (ts.isEmpty() && !p.isOnGround()) return;
                done[0] = true;
                Combat.move(p, Vec3d.ZERO);
                Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.3, 2.6, Fx.RED, 6);
                Fx.debris(w, p.getPos(), 12, 1.2);
                w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.5f, 1.4f);
                for (LivingEntity t : ts) {
                    if (Combat.hit(p, t, DIVE) == Combat.Result.MISS) continue;
                    Fx.word(w, t.getPos().add(0, 1.6, 0), Fx.SLAM, 1.2f);
                    for (int s = 0; s < 3; s++) {
                        final int ss = s;
                        Tasks.later(6 + s * 5, () -> {
                            if (!alive(p) || !t.isAlive() || t.squaredDistanceTo(p) > 16) return;
                            if (ss == 0) drink(p, e, t); else addBlood(e, 2);
                            bloodLine(w, t, p);
                            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 1.0f, 0.5f);
                        });
                    }
                }
            });
        }
        return true;
    }

    // ② 기화냉동법 (땅): 낮게 파고들며 오른손바닥을 대면(4틱) 상대가 하얗게 얼어붙음 → 손을 머리 위로 들어 올리며 언 상대를 띄워 들고(8~18) →
    //   21틱에 산산조각 내며 내던짐
    private static boolean vaporFreeze(ServerPlayerEntity p) {
        Motion.play(p, "vm_freeze");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        for (int i = 1; i <= 3; i++) Tasks.later(i, () -> {
            if (!alive(p)) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 0.5, p.getVelocity().y, f.z * 0.5));
        });
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            LivingEntity t = Combat.targets(p, 2.8, 50, 1).stream().findFirst().orElse(null);
            Vec3d hand = front(p, 1.1, 1.0);
            w.spawnParticles(ParticleTypes.SNOWFLAKE, hand.x, hand.y, hand.z, 14, 0.15, 0.15, 0.15, 0.05);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 0.7f, 1.4f);
            if (t == null) { Motion.play(p, "stop"); return; }
            if (Combat.hit(p, t, FREEZE_GRAB) != Combat.Result.HIT) return;
            Combat.afterSkill(p, 26);
            iceFx(w, t, 18);
            Fx.flash(t, 0.7f);
            Fx.word(w, t.getPos().add(0, 2.1, 0), Fx.Sfx.GAGIN, 1.2f);
            for (int i = 1; i <= 16; i++) {   // 손을 들어 올리는 만큼 언 상대가 떠오름 (0 → 1.1칸)
                final double up = Math.min(1.1, i * 0.1);
                Tasks.later(i, () -> {
                    if (!alive(p) || !t.isAlive()) return;
                    Vec3d at = front(p, 1.05, up);
                    t.requestTeleport(at.x, at.y, at.z);
                    t.setVelocity(0, 0, 0);
                    t.velocityModified = true;
                });
            }
            root(p, 1, 17);
            Tasks.later(17, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Combat.hit(p, t, FREEZE_LIFT);
                shatterFx(w, t);
                Fx.word(w, t.getPos().add(0, 2.0, 0), Fx.Sfx.BAKIN, 1.3f);
            });
        });
        return true;
    }

    // ② 평타 중: 얼음 손톱 — 손등 쪽 사선 할퀴기, 맞으면 잠깐 얼어 경직이 길다 (가드 가능)
    private static boolean iceClaw(ServerPlayerEntity p) {
        Motion.play(p, "vm_iceclaw");
        Combat.afterSkill(p, 8);
        ServerWorld w = p.getServerWorld();
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            Fx.slash(w, p, 2.4, 140, false, Fx.WHITE);
            for (LivingEntity t : Combat.targets(p, 3.2, 60, 2)) {
                if (Combat.hit(p, t, ICE_CLAW) != Combat.Result.HIT) continue;
                t.setFrozenTicks(Math.max(t.getFrozenTicks(), 120));
                w.spawnParticles(ParticleTypes.SNOWFLAKE, t.getX(), t.getY() + 1, t.getZ(), 14, 0.3, 0.5, 0.3, 0.02);
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 0.8f, 1.2f);
        });
        return true;
    }

    // ② 공중: 앞 3칸 상대를 두 손으로 붙잡아 얼린 뒤 머리 위로 들었다가 땅으로 내리꽂음
    private static boolean iceDrop(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        LivingEntity t = near(p, 3.5);
        if (t == null) { JobSkills.bar(p, "붙잡을 대상이 없습니다 (3.5칸)", Formatting.RED); return false; }
        Motion.play(p, "vm_icedrop");
        Combat.afterSkill(p, 14);
        Tasks.later(2, () -> {
            if (!alive(p) || !t.isAlive()) return;
            if (Combat.hit(p, t, FREEZE_GRAB.withStun(20)) != Combat.Result.HIT) return;
            iceFx(w, t, 10);
            for (int i = 0; i < 6; i++) Tasks.later(i, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, 0.02, 0)); });
            pin(p, t, 1, 5, 0.9, 1.6);
            Tasks.later(6, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Combat.hit(p, t, ICE_DROP);
                shatterFx(w, t);
                Fx.word(w, t.getPos().add(0, 1.4, 0), Fx.SLAM, 1.3f);
                Combat.move(p, new Vec3d(0, -0.4, 0));
            });
        });
        return true;
    }

    // ③ 괴력 연격 (참고: KUA! 파생기): 짧게 뛰어들며 무릎(5틱) → 맞으면 0.8초 동안 평타로 파생
    //    평타 = 수도 → 손등 (약, 날려보냄) · 스페이스+평타 = 무릎 → 얼리는 손바닥 → 올려치기 (중, 높이 띄움) ·
    //    웅크리고(Shift)+평타 = 머리를 움켜쥐어 들었다가 땅에 내리꽂음 (강, 가드 깨기). 파생 안 하면 그대로 끝
    private static boolean might(ServerPlayerEntity p) {
        Motion.play(p, "vm_kua");
        Combat.afterSkill(p, 12);
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.7f, 0.8f);
        for (int i = 1; i <= 4; i++) Tasks.later(i, () -> {
            if (!alive(p)) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 0.55, i0(p) ? 0.18 : p.getVelocity().y, f.z * 0.55));
        });
        Tasks.later(5, () -> {
            if (!alive(p)) return;
            LivingEntity t = Combat.targets(p, 2.6, 55, 1).stream().findFirst().orElse(null);
            if (t == null || Combat.hit(p, t, KUA_KNEE) != Combat.Result.HIT) return;
            Fx.word(w, t.getPos().add(0, 2.0, 0), Fx.Sfx.DOGO, 1.1f);
            KUA_UNTIL.put(p.getUuid(), Combat.now() + 16);
            KUA_TARGET.put(p.getUuid(), t.getUuid());
            JobSkills.bar(p, "▶ 평타: 수도·손등  |  스페이스+평타: 올려치기  |  웅크리고+평타: 내리꽂기", Formatting.GOLD);
        });
        return true;
    }

    private static boolean i0(ServerPlayerEntity p) { return p.isOnGround(); }

    private static final Map<UUID, Long> KUA_UNTIL = new HashMap<>();
    private static final Map<UUID, UUID> KUA_TARGET = new HashMap<>();
    private static final Hit KUA_KNEE = new Hit(1.0f, Hit.Kind.HIT, 26, 0.1, 0, 10, false, false);
    private static final Hit KUA_CHOP = new Hit(0.9f, Hit.Kind.HIT, 18, 0.1, 0, 10, false, false);
    private static final Hit KUA_BACK = new Hit(1.4f, Hit.Kind.KNOCK, 18, 1.1, 0.3, 15, false, false);
    private static final Hit KUA_PALM = new Hit(0.8f, Hit.Kind.HIT, 24, 0, 0, 10, false, false);
    private static final Hit KUA_UPPER = new Hit(1.4f, Hit.Kind.AIR, 30, 0.1, 1.15, 15, false, false);
    private static final Hit KUA_GRAB = new Hit(0.6f, Hit.Kind.HIT, 30, 0, 0, 0, true, false).noParry();
    private static final Hit KUA_SLAM = new Hit(2.4f, Hit.Kind.DOWN, 30, 0, -1.2, 0, true, true).noParry();

    /** 괴력 연격 뒤 0.8초 안의 평타 = 파생기 */
    @Override
    public boolean m1Override(ServerPlayerEntity p, boolean up) {
        Long until = KUA_UNTIL.get(p.getUuid());
        if (until == null) return false;
        if (Combat.now() > until) { KUA_UNTIL.remove(p.getUuid()); return false; }
        KUA_UNTIL.remove(p.getUuid());
        UUID tid = KUA_TARGET.remove(p.getUuid());
        ServerWorld w = p.getServerWorld();
        LivingEntity t = tid != null && w.getEntity(tid) instanceof LivingEntity le && le.isAlive() && le.squaredDistanceTo(p) < 25 ? le : null;
        if (t == null) return false;
        if (p.isSneaking()) kuaHeavy(p, t);
        else if (up) kuaMedium(p, t);
        else kuaLight(p, t);
        return true;
    }

    /** 약: 수도(3) → 손등(8, 날려보냄) */
    private static void kuaLight(ServerPlayerEntity p, LivingEntity t) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "vm_kua_l");
        Combat.afterSkill(p, 14);
        Tasks.later(3, () -> { if (alive(p) && t.isAlive()) { Combat.hit(p, t, KUA_CHOP); Fx.slash(w, p, 2.4, 55, false, Fx.RED); } });
        Tasks.later(8, () -> {
            if (!alive(p) || !t.isAlive()) return;
            if (Combat.hit(p, t, KUA_BACK) == Combat.Result.HIT) Fx.word(w, t.getPos().add(0, 2.0, 0), Fx.BLUNT, 1.2f);
            Fx.slash(w, p, 2.4, 0, true, Fx.RED);
        });
    }

    /** 중: 무릎(3) → 얼리는 손바닥(9) → 올려치기(14, 높이 띄움) */
    private static void kuaMedium(ServerPlayerEntity p, LivingEntity t) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "vm_kua_m");
        Combat.afterSkill(p, 18);
        Tasks.later(3, () -> { if (alive(p) && t.isAlive()) Combat.hit(p, t, KUA_CHOP); });
        Tasks.later(9, () -> {
            if (!alive(p) || !t.isAlive()) return;
            if (Combat.hit(p, t, KUA_PALM) == Combat.Result.HIT) iceFx(w, t, 6);
        });
        Tasks.later(14, () -> {
            if (!alive(p) || !t.isAlive()) return;
            if (Combat.hit(p, t, KUA_UPPER) == Combat.Result.HIT) { shatterFx(w, t); Fx.rings(w, t.getBoundingBox().getCenter(), new Vec3d(0, 1, 0), 2.0f, 2); }
            Fx.slash(w, p, 2.4, 100, true, Fx.RED);
        });
    }

    /** 강 (URYYY): 머리를 움켜쥐고(3) 들어 올렸다가(8~12) 땅에 내리꽂음(14, 가드 깨기·다운) */
    private static void kuaHeavy(ServerPlayerEntity p, LivingEntity t) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "vm_kua_h");
        Combat.afterSkill(p, 22);
        Tasks.later(3, () -> {
            if (!alive(p) || !t.isAlive()) return;
            if (Combat.hit(p, t, KUA_GRAB) != Combat.Result.HIT) return;
            for (int i = 1; i <= 10; i++) {
                final double up = i <= 5 ? i * 0.2 : 1.0;
                Tasks.later(i, () -> {
                    if (!alive(p) || !t.isAlive()) return;
                    Vec3d at = front(p, 0.9, up);
                    t.requestTeleport(at.x, at.y, at.z);
                    t.setVelocity(0, 0, 0);
                    t.velocityModified = true;
                });
            }
            root(p, 1, 12);
            Tasks.later(5, () -> w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.7f, 1.7f));
            Tasks.later(11, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Combat.hit(p, t, KUA_SLAM);
                Fx.word(w, t.getPos().add(0, 1.4, 0), Fx.SLAM, 1.4f);
                Fx.debris(w, t.getPos(), 16, 1.4);
                Fx.wave(w, t.getPos().add(0, 0.1, 0), 0.3, 3.0, Fx.WHITE, 7);
                Fx.shakeAround(w, t.getPos(), 8, 0.6f);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.6f, 1.2f);
            });
        });
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

    /** 피의 하인: 30초 안에 흡혈한 몬스터 — 손바닥을 위로 들어 올리면 몬스터 발밑에서 피가 소용돌이치며 솟고 눈이 붉게 빛남 */
    private static boolean bloodThrall(ServerPlayerEntity p) {
        UUID id = LAST_PREY.get(p.getUuid());
        Long at = LAST_PREY_AT.get(p.getUuid());
        if (id == null || at == null || Combat.now() - at > 600) { JobSkills.bar(p, "30초 안에 흡혈한 몬스터가 없습니다", Formatting.RED); return false; }
        Entity t = p.getServerWorld().getEntity(id);
        if (!(t instanceof MobEntity m) || !m.isAlive() || m.squaredDistanceTo(p) > 16 * 16) { JobSkills.bar(p, "흡혈한 몬스터가 근처에 없습니다", Formatting.RED); return false; }
        if (Thralls.isThrall(m)) { JobSkills.bar(p, "이미 하인입니다", Formatting.GRAY); return false; }
        Motion.play(p, "vm_thrall");
        Combat.afterSkill(p, 10);
        dominateFx(p, m, 16);
        Tasks.later(12, () -> { if (alive(p) && m.isAlive()) Thralls.bind(p, m, 60, 2, "피의 하인"); });
        return true;
    }

    /** 지배 연출: 대상 발밑에서 피가 나선으로 솟아오르고, 마지막에 머리에서 붉은 눈빛 + 일어나는 소리 */
    private static void dominateFx(ServerPlayerEntity p, LivingEntity t, int len) {
        ServerWorld w = p.getServerWorld();
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_CONVERTED, SoundCategory.PLAYERS, 0.8f, 0.7f);
        for (int i = 0; i < len; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                if (!t.isAlive()) return;
                double h = t.getHeight() * k / (double) len;
                for (int arm = 0; arm < 3; arm++) {
                    double a = k * 0.7 + arm * Math.PI * 2 / 3;
                    double r = 0.75 - 0.3 * k / len;
                    w.spawnParticles(BLOOD, t.getX() + Math.cos(a) * r, t.getY() + h, t.getZ() + Math.sin(a) * r, 2, 0.02, 0.02, 0.02, 0);
                }
                if (k % 4 == 0) w.spawnParticles(ParticleTypes.SQUID_INK, t.getX(), t.getY() + 0.1, t.getZ(), 3, 0.4, 0.05, 0.4, 0.01);
            });
        }
        Tasks.later(len, () -> {
            if (!t.isAlive()) return;
            Vec3d eye = t.getEyePos();
            w.spawnParticles(new DustParticleEffect(new Vector3f(1.0f, 0.05f, 0.05f), 1.2f), eye.x, eye.y, eye.z, 12, 0.15, 0.05, 0.15, 0);
            w.spawnParticles(BLOOD, t.getX(), t.getY() + 1, t.getZ(), 30, 0.3, 0.6, 0.3, 0);
            Fx.rings(w, t.getBoundingBox().getCenter(), new Vec3d(0, 1, 0), 1.6f, 1);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.6f, 1.4f);
        });
    }

    // ⑥ 냉동 반격 (참고: '순식간에 얼린 것이다'): 오른손바닥을 내밀고 1.5초(30틱) 받아내는 자세 — 가드를 안 눌러도 됨.
    //    그 사이 (어느 방향이든) 맞으면 피해 없이 → 하얀 섬광 + 손바닥을 꽂아 때린 상대를 얼림 → 14틱 뒤 산산조각
    private static boolean freezeCounter(ServerPlayerEntity p) {
        Motion.play(p, "vm_counter");
        Combat.afterSkill(p, 28);
        ServerWorld w = p.getServerWorld();
        for (int i = 3; i < 30; i += 4) Tasks.later(i, () -> {   // 내민 손바닥에 서리
            if (!alive(p)) return;
            Vec3d hand = front(p, 0.7, 1.35);
            w.spawnParticles(ParticleTypes.SNOWFLAKE, hand.x, hand.y, hand.z, 2, 0.08, 0.08, 0.08, 0.01);
        });
        Combat.counterWindow(p, 30, atk -> {
            if (!alive(p)) return;
            Motion.play(p, "vm_counter_hit");
            Combat.afterSkill(p, 16);
            Combat.invuln(p, 12);
            Fx.flash(p, 0.9f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.6f, 0.6f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.2f, 0.7f);
            // 때린 상대 쪽으로 몸을 돌림
            Vec3d to = atk.getPos().subtract(p.getPos());
            p.setYaw((float) Math.toDegrees(Math.atan2(-to.x, to.z)));
            p.setHeadYaw(p.getYaw());
            Tasks.later(2, () -> {
                if (!alive(p) || !atk.isAlive() || atk.squaredDistanceTo(p) > 36) return;
                if (Combat.hit(p, atk, COUNTER_FREEZE) != Combat.Result.HIT) return;
                iceFx(w, atk, 16);
                Fx.flash(atk, 1.0f);
                Fx.impactFlash(w, atk.getBoundingBox().getCenter(), 1.2f);
                Fx.word(w, atk.getPos().add(0, 2.2, 0), Fx.Sfx.GAGIN, 1.4f);
                Tasks.later(14, () -> { if (alive(p) && atk.isAlive()) { Combat.hit(p, atk, SHATTER); shatterFx(w, atk); } });
            });
        });
        return true;
    }

    /**
     * 눈에서 쏘는 광선 (참고: 공열안자경): delay 틱 뒤, 눈앞에 하얀 가시 폭발 → 붉은 심 둘레를 하얀 고리가 감은 나선 광선이 frames 틱 동안 뻗음.
     * 맞힌 대상마다 h (pierce = 관통 수, 맞은 자리 폭발). 끝(벽·땅)에 닿으면 onEnd
     */
    private static void beam(ServerPlayerEntity p, int delay, double len0, Hit h, int pierce, int frames, java.util.function.Consumer<Vec3d> onEnd) {
        ServerWorld w = p.getServerWorld();
        Tasks.later(delay, () -> {
            if (!alive(p)) return;
            Vec3d eye = p.getEyePos(), look = p.getRotationVector();
            HitResult hit = p.raycast(len0, 0, false);
            double len = hit.getType() == HitResult.Type.MISS ? len0 : hit.getPos().distanceTo(eye);
            Vec3d up0 = Math.abs(look.y) > 0.95 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            Vec3d sA = look.crossProduct(up0).normalize(), sB = look.crossProduct(sA).normalize();
            Vec3d start = eye.add(look.multiply(0.5));
            // 눈앞 가시 폭발
            Fx.burst(w, start, look, 1.1f);
            Fx.rings(w, start, look, 1.2f, 2);
            w.spawnParticles(ParticleTypes.END_ROD, start.x, start.y, start.z, 14, 0.05, 0.05, 0.05, 0.25);
            Set<UUID> done = new HashSet<>();
            DustParticleEffect core = new DustParticleEffect(new Vector3f(0.9f, 0.05f, 0.05f), 1.3f);
            DustParticleEffect coil = new DustParticleEffect(new Vector3f(1.0f, 0.95f, 0.95f), 0.8f);
            for (int i = 0; i < frames; i++) {
                final int k = i;
                Tasks.later(i, () -> {
                    for (double d = 0.5; d < len; d += 0.22) {
                        Vec3d q = start.add(look.multiply(d - 0.5));
                        w.spawnParticles(core, q.x, q.y, q.z, 1, 0, 0, 0, 0);
                        double a = d * 5.5 + k * 1.3;   // 나선 고리
                        Vec3d r = sA.multiply(Math.cos(a) * 0.17).add(sB.multiply(Math.sin(a) * 0.17));
                        w.spawnParticles(coil, q.x + r.x, q.y + r.y, q.z + r.z, 1, 0, 0, 0, 0);
                    }
                    for (Entity t : w.getOtherEntities(p, p.getBoundingBox().stretch(look.multiply(len)).expand(1), x -> Targets.canHit(p, x))) {
                        if (done.contains(t.getUuid()) || done.size() >= pierce) continue;
                        Vec3d c = t.getPos().add(0, t.getHeight() / 2, 0);
                        double along = c.subtract(eye).dotProduct(look);
                        if (along < 0 || along > len) continue;
                        if (c.distanceTo(eye.add(look.multiply(along))) > t.getWidth() / 2 + 0.6) continue;
                        done.add(t.getUuid());
                        if (Combat.hit(p, (LivingEntity) t, h) == Combat.Result.MISS) continue;
                        Fx.burst(w, c, look, 1.0f);
                        Fx.impactFlash(w, c, 0.9f);
                        w.spawnParticles(BLOOD, c.x, c.y, c.z, 16, 0.2, 0.2, 0.2, 0);
                    }
                });
            }
            if (hit.getType() != HitResult.Type.MISS) {
                Vec3d hp = hit.getPos();
                w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, w.getBlockState(BlockPos.ofFloored(hp.add(look.multiply(0.1))))),
                        hp.x, hp.y, hp.z, 25, 0.2, 0.2, 0.2, 0.2);
                if (onEnd != null) onEnd.accept(hp);
            }
            Fx.shake(p, 0.3f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GUARDIAN_ATTACK, SoundCategory.PLAYERS, 1.2f, 2.0f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.4f, 2.0f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 1.6f);
        });
    }

    /** 광선 기 모으기: 관자놀이에 댄 손 사이 눈에서 붉은 빛이 모임 */
    private static void beamCharge(ServerPlayerEntity p, int from, int to) {
        ServerWorld w = p.getServerWorld();
        for (int i = from; i < to; i++) Tasks.later(i, () -> {
            if (!alive(p)) return;
            Vec3d e2 = p.getEyePos().add(p.getRotationVector().multiply(0.35));
            w.spawnParticles(new DustParticleEffect(new Vector3f(1.0f, 0.1f, 0.1f), 0.7f), e2.x, e2.y, e2.z, 3, 0.12, 0.04, 0.12, 0);
        });
        Tasks.later(from, () -> { if (alive(p)) w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.6f, 1.8f); });
    }

    // ⑦ 혈안 광선 (땅): 관자놀이에 손을 대고 고개를 숙여 모음(4~9) → 10틱에 고개를 들며 발사, 20칸 관통
    private static boolean eyeBeam(ServerPlayerEntity p) {
        Motion.play(p, "vm_beam");
        Combat.afterSkill(p, 18);
        beamCharge(p, 4, 10);
        beam(p, 10, 20, BEAM, 8, 5, null);
        return true;
    }

    // ⑦ 평타 중: 관자놀이에 손을 대며 고개를 내밀어 짧은 단발 (12칸, 한 명)
    private static boolean beamQuick(ServerPlayerEntity p) {
        Motion.play(p, "vm_beam_quick");
        Combat.afterSkill(p, 8);
        beam(p, 4, 12, BEAM_QUICK, 1, 2, null);
        return true;
    }

    // ⑦ 공중: 공중에 멈춰 관자놀이에 손 → 몸을 숙여 내려다보며 쏨(8틱) — 땅에 닿은 곳이 터져 주변을 띄움
    private static boolean beamAir(ServerPlayerEntity p) {
        Motion.play(p, "vm_beam_air");
        Combat.afterSkill(p, 16);
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 16; i++) Tasks.later(i, () -> { if (alive(p) && !p.isOnGround()) Combat.move(p, new Vec3d(p.getVelocity().x * 0.5, 0.0, p.getVelocity().z * 0.5)); });
        beamCharge(p, 3, 8);
        beam(p, 8, 22, BEAM, 8, 5, hp -> {
            Fx.burst(w, hp, new Vec3d(0, 1, 0), 1.2f);
            Fx.debris(w, hp, 16, 1.6);
            Fx.wave(w, hp.add(0, 0.1, 0), 0.3, 2.8, Fx.RED, 7);
            w.playSound(null, BlockPos.ofFloored(hp), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.7f, 1.4f);
            for (Entity t : w.getOtherEntities(p, new Box(hp, hp).expand(2.2), x -> Targets.canHit(p, x))) Combat.hit(p, (LivingEntity) t, BEAM_BLAST);
        });
        return true;
    }

    // ⑧ 무다 연타 (참고: '무다무다무다'): 낮게 달려들어(1~3) 어깨로 들이받아 띄우고(4) → 오른손으로 목을 쥐어 머리 위로 들어 올림(7) →
    //    3틱마다 손가락을 꽂아 흡혈(9~30, 8번) → 머리 위로 크게 휘둘러(36) 앞의 땅에 내리꽂음(39, 다운). 모션 vm_muda 에 맞춤
    private static boolean muda(ServerPlayerEntity p, PlayerData.Entry e) {
        Motion.play(p, "vm_muda");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        for (int i = 1; i <= 3; i++) Tasks.later(i, () -> {
            if (!alive(p)) return;
            Vec3d f = Combat.horizontal(p.getRotationVec(1f));
            Combat.move(p, new Vec3d(f.x * 0.7, p.getVelocity().y, f.z * 0.7));
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 0.2, p.getZ(), 2, 0.2, 0.05, 0.2, 0.01);
        });
        Tasks.later(4, () -> {
            if (!alive(p)) return;
            LivingEntity t = Combat.targets(p, 2.8, 55, 1).stream().findFirst().orElse(null);
            if (t == null) { Motion.play(p, "stop"); return; }
            if (Combat.hit(p, t, GRAB) != Combat.Result.HIT) { Motion.play(p, "stop"); return; }
            Combat.afterSkill(p, 44);
            Combat.move(p, Vec3d.ZERO);
            Fx.word(w, t.getPos().add(0, 2.0, 0), Fx.Sfx.DOGO, 1.1f);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0f, 0.7f);
            for (int i = 1; i <= 3; i++) {   // 들이받아 띄움
                final double up = i * 0.3;
                Tasks.later(i, () -> { if (alive(p) && t.isAlive()) { Vec3d at = front(p, 0.8, up); t.requestTeleport(at.x, at.y, at.z); t.setVelocity(0, 0, 0); t.velocityModified = true; } });
            }
            pin(p, t, 4, 28, 0.75, 0.95);   // 7틱부터 머리 위로 들어 올린 채
            root(p, 1, 34);
            for (int s2 = 0; s2 < 8; s2++) {
                final int ss = s2;
                Tasks.later(5 + s2 * 3, () -> {
                    if (!alive(p) || !t.isAlive()) return;
                    Combat.hit(p, t, MUDA);
                    addBlood(e, 1.5);
                    if (ss == 0) addDrink(p, e, 40, false);
                    bloodSpurt(w, t, p);
                    w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_WEAK, SoundCategory.PLAYERS, 1.0f, 1.0f + ss * 0.05f);
                    w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 0.6f, 0.5f);
                    if (ss % 3 == 0) Fx.word(w, t.getBoundingBox().getCenter().add(0, 1.0, 0), Fx.Sfx.DODODO, 1.0f);
                });
            }
            for (int i = 32; i <= 34; i++) {   // 머리 위로 휘두름 (뒤로 넘겼다가)
                final int k = i;
                Tasks.later(k, () -> { if (alive(p) && t.isAlive()) { Vec3d at = front(p, 0.2 - (k - 32) * 0.15, 1.6); t.requestTeleport(at.x, at.y, at.z); t.setVelocity(0, 0, 0); t.velocityModified = true; } });
            }
            Tasks.later(35, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Vec3d at = front(p, 1.4, 0.2);
                t.requestTeleport(at.x, at.y, at.z);
                Combat.hit(p, t, MUDA_END);
                Fx.word(w, t.getPos().add(0, 1.4, 0), Fx.SLAM, 1.5f);
                Fx.debris(w, t.getPos(), 18, 1.6);
                Fx.wave(w, t.getPos().add(0, 0.1, 0), 0.3, 3.4, Fx.WHITE, 8);
                Fx.shakeAround(w, t.getPos(), 10, 0.7f);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.7f, 1.1f);
            });
        });
        return true;
    }

    /** 육아 지배: 이마에서 육아를 떼어(3) 두 손가락으로 튕겨 날림(7) → 붉은 육아가 날아가 대상 이마에 박히면 지배 (플레이어·보스 불가) */
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
        if (Thralls.isThrall(t)) { JobSkills.bar(p, "이미 하인입니다", Formatting.GRAY); return false; }
        final LivingEntity tt = t;
        Motion.play(p, "vm_budshot");
        Combat.afterSkill(p, 10);
        ServerWorld w = p.getServerWorld();
        Tasks.later(3, () -> { if (alive(p)) { Vec3d f = p.getEyePos().add(p.getRotationVector().multiply(0.3)); w.spawnParticles(BLOOD, f.x, f.y + 0.1, f.z, 6, 0.05, 0.05, 0.05, 0); w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_SLIME_BLOCK_STEP, SoundCategory.PLAYERS, 0.8f, 1.4f); } });
        Tasks.later(7, () -> {
            if (!alive(p) || !tt.isAlive()) return;
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 0.6f, 1.6f);
            Vec3d from = p.getPos().add(Combat.horizontal(p.getRotationVec(1f)).multiply(0.6)).add(0, 1.45, 0);
            int n = Math.max(3, (int) (from.distanceTo(tt.getEyePos()) / 1.2));
            for (int i = 0; i <= n; i++) {
                final int k = i;
                Tasks.later(k, () -> {
                    if (!tt.isAlive()) return;
                    Vec3d q = from.lerp(tt.getEyePos(), k / (double) n);
                    w.spawnParticles(new DustParticleEffect(new Vector3f(0.75f, 0.1f, 0.2f), 1.5f), q.x, q.y, q.z, 3, 0.03, 0.03, 0.03, 0);
                });
            }
            Tasks.later(n, () -> {
                if (!alive(p) || !tt.isAlive()) return;
                w.playSound(null, tt.getBlockPos(), SoundEvents.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 1.0f, 0.6f);
                dominateFx(p, tt, 12);
                Tasks.later(12, () -> { if (alive(p) && tt.isAlive()) Thralls.bind(p, tt, 60, 2, "육아에 지배당함"); });
            });
        });
        return true;
    }

    // ═════════ 각성 '혈광 해방' · 각성기 '피의 만찬' ═════════

    /**
     * 각성 '혈광 해방' (80틱 컷신 vm_awaken, 참고: 대전 시작·각성 포즈 '바-ン'): 고개를 숙이고 가슴에 손(0) → 고개를 젖혀 포효(16, 붉은 충격파) →
     * 오른팔을 머리 위로 크게 휘둘러 올림(32) → 넓게 주저앉으며 두 손을 갈고리처럼 펼침(46, 피의 기둥·박쥐 떼) → 버팀. 컷신 동안 무적, 각성 시간은 그만큼 늘림
     */
    private static final int AWAKEN_SCENE = 80;

    @Override
    public void onAwaken(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        Motion.play(p, "vm_awaken");
        CutscenePayload pl = new CutscenePayload("vm_awaken", p.getId(), -1);
        if (ServerPlayNetworking.canSend(p, CutscenePayload.ID)) ServerPlayNetworking.send(p, pl);
        Combat.invuln(p, AWAKEN_SCENE);
        Combat.afterSkill(p, AWAKEN_SCENE - 6);
        Awaken.extend(p, AWAKEN_SCENE);
        addBlood(e, 20);
        for (int t = 0; t < 16; t += 3) Tasks.later(t, () -> {   // 발밑에 피가 고임
            if (alive(p)) w.spawnParticles(BLOOD, p.getX(), p.getY() + 0.05, p.getZ(), 8, 0.6, 0.02, 0.6, 0);
        });
        Tasks.later(2, () -> { if (alive(p)) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.6f, 0.7f); });
        Tasks.later(10, () -> { if (alive(p)) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.8f, 0.8f); });
        Tasks.later(16, () -> {   // 포효
            if (!alive(p)) return;
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 1.2f, 1.5f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 1.0f, 1.3f);
            Fx.wave(w, p.getPos().add(0, 1.2, 0), 0.4, 6.0, Fx.RED, 10);
            Fx.shakeAround(w, p.getPos(), 14, 0.7f);
            w.spawnParticles(BLOOD, p.getX(), p.getEyeY(), p.getZ(), 40, 0.3, 0.2, 0.3, 0);
        });
        Tasks.later(32, () -> { if (alive(p)) { w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 0.6f); Fx.slash(w, p, 1.8, 80, true, Fx.RED); } });
        Tasks.later(46, () -> {   // 주저앉는 순간: 피의 기둥이 솟고 박쥐 떼가 흩어짐
            if (!alive(p)) return;
            Vec3d c = p.getPos();
            for (int i = 0; i < 24; i++) {
                final int k = i;
                Tasks.later(i / 2, () -> {
                    double a = k * Math.PI * 2 / 12;
                    double r = 0.9 + (k % 3) * 0.15;
                    w.spawnParticles(BLOOD, c.x + Math.cos(a) * r, c.y + k * 0.18, c.z + Math.sin(a) * r, 6, 0.05, 0.1, 0.05, 0);
                });
            }
            w.spawnParticles(ParticleTypes.SQUID_INK, c.x, c.y + 1.2, c.z, 40, 0.6, 0.8, 0.6, 0.25);
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.3, c.z, 30, 1.2, 0.2, 1.2, 0.02);
            Fx.wave(w, c.add(0, 0.1, 0), 0.5, 7.0, Fx.RED, 12);
            Fx.rings(w, c.add(0, 1, 0), new Vec3d(0, 1, 0), 3.5f, 3);
            Fx.word(w, c.add(0, 3.0, 0), Fx.Sfx.DOGOOON, 1.6f);
            Fx.shakeAround(w, c, 16, 0.9f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.7f, 1.6f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_BAT_TAKEOFF, SoundCategory.PLAYERS, 1.4f, 0.7f);
        });
        for (int t = 50; t < AWAKEN_SCENE; t += 4) Tasks.later(t, () -> {
            if (alive(p)) w.spawnParticles(BLOOD, p.getX(), p.getY() + 1.0, p.getZ(), 6, 0.5, 0.7, 0.5, 0);
        });
        Tasks.later(AWAKEN_SCENE - 4, () -> {
            if (alive(p) && Awaken.active(p)) JobSkills.bar(p, "🩸 혈광 해방 — 주는 피해 ×1.2, 평타가 맞을 때마다 흡혈  (G = 피의 만찬)", Formatting.DARK_RED);
        });
        for (int t = AWAKEN_SCENE; t < 900 + AWAKEN_SCENE; t += 20) Tasks.later(t, () -> {
            if (alive(p) && Awaken.active(p)) w.spawnParticles(BLOOD, p.getX(), p.getY() + 1.0, p.getZ(), 3, 0.3, 0.5, 0.3, 0);
        });
    }

    /** 컷신 길이 (클라이언트 Cutscene 대본 vm_ult 와 맞춤) */
    private static final int ULT_SCENE = 110;
    private static final Hit ULT_PIERCE = new Hit(1.5f, Hit.Kind.HIT, 100, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_CLAW = new Hit(0.55f, Hit.Kind.HIT, 80, 0, 0, 0, true, false).noParry();
    private static final Hit ULT_END = new Hit(6.0f, Hit.Kind.DOWN, 40, 0, -1.2, 0, true, true).noParry();

    /**
     * 각성기 '피의 만찬' (110틱 컷신): 낮게 달려듦 → 손을 가슴에 꽂아 얼려 붙들고(8) → 두 손 손톱 난타 8번(20~41) →
     * 웅크렸다가 높이 뛰어오름(50) → 공중에서 손끝을 하늘로 세웠다가 아래를 가리킴(62) → 머리부터 내리꽂혀 손끝으로 박음(66, 다운) →
     * 한쪽 무릎 → 천천히 일어나 두 팔을 펴고 웃음. 잡힌 상대는 내리꽂힐 때까지 얼어서 묶임, 플레이어면 같은 컷신
     */
    @Override
    public boolean ultimate(ServerPlayerEntity p, PlayerData.Entry e) {
        ServerWorld w = p.getServerWorld();
        LivingEntity t = near(p, 7);
        if (t == null) { JobSkills.bar(p, "앞에 대상이 없습니다 (7칸)", Formatting.GRAY); return false; }
        Motion.play(p, "vm_ult");
        Combat.invuln(p, ULT_SCENE);
        Combat.afterSkill(p, ULT_SCENE - 6);
        Awaken.extend(p, ULT_SCENE);
        List<ServerPlayerEntity> viewers = new ArrayList<>(List.of(p));
        if (t instanceof ServerPlayerEntity sp) viewers.add(sp);
        CutscenePayload pl = new CutscenePayload("vm_ult", p.getId(), t.getId());
        for (ServerPlayerEntity v : viewers) if (ServerPlayNetworking.canSend(v, CutscenePayload.ID)) ServerPlayNetworking.send(v, pl);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PHANTOM_SWOOP, SoundCategory.PLAYERS, 1.0f, 0.9f);
        // 0~7: 상대 앞까지 달려듦
        for (int i = 0; i < 7; i++) Tasks.later(i, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Vec3d to = t.getPos().subtract(p.getPos());
            double d = Math.sqrt(to.x * to.x + to.z * to.z);
            if (d < 1.3) { Combat.move(p, new Vec3d(0, p.getVelocity().y, 0)); return; }
            Vec3d dir = Combat.horizontal(to);
            Combat.move(p, new Vec3d(dir.x * Math.min(1.1, d - 1.1), p.getVelocity().y, dir.z * Math.min(1.1, d - 1.1)));
        });
        // 8: 손을 가슴에 꽂아 얼림 → 내리꽂힐 때까지 그 자리에 묶음
        Tasks.later(8, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Combat.hit(p, t, ULT_PIERCE);
            iceFx(w, t, 58);
            Fx.flash(t, 1.0f);
            Fx.word(w, t.getPos().add(0, 2.2, 0), Fx.Sfx.DOGO, 1.4f);
            w.spawnParticles(BLOOD, t.getX(), t.getY() + 1.2, t.getZ(), 30, 0.2, 0.3, 0.2, 0);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.2f, 0.6f);
            Vec3d lock = t.getPos();
            for (int i = 1; i <= 58; i++) Tasks.later(i, () -> {
                if (!t.isAlive()) return;
                if (t.squaredDistanceTo(lock) > 0.04) t.requestTeleport(lock.x, lock.y, lock.z);
                t.setVelocity(0, Math.min(0, t.getVelocity().y), 0);
                t.velocityModified = true;
            });
        });
        // 20~41: 손톱 난타
        for (int s = 0; s < 8; s++) {
            final int ss = s;
            Tasks.later(20 + s * 3, () -> {
                if (!alive(p) || !t.isAlive()) return;
                Combat.hit(p, t, ULT_CLAW);
                Vec3d fw = Combat.horizontal(p.getRotationVec(1f));
                Vec3d sd = new Vec3d(-fw.z, 0, fw.x).multiply(ss % 2 == 0 ? 0.25 : -0.25);
                Fx.kickStreak(w, p.getPos().add(fw.multiply(1.3)).add(sd).add(0, 1.25, 0), fw);   // 곧게 찌르는 손끝 줄기
                w.spawnParticles(BLOOD, t.getX(), t.getY() + 1.2, t.getZ(), 8, 0.2, 0.3, 0.2, 0);
                w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.3f + ss * 0.05f);
            });
        }
        // 46~50: 웅크렸다가 높이 뛰어오름 → 62까지 공중에 머묾
        Tasks.later(48, () -> { if (alive(p)) { Combat.move(p, new Vec3d(0, 1.05, 0)); Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.3, 3.0, Fx.WHITE, 6); w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GOAT_LONG_JUMP, SoundCategory.PLAYERS, 1.0f, 0.8f); } });
        for (int i = 56; i < 63; i++) Tasks.later(i, () -> { if (alive(p)) Combat.move(p, new Vec3d(0, 0.03, 0)); });
        // 63~66: 머리부터 내리꽂힘
        for (int i = 63; i <= 67; i++) Tasks.later(i, () -> {
            if (!alive(p) || !t.isAlive()) return;
            Vec3d to = t.getPos().add(0, 1.0, 0).subtract(p.getPos());
            if (to.length() < 1.2) { Combat.move(p, Vec3d.ZERO); return; }
            Combat.move(p, to.normalize().multiply(Math.min(2.2, to.length())));
        });
        Tasks.later(67, () -> {
            if (!alive(p)) return;
            if (t.isAlive()) {
                Combat.hit(p, t, ULT_END);
                shatterFx(w, t);
                Fx.word(w, t.getPos().add(0, 1.8, 0), Fx.Sfx.DOGOOON, 1.8f);
                Fx.rings(w, t.getBoundingBox().getCenter(), new Vec3d(0, 1, 0), 4f, 3);
                Fx.debris(w, t.getPos(), 26, 2.0);
                Fx.impactFlash(w, t.getBoundingBox().getCenter(), 1.8f);
                drink(p, e, t);
                addBlood(e, 20);
                bloodLine(w, t, p);
            }
            Fx.wave(w, p.getPos().add(0, 0.1, 0), 0.5, 6.0, Fx.RED, 10);
            Fx.shakeAround(w, p.getPos(), 16, 1.0f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 1.2f, 0.7f);
        });
        // 94: 두 팔을 펴고 웃음
        Tasks.later(94, () -> {
            if (!alive(p)) return;
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 1.0f, 1.6f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EVOKER_CELEBRATE, SoundCategory.PLAYERS, 1.0f, 0.6f);
            w.spawnParticles(BLOOD, p.getX(), p.getY() + 1.4, p.getZ(), 60, 0.7, 0.8, 0.7, 0);
        });
        return true;
    }
}
