package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 현자의 돌 효과 선택창 (사용 방식 A: 하나 고르면 돌 1개 소모) */
public class StoneMenu extends ButtonMenu {
    public static final int SHINY_LEVEL = 50;

    private static final int DUEL = 1, CANDY = 2, MASTER = 3, WISH = 5, PIKACHU = 6, SHINY = 7;

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new StoneMenu(syncId, inv, player),
                Text.literal("현자의 돌 — 효과를 하나 고르세요")));
    }

    private StoneMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
        super(syncId, inv, player);
        button(DUEL, icon(DuelMod.DUEL_GLOVE, "① 리스크 없는 결투", Formatting.RED,
                "돌을 들고 플레이어를 우클릭하면 시작", "져도 잃는 것 없음",
                "이기면 아이템 또는 포켓몬(랜덤)을 빼앗음", "※ 결투가 시작될 때 돌 소모"));
        button(CANDY, icon(cobblemon("rare_candy"), "② 이상한사탕 100개", Formatting.AQUA, "즉시 지급"));
        button(MASTER, icon(cobblemon("master_ball"), "③ 마스터볼 3개", Formatting.LIGHT_PURPLE, "즉시 지급"));
        button(WISH, icon(Items.NETHER_STAR, "④ 원하는 아이템 1개", Formatting.YELLOW,
                "선택 후 채팅에  /wish <아이템>  입력", "예) /wish elytra   /wish cobblemon:exp_candy_xl",
                "※ 아이템을 받을 때 돌 소모", "취소:  /wish"));
        button(PIKACHU, icon(Items.GOLD_NUGGET, "⑤ 연금술사 피카츄", Formatting.GOLD,
                "돌을 들고 자신의 연금술사 피카츄를 우클릭", "현자 폼으로 영구 전환 (되돌릴 수 없음)"));
        button(SHINY, icon(cobblemon("poke_ball"), "⑥ 랜덤 이로치 포켓몬", Formatting.GREEN,
                "전설·환상·커스텀 포함 전체에서 랜덤", "Lv." + SHINY_LEVEL + " 이로치로 지급"));
    }

    @Override
    protected boolean onPick(int slot) {
        switch (slot) {
            case DUEL -> {
                say("✦ 현자의 돌을 손에 들고 결투할 플레이어를 우클릭하세요.", Formatting.RED);
                return true;
            }
            case CANDY -> {
                if (!PhilosopherStoneItem.consumeOne(player)) return fail();
                give(new ItemStack(cobblemon("rare_candy"), 100));
                done("이상한사탕 100개를 받았습니다.");
                return true;
            }
            case MASTER -> {
                if (!PhilosopherStoneItem.consumeOne(player)) return fail();
                give(new ItemStack(cobblemon("master_ball"), 3));
                done("마스터볼 3개를 받았습니다.");
                return true;
            }
            case WISH -> {
                WishCommand.arm(player);
                say("✦ 채팅에  /wish <아이템>  을 입력하세요. (탭으로 자동완성)  취소는  /wish", Formatting.YELLOW);
                return true;
            }
            case PIKACHU -> {
                say("✦ 현자의 돌을 손에 들고 자신의 연금술사 피카츄를 우클릭하세요. (되돌릴 수 없습니다)", Formatting.GOLD);
                return true;
            }
            case SHINY -> {
                List<Species> all = PokemonSpecies.getImplemented();
                if (all.isEmpty()) return fail();
                if (!PhilosopherStoneItem.consumeOne(player)) return fail();
                Species sp = all.get(ThreadLocalRandom.current().nextInt(all.size()));
                Pokemon mon = new Pokemon();
                mon.setSpecies(sp);
                mon.setLevel(SHINY_LEVEL);
                mon.initialize();
                mon.setShiny(true);
                boolean pc = false;
                if (!Cobblemon.INSTANCE.getStorage().getParty(player).add(mon)) {
                    Cobblemon.INSTANCE.getStorage().getPC(player).add(mon);
                    pc = true;
                }
                done("이로치 [" + sp.getTranslatedName().getString() + "] Lv." + SHINY_LEVEL + "이(가) 나타났습니다!"
                        + (pc ? " (파티가 가득 차서 PC로 보냈습니다)" : ""));
                return true;
            }
            default -> { }
        }
        return false;
    }

    // ───────── helpers ─────────
    static Item cobblemon(String id) { return Registries.ITEM.get(Identifier.of("cobblemon", id)); }

    private void give(ItemStack stack) {
        // 64개 넘는 묶음은 나눠서 지급, 가득 차면 발밑에 떨어뜨림
        while (!stack.isEmpty()) {
            ItemStack part = stack.split(Math.min(stack.getMaxCount(), stack.getCount()));
            player.getInventory().offerOrDrop(part);
        }
    }

    private void say(String msg, Formatting c) { player.sendMessage(Text.literal(msg).formatted(c), false); }

    private void done(String msg) {
        StoneFx.big(player);
        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        say("✦ " + msg, Formatting.LIGHT_PURPLE);
    }

    private boolean fail() {
        say("현자의 돌이 없습니다.", Formatting.RED);
        return true;
    }
}
