package bbc.job.alchemy;

import bbc.core.ButtonMenu;
import bbc.grow.Growth;
import bbc.job.Jobs;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 현자의 돌 효과 선택창 (하나 고르면 돌 1개 소모). 무현버스의 포켓몬 보상을 도시 능력자 설정으로 바꾼 것.
 *  ① 리스크 없는 결투  ② 존재의 비약  ③ 운명의 계시  ④ 등가교환의 끝  ⑤ 연성 골렘  ⑥ 생체 연성
 */
public class StoneMenu extends ButtonMenu {
    private static final int DUEL = 1, ELIXIR = 2, FATE = 3, WISH = 5, GOLEM = 6, BIO = 7;

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new StoneMenu(syncId, inv, player),
                Text.literal("현자의 돌 — 효과를 하나 고르세요")));
    }

    private StoneMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
        super(syncId, inv, player);
        button(DUEL, icon(Items.IRON_SWORD, "① 리스크 없는 결투", Formatting.RED,
                "돌을 들고 플레이어를 우클릭하면 시작 (3분)", "체력이 다하면 쓰러지는 대신 결투가 끝남",
                "져도 잃는 것 없음, 이기면 상대가 손에 든 아이템을 빼앗음", "※ 결투가 시작될 때 돌 소모"));
        button(ELIXIR, icon(Items.EXPERIENCE_BOTTLE, "② 존재의 비약", Formatting.AQUA,
                "존재치를 다음 파워 문턱의 50%만큼 즉시 얻음"));
        button(FATE, icon(Items.PAPER, "③ 운명의 계시", Formatting.LIGHT_PURPLE,
                "재능 카드 3장 중 1장을 바로 고름", "희귀 이상만 나옴"));
        button(WISH, icon(Items.NETHER_STAR, "④ 등가교환의 끝", Formatting.YELLOW,
                "일반 아이템·재료 1개를 골라 받음", "선택 후 채팅에  /wish <아이템>", "무기·갑옷·유물·임플란트·현자의 돌은 안 됨",
                "※ 아이템을 받을 때 돌 소모", "취소:  /wish"));
        button(GOLEM, icon(Items.CARVED_PUMPKIN, "⑤ 연성 골렘", Formatting.GOLD,
                "연금술로 만든 골렘 동료가 따라다니며 같이 싸움", "연금술사 전용 · 한 번 만들면 되돌릴 수 없음"));
        button(BIO, icon(Items.HEART_OF_THE_SEA, "⑥ 생체 연성", Formatting.DARK_GRAY,
                "생체 임플란트 시술 재료 (준비 중)", "임플란트 시술소가 생기면 열립니다"));
    }

    @Override
    protected boolean onPick(int slot) {
        switch (slot) {
            case DUEL -> say("✦ 현자의 돌을 손에 들고 결투할 플레이어를 우클릭하세요.", Formatting.RED);
            case ELIXIR -> {
                if (!PhilosopherStoneItem.consumeOne(player)) return fail();
                var e = Growth.e(player);
                int pw = Growth.power(e);
                double gain = Math.max(30, (Growth.epFor(pw + 1) - Growth.epFor(pw)) * 0.5);
                Growth.gainEp(player, gain, "존재의 비약");
                done("존재의 비약 — 존재치 +" + (long) gain);
            }
            case FATE -> {
                var e = Growth.e(player);
                if (!e.str("g.pick").isEmpty()) { say("아직 고르지 않은 재능 카드가 있습니다. 먼저 고르세요 (K)", Formatting.GRAY); return true; }
                if (!PhilosopherStoneItem.consumeOne(player)) return fail();
                Growth.rollCards(player, 1);
                if (e.str("g.pick").isEmpty()) {   // 조건을 맞는 희귀 카드가 없으면 돌려줌
                    player.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(AlchemyItems.PHILOSOPHERS_STONE));
                    say("지금 받을 수 있는 희귀 이상 카드가 없어 돌을 돌려받았습니다.", Formatting.GRAY);
                    return true;
                }
                StoneFx.big(player);
            }
            case WISH -> {
                WishCommand.arm(player);
                say("✦ 채팅에  /wish <아이템>  을 입력하세요. (탭으로 자동완성)  취소는  /wish", Formatting.YELLOW);
            }
            case GOLEM -> {
                if (!Alch.ALCHEMIST.equals(Jobs.entry(player).job)) { say("연성 골렘은 연금술사만 만들 수 있습니다.", Formatting.RED); return true; }
                if (AlchemyGolem.has(player)) { say("이미 연성 골렘이 있습니다.", Formatting.GRAY); return true; }
                if (!PhilosopherStoneItem.consumeOne(player)) return fail();
                AlchemyGolem.grant(player);
                done("연성 골렘이 깨어났다! 이제 당신을 따라다닙니다.");
            }
            case BIO -> { say("생체 연성은 임플란트 시술소가 생기면 열립니다.", Formatting.GRAY); return true; }
            default -> { return false; }
        }
        return true;
    }

    private void say(String m, Formatting f) { player.sendMessage(Text.literal(m).formatted(f), false); }

    private void done(String m) {
        StoneFx.big(player);
        player.sendMessage(Text.literal("✦ " + m).formatted(Formatting.LIGHT_PURPLE), false);
    }

    private boolean fail() {
        player.sendMessage(Text.literal("현자의 돌이 없습니다.").formatted(Formatting.RED), false);
        return true;
    }
}
