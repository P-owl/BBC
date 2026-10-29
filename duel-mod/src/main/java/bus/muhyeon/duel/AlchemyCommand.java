package bus.muhyeon.duel;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * /alchemy                     내 에너지·규칙 보기
 * /alchemy value               손에 든 아이템 가치
 * /alchemy search <단어>       근처 연성진 목록을 검색해서 열기
 * /alchemy reload              (관리자) 설정·가치표 다시 읽기
 * /alchemy give <플레이어> <E> (관리자) 에너지 지급(음수면 회수)
 */
public final class AlchemyCommand {
    private AlchemyCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                dispatcher.register(CommandManager.literal("alchemy")
                        .executes(AlchemyCommand::info)
                        .then(CommandManager.literal("value").executes(AlchemyCommand::value))
                        .then(CommandManager.literal("open").executes(ctx -> {
                            ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
                            if (!Jobs.entry(p).truth) { say(p, "진리를 본 자만 연성진 없이 연성할 수 있습니다.", Formatting.RED); return 0; }
                            AlchemyMenu.openPortable(p);
                            return 1;
                        }))
                        .then(CommandManager.literal("search")
                                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(AlchemyCommand::search)))
                        .then(CommandManager.literal("reload")
                                .requires(s -> s.hasPermissionLevel(2))
                                .executes(ctx -> {
                                    String msg = AlchemyValues.reload(ctx.getSource().getServer());
                                    ctx.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), true);
                                    return 1;
                                }))
                        .then(CommandManager.literal("give")
                                .requires(s -> s.hasPermissionLevel(2))
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg())
                                                .executes(AlchemyCommand::give))))));
    }

    public static void registerJob() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                dispatcher.register(CommandManager.literal("job").executes(ctx -> {
                    JobMenu.open(ctx.getSource().getPlayerOrThrow());
                    return 1;
                }).then(JobAdminCommand.build())));
    }

    private static int info(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        AlchemyData.Entry e = AlchemyData.get(p.getServer()).of(p.getUuid());
        say(p, "── 연성 (등가교환) ──", Formatting.LIGHT_PURPLE);
        say(p, "직업: " + Jobs.rankName(e) + "  (/job)", Formatting.GOLD);
        say(p, "에너지: " + Alchemy.fmt(e.energy) + " E · 연성 가능 " + AlchemyValues.knownItems(e).size() + "종", Formatting.WHITE);
        if (AlchemyValues.dailyCap > 0)
            say(p, "오늘 분해: " + Alchemy.fmt(e.gainedToday(Jobs.dailyCap(e))) + " / " + Alchemy.fmt(Jobs.dailyCap(e)) + " E", Formatting.GRAY);
        say(p, "분해: 아이템 → 가치만큼 에너지 (분해해 본 아이템만 연성 가능)", Formatting.GRAY);
        say(p, "연성: 가치 + 손실 " + Math.round((Alchemy.multiplier(p) - 1) * 100) + "% (직업 등급·현자의 돌에 따라 줄어듦)", Formatting.GRAY);
        return 1;
    }

    private static int value(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        ItemStack st = p.getMainHandStack();
        if (st.isEmpty()) { say(p, "손에 아이템을 드세요.", Formatting.RED); return 0; }
        double v = AlchemyValues.valueOf(st.getItem());
        String name = st.getName().getString();
        if (v <= 0) { say(p, name + ": 가치 없음 (분해·연성 불가)", Formatting.RED); return 0; }
        double cost = Alchemy.unitCost(p, st.getItem());
        String problem = AlchemyValues.depositProblem(st);
        say(p, name + "  가치 " + Alchemy.fmt(v) + " E  ·  연성 비용 " + Alchemy.fmt(cost) + " E"
                + (AlchemyValues.canOutput(st.getItem()) ? "" : "  (연성 금지)"), Formatting.AQUA);
        say(p, "들고 있는 " + st.getCount() + "개 분해 시 " + Alchemy.fmt(v * st.getCount()) + " E"
                + (problem != null ? "  — 지금은 불가: " + problem : ""), Formatting.GRAY);
        return 1;
    }

    private static int search(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        String text = StringArgumentType.getString(ctx, "text");
        BlockPos pos = AlchemyMenu.LAST_CIRCLE.get(p.getUuid());
        if (!AlchemyMenu.near(p, pos) && Jobs.entry(p).truth) {
            AlchemyMenu.openPortable(p, text);
            return 1;
        }
        if (!AlchemyMenu.near(p, pos)) {
            say(p, "연성진 근처(8칸 이내)에서 사용하세요.", Formatting.RED);
            return 0;
        }
        AlchemyMenu.open(p, pos, text);
        return 1;
    }

    private static int give(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
        double amount = DoubleArgumentType.getDouble(ctx, "amount");
        AlchemyData data = AlchemyData.get(target.getServer());
        AlchemyData.Entry e = data.of(target.getUuid());
        e.energy = Math.max(0, e.energy + amount);
        data.markDirty();
        ctx.getSource().sendFeedback(() -> Text.literal(target.getName().getString() + " 에너지: " + Alchemy.fmt(e.energy) + " E"), true);
        return 1;
    }

    private static void say(ServerPlayerEntity p, String msg, Formatting f) {
        p.sendMessage(Text.literal(msg).formatted(f), false);
    }
}
