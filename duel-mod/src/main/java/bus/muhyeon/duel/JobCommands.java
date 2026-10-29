package bus.muhyeon.duel;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 직업별 명령어 (마인크래프트 명령어는 한글을 못 써서 영어)
 *   /doctor ask <박사>      내 파티 포켓몬 연구를 근처 박사에게 맡김
 *   /support <당대표>       지지
 *   /party ...              당 (create / invite / join / leave / donate / pay / merge / info)
 *   /president ...          대통령 (accept / refuse / claim / unclaim / name / allow / disallow / tp / biome / order / wanted / pardon)
 *   /martiallaw             비상계엄 해제 투표
 */
public final class JobCommands {
    private JobCommands() {}

    /** 오류 메시지가 있으면 빨갛게, 없으면 성공 메시지 */
    static int run(CommandContext<ServerCommandSource> c, String err, String ok) {
        if (err != null) { c.getSource().sendError(Text.literal(err)); return 0; }
        if (ok != null) c.getSource().sendFeedback(() -> Text.literal(ok).formatted(Formatting.GREEN), false);
        return 1;
    }

    static int allow(CommandContext<ServerCommandSource> c, boolean add) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        ServerPlayerEntity t = EntityArgumentType.getPlayer(c, "player");
        if (!Politics.isPresident(p.getServer(), p.getUuid())) return run(c, "대통령만", null);
        var terr = Politics.G(p.getServer()).getCompound("terr");
        var l = terr.getList("allow", net.minecraft.nbt.NbtElement.STRING_TYPE);
        for (int i = l.size() - 1; i >= 0; i--) if (l.getString(i).equals(t.getUuidAsString())) l.remove(i);
        if (add) l.add(net.minecraft.nbt.NbtString.of(t.getUuidAsString()));
        terr.put("allow", l);
        Politics.G(p.getServer()).put("terr", terr);
        Politics.dirty(p.getServer());
        return run(c, null, t.getName().getString() + (add ? " 영토 허가" : " 허가 취소"));
    }

    static void partyInfo(ServerPlayerEntity p) {
        var s = p.getServer();
        java.util.UUID l = Politics.partyOf(s, p.getUuid());
        if (l == null) { p.sendMessage(Text.literal("당에 소속되어 있지 않습니다").formatted(Formatting.GRAY), false); return; }
        var c = Politics.G(s).getCompound("party." + l);
        p.sendMessage(Text.literal("── [" + c.getString("name") + "] ──").formatted(Formatting.AQUA), false);
        var e = AlchemyData.get(s).of(l);
        p.sendMessage(Text.literal("당대표 등급 " + Politics.rankName(e) + " · 지지 점수 " + Politics.score(s, l) + " · 당원 " + Politics.members(s, l).size() + "명 · 자금 " + Money.fmt((long) e.num(Politics.P + "fund"))).formatted(Formatting.WHITE), false);
        String pl = Politics.activePledge(s, p.getUuid());
        if (!pl.isEmpty()) p.sendMessage(Text.literal("오늘의 공약: " + Politics.pledgeName(pl)).formatted(Formatting.YELLOW), false);
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> {
            d.register(CommandManager.literal("doctor")
                    .then(CommandManager.literal("ask").then(CommandManager.argument("doctor", EntityArgumentType.player()).executes(c -> {
                        ServerPlayerEntity me = c.getSource().getPlayerOrThrow();
                        ServerPlayerEntity doc = EntityArgumentType.getPlayer(c, "doctor");
                        String err = PokemonDoctor.request(me, doc);
                        if (err != null) { c.getSource().sendError(Text.literal(err)); return 0; }
                        me.sendMessage(Text.literal("🔬 " + doc.getName().getString() + " 박사에게 연구를 맡겼습니다 (2분 동안 유효, 근처에 계세요)").formatted(Formatting.AQUA), false);
                        return 1;
                    }))));

            d.register(CommandManager.literal("support").then(CommandManager.argument("leader", StringArgumentType.word())
                    .suggests((c, b) -> { for (var pl : c.getSource().getServer().getPlayerManager().getPlayerList()) if (Politics.is(Jobs.entry(pl))) b.suggest(pl.getName().getString()); return b.buildFuture(); })
                    .executes(c -> run(c, Politics.support(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "leader")), "📣 지지했습니다"))));

            d.register(CommandManager.literal("party")
                    .then(CommandManager.literal("create").then(CommandManager.argument("name", StringArgumentType.string())
                            .then(CommandManager.argument("color", StringArgumentType.word())
                                    .suggests((c, b) -> { for (String col : new String[]{"red", "blue", "green", "gold", "aqua", "light_purple", "yellow", "white", "dark_red", "dark_blue", "dark_green", "dark_aqua", "dark_purple", "gray"}) b.suggest(col); return b.buildFuture(); })
                                    .executes(c -> run(c, Politics.createParty(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "color")), null)))))
                    .then(CommandManager.literal("invite").then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(c -> run(c, Politics.invite(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player")), "초대했습니다"))))
                    .then(CommandManager.literal("join").then(CommandManager.argument("leader", StringArgumentType.word())
                            .executes(c -> run(c, Politics.join(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "leader")), "입당했습니다"))))
                    .then(CommandManager.literal("leave").executes(c -> run(c, Politics.leave(c.getSource().getPlayerOrThrow()), "탈당했습니다")))
                    .then(CommandManager.literal("donate").then(CommandManager.argument("amount", LongArgumentType.longArg(1))
                            .executes(c -> run(c, Politics.donate(c.getSource().getPlayerOrThrow(), LongArgumentType.getLong(c, "amount")), "당 자금을 냈습니다"))))
                    .then(CommandManager.literal("pay").then(CommandManager.argument("player", EntityArgumentType.player()).then(CommandManager.argument("amount", LongArgumentType.longArg(1))
                            .executes(c -> run(c, Politics.pay(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player"), LongArgumentType.getLong(c, "amount")), "지급했습니다")))))
                    .then(CommandManager.literal("merge")
                            .then(CommandManager.literal("accept").executes(c -> run(c, Politics.acceptMerge(c.getSource().getPlayerOrThrow()), null)))
                            .then(CommandManager.argument("leader", EntityArgumentType.player())
                                    .executes(c -> run(c, Politics.merge(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "leader")), "합당을 제안했습니다"))))
                    .then(CommandManager.literal("info").executes(c -> { partyInfo(c.getSource().getPlayerOrThrow()); return 1; })));

            d.register(CommandManager.literal("president")
                    .then(CommandManager.literal("accept").executes(c -> run(c, Politics.answer(c.getSource().getPlayerOrThrow(), true), null)))
                    .then(CommandManager.literal("refuse").executes(c -> run(c, Politics.answer(c.getSource().getPlayerOrThrow(), false), null)))
                    .then(CommandManager.literal("claim").executes(c -> run(c, Politics.claim(c.getSource().getPlayerOrThrow(), true), null)))
                    .then(CommandManager.literal("unclaim").executes(c -> run(c, Politics.claim(c.getSource().getPlayerOrThrow(), false), null)))
                    .then(CommandManager.literal("name").then(CommandManager.argument("name", StringArgumentType.greedyString()).executes(c -> {
                        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                        if (!Politics.isPresident(p.getServer(), p.getUuid())) return run(c, "대통령만", null);
                        var t = Politics.G(p.getServer()).getCompound("terr");
                        t.putString("name", StringArgumentType.getString(c, "name"));
                        Politics.G(p.getServer()).put("terr", t);
                        Politics.dirty(p.getServer());
                        return run(c, null, "영토 이름을 바꿨습니다");
                    })))
                    .then(CommandManager.literal("allow").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> allow(c, true))))
                    .then(CommandManager.literal("disallow").then(CommandManager.argument("player", EntityArgumentType.player()).executes(c -> allow(c, false))))
                    .then(CommandManager.literal("tp").then(CommandManager.argument("x", IntegerArgumentType.integer()).then(CommandManager.argument("z", IntegerArgumentType.integer())
                            .executes(c -> run(c, Politics.transport(c.getSource().getPlayerOrThrow(), null, IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z")), null)))))
                    .then(CommandManager.literal("biome").then(CommandManager.argument("biome", StringArgumentType.greedyString())
                            .suggests((c, b) -> { c.getSource().getServer().getRegistryManager().get(net.minecraft.registry.RegistryKeys.BIOME).getIds().forEach(id -> b.suggest(id.toString())); return b.buildFuture(); })
                            .executes(c -> run(c, Politics.transport(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "biome"), null, null), null))))
                    .then(CommandManager.literal("order").then(CommandManager.argument("kind", StringArgumentType.word())
                            .suggests((c, b) -> { for (String k : Politics.ORDERS) b.suggest(k); return b.buildFuture(); })
                            .executes(c -> run(c, Politics.order(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "kind")), null))))
                    .then(CommandManager.literal("wanted").then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(c -> run(c, Politics.wantedOrder(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player")), null))))
                    .then(CommandManager.literal("pardon").then(CommandManager.argument("player", EntityArgumentType.player())
                            .executes(c -> run(c, Politics.pardon(c.getSource().getPlayerOrThrow(), EntityArgumentType.getPlayer(c, "player")), null)))));

            d.register(CommandManager.literal("martiallaw").executes(c -> run(c, Politics.voteLift(c.getSource().getPlayerOrThrow()), null)));
        });
    }
}
