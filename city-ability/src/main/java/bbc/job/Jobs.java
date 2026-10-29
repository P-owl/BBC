package bbc.job;

import bbc.core.PlayerData;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 직업 등록부와 공용 도우미 */
public final class Jobs {
    private Jobs() {}

    private static final Map<String, JobType> TYPES = new LinkedHashMap<>();

    public static void register(JobType t) { TYPES.put(t.id(), t); }
    public static Collection<JobType> all() { return TYPES.values(); }
    public static JobType byId(String id) { return TYPES.get(id); }

    public static PlayerData.Entry entry(ServerPlayerEntity p) { return PlayerData.get(p.getServer()).of(p.getUuid()); }
    public static void dirty(ServerPlayerEntity p) { PlayerData.get(p.getServer()).markDirty(); }

    public static JobType type(PlayerData.Entry e) { return e == null || e.job.isEmpty() ? null : TYPES.get(e.job); }

    public static int rank(PlayerData.Entry e) {
        JobType t = type(e);
        return t == null ? 0 : (int) e.num(t.prefix() + "rank");
    }

    public static boolean hasCombat(PlayerData.Entry e) {
        JobType t = type(e);
        return t != null && t.skills() != null;
    }

    /** 직업 바꾸기. 이전 직업 데이터는 지움 */
    public static void set(ServerPlayerEntity p, JobType t) {
        PlayerData.Entry e = entry(p);
        quit(p);
        e.job = t.id();
        e.num(t.prefix() + "rank", 1);
        t.onJoin(p, e);
        JobSkills.resetCooldown(p.getUuid());
        dirty(p);
    }

    public static void quit(ServerPlayerEntity p) {
        PlayerData.Entry e = entry(p);
        JobType old = type(e);
        if (old != null) {
            old.onQuit(p, e);
            e.clearJobData(old.prefix());
        }
        e.job = "";
        JobSkills.resetCooldown(p.getUuid());
        dirty(p);
    }
}
