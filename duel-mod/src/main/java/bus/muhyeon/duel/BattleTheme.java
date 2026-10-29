package bus.muhyeon.duel;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.net.messages.client.battle.BattleMusicPacket;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/** 내 연금술사/현자 피카츄를 배틀에 내보내면 배틀 참가자 전원의 배틀 음악을 전용 테마로 바꾼다. */
public final class BattleTheme {
    private static final Identifier ED = Identifier.of("cobblemon", "battle.alchemy.ed");
    private static final Identifier HOEN = Identifier.of("cobblemon", "battle.alchemy.hoen");

    private BattleTheme() {}

    public static void register() {
        CobblemonEvents.POKEMON_SENT_POST.subscribe(Priority.LOW, e -> {
            onSent(e.getPokemon());
            return kotlin.Unit.INSTANCE;
        });
    }

    private static void onSent(Pokemon p) {
        if (p == null || !AlchemistForm.isPikachu(p)) return;
        Identifier theme = AlchemistForm.isAlchemist(p) ? ED
                : p.getAspects().contains(AlchemistForm.SAGE) ? HOEN : null;
        if (theme == null) return;
        ServerPlayerEntity owner = p.getOwnerPlayer();
        if (owner == null) return;
        // 배틀 시작 시 기본 음악 패킷보다 늦게 도착하도록 0.5초 뒤 전환
        Tasks.later(10, () -> {
            PokemonBattle battle = BattleRegistry.getBattleByParticipatingPlayer(owner);
            if (battle == null || battle.getEnded()) return;
            new BattleMusicPacket(theme, 1.0f, 1.0f, false).sendToPlayers(battle.getPlayers());
        });
    }
}
