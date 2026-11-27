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
 * 기둥 속 사내 · 빛: 아래팔에 찬 검은 팔찌에서 돋은 큰 초승달 칼날 (손목 쪽에서 바깥으로 크게 휘어 어깨 높이까지 — 가운데가 두껍고 양끝이 뾰족한 낫,
 * 어두운 강철 + 밝은 날 끝). 전투 모드에서만 보이고, 발의 칼날 기술 땐 오른 정강이에도 작게.
 * 모션의 팔꿈치·무릎 굽힘을 따라감 (AutomailRender 와 같은 방식). 마디 값은 tools 의 crescent.py 로 만듦
 */
public class LightBladeRender extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private static final Identifier TEX = Identifier.of("bbc", "textures/entity/light_blade.png");
    private static final int[] S = {0, 0}, E = {32, 0}, M = {0, 16}, B = {32, 16};
    private static final float T = 0.8f;   // 칼날 두께

    /** 초승달 마디 {가운데 x, 가운데 y, 폭(반지름 방향), 길이(접선), 각도°} — 오른팔 좌표 (바깥 = -x, 아래 = +y) */
    private static final float[][] ARM = {
            {-3.071f, 8.348f, 0.721f, 0.937f, 85.47f},
            {-3.894f, 8.599f, 1.254f, 0.997f, 96.41f},
            {-4.789f, 8.649f, 1.721f, 1.05f, 107.34f},
            {-5.704f, 8.483f, 2.126f, 1.095f, 118.28f},
            {-6.581f, 8.098f, 2.462f, 1.133f, 129.22f},
            {-7.363f, 7.507f, 2.722f, 1.163f, 140.16f},
            {-7.995f, 6.737f, 2.899f, 1.182f, 151.09f},
            {-8.436f, 5.833f, 2.989f, 1.193f, 162.03f},
            {-8.655f, 4.848f, 2.989f, 1.193f, 172.97f},
            {-8.637f, 3.842f, 2.899f, 1.182f, 183.91f},
            {-8.389f, 2.878f, 2.722f, 1.163f, 194.84f},
            {-7.93f, 2.012f, 2.462f, 1.133f, 205.78f},
            {-7.298f, 1.292f, 2.126f, 1.095f, 216.72f},
            {-6.539f, 0.755f, 1.721f, 1.05f, 227.66f},
            {-5.707f, 0.422f, 1.254f, 0.997f, 238.59f},
            {-4.855f, 0.302f, 0.721f, 0.937f, 249.53f}};
    private static final float[][] LEG = {
            {-2.0f, 9.5f, 0.541f, 0.703f, 85.47f},
            {-2.617f, 9.688f, 0.941f, 0.748f, 96.41f},
            {-3.288f, 9.726f, 1.291f, 0.788f, 107.34f},
            {-3.975f, 9.601f, 1.595f, 0.821f, 118.28f},
            {-4.633f, 9.312f, 1.847f, 0.85f, 129.22f},
            {-5.219f, 8.869f, 2.042f, 0.872f, 140.16f},
            {-5.693f, 8.292f, 2.174f, 0.886f, 151.09f},
            {-6.024f, 7.614f, 2.242f, 0.895f, 162.03f},
            {-6.188f, 6.875f, 2.242f, 0.895f, 172.97f},
            {-6.175f, 6.12f, 2.174f, 0.886f, 183.91f},
            {-5.989f, 5.397f, 2.042f, 0.872f, 194.84f},
            {-5.644f, 4.748f, 1.847f, 0.85f, 205.78f},
            {-5.17f, 4.208f, 1.595f, 0.821f, 216.72f},
            {-4.601f, 3.805f, 1.291f, 0.788f, 227.66f},
            {-3.977f, 3.555f, 0.941f, 0.748f, 238.59f},
            {-3.338f, 3.465f, 0.541f, 0.703f, 249.53f}};

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
            c.add(box(E, w / 2 - 0.3f, -l / 2, -T / 2 - 0.05f, 0.42f, l, T + 0.1f)); // 바깥 날 (밝게)
            ModelPart seg = new ModelPart(c, Map.of());
            seg.setPivot(s[0] * side, s[1], 0);
            float ang = side > 0 ? s[4] : 180f - s[4];   // 왼팔은 좌우를 뒤집음
            seg.roll = (float) Math.toRadians(ang);
            kids.put("s" + i, seg);
        }
        List<ModelPart.Cuboid> base = new ArrayList<>();
        if (band) {   // 칼날이 돋는 검은 팔찌 + 징 둘
            base.add(box(M, side > 0 ? -3.3f : -1.2f, 6.2f, -2.3f, 4.5f, 2.2f, 4.6f));
            float sx = side > 0 ? -3.55f : 3.25f;
            base.add(box(B, sx, 6.5f, -0.9f, 0.3f, 0.7f, 0.7f));
            base.add(box(B, sx, 6.5f, 0.3f, 0.3f, 0.7f, 0.7f));
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
