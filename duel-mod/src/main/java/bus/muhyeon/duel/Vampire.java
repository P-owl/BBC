package bus.muhyeon.duel;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
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
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.item.Item;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 뱀파이어 (석가면). 단계: 1 일반 뱀파이어 · 2 숙련된 뱀파이어 · 3 기둥 속 사내 · 4 완전생물
 *
 * 되는 법: 밤에 석가면을 머리에 쓰고, 쓴 채로 피해를 입으면(피를 묻히면) 가면에서 가시가 튀어나와 뱀파이어가 됨. 기존 직업은 사라짐.
 * 종족 규칙
 *   햇빛: 낮에 하늘이 보이는 곳에 서 있으면 1초마다 4 피해 + 불 (비·그늘·지하는 안전)  ── 기둥 속 사내는 석화, 완전생물은 면역
 *   피 게이지(0~100): 1분에 2씩(낮엔 5씩) 줄고 흡혈로만 참. 자연 재생은 배고픔이 아니라 피를 씀(체력 1 = 피 1)
 *     70 이상 '포만' 힘 I · 0 '갈증' 최대 체력 -6, 이동 -15%, 재생 멈춤, 햇빛 피해 2배
 *   밤: 힘 I, 야간 투시, 2초마다 체력 1 (피 소모 없음)
 *   신성력 피해 2배 (신부)
 * 숙련 조건: 흡혈량 5,000 + 플레이어 흡혈 3회(같은 사람 하루 1번) + 뱀파이어로 밤 7번
 */
public final class Vampire implements JobType {
    public static final Vampire INSTANCE = new Vampire();
    private Vampire() {}

    public static final String P = "vam.";
    static final String[] RANKS = {"인간", "일반 뱀파이어", "숙련된 뱀파이어", "기둥 속 사내", "완전생물"};

    public static final double SKILLED_DRINK = 5_000;
    public static final int SKILLED_PLAYERS = 3, SKILLED_NIGHTS = 7;

    // ───────── JobType ─────────
    @Override public String id() { return Jobs.VAMPIRE_ID; }
    @Override public String name() { return "뱀파이어"; }
    @Override public String prefix() { return P; }
    @Override public Item icon() { return DuelMod.STONE_MASK; }
    @Override public Formatting color() { return Formatting.DARK_RED; }
    @Override public int maxRank() { return 4; }
    @Override public String rankName(int r) { return RANKS[Math.max(0, Math.min(4, r))]; }
    @Override public boolean selectable() { return false; }
    @Override public String[] summary() {
        return new String[]{"석가면을 쓰고 피를 묻힌 자", "밤의 지배자, 햇빛과 신성력이 약점",
                "숙련 → 기둥 속 사내 → 완전생물", "직업 선택으로는 될 수 없음 (석가면)"};
    }
    @Override public void openDetail(ServerPlayerEntity p) { VampireMenu.Info.open(p); }
    @Override public void openStatus(ServerPlayerEntity p) { VampireMenu.Status.open(p); }
    @Override public JobSkills.Set skills() { return SKILLS; }

    @Override public void onJoin(ServerPlayerEntity p, AlchemyData.Entry e) {
        e.num(P + "rank", 1);
        e.num(P + "blood", 60);
    }

    @Override public void onQuit(ServerPlayerEntity p, AlchemyData.Entry e) {
        clearMods(p);
        Pillar.removeRitual(p.getServer(), p.getUuid(), false);
        AlchemyData d = AlchemyData.get(p.getServer());
        for (String r : Pillar.ROUTES) if (d.global.getString("route." + r).equals(p.getUuidAsString())) d.global.remove("route." + r);
        Thralls.releaseAll(p.getUuid());
        p.removeStatusEffect(StatusEffects.NIGHT_VISION);
        p.getHungerManager().setFoodLevel(20);
        p.extinguish();
    }

    @Override public void adminRank(ServerPlayerEntity p, AlchemyData.Entry e, int r) {
        e.num(P + "rank", r);
        if (r >= 2) {
            e.num(P + "drink", Math.max(e.num(P + "drink"), SKILLED_DRINK));
            e.num(P + "pdrink", Math.max(e.num(P + "pdrink"), SKILLED_PLAYERS));
            e.num(P + "nights", Math.max(e.num(P + "nights"), SKILLED_NIGHTS));
        }
        e.num(P + "blood", 100);
    }
    @Override public void adminPoints(ServerPlayerEntity p, AlchemyData.Entry e, double amount) { addDrink(p, e, amount, false); }
    @Override public String adminInfo(AlchemyData.Entry e) {
        return "흡혈량 " + Alchemy.fmt(e.num(P + "drink")) + " · 플레이어 " + (int) e.num(P + "pdrink") + " · 밤 " + (int) e.num(P + "nights")
                + " · 피 " + (int) e.num(P + "blood");
    }

    public static boolean is(AlchemyData.Entry e) { return Jobs.VAMPIRE_ID.equals(e.job); }
    public static int stage(AlchemyData.Entry e) { return is(e) ? Math.max(1, (int) e.num(P + "rank")) : 0; }

    // ───────── 피 ─────────

    public static double blood(AlchemyData.Entry e) { return e.num(P + "blood"); }
    public static void addBlood(AlchemyData.Entry e, double v) { e.num(P + "blood", Math.max(0, Math.min(100, blood(e) + v))); }
    public static boolean thirsty(AlchemyData.Entry e) { return blood(e) <= 0; }

    /** 흡혈량 누적 + 숙련 승급 확인 */
    static void addDrink(ServerPlayerEntity p, AlchemyData.Entry e, double amount, boolean player) {
        e.addNum(P + "drink", amount);
        if (player) e.addNum(P + "pdrink", 1);
        checkRank(p, e);
        AlchemyData.get(p.getServer()).markDirty();
    }

    static void checkRank(ServerPlayerEntity p, AlchemyData.Entry e) {
        if (stage(e) == 1 && e.num(P + "drink") >= SKILLED_DRINK && e.num(P + "pdrink") >= SKILLED_PLAYERS && e.num(P + "nights") >= SKILLED_NIGHTS) {
            e.num(P + "rank", 2);
            Jobs.announceRank(p, "뱀파이어", RANKS[2], false);
            p.sendMessage(Text.literal("  새 기술: 혈동 · 혈안 광선 · 육아 지배, 하루 한 번 죽음에서 되살아남").formatted(Formatting.DARK_RED), false);
        }
    }

    // ───────── 등록 ─────────

    private static final Identifier THIRST_ID = Identifier.of(DuelMod.MOD_ID, "vampire_thirst");
    private static final Identifier THIRST_SPEED = Identifier.of(DuelMod.MOD_ID, "vampire_thirst_speed");
    private static final Identifier MIGHT_ID = Identifier.of(DuelMod.MOD_ID, "vampire_might");
    private static final Identifier MIGHT_KB = Identifier.of(DuelMod.MOD_ID, "vampire_might_kb");
    static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.0f, 0.02f), 1.0f);

    /** 플레이어 → 마지막으로 햇빛에 탄 시각 (되살아남 판정) */
    private static final Map<UUID, Long> SUN_HURT = new HashMap<>();
    /** 혈동 직후 신성력 피해 반감 */
    static final Map<UUID, Long> HOLY_GUARD = new HashMap<>();
    /** 석가면 의식: 가면을 쓴 채 피해를 입으면 */
    private static final Set<UUID> RITUAL = new HashSet<>();
    private static int clock = 0;

    public static void register() {
        Jobs.registerType(INSTANCE);
        Thralls.register();
        Pillar.register();
        Aja.register();
        Ultimate.register();

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
                    .with(ItemEntry.builder(DuelMod.STONE_MASK)).conditionally(RandomChanceLootCondition.builder(chance)));
        });

        // 석가면을 쓴 채(밤) 피해를 입으면 → 뱀파이어
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayerEntity p) || taken <= 0) return;
            if (!p.getEquippedStack(EquipmentSlot.HEAD).isOf(DuelMod.STONE_MASK)) return;
            AlchemyData.Entry e = Jobs.entry(p);
            if (is(e) || RITUAL.contains(p.getUuid())) return;
            if (!p.getServerWorld().isNight()) {
                p.sendMessage(Text.literal("가면이 꿈틀거리지만… 아무 일도 일어나지 않는다. (밤이어야 한다)").formatted(Formatting.GRAY), true);
                return;
            }
            transform(p);
        });

        // 숙련된 뱀파이어: 하루 한 번 되살아남 (햇빛·신성력으로 죽으면 안 됨)
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return true;
            AlchemyData.Entry e = Jobs.entry(p);
            int s = stage(e);
            if (s != 2) return true; // 완전생물은 Ultimate 가 따로 처리
            Long sun = SUN_HURT.get(p.getUuid());
            if (sun != null && System.currentTimeMillis() - sun < 1500) return true;
            if (source.getAttacker() instanceof ServerPlayerEntity atk && Priest.is(Jobs.entry(atk))) return true;
            if (e.time(P + "revive") == PokemonDoctor.today()) return true;
            e.time(P + "revive", PokemonDoctor.today());
            p.setHealth(4);
            p.extinguish();
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 3, false, false));
            ServerWorld w = p.getServerWorld();
            w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 60, 0.4, 0.8, 0.4, 0);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_VILLAGER_CURE, SoundCategory.PLAYERS, 1.0f, 0.6f);
            p.sendMessage(Text.literal("🩸 찢어진 살이 다시 붙는다…").formatted(Formatting.DARK_RED), false);
            AlchemyData.get(p.getServer()).markDirty();
            return false;
        });

        // 햇빛에 죽으면 기둥 각성 진행도(밤 생존) -5
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return;
            AlchemyData.Entry e = Jobs.entry(p);
            if (!is(e)) return;
            Long sun = SUN_HURT.get(p.getUuid());
            if (sun != null && System.currentTimeMillis() - sun < 1500) e.num(P + "pnights", Math.max(0, e.num(P + "pnights") - 5));
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((o, n, alive) -> { if (is(Jobs.entry(n))) addBlood(Jobs.entry(n), 0); });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> Thralls.releaseAll(h.getPlayer().getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(Vampire::tick);
    }

    // ───────── 석가면 의식 ─────────

    static void transform(ServerPlayerEntity p) {
        RITUAL.add(p.getUuid());
        ServerWorld w = p.getServerWorld();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 6, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, false, false));
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.6f);
        p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal("가면이 피를 마신다").formatted(Formatting.DARK_RED)));
        // 가면 둘레에서 가시가 튀어나와 머리에 박힘
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
            AlchemyData data = AlchemyData.get(p.getServer());
            AlchemyData.Entry e = data.of(p.getUuid());
            CombatMode.exit(p, null);
            if (Jobs.isAlchemist(e)) { e.xp = 0; e.truth = false; }
            JobType old = Jobs.type(e);
            if (old != null) { old.onQuit(p, e); e.clearJobData(old.prefix()); }
            e.job = Jobs.VAMPIRE_ID;
            INSTANCE.onJoin(p, e);
            data.markDirty();
            w.spawnParticles(ParticleTypes.FLASH, p.getX(), p.getEyeY(), p.getZ(), 1, 0, 0, 0, 0);
            w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 120, 0.6, 1, 0.6, 0);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.PLAYERS, 0.8f, 1.6f);
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(Text.literal("인간을 그만두었다").formatted(Formatting.GRAY)));
            p.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.literal("뱀파이어").formatted(Formatting.DARK_RED, Formatting.BOLD)));
            p.sendMessage(Text.literal("🩸 일반 뱀파이어가 되었습니다. 햇빛을 피하고, 피로 살아가십시오. (` 키 전투 모드, /job 상태)").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
            p.getServer().getPlayerManager().broadcast(Text.literal("🩸 어딘가에서 석가면이 깨어났다…").formatted(Formatting.DARK_RED), false);
        });
    }

    // ───────── 매 틱 ─────────

    private static void tick(MinecraftServer server) {
        clock++;
        if (clock % 20 != 0) return;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            AlchemyData.Entry e = Jobs.entry(p);
            int s = stage(e);
            if (s == 0 || p.isSpectator() || p.isCreative()) continue;
            ServerWorld w = p.getServerWorld();
            boolean night = w.isNight() || !w.getDimension().hasSkyLight();
            Pillar.secondTick(p, e, clock);
            if (Pillar.sleeping(p)) continue;

            // 밤 생존 기록 (기둥 각성용 'pnights' 는 숙련 이후에만)
            long tod = w.getTimeOfDay();
            long t = tod % 24000;
            if (t >= 13000 && t < 23000) e.num(P + "curnight", tod / 24000 + 1);
            else if (t < 12000 && e.num(P + "curnight") > 0 && e.num(P + "curnight") != e.num(P + "lastnight")) {
                e.num(P + "lastnight", e.num(P + "curnight"));
                e.addNum(P + "nights", 1);
                if (s == 2) e.addNum(P + "pnights", 1);
                checkRank(p, e);
                p.sendMessage(Text.literal("🌅 밤을 하나 더 넘겼다 (" + (int) e.num(P + "nights") + "번째)").formatted(Formatting.DARK_RED), true);
            }

            // 피 감소 (완전생물 제외): 1분 2, 낮 5
            if (s < 4) addBlood(e, -(night ? 2.0 : 5.0) / 60.0);
            boolean thirst = s < 4 && thirsty(e);

            // 배고픔으로 인한 자연 재생을 막고(배고픔 17 고정) 피로 재생
            if (s < 4) { p.getHungerManager().setFoodLevel(17); p.getHungerManager().setSaturationLevel(0); }
            if (p.getHealth() < p.getMaxHealth() && !thirst && clock % 40 == 0) {
                if (night || s == 4) p.heal(1);
                else if (blood(e) >= 1) { p.heal(1); addBlood(e, -1); }
            }

            // 상시 효과
            if (night || s >= 3) p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 400, 0, true, false));
            if ((night || blood(e) >= 70) && !thirst) p.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 40, s >= 3 ? 1 : 0, true, false));
            setThirst(p, thirst);

            // 햇빛
            boolean sun = sunlit(w, p);
            if (sun && s <= 2) {
                SUN_HURT.put(p.getUuid(), now);
                p.setOnFireFor(2);
                p.damage(p.getDamageSources().magic(), thirst ? 8 : 4);
                w.spawnParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 10, 0.3, 0.6, 0.3, 0.02);
                if (clock % 60 == 0) p.sendMessage(Text.literal("☀ 햇빛이 몸을 태운다! 그늘로!").formatted(Formatting.GOLD), true);
            } else if (s == 3) {
                Pillar.sunTick(p, e, sun);
            }
        }
        if (clock % 1200 == 0) { SUN_HURT.values().removeIf(x -> now - x > 60_000); HOLY_GUARD.values().removeIf(x -> x < now); }
    }

    /** 햇빛 사망 판정용 */
    static void markSun(ServerPlayerEntity p) { SUN_HURT.put(p.getUuid(), System.currentTimeMillis()); }

    /** 낮 + 하늘이 보임 + 비가 안 옴 */
    public static boolean sunlit(ServerWorld w, ServerPlayerEntity p) {
        if (Priest.inHolyLight(w, p.getPos())) return true;
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

    static void clearMods(ServerPlayerEntity p) {
        for (var a : new net.minecraft.registry.entry.RegistryEntry[]{EntityAttributes.GENERIC_MAX_HEALTH, EntityAttributes.GENERIC_MOVEMENT_SPEED,
                EntityAttributes.GENERIC_ATTACK_DAMAGE, EntityAttributes.GENERIC_ATTACK_KNOCKBACK}) {
            @SuppressWarnings("unchecked")
            EntityAttributeInstance inst = p.getAttributeInstance((net.minecraft.registry.entry.RegistryEntry<net.minecraft.entity.attribute.EntityAttribute>) a);
            if (inst == null) continue;
            inst.removeModifier(THIRST_ID);
            inst.removeModifier(THIRST_SPEED);
            inst.removeModifier(MIGHT_ID);
            inst.removeModifier(MIGHT_KB);
        }
        Pillar.clearMods(p);
    }

    /** 혈동 직후엔 신성력 피해가 절반 */
    public static float holyGuard(Entity t) {
        Long until = HOLY_GUARD.get(t.getUuid());
        return until != null && until > System.currentTimeMillis() ? 0.5f : 1f;
    }

    // ───────── 흡혈 ─────────

    /** 흡혈 공통: 대상 종류에 따라 피·흡혈량 */
    static void drink(ServerPlayerEntity p, AlchemyData.Entry e, LivingEntity t) {
        if (t instanceof ServerPlayerEntity tp) {
            String key = P + "pd_" + tp.getUuid();
            boolean count = e.time(key) != PokemonDoctor.today();
            e.time(key, PokemonDoctor.today());
            addBlood(e, 20);
            addDrink(p, e, 300, count);
            if (count) p.sendMessage(Text.literal("🩸 " + tp.getName().getString() + "의 피 (플레이어 흡혈 " + (int) e.num(P + "pdrink") + "회)").formatted(Formatting.DARK_RED), true);
        } else if (t instanceof MerchantEntity) {
            addBlood(e, 15);
            addDrink(p, e, 100, false);
        } else if (t instanceof PokemonEntity pe) {
            int lv = pe.getPokemon().getLevel();
            addBlood(e, 8 + lv / 10.0);
            addDrink(p, e, 20 + lv * 4, false);
            e.jd.putLong(P + "lastmon", System.currentTimeMillis());
            e.jd.putUuid(P + "lastmonid", pe.getUuid());
            Pillar.onDrinkPokemon(p, e, pe);
        } else {
            addBlood(e, 5);
            addDrink(p, e, 10, false);
        }
    }

    // ───────── 전투 기술 (일반·숙련) ─────────

    static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("손끝 흡혈", "흡혈", 1, 0, 4, "근접 대상에 손가락을 꽂아 6 피해 + 흡혈 (스태미나 15)"),
            new JobSkills.Skill("괴력", "괴력", 1, 10, 20, "10초 동안 공격력 +6, 넉백 강화"),
            new JobSkills.Skill("재생", "재생", 1, 20, 15, "체력 8 회복 + 불 끄기"),
            new JobSkills.Skill("피의 하인", "하인", 1, 25, 45, "방금(30초 안) 흡혈한 야생 포켓몬을 60초 하인으로 (최대 2)"),
            new JobSkills.Skill("혈동", "혈동", 2, 30, 25, "붙잡은 상대를 3초 얼려 8 피해 · 그동안 받는 신성력 피해 절반"),
            new JobSkills.Skill("혈안 광선", "광선", 2, 35, 20, "눈에서 고압 체액을 쏴 직선 20칸 관통 14 피해"),
            new JobSkills.Skill("육아 지배", "육아", 2, 40, 60, "주민이나 야생 포켓몬 하나를 60초 하인으로 (플레이어 불가)"),
    };

    static final JobSkills.Set SKILLS = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, AlchemyData.Entry e) {
            int s = stage(e);
            if (s == 3) return Pillar.skills(e);
            if (s == 4) return Ultimate.skills(e);
            return LIST;
        }
        @Override public String costName() { return "피"; }
        @Override public double resource(ServerPlayerEntity p, AlchemyData.Entry e) { return stage(e) >= 3 ? Stamina.get(p) : blood(e); }
        @Override public boolean pay(ServerPlayerEntity p, AlchemyData.Entry e, JobSkills.Skill s) {
            if (stage(e) >= 3) return Stamina.spend(p, s.cost());
            if (blood(e) + 1e-6 < s.cost()) return false;
            addBlood(e, -s.cost());
            return true;
        }
        @Override public String blocked(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
            if (stage(e) <= 2 && thirsty(e) && idx != 0) return "갈증 상태에서는 흡혈만 할 수 있습니다";
            return null;
        }
        @Override public boolean use(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
            int s = stage(e);
            if (s == 3) return Pillar.use(p, e, idx);
            if (s == 4) return Ultimate.use(p, e, idx);
            return switch (idx) {
                case 0 -> fingerDrink(p, e);
                case 1 -> might(p);
                case 2 -> regen(p);
                case 3 -> bloodThrall(p, e);
                case 4 -> bloodFreeze(p);
                case 5 -> eyeBeam(p);
                case 6 -> fleshBud(p);
                default -> false;
            };
        }
    };

    private static boolean fingerDrink(ServerPlayerEntity p, AlchemyData.Entry e) {
        LivingEntity t = AlchemySkills.aimTarget(p, 3.5);
        if (t == null) { JobSkills.bar(p, "손이 닿는 곳(3.5칸)에 대상이 없습니다", Formatting.RED); return false; }
        if (!Stamina.spend(p, 15)) { JobSkills.bar(p, "스태미나 부족 (15)", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        if (Targets.damage(p, t, 6)) {
            drink(p, e, t);
            w.spawnParticles(BLOOD, t.getX(), t.getY() + t.getHeight() * 0.6, t.getZ(), 25, 0.2, 0.3, 0.2, 0);
            // 피가 손으로 빨려 들어오는 줄기
            Vec3d from = t.getPos().add(0, t.getHeight() * 0.6, 0), to = p.getPos().add(0, 1.2, 0);
            for (int i = 0; i <= 10; i++) {
                Vec3d q = from.lerp(to, i / 10.0);
                Tasks.later(i / 2, () -> w.spawnParticles(BLOOD, q.x, q.y, q.z, 2, 0.02, 0.02, 0.02, 0));
            }
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, 1.0f, 0.6f);
            w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_SWEET_BERRY_BUSH, SoundCategory.PLAYERS, 1.0f, 0.8f);
        }
        return true;
    }

    private static boolean might(ServerPlayerEntity p) {
        EntityAttributeInstance atk = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        EntityAttributeInstance kb = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_KNOCKBACK);
        if (atk == null) return false;
        atk.removeModifier(MIGHT_ID);
        atk.addTemporaryModifier(new EntityAttributeModifier(MIGHT_ID, 6, EntityAttributeModifier.Operation.ADD_VALUE));
        if (kb != null) { kb.removeModifier(MIGHT_KB); kb.addTemporaryModifier(new EntityAttributeModifier(MIGHT_KB, 1.5, EntityAttributeModifier.Operation.ADD_VALUE)); }
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.6, 0.4, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.6f, 1.4f);
        Tasks.later(200, () -> {
            EntityAttributeInstance a = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            if (a != null) a.removeModifier(MIGHT_ID);
            EntityAttributeInstance k = p.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_KNOCKBACK);
            if (k != null) k.removeModifier(MIGHT_KB);
        });
        return true;
    }

    private static boolean regen(ServerPlayerEntity p) {
        p.heal(8);
        p.extinguish();
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(BLOOD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.4, 0.7, 0.4, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_SLIME_BLOCK_PLACE, SoundCategory.PLAYERS, 1.0f, 0.5f);
        return true;
    }

    private static boolean bloodThrall(ServerPlayerEntity p, AlchemyData.Entry e) {
        long last = e.jd.getLong(P + "lastmon");
        if (!e.jd.containsUuid(P + "lastmonid") || System.currentTimeMillis() - last > 30_000) {
            JobSkills.bar(p, "30초 안에 흡혈한 야생 포켓몬이 없습니다", Formatting.RED);
            return false;
        }
        Entity t = p.getServerWorld().getEntity(e.jd.getUuid(P + "lastmonid"));
        if (!(t instanceof PokemonEntity pe) || !pe.isAlive() || pe.getOwnerUuid() != null || pe.squaredDistanceTo(p) > 16 * 16) {
            JobSkills.bar(p, "흡혈한 포켓몬이 근처에 없습니다", Formatting.RED);
            return false;
        }
        return Thralls.bind(p, pe, 60, 5, 2, "피의 하인");
    }

    private static boolean bloodFreeze(ServerPlayerEntity p) {
        LivingEntity t = AlchemySkills.aimTarget(p, 4);
        if (t == null) { JobSkills.bar(p, "붙잡을 대상이 없습니다 (4칸)", Formatting.RED); return false; }
        ServerWorld w = p.getServerWorld();
        Targets.damage(p, t, 8);
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 9, false, false));
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 60, 128, false, false)); // 점프 불가
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 60, 3, false, false));
        t.setFrozenTicks(Math.max(t.getFrozenTicks(), 200));
        HOLY_GUARD.put(p.getUuid(), System.currentTimeMillis() + 3000);
        AlchemySkills.display(w, Blocks.BLUE_ICE.getDefaultState(), t.getX(), t.getY(), t.getZ(), t.getWidth() + 0.4f, t.getHeight() + 0.2f, t.getWidth() + 0.4f, 0, 60, 2);
        for (int i = 0; i < 60; i += 10) Tasks.later(i, () -> w.spawnParticles(ParticleTypes.SNOWFLAKE, t.getX(), t.getY() + 1, t.getZ(), 10, 0.4, 0.6, 0.4, 0.01));
        w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.0f, 0.5f);
        w.playSound(null, t.getBlockPos(), SoundEvents.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.PLAYERS, 1.0f, 0.8f);
        return true;
    }

    private static boolean eyeBeam(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
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
                    Targets.damage(p, t, 14);
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
        return true;
    }

    private static boolean fleshBud(ServerPlayerEntity p) {
        LivingEntity t = AlchemySkills.aimTarget(p, 8);
        if (t == null) {
            // 주민은 canHit 대상이 아닐 수도 있어서 따로 찾음
            for (Entity x : p.getServerWorld().getOtherEntities(p, p.getBoundingBox().expand(8), x -> x instanceof MerchantEntity)) {
                Vec3d to = x.getPos().add(0, 1, 0).subtract(p.getEyePos());
                if (to.normalize().dotProduct(p.getRotationVector()) > 0.95 && p.canSee(x)) { t = (LivingEntity) x; break; }
            }
        }
        if (t == null || t instanceof ServerPlayerEntity || (t instanceof PokemonEntity pe && pe.getOwnerUuid() != null)
                || !(t instanceof MerchantEntity || t instanceof PokemonEntity)) {
            JobSkills.bar(p, "주민이나 야생 포켓몬을 바라보세요 (8칸)", Formatting.RED);
            return false;
        }
        ServerWorld w = p.getServerWorld();
        Vec3d from = p.getPos().add(0, 1.2, 0), to = t.getPos().add(0, t.getHeight() * 0.8, 0);
        for (int i = 0; i <= 8; i++) {
            Vec3d q = from.lerp(to, i / 8.0);
            Tasks.later(i, () -> w.spawnParticles(BLOOD, q.x, q.y, q.z, 3, 0.03, 0.03, 0.03, 0));
        }
        return Thralls.bind(p, t, 60, t instanceof MerchantEntity ? 3 : 6, 2, "육아에 지배당함");
    }

    static Box box(Vec3d c, double r) { return new Box(c, c).expand(r); }
}
