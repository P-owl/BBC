package bus.muhyeon.duel;

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

/**
 * 연성 기술 스태미나.
 *   최대치   = 150 + 등급×30 + 플레이어 레벨×2     (레벨은 100까지 반영)
 *   초당 회복 = 5 + 등급×1.2 + 플레이어 레벨×0.08  (전투 모드 밖에서는 2배)
 *   예) 견습 Lv.10 → 최대 200, 초당 7.0 / 대연금술사 Lv.40 → 최대 350, 초당 13.0 / 진리 Lv.100 → 최대 500, 초당 19.0
 * 전투 모드 중에는 화면 위 보스바로 표시.
 */
public final class Stamina {
    private Stamina() {}

    private static final Map<UUID, Double> CUR = new HashMap<>();
    private static final Map<UUID, ServerBossBar> BARS = new HashMap<>();
    private static int clock = 0;

    public static double max(ServerPlayerEntity p) {
        int rank = Jobs.jobRank(Jobs.entry(p));
        int lv = Math.min(100, p.experienceLevel);
        return 150 + rank * 30 + lv * 2;
    }

    public static double regenPerSecond(ServerPlayerEntity p) {
        int rank = Jobs.jobRank(Jobs.entry(p));
        int lv = Math.min(100, p.experienceLevel);
        return 5 + rank * 1.2 + lv * 0.08;
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
                CUR.put(p.getUuid(), m); // 레벨이 떨어지면 최대치도 줄어듦
            }
            if (CombatMode.active(p)) updateBar(p);
        }
    }

    // ───────── 보스바 ─────────

    public static void showBar(ServerPlayerEntity p) {
        ServerBossBar bar = BARS.computeIfAbsent(p.getUuid(),
                k -> new ServerBossBar(Text.literal("스태미나"), BossBar.Color.YELLOW, BossBar.Style.NOTCHED_10));
        bar.addPlayer(p);
        updateBar(p);
    }

    public static void hideBar(ServerPlayerEntity p) {
        ServerBossBar bar = BARS.remove(p.getUuid());
        if (bar != null) bar.clearPlayers();
    }

    private static void updateBar(ServerPlayerEntity p) {
        ServerBossBar bar = BARS.get(p.getUuid());
        if (bar == null) return;
        double m = max(p), cur = get(p);
        bar.setPercent((float) Math.max(0, Math.min(1, cur / m)));
        bar.setName(Text.literal("⚡ 스태미나 " + (int) cur + " / " + (int) m + "   (초당 +" + String.format("%.1f", regenPerSecond(p)) + ")"));
        bar.setColor(cur < m * 0.25 ? BossBar.Color.RED : BossBar.Color.YELLOW);
    }
}
