package bbc.core;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 플레이어별 저장 데이터 + 서버 전체 기록. world/data/bbc_players.dat 에 저장.
 * 직업·성장 데이터는 jd(자유 NBT)에 키 앞머리로 나눠 넣는다 (예: "tr.rank", "stat.str").
 */
public class PlayerData extends PersistentState {
    public static final class Entry {
        /** "" = 무직 */
        public String job = "";
        public NbtCompound jd = new NbtCompound();

        public double num(String k) { return jd.getDouble(k); }
        public void num(String k, double v) { jd.putDouble(k, v); }
        public void addNum(String k, double v) { jd.putDouble(k, jd.getDouble(k) + v); }
        public String str(String k) { return jd.getString(k); }
        public void str(String k, String v) { jd.putString(k, v); }
        public boolean flag(String k) { return jd.getBoolean(k); }
        public void flag(String k, boolean v) { jd.putBoolean(k, v); }

        /** 직업을 그만둘 때 그 직업의 데이터만 지움 */
        public void clearJobData(String prefix) {
            for (String k : new ArrayList<>(jd.getKeys())) if (k.startsWith(prefix)) jd.remove(k);
        }
    }

    private final Map<UUID, Entry> map = new HashMap<>();
    /** 서버 전체 기록 (자리·이벤트 등) */
    public NbtCompound global = new NbtCompound();

    public static final PersistentState.Type<PlayerData> TYPE =
            new PersistentState.Type<>(PlayerData::new, PlayerData::fromNbt, null);

    public static PlayerData get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "bbc_players");
    }

    public Entry of(UUID id) { return map.computeIfAbsent(id, k -> new Entry()); }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.put("__global", global.copy());
        for (Map.Entry<UUID, Entry> e : map.entrySet()) {
            NbtCompound c = new NbtCompound();
            c.putString("job", e.getValue().job);
            c.put("jd", e.getValue().jd.copy());
            nbt.put(e.getKey().toString(), c);
        }
        return nbt;
    }

    public static PlayerData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        PlayerData d = new PlayerData();
        d.global = nbt.getCompound("__global").copy();
        for (String key : nbt.getKeys()) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException ex) { continue; }
            NbtCompound c = nbt.getCompound(key);
            Entry en = new Entry();
            en.job = c.getString("job");
            en.jd = c.getCompound("jd").copy();
            d.map.put(id, en);
        }
        return d;
    }
}
