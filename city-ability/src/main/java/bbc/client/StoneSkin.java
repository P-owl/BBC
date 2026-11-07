package bbc.client;

import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 기둥 속 사내 햇빛 석화: 서버가 "#stone:on" 을 1초마다, 풀리면 "#stone:off" 를 보냄. 3초 동안 소식이 없으면 저절로 풀림 */
public final class StoneSkin {
    private StoneSkin() {}
    public static final Identifier TEXTURE = Identifier.of("bbc", "textures/entity/stone_skin.png");
    private static final Map<Integer, Long> SEEN = new ConcurrentHashMap<>();

    /** 처리했으면 true */
    public static boolean handle(int entityId, String motion) {
        if (motion.equals("#stone:on")) { SEEN.put(entityId, System.currentTimeMillis()); return true; }
        if (motion.equals("#stone:off")) { SEEN.remove(entityId); return true; }
        return false;
    }

    public static boolean is(int entityId) {
        Long t = SEEN.get(entityId);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 3000) { SEEN.remove(entityId); return false; }
        return true;
    }

    public static void clear() { SEEN.clear(); }
}
