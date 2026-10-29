package bus.muhyeon.duel;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 플레이어별 연성 에너지 · 이해한(분해해 본) 아이템 목록 · 오늘 얻은 에너지. world/data/duel_alchemy.dat 에 저장 */
public class AlchemyData extends PersistentState {
    public static final class Entry {
        public double energy;
        public final Set<String> known = new LinkedHashSet<>();
        public long day;
        public double gainedToday;
        /** 마지막으로 현자의 돌을 연성한 시각 (ms) */
        public long lastStone;

        // ── 직업 ──
        /** "" = 무직, "alchemist" = 연금술사 */
        public String job = "";
        /** 연금술사 숙련도 (연성에 쓴 에너지 누적) */
        public double xp;
        /** 누적 분해량 (직업 입문 조건) */
        public double depositedTotal;
        /** 진리를 보았는가 */
        public boolean truth;
        /** 진리의 문 통행료: "arm" / "leg" / "heart" */
        public String toll = "";
        /** 진리의 문 연출 중 접속이 끊겼을 때 돌아갈 위치 */
        public String retDim = "";
        public double retX, retY, retZ;
        /** 마지막으로 레이드 연성진을 연성한 시각 (ms) */
        public long lastRaidCircle;
        /** 장착한 오토메일: "arm" / "leg" / "heart" (통행료와 같으면 효과) */
        public String automail = "";

        /**
         * 다른 직업들의 자유 데이터 (키 앞머리로 구분).
         *   "doc.*" 포켓몬 박사, "pri.*" 신부, "vam.*" 뱀파이어, "pol.*" 당대표·대통령
         *   "flag.*" 직업을 바꿔도 남는 계정 기록 (예: 마스터볼을 받은 적 있음)
         */
        public NbtCompound jd = new NbtCompound();

        public double num(String k) { return jd.getDouble(k); }
        public void num(String k, double v) { jd.putDouble(k, v); }
        public double addNum(String k, double v) { double n = jd.getDouble(k) + v; jd.putDouble(k, n); return n; }
        public long time(String k) { return jd.getLong(k); }
        public void time(String k, long v) { jd.putLong(k, v); }
        public String str(String k) { return jd.getString(k); }
        public void str(String k, String v) { jd.putString(k, v); }
        public boolean flag(String k) { return jd.getBoolean(k); }
        public void flag(String k, boolean v) { jd.putBoolean(k, v); }

        /** 직업을 그만둘 때: 그 직업의 진행 데이터만 지움 ("flag.*" 은 남김) */
        public void clearJobData(String prefix) {
            for (String k : new java.util.ArrayList<>(jd.getKeys())) if (k.startsWith(prefix)) jd.remove(k);
        }

        /** 날짜가 바뀌면 지난 날 수만큼 한도를 빼 줌 (한도를 넘겨 분해한 양은 다음 날로 이월) */
        public double gainedToday() { return gainedToday(0); }

        public double gainedToday(double cap) {
            long today = LocalDate.now().toEpochDay();
            if (day != today) {
                long days = day == 0 ? 1 : Math.max(1, today - day);
                gainedToday = cap > 0 ? Math.max(0, gainedToday - cap * days) : 0;
                day = today;
            }
            return gainedToday;
        }
    }

    private final Map<UUID, Entry> map = new HashMap<>();
    /** 서버 전체에서 마지막으로 현자의 돌이 연성된 시각 (ms) */
    public long serverLastStone;
    /** 서버 전체 기록 (기둥 루트 주인, 진행 중인 의식, 대통령, 에이자의 적석 등) */
    public NbtCompound global = new NbtCompound();

    public static final PersistentState.Type<AlchemyData> TYPE =
            new PersistentState.Type<>(AlchemyData::new, AlchemyData::fromNbt, null);

    public static AlchemyData get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "duel_alchemy");
    }

    public Entry of(UUID id) { return map.computeIfAbsent(id, k -> new Entry()); }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putLong("server_last_stone", serverLastStone);
        nbt.put("__global", global.copy());
        for (Map.Entry<UUID, Entry> e : map.entrySet()) {
            NbtCompound c = new NbtCompound();
            Entry en = e.getValue();
            c.putDouble("e", en.energy);
            c.putLong("d", en.day);
            c.putDouble("g", en.gainedToday);
            c.putLong("s", en.lastStone);
            c.putString("job", en.job);
            c.putDouble("xp", en.xp);
            c.putDouble("dep", en.depositedTotal);
            c.putBoolean("truth", en.truth);
            c.putString("toll", en.toll);
            c.putString("rd", en.retDim);
            c.putDouble("rx", en.retX);
            c.putDouble("ry", en.retY);
            c.putDouble("rz", en.retZ);
            c.putLong("rc", en.lastRaidCircle);
            c.putString("am", en.automail);
            c.put("jd", en.jd.copy());
            NbtList list = new NbtList();
            for (String k : en.known) list.add(NbtString.of(k));
            c.put("k", list);
            nbt.put(e.getKey().toString(), c);
        }
        return nbt;
    }

    public static AlchemyData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        AlchemyData d = new AlchemyData();
        d.serverLastStone = nbt.getLong("server_last_stone");
        d.global = nbt.getCompound("__global").copy();
        for (String key : nbt.getKeys()) {
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException ex) { continue; }
            NbtCompound c = nbt.getCompound(key);
            Entry en = new Entry();
            en.energy = c.getDouble("e");
            en.day = c.getLong("d");
            en.gainedToday = c.getDouble("g");
            en.lastStone = c.getLong("s");
            en.job = c.getString("job");
            en.xp = c.getDouble("xp");
            en.depositedTotal = c.getDouble("dep");
            en.truth = c.getBoolean("truth");
            en.toll = c.getString("toll");
            en.retDim = c.getString("rd");
            en.retX = c.getDouble("rx");
            en.retY = c.getDouble("ry");
            en.retZ = c.getDouble("rz");
            en.lastRaidCircle = c.getLong("rc");
            en.automail = c.getString("am");
            en.jd = c.getCompound("jd").copy();
            NbtList list = c.getList("k", NbtElement.STRING_TYPE);
            for (int i = 0; i < list.size(); i++) en.known.add(list.getString(i));
            d.map.put(id, en);
        }
        return d;
    }
}
