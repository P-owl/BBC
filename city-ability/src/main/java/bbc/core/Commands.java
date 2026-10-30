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
