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

    public static void play(LivingEntity e, String motion) {
        // 플레이어는 playerAnimator 모션, 사람 모양 몬스터는 클라이언트가 간단한 자세를 덧씌움 (CombatHud.pose)
        MotionPayload pkt = new MotionPayload(e.getId(), motion);
        if (e instanceof ServerPlayerEntity sp && ServerPlayNetworking.canSend(sp, MotionPayload.ID)) ServerPlayNetworking.send(sp, pkt);
        for (ServerPlayerEntity o : PlayerLookup.tracking(e))
            if (o != e && ServerPlayNetworking.canSend(o, MotionPayload.ID)) ServerPlayNetworking.send(o, pkt);
    }
}
