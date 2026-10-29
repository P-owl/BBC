package bus.muhyeon.duel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 클라이언트 전용: 서버가 보낸 마커를 Xaero 미니맵의 '제3자 웨이포인트'로 등록 (리플렉션, Xaero 가 없으면 조용히 무시).
 * 내 차원에 있는 마커만 보여줌.
 */
public final class XaeroBridge {
    private XaeroBridge() {}

    private static final Identifier ORIGIN = Identifier.of(DuelMod.MOD_ID, "markers");
    private static List<MarkerPayload.Marker> current = new ArrayList<>();
    private static boolean broken = false;
    private static Constructor<?> WAYPOINT;

    public static void set(List<MarkerPayload.Marker> markers) {
        current = markers;
        apply();
    }

    public static void clear() { current = new ArrayList<>(); apply(); }

    private static void apply() {
        if (broken) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        try {
            Class<?> mods = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Object minimap = mods.getField("MINIMAP").get(null);
            Object session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) return;
            Object wm = session.getClass().getMethod("getWorldManager").invoke(session);
            Object root = wm.getClass().getMethod("getCurrentRootContainer").invoke(wm);
            if (root == null) return;
            Object tpm = find(root.getClass(), "getThirdPartyWaypointManager").invoke(root);
            Object tp = tpm.getClass().getMethod("get", Identifier.class).invoke(tpm, ORIGIN);
            tp.getClass().getMethod("clear").invoke(tp);
            if (WAYPOINT == null) {
                Class<?> wp = Class.forName("xaero.common.minimap.waypoints.Waypoint");
                WAYPOINT = wp.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
            }
            Method add = tp.getClass().getMethod("add", String.class, Class.forName("xaero.common.minimap.waypoints.Waypoint"));
            String dim = mc.world.getRegistryKey().getValue().toString();
            for (MarkerPayload.Marker m : current) {
                if (!m.dim().equals(dim)) continue;
                Object w = WAYPOINT.newInstance(m.x(), m.y(), m.z(), m.label(), m.initials(), m.color());
                add.invoke(tp, m.id(), w);
            }
        } catch (ClassNotFoundException e) {
            broken = true; // Xaero 없음
        } catch (Throwable t) {
            broken = true;
            DuelMod.LOG.warn("[markers] Xaero 연동 실패 — 지도 마커를 끕니다: {}", t.toString());
        }
    }

    private static Method find(Class<?> c, String name) throws NoSuchMethodException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) { m.setAccessible(true); return m; }
        }
        throw new NoSuchMethodException(name);
    }
}
