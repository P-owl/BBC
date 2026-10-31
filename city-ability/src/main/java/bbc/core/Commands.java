package bbc.core;

import bbc.combat.CombatMode;
import bbc.job.JobType;
import bbc.job.Jobs;
import bbc.job.SkillMenu;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * /bbc skills                      기술 장착 창
 * /bbc grow                        성장 창 (키: K)
 * /bbc admin ep <플레이어> <양>      존재치 주기 · admin stat <플레이어> <스탯> <점> 확정 대기 점수 주기 · admin tool <스탯> [개수] 수련 도구
 * /bbc combat                      전투 모드 켜기/끄기 (키: `)
 * /bbc info [플레이어]             직업·등급 보기
 * /bbc admin job <플레이어> <직업>  직업 주기 (관리자)
 * /bbc admin quit <플레이어>        직업 없애기 (관리자)
 * /bbc admin rank <플레이어> <등급> 등급 바꾸기 (관리자)
 * /bbc admin dummy               콤보 연습용 허수아비 소환 (관리자)
 * /bbc admin opponent <단계>      전투 AI 연습 상대 소환: grunt(하급) / skilled(숙련) / boss(보스)
 */
public final class Commands {
    private Commands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("bbc")
                .then(CommandManager.literal("skills").executes(c -> { SkillMenu.open(c.getSource().getPlayerOrThrow()); return 1; }))
                .then(CommandManager.literal("grow").executes(c -> { bbc.grow.GrowthNet.open(c.getSource().getPlayerOrThrow()); return 1; }))
                .then(CommandManager.literal("combat").executes(c -> { CombatMode.toggle(c.getSource().getPlayerOrThrow()); return 1; }))
                .then(CommandManager.literal("info")
                        .executes(c -> info(c, c.getSource().getPlayerOrThrow()))
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(c -> info(c, EntityArgumentType.getPlayer(c, "player")))))
                .then(CommandManager.literal("admin").requires(s -> s.hasPermissionLevel(2))
                        .then(CommandManager.literal("job")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("job", StringArgumentType.word())
                                                .suggests((c, b) -> { Jobs.all().forEach(t -> b.suggest(t.id())); return b.buildFuture(); })
                                                .executes(Commands::setJob))))
                        .then(CommandManager.literal("quit")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(c -> { ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player"); CombatMode.set(p, false); Jobs.quit(p); ok(c, p.getName().getString() + " 직업 없앰"); return 1; })))
                        .then(CommandManager.literal("ep")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 1000000))
                                                .executes(c -> { ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player"); bbc.grow.Growth.gainEp(p, IntegerArgumentType.getInteger(c, "amount"), "관리자"); ok(c, "존재치 지급"); return 1; }))))
                        .then(CommandManager.literal("stat")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("stat", StringArgumentType.word())
                                                .suggests((c, b) -> { for (bbc.grow.Stat s : bbc.grow.Stat.values()) b.suggest(s.id); return b.buildFuture(); })
                                                .then(CommandManager.argument("points", IntegerArgumentType.integer(1, 100))
                                                        .executes(Commands::giveStat)))))
                        .then(CommandManager.literal("tool")
                                .then(CommandManager.argument("stat", StringArgumentType.word())
                                        .suggests((c, b) -> { for (bbc.grow.Stat s : bbc.grow.Stat.values()) b.suggest(s.id); return b.buildFuture(); })
                                        .executes(c -> giveTool(c, 16))
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 64)).executes(c -> giveTool(c, IntegerArgumentType.getInteger(c, "count"))))))
                        .then(CommandManager.literal("dummy").executes(c -> { bbc.combat.Combat.spawnDummy(c.getSource().getPlayerOrThrow()); return 1; }))
                        .then(CommandManager.literal("opponent")
                                .then(CommandManager.literal("grunt").executes(c -> { bbc.combat.MobAI.spawnOpponent(c.getSource().getPlayerOrThrow(), bbc.combat.MobAI.Tier.GRUNT); return 1; }))
                                .then(CommandManager.literal("skilled").executes(c -> { bbc.combat.MobAI.spawnOpponent(c.getSource().getPlayerOrThrow(), bbc.combat.MobAI.Tier.SKILLED); return 1; }))
                                .then(CommandManager.literal("boss").executes(c -> { bbc.combat.MobAI.spawnOpponent(c.getSource().getPlayerOrThrow(), bbc.combat.MobAI.Tier.BOSS); return 1; })))
                        .then(CommandManager.literal("rank")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("rank", IntegerArgumentType.integer(1, 99))
                                                .executes(Commands::setRank)))))));
    }

    private static int info(CommandContext<ServerCommandSource> c, ServerPlayerEntity p) {
        PlayerData.Entry e = Jobs.entry(p);
        JobType t = Jobs.type(e);
        ok(c, p.getName().getString() + ": " + (t == null ? "무직" : t.name() + " · " + t.rankName(Jobs.rank(e)) + " (등급 " + Jobs.rank(e) + ")"));
        return 1;
    }

    private static int giveStat(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
        bbc.grow.Stat s = bbc.grow.Stat.byId(StringArgumentType.getString(c, "stat"));
        if (s == null) { c.getSource().sendError(Text.literal("없는 스탯")); return 0; }
        int n = IntegerArgumentType.getInteger(c, "points");
        PlayerData.Entry e = Jobs.entry(p);
        e.addNum("g.p." + s.id, n);
        Jobs.dirty(p);
        ok(c, p.getName().getString() + " " + s.name + " 확정 대기 +" + n);
        return 1;
    }

    private static int giveTool(CommandContext<ServerCommandSource> c, int count) throws CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
        bbc.grow.Stat s = bbc.grow.Stat.byId(StringArgumentType.getString(c, "stat"));
        if (s == null) { c.getSource().sendError(Text.literal("없는 스탯")); return 0; }
        p.giveItemStack(bbc.grow.GrowthEvents.tool(s, count));
        return 1;
    }

    private static int setJob(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
        JobType t = Jobs.byId(StringArgumentType.getString(c, "job"));
        if (t == null) { c.getSource().sendError(Text.literal("없는 직업입니다")); return 0; }
        CombatMode.set(p, false);
        Jobs.set(p, t);
        ok(c, p.getName().getString() + " → " + t.name());
        return 1;
    }

    private static int setRank(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = EntityArgumentType.getPlayer(c, "player");
        PlayerData.Entry e = Jobs.entry(p);
        JobType t = Jobs.type(e);
        if (t == null) { c.getSource().sendError(Text.literal("직업이 없습니다")); return 0; }
        int r = Math.min(t.maxRank(), IntegerArgumentType.getInteger(c, "rank"));
        e.num(t.prefix() + "rank", r);
        Jobs.dirty(p);
        ok(c, p.getName().getString() + " 등급 " + r);
        return 1;
    }

    private static void ok(CommandContext<ServerCommandSource> c, String m) {
        c.getSource().sendFeedback(() -> Text.literal(m).formatted(Formatting.GREEN), true);
    }
}
