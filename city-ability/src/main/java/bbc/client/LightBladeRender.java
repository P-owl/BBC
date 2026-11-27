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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기둥 속 사내 · 빛 (참고: 받은 그림): 아래팔 아래쪽을 감싼 강철 토시 바깥에서 돋아, 팔에서 떨어진 채 바깥으로 휘며 어깨 높이까지 올라가는 가는 낫
 * (뿌리가 두껍고 끝이 뾰족, 오목한 쪽이 팔을 향함 · 푸른 강철 + 밝은 날 끝). 전투 모드에서만 보이고, 발의 칼날 기술 땐 오른 정강이에도 작게.
 * 모션의 팔꿈치·무릎 굽힘을 따라감 (AutomailRender 와 같은 방식). 마디 값은 tools 의 crescent.py 로 만듦
 */
public class LightBladeRender extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private static final Identifier TEX = Identifier.of("bbc", "textures/entity/light_blade.png");
    private static final int[] S = {0, 0}, E = {32, 0}, M = {0, 16}, B = {32, 16};
    private static final float T = 0.5f;   // 칼날 두께

    /** 초승달 마디 {가운데 x, 가운데 y, 폭(반지름 방향), 길이(접선), 각도°} — 오른팔 좌표 (바깥 = -x, 아래 = +y) */
    private static final float[][] ARM = {
            {-3.803f, 8.462f, 1.12f, 0.846f, 121.33f},
            {-4.423f, 8.093f, 1.219f, 0.853f, 128.0f},
            {-4.994f, 7.645f, 1.301f, 0.858f, 134.67f},
            {-5.505f, 7.125f, 1.364f, 0.862f, 141.33f},
            {-5.945f, 6.54f, 1.406f, 0.865f, 148.0f},
            {-6.307f, 5.902f, 1.427f, 0.867f, 154.67f},
            {-6.582f, 5.221f, 1.427f, 0.867f, 161.33f},
            {-6.765f, 4.51f, 1.406f, 0.865f, 168.0f},
            {-6.855f, 3.784f, 1.364f, 0.862f, 174.67f},
            {-6.849f, 3.055f, 1.301f, 0.858f, 181.33f},
            {-6.749f, 2.336f, 1.219f, 0.853f, 188.0f},
            {-6.559f, 1.64f, 1.12f, 0.846f, 194.67f},
            {-6.284f, 0.98f, 1.004f, 0.838f, 201.33f},
            {-5.931f, 0.366f, 0.874f, 0.829f, 208.0f},
            {-5.507f, -0.194f, 0.733f, 0.819f, 214.67f},
            {-5.023f, -0.691f, 0.582f, 0.809f, 221.33f},
            {-4.489f, -1.119f, 0.424f, 0.798f, 228.0f},
            {-3.914f, -1.475f, 0.262f, 0.787f, 234.67f}};
    private static final float[][] LEG = {
            {-2.0f, 9.5f, 0.84f, 0.634f, 121.33f},
            {-2.465f, 9.223f, 0.914f, 0.64f, 128.0f},
            {-2.893f, 8.887f, 0.976f, 0.643f, 134.67f},
            {-3.276f, 8.497f, 1.023f, 0.646f, 141.33f},
            {-3.607f, 8.059f, 1.054f, 0.649f, 148.0f},
            {-3.878f, 7.58f, 1.07f, 0.65f, 154.67f},
            {-4.084f, 7.069f, 1.07f, 0.65f, 161.33f},
            {-4.221f, 6.536f, 1.054f, 0.649f, 168.0f},
            {-4.289f, 5.992f, 1.023f, 0.646f, 174.67f},
            {-4.285f, 5.445f, 0.976f, 0.643f, 181.33f},
            {-4.21f, 4.905f, 0.914f, 0.64f, 188.0f},
            {-4.067f, 4.383f, 0.84f, 0.634f, 194.67f},
            {-3.861f, 3.889f, 0.753f, 0.628f, 201.33f},
            {-3.596f, 3.428f, 0.655f, 0.622f, 208.0f},
            {-3.278f, 3.008f, 0.55f, 0.614f, 214.67f},
            {-2.915f, 2.635f, 0.436f, 0.607f, 221.33f},
            {-2.514f, 2.314f, 0.318f, 0.599f, 228.0f},
            {-2.083f, 2.047f, 0.197f, 0.59f, 234.67f}};

    static final ModelPart ARM_R = blade(ARM, 1, true);
    static final ModelPart ARM_L = blade(ARM, -1, true);
    static final ModelPart LEG_R = blade(LEG, 1, false);

    private static ModelPart blade(float[][] segs, int side, boolean band) {
        Map<String, ModelPart> kids = new HashMap<>();
        for (int i = 0; i < segs.length; i++) {
            float[] s = segs[i];
            float w = s[2], l = s[3];
            List<ModelPart.Cuboid> c = new ArrayList<>();
            c.add(box(S, -w / 2, -l / 2, -T / 2, w, l, T));                         // 칼몸
            c.add(box(E, w / 2 - 0.22f, -l / 2, -T / 2 - 0.04f, 0.3f, l, T + 0.08f)); // 바깥 날 (밝게)
            ModelPart seg = new ModelPart(c, Map.of());
            seg.setPivot(s[0] * side, s[1], 0);
            float ang = side > 0 ? s[4] : 180f - s[4];   // 왼팔은 좌우를 뒤집음
            seg.roll = (float) Math.toRadians(ang);
            kids.put("s" + i, seg);
        }
        List<ModelPart.Cuboid> base = new ArrayList<>();
        if (band) {   // 칼날이 돋는 팔찌 (아래팔 아래쪽을 감싼 두꺼운 강철 토시) + 바깥 판 + 둥근 장식
            float o = side > 0 ? -1 : 1;   // 바깥 방향
            base.add(box(M, side > 0 ? -3.45f : -1.45f, 5.2f, -2.45f, 4.9f, 4.4f, 4.9f));
            base.add(box(M, side > 0 ? -3.8f : 3.45f, 5.9f, -1.5f, 0.35f, 3.0f, 3.0f));
            base.add(box(B, side > 0 ? -4.05f : 3.8f, 6.9f, -0.5f, 0.25f, 1.0f, 1.0f));
            base.add(box(B, side > 0 ? -3.5f : -1.5f, 5.2f, -2.5f, 5.0f, 0.35f, 5.0f));   // 윗테
            base.add(box(B, side > 0 ? -3.5f : -1.5f, 9.25f, -2.5f, 5.0f, 0.35f, 5.0f));  // 아랫테
        }
        return new ModelPart(base, kids);
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
        if ("pl_awaken".equals(mo) && age < 31) grow = age < 27 ? 0f : (age - 27) / 4f;   // 각성: 27틱에 칼날이 돋음
        if (grow <= 0f) return;
        PlayerEntityModel<AbstractClientPlayerEntity> m = getContextModel();
        draw(ARM_R, m.rightArm, BbcClient.bend(player, "rightArm"), 4f, grow, ARM[0][0], ARM[0][1], light, matrices, vcp);
        draw(ARM_L, m.leftArm, BbcClient.bend(player, "leftArm"), 4f, grow, -ARM[0][0], ARM[0][1], light, matrices, vcp);
        boolean leg = mo != null && age < 20 && Set.of("pl_4", "pl_leg", "pl_leg_q", "pl_leg_air").contains(mo);
        if (leg) draw(LEG_R, m.rightLeg, BbcClient.bend(player, "rightLeg"), 6f, Math.min(1f, age / 3f), LEG[0][0], LEG[0][1], light, matrices, vcp);
    }

    /** 굽힘 아래쪽: 팔다리 변환 → 굽힘 → (돋는 중이면 붙는 자리에서부터 키움) → 그림 */
    private static void draw(ModelPart part, ModelPart follow, float[] bend, float mid, float grow, float bx, float by, int light, MatrixStack matrices, VertexConsumerProvider vcp) {
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
            matrices.translate(bx / 16f, by / 16f, 0);
            matrices.scale(grow, grow, grow);
            matrices.translate(-bx / 16f, -by / 16f, 0);
        }
        part.setPivot(0, 0, 0);
        part.setAngles(0, 0, 0);
        part.render(matrices, vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEX)), light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }

    private static ModelPart.Cuboid box(int[] uv, float x, float y, float z, float sx, float sy, float sz) {
        return new ModelPart.Cuboid(uv[0], uv[1], x, y, z, sx, sy, sz, 0, 0, 0, false, 64, 32, EnumSet.allOf(Direction.class));
    }
}
