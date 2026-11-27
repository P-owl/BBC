package bbc.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기둥 속 사내 · 빛: 두 아래팔 바깥쪽(새끼손가락 쪽)에서 돋아 주먹 너머까지 휘어 나가는 빛 칼날 (참고: 올스타 배틀 R '빛의 기둥 속 사내' —
 * 모양 원칙만: 아래팔에 붙은 얇은 낫 모양, 끝이 바깥으로 휨). 전투 모드에서만 보이고, 발의 칼날 기술 땐 오른 정강이에도.
 * 모션의 팔꿈치·무릎 굽힘을 따라감 (AutomailRender 와 같은 방식). 빛나게: 밝기 최대 + 눈 레이어(가산)로 한 번 더
 */
public class LightBladeRender extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private static final Identifier TEX = Identifier.of("bbc", "textures/entity/light_blade.png");
    private static final int[] S = {0, 0}, E = {32, 0}, M = {0, 16};
    private static final int FULL = 0xF000F0;

    /** 칼날 마디 (아래 → 끝): {y0, y1, 안쪽 x, 바깥 x} — 오른팔 기준 (바깥 = -x). 아래팔 5 부터 주먹(10) 너머 17 까지, 끝으로 갈수록 바깥으로 휨 */
    private static final float[][] SEGS = {
            {5f, 6.5f, -3.0f, -3.9f},
            {6.5f, 8f, -3.05f, -4.6f},
            {8f, 9.5f, -3.2f, -5.2f},
            {9.5f, 11f, -3.5f, -5.7f},
            {11f, 12.8f, -4.0f, -6.0f},
            {12.8f, 14.4f, -4.7f, -6.1f},
            {14.4f, 15.8f, -5.4f, -6.0f},
            {15.8f, 16.8f, -5.8f, -6.0f}};

    static final ModelPart ARM_R = blade(1, 0f, 0f);
    static final ModelPart ARM_L = blade(-1, 0f, 0f);
    /** 오른 정강이 (다리 좌표: x -2~2, y 0~12) — 팔 칼날과 같은 모양을 1칸 내려 바깥(-x) 으로 */
    static final ModelPart LEG_R = blade(1, 1.0f, 1.0f);

    private static ModelPart blade(int side, float dx, float dy) {
        List<ModelPart.Cuboid> c = new ArrayList<>();
        float t = 0.35f;
        c.add(box(M, side > 0 ? -3.35f + dx : 2.85f - dx, 4.3f + dy, -0.8f, 0.5f, 1.4f, 1.6f));   // 받침 (칼날이 돋는 자리)
        for (float[] s : SEGS) {
            float in = (s[2] + dx) * side, out = (s[3] + dx) * side;
            float x0 = Math.min(in, out), w = Math.abs(out - in);
            c.add(box(S, x0, s[0] + dy, -t / 2, w, s[1] - s[0] + 0.02f, t));                          // 칼몸
            float ex = side > 0 ? x0 - 0.12f : x0 + w - 0.13f;
            c.add(box(E, ex, s[0] + dy, -t / 2 - 0.03f, 0.25f, s[1] - s[0] + 0.02f, t + 0.06f));      // 바깥 날 (하얗게)
        }
        return new ModelPart(c, Map.of());
    }

    public LightBladeRender(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> ctx) {
        super(ctx);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vcp, int light, AbstractClientPlayerEntity player,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        int id = player.getId();
        if (!Anim.lightBlades(id) || player.isInvisible() || StoneSkin.is(id)) return;
        String mo = Anim.lastMotion(id);
        float age = Anim.motionAge(id, tickDelta);
        float grow = 1f;
        if ("pl_awaken".equals(mo) && age < 31) grow = age < 27 ? 0f : (age - 27) / 4f;   // 각성: 27틱에 칼날이 번쩍 돋음
        if (grow <= 0f) return;
        PlayerEntityModel<AbstractClientPlayerEntity> m = getContextModel();
        draw(ARM_R, m.rightArm, BbcClient.bend(player, "rightArm"), 4f, grow, -3.0f, matrices, vcp);
        draw(ARM_L, m.leftArm, BbcClient.bend(player, "leftArm"), 4f, grow, 3.0f, matrices, vcp);
        boolean leg = mo != null && age < 20 && Set.of("pl_4", "pl_leg", "pl_leg_q", "pl_leg_air").contains(mo);
        if (leg) draw(LEG_R, m.rightLeg, BbcClient.bend(player, "rightLeg"), 6f, Math.min(1f, age / 3f), -2.0f, matrices, vcp);
    }

    /** 굽힘 아래쪽: 팔다리 변환 → 굽힘 → (돋는 중이면 받침에서부터 늘림) → 밝게 두 번 그림 */
    private static void draw(ModelPart part, ModelPart follow, float[] bend, float mid, float grow, float baseX, MatrixStack matrices, VertexConsumerProvider vcp) {
        matrices.push();
        follow.rotate(matrices);
        if (bend != null && bend[1] != 0f) {
            float off = mid / 16f;
            matrices.translate(0, off, 0);
            float ax = -bend[0];
            matrices.multiply(new org.joml.Quaternionf().rotateAxis(bend[1], (float) Math.cos(ax), 0, (float) Math.sin(ax)));
            matrices.translate(0, -off, 0);
        }
        if (grow < 1f) {
            float by = 5f / 16f, bx = baseX / 16f;
            matrices.translate(bx, by, 0);
            matrices.scale(grow, grow, 1f);
            matrices.translate(-bx, -by, 0);
        }
        part.setPivot(0, 0, 0);
        part.setAngles(0, 0, 0);
        part.render(matrices, vcp.getBuffer(RenderLayer.getEntityTranslucent(TEX)), FULL, OverlayTexture.DEFAULT_UV);
        part.render(matrices, vcp.getBuffer(RenderLayer.getEyes(TEX)), FULL, OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }

    private static ModelPart.Cuboid box(int[] uv, float x, float y, float z, float sx, float sy, float sz) {
        return new ModelPart.Cuboid(uv[0], uv[1], x, y, z, sx, sy, sz, 0, 0, 0, false, 64, 32, EnumSet.allOf(Direction.class));
    }
}
