package bbc.combat;

import bbc.net.MotionPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 모션 재생 요청. 플레이어에게만 (몬스터 모션은 GeckoLib 모델을 만들 때).
 * 본인과 주변에서 그 플레이어를 보고 있는 사람 모두에게 보낸다. 받는 쪽에 playerAnimator 가 없으면 무시된다.
 * 모션 파일: assets/bbc/player_animations/<이름>.json
 */
public final class Motion {
    private Motion() {}

    /** 마지막으로 튼 모션 {이름, 시작 틱} (기술 사용 중 잠금 계산용) */
    private static final java.util.Map<Integer, Object[]> LAST = new java.util.HashMap<>();
    /** 모션 길이 (틱, 모드 안의 player_animations JSON 의 endTick) */
    private static final java.util.Map<String, Integer> LEN = new java.util.concurrent.ConcurrentHashMap<>();

    public static int length(String motion) {
        if (motion == null || motion.startsWith("#") || motion.equals("stop")) return 0;
        return LEN.computeIfAbsent(motion, m -> {
            try (var in = Motion.class.getResourceAsStream("/assets/bbc/player_animations/" + m + ".json")) {
                if (in == null) return 0;
                var o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                var em = o.has("emote") ? o.getAsJsonObject("emote") : o;
                if (em.has("isLoop") && em.get("isLoop").getAsBoolean()) return 0;
                return em.has("endTick") ? em.get("endTick").getAsInt() : 0;
            } catch (Exception ex) { return 0; }
        });
    }

    /** 이번 틱에 튼 모션이 끝나는 틱 (없으면 now) */
    public static long playingUntil(LivingEntity e, long now) {
        Object[] l = LAST.get(e.getId());
        if (l == null) return now;
        return (long) l[1] + length((String) l[0]);
    }

    /** 이 틱(또는 그 뒤)에 시작한 모션이 있으면 그 이름 */
    public static String startedSince(LivingEntity e, long tick) {
        Object[] l = LAST.get(e.getId());
        return l != null && (long) l[1] >= tick ? (String) l[0] : null;
    }

    public static void play(LivingEntity e, String motion) {
        if (!motion.startsWith("#")) {
            if (motion.equals("stop")) LAST.remove(e.getId());
            else LAST.put(e.getId(), new Object[]{motion, Combat.now()});
        }
        // 플레이어는 playerAnimator 모션, 사람 모양 몬스터는 클라이언트가 간단한 자세를 덧씌움 (CombatHud.pose)
        MotionPayload pkt = new MotionPayload(e.getId(), motion);
        if (e instanceof ServerPlayerEntity sp && ServerPlayNetworking.canSend(sp, MotionPayload.ID)) ServerPlayNetworking.send(sp, pkt);
        for (ServerPlayerEntity o : PlayerLookup.tracking(e))
            if (o != e && ServerPlayNetworking.canSend(o, MotionPayload.ID)) ServerPlayNetworking.send(o, pkt);
    }
}
