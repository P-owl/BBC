package bus.muhyeon.duel;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 신부. 고스트 포켓몬을 잡아 명성을 쌓고, 신성력으로 뱀파이어에게 2배 피해를 준다.
 *   1 수사   0       성수 제작 · 성스러운 일격 · 성수 투척
 *   2 신부   300     축복 · 밤에 근처 고스트 포켓몬 감지
 *   3 사제   1,500   퇴마 · 십자가 결계 · 고스트 포획률 +20% · 내 고스트 포켓몬 경험치 +30%
 *   4 주교   5,000   빛의 십자가 · 뱀파이어 감지(24칸) · 하루 1번 근처 아군 죽음을 막음
 *   5 성자   15,000  성자의 기적 · 정화 의식(뱀파이어 → 인간) · 에이자의 적석 보관
 * 신성력: 최대 50 + 등급×30, 초당 1 회복 · 웅크리고 가만히 있으면(기도) 초당 5 · 고스트 포켓몬을 잡으면 +10
 */
public final class Priest implements JobType {
    public static final Priest INSTANCE = new Priest();
    private Priest() {}

    public static final String P = "pri.";
    static final String[] RANKS = {"무직", "수사", "신부", "사제", "주교", "성자"};
    static final double[] NEED = {0, 0, 300, 1_500, 5_000, 15_000};

    // ───────── JobType ─────────
    @Override public String id() { return Jobs.PRIEST_ID; }
    @Override public String name() { return "신부"; }
    @Override public String prefix() { return P; }
    @Override public Item icon() { return Items.TOTEM_OF_UNDYING; }
    @Override public Formatting color() { return Formatting.YELLOW; }
    @Override public int maxRank() { return 5; }
    @Override public String rankName(int r) { return RANKS[Math.max(0, Math.min(5, r))]; }
    @Override public String[] summary() {
        return new String[]{"고스트 포켓몬을 거두어 명성을 쌓는 성직자",
                "신성력이 담긴 공격은 뱀파이어에게 2배",
                "정점은 뱀파이어를 인간으로 되돌리는 성자"};
    }
    @Override public void openDetail(ServerPlayerEntity p) { PriestMenu.Detail.open(p); }
    @Override public void openStatus(ServerPlayerEntity p) { PriestMenu.Status.open(p); }
    @Override public JobSkills.Set skills() { return SKILLS; }
    @Override public void onJoin(ServerPlayerEntity p, AlchemyData.Entry e) { e.num(P + "rank", 1); HOLY.put(p.getUuid(), maxHoly(e)); }
    @Override public void onQuit(ServerPlayerEntity p, AlchemyData.Entry e) { HOLY.remove(p.getUuid()); }
    @Override public void adminRank(ServerPlayerEntity p, AlchemyData.Entry e, int r) {
        e.num(P + "rank", r);
        e.num(P + "fame", Math.max(e.num(P + "fame"), NEED[r]));
        HOLY.put(p.getUuid(), maxHoly(e));
    }
    @Override public void adminPoints(ServerPlayerEntity p, AlchemyData.Entry e, double amount) { addFame(p, e, amount, null); }
    @Override public String adminInfo(AlchemyData.Entry e) { return "명성 " + Alchemy.fmt(e.num(P + "fame")); }

    public static boolean is(AlchemyData.Entry e) { return Jobs.PRIEST_ID.equals(e.job); }
    public static int rank(AlchemyData.Entry e) { return is(e) ? (int) e.num(P + "rank") : 0; }

    public static void addFame(ServerPlayerEntity p, AlchemyData.Entry e, double amount, String why) {
        if (!is(e) || amount == 0) return;
        e.addNum(P + "fame", amount);
        if (why != null && amount > 0) p.sendMessage(Text.literal("✝ 명성 +" + (int) amount + " (" + why + ")").formatted(Formatting.YELLOW), true);
        int r = rank(e);
        while (r < 5 && e.num(P + "fame") >= NEED[r + 1]) {
            r++;
            e.num(P + "rank", r);
            Jobs.announceRank(p, "신부", RANKS[r], r >= 4);
        }
        AlchemyData.get(p.getServer()).markDirty();
    }

    // ───────── 신성력 ─────────

    private static final Map<UUID, Double> HOLY = new HashMap<>();

    public static double maxHoly(AlchemyData.Entry e) { return 50 + rank(e) * 30; }
    public static double holy(ServerPlayerEntity p) { return HOLY.computeIfAbsent(p.getUuid(), k -> maxHoly(Jobs.entry(p))); }
    public static void addHoly(ServerPlayerEntity p, double v) {
        double m = maxHoly(Jobs.entry(p));
        HOLY.put(p.getUuid(), Math.max(0, Math.min(m, holy(p) + v)));
    }

    // ───────── 뱀파이어 판정 (뱀파이어 직업과 공유) ─────────

    /** 뱀파이어 단계: 0 = 인간, 1 일반, 2 숙련, 3 기둥 속 사내, 4 완전생물 */
    public static int vampStage(Entity t) {
        if (!(t instanceof ServerPlayerEntity sp)) return 0;
        AlchemyData.Entry e = Jobs.entry(sp);
        return Jobs.VAMPIRE_ID.equals(e.job) ? Math.max(1, (int) e.num("vam.rank")) : 0;
    }

    /** 신성력 피해 배율: 뱀파이어·기둥 속 사내 2배, 완전생물은 면역이라 그대로 */
    public static float holyMult(Entity t) {
        int s = vampStage(t);
        return s >= 1 && s <= 3 ? 2f * Vampire.holyGuard(t) : 1f;
    }

    /** 신성력 공격 (뱀파이어에게 2배 + 지지는 연기) */
    public static boolean holyHit(ServerPlayerEntity caster, Entity t, float base) {
        float m = holyMult(t);
        t.timeUntilRegen = 0;
        boolean ok = Targets.damage(caster, t, base * m);
        if (ok && m > 1 && t.getWorld() instanceof ServerWorld w) {
            w.spawnParticles(ParticleTypes.LARGE_SMOKE, t.getX(), t.getY() + 1, t.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
            w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.PLAYERS, 1.0f, 1.4f);
            if (t instanceof ServerPlayerEntity v) v.sendMessage(Text.literal("✝ 신성한 힘이 살을 태운다!").formatted(Formatting.GOLD), true);
        }
        return ok;
    }

    // ───────── 성자의 기적 (인공 햇빛) ─────────

    record Light(ServerWorld world, Vec3d c, double r, long until, UUID caster) {}
    static final List<Light> LIGHTS = new ArrayList<>();

    /** 이 위치가 성자의 기적 빛 안인가 (뱀파이어 햇빛 판정에 사용) */
    public static boolean inHolyLight(ServerWorld w, Vec3d pos) {
        long now = System.currentTimeMillis();
        for (Light l : LIGHTS) if (l.world == w && l.until > now && l.c.squaredDistanceTo(pos) <= l.r * l.r) return true;
        return false;
    }

    // ───────── 상태 ─────────

    private static final Map<UUID, Long> SMITE = new HashMap<>();
    /** 퇴마된 야생 포켓몬 → 끝나는 시각 */
    static final Map<UUID, Long> EXORCISED = new HashMap<>();
    private static final Map<UUID, Long> GHOST_PING = new HashMap<>();
    private static int clock = 0;

    // ───────── 등록 ─────────

    public static void register() {
        Jobs.registerType(INSTANCE);

        // 고스트 포켓몬 포획 → 명성 + 신성력
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, ev -> {
            ServerPlayerEntity p = ev.getPlayer();
            AlchemyData.Entry e = Jobs.entry(p);
            Pokemon pk = ev.getPokemon();
            if (!is(e) || !ghost(pk)) return kotlin.Unit.INSTANCE;
            double f = pk.getSpecies().getEvolutions().isEmpty() ? 30 : 10;
            if (pk.getShiny()) f *= 5;
            if (pk.getSpecies().getName().equalsIgnoreCase("giratina")) f += 500;
            addHoly(p, 10);
            addFame(p, e, f, "고스트 포켓몬을 거둠");
            return kotlin.Unit.INSTANCE;
        });

        // 야생 고스트 포켓몬과의 배틀 승리 → 명성 +3 (하루 30번)
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, ev -> {
            if (ev.getWasWildCapture()) return kotlin.Unit.INSTANCE;
            boolean wildGhost = false;
            for (var a : ev.getLosers()) if (a instanceof PokemonBattleActor pa && ghost(pa.getPokemon().getOriginalPokemon())) wildGhost = true;
            if (!wildGhost) return kotlin.Unit.INSTANCE;
            for (var a : ev.getWinners()) for (UUID id : a.getPlayerUUIDs()) {
                ServerPlayerEntity p = ev.getBattle().getPlayers().stream().filter(x -> x.getUuid().equals(id)).findFirst().orElse(null);
                if (p == null) continue;
                AlchemyData.Entry e = Jobs.entry(p);
                if (!is(e) || !daily(e, "ghostwin", 30)) continue;
                addFame(p, e, 3, "고스트 포켓몬을 잠재움");
            }
            return kotlin.Unit.INSTANCE;
        });

        // 사제: 고스트 포획률 +20%, 퇴마된 포켓몬 ×2
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.NORMAL, ev -> {
            Long ex = EXORCISED.get(ev.getPokemonEntity().getUuid());
            if (ex != null && ex > System.currentTimeMillis()) ev.setCatchRate(ev.getCatchRate() * 2f);
            if (ev.getThrower() instanceof ServerPlayerEntity p && rank(Jobs.entry(p)) >= 3 && ghost(ev.getPokemonEntity().getPokemon()))
                ev.setCatchRate(ev.getCatchRate() * 1.2f);
            return kotlin.Unit.INSTANCE;
        });

        // 사제: 내 고스트 포켓몬 경험치 +30%
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(Priority.NORMAL, ev -> {
            ServerPlayerEntity p = ev.getPokemon().getOwnerPlayer();
            if (p != null && rank(Jobs.entry(p)) >= 3 && ghost(ev.getPokemon())) ev.setExperience((int) Math.ceil(ev.getExperience() * 1.3));
            return kotlin.Unit.INSTANCE;
        });

        // 신부: 밤에 근처 야생 고스트 포켓몬이 나타나면 알림
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof PokemonEntity pe) || pe.getOwnerUuid() != null || !world.isNight()) return;
            if (!ghost(pe.getPokemon())) return;
            long now = System.currentTimeMillis();
            for (ServerPlayerEntity p : world.getPlayers()) {
                if (rank(Jobs.entry(p)) < 2 || p.squaredDistanceTo(pe) > 64 * 64) continue;
                Long last = GHOST_PING.get(p.getUuid());
                if (last != null && now - last < 30_000) continue;
                GHOST_PING.put(p.getUuid(), now);
                double dx = pe.getX() - p.getX(), dz = pe.getZ() - p.getZ();
                p.sendMessage(Text.literal("✝ 떠도는 영혼의 기척: " + PokemonDoctor.dir(dx, dz) + " " + (int) Math.sqrt(dx * dx + dz * dz) + "블록 — ")
                        .formatted(Formatting.DARK_PURPLE).append(pe.getPokemon().getSpecies().getTranslatedName()), false);
            }
        });

        // 성스러운 일격: 다음 근접 공격에 신성력 피해 추가
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity atk) || source.getSource() != atk) return;
            Long until = SMITE.get(atk.getUuid());
            if (until == null) return;
            SMITE.remove(atk.getUuid());
            if (until < System.currentTimeMillis()) return;
            holyHit(atk, entity, 8);
            ServerWorld w = atk.getServerWorld();
            w.spawnParticles(ParticleTypes.END_ROD, entity.getX(), entity.getY() + 1, entity.getZ(), 20, 0.3, 0.6, 0.3, 0.1);
            w.playSound(null, entity.getBlockPos(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        });

        // 뱀파이어를 쓰러뜨리면 명성
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            int s = vampStage(entity);
            if (s == 0 || !(source.getAttacker() instanceof ServerPlayerEntity p)) return;
            AlchemyData.Entry e = Jobs.entry(p);
            if (!is(e)) return;
            double[] fame = {0, 50, 150, 500, 3000};
            addFame(p, e, fame[Math.min(4, s)], "뱀파이어 퇴치");
        });

        // 주교: 하루 한 번, 16칸 안 아군(자신 포함)의 죽음을 막음
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity dying) || vampStage(dying) > 0) return true;
            for (ServerPlayerEntity pr : dying.getServerWorld().getPlayers()) {
                AlchemyData.Entry e = Jobs.entry(pr);
                if (rank(e) < 4 || pr.squaredDistanceTo(dying) > 16 * 16) continue;
                if (PokemonDoctor.today() == e.time(P + "revive")) continue;
                e.time(P + "revive", PokemonDoctor.today());
                AlchemyData.get(pr.getServer()).markDirty();
                dying.setHealth(8);
                dying.extinguish();
                dying.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 1));
                dying.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 2));
                ServerWorld w = dying.getServerWorld();
                w.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, dying.getX(), dying.getY() + 1, dying.getZ(), 60, 0.5, 1, 0.5, 0.5);
                w.playSound(null, dying.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1.0f, 1.0f);
                dying.sendMessage(Text.literal("✝ " + pr.getName().getString() + " 주교의 기도가 죽음을 물리쳤다").formatted(Formatting.GOLD), false);
                if (pr != dying) pr.sendMessage(Text.literal("✝ 오늘의 기적을 " + dying.getName().getString() + "님에게 썼습니다").formatted(Formatting.YELLOW), false);
                return false;
            }
            return true;
        });

        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> { HOLY.remove(h.getPlayer().getUuid()); SMITE.remove(h.getPlayer().getUuid()); });
        ServerTickEvents.END_SERVER_TICK.register(Priest::tick);
    }

    static boolean ghost(Pokemon pk) {
        if (pk == null) return false;
        for (ElementalType t : pk.getTypes()) if (t.getName().equalsIgnoreCase("ghost")) return true;
        return false;
    }

    /** 하루 횟수 제한 (true = 아직 가능, 1 차감) */
    static boolean daily(AlchemyData.Entry e, String key, int max) {
        long d = PokemonDoctor.today();
        if (e.time(P + key + "_d") != d) { e.time(P + key + "_d", d); e.num(P + key, 0); }
        if (e.num(P + key) >= max) return false;
        e.addNum(P + key, 1);
        return true;
    }

    private static final DustParticleEffect GOLD = new DustParticleEffect(new Vector3f(1.0f, 0.85f, 0.3f), 1.2f);

    private static void tick(MinecraftServer server) {
        clock++;
        long now = System.currentTimeMillis();
        if (clock % 10 == 0) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                AlchemyData.Entry e = Jobs.entry(p);
                if (!is(e)) continue;
                boolean pray = p.isSneaking() && p.isOnGround() && p.getVelocity().horizontalLengthSquared() < 1e-4;
                addHoly(p, pray ? 2.5 : 0.5);
                if (pray && clock % 20 == 0) p.getServerWorld().spawnParticles(GOLD, p.getX(), p.getY() + 2.2, p.getZ(), 3, 0.2, 0.1, 0.2, 0);
                // 주교: 24칸 안 뱀파이어 감지 (나에게만 보이는 붉은 표식)
                if (rank(e) >= 4 && clock % 20 == 0) {
                    for (ServerPlayerEntity v : p.getServerWorld().getPlayers()) {
                        int s = vampStage(v);
                        if (s == 0 || s >= 4 || v == p || v.squaredDistanceTo(p) > 24 * 24) continue;
                        p.getServerWorld().spawnParticles(p, new DustParticleEffect(new Vector3f(0.8f, 0.05f, 0.05f), 1.5f), true,
                                v.getX(), v.getY() + v.getHeight() + 0.6, v.getZ(), 6, 0.15, 0.15, 0.15, 0);
                    }
                }
            }
        }
        // 성자의 기적: 빛 안의 뱀파이어는 타고, 아군은 회복
        if (clock % 20 == 0 && !LIGHTS.isEmpty()) {
            LIGHTS.removeIf(l -> l.until < now);
            for (Light l : LIGHTS) {
                ServerPlayerEntity caster = server.getPlayerManager().getPlayer(l.caster);
                for (ServerPlayerEntity v : l.world.getPlayers()) {
                    if (v.squaredDistanceTo(l.c) > l.r * l.r) continue;
                    int s = vampStage(v);
                    if (s >= 1 && s <= 3) {
                        v.setOnFireFor(3);
                        if (caster != null) holyHit(caster, v, 4);
                        else v.damage(v.getDamageSources().magic(), 8);
                    } else if (s == 0) {
                        v.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 40, 1, false, false));
                    }
                }
            }
        }
        if (clock % 200 == 0) EXORCISED.values().removeIf(t -> t < now);
    }

    // ───────── 전투 기술 ─────────

    static final JobSkills.Skill[] LIST = {
            new JobSkills.Skill("성스러운 일격", "일격", 1, 10, 6, "10초 안의 다음 근접 공격에 신성 피해 +8"),
            new JobSkills.Skill("성수 투척", "성수", 1, 0, 4, "성수 1병을 던져 4칸 범위 6 피해 (성수 필요)"),
            new JobSkills.Skill("축복", "축복", 2, 20, 10, "바라보는 아군(없으면 자신) 체력 6 회복 + 재생 10초 · 다른 사람 축복 시 명성"),
            new JobSkills.Skill("퇴마", "퇴마", 3, 25, 20, "야생 고스트 포켓몬 하나를 60초 동안 정화 → 포획률 ×2"),
            new JobSkills.Skill("십자가 결계", "결계", 3, 35, 25, "발밑 5×5 성역 10초 — 뱀파이어는 밀려나고 초당 3 피해"),
            new JobSkills.Skill("빛의 십자가", "십자가", 4, 50, 30, "하늘에서 빛기둥 3개, 각 12 피해 · 기둥 속 사내는 2초 석화"),
            new JobSkills.Skill("성자의 기적", "기적", 5, 100, 120, "신성력을 모두 써서 반경 20칸을 10초간 한낮으로 — 뱀파이어는 햇빛에 탐, 아군 재생"),
            new JobSkills.Skill("정화 의식", "정화", 5, 100, 60, "6칸 안 뱀파이어(일반·숙련)를 5초 의식으로 인간으로 되돌림"),
    };

    static final JobSkills.Set SKILLS = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, AlchemyData.Entry e) { return LIST; }
        @Override public String costName() { return "신성력"; }
        @Override public double resource(ServerPlayerEntity p, AlchemyData.Entry e) { return holy(p); }
        @Override public boolean pay(ServerPlayerEntity p, AlchemyData.Entry e, JobSkills.Skill s) {
            if (s == LIST[6]) { HOLY.put(p.getUuid(), 0.0); return true; }
            if (holy(p) + 1e-6 < s.cost()) return false;
            addHoly(p, -s.cost());
            return true;
        }
        @Override public String blocked(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
            if (idx == 1 && Jobs.count(p, DuelMod.HOLY_WATER) <= 0) return "성수가 없습니다 (/job 창에서 만들기)";
            return null;
        }
        @Override public boolean use(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
            return switch (idx) {
                case 0 -> smite(p);
                case 1 -> holyWater(p);
                case 2 -> bless(p, e);
                case 3 -> exorcise(p);
                case 4 -> sanctuary(p);
                case 5 -> lightCross(p);
                case 6 -> miracle(p);
                case 7 -> purifyRitual(p);
                default -> false;
            };
        }
    };

    private static boolean smite(ServerPlayerEntity p) {
        SMITE.put(p.getUuid(), System.currentTimeMillis() + 10_000);
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(GOLD, p.getX(), p.getY() + 1.2, p.getZ(), 20, 0.4, 0.5, 0.4, 0);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 1.6f);
        return true;
    }

    private static boolean holyWater(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        HitResult hit = p.raycast(12, 0, false);
        Vec3d at = hit.getType() == HitResult.Type.MISS ? p.getEyePos().add(p.getRotationVector().multiply(10)) : hit.getPos();
        LivingEntity aim = AlchemySkills.aimTarget(p, 12);
        if (aim != null) at = aim.getPos().add(0, 0.5, 0);
        Jobs.take(p, DuelMod.HOLY_WATER, 1);
        Vec3d from = p.getEyePos(), to = at;
        // 포물선 궤적
        for (int i = 1; i <= 8; i++) {
            double t = i / 8.0;
            Vec3d q = from.lerp(to, t).add(0, Math.sin(t * Math.PI) * 1.2, 0);
            Tasks.later(i, () -> w.spawnParticles(ParticleTypes.SPLASH, q.x, q.y, q.z, 3, 0.05, 0.05, 0.05, 0));
        }
        final Vec3d c = to;
        Tasks.later(9, () -> {
            w.spawnParticles(ParticleTypes.SPLASH, c.x, c.y + 0.3, c.z, 80, 1.5, 0.4, 1.5, 0.3);
            w.spawnParticles(GOLD, c.x, c.y + 0.5, c.z, 30, 1.5, 0.5, 1.5, 0);
            w.spawnParticles(ParticleTypes.END_ROD, c.x, c.y + 0.5, c.z, 15, 1.2, 0.5, 1.2, 0.05);
            w.playSound(null, BlockPos.ofFloored(c), SoundEvents.ENTITY_SPLASH_POTION_BREAK, SoundCategory.PLAYERS, 1.0f, 1.0f);
            for (Entity t : w.getOtherEntities(p, new Box(c, c).expand(4), x -> Targets.canHit(p, x))) {
                if (t.squaredDistanceTo(c) > 16) continue;
                holyHit(p, t, 6);
                if (vampStage(t) > 0 && t instanceof LivingEntity le) { le.setOnFireFor(2); }
            }
        });
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_SPLASH_POTION_THROW, SoundCategory.PLAYERS, 1.0f, 1.0f);
        return true;
    }

    /** 축복 대상: 바라보는 아군 플레이어·주민·길들인 동물·내 포켓몬 (없으면 자신) */
    private static LivingEntity allyAim(ServerPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), look = p.getRotationVector();
        LivingEntity best = null;
        double bestD = range;
        for (Entity t : p.getServerWorld().getOtherEntities(p, p.getBoundingBox().stretch(look.multiply(range)).expand(2),
                x -> x instanceof ServerPlayerEntity || x instanceof MerchantEntity || x instanceof TameableEntity
                        || (x instanceof PokemonEntity pe && p.getUuid().equals(pe.getOwnerUuid())))) {
            Vec3d to = t.getPos().add(0, t.getHeight() / 2, 0).subtract(eye);
            double d = to.length();
            if (d > range || to.normalize().dotProduct(look) < 0.95 || !p.canSee(t)) continue;
            if (d < bestD) { bestD = d; best = (LivingEntity) t; }
        }
        return best;
    }

    private static boolean bless(ServerPlayerEntity p, AlchemyData.Entry e) {
        LivingEntity t = allyAim(p, 12);
        if (t == null) t = p;
        if (vampStage(t) > 0) { JobSkills.bar(p, "뱀파이어는 축복을 받을 수 없습니다", Formatting.RED); return false; }
        t.heal(6);
        t.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 0));
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(GOLD, t.getX(), t.getY() + t.getHeight() + 0.3, t.getZ(), 25, 0.4, 0.2, 0.4, 0);
        w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, t.getX(), t.getY() + 1, t.getZ(), 10, 0.4, 0.5, 0.4, 0);
        w.playSound(null, t.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        if (t instanceof ServerPlayerEntity sp && sp != p) {
            sp.sendMessage(Text.literal("✝ " + p.getName().getString() + "님의 축복을 받았습니다").formatted(Formatting.YELLOW), true);
            if (daily(e, "bless", 5)) addFame(p, e, 5, "축복");
        } else if (t instanceof MerchantEntity) {
            if (daily(e, "blessv", 20)) addFame(p, e, 1, "주민 축복");
        }
        return true;
    }

    private static boolean exorcise(ServerPlayerEntity p) {
        LivingEntity t = AlchemySkills.aimTarget(p, 20);
        if (!(t instanceof PokemonEntity pe) || pe.getOwnerUuid() != null || !ghost(pe.getPokemon())) {
            JobSkills.bar(p, "바라보는 야생 고스트 포켓몬이 없습니다", Formatting.RED);
            return false;
        }
        EXORCISED.put(pe.getUuid(), System.currentTimeMillis() + 60_000);
        pe.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 1200, 0, false, false));
        pe.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 1200, 2, false, false));
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 20; i += 2) Tasks.later(i, () -> w.spawnParticles(GOLD, pe.getX(), pe.getY() + pe.getHeight() / 2, pe.getZ(), 10, 0.5, 0.6, 0.5, 0));
        w.playSound(null, pe.getBlockPos(), SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1.2f, 1.2f);
        p.sendMessage(Text.literal("✝ 퇴마: ").formatted(Formatting.YELLOW).append(pe.getPokemon().getSpecies().getTranslatedName())
                .append(Text.literal(" 의 영혼이 잠잠해졌다 (60초간 포획률 ×2)").formatted(Formatting.YELLOW)), false);
        return true;
    }

    private static boolean sanctuary(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        double y = Math.floor(c.y);
        // 바닥의 금빛 십자가 (블록 디스플레이)
        AlchemySkills.display(w, Blocks.GOLD_BLOCK.getDefaultState(), c.x, y - 0.95, c.z, 5f, 1f, 0.8f, 0, 200, 4);
        AlchemySkills.display(w, Blocks.GOLD_BLOCK.getDefaultState(), c.x, y - 0.95, c.z, 0.8f, 1f, 5f, 0, 200, 4);
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.5f, 0.8f);
        for (int t = 0; t < 200; t += 5) {
            final int tick = t;
            Tasks.later(t, () -> {
                for (int i = 0; i < 16; i++) {
                    double a = Math.PI * 2 * i / 16;
                    w.spawnParticles(GOLD, c.x + Math.cos(a) * 2.6, y + 0.2 + (tick % 20) / 10.0, c.z + Math.sin(a) * 2.6, 1, 0, 0, 0, 0);
                }
                for (ServerPlayerEntity v : w.getPlayers()) {
                    int s = vampStage(v);
                    if (s == 0 || s >= 4) continue;
                    double dx = v.getX() - c.x, dz = v.getZ() - c.z;
                    if (Math.abs(dx) > 2.6 || Math.abs(dz) > 2.6 || Math.abs(v.getY() - y) > 3) continue;
                    holyHit(p, v, 0.75f / holyMult(v)); // 0.25초마다 0.75 = 초당 3 (배율 포함 최종값)
                    double len = Math.max(0.3, Math.sqrt(dx * dx + dz * dz));
                    v.setVelocity(dx / len * 0.9, 0.3, dz / len * 0.9);
                    v.velocityModified = true;
                }
            });
        }
        return true;
    }

    static boolean lightCross(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        LivingEntity aim = AlchemySkills.aimTarget(p, 24);
        Vec3d c;
        if (aim != null) c = aim.getPos();
        else {
            HitResult hit = p.raycast(20, 0, false);
            c = hit.getType() == HitResult.Type.MISS ? p.getPos().add(p.getRotationVector().multiply(12)) : hit.getPos();
        }
        Vec3d side = new Vec3d(-p.getRotationVector().z, 0, p.getRotationVector().x).normalize().multiply(2.2);
        Vec3d[] pts = {c, c.add(side), c.subtract(side)};
        for (int i = 0; i < 3; i++) {
            Vec3d at = pts[i];
            Tasks.later(i * 8, () -> pillar(p, w, at));
        }
        return true;
    }

    private static void pillar(ServerPlayerEntity p, ServerWorld w, Vec3d at) {
        for (int k = 0; k < 6; k++) {
            final int kk = k;
            Tasks.later(k, () -> {
                double top = at.y + 14 - kk * 2.3;
                for (double y = top; y > at.y; y -= 0.5)
                    w.spawnParticles(ParticleTypes.END_ROD, at.x, y, at.z, 1, 0.08, 0, 0.08, 0);
            });
        }
        AlchemySkills.display(w, Blocks.YELLOW_STAINED_GLASS.getDefaultState(), at.x, at.y, at.z, 0.9f, 12f, 0.9f, 4, 16, 2);
        AlchemySkills.display(w, Blocks.YELLOW_STAINED_GLASS.getDefaultState(), at.x, at.y + 8, at.z, 3.2f, 0.8f, 0.9f, 4, 16, 2);
        Tasks.later(6, () -> {
            w.spawnParticles(GOLD, at.x, at.y + 0.3, at.z, 50, 1.2, 0.3, 1.2, 0);
            w.spawnParticles(ParticleTypes.FLASH, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
            w.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 1.0f, 1.6f);
            w.playSound(null, BlockPos.ofFloored(at), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 0.6f);
            for (Entity t : w.getOtherEntities(p, new Box(at, at).expand(2.2, 3, 2.2), x -> Targets.canHit(p, x))) {
                holyHit(p, t, 12);
                if (vampStage(t) == 3 && t instanceof LivingEntity le) {
                    le.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 9, false, false));
                    le.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 40, 9, false, false));
                    w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.STONE.getDefaultState()),
                            t.getX(), t.getY() + 1, t.getZ(), 30, 0.3, 0.8, 0.3, 0.1);
                    if (t instanceof ServerPlayerEntity v) v.sendMessage(Text.literal("빛에 몸이 굳는다!").formatted(Formatting.GRAY), true);
                }
            }
            HIT_BY_CROSS.forEach(h -> h.accept(w, at));
        });
    }

    /** 빛의 십자가가 떨어진 자리 (기둥 각성 방해 등 다른 직업이 구독) */
    public static final List<java.util.function.BiConsumer<ServerWorld, Vec3d>> HIT_BY_CROSS = new ArrayList<>();

    private static boolean miracle(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        Vec3d c = p.getPos();
        LIGHTS.add(new Light(w, c, 20, System.currentTimeMillis() + 10_000, p.getUuid()));
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 2.0f, 1.2f);
        w.playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.5f, 0.8f);
        for (int t = 0; t < 200; t += 4) {
            Tasks.later(t, () -> {
                for (int i = 0; i < 24; i++) {
                    double a = Math.random() * Math.PI * 2, r = Math.sqrt(Math.random()) * 20;
                    w.spawnParticles(ParticleTypes.END_ROD, c.x + Math.cos(a) * r, c.y + 6 + Math.random() * 6, c.z + Math.sin(a) * r, 1, 0, -0.4, 0, 0.2);
                }
                for (int i = 0; i < 40; i++) {
                    double a = Math.PI * 2 * i / 40;
                    w.spawnParticles(GOLD, c.x + Math.cos(a) * 20, c.y + 0.5, c.z + Math.sin(a) * 20, 1, 0, 0, 0, 0);
                }
            });
        }
        p.getServer().getPlayerManager().broadcast(Text.literal("✝ " + p.getName().getString() + " 성자의 기적 — 밤이 물러갑니다").formatted(Formatting.GOLD), false);
        return true;
    }

    private static boolean purifyRitual(ServerPlayerEntity p) {
        ServerPlayerEntity v = null;
        double best = 6 * 6;
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
            if (o == p || vampStage(o) == 0) continue;
            double d = o.squaredDistanceTo(p);
            if (d <= best) { best = d; v = o; }
        }
        if (v == null) { JobSkills.bar(p, "6칸 안에 뱀파이어가 없습니다", Formatting.RED); return false; }
        int s = vampStage(v);
        if (s >= 3) { JobSkills.bar(p, "기둥 속 사내 이상은 정화할 수 없습니다 — 쓰러뜨리는 수밖에", Formatting.RED); return false; }
        final ServerPlayerEntity target = v;
        ServerWorld w = p.getServerWorld();
        target.sendMessage(Text.literal("✝ " + p.getName().getString() + " 성자가 정화 의식을 시작했다! 5초 안에 벗어나라!").formatted(Formatting.GOLD, Formatting.BOLD), false);
        p.sendMessage(Text.literal("✝ 정화 의식 — 5초 동안 6칸 안에 붙잡아 두세요").formatted(Formatting.YELLOW), false);
        boolean[] broken = {false};
        for (int t = 0; t <= 100; t += 5) {
            final int tick = t;
            Tasks.later(t, () -> {
                if (broken[0]) return;
                if (!p.isAlive() || !target.isAlive() || p.isDisconnected() || target.isDisconnected()
                        || target.getServerWorld() != w || target.squaredDistanceTo(p) > 8 * 8) {
                    broken[0] = true;
                    if (!p.isDisconnected()) p.sendMessage(Text.literal("정화 의식이 끊겼습니다").formatted(Formatting.RED), false);
                    return;
                }
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 3, false, false));
                for (int i = 0; i < 20; i++) {
                    double a = Math.PI * 2 * i / 20 + tick * 0.1;
                    w.spawnParticles(GOLD, target.getX() + Math.cos(a) * 1.2, target.getY() + 0.2 + tick / 50.0, target.getZ() + Math.sin(a) * 1.2, 1, 0, 0, 0, 0);
                }
                if (tick % 20 == 0) w.playSound(null, target.getBlockPos(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 0.8f + tick / 100f);
                if (tick == 100) {
                    purify(target);
                    addFame(p, Jobs.entry(p), 300, "정화");
                }
            });
        }
        return true;
    }

    /** 뱀파이어를 인간으로 */
    public static void purify(ServerPlayerEntity v) {
        AlchemyData data = AlchemyData.get(v.getServer());
        AlchemyData.Entry e = data.of(v.getUuid());
        if (!Jobs.VAMPIRE_ID.equals(e.job)) return;
        CombatMode.exit(v, null);
        JobType t = Jobs.TYPES.get(Jobs.VAMPIRE_ID);
        if (t != null) { t.onQuit(v, e); e.clearJobData(t.prefix()); }
        e.job = "";
        e.flag("flag.vam_purified", true);
        data.markDirty();
        ServerWorld w = v.getServerWorld();
        w.spawnParticles(ParticleTypes.END_ROD, v.getX(), v.getY() + 1, v.getZ(), 80, 0.4, 1, 0.4, 0.2);
        w.playSound(null, v.getBlockPos(), SoundEvents.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        v.sendMessage(Text.literal("✝ 몸에서 피의 저주가 빠져나간다… 인간으로 돌아왔다.").formatted(Formatting.GOLD, Formatting.BOLD), false);
        v.getServer().getPlayerManager().broadcast(Text.literal("✝ " + v.getName().getString() + "님이 정화되어 인간으로 돌아왔습니다.").formatted(Formatting.YELLOW), false);
    }
}
