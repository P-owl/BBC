package bus.muhyeon.duel;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 지도 모드(Xaero 미니맵·월드맵) 마커. 서버가 1초마다 모든 마커를 모아 각 클라이언트에 보내고,
 * 클라이언트(XaeroBridge)가 Xaero 의 '제3자 웨이포인트'로 띄운다. Xaero 가 없으면 아무 일도 안 일어남.
 * 색: Xaero 색 번호 (0 검정 … 4 진빨강 … 12 빨강 … 14 노랑 … 15 흰색)
 */
public final class Markers {
    private Markers() {}

    public static final int RED = 12, DARK_RED = 4, GOLD = 6, YELLOW = 14, PURPLE = 13, AQUA = 11, WHITE = 15;

    /** 마커를 모으는 함수들: (서버, 목록에 추가) */
    public static final List<BiConsumer<MinecraftServer, List<MarkerPayload.Marker>>> PROVIDERS = new ArrayList<>();

    private static int clock = 0;

    public static void register() {
        PayloadTypeRegistry.playS2C().register(MarkerPayload.ID, MarkerPayload.CODEC);
        ServerTickEvents.END_SERVER_TICK.register(Markers::tick);
    }

    public static MarkerPayload.Marker at(String id, String label, String initials, ServerPlayerEntity p, int color) {
        return new MarkerPayload.Marker(id, label, initials, p.getServerWorld().getRegistryKey().getValue().toString(),
                (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()), color);
    }

    private static void tick(MinecraftServer server) {
        if (++clock % 20 != 0) return;
        List<MarkerPayload.Marker> all = new ArrayList<>();
        for (var p : PROVIDERS) {
            try { p.accept(server, all); } catch (Throwable t) { DuelMod.LOG.warn("[markers] provider failed", t); }
        }
        MarkerPayload pl = new MarkerPayload(all);
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList())
            if (ServerPlayNetworking.canSend(p, MarkerPayload.ID)) ServerPlayNetworking.send(p, pl);
    }
}
