package bbc.job;


import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import bbc.combat.CombatMode;

/**
 * 스태미나 (기술·대시·가드 공용 자원).
 *   최대치   = 150 + 직업 등급×30   (파워·민첩 스탯 반영은 3단계에서)
 *   초당 회복 = 5 + 등급×1.2        (전투 모드 밖에서는 2배)
 * 전투 모드 중에는 체력 위 작은 막대로 표시 (CombatHud).
 */
public final class Stamina {
    private Stamina() {}

    private static final Map<UUID, Double> CUR = new HashMap<>();
    private static final Map<UUID, ServerBossBar> BARS = new HashMap<>();
    private static int clock = 0;

    public static double max(ServerPlayerEntity p) {
        int rank = Jobs.rank(Jobs.entry(p));
        return 150 + rank * 30;
    }

    public static double regenPerSecond(ServerPlayerEntity p) {
        int rank = Jobs.rank(Jobs.entry(p));
        return 5 + rank * 1.2;
    }

    public static double get(ServerPlayerEntity p) {
        return CUR.computeIfAbsent(p.getUuid(), k -> max(p));
    }

    /** 충분하면 빼고 true */
    public static boolean spend(ServerPlayerEntity p, double amount) {
        double cur = get(p);
        if (cur + 1e-6 < amount) return false;
        CUR.put(p.getUuid(), cur - amount);
        updateBar(p);
        return true;
    }

    public static void refill(ServerPlayerEntity p) {
        CUR.put(p.getUuid(), max(p));
        updateBar(p);
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Stamina::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            hideBar(handler.getPlayer());
            CUR.remove(handler.getPlayer().getUuid());
        });
    }

    private static void tick(MinecraftServer server) {
        if (++clock % 10 != 0) return; // 0.5초마다
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!Jobs.hasCombat(Jobs.entry(p))) continue;
            double m = max(p);
            double cur = get(p);
            if (cur < m) {
                double gain = regenPerSecond(p) * 0.5 * (CombatMode.active(p) ? 1 : 2);
                CUR.put(p.getUuid(), Math.min(m, cur + gain));
            } else if (cur > m) {
                CUR.put(p.getUuid(), m); // 등급이 떨어지면 최대치도 줄어듦
            }
            if (CombatMode.active(p)) updateBar(p);
        }
    }

    // ───────── 표시 ─────────
    // 예전엔 화면 위 보스바였는데 각성 막대와 겹쳐서, 체력 위 작은 막대로 옮김 (클라이언트 CombatHud 가 그림).
    // 값은 Awaken 의 HUD 패킷에 같이 실려 간다.

    public static void showBar(ServerPlayerEntity p) {}

    public static void hideBar(ServerPlayerEntity p) { BARS.remove(p.getUuid()); }

    private static void updateBar(ServerPlayerEntity p) {}
}
