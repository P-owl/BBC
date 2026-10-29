package bus.muhyeon.duel;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.ErroredBattleStart;
import com.cobblemon.mod.common.battles.SuccessfulBattleStart;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtLong;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 당대표 → 대통령.
 * 지지 점수 = 플레이어 지지자×10 + 주민 지지자×2(최대 60명) − 벌점.  (비율 조건 없음 — 소규모 서버 기준)
 *   1 창당인    입문
 *   2 지역위원장 지지 점수 20
 *   3 원내대표  50 + 포켓몬 배틀 10승
 *   4 당대표    100 + 플레이어 지지자 2명
 *   5 대선 후보 150 + 플레이어 지지자 3명 + 당 자금 200,000 코블달러
 * 대통령은 서버에 1명 (당대표 5등급 + 대통령 자리)
 */
public final class Politics implements JobType {
    public static final Politics INSTANCE = new Politics();
    private Politics() {}

    public static final String P = "pol.";
    static final String[] RANKS = {"무직", "창당인", "지역위원장", "원내대표", "당대표", "대선 후보"};
    static final int[] NEED_SCORE = {0, 0, 20, 50, 100, 150};
    static final int[] NEED_PLAYERS = {0, 0, 0, 0, 2, 3};
    public static final int NEED_WINS = 10, MAX_VILLAGERS = 60;
    public static final long NEED_FUND = 200_000, FOUND_PRICE = 10_000;

    // ───────── JobType ─────────
    @Override public String id() { return Jobs.LEADER_ID; }
    @Override public String name() { return "당대표"; }
    @Override public String prefix() { return P; }
    @Override public Item icon() { return Items.GOAT_HORN; }
    @Override public Formatting color() { return Formatting.BLUE; }
    @Override public int maxRank() { return 5; }
    @Override public String rankName(int r) { return RANKS[Math.max(0, Math.min(5, r))]; }
    @Override public String[] summary() {
        return new String[]{"지지자를 모아 당을 키우는 정치인", "플레이어와 주민의 지지로 등급이 오름", "대선 후보가 되면 대통령에 도전"};
    }
    @Override public void openDetail(ServerPlayerEntity p) { PoliticsMenu.Detail.open(p); }
    @Override public void openStatus(ServerPlayerEntity p) { PoliticsMenu.Status.open(p); }
    @Override public JobSkills.Set skills() { return SKILLS; }
    @Override public void onJoin(ServerPlayerEntity p, AlchemyData.Entry e) { e.num(P + "rank", 1); }
    @Override public void onQuit(ServerPlayerEntity p, AlchemyData.Entry e) {
        MinecraftServer s = p.getServer();
        if (isPresident(s, p.getUuid())) vacate(s, "대통령이 직업을 내려놓음");
        dissolve(s, p.getUuid());
    }
    @Override public void adminRank(ServerPlayerEntity p, AlchemyData.Entry e, int r) { e.num(P + "rank", r); }
    @Override public void adminPoints(ServerPlayerEntity p, AlchemyData.Entry e, double amount) { e.addNum(P + "malus", -amount); }
    @Override public String adminInfo(AlchemyData.Entry e) {
        return "점수 보정 " + (int) -e.num(P + "malus") + " · 주민 " + villagers(e) + " · 승리 " + (int) e.num(P + "wins") + " · 자금 " + (long) e.num(P + "fund");
    }

    public static boolean is(AlchemyData.Entry e) { return Jobs.LEADER_ID.equals(e.job); }
    static String rankName(AlchemyData.Entry e) { return RANKS[Math.max(0, Math.min(5, rank(e)))]; }
    public static int rank(AlchemyData.Entry e) { return is(e) ? (int) e.num(P + "rank") : 0; }

    static NbtCompound G(MinecraftServer s) { return AlchemyData.get(s).global; }
    static void dirty(MinecraftServer s) { AlchemyData.get(s).markDirty(); }
    static long now() { return System.currentTimeMillis(); }
    static long today() { return PokemonDoctor.today(); }

    // ═════════ 지지 ═════════

    public static int playerSupporters(MinecraftServer s, UUID leader) {
        int n = 0;
        String id = leader.toString();
        NbtCompound g = G(s);
        for (String k : g.getKeys()) if (k.startsWith("sup.") && g.getString(k).equals(id) && !k.equals("sup." + id)) n++;
        return n;
    }

    static int villagers(AlchemyData.Entry e) { return Math.min(MAX_VILLAGERS, e.jd.getList(P + "vil", NbtElement.STRING_TYPE).size()); }

    public static int score(MinecraftServer s, UUID leader) {
        AlchemyData.Entry e = AlchemyData.get(s).of(leader);
        return (int) Math.round(playerSupporters(s, leader) * 10 + villagers(e) * 2 - e.num(P + "malus"));
    }

    /** 플레이어가 당대표를 지지 */
    public static String support(ServerPlayerEntity voter, String leaderName) {
        MinecraftServer s = voter.getServer();
        ServerPlayerEntity leader = s.getPlayerManager().getPlayer(leaderName);
        UUID lid = leader != null ? leader.getUuid() : null;
        if (lid == null) return "접속 중인 당대표만 지지할 수 있습니다";
        if (!is(Jobs.entry(leader))) return leaderName + "님은 당대표가 아닙니다";
        if (lid.equals(voter.getUuid())) return "자기 자신은 지지할 수 없습니다";
        int played = voter.getStatHandler().getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME));
        if (played < 3 * 72_000) return "플레이 시간 3시간 이상부터 지지할 수 있습니다";
        NbtCompound g = G(s);
        String key = "sup." + voter.getUuid();
        if (g.getString(key).equals(lid.toString())) return "이미 지지하고 있습니다";
        long last = g.getLong("supt." + voter.getUuid());
        if (!g.getString(key).isEmpty() && now() - last < 7 * 86_400_000L) return "지지는 7일에 한 번만 바꿀 수 있습니다 (" + ((last + 7 * 86_400_000L - now()) / 3_600_000L + 1) + "시간 남음)";
        g.putString(key, lid.toString());
        g.putLong("supt." + voter.getUuid(), now());
        dirty(s);
        leader.sendMessage(Text.literal("📣 " + voter.getName().getString() + "님이 당신을 지지합니다! (지지 점수 " + score(s, lid) + ")").formatted(Formatting.AQUA), false);
        checkRank(leader, Jobs.entry(leader));
        return null;
    }

    /** 주민 지지자 추가 (웅크린 채 에메랄드를 들고 주민 우클릭) */
    static ActionResult recruitVillager(ServerPlayerEntity p, VillagerEntity v) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (!is(e)) return ActionResult.PASS;
        String tag = "duel_vsup:" + p.getUuid();
        if (v.getCommandTags().contains(tag)) { p.sendMessage(Text.literal("이미 당신을 지지하는 주민입니다").formatted(Formatting.GRAY), true); return ActionResult.SUCCESS; }
        if (villagers(e) >= MAX_VILLAGERS) { p.sendMessage(Text.literal("주민 지지자는 " + MAX_VILLAGERS + "명까지입니다").formatted(Formatting.RED), true); return ActionResult.SUCCESS; }
        int cost = e.time(P + "speech") > now() ? 3 : 5;
        if (Jobs.count(p, Items.EMERALD) < cost) { p.sendMessage(Text.literal("에메랄드 " + cost + "개가 필요합니다").formatted(Formatting.RED), true); return ActionResult.SUCCESS; }
        Jobs.take(p, Items.EMERALD, cost);
        // 다른 당대표 지지자였으면 빼 옴
        for (String t : new ArrayList<>(v.getCommandTags())) {
            if (!t.startsWith("duel_vsup:")) continue;
            v.removeCommandTag(t);
            try { removeVillager(p.getServer(), UUID.fromString(t.substring(10)), v.getUuid()); } catch (IllegalArgumentException ignored) {}
        }
        v.addCommandTag(tag);
        NbtList l = e.jd.getList(P + "vil", NbtElement.STRING_TYPE);
        l.add(NbtString.of(v.getUuidAsString()));
        e.jd.put(P + "vil", l);
        dirty(p.getServer());
        ServerWorld w = p.getServerWorld();
        w.spawnParticles(ParticleTypes.HAPPY_VILLAGER, v.getX(), v.getY() + 2, v.getZ(), 15, 0.3, 0.3, 0.3, 0);
        w.playSound(null, v.getBlockPos(), SoundEvents.ENTITY_VILLAGER_YES, SoundCategory.NEUTRAL, 1f, 1f);
        p.sendMessage(Text.literal("📣 주민이 지지를 약속했다 (주민 " + villagers(e) + "명 · 지지 점수 " + score(p.getServer(), p.getUuid()) + ")").formatted(Formatting.AQUA), true);
        checkRank(p, e);
        return ActionResult.SUCCESS;
    }

    static void removeVillager(MinecraftServer s, UUID leader, UUID villager) {
        AlchemyData.Entry e = AlchemyData.get(s).of(leader);
        NbtList l = e.jd.getList(P + "vil", NbtElement.STRING_TYPE);
        for (int i = 0; i < l.size(); i++) if (l.getString(i).equals(villager.toString())) { l.remove(i); break; }
        e.jd.put(P + "vil", l);
        dirty(s);
    }

    /** 벌점 (지지 점수 차감). 주민 지지자가 먼저 떠나고, 모자라면 벌점으로 남음 */
    static void penalty(MinecraftServer s, UUID leader, int points, String why) {
        AlchemyData.Entry e = AlchemyData.get(s).of(leader);
        NbtList l = e.jd.getList(P + "vil", NbtElement.STRING_TYPE);
        int leave = Math.min(l.size(), points / 2);
        for (int i = 0; i < leave; i++) l.remove(l.size() - 1);
        e.jd.put(P + "vil", l);
        int rest = points - leave * 2;
        if (rest > 0) e.addNum(P + "malus", rest);
        dirty(s);
        ServerPlayerEntity p = s.getPlayerManager().getPlayer(leader);
        if (p != null) p.sendMessage(Text.literal("📉 지지 점수 -" + points + " (" + why + ")" + (leave > 0 ? " · 주민 " + leave + "명이 등을 돌림" : "")).formatted(Formatting.RED), false);
    }

    static void checkRank(ServerPlayerEntity p, AlchemyData.Entry e) {
        if (!is(e)) return;
        MinecraftServer s = p.getServer();
        int r = rank(e);
        while (r < 5) {
            int n = r + 1;
            if (score(s, p.getUuid()) < NEED_SCORE[n] || playerSupporters(s, p.getUuid()) < NEED_PLAYERS[n]) break;
            if (n == 3 && e.num(P + "wins") < NEED_WINS) break;
            if (n == 5 && e.num(P + "fund") < NEED_FUND) break;
            if (n == 5 && e.time(P + "ban") > now()) break;
            r = n;
            e.num(P + "rank", r);
            Jobs.announceRank(p, "당대표", RANKS[r], r >= 4);
        }
        dirty(s);
    }

    // ═════════ 당 ═════════

    public static UUID partyOf(MinecraftServer s, UUID player) {
        String l = G(s).getString("pm." + player);
        if (!l.isEmpty()) return UUID.fromString(l);
        return G(s).contains("party." + player) ? player : null;
    }

    public static boolean sameParty(MinecraftServer s, UUID a, UUID b) {
        UUID pa = partyOf(s, a);
        return pa != null && pa.equals(partyOf(s, b));
    }

    static List<UUID> members(MinecraftServer s, UUID leader) {
        List<UUID> l = new ArrayList<>();
        NbtCompound g = G(s);
        for (String k : g.getKeys()) if (k.startsWith("pm.") && g.getString(k).equals(leader.toString())) l.add(UUID.fromString(k.substring(3)));
        return l;
    }

    static int memberCap(int rank) { return rank <= 1 ? 3 : rank == 2 ? 10 : 30; }

    static String createParty(ServerPlayerEntity p, String name, String color) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (!is(e)) return "당대표만 창당할 수 있습니다";
        MinecraftServer s = p.getServer();
        if (G(s).contains("party." + p.getUuid())) return "이미 당이 있습니다";
        if (name.length() < 1 || name.length() > 12) return "당 이름은 1~12자";
        Formatting f = Formatting.byName(color);
        if (f == null || !f.isColor()) return "색은 red, blue, green, gold, aqua, light_purple, yellow, white … 중 하나";
        NbtCompound c = new NbtCompound();
        c.putString("name", name);
        c.putString("color", f.getName());
        G(s).put("party." + p.getUuid(), c);
        dirty(s);
        syncTeam(s, p.getUuid());
        s.getPlayerManager().broadcast(Text.literal("📣 " + p.getName().getString() + "님이 ").formatted(Formatting.AQUA)
                .append(Text.literal("[" + name + "]").formatted(f, Formatting.BOLD)).append(Text.literal("을(를) 창당했습니다").formatted(Formatting.AQUA)), false);
        return null;
    }

    static final Map<UUID, UUID> INVITES = new HashMap<>();

    static String invite(ServerPlayerEntity leader, ServerPlayerEntity target) {
        MinecraftServer s = leader.getServer();
        if (!G(s).contains("party." + leader.getUuid())) return "먼저 /party create 로 창당하세요";
        if (partyOf(s, target.getUuid()) != null) return target.getName().getString() + "님은 이미 다른 당 소속입니다";
        if (members(s, leader.getUuid()).size() >= memberCap(rank(Jobs.entry(leader)))) return "당원 수가 꽉 찼습니다 (등급이 오르면 늘어남)";
        INVITES.put(target.getUuid(), leader.getUuid());
        NbtCompound c = G(s).getCompound("party." + leader.getUuid());
        target.sendMessage(Text.literal("📣 " + leader.getName().getString() + "님이 [" + c.getString("name") + "]에 초대했습니다 ").formatted(Formatting.AQUA)
                .append(button("[입당]", "/party join " + leader.getName().getString(), Formatting.GREEN)), false);
        return null;
    }

    static String join(ServerPlayerEntity p, String leaderName) {
        MinecraftServer s = p.getServer();
        ServerPlayerEntity leader = s.getPlayerManager().getPlayer(leaderName);
        if (leader == null || !leader.getUuid().equals(INVITES.get(p.getUuid()))) return "초대받지 않았습니다";
        INVITES.remove(p.getUuid());
        G(s).putString("pm." + p.getUuid(), leader.getUuidAsString());
        dirty(s);
        syncTeam(s, leader.getUuid());
        leader.sendMessage(Text.literal("📣 " + p.getName().getString() + "님이 입당했습니다").formatted(Formatting.AQUA), false);
        return null;
    }

    static String leave(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        String l = G(s).getString("pm." + p.getUuid());
        if (l.isEmpty()) return "당원이 아닙니다 (당대표는 직업을 그만두면 당이 해산됩니다)";
        G(s).remove("pm." + p.getUuid());
        dirty(s);
        Team t = s.getScoreboard().getScoreHolderTeam(p.getNameForScoreboard());
        if (t != null && t.getName().startsWith("duelp_")) s.getScoreboard().removeScoreHolderFromTeam(p.getNameForScoreboard(), t);
        return null;
    }

    static void dissolve(MinecraftServer s, UUID leader) {
        NbtCompound g = G(s);
        for (UUID m : members(s, leader)) g.remove("pm." + m);
        g.remove("party." + leader);
        g.remove("pledge." + leader);
        Team t = s.getScoreboard().getTeam(teamName(leader));
        if (t != null) s.getScoreboard().removeTeam(t);
        dirty(s);
    }

    static String teamName(UUID leader) { return "duelp_" + leader.toString().substring(0, 8); }

    /** 당 태그를 이름표 앞에 (스코어보드 팀) */
    static void syncTeam(MinecraftServer s, UUID leader) {
        NbtCompound c = G(s).getCompound("party." + leader);
        if (c.isEmpty()) return;
        Scoreboard sb = s.getScoreboard();
        Team t = sb.getTeam(teamName(leader));
        if (t == null) t = sb.addTeam(teamName(leader));
        Formatting f = Formatting.byName(c.getString("color"));
        t.setPrefix(Text.literal("[" + c.getString("name") + "] ").formatted(f == null ? Formatting.WHITE : f));
        t.setFriendlyFireAllowed(false);
        List<UUID> all = new ArrayList<>(members(s, leader));
        all.add(leader);
        for (UUID id : all) {
            ServerPlayerEntity m = s.getPlayerManager().getPlayer(id);
            if (m != null) sb.addScoreHolderToTeam(m.getNameForScoreboard(), t);
        }
    }

    static String donate(ServerPlayerEntity p, long amount) {
        MinecraftServer s = p.getServer();
        UUID leader = partyOf(s, p.getUuid());
        if (leader == null) return "당에 소속되어 있지 않습니다";
        if (amount <= 0) return "금액을 확인하세요";
        if (!Money.take(p, amount)) return "코블달러가 부족합니다";
        AlchemyData.get(s).of(leader).addNum(P + "fund", amount);
        dirty(s);
        ServerPlayerEntity l = s.getPlayerManager().getPlayer(leader);
        if (l != null) {
            l.sendMessage(Text.literal("💰 " + p.getName().getString() + "님이 당 자금 " + Money.fmt(amount) + "을(를) 냈습니다").formatted(Formatting.GOLD), false);
            checkRank(l, Jobs.entry(l));
        }
        return null;
    }

    static String pay(ServerPlayerEntity leader, ServerPlayerEntity to, long amount) {
        MinecraftServer s = leader.getServer();
        AlchemyData.Entry e = Jobs.entry(leader);
        if (rank(e) < 4) return "정치 자금은 당대표(등급 4)부터";
        if (!leader.getUuid().equals(partyOf(s, to.getUuid()))) return "우리 당원에게만 줄 수 있습니다";
        if (amount <= 0 || e.num(P + "fund") < amount) return "당 자금이 부족합니다";
        e.addNum(P + "fund", -amount);
        Money.give(to, amount);
        dirty(s);
        to.sendMessage(Text.literal("💰 당에서 정치 자금 " + Money.fmt(amount) + "을(를) 받았습니다").formatted(Formatting.GOLD), false);
        return null;
    }

    static final Map<UUID, UUID> MERGE = new HashMap<>();

    /** 합당: a 가 b 에게 제안 → b 가 수락하면 b 의 당원·지지자가 a 에게 넘어가고 b 는 부대표(당원) */
    static String merge(ServerPlayerEntity a, ServerPlayerEntity b) {
        if (rank(Jobs.entry(a)) < 4 || rank(Jobs.entry(b)) < 4) return "합당은 두 사람 모두 당대표(등급 4) 이상이어야 합니다";
        MERGE.put(b.getUuid(), a.getUuid());
        b.sendMessage(Text.literal("📣 " + a.getName().getString() + "님이 합당을 제안했습니다 (당신의 당원·지지자가 넘어가고 부대표가 됨) ").formatted(Formatting.AQUA)
                .append(button("[수락]", "/party merge accept", Formatting.GREEN)), false);
        return null;
    }

    static String acceptMerge(ServerPlayerEntity b) {
        UUID a = MERGE.remove(b.getUuid());
        MinecraftServer s = b.getServer();
        if (a == null || s.getPlayerManager().getPlayer(a) == null) return "받은 합당 제안이 없습니다";
        NbtCompound g = G(s);
        for (UUID m : members(s, b.getUuid())) g.putString("pm." + m, a.toString());
        for (String k : new ArrayList<>(g.getKeys())) if (k.startsWith("sup.") && g.getString(k).equals(b.getUuidAsString())) g.putString(k, a.toString());
        g.remove("party." + b.getUuid());
        Team t = s.getScoreboard().getTeam(teamName(b.getUuid()));
        if (t != null) s.getScoreboard().removeTeam(t);
        g.putString("pm." + b.getUuid(), a.toString());
        g.putBoolean("vice." + b.getUuid(), true);
        dirty(s);
        syncTeam(s, a);
        s.getPlayerManager().broadcast(Text.literal("📣 " + b.getName().getString() + "의 당이 " + s.getPlayerManager().getPlayer(a).getName().getString() + "의 당에 합쳐졌습니다").formatted(Formatting.AQUA), false);
        return null;
    }

    static MutableText button(String label, String cmd, Formatting f) {
        return Text.literal(label).formatted(f, Formatting.BOLD).styled(st -> st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(cmd))));
    }

    // ═════════ 공약 (하루 한 가지, 당원 전체) ═════════

    public static final String[] PLEDGES = {"catch", "exp", "speed"};
    static String pledgeName(String k) {
        return switch (k) { case "catch" -> "포획률 +5%"; case "exp" -> "포켓몬 경험치 +10%"; case "speed" -> "신속 I"; default -> "-"; };
    }

    static String activePledge(MinecraftServer s, UUID player) {
        UUID l = partyOf(s, player);
        if (l == null) return "";
        NbtCompound g = G(s);
        return g.getLong("pledged." + l) == today() ? g.getString("pledge." + l) : "";
    }

    static String pledge(ServerPlayerEntity p, String kind) {
        MinecraftServer s = p.getServer();
        if (rank(Jobs.entry(p)) < 3) return "공약은 원내대표(등급 3)부터";
        if (!G(s).contains("party." + p.getUuid())) return "먼저 창당하세요";
        if (G(s).getLong("pledged." + p.getUuid()) == today()) return "오늘은 이미 공약을 걸었습니다";
        G(s).putString("pledge." + p.getUuid(), kind);
        G(s).putLong("pledged." + p.getUuid(), today());
        dirty(s);
        NbtCompound c = G(s).getCompound("party." + p.getUuid());
        s.getPlayerManager().broadcast(Text.literal("📜 [" + c.getString("name") + "] 오늘의 공약: " + pledgeName(kind) + " (당원 전원)").formatted(Formatting.AQUA), false);
        return null;
    }

    // ═════════ 대통령 ═════════

    public static UUID president(MinecraftServer s) {
        String id = G(s).getString("pres");
        return id.isEmpty() ? null : UUID.fromString(id);
    }

    public static boolean isPresident(MinecraftServer s, UUID id) { return id.equals(president(s)); }

    static void inaugurate(ServerPlayerEntity p, String how) {
        MinecraftServer s = p.getServer();
        NbtCompound g = G(s);
        g.putString("pres", p.getUuidAsString());
        g.putString("pres.name", p.getName().getString());
        g.putLong("pres.since", now());
        g.putInt("pres.defenses", 0);
        g.putLong("pres.protect", now() + 86_400_000L);
        g.put("pres.refuse", new NbtList());
        g.remove("terr");
        g.remove("martial");
        g.remove("insur");
        g.remove("pres.low");
        AlchemyData.Entry e = Jobs.entry(p);
        e.num(P + "rank", 5);
        dirty(s);
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) {
            o.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(how).formatted(Formatting.GRAY)));
            o.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("대통령 " + p.getName().getString()).formatted(Formatting.GOLD, Formatting.BOLD)));
            o.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 0.9f);
        }
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 6; i++) Tasks.later(i * 6, () -> w.spawnParticles(ParticleTypes.FIREWORK, p.getX(), p.getY() + 3, p.getZ(), 60, 1.5, 1.5, 1.5, 0.2));
        s.getPlayerManager().broadcast(Text.literal("🏛 " + p.getName().getString() + "님이 대통령에 취임했습니다 (" + how + ")").formatted(Formatting.GOLD, Formatting.BOLD), false);
        p.sendMessage(Text.literal("🏛 대통령의 위치는 영토 밖에서 모두의 지도에 표시됩니다. /job 에서 영토·특별 수송·행정 명령을 쓰세요.").formatted(Formatting.GOLD), false);
    }

    static void vacate(MinecraftServer s, String why) {
        UUID id = president(s);
        if (id == null) return;
        NbtCompound g = G(s);
        String name = g.getString("pres.name");
        g.remove("pres"); g.remove("terr"); g.remove("martial"); g.remove("insur");
        AlchemyData.Entry e = AlchemyData.get(s).of(id);
        if (is(e) && rank(e) > 4) e.num(P + "rank", 4);
        endMartialEffects(s);
        dirty(s);
        s.getPlayerManager().broadcast(Text.literal("🏛 대통령 " + name + "이(가) 물러났습니다 — " + why + ". 대통령 자리가 비었습니다.").formatted(Formatting.GOLD), false);
    }

    // ───────── 영토 ─────────

    public static final int MAX_CHUNKS = 64;

    static NbtList chunks(MinecraftServer s) { return G(s).getCompound("terr").getList("c", NbtElement.LONG_TYPE); }

    static boolean inTerritory(MinecraftServer s, ServerWorld w, BlockPos pos) {
        NbtCompound t = G(s).getCompound("terr");
        if (t.isEmpty() || !t.getString("dim").equals(w.getRegistryKey().getValue().toString())) return false;
        long c = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
        NbtList l = t.getList("c", NbtElement.LONG_TYPE);
        for (int i = 0; i < l.size(); i++) if (((NbtLong) l.get(i)).longValue() == c) return true;
        return false;
    }

    static boolean territoryActive(MinecraftServer s) { return !insurrection(s); }

    static boolean allowed(MinecraftServer s, ServerPlayerEntity p) {
        UUID pres = president(s);
        if (pres == null || pres.equals(p.getUuid()) || p.hasPermissionLevel(2)) return true;
        NbtList a = G(s).getCompound("terr").getList("allow", NbtElement.STRING_TYPE);
        for (int i = 0; i < a.size(); i++) if (a.getString(i).equals(p.getUuidAsString())) return true;
        return false;
    }

    static String claim(ServerPlayerEntity p, boolean add) {
        MinecraftServer s = p.getServer();
        if (!isPresident(s, p.getUuid())) return "대통령만 영토를 정할 수 있습니다";
        ServerWorld w = p.getServerWorld();
        NbtCompound t = G(s).getCompound("terr");
        String dim = w.getRegistryKey().getValue().toString();
        if (!t.isEmpty() && !t.getString("dim").equals(dim)) return "영토는 한 차원에만 둘 수 있습니다 (" + t.getString("dim") + ")";
        BlockPos spawn = w.getSpawnPos();
        if (add && w.getRegistryKey() == net.minecraft.world.World.OVERWORLD && p.getBlockPos().isWithinDistance(spawn, 64)) return "스폰 근처(64칸)는 영토로 삼을 수 없습니다";
        long c = ChunkPos.toLong(p.getBlockX() >> 4, p.getBlockZ() >> 4);
        NbtList l = t.getList("c", NbtElement.LONG_TYPE);
        int idx = -1;
        for (int i = 0; i < l.size(); i++) if (((NbtLong) l.get(i)).longValue() == c) idx = i;
        if (add) {
            if (idx >= 0) return "이미 영토입니다";
            if (l.size() >= MAX_CHUNKS) return "영토는 " + MAX_CHUNKS + "청크까지";
            l.add(NbtLong.of(c));
        } else {
            if (idx < 0) return "영토가 아닙니다";
            l.remove(idx);
        }
        t.put("c", l);
        t.putString("dim", dim);
        if (!t.contains("name")) t.putString("name", "대통령 관저");
        G(s).put("terr", t);
        dirty(s);
        p.sendMessage(Text.literal("🏛 영토 " + l.size() + " / " + MAX_CHUNKS + "청크").formatted(Formatting.GOLD), true);
        return null;
    }

    static ChunkPos territoryCenter(MinecraftServer s) {
        NbtList l = chunks(s);
        if (l.isEmpty()) return null;
        long sx = 0, sz = 0;
        for (int i = 0; i < l.size(); i++) { ChunkPos c = new ChunkPos(((NbtLong) l.get(i)).longValue()); sx += c.x; sz += c.z; }
        return new ChunkPos((int) (sx / l.size()), (int) (sz / l.size()));
    }

    // ───────── 특별 수송 ─────────

    static String transport(ServerPlayerEntity p, String biomeId, Integer x, Integer z) {
        MinecraftServer s = p.getServer();
        if (!isPresident(s, p.getUuid())) return "대통령만 쓸 수 있습니다";
        if (insurrection(s)) return "내란 중에는 특별 수송이 막힙니다";
        if (BattleRegistry.getBattleByParticipatingPlayer(p) != null || CombatMode.active(p)) return "전투·배틀 중에는 쓸 수 없습니다";
        AlchemyData.Entry e = Jobs.entry(p);
        long cd = martial(s) ? 60_000 : 600_000;
        long left = e.time(P + "tp") + cd - now();
        if (penalized(s, 1)) return "도전을 피한 벌로 24시간 동안 특별 수송을 쓸 수 없습니다";
        if (left > 0) return "특별 수송 재사용까지 " + (left / 60_000 + 1) + "분";
        ServerWorld w = p.getServerWorld();
        BlockPos dest;
        if (biomeId != null) {
            Identifier bid = Identifier.tryParse(biomeId.contains(":") ? biomeId : "minecraft:" + biomeId);
            if (bid == null) return "바이옴 이름을 확인하세요";
            var found = w.locateBiome(b -> b.matchesId(bid), p.getBlockPos(), 6400, 32, 64);
            if (found == null) return "근처(6,400칸)에서 그 바이옴을 찾지 못했습니다";
            dest = found.getFirst();
        } else {
            dest = new BlockPos(x, 0, z);
        }
        w.getChunk(dest.getX() >> 4, dest.getZ() >> 4);
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, dest.getX(), dest.getZ());
        BlockPos to = new BlockPos(dest.getX(), Math.max(y, w.getBottomY() + 1), dest.getZ());
        e.time(P + "tp", now());
        dirty(s);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 4, false, false));
        w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1f, 1.2f);
        p.sendMessage(Text.literal("🚁 특별 수송 준비… 3초").formatted(Formatting.GOLD), true);
        Tasks.later(60, () -> {
            if (p.isDisconnected() || !p.isAlive()) return;
            p.teleport(w, to.getX() + 0.5, to.getY(), to.getZ() + 0.5, p.getYaw(), p.getPitch());
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4, false, false));
            s.getPlayerManager().broadcast(Text.literal("🚁 대통령이 특별 수송으로 도착했습니다: " + to.getX() + ", " + to.getY() + ", " + to.getZ()).formatted(Formatting.GOLD), false);
        });
        return null;
    }

    // ───────── 행정 명령 · 수배 · 사면 ─────────

    public static final String[] ORDERS = {"catch", "exp", "weather", "shiny"};
    static String orderName(String k) {
        return switch (k) { case "catch" -> "포획률 +10%"; case "exp" -> "포켓몬 경험치 +20%"; case "weather" -> "날씨 맑음"; case "shiny" -> "이로치 확률 소폭 증가"; default -> "-"; };
    }

    static String activeOrder(MinecraftServer s) { return G(s).getLong("order.until") > now() ? G(s).getString("order") : ""; }

    static String order(ServerPlayerEntity p, String kind) {
        MinecraftServer s = p.getServer();
        if (!isPresident(s, p.getUuid())) return "대통령만 쓸 수 있습니다";
        if (penalized(s, 1)) return "도전을 피한 벌로 24시간 동안 행정 명령을 쓸 수 없습니다";
        AlchemyData.Entry e = Jobs.entry(p);
        if (e.time(P + "orderday") == today()) return "행정 명령은 하루 한 번";
        e.time(P + "orderday", today());
        G(s).putString("order", kind);
        G(s).putLong("order.until", now() + 30 * 60_000L);
        if (kind.equals("weather")) s.getOverworld().setWeather(36_000, 0, false, false);
        dirty(s);
        s.getPlayerManager().broadcast(Text.literal("🏛 행정 명령: " + orderName(kind) + " (30분, 서버 전체)").formatted(Formatting.GOLD, Formatting.BOLD), false);
        return null;
    }

    static boolean wanted(MinecraftServer s, UUID id) { return G(s).getLong("wanted." + id) > now(); }

    static String wantedOrder(ServerPlayerEntity p, ServerPlayerEntity t) {
        MinecraftServer s = p.getServer();
        if (!isPresident(s, p.getUuid())) return "대통령만 쓸 수 있습니다";
        if (t == p) return "자기 자신은 수배할 수 없습니다";
        AlchemyData.Entry e = Jobs.entry(p);
        if (!martial(s)) {
            if (e.time(P + "wantday") == today()) return "수배령은 하루 한 명";
            if (e.str(P + "lastwanted").equals(t.getUuidAsString())) return "같은 사람을 연달아 수배할 수 없습니다";
            e.time(P + "wantday", today());
        }
        e.str(P + "lastwanted", t.getUuidAsString());
        G(s).putLong("wanted." + t.getUuid(), now() + 86_400_000L);
        dirty(s);
        s.getPlayerManager().broadcast(Text.literal("🚨 수배령: " + t.getName().getString() + " (24시간 동안 위치 공개)").formatted(Formatting.RED, Formatting.BOLD), false);
        return null;
    }

    static String pardon(ServerPlayerEntity p, ServerPlayerEntity t) {
        MinecraftServer s = p.getServer();
        if (!isPresident(s, p.getUuid())) return "대통령만 쓸 수 있습니다";
        AlchemyData.Entry e = Jobs.entry(p);
        long week = today() / 7;
        if ((long) e.num(P + "pardonw") == week) return "특별 사면은 주 1회";
        e.num(P + "pardonw", week);
        G(s).remove("wanted." + t.getUuid());
        dirty(s);
        s.getPlayerManager().broadcast(Text.literal("🏛 특별 사면: " + t.getName().getString()).formatted(Formatting.GOLD), false);
        return null;
    }

    // ═════════ 도전 ═════════

    static final Map<UUID, UUID> PENDING = new HashMap<>();       // 대통령 → 도전자
    static final Map<UUID, Long> PENDING_UNTIL = new HashMap<>();
    static final Map<UUID, UUID> CHALLENGE_BATTLES = new HashMap<>(); // battleId → 도전자

    /** 도전장으로 대통령을 우클릭 */
    static ActionResult challenge(ServerPlayerEntity c, ServerPlayerEntity pres, ItemStack letter) {
        MinecraftServer s = c.getServer();
        if (!isPresident(s, pres.getUuid())) { c.sendMessage(Text.literal("대통령에게만 쓸 수 있습니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        AlchemyData.Entry e = Jobs.entry(c);
        if (rank(e) < 5) { c.sendMessage(Text.literal("대선 후보(당대표 5등급)만 도전할 수 있습니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        if (e.time(P + "ban") > now()) { c.sendMessage(Text.literal("도전 금지 기간입니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        if (G(s).getLong("chal." + c.getUuid()) > now()) { c.sendMessage(Text.literal("대통령에게 진 뒤 3일 동안은 다시 도전할 수 없습니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        boolean insur = insurrection(s) && c.getUuidAsString().equals(G(s).getString("insur"));
        if (!insur && G(s).getLong("pres.protect") > now()) { c.sendMessage(Text.literal("대통령은 지금 방어 후 보호 기간입니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        if (!insur && martial(s) && inMartialZone(s, c)) { c.sendMessage(Text.literal("계엄 구역 안에서는 도전장을 쓸 수 없습니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        if (!insur && G(s).getLong("refusedby." + c.getUuid()) > now()) { c.sendMessage(Text.literal("거절당한 뒤 1시간이 지나야 다시 도전할 수 있습니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        if (c.squaredDistanceTo(pres) > 4 * 4) { c.sendMessage(Text.literal("대통령 4칸 안에서 도전장을 내밀어야 합니다").formatted(Formatting.RED), true); return ActionResult.FAIL; }
        if (hasBanned(c)) { c.sendMessage(Text.literal("전설·환상·울트라비스트는 도전 배틀에 데려갈 수 없습니다").formatted(Formatting.RED), false); return ActionResult.FAIL; }
        if (insur) { startBattle(c, pres); return ActionResult.SUCCESS; }
        PENDING.put(pres.getUuid(), c.getUuid());
        PENDING_UNTIL.put(pres.getUuid(), now() + 30_000);
        pres.sendMessage(Text.literal("⚔ " + c.getName().getString() + "이(가) 대통령 자리를 걸고 도전장을 내밀었습니다! (30초) ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(button("[수락]", "/president accept", Formatting.GREEN)).append(Text.literal(" ")).append(button("[거절]", "/president refuse", Formatting.RED)), false);
        c.sendMessage(Text.literal("⚔ 도전장을 내밀었습니다. 대통령의 답을 기다립니다 (30초)").formatted(Formatting.GOLD), false);
        s.getPlayerManager().broadcast(Text.literal("⚔ " + c.getName().getString() + "이(가) 대통령에게 도전장을 내밀었다!").formatted(Formatting.GOLD), false);
        return ActionResult.SUCCESS;
    }

    static boolean hasBanned(ServerPlayerEntity p) {
        for (Pokemon pk : PokemonDoctor.party(p)) if (pk != null && (pk.isLegendary() || pk.isMythical() || pk.hasLabels("ultra_beast"))) return true;
        return false;
    }

    static String answer(ServerPlayerEntity pres, boolean accept) {
        MinecraftServer s = pres.getServer();
        UUID cid = PENDING.remove(pres.getUuid());
        PENDING_UNTIL.remove(pres.getUuid());
        if (cid == null) return "받은 도전이 없습니다";
        ServerPlayerEntity c = s.getPlayerManager().getPlayer(cid);
        if (c == null) return "도전자가 나갔습니다";
        if (accept) {
            if (hasBanned(pres)) { refuse(s, cid, "대통령 파티에 금지 포켓몬"); return null; }
            startBattle(c, pres);
        } else refuse(s, cid, "거절");
        return null;
    }

    static void startBattle(ServerPlayerEntity c, ServerPlayerEntity pres) {
        if (BattleRegistry.getBattleByParticipatingPlayer(c) != null || BattleRegistry.getBattleByParticipatingPlayer(pres) != null) {
            c.sendMessage(Text.literal("둘 중 누군가 이미 배틀 중입니다").formatted(Formatting.RED), false);
            return;
        }
        BattleFormat base = BattleFormat.Companion.getGEN_9_SINGLES();
        BattleFormat fmt = new BattleFormat(base.getMod(), base.getBattleType(), base.getRuleSet(), base.getGen(), 100);
        var result = BattleBuilder.INSTANCE.pvp1v1(c, pres, null, null, fmt, true, true);
        if (result instanceof SuccessfulBattleStart ok) {
            CHALLENGE_BATTLES.put(ok.getBattle().getBattleId(), c.getUuid());
            c.getServer().getPlayerManager().broadcast(Text.literal("⚔ 대통령 결정전 시작: " + c.getName().getString() + " vs " + pres.getName().getString() + " (레벨 100 고정)")
                    .formatted(Formatting.GOLD, Formatting.BOLD), false);
        } else if (result instanceof ErroredBattleStart err) {
            err.sendTo(c, t -> t);
            err.sendTo(pres, t -> t);
        }
    }

    /** 거절 처리 (7일 누적 벌) */
    static void refuse(MinecraftServer s, UUID challenger, String why) {
        UUID pres = president(s);
        if (pres == null) return;
        NbtCompound g = G(s);
        NbtList l = g.getList("pres.refuse", NbtElement.LONG_TYPE);
        NbtList keep = new NbtList();
        for (int i = 0; i < l.size(); i++) if (now() - ((NbtLong) l.get(i)).longValue() < 7 * 86_400_000L) keep.add(l.get(i));
        keep.add(NbtLong.of(now()));
        g.put("pres.refuse", keep);
        g.putLong("refusedby." + challenger, now() + 3_600_000L);
        int n = keep.size();
        dirty(s);
        ServerPlayerEntity c = s.getPlayerManager().getPlayer(challenger);
        s.getPlayerManager().broadcast(Text.literal("🏛 대통령이 도전을 피했습니다 (" + why + ", 7일 안 " + n + "번째)").formatted(Formatting.RED, Formatting.BOLD), false);
        if (n == 1) {
            penalty(s, pres, 40, "도전 회피");
            g.putLong("pres.pen1", now() + 86_400_000L);
        } else if (n == 2) {
            penalty(s, pres, 80, "도전 회피 2번째");
            g.putLong("pres.pen2", now() + 86_400_000L);
            g.putLong("pres.nomartial", now() + 7 * 86_400_000L);
            ServerPlayerEntity pp = s.getPlayerManager().getPlayer(pres);
            if (pp != null) Thralls.releaseAll(pp.getUuid());
        } else {
            AlchemyData.Entry old = AlchemyData.get(s).of(pres);
            vacate(s, "도전을 세 번 피해 탄핵");
            if (is(old)) { old.num(P + "rank", 3); old.time(P + "ban", now() + 7 * 86_400_000L); }
            if (c != null && rank(Jobs.entry(c)) >= 5) inaugurate(c, "부전승");
        }
        dirty(s);
    }

    static boolean penalized(MinecraftServer s, int level) {
        return G(s).getLong(level == 1 ? "pres.pen1" : "pres.pen2") > now() || (level == 1 && G(s).getLong("pres.pen2") > now());
    }

    static void settleChallenge(MinecraftServer s, UUID battleId, UUID winner) {
        UUID cid = CHALLENGE_BATTLES.remove(battleId);
        if (cid == null) return;
        UUID pres = president(s);
        if (pres == null) return;
        ServerPlayerEntity c = s.getPlayerManager().getPlayer(cid);
        if (winner != null && winner.equals(cid) && c != null) {
            AlchemyData.Entry old = AlchemyData.get(s).of(pres);
            G(s).remove("pres");
            if (is(old)) old.num(P + "rank", 4);
            boolean wasInsur = cid.toString().equals(G(s).getString("insur"));
            inaugurate(c, wasInsur ? "내란 성공" : "도전 승리");
        } else {
            NbtCompound g = G(s);
            g.putInt("pres.defenses", g.getInt("pres.defenses") + 1);
            g.putLong("pres.protect", now() + 86_400_000L);
            g.putLong("chal." + cid, now() + 3 * 86_400_000L);
            NbtList l = g.getList("pres.refuse", NbtElement.LONG_TYPE);
            if (!l.isEmpty()) l.remove(0);
            g.put("pres.refuse", l);
            AlchemyData.get(s).of(pres).addNum(P + "malus", -20);
            if (cid.toString().equals(g.getString("insur"))) failInsurrection(s, "결정전 패배");
            dirty(s);
            s.getPlayerManager().broadcast(Text.literal("🏛 대통령이 자리를 지켰습니다 (연속 방어 " + g.getInt("pres.defenses") + "회)").formatted(Formatting.GOLD, Formatting.BOLD), false);
        }
    }

    // ═════════ 내란 ═════════

    static boolean insurrection(MinecraftServer s) { return G(s).getLong("insur.until") > now() && !G(s).getString("insur").isEmpty(); }

    static boolean insurgent(ServerPlayerEntity p) {
        return Politics.is(Jobs.entry(p)) && insurrection(p.getServer()) && p.getUuidAsString().equals(G(p.getServer()).getString("insur"));
    }

    static String insurrect(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        UUID pres = president(s);
        if (pres == null) return "대통령이 없습니다 — 출마 선언으로 취임하세요";
        if (pres.equals(p.getUuid())) return "대통령은 내란을 일으킬 수 없습니다";
        if (s.getPlayerManager().getPlayer(pres) == null) return "대통령이 접속해 있을 때만 일으킬 수 있습니다";
        if (insurrection(s)) return "이미 내란이 진행 중입니다";
        AlchemyData.Entry e = Jobs.entry(p);
        if (e.time(P + "ban") > now()) return "내란 실패의 벌로 금지 기간입니다";
        if (now() - e.time(P + "insurcd") < 7 * 86_400_000L) return "내란은 7일에 한 번";
        List<ServerPlayerEntity> near = new ArrayList<>();
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers())
            if (o != p && o.squaredDistanceTo(p) <= 16 * 16 && p.getUuid().equals(partyOf(s, o.getUuid()))) near.add(o);
        if (near.isEmpty()) {
            if (villagers(e) < 30) return "16칸 안에 당원 1명이 있거나, 주민 지지자 30명을 바쳐야 합니다";
            NbtList l = e.jd.getList(P + "vil", NbtElement.STRING_TYPE);
            for (int i = 0; i < 30 && !l.isEmpty(); i++) l.remove(l.size() - 1);
            e.jd.put(P + "vil", l);
        }
        e.time(P + "insurcd", now());
        NbtCompound g = G(s);
        g.putString("insur", p.getUuidAsString());
        g.putLong("insur.until", now() + 15 * 60_000L);
        NbtList parts = new NbtList();
        for (ServerPlayerEntity o : near) parts.add(NbtString.of(o.getUuidAsString()));
        g.put("insur.parts", parts);
        if (martial(s)) { g.remove("martial"); endMartialEffects(s); s.getPlayerManager().broadcast(Text.literal("🏛 내란으로 비상계엄이 무너졌다").formatted(Formatting.RED), false); }
        dirty(s);
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) {
            o.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(p.getName().getString() + "이(가) 대통령을 노린다 — 15분").formatted(Formatting.GRAY)));
            o.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("내란 발생").formatted(Formatting.DARK_RED, Formatting.BOLD)));
            o.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 1f, 1f);
        }
        return null;
    }

    static void failInsurrection(MinecraftServer s, String why) {
        NbtCompound g = G(s);
        String id = g.getString("insur");
        if (id.isEmpty()) return;
        UUID lid = UUID.fromString(id);
        AlchemyData.Entry e = AlchemyData.get(s).of(lid);
        if (is(e)) { e.num(P + "rank", Math.min(rank(e), 3)); e.time(P + "ban", now() + 7 * 86_400_000L); }
        NbtList parts = g.getList("insur.parts", NbtElement.STRING_TYPE);
        for (int i = 0; i < parts.size(); i++) g.putLong("wanted." + parts.getString(i), now() + 86_400_000L);
        g.remove("insur"); g.remove("insur.until"); g.remove("insur.parts");
        dirty(s);
        s.getPlayerManager().broadcast(Text.literal("🏛 내란이 실패했다 (" + why + ") — 주동자는 3등급으로, 가담자는 수배").formatted(Formatting.GOLD, Formatting.BOLD), false);
    }

    // ═════════ 비상계엄 ═════════

    static boolean martial(MinecraftServer s) { return G(s).getCompound("martial").getLong("until") > now(); }

    static double martialRadius(MinecraftServer s) { return G(s).getCompound("martial").getDouble("r"); }

    static boolean inMartialZone(MinecraftServer s, ServerPlayerEntity p) {
        if (!martial(s)) return false;
        UUID pres = president(s);
        if (pres == null || pres.equals(p.getUuid())) return false;
        ServerPlayerEntity pp = s.getPlayerManager().getPlayer(pres);
        if (pp == null || pp.getServerWorld() != p.getServerWorld()) return false;
        double r = martialRadius(s);
        return p.squaredDistanceTo(pp) <= r * r || inTerritory(s, p.getServerWorld(), p.getBlockPos()) && G(s).getCompound("martial").getBoolean("terr");
    }

    static String declareMartial(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        if (!isPresident(s, p.getUuid())) return "대통령만 쓸 수 있습니다";
        if (G(s).getLong("pres.nomartial") > now()) return "도전을 피한 벌로 비상계엄이 봉인되어 있습니다";
        if (insurrection(s)) return "내란 중에는 계엄을 선포할 수 없습니다";
        AlchemyData.Entry e = Jobs.entry(p);
        long left = e.time(P + "martialcd") + 7 * 86_400_000L - now();
        if (left > 0) return "비상계엄은 7일에 한 번 (" + (left / 3_600_000L + 1) + "시간 남음)";
        boolean inTerr = inTerritory(s, p.getServerWorld(), p.getBlockPos());
        NbtCompound m = new NbtCompound();
        m.putLong("until", now() + 15 * 60_000L);
        m.putDouble("r", inTerr ? 256 : 128);
        m.putBoolean("terr", inTerr);
        m.put("votes", new NbtList());
        G(s).put("martial", m);
        e.time(P + "martialcd", now());
        dirty(s);
        // 계엄군: 파티 포켓몬 전원
        int n = 0;
        for (Pokemon pk : PokemonDoctor.party(p)) {
            if (pk == null || pk.isFainted() || pk.getEntity() != null) continue;
            Vec3d at = p.getPos().add((n % 3) - 1, 0, (n / 3) - 0.5);
            PokemonEntity ent = pk.sendOut(p.getServerWorld(), at, null, x -> kotlin.Unit.INSTANCE);
            if (ent != null) { Thralls.bind(p, ent, 900, 8, 6, "계엄군", true); n++; }
        }
        p.getServerWorld().setWeather(0, 18_000, true, false);
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) {
            o.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("대통령 반경 " + (int) martialRadius(s) + "칸 · 15분").formatted(Formatting.GRAY)));
            o.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("비상계엄 선포").formatted(Formatting.DARK_RED, Formatting.BOLD)));
            o.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 1.5f, 0.6f);
        }
        s.getPlayerManager().broadcast(Text.literal("🚨 비상계엄: 구역 안 비행·텔레포트·블록·포켓몬·전투 기술 금지. 해제 투표: ").formatted(Formatting.RED, Formatting.BOLD)
                .append(button("[/martiallaw]", "/martiallaw", Formatting.YELLOW)), false);
        return null;
    }

    static String voteLift(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        if (!martial(s)) return "비상계엄 중이 아닙니다";
        if (isPresident(s, p.getUuid())) return "대통령은 투표할 수 없습니다";
        NbtCompound m = G(s).getCompound("martial");
        NbtList v = m.getList("votes", NbtElement.STRING_TYPE);
        for (int i = 0; i < v.size(); i++) if (v.getString(i).equals(p.getUuidAsString())) return "이미 투표했습니다";
        v.add(NbtString.of(p.getUuidAsString()));
        m.put("votes", v);
        G(s).put("martial", m);
        int voters = s.getPlayerManager().getPlayerList().size() - 1;
        s.getPlayerManager().broadcast(Text.literal("🗳 계엄 해제 요구 " + v.size() + " / 과반 " + (voters / 2 + 1) + " (최소 2)").formatted(Formatting.YELLOW), false);
        if (v.size() >= 2 && v.size() > voters / 2.0) {
            UUID pres = president(s);
            G(s).remove("martial");
            endMartialEffects(s);
            if (pres != null) penalty(s, pres, 60, "계엄 해제 요구 가결");
            s.getPlayerManager().broadcast(Text.literal("🗳 계엄 해제 요구가 가결되어 비상계엄이 풀렸습니다").formatted(Formatting.GREEN, Formatting.BOLD), false);
        }
        dirty(s);
        return null;
    }

    private static final Identifier MARTIAL_SLOW = Identifier.of(DuelMod.MOD_ID, "martial_slow");
    private static final Identifier MARTIAL_KB = Identifier.of(DuelMod.MOD_ID, "martial_kb");

    static void endMartialEffects(MinecraftServer s) {
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) {
            EntityAttributeInstance sp = o.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            if (sp != null) sp.removeModifier(MARTIAL_SLOW);
            EntityAttributeInstance kb = o.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
            if (kb != null) kb.removeModifier(MARTIAL_KB);
        }
        UUID pres = president(s);
        if (pres != null) Thralls.releaseAll(pres);
    }

    static final Map<UUID, Long> ARREST_CD = new HashMap<>();

    // ═════════ 등록 ═════════

    private static int clock = 0;

    public static void register() {
        Jobs.registerType(INSTANCE);

        // 배틀 승리 횟수 + 대통령 결정전
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, ev -> {
            MinecraftServer s = ev.getBattle().getPlayers().isEmpty() ? null : ev.getBattle().getPlayers().get(0).getServer();
            UUID winner = null;
            for (var a : ev.getWinners()) if (a instanceof PlayerBattleActor pa && pa.getEntity() != null) {
                winner = pa.getEntity().getUuid();
                AlchemyData.Entry e = Jobs.entry(pa.getEntity());
                if (is(e)) { e.addNum(P + "wins", 1); checkRank(pa.getEntity(), e); }
            }
            if (s != null) settleChallenge(s, ev.getBattle().getBattleId(), winner);
            return kotlin.Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_FLED.subscribe(Priority.NORMAL, ev -> {
            var battle = ev.getBattle();
            if (!CHALLENGE_BATTLES.containsKey(battle.getBattleId())) return kotlin.Unit.INSTANCE;
            ServerPlayerEntity fled = ev.getPlayer().getEntity();
            UUID winner = null;
            for (ServerPlayerEntity p : battle.getPlayers()) if (fled == null || !p.getUuid().equals(fled.getUuid())) winner = p.getUuid();
            if (!battle.getPlayers().isEmpty()) settleChallenge(battle.getPlayers().get(0).getServer(), battle.getBattleId(), winner);
            return kotlin.Unit.INSTANCE;
        });

        // 공약·행정 명령: 포획률, 경험치
        CobblemonEvents.POKEMON_CATCH_RATE.subscribe(Priority.NORMAL, ev -> {
            if (!(ev.getThrower() instanceof ServerPlayerEntity p)) return kotlin.Unit.INSTANCE;
            MinecraftServer s = p.getServer();
            if ("catch".equals(activePledge(s, p.getUuid()))) ev.setCatchRate(ev.getCatchRate() * 1.05f);
            if ("catch".equals(activeOrder(s))) ev.setCatchRate(ev.getCatchRate() * 1.10f);
            return kotlin.Unit.INSTANCE;
        });
        CobblemonEvents.EXPERIENCE_GAINED_EVENT_PRE.subscribe(Priority.NORMAL, ev -> {
            ServerPlayerEntity p = ev.getPokemon().getOwnerPlayer();
            if (p == null) return kotlin.Unit.INSTANCE;
            double m = 1;
            if ("exp".equals(activePledge(p.getServer(), p.getUuid()))) m += 0.10;
            if ("exp".equals(activeOrder(p.getServer()))) m += 0.20;
            if (m > 1) ev.setExperience((int) Math.ceil(ev.getExperience() * m));
            return kotlin.Unit.INSTANCE;
        });
        // 행정 명령 '이로치': 야생 포켓몬이 나타날 때 1/1024 로 이로치
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PokemonEntity pe && pe.getOwnerUuid() == null && pe.age < 5 && "shiny".equals(activeOrder(world.getServer()))
                    && world.random.nextInt(1024) == 0) pe.getPokemon().setShiny(true);
        });

        // 계엄: 포켓몬 내보내기 금지, 대통령에게 거는 배틀 금지
        CobblemonEvents.POKEMON_SENT_PRE.subscribe(Priority.HIGH, ev -> {
            ServerPlayerEntity p = ev.getPokemon().getOwnerPlayer();
            if (p != null && inMartialZone(p.getServer(), p)) {
                ev.cancel();
                p.sendMessage(Text.literal("🚨 계엄 구역에서는 포켓몬을 내보낼 수 없습니다").formatted(Formatting.RED), true);
            }
            return kotlin.Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGH, ev -> {
            List<ServerPlayerEntity> ps = ev.getBattle().getPlayers();
            if (ps.isEmpty()) return kotlin.Unit.INSTANCE;
            MinecraftServer s = ps.get(0).getServer();
            if (!martial(s)) return kotlin.Unit.INSTANCE;
            UUID pres = president(s);
            boolean challenge = false;
            for (ServerPlayerEntity p : ps) if (p.getUuid().equals(pres)) challenge = true;
            if (challenge && !insurrection(s)) {
                for (ServerPlayerEntity p : ps) if (!p.getUuid().equals(pres) && inMartialZone(s, p)) {
                    ev.cancel();
                    p.sendMessage(Text.literal("🚨 계엄 중에는 대통령에게 배틀을 걸 수 없습니다").formatted(Formatting.RED), false);
                }
            }
            return kotlin.Unit.INSTANCE;
        });

        // 영토 보호 · 계엄 블록 금지
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayerEntity p)) return true;
            return !blockedBuild(p, (ServerWorld) world, pos);
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            ServerWorld w = (ServerWorld) world;
            if (blockedBuild(p, w, hit.getBlockPos()) || (p.getStackInHand(hand).getItem() instanceof BlockItem && blockedBuild(p, w, hit.getBlockPos().offset(hit.getSide())))) {
                p.sendMessage(Text.literal(martial(p.getServer()) && inMartialZone(p.getServer(), p) ? "🚨 계엄 구역입니다" : "🏛 대통령 영토입니다").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || !(player instanceof ServerPlayerEntity p) || !(entity instanceof ServerPlayerEntity)) return ActionResult.PASS;
            MinecraftServer s = p.getServer();
            if (president(s) != null && territoryActive(s) && inTerritory(s, p.getServerWorld(), entity.getBlockPos()) && !G(s).getCompound("terr").getBoolean("pvp")) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        // 계엄: 엔더진주·후렴과 금지
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack st = player.getStackInHand(hand);
            if (!world.isClient && player instanceof ServerPlayerEntity p && (st.isOf(Items.ENDER_PEARL) || st.isOf(Items.CHORUS_FRUIT)) && inMartialZone(p.getServer(), p)) {
                p.sendMessage(Text.literal("🚨 계엄 구역에서는 순간이동할 수 없습니다").formatted(Formatting.RED), true);
                return TypedActionResult.fail(st);
            }
            return TypedActionResult.pass(st);
        });

        // 주민 지지 · 도전장 · 체포
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || hand != net.minecraft.util.Hand.MAIN_HAND || !(player instanceof ServerPlayerEntity p)) return ActionResult.PASS;
            if (entity instanceof VillagerEntity v && p.isSneaking() && p.getMainHandStack().isOf(Items.EMERALD) && is(Jobs.entry(p))) return recruitVillager(p, v);
            if (entity instanceof ServerPlayerEntity t) {
                if (p.getMainHandStack().isOf(DuelMod.CHALLENGE_LETTER)) return challenge(p, t, p.getMainHandStack());
                MinecraftServer s = p.getServer();
                if (isPresident(s, p.getUuid()) && inMartialZone(s, t) && p.getMainHandStack().isEmpty()) {
                    Long cd = ARREST_CD.get(p.getUuid());
                    if (cd != null && cd > now()) { p.sendMessage(Text.literal("체포 재사용까지 " + ((cd - now()) / 1000 + 1) + "초").formatted(Formatting.GRAY), true); return ActionResult.SUCCESS; }
                    ARREST_CD.put(p.getUuid(), now() + 30_000);
                    t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 160, 9, false, false));
                    t.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 9, false, false));
                    t.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 160, 4, false, false));
                    t.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 160, 128, false, false));
                    t.getServerWorld().playSound(null, t.getBlockPos(), SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.5f, 0.6f);
                    t.sendMessage(Text.literal("🚨 체포되었다! (8초)").formatted(Formatting.RED, Formatting.BOLD), true);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // 주민 지지자가 죽거나 좀비가 되면 지지도 사라짐
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof VillagerEntity v) dropVillager(v);
            // 계엄 중 대통령이 플레이어에게 죽으면 탄핵
            if (entity instanceof ServerPlayerEntity dead && martial(dead.getServer()) && isPresident(dead.getServer(), dead.getUuid())
                    && source.getAttacker() instanceof ServerPlayerEntity killer) {
                MinecraftServer s = dead.getServer();
                vacate(s, "계엄 중 쓰러짐");
                if (rank(Jobs.entry(killer)) >= 5) inaugurate(killer, "계엄을 무너뜨림");
            }
        });
        ServerLivingEntityEvents.MOB_CONVERSION.register((prev, conv, keep) -> { if (prev instanceof VillagerEntity v) dropVillager(v); });

        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> Tasks.later(20, () -> {
            ServerPlayerEntity p = h.getPlayer();
            if (p.isDisconnected()) return;
            UUID l = partyOf(s, p.getUuid());
            if (l != null) syncTeam(s, l);
        }));

        // 계엄 구역: 당대표(내란용)를 빼고 전투 모드 봉인
        CombatMode.BLOCKERS.add(p -> inMartialZone(p.getServer(), p) && !is(Jobs.entry(p)));
        Markers.PROVIDERS.add(Politics::markers);
        ServerTickEvents.END_SERVER_TICK.register(Politics::tick);
    }

    static void dropVillager(VillagerEntity v) {
        for (String t : v.getCommandTags()) {
            if (!t.startsWith("duel_vsup:")) continue;
            try { removeVillager(v.getServer(), UUID.fromString(t.substring(10)), v.getUuid()); } catch (IllegalArgumentException ignored) {}
        }
    }

    static boolean blockedBuild(ServerPlayerEntity p, ServerWorld w, BlockPos pos) {
        MinecraftServer s = p.getServer();
        if (p.hasPermissionLevel(2) && p.isCreative()) return false;
        if (inMartialZone(s, p)) return true;
        return president(s) != null && territoryActive(s) && inTerritory(s, w, pos) && !allowed(s, p);
    }

    private static void markers(MinecraftServer s, List<MarkerPayload.Marker> out) {
        UUID pres = president(s);
        if (pres != null) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(pres);
            if (p != null) {
                boolean inside = territoryActive(s) && inTerritory(s, p.getServerWorld(), p.getBlockPos());
                boolean exposed = !inside || martial(s) || insurrection(s) || penalized(s, 2);
                if (exposed) out.add(Markers.at("president", "대통령 " + p.getName().getString(), "大", p, Markers.GOLD));
                else {
                    ChunkPos c = territoryCenter(s);
                    NbtCompound t = G(s).getCompound("terr");
                    if (c != null) out.add(new MarkerPayload.Marker("residence", "관저 (" + t.getString("name") + ")", "官", t.getString("dim"), c.getCenterX(), p.getBlockY(), c.getCenterZ(), Markers.YELLOW));
                }
            }
        }
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList())
            if (wanted(s, o.getUuid())) out.add(Markers.at("wanted_" + o.getUuid(), "수배: " + o.getName().getString(), "!", o, Markers.RED));
    }

    private static void tick(MinecraftServer s) {
        clock++;
        if (clock % 20 != 0) return;
        long t = now();
        NbtCompound g = G(s);
        // 도전 응답 시간 초과
        for (UUID pres : new ArrayList<>(PENDING_UNTIL.keySet())) {
            if (PENDING_UNTIL.get(pres) > t) continue;
            UUID c = PENDING.remove(pres);
            PENDING_UNTIL.remove(pres);
            if (c != null) refuse(s, c, "무응답");
        }
        // 내란 시간 종료
        if (!g.getString("insur").isEmpty() && g.getLong("insur.until") <= t) failInsurrection(s, "15분 안에 대통령을 끌어내리지 못함");
        // 공약: 신속
        if (clock % 200 == 0) {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList())
                if ("speed".equals(activePledge(s, p.getUuid()))) p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 260, 0, true, false));
        }
        UUID pres = president(s);
        ServerPlayerEntity pp = pres == null ? null : s.getPlayerManager().getPlayer(pres);
        // 계엄
        if (martial(s) && pp != null) {
            pp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 2, true, false));
            pp.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 40, 1, true, false));
            EntityAttributeInstance kb = pp.getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
            if (kb != null && kb.getModifier(MARTIAL_KB) == null) kb.addTemporaryModifier(new EntityAttributeModifier(MARTIAL_KB, 1, EntityAttributeModifier.Operation.ADD_VALUE));
            double r = martialRadius(s);
            for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) {
                EntityAttributeInstance sp = o.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
                boolean in = inMartialZone(s, o);
                if (sp != null) {
                    if (in && sp.getModifier(MARTIAL_SLOW) == null) sp.addTemporaryModifier(new EntityAttributeModifier(MARTIAL_SLOW, -0.3, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                    else if (!in) sp.removeModifier(MARTIAL_SLOW);
                }
                if (!in) {
                    // 구역 밖으로 막 나갔으면 경계로 되돌림
                    if (o != pp && o.getServerWorld() == pp.getServerWorld()) {
                        double d = o.distanceTo(pp);
                        if (d > r && d < r + 6) {
                            Vec3d back = pp.getPos().add(o.getPos().subtract(pp.getPos()).normalize().multiply(r - 1));
                            o.requestTeleport(back.x, o.getY(), back.z);
                            o.sendMessage(Text.literal("🚨 계엄 구역을 벗어날 수 없습니다").formatted(Formatting.RED), true);
                        }
                    }
                    continue;
                }
                if (o.isFallFlying()) o.stopFallFlying();
                if (o.getAbilities().flying && !o.isCreative()) { o.getAbilities().flying = false; o.sendAbilitiesUpdate(); }
                if (o.getVehicle() instanceof PokemonEntity) o.stopRiding();
                if (CombatMode.active(o)) CombatMode.exit(o, "🚨 계엄으로 전투 모드가 풀렸습니다");
            }
            if (clock % 60 == 0) {
                ServerWorld w = pp.getServerWorld();
                for (int i = 0; i < 64; i++) {
                    double a = Math.PI * 2 * i / 64;
                    w.spawnParticles(new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.8f, 0f, 0f), 2f),
                            pp.getX() + Math.cos(a) * Math.min(r, 48), pp.getY() + 1, pp.getZ() + Math.sin(a) * Math.min(r, 48), 1, 0, 0.5, 0, 0);
                }
            }
        } else if (!g.getCompound("martial").isEmpty() && g.getCompound("martial").getLong("until") <= t) {
            g.remove("martial");
            endMartialEffects(s);
            if (pres != null) penalty(s, pres, 30, "비상계엄 종료");
            s.getPlayerManager().broadcast(Text.literal("🏛 비상계엄이 끝났습니다").formatted(Formatting.GOLD), false);
            dirty(s);
        }
        // 대통령 접속 기록 · 탄핵 · 잠수 (1분마다)
        if (clock % 1200 == 0 && pres != null) {
            AlchemyData.Entry e = AlchemyData.get(s).of(pres);
            if (pp != null) e.time(P + "seen", t);
            else if (e.time(P + "seen") > 0 && t - e.time(P + "seen") > 7 * 86_400_000L) { vacate(s, "7일 동안 자리를 비움"); return; }
            boolean low = playerSupporters(s, pres) == 0 && villagers(e) < 20;
            if (!low) g.remove("pres.low");
            else if (!g.contains("pres.low")) g.putLong("pres.low", t);
            else if (t - g.getLong("pres.low") > 7 * 86_400_000L) vacate(s, "7일 동안 지지자가 없어 탄핵");
            dirty(s);
        }
        if (clock % 72000 == 0) {
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                AlchemyData.Entry e = Jobs.entry(p);
                if (is(e) && e.num(P + "malus") > 0) e.num(P + "malus", Math.max(0, e.num(P + "malus") - 10));
            }
        }
    }

    // ═════════ 전투 기술 ═════════

    static final JobSkills.Skill[] LEADER = {
            new JobSkills.Skill("연설", "연설", 2, 20, 90, "16칸 안 당원 30초 저항 I + 신속 I · 30초 동안 주민 설득 에메랄드 3개"),
            new JobSkills.Skill("유세", "유세", 3, 30, 600, "32칸 안 플레이어에게 [지지하기] 버튼을 보냄"),
            new JobSkills.Skill("공약", "공약", 3, 0, 5, "오늘의 공약 고르기 (당원 전체, 하루 1번)"),
            new JobSkills.Skill("정치 자금", "자금", 4, 0, 3, "당 금고 열기 · 당원에게 자금 지급"),
            new JobSkills.Skill("경호원", "경호", 4, 30, 60, "파티 포켓몬 하나가 60초 동안 나와 경호 (대통령은 2마리)"),
            new JobSkills.Skill("내란", "내란", 5, 60, 60, "15분 동안 대통령 영토·특별 수송 봉인, 위치 실시간 공개, 도전을 거절할 수 없음"),
            new JobSkills.Skill("출마 선언", "출마", 5, 0, 10, "대통령이 없으면 취임, 있으면 도전장을 받음"),
    };
    static final JobSkills.Skill MARTIAL = new JobSkills.Skill("비상계엄", "계엄", 5, 80, 5, "15분 계엄 (7일 1번) — 대통령 주변 128칸(영토 안이면 256칸) 통제");

    static final JobSkills.Set SKILLS = new JobSkills.Set() {
        @Override public JobSkills.Skill[] skills(ServerPlayerEntity p, AlchemyData.Entry e) {
            if (!isPresident(p.getServer(), p.getUuid())) return LEADER;
            JobSkills.Skill[] l = LEADER.clone();
            l[6] = MARTIAL;
            return l;
        }
        @Override public String blocked(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
            if (idx != 5 && inMartialZone(p.getServer(), p)) return "🚨 계엄 구역에서는 내란만 쓸 수 있습니다";
            return null;
        }
        @Override public boolean use(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
            String err = switch (idx) {
                case 0 -> speech(p, e);
                case 1 -> campaign(p);
                case 2 -> { JobMenu.later(p, () -> PoliticsMenu.PledgeMenu.open(p)); yield null; }
                case 3 -> { JobMenu.later(p, () -> PoliticsMenu.FundMenu.open(p)); yield null; }
                case 4 -> guard(p);
                case 5 -> insurrect(p);
                case 6 -> isPresident(p.getServer(), p.getUuid()) ? declareMartial(p) : candidacy(p, e);
                default -> "?";
            };
            if (err != null) { JobSkills.bar(p, err, Formatting.RED); return false; }
            return true;
        }
    };

    static String speech(ServerPlayerEntity p, AlchemyData.Entry e) {
        MinecraftServer s = p.getServer();
        e.time(P + "speech", now() + 30_000);
        int n = 0;
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
            if (o.squaredDistanceTo(p) > 16 * 16 || !(o == p || p.getUuid().equals(partyOf(s, o.getUuid())))) continue;
            o.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 600, 0));
            o.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 600, 0));
            n++;
        }
        ServerWorld w = p.getServerWorld();
        w.playSound(null, p.getBlockPos(), SoundEvents.GOAT_HORN_SOUNDS.get(0).value(), SoundCategory.PLAYERS, 2f, 1f);
        w.spawnParticles(ParticleTypes.NOTE, p.getX(), p.getY() + 2.2, p.getZ(), 12, 1, 0.3, 1, 1);
        p.sendMessage(Text.literal("📣 연설! 당원 " + n + "명에게 힘을 주었다 · 30초 동안 주민 설득 비용 3개").formatted(Formatting.AQUA), true);
        return null;
    }

    static String campaign(ServerPlayerEntity p) {
        int n = 0;
        for (ServerPlayerEntity o : p.getServerWorld().getPlayers()) {
            if (o == p || o.squaredDistanceTo(p) > 32 * 32) continue;
            o.sendMessage(Text.literal("📣 " + p.getName().getString() + " 후보의 유세: \"저를 지지해 주십시오!\" ").formatted(Formatting.AQUA)
                    .append(button("[지지하기]", "/support " + p.getName().getString(), Formatting.GREEN)), false);
            n++;
        }
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.GOAT_HORN_SOUNDS.get(1).value(), SoundCategory.PLAYERS, 3f, 1f);
        return n == 0 ? "32칸 안에 유세를 들을 사람이 없습니다" : null;
    }

    static String guard(ServerPlayerEntity p) {
        int max = isPresident(p.getServer(), p.getUuid()) ? 2 : 1;
        int n = 0;
        for (Pokemon pk : PokemonDoctor.party(p)) {
            if (n >= max) break;
            if (pk == null || pk.isFainted() || pk.getEntity() != null) continue;
            PokemonEntity ent = pk.sendOut(p.getServerWorld(), p.getPos().add(p.getRotationVector().multiply(-1.5)), null, x -> kotlin.Unit.INSTANCE);
            if (ent != null && Thralls.bind(p, ent, 60, 6, max, "경호원", true)) n++;
        }
        return n == 0 ? "내보낼 수 있는 포켓몬이 없습니다" : null;
    }

    static String candidacy(ServerPlayerEntity p, AlchemyData.Entry e) {
        MinecraftServer s = p.getServer();
        checkRank(p, e);
        if (rank(e) < 5) return "대선 후보(등급 5)만 출마할 수 있습니다";
        if (president(s) == null) { inaugurate(p, "출마 선언"); return null; }
        if (Jobs.count(p, DuelMod.CHALLENGE_LETTER) > 0) return "이미 도전장이 있습니다 — 대통령 4칸 안에서 우클릭";
        p.getInventory().offerOrDrop(new ItemStack(DuelMod.CHALLENGE_LETTER));
        p.sendMessage(Text.literal("✉ 도전장을 받았습니다. 대통령을 찾아가 4칸 안에서 우클릭하세요 (위치는 지도에 표시)").formatted(Formatting.GOLD), false);
        return null;
    }
}
