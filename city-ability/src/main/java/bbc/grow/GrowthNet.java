package bbc.grow;

import bbc.core.PlayerData;
import bbc.job.JobSkills;
import bbc.job.Jobs;
import bbc.net.GrowthActionPayload;
import bbc.net.GrowthSyncPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 성장 창(클라이언트 전용 화면)과 주고받기: 상태를 JSON 으로 보내고, 버튼 조작을 받음 */
public final class GrowthNet {
    private GrowthNet() {}

    private static final Set<UUID> DIRTY = new HashSet<>();
    private static long clock;

    public static void markDirty(ServerPlayerEntity p) { DIRTY.add(p.getUuid()); }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(GrowthActionPayload.ID, (pl, ctx) -> ctx.server().execute(() -> act(ctx.player(), pl.action(), pl.arg())));
        ServerTickEvents.END_SERVER_TICK.register(GrowthNet::tick);
    }

    private static void tick(MinecraftServer s) {
        if (++clock % 40 != 0 || DIRTY.isEmpty()) return;
        for (UUID id : new HashSet<>(DIRTY)) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            if (p != null) sync(p, 0);
        }
        DIRTY.clear();
    }

    /** 클라이언트가 이 모드를 갖고 있으면 화면용 상태를 보냄, 없으면 상자 메뉴로 */
    public static void open(ServerPlayerEntity p) {
        Growth.ensureRace(p);
        if (ServerPlayNetworking.canSend(p, GrowthSyncPayload.ID)) sync(p, 1);
        else GrowthMenu.open(p);
    }

    public static void openCards(ServerPlayerEntity p) {
        if (ServerPlayNetworking.canSend(p, GrowthSyncPayload.ID)) sync(p, 2);
        else GrowthMenu.openCards(p);
    }

    public static void sync(ServerPlayerEntity p, int open) {
        if (!ServerPlayNetworking.canSend(p, GrowthSyncPayload.ID)) return;
        DIRTY.remove(p.getUuid());
        ServerPlayNetworking.send(p, new GrowthSyncPayload(json(p).toString(), open));
    }

    private static void act(ServerPlayerEntity p, int action, String arg) {
        switch (action) {
            case GrowthActionPayload.SYNC -> { sync(p, 0); return; }
            case GrowthActionPayload.TRAIT -> { try { Growth.spendTrait(p, Integer.parseInt(arg)); } catch (NumberFormatException ignored) {} }
            case GrowthActionPayload.REROLL -> Growth.rerollRace(p);
            case GrowthActionPayload.CHOOSE -> { Talent t = Talent.byId(arg); if (t != null) Growth.choose(p, t); }
            case GrowthActionPayload.BURN -> { Talent t = Talent.byId(arg); if (t != null) Growth.burn(p, t); }
            case GrowthActionPayload.CONFIRM -> {
                Stat s = Stat.byId(arg);
                if (s == null) break;
                ItemStack tool = findTool(p, s);
                if (tool == null) { p.sendMessage(net.minecraft.text.Text.literal("가방에 " + s.tool + "이(가) 없습니다").formatted(net.minecraft.util.Formatting.GRAY), true); break; }
                if (Growth.confirm(p, s)) GrowthEvents.useTool(p, tool);
            }
            case GrowthActionPayload.EQUIP -> equip(p, arg);
            case GrowthActionPayload.UNEQUIP -> {
                try {
                    int k = Integer.parseInt(arg.trim());
                    var e = bbc.job.Jobs.entry(p);
                    int[] eq = JobSkills.equipped(e);
                    if (k >= 0 && k < JobSkills.SLOTS) { eq[k] = -1; JobSkills.setEquipped(e, eq); bbc.job.Jobs.dirty(p); }
                } catch (NumberFormatException ignored) {}
            }
            default -> {}
        }
        sync(p, 0);
    }

    /** 스탯 창 '기술' 탭: 기술을 칸에 장착. 이미 다른 칸에 있으면 두 칸을 맞바꿈. 등급이 모자라면 안 됨 */
    private static void equip(ServerPlayerEntity p, String arg) {
        String[] a = arg.split(",");
        if (a.length != 2) return;
        int k, idx;
        try { k = Integer.parseInt(a[0].trim()); idx = Integer.parseInt(a[1].trim()); } catch (NumberFormatException ex) { return; }
        var e = bbc.job.Jobs.entry(p);
        JobSkills.Set set = JobSkills.of(e);
        if (set == null || k < 0 || k >= JobSkills.SLOTS) return;
        JobSkills.Skill[] sk = set.skills(p, e);
        if (idx < 0 || idx >= sk.length || sk[idx] == null) return;
        if (sk[idx].rank() > bbc.job.Jobs.rank(e)) { p.sendMessage(net.minecraft.text.Text.literal(sk[idx].name() + " — 아직 등급이 모자랍니다").formatted(net.minecraft.util.Formatting.RED), true); return; }
        int[] eq = JobSkills.equipped(e);
        int was = -1;
        for (int i = 0; i < eq.length; i++) if (eq[i] == idx) was = i;
        if (was == k) return;
        if (was >= 0) eq[was] = eq[k];   // 맞바꿈
        eq[k] = idx;
        JobSkills.setEquipped(e, eq);
        bbc.job.Jobs.dirty(p);
    }

    static ItemStack findTool(ServerPlayerEntity p, Stat s) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) { ItemStack st = inv.getStack(i); if (!st.isEmpty() && GrowthEvents.toolOf(st) == s) return st; }
        return null;
    }

    static int countTools(ServerPlayerEntity p, Stat s) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) { ItemStack st = inv.getStack(i); if (!st.isEmpty() && GrowthEvents.toolOf(st) == s) n += GrowthEvents.usesLeft(st); }
        return n;
    }

    private static JsonObject json(ServerPlayerEntity p) {
        PlayerData.Entry e = Growth.e(p);
        JsonObject o = new JsonObject();
        int pw = Growth.power(e);
        o.addProperty("power", pw);
        o.addProperty("maxPower", Growth.MAX_POWER);
        o.addProperty("ep", Growth.ep(e));
        o.addProperty("epFrom", Growth.epFor(pw));
        o.addProperty("epTo", Growth.epFor(pw + 1));
        o.addProperty("inv", Growth.invested(e));
        o.addProperty("per", Growth.perPower(pw));
        o.addProperty("burns", Growth.burns(e));
        o.addProperty("name", p.getName().getString());
        var job = Jobs.type(e);
        o.addProperty("job", job == null ? "무직" : job.name() + " · " + job.rankName(Jobs.rank(e)));
        Race r = Growth.race(e);
        JsonObject ro = new JsonObject();
        ro.addProperty("name", r.name); ro.addProperty("sub", Growth.raceSub(e)); ro.addProperty("good", r.good); ro.addProperty("bad", r.bad);
        ro.addProperty("color", r.color.getColorValue() == null ? 0xFFFFFF : r.color.getColorValue());
        ro.addProperty("rerolled", e.flag("g.rerolled"));
        o.add("race", ro);
        o.addProperty("injury", Math.max(0, (long) ((e.num("g.injury_until") - System.currentTimeMillis()) / 1000)));
        JsonArray tr = new JsonArray();
        for (int i = 0; i < Growth.TRAITS.length; i++) {
            JsonObject t = new JsonObject();
            t.addProperty("name", Growth.TRAIT_NAMES[i]); t.addProperty("desc", Growth.TRAIT_DESC[i]); t.addProperty("v", Growth.trait(e, i));
            tr.add(t);
        }
        o.add("traits", tr);
        o.addProperty("tp", Math.max(0, Growth.traitPoints(e)));
        JsonArray st = new JsonArray();
        for (Stat s : Stat.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", s.id); j.addProperty("name", s.name); j.addProperty("v", Growth.stat(e, s)); j.addProperty("pend", Growth.pending(e, s));
            j.addProperty("x", Growth.exp(e, s)); j.addProperty("need", Growth.need(e, s)); j.addProperty("how", s.how); j.addProperty("tool", s.tool);
            j.addProperty("tools", countTools(p, s));
            st.add(j);
        }
        o.add("stats", st);
        o.add("cards", cardsJson(Growth.cards(e)));
        o.add("pick", cardsJson(Growth.pick(e)));
        JsonArray ms = new JsonArray();
        JobSkills.Set set = JobSkills.of(e);
        if (set != null) {
            JobSkills.Skill[] sk = set.skills(p, e);
            for (int i = 0; i < sk.length; i++) {
                if (sk[i] == null) continue;
                JsonObject m = new JsonObject();
                m.addProperty("name", sk[i].name()); m.addProperty("m", Growth.mastery(e, i));
                ms.add(m);
            }
        }
        o.add("mastery", ms);
        // 기술 장착 (스탯 창 '기술' 탭)
        JsonArray sks = new JsonArray();
        JsonArray eqa = new JsonArray();
        if (set != null) {
            JobSkills.Skill[] sk = set.skills(p, e);
            int rank = Jobs.rank(e);
            for (int i = 0; i < sk.length; i++) {
                JsonObject m = new JsonObject();
                if (sk[i] == null) { m.addProperty("name", ""); sks.add(m); continue; }
                m.addProperty("name", sk[i].name()); m.addProperty("short", sk[i].shortName());
                m.addProperty("cost", (int) sk[i].cost()); m.addProperty("cd", sk[i].cooldown());
                m.addProperty("lock", sk[i].rank() > rank); m.addProperty("rankName", Jobs.type(e).rankName(sk[i].rank()));
                m.addProperty("desc", sk[i].desc()); m.addProperty("m", Growth.mastery(e, i));
                sks.add(m);
            }
            for (int v : JobSkills.equipped(e)) eqa.add(v);
            o.addProperty("costName", set.costName());
        }
        o.add("skills", sks);
        o.add("equip", eqa);
        return o;
    }

    private static JsonArray cardsJson(java.util.List<Talent> l) {
        JsonArray a = new JsonArray();
        for (Talent t : l) {
            JsonObject c = new JsonObject();
            c.addProperty("id", t.id); c.addProperty("name", t.name); c.addProperty("group", t.group); c.addProperty("rarity", t.rarity);
            c.addProperty("effect", t.effect); c.addProperty("cond", t.cond);
            a.add(c);
        }
        return a;
    }
}
