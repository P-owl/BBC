package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 결투 장갑 / 현자의 돌 배틀만 추적해서 승패에 따라 보상을 처리한다.
 * 일반 /challenge 배틀에는 영향 없음.
 */
public final class DuelRewards {
    /** true = 한 칸 통째로, false = 그 칸에서 1개만 */
    public static final boolean STEAL_WHOLE_STACK = true;

    private enum Kind { GLOVE, STONE }
    private record Info(Kind kind, UUID caster) {}

    private static final Map<UUID, Info> DUELS = new ConcurrentHashMap<>();

    private DuelRewards() {}

    public static void markGlove(PokemonBattle battle, ServerPlayerEntity caster) {
        DUELS.put(battle.getBattleId(), new Info(Kind.GLOVE, caster.getUuid()));
    }

    public static void markStone(PokemonBattle battle, ServerPlayerEntity caster) {
        DUELS.put(battle.getBattleId(), new Info(Kind.STONE, caster.getUuid()));
    }

    public static void register() {
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            Info info = DUELS.remove(event.getBattle().getBattleId());
            if (info != null) settle(info, players(event.getWinners()), players(event.getLosers()));
            return kotlin.Unit.INSTANCE;
        });

        // 도망(항복)도 패배로 처리
        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL, event -> {
            PokemonBattle battle = event.getBattle();
            Info info = DUELS.remove(battle.getBattleId());
            if (info != null) {
                ServerPlayerEntity loser = event.getPlayer().getEntity();
                List<ServerPlayerEntity> winners = new ArrayList<>();
                for (ServerPlayerEntity p : battle.getPlayers()) {
                    if (loser == null || !p.getUuid().equals(loser.getUuid())) winners.add(p);
                }
                List<ServerPlayerEntity> losers = new ArrayList<>();
                if (loser != null) losers.add(loser);
                settle(info, winners, losers);
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    private static List<ServerPlayerEntity> players(List<BattleActor> actors) {
        List<ServerPlayerEntity> out = new ArrayList<>();
        for (BattleActor a : actors) {
            if (a instanceof PlayerBattleActor pa && pa.getEntity() != null) out.add(pa.getEntity());
        }
        return out;
    }

    private static void settle(Info info, List<ServerPlayerEntity> winners, List<ServerPlayerEntity> losers) {
        if (winners.isEmpty() || losers.isEmpty()) return;

        if (info.kind() == Kind.GLOVE) {
            for (int i = 0; i < winners.size(); i++) stealItem(winners.get(i), losers.get(i % losers.size()));
            return;
        }

        // 현자의 돌: 시전자가 이겼을 때만 보상, 지면 아무것도 잃지 않음
        ServerPlayerEntity caster = null;
        for (ServerPlayerEntity w : winners) if (w.getUuid().equals(info.caster())) caster = w;

        if (caster == null) {
            for (ServerPlayerEntity l : losers) {
                if (l.getUuid().equals(info.caster())) {
                    l.sendMessage(Text.literal("✦ 현자의 돌의 가호로 아무것도 잃지 않았습니다.").formatted(Formatting.LIGHT_PURPLE), false);
                }
            }
            return;
        }

        ServerPlayerEntity winner = caster;
        UUID loserId = losers.get(0).getUuid();
        // 배틀 정리가 끝난 뒤에 선택창을 띄움 (바로 띄우면 배틀 화면에 묻힘)
        Tasks.later(20, () -> RewardChoiceMenu.open(winner, loserId));
    }

    // ───────────────────────────── 보상 실행 ─────────────────────────────

    public static void stealItem(ServerPlayerEntity winner, ServerPlayerEntity loser) {
        PlayerInventory inv = loser.getInventory();

        // 대상 칸: 메인 인벤토리 36칸 + 왼손. 갑옷은 제외.
        List<Integer> slots = new ArrayList<>();
        for (int s = 0; s < inv.main.size(); s++) {
            if (!inv.main.get(s).isEmpty()) slots.add(s);
        }
        boolean offhandFilled = !inv.offHand.get(0).isEmpty();
        int total = slots.size() + (offhandFilled ? 1 : 0);

        if (total == 0) {
            winner.sendMessage(Text.literal(loser.getName().getString() + "님의 인벤토리가 비어 있어 가져올 아이템이 없습니다.")
                    .formatted(Formatting.GRAY), false);
            return;
        }

        int pick = ThreadLocalRandom.current().nextInt(total);
        ItemStack taken;
        if (pick < slots.size()) {
            int slot = slots.get(pick);
            taken = STEAL_WHOLE_STACK ? inv.removeStack(slot) : inv.removeStack(slot, 1);
        } else {
            ItemStack off = inv.offHand.get(0);
            taken = STEAL_WHOLE_STACK ? off.copyAndEmpty() : off.split(1);
        }
        loser.currentScreenHandler.sendContentUpdates();

        String desc = taken.getName().getString() + (taken.getCount() > 1 ? " ×" + taken.getCount() : "");
        winner.getInventory().offerOrDrop(taken);   // 가득 차면 발밑에 떨어뜨림

        winner.getWorld().playSound(null, winner.getBlockPos(),
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1.0f, 0.7f);
        winner.sendMessage(Text.literal("🏆 결투 승리! " + loser.getName().getString() + "님에게서 [" + desc + "]을(를) 빼앗았습니다.")
                .formatted(Formatting.GOLD), false);
        loser.sendMessage(Text.literal("💀 결투 패배... " + winner.getName().getString() + "님이 [" + desc + "]을(를) 가져갔습니다.")
                .formatted(Formatting.DARK_RED), false);
    }

    /** 상대 파티에서 랜덤 1마리를 가져온다. 파티가 비어 있으면 아이템 강탈로 대체. */
    public static void stealPokemon(ServerPlayerEntity winner, ServerPlayerEntity loser) {
        PlayerPartyStore from = Cobblemon.INSTANCE.getStorage().getParty(loser);
        List<Pokemon> candidates = new ArrayList<>();
        for (Pokemon p : from.toGappyList()) if (p != null) candidates.add(p);

        if (candidates.isEmpty()) {
            winner.sendMessage(Text.literal("상대 파티가 비어 있어 대신 아이템을 빼앗습니다.").formatted(Formatting.GRAY), false);
            stealItem(winner, loser);
            return;
        }

        Pokemon taken = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        if (taken.getEntity() != null) taken.recall();
        if (!from.remove(taken)) {
            winner.sendMessage(Text.literal("포켓몬을 옮기지 못했습니다. 대신 아이템을 빼앗습니다.").formatted(Formatting.RED), false);
            stealItem(winner, loser);
            return;
        }

        PlayerPartyStore to = Cobblemon.INSTANCE.getStorage().getParty(winner);
        boolean toPc = false;
        if (!to.add(taken)) {
            Cobblemon.INSTANCE.getStorage().getPC(winner).add(taken);
            toPc = true;
        }

        String name = taken.getSpecies().getTranslatedName().getString() + " Lv." + taken.getLevel()
                + (taken.getShiny() ? " ★" : "");
        winner.getWorld().playSound(null, winner.getBlockPos(),
                SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.2f);
        winner.sendMessage(Text.literal("✦ 현자의 돌 결투 승리! " + loser.getName().getString() + "님의 [" + name + "]을(를) 빼앗았습니다."
                + (toPc ? " (파티가 가득 차서 PC로 보냈습니다)" : "")).formatted(Formatting.LIGHT_PURPLE), false);
        loser.sendMessage(Text.literal("💀 " + winner.getName().getString() + "님이 당신의 [" + name + "]을(를) 가져갔습니다.")
                .formatted(Formatting.DARK_RED), false);
    }

    public static ServerPlayerEntity online(MinecraftServer server, UUID id) {
        return server.getPlayerManager().getPlayer(id);
    }
}
