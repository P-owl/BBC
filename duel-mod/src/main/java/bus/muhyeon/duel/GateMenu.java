package bus.muhyeon.duel;

import com.cobblemon.mod.common.battles.BattleRegistry;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * 진리의 문 — 통행료 선택.
 * 기본 대가: 연성 에너지 1,048,576 E + 경험치 30레벨 (무조건)
 * 추가 대가(하나 고름, 영구): 팔 / 다리 / 심장
 * 연성진 근처에서만 열 수 있음.
 */
public class GateMenu extends ButtonMenu {
    private static final int COST = 0, ARM = 2, LEG = 4, HEART = 6, WARN = 8;

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new GateMenu(syncId, inv, player),
                Text.literal("진리의 문 — 통행료를 고르세요")));
    }

    private GateMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
        super(syncId, inv, player);
        AlchemyData.Entry e = Jobs.entry(player);
        boolean okE = e.energy + 1e-6 >= Jobs.GATE_ENERGY;
        boolean okL = player.experienceLevel >= Jobs.GATE_LEVELS;
        boolean okC = AlchemyMenu.near(player, AlchemyMenu.LAST_CIRCLE.get(player.getUuid()));
        button(COST, icon(Items.NETHER_STAR, "기본 대가", Formatting.WHITE,
                (okE ? "✔ " : "✘ ") + "연성 에너지 " + Alchemy.fmt(Jobs.GATE_ENERGY) + " E  (보유 " + Alchemy.fmt(e.energy) + ")",
                (okL ? "✔ " : "✘ ") + "경험치 " + Jobs.GATE_LEVELS + "레벨  (보유 " + player.experienceLevel + ")",
                (okC ? "✔ " : "✘ ") + "연성진 근처에서만 열 수 있음",
                "+ 오른쪽에서 몸의 일부를 하나 골라 바칩니다"));
        button(ARM, icon(Items.IRON_SWORD, "한쪽 팔을 바친다", Formatting.RED,
                "영구: 공격력 -30%, 채굴 속도 -20%", "▶ 클릭하면 바로 문이 열립니다"));
        button(LEG, icon(Items.LEATHER_BOOTS, "한쪽 다리를 바친다", Formatting.RED,
                "영구: 이동 속도 -15%", "▶ 클릭하면 바로 문이 열립니다"));
        button(HEART, icon(Items.RED_DYE, "심장의 일부를 바친다", Formatting.RED,
                "영구: 최대 체력 -6 (하트 3칸)", "▶ 클릭하면 바로 문이 열립니다"));
        button(WARN, icon(Items.WITHER_SKELETON_SKULL, "⚠ 되돌릴 수 없습니다", Formatting.DARK_RED,
                "통행료는 영원히 돌아오지 않습니다", "죽어도, 다시 접속해도 유지됩니다"));
    }

    @Override
    protected boolean onPick(int slot) {
        String toll = switch (slot) {
            case ARM -> "arm";
            case LEG -> "leg";
            case HEART -> "heart";
            default -> null;
        };
        if (toll == null) return false;

        AlchemyData data = AlchemyData.get(player.getServer());
        AlchemyData.Entry e = data.of(player.getUuid());
        BlockPos circle = AlchemyMenu.LAST_CIRCLE.get(player.getUuid());
        if (Jobs.rank(e) != 4) return fail("대연금술사만 문을 열 수 있습니다.");
        if (!AlchemyMenu.near(player, circle)) return fail("연성진 근처(8칸 이내)에서 여세요.");
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) return fail("배틀 중에는 열 수 없습니다.");
        if (e.energy + 1e-6 < Jobs.GATE_ENERGY) return fail("연성 에너지가 부족합니다.");
        if (player.experienceLevel < Jobs.GATE_LEVELS) return fail("경험치 레벨이 부족합니다.");

        // 대가는 문에 들어가기 전에 전부 치른다 (연출 중 접속이 끊겨도 결과는 확정)
        e.energy -= Jobs.GATE_ENERGY;
        player.addExperienceLevels(-Jobs.GATE_LEVELS);
        e.truth = true;
        e.toll = toll;
        data.markDirty();
        TruthGate.begin(player, toll);
        return true;
    }

    private boolean fail(String msg) {
        player.sendMessage(Text.literal(msg).formatted(Formatting.RED), false);
        return true;
    }
}
