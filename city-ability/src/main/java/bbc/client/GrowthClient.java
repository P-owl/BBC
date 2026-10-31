package bbc.client;

import bbc.net.CombatTagPayload;
import bbc.net.GrowthActionPayload;
import bbc.net.GrowthSyncPayload;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/** 서버가 보낸 성장 상태를 들고 있다가 성장 창·재능 카드 창에 줌. 전투 중 남은 시간도 */
public final class GrowthClient {
    private GrowthClient() {}

    static JsonObject state = new JsonObject();
    static long syncedAt;
    /** 전투 중 남은 틱 (받은 순간 기준) */
    static int combatTicks;
    static long combatAt;

    static void init() {
        ClientPlayNetworking.registerGlobalReceiver(GrowthSyncPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            try { state = JsonParser.parseString(p.json()).getAsJsonObject(); } catch (Exception ignored) { return; }
            syncedAt = System.currentTimeMillis();
            MinecraftClient c = ctx.client();
            if (p.open() == 1) c.setScreen(new GrowthScreen());
            else if (p.open() == 2) c.setScreen(new TalentScreen(true));
            else if (c.currentScreen instanceof TalentScreen ts && state.getAsJsonArray("pick").isEmpty()) ts.close();
        }));
        ClientPlayNetworking.registerGlobalReceiver(CombatTagPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            combatTicks = p.ticks();
            combatAt = System.currentTimeMillis();
        }));
    }

    static void send(int action, String arg) {
        if (ClientPlayNetworking.canSend(GrowthActionPayload.ID)) ClientPlayNetworking.send(new GrowthActionPayload(action, arg));
    }

    /** 전투 중 남은 초 (0 이면 아님) */
    static float combatLeft() {
        if (combatTicks <= 0) return 0;
        float left = combatTicks / 20f - (System.currentTimeMillis() - combatAt) / 1000f;
        return Math.max(0, left);
    }

    static int i(String k) { return state.has(k) ? state.get(k).getAsInt() : 0; }
    static double d(String k) { return state.has(k) ? state.get(k).getAsDouble() : 0; }
    static String s(String k) { return state.has(k) ? state.get(k).getAsString() : ""; }
}
