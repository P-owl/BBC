package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.CobblemonItems;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.Abilities;
import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 연성진으로 소환하는 레이드 보스 연금술사 피카츄.
 * 소환 → 소환자가 평소처럼 포켓몬을 던져(R) 배틀을 건다 → 승패에 따라 보상.
 *  - Lv.100, 6V, 전기구슬, 특성 '연성 결계'
 *  - 몬스터볼 무효, 소환자 외에는 배틀 불가
 *  - 2분 안에 도전하지 않으면 사라짐(패배 처리)
 *  - 승리: 현자의 돌 1개 + 50% 확률로 연금술사 피카츄 / 패배·도망: 50% 확률로 현자의 돌
 */
public final class RaidBoss {
    public static final String ASPECT = "raidboss";
    public static final double CAPTURE_CHANCE = 0.5;
    public static final double STONE_ON_LOSS = 0.5;
    /** 도전 가능 시간: 소환 후 5분 */
    private static final int OPEN_TICKS = 20 * 300;
    private static final List<String> BOSS_MOVES = List.of("weaponforge", "steelarmblade", "groundforge", "thunderpunch");

    private static final class Raid {
        final UUID summoner;
        final PokemonEntity boss;
        final ServerBossBar bar;
        /** 진행 중인 배틀 ID → 도전자 */
        final Map<UUID, UUID> battles = new ConcurrentHashMap<>();
        final Map<UUID, PokemonBattle> battleObjs = new ConcurrentHashMap<>();
        /** 이미 도전한 플레이어 (재도전 불가) */
        final Set<UUID> challengers = ConcurrentHashMap.newKeySet();
        /** 피해를 준 플레이어 */
        final Set<UUID> contributors = ConcurrentHashMap.newKeySet();
        double pool, poolMax;
        boolean done, defeated;
        int age;
        Raid(UUID summoner, PokemonEntity boss, ServerBossBar bar) { this.summoner = summoner; this.boss = boss; this.bar = bar; }
        boolean open() { return !done && !defeated && age < OPEN_TICKS; }
    }

    /** 보스 엔티티 UUID → 레이드 */
    private static final Map<UUID, Raid> BY_BOSS = new ConcurrentHashMap<>();
    /** 배틀 ID → 레이드 */
    private static final Map<UUID, Raid> BY_BATTLE = new ConcurrentHashMap<>();

    private RaidBoss() {}

    public static void register() {
        CobblemonEvents.THROWN_POKEBALL_HIT.subscribe(Priority.HIGH, e -> {
            if (isBoss(e.getPokemon().getPokemon())) {
                e.cancel();
                if (e.getPokeBall().getOwner() instanceof ServerPlayerEntity sp) {
                    sp.sendMessage(Text.literal("연성 결계가 몬스터볼을 튕겨냈다! 먼저 쓰러뜨려야 한다.").formatted(Formatting.RED), true);
                }
            }
            return kotlin.Unit.INSTANCE;
        });

        // 도전 조건: 레이드 진행 중 + 아직 도전하지 않은 플레이어
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGH, e -> {
            Raid raid = raidOf(e.getBattle());
            if (raid == null) return kotlin.Unit.INSTANCE;
            String why = null;
            if (!raid.open()) why = "레이드 도전 시간이 끝났다.";
            for (BattleActor a : e.getBattle().getActors()) {
                if (a instanceof PlayerBattleActor pa && raid.challengers.contains(pa.getUuid())) why = "이미 이 레이드에 도전했다. (재도전 불가)";
            }
            DuelMod.LOG.info("[raid] battle start check: {}", why == null ? "OK" : why);
            if (why != null) {
                e.setReason(Text.literal(why));
                e.cancel();
            }
            return kotlin.Unit.INSTANCE;
        });

        CobblemonEvents.BATTLE_STARTED_POST.subscribe(Priority.NORMAL, e -> {
            Raid raid = raidOf(e.getBattle());
            if (raid == null) return kotlin.Unit.INSTANCE;
            PokemonBattle battle = e.getBattle();
            for (BattleActor a : battle.getActors()) {
                if (a instanceof PlayerBattleActor pa) {
                    raid.challengers.add(pa.getUuid());
                    raid.battles.put(battle.getBattleId(), pa.getUuid());
                }
            }
            raid.battleObjs.put(battle.getBattleId(), battle);
            BY_BATTLE.put(battle.getBattleId(), raid);
            synchronized (raid) {
                // 도전자 한 명당 보스 HP 한 칸씩 추가
                double add = bossMaxHp(raid);
                raid.pool += add;
                raid.poolMax += add;
            }
            int n = raid.challengers.size();
            broadcastNear(raid, Text.literal("⚔ " + playerName(battle) + "님이 레이드에 합류했다! (도전자 " + n + "명)").formatted(Formatting.YELLOW));
            Tasks.later(10, () -> resyncAll(raid));
            Tasks.later(40, () -> resyncAll(raid));
            return kotlin.Unit.INSTANCE;
        });

        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, e -> {
            onBattleOver(e.getBattle());
            return kotlin.Unit.INSTANCE;
        });

        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL, e -> {
            onBattleOver(e.getBattle());
            return kotlin.Unit.INSTANCE;
        });
    }

    private static void onBattleOver(PokemonBattle battle) {
        Raid raid = BY_BATTLE.remove(battle.getBattleId());
        if (raid == null) return;
        UUID who = raid.battles.remove(battle.getBattleId());
        raid.battleObjs.remove(battle.getBattleId());
        if (raid.defeated || raid.done || who == null) return;
        ServerPlayerEntity p = player(raid, who);
        if (p != null) p.sendMessage(Text.literal("레이드에서 탈락했다... 다른 도전자가 쓰러뜨리면 보상을 받을 수 있다.").formatted(Formatting.GRAY), false);
    }

    private static String playerName(PokemonBattle battle) {
        for (ServerPlayerEntity p : battle.getPlayers()) return p.getName().getString();
        return "?";
    }

    private static ServerPlayerEntity player(Raid raid, UUID id) {
        return raid.boss.getServer() == null ? null : raid.boss.getServer().getPlayerManager().getPlayer(id);
    }

    private static void broadcastNear(Raid raid, Text msg) {
        if (!(raid.boss.getWorld() instanceof ServerWorld w)) return;
        for (ServerPlayerEntity sp : w.getPlayers()) if (sp.squaredDistanceTo(raid.boss) < 64 * 64) sp.sendMessage(msg, false);
    }

    private static int bossMaxHp(Raid raid) {
        return Math.max(1, raid.boss.getPokemon().getMaxHealth());
    }

    /** 쇼다운 보스 특성이 보낸 피해/회복 보고 (AlchemyStartMixin 에서 호출) */
    public static void onReport(PokemonBattle battle, int amount, boolean heal) {
        Raid raid = BY_BATTLE.get(battle.getBattleId());
        if (raid == null || raid.done || raid.defeated) return;
        boolean kill = false;
        synchronized (raid) {
            if (heal) {
                raid.pool = Math.min(raid.poolMax, raid.pool + (double) amount / Math.max(1, raid.battles.size()));
            } else {
                raid.pool -= amount;
                UUID who = raid.battles.get(battle.getBattleId());
                if (who != null) raid.contributors.add(who);
                if (raid.pool <= 0) { raid.pool = 0; kill = true; }
            }
        }
        if (kill) Tasks.later(1, () -> defeat(raid));
        else Tasks.later(1, () -> resyncAll(raid));
    }

    /** 모든 레이드 배틀의 보스 HP 를 공유 HP 비율에 맞춤 */
    private static void resyncAll(Raid raid) {
        if (raid.done || raid.defeated || raid.poolMax <= 0) return;
        int max = bossMaxHp(raid);
        int target = (int) Math.max(1, Math.ceil(raid.pool / raid.poolMax * max));
        String code = "const bp=battle.sides[1].active[0];if(bp&&bp.hp>0&&bp.hp!==" + target + "){bp.sethp(" + target + ");"
                + "battle.add('-sethp',bp,bp.getHealth,'[silent]');battle.sendUpdates();}";
        for (PokemonBattle b : raid.battleObjs.values()) {
            if (b.getEnded()) continue;
            try { b.writeShowdownAction(">eval " + code); } catch (Throwable t) { DuelMod.LOG.warn("레이드 HP 동기화 실패: {}", t.toString()); }
        }
    }

    /** 공유 HP 가 0 → 모든 배틀 승리 처리 + 보상 */
    private static void defeat(Raid raid) {
        if (raid.defeated || raid.done) return;
        raid.defeated = true;
        for (PokemonBattle b : raid.battleObjs.values()) {
            if (b.getEnded()) continue;
            try { b.writeShowdownAction(">forcewin p1"); } catch (Throwable t) { DuelMod.LOG.warn("레이드 강제 승리 실패: {}", t.toString()); }
        }
        if (raid.boss.getServer() != null) {
            raid.boss.getServer().getPlayerManager().broadcast(Text.literal("✦ 레이드 성공! 폭주하는 연금술사 피카츄가 쓰러졌다! (참여 " + raid.contributors.size() + "명)")
                    .formatted(Formatting.AQUA, Formatting.BOLD), false);
        }
        close(raid);
        Tasks.later(40, () -> {
            ServerPlayerEntity summoner = player(raid, raid.summoner);
            if (summoner != null) {
                giveStone(summoner);
                summoner.sendMessage(Text.literal("✦ 연성진이 무너지며 현자의 돌 하나를 남겼다.").formatted(Formatting.DARK_RED), false);
            }
            for (UUID id : raid.contributors) {
                ServerPlayerEntity p = player(raid, id);
                if (p != null) tryCapture(p);
            }
        });
    }

    /** 실패: 시간 종료 + 진행 중인 배틀 없음 */
    private static void fail(Raid raid) {
        if (raid.done || raid.defeated) return;
        if (raid.boss.getServer() != null) {
            raid.boss.getServer().getPlayerManager().broadcast(Text.literal("연금술사 피카츄가 연성진 너머로 사라졌다... (레이드 실패)")
                    .formatted(Formatting.GRAY), false);
        }
        close(raid);
        Tasks.later(30, () -> {
            ServerPlayerEntity summoner = player(raid, raid.summoner);
            if (summoner != null && ThreadLocalRandom.current().nextDouble() < STONE_ON_LOSS) {
                giveStone(summoner);
                summoner.sendMessage(Text.literal("…무너진 연성진 자리에서 현자의 돌을 주웠다.").formatted(Formatting.DARK_RED), false);
            }
        });
    }

    private static void close(Raid raid) {
        raid.done = true;
        BY_BOSS.remove(raid.boss.getUuid());
        for (UUID id : raid.battleObjs.keySet()) BY_BATTLE.remove(id);
        raid.bar.clearPlayers();
        raid.bar.setVisible(false);
        Tasks.later(60, () -> { if (!raid.boss.isRemoved()) raid.boss.discard(); });
    }

    public static boolean isBoss(Pokemon p) {
        // 클라이언트에는 forcedAspects 가 동기화되지 않으므로 aspects 도 확인
        return p != null && (p.getForcedAspects().contains(ASPECT) || p.getAspects().contains(ASPECT));
    }

    /** 배틀에 레이드 보스가 있으면 그 레이드 */
    private static Raid raidOf(PokemonBattle battle) {
        for (BattleActor a : battle.getActors()) {
            if (a instanceof PokemonBattleActor wild && wild.getEntity() != null) {
                Raid r = BY_BOSS.get(wild.getEntity().getUuid());
                if (r != null) return r;
            }
        }
        return null;
    }

    /** canBattle 믹스인용: 이 보스에 지금 도전할 수 있는가 (서버) */
    public static boolean canChallenge(PokemonEntity entity, UUID player) {
        Raid raid = BY_BOSS.get(entity.getUuid());
        return raid != null && raid.open() && !raid.challengers.contains(player);
    }

    /** 디버그 로그용 */
    public static boolean isRaidBattle(PokemonBattle battle) {
        return BY_BATTLE.containsKey(battle.getBattleId()) || raidOf(battle) != null;
    }

    private static void giveStone(ServerPlayerEntity p) {
        p.getInventory().offerOrDrop(new ItemStack(DuelMod.PHILOSOPHERS_STONE));
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    /** 연성진 연출이 끝난 뒤 호출. 실패하면 false (아이템 돌려줄 것) */
    public static boolean summon(ServerPlayerEntity player, Vec3d pos) {
        Species species = PokemonSpecies.getByName("pikachu");
        if (species == null) return false;
        Pokemon mon = species.create(100);
        AlchemistForm.toAlchemist(mon);
        Set<String> aspects = new HashSet<>(mon.getForcedAspects());
        aspects.add(ASPECT);
        mon.setForcedAspects(aspects);
        mon.updateAspects();

        for (Stats s : new Stats[]{Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED}) {
            mon.setIV(s, 31);
        }
        mon.setEV(Stats.HP, 4);
        mon.setEV(Stats.ATTACK, 252);
        mon.setEV(Stats.SPEED, 252);
        try {
            AbilityTemplate tpl = Abilities.get("transmutationbarrier");
            if (tpl != null) mon.updateAbility(tpl.create(false, Priority.LOW));
        } catch (Throwable t) {
            DuelMod.LOG.warn("연성 결계 특성 부여 실패: {}", t.toString());
        }
        for (int i = 0; i < BOSS_MOVES.size(); i++) {
            MoveTemplate t = Moves.getByName(BOSS_MOVES.get(i));
            if (t != null) mon.getMoveSet().setMove(i, t.create(t.getMaxPp()));
        }
        mon.swapHeldItem(new ItemStack(CobblemonItems.LIGHT_BALL), false, false);
        mon.setScaleModifier(2.0f);
        mon.heal();

        ServerWorld w = player.getServerWorld();
        PokemonEntity boss = new PokemonEntity(w, mon, CobblemonEntities.POKEMON);
        boss.refreshPositionAndAngles(pos.x, pos.y, pos.z, player.getYaw() + 180f, 0f);
        boss.setInvulnerable(true); // 칼·화살 등으로 엔티티가 죽어 레이드가 끝나는 것 방지
        if (!w.spawnEntity(boss)) return false;

        ServerBossBar bar = new ServerBossBar(Text.literal("폭주하는 연금술사 피카츄").formatted(Formatting.GOLD, Formatting.BOLD),
                BossBar.Color.RED, BossBar.Style.NOTCHED_10);
        Raid raid = new Raid(player.getUuid(), boss, bar);
        raid.pool = 0;
        raid.poolMax = 0;
        BY_BOSS.put(boss.getUuid(), raid);
        tick(raid, 0);

        w.getServer().getPlayerManager().broadcast(Text.literal("⚠ " + player.getName().getString()
                + "님이 연성진을 열었다! 공유 레이드: 폭주하는 연금술사 피카츄 출현 (" + boss.getBlockX() + ", " + boss.getBlockY() + ", " + boss.getBlockZ() + ")")
                .formatted(Formatting.GOLD), false);
        w.playSound(null, boss.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 0.6f, 1.6f);
        player.sendMessage(Text.literal("⚠ 레이드 보스 출현! 근처 플레이어 누구나 포켓몬을 던져(R) 함께 도전할 수 있습니다. (5분)")
                .formatted(Formatting.GOLD, Formatting.BOLD), false);
        return true;
    }

    private static final DustParticleEffect RIFT = new DustParticleEffect(new Vector3f(0.35f, 0.75f, 1.0f), 1.6f);
    private static final DustParticleEffect RIFT_CORE = new DustParticleEffect(new Vector3f(0.95f, 0.15f, 0.2f), 1.2f);

    /** 보스바 갱신(근처 48칸) + 머리 위 연성 균열 + 시간 초과/배틀 소실 정리 */
    private static void tick(Raid raid, int n) {
        if (raid.done) return;
        PokemonEntity boss = raid.boss;
        raid.age += 5;
        // 끊긴 배틀 정리
        for (UUID id : new ArrayList<>(raid.battleObjs.keySet())) {
            PokemonBattle b = raid.battleObjs.get(id);
            if (b == null || b.getEnded() || BattleRegistry.getBattle(id) == null) {
                raid.battleObjs.remove(id);
                raid.battles.remove(id);
                BY_BATTLE.remove(id);
            }
        }
        if (boss.isRemoved() || (!raid.open() && raid.battles.isEmpty())) {
            fail(raid);
            return;
        }

        float ratio = raid.poolMax > 0 ? (float) (raid.pool / raid.poolMax) : 1f;
        raid.bar.setPercent(Math.max(0f, Math.min(1f, ratio)));
        raid.bar.setColor(ratio <= 0.5f ? BossBar.Color.PURPLE : BossBar.Color.RED);
        int left = Math.max(0, (OPEN_TICKS - raid.age) / 20);
        raid.bar.setName(Text.literal("폭주하는 연금술사 피카츄  ·  도전자 " + raid.challengers.size() + "명"
                + (raid.open() ? "  ·  " + left / 60 + ":" + String.format("%02d", left % 60) : "  ·  마감"))
                .formatted(Formatting.GOLD, Formatting.BOLD));

        ServerWorld w = (ServerWorld) boss.getWorld();
        for (ServerPlayerEntity sp : w.getPlayers()) {
            if (sp.squaredDistanceTo(boss) < 48 * 48) raid.bar.addPlayer(sp); else raid.bar.removePlayer(sp);
        }

        Vec3d top = boss.getPos().add(0, boss.getHeight() + 3.0, 0);
        double spin = n * 0.15;
        for (int i = 0; i < 24; i++) {
            double a = spin + Math.PI * 2 * i / 24;
            double r = 1.6 + 0.2 * Math.sin(n * 0.3 + i);
            w.spawnParticles(RIFT, top.x + Math.cos(a) * r, top.y + Math.sin(a * 2) * 0.15, top.z + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
        w.spawnParticles(RIFT_CORE, top.x, top.y, top.z, 6, 0.4, 0.15, 0.4, 0);
        w.spawnParticles(ParticleTypes.REVERSE_PORTAL, top.x, top.y, top.z, 8, 0.6, 0.2, 0.6, 0.02);
        if (n % 8 == 0) w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, top.x, top.y - 1.0, top.z, 10, 0.5, 1.0, 0.5, 0.3);

        Tasks.later(5, () -> tick(raid, n + 1));
    }

    private static void tryCapture(ServerPlayerEntity p) {
        // 연금술사 직업이면 반드시 포획
        boolean alchemist = Jobs.isAlchemist(Jobs.entry(p));
        if (alchemist) p.sendMessage(Text.literal("✦ 연금술사의 연성진이 도망치려는 피카츄를 붙잡았다!").formatted(Formatting.GOLD), false);
        if (!alchemist && ThreadLocalRandom.current().nextDouble() >= CAPTURE_CHANCE) {
            p.sendMessage(Text.literal("연금술사 피카츄는 쓰러지기 직전 연성진 너머로 도망쳤다... (포획 실패)")
                    .formatted(Formatting.GRAY), false);
            p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.2f);
            return;
        }
        Species species = PokemonSpecies.getByName("pikachu");
        if (species == null) return;
        Pokemon mon = species.create(50);
        AlchemistForm.toAlchemist(mon);
        List<Stats> stats = new ArrayList<>(List.of(Stats.HP, Stats.ATTACK, Stats.DEFENCE,
                Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED));
        Collections.shuffle(stats);
        for (int i = 0; i < 3; i++) mon.setIV(stats.get(i), 31);
        mon.heal();

        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(p);
        boolean toParty = party.add(mon);
        if (!toParty) Cobblemon.INSTANCE.getStorage().getPC(p).add(mon);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        p.sendMessage(Text.literal("✦ 연금술사 피카츄를 손에 넣었다!" + (toParty ? "" : " (파티가 가득 차서 PC로 보냈습니다)"))
                .formatted(Formatting.AQUA, Formatting.BOLD), false);
    }
}
