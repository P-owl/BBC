package bbc.client;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;

/** 믹스인(다른 패키지)에서 CombatHud 의 패키지 전용 메서드를 부르기 위한 다리 */
public final class CombatHudBridge {
    private CombatHudBridge() {}
    public static void pose(LivingEntity e, BipedEntityModel<?> m, float delta) { CombatHud.pose(e, m, delta); }
    public static float[] shake(float delta) { return CombatHud.shake(delta); }
}
