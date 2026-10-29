package bus.muhyeon.duel;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 테스트용 관리자 명령어 (권한 2 이상)
 *   /job admin info <플레이어>                 직업·등급·숙련도·에너지·통행료 보기
 *   /job admin rank <플레이어> <0~5>           등급 바로 설정 (0 = 무직, 5 = 진리를 본 자)
 *   /job admin xp <플레이어> <양>              숙련도 더하기 (음수면 빼기)
 *   /job admin toll <플레이어> arm|leg|heart|none   통행료 바꾸기 (none = 원래 몸으로)
 *   /job admin gate <플레이어> [arm|leg|heart] 진리의 문 연출만 보기 (대가·등급 변화 없음)
 *   /job admin cooldown <플레이어>             현자의 돌·기술 대기시간 초기화
 *   /job admin known <플레이어>                연성 가능한 모든 아이템을 '이해'시킴
 *   /job admin reset <플레이어>                직업·진리·통행료·숙련도 전부 초기화 (에너지는 유지)
 *   /job admin setjob <플레이어> <직업> <등급> 다른 직업으로 바로 설정 (doctor / priest / vampire / leader …)
 *   /job admin points <플레이어> <양>          지금 직업의 등급 지표 더하기 (박사 RP, 신부 명성 등)
 */
public final class JobAdminCommand {
    private JobAdminCommand() {}

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return CommandManager.literal("admin")
                .requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.literal("info").then(player().executes(JobAdminCommand::info)))
                .then(CommandManager.literal("rank").then(player()
                        .then(CommandManager.argument("rank", IntegerArgumentType.integer(0, 5)).executes(JobAdminCommand::rank))))
                .then(CommandManager.literal("xp").then(player()
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg()).executes(JobAdminCommand::xp))))
                .then(CommandManager.literal("toll").then(player()
                        .then(CommandManager.literal("arm").executes(c -> toll(c, "arm")))
                        .then(CommandManager.literal("leg").executes(c -> toll(c, "leg")))
                        .then(CommandManager.literal("heart").executes(c -> toll(c, "heart")))
                        .then(CommandManager.literal("none").executes(c -> toll(c, "")))))
                .then(CommandManager.literal("gate").then(player()
                        .executes(c -> gate(c, null))
                        .then(CommandManager.literal("arm").executes(c -> gate(c, "arm")))
                        .then(CommandManager.literal("leg").executes(c -> gate(c, "leg")))
                        .then(CommandManager.literal("heart").executes(c -> gate(c, "heart")))))
                .then(CommandManager.literal("cooldown").then(player().executes(JobAdminCommand::cooldown)))
                .then(CommandManager.literal("known").then(player().executes(JobAdminCommand::known)))
                .then(CommandManager.literal("reset").then(player().executes(JobAdminCommand::reset)))
                .then(CommandManager.literal("setjob").then(player()
                        .then(CommandManager.argument("job", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((c, b) -> { for (String id : Jobs.TYPES.keySet()) b.suggest(id); return b.buildFuture(); })
                                .then(CommandManager.argument("rank", IntegerArgumentType.integer(1, 9)).executes(JobAdminCommand::setJob)))))
                .then(CommandManager.literal("pillar").then(player()
                        .then(CommandManager.literal("wind").executes(c -> pillar(c, Pillar.WIND)))
                        .then(CommandManager.literal("heat").executes(c -> pillar(c, Pillar.HEAT)))
                        .then(CommandManager.literal("light").executes(c -> pillar(c, Pillar.LIGHT)))
                        .then(CommandManager.literal("absorb").executes(c -> pillar(c, Pillar.ABSORB)))))
                .then(CommandManager.literal("aja").then(player().executes(c -> {
                    ServerPlayerEntity p = target(c);
                    p.getInventory().offerOrDrop(new net.minecraft.item.ItemStack(DuelMod.AJA_STONE));
                    data(p).global.putBoolean("aja.picked", true);
                    data(p).markDirty();
                    ok(c, p.getName().getString() + " 에게 에이자의 적석 (테스트, 이후 자연 등장 없음)");
                    return 1;
                })))
                .then(CommandManager.literal("ultimate").then(player().executes(c -> {
                    ServerPlayerEntity p = target(c);
                    AlchemyData d = data(p);
                    AlchemyData.Entry e = d.of(p.getUuid());
                    if (!Vampire.is(e) || Vampire.stage(e) < 3) { c.getSource().sendError(Text.literal("먼저 /job admin pillar 로 기둥 속 사내로 만드세요")); return 0; }
                    e.num(Vampire.P + "mastery", 100);
                    e.num(Vampire.P + "drink", Math.max(e.num(Vampire.P + "drink"), Ultimate.NEED_DRINK));
                    d.markDirty();
                    Ultimate.complete(p);
                    ok(c, p.getName().getString() + " → 완전생물 (테스트)");
                    return 1;
                })))
                .then(CommandManager.literal("unultimate").executes(c -> {
                    data(c.getSource().getPlayerOrThrow()).global.remove("ultimate");
                    data(c.getSource().getPlayerOrThrow()).markDirty();
                    ok(c, "완전생물 자리 비움 (기록만)");
                    return 1;
                }))
                .then(CommandManager.literal("points").then(player()
                        .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg()).executes(JobAdminCommand::points))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, ?> player() {
        return CommandManager.argument("player", EntityArgumentType.player());
    }

    private static ServerPlayerEntity target(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        return EntityArgumentType.getPlayer(c, "player");
    }

    private static AlchemyData data(ServerPlayerEntity p) { return AlchemyData.get(p.getServer()); }

    private static void ok(CommandContext<ServerCommandSource> c, String msg) {
        c.getSource().sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), true);
    }

    // ───────── 명령 ─────────

    private static int info(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData.Entry e = data(p).of(p.getUuid());
        String name = p.getName().getString();
        ServerCommandSource s = c.getSource();
        s.sendFeedback(() -> Text.literal("── " + name + " ──").formatted(Formatting.GOLD), false);
        s.sendFeedback(() -> Text.literal("직업: " + Jobs.rankName(e) + " (등급 " + Jobs.jobRank(e) + ")  숙련도 " + Alchemy.fmt(e.xp)
                + (Jobs.type(e) != null ? "  " + Jobs.type(e).adminInfo(e) : "")), false);
        s.sendFeedback(() -> Text.literal("에너지 " + Alchemy.fmt(e.energy) + " E · 누적 분해 " + Alchemy.fmt(e.depositedTotal)
                + " E · 오늘 " + Alchemy.fmt(e.gainedToday(Jobs.dailyCap(e))) + " / " + Alchemy.fmt(Jobs.dailyCap(e))), false);
        s.sendFeedback(() -> Text.literal("연성 가능 " + AlchemyValues.knownItems(e).size() + "종 (직접 분해 " + AlchemyValues.depositedKinds(e) + "종) · 통행료: " + Jobs.tollName(e.toll)), false);
        long left = e.lastStone <= 0 ? 0 : e.lastStone + StoneForgeMenu.cooldownDays(e) * 86_400_000L - System.currentTimeMillis();
        s.sendFeedback(() -> Text.literal("현자의 돌 대기: " + (left > 0 ? (left / 3_600_000L) + "시간 남음" : "없음")), false);
        return 1;
    }

    private static int rank(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        int r = IntegerArgumentType.getInteger(c, "rank");
        AlchemyData d = data(p);
        AlchemyData.Entry e = d.of(p.getUuid());
        if (r == 0) {
            e.job = "";
            e.xp = 0;
            e.truth = false;
        } else {
            e.job = Jobs.ALCHEMIST;
            e.truth = r == 5;
            e.xp = Jobs.RANK_XP[Math.min(r, 4)];
        }
        if (!e.truth) { e.toll = ""; e.automail = ""; } // 진리 이전 등급으로 내리면 통행료도 돌려줌
        d.markDirty();
        Jobs.applyToll(p);
        ok(c, p.getName().getString() + " → " + Jobs.rankName(e));
        return 1;
    }

    private static int xp(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData d = data(p);
        AlchemyData.Entry e = d.of(p.getUuid());
        if (!Jobs.isAlchemist(e)) {
            c.getSource().sendError(Text.literal("연금술사가 아닙니다. 먼저 /job admin rank 로 직업을 주세요."));
            return 0;
        }
        double amt = DoubleArgumentType.getDouble(c, "amount");
        if (amt > 0) Jobs.addXp(p, e, amt);
        else e.xp = Math.max(0, e.xp + amt);
        d.markDirty();
        ok(c, p.getName().getString() + " 숙련도 " + Alchemy.fmt(e.xp) + " (" + Jobs.rankName(e) + ")");
        return 1;
    }

    private static int toll(CommandContext<ServerCommandSource> c, String toll) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData d = data(p);
        d.of(p.getUuid()).toll = toll;
        d.markDirty();
        Jobs.applyToll(p);
        ok(c, p.getName().getString() + " 통행료 → " + Jobs.tollName(toll));
        return 1;
    }

    private static int gate(CommandContext<ServerCommandSource> c, String toll) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData.Entry e = data(p).of(p.getUuid());
        String shown = toll != null ? toll : (e.toll.isEmpty() ? "heart" : e.toll);
        // 연출만: 끝나면 Jobs.applyToll 이 '저장된' 통행료를 다시 붙이므로 실제 능력치는 안 바뀜
        TruthGate.begin(p, shown, true);
        ok(c, p.getName().getString() + " 진리의 문 연출 시작 (테스트, 대가 없음)");
        return 1;
    }

    private static int cooldown(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData d = data(p);
        d.of(p.getUuid()).lastStone = 0;
        d.markDirty();
        AlchemySkills.resetCooldown(p.getUuid());
        AutomailSkill.resetCooldown(p.getUuid());
        JobSkills.resetCooldown(p.getUuid());
        Stamina.refill(p);
        ok(c, p.getName().getString() + " 대기시간 초기화 + 스태미나 가득");
        return 1;
    }

    private static int setJob(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        String id = com.mojang.brigadier.arguments.StringArgumentType.getString(c, "job");
        JobType t = Jobs.TYPES.get(id);
        if (t == null) { c.getSource().sendError(Text.literal("없는 직업: " + id + "  (가능: " + String.join(", ", Jobs.TYPES.keySet()) + ")")); return 0; }
        int r = Math.min(t.maxRank(), IntegerArgumentType.getInteger(c, "rank"));
        AlchemyData d = data(p);
        AlchemyData.Entry e = d.of(p.getUuid());
        CombatMode.exit(p, null);
        if (!t.id().equals(e.job)) {
            JobType old = Jobs.type(e);
            if (old != null) { old.onQuit(p, e); e.clearJobData(old.prefix()); }
            e.job = t.id();
            t.onJoin(p, e);
        }
        t.adminRank(p, e, r);
        d.markDirty();
        ok(c, p.getName().getString() + " → " + t.name() + " " + Jobs.rankName(e));
        return 1;
    }

    private static int pillar(CommandContext<ServerCommandSource> c, String route) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData d = data(p);
        AlchemyData.Entry e = d.of(p.getUuid());
        if (!Vampire.is(e)) { e.job = Jobs.VAMPIRE_ID; Vampire.INSTANCE.onJoin(p, e); }
        for (String r : Pillar.ROUTES) if (d.global.getString("route." + r).equals(p.getUuidAsString())) d.global.remove("route." + r);
        e.num(Vampire.P + "rank", 3);
        e.str(Vampire.P + "route", route);
        d.global.putString("route." + route, p.getUuidAsString());
        d.markDirty();
        Pillar.ensureMods(p, e);
        ok(c, p.getName().getString() + " → 기둥 속 사내 (" + Pillar.routeName(route) + ")");
        return 1;
    }

    private static int points(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData d = data(p);
        AlchemyData.Entry e = d.of(p.getUuid());
        JobType t = Jobs.type(e);
        if (t == null) { c.getSource().sendError(Text.literal("연금술사 외 직업이 아닙니다 (연금술사는 xp 사용)")); return 0; }
        t.adminPoints(p, e, DoubleArgumentType.getDouble(c, "amount"));
        d.markDirty();
        ok(c, p.getName().getString() + " " + t.name() + " → " + Jobs.rankName(e) + "  " + t.adminInfo(e));
        return 1;
    }

    private static int known(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData d = data(p);
        AlchemyData.Entry e = d.of(p.getUuid());
        for (var item : AlchemyValues.allValued()) if (AlchemyValues.canOutput(item)) e.known.add(AlchemyValues.id(item));
        d.markDirty();
        ok(c, p.getName().getString() + " 연성 가능 " + AlchemyValues.knownItems(e).size() + "종");
        return 1;
    }

    private static int reset(CommandContext<ServerCommandSource> c) throws CommandSyntaxException {
        ServerPlayerEntity p = target(c);
        AlchemyData d = data(p);
        AlchemyData.Entry e = d.of(p.getUuid());
        CombatMode.exit(p, null);
        JobType t = Jobs.type(e);
        if (t != null) { t.onQuit(p, e); e.clearJobData(t.prefix()); }
        JobSkills.resetCooldown(p.getUuid());
        e.job = "";
        e.xp = 0;
        e.truth = false;
        e.toll = "";
        e.automail = "";
        e.lastStone = 0;
        d.markDirty();
        AlchemySkills.resetCooldown(p.getUuid());
        Jobs.applyToll(p);
        ok(c, p.getName().getString() + " 직업 초기화 (에너지·이해한 아이템은 유지)");
        return 1;
    }
}
