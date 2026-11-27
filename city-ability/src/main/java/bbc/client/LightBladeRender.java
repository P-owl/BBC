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
 * 기둥 속 사내 · 빛 칼날 (참고: 받은 그림 — 팔 내린 정면을 재서 옮김, 치수는 tools/crescent.py, 이 파일은 tools/gen_light_blade.py 가 만듦).
 * 아래팔 아래쪽 강철 토시 (은빛 위아래 테 · 줄 · 앞면 둥근 장식과 금빛 가운데) → 토시 바깥 아래쪽에서 칼날이 돋아 팔에서 떨어진 채 바깥으로 완만히 휘며
 * 어깨 위까지 (팔꿈치 높이에서 가장 넓고 끝이 뾰족, 흰빛 도는 푸른 강철 + 밝은 바깥 날 + 짙은 안쪽 등) → 팔꿈치 높이에서 검은 막대 둘이 V 로 팔과 칼날을 이음.
 * 전투 모드에서만 보이고 모션의 팔꿈치·무릎 굽힘을 따라감. 각성 땐 27틱에 칼날이 뿌리에서부터 돋고, 발의 칼날 기술 땐 오른 정강이에도 0.7배로
 */
public class LightBladeRender extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private static final Identifier TEX = Identifier.of("bbc", "textures/entity/light_blade.png");
    private static final int TW = 64, TH = 48;
    /** 텍스처 구역: S 칼몸 · E 날 · D 짙은 파랑 · M 토시 강철 · B 은빛 · K 검정 · G 금빛 */
    private static final int S = 0, E = 1, D = 2, M = 3, B = 4, K = 5, G = 6;
    private static final int[][] UV = {{0, 0}, {32, 0}, {32, 32}, {0, 16}, {32, 16}, {0, 32}, {48, 32}};
    /** 칼날이 돋기 시작하는 자리 (뿌리) */
    private static final float GX = -3.7f, GY = 7.6f;

    /** 돌린 상자 {구역, 가운데 x, y, z, 폭, 길이, 두께, 각도°} — 오른팔 좌표 (바깥 = -x, 아래 = +y). 폭 방향 = 각도 쪽 */
    private static final float[][] BLADE = {
            {S, -3.899f, 7.706f, 0f, 0.751f, 0.625f, 0.45f, 157.29f},
            {E, -4.135f, 7.805f, 0f, 0.26f, 0.625f, 0.51f, 157.29f},
            {D, -3.617f, 7.588f, 0f, 0.16f, 0.625f, 0.49f, 157.29f},
            {S, -4.144f, 7.228f, 0f, 0.852f, 0.625f, 0.45f, 159.18f},
            {E, -4.43f, 7.337f, 0f, 0.26f, 0.625f, 0.51f, 159.18f},
            {D, -3.811f, 7.101f, 0f, 0.16f, 0.625f, 0.49f, 159.18f},
            {S, -4.371f, 6.74f, 0f, 0.948f, 0.625f, 0.45f, 161.07f},
            {E, -4.706f, 6.855f, 0f, 0.26f, 0.625f, 0.51f, 161.07f},
            {D, -3.989f, 6.609f, 0f, 0.16f, 0.625f, 0.49f, 161.07f},
            {S, -4.579f, 6.242f, 0f, 1.035f, 0.625f, 0.45f, 162.96f},
            {E, -4.959f, 6.358f, 0f, 0.26f, 0.625f, 0.51f, 162.96f},
            {D, -4.151f, 6.111f, 0f, 0.16f, 0.625f, 0.49f, 162.96f},
            {S, -4.764f, 5.735f, 0f, 1.11f, 0.625f, 0.45f, 164.85f},
            {E, -5.184f, 5.849f, 0f, 0.26f, 0.625f, 0.51f, 164.85f},
            {D, -4.296f, 5.608f, 0f, 0.16f, 0.625f, 0.49f, 164.85f},
            {S, -4.926f, 5.219f, 0f, 1.171f, 0.625f, 0.45f, 166.74f},
            {E, -5.379f, 5.326f, 0f, 0.26f, 0.625f, 0.51f, 166.74f},
            {D, -4.424f, 5.101f, 0f, 0.16f, 0.625f, 0.49f, 166.74f},
            {S, -5.063f, 4.696f, 0f, 1.216f, 0.625f, 0.45f, 168.62f},
            {E, -5.541f, 4.792f, 0f, 0.26f, 0.625f, 0.51f, 168.62f},
            {D, -4.536f, 4.59f, 0f, 0.16f, 0.625f, 0.49f, 168.62f},
            {S, -5.175f, 4.166f, 0f, 1.242f, 0.625f, 0.45f, 170.51f},
            {E, -5.669f, 4.249f, 0f, 0.26f, 0.625f, 0.51f, 170.51f},
            {D, -4.632f, 4.075f, 0f, 0.16f, 0.625f, 0.49f, 170.51f},
            {S, -5.259f, 3.63f, 0f, 1.25f, 0.625f, 0.45f, 172.4f},
            {E, -5.76f, 3.697f, 0f, 0.26f, 0.625f, 0.51f, 172.4f},
            {D, -4.709f, 3.557f, 0f, 0.16f, 0.625f, 0.49f, 172.4f},
            {S, -5.316f, 3.092f, 0f, 1.239f, 0.625f, 0.45f, 174.29f},
            {E, -5.813f, 3.142f, 0f, 0.26f, 0.625f, 0.51f, 174.29f},
            {D, -4.769f, 3.037f, 0f, 0.16f, 0.625f, 0.49f, 174.29f},
            {S, -5.347f, 2.551f, 0f, 1.211f, 0.625f, 0.45f, 176.18f},
            {E, -5.831f, 2.583f, 0f, 0.26f, 0.625f, 0.51f, 176.18f},
            {D, -4.813f, 2.515f, 0f, 0.16f, 0.625f, 0.49f, 176.18f},
            {S, -5.351f, 2.01f, 0f, 1.165f, 0.625f, 0.45f, 178.07f},
            {E, -5.813f, 2.026f, 0f, 0.26f, 0.625f, 0.51f, 178.07f},
            {D, -4.839f, 1.993f, 0f, 0.16f, 0.625f, 0.49f, 178.07f},
            {S, -5.329f, 1.47f, 0f, 1.101f, 0.625f, 0.45f, 179.96f},
            {E, -5.759f, 1.47f, 0f, 0.26f, 0.625f, 0.51f, 179.96f},
            {D, -4.849f, 1.47f, 0f, 0.16f, 0.625f, 0.49f, 179.96f},
            {S, -5.28f, 0.932f, 0f, 1.022f, 0.625f, 0.45f, 181.85f},
            {E, -5.671f, 0.919f, 0f, 0.26f, 0.625f, 0.51f, 181.85f},
            {D, -4.839f, 0.946f, 0f, 0.16f, 0.625f, 0.49f, 181.85f},
            {S, -5.207f, 0.398f, 0f, 0.926f, 0.625f, 0.45f, 183.74f},
            {E, -5.549f, 0.376f, 0f, 0.26f, 0.625f, 0.51f, 183.74f},
            {D, -4.815f, 0.424f, 0f, 0.16f, 0.625f, 0.49f, 183.74f},
            {S, -5.108f, -0.132f, 0f, 0.816f, 0.625f, 0.45f, 185.63f},
            {E, -5.395f, -0.16f, 0f, 0.26f, 0.625f, 0.51f, 185.63f},
            {D, -4.772f, -0.099f, 0f, 0.16f, 0.625f, 0.49f, 185.63f},
            {S, -4.985f, -0.655f, 0f, 0.69f, 0.625f, 0.45f, 187.52f},
            {E, -5.208f, -0.684f, 0f, 0.26f, 0.625f, 0.51f, 187.52f},
            {D, -4.712f, -0.619f, 0f, 0.16f, 0.625f, 0.49f, 187.52f},
            {S, -4.837f, -1.17f, 0f, 0.551f, 0.625f, 0.45f, 189.41f},
            {E, -4.99f, -1.195f, 0f, 0.26f, 0.625f, 0.51f, 189.41f},
            {D, -4.634f, -1.136f, 0f, 0.16f, 0.625f, 0.49f, 189.41f},
            {S, -4.666f, -1.676f, 0f, 0.395f, 0.625f, 0.45f, 191.29f},
            {E, -4.742f, -1.691f, 0f, 0.26f, 0.625f, 0.51f, 191.29f},
            {S, -4.467f, -2.171f, 0f, 0.217f, 0.625f, 0.45f, 193.18f},
            {E, -4.456f, -2.168f, 0f, 0.26f, 0.625f, 0.51f, 193.18f}};
    private static final float[][] ROT = {
            {K, -3.886f, 3.35f, 0f, 0.22f, 1.937f, 0.22f, 126.788f},
            {K, -3.756f, 4.25f, 0f, 0.22f, 1.5f, 0.22f, 60.005f}};
    /** 곧은 상자 {구역, x0, y0, z0, sx, sy, sz} */
    private static final float[][] BOX = {
            {M, -3.4f, 5.4f, -2.4f, 4.8f, 3.4f, 4.8f},
            {B, -3.5f, 5.1f, -2.5f, 5f, 0.4f, 5f},
            {B, -3.5f, 8.75f, -2.5f, 5f, 0.45f, 5f},
            {D, -3.45f, 6.05f, -2.45f, 4.9f, 0.12f, 4.9f},
            {D, -3.45f, 8.35f, -2.45f, 4.9f, 0.12f, 4.9f},
            {B, -1.7f, 6.55f, -2.65f, 1.4f, 1f, 0.3f},
            {B, -1.5f, 6.35f, -2.65f, 1f, 1.4f, 0.3f},
            {D, -1.4f, 6.65f, -2.72f, 0.8f, 0.8f, 0.1f},
            {G, -1.25f, 6.8f, -2.82f, 0.5f, 0.5f, 0.2f},
            {B, -1.15f, 7.9f, -2.6f, 0.3f, 0.3f, 0.2f},
            {M, -3.85f, 6.6f, -0.5f, 0.5f, 2f, 1f},
            {B, -3.95f, 6.9f, -0.6f, 0.15f, 1.4f, 1.2f},
            {B, -3.45f, 3.6f, -0.3f, 0.6f, 0.6f, 0.6f},
            {K, -4.871f, 2.55f, -0.3f, 0.5f, 0.5f, 0.6f},
            {K, -4.613f, 4.35f, -0.3f, 0.5f, 0.5f, 0.6f}};

    static final ModelPart BLADE_R = rotParts(BLADE, 1), BLADE_L = rotParts(BLADE, -1);
    static final ModelPart GEAR_R = gear(1), GEAR_L = gear(-1);

    /** 돌린 상자들 (왼팔은 x 와 각도를 뒤집음) */
    private static ModelPart rotParts(float[][] rows, int side) {
        Map<String, ModelPart> kids = new HashMap<>();
        for (int i = 0; i < rows.length; i++) {
            float[] r = rows[i];
            float w = r[4], l = r[5], t = r[6];
            ModelPart p = new ModelPart(List.of(cub((int) r[0], -w / 2, -l / 2, -t / 2, w, l, t)), Map.of());
            p.setPivot(r[1] * side, r[2], r[3]);
            p.roll = (float) Math.toRadians(side > 0 ? r[7] : 180f - r[7]);
            kids.put("r" + i, p);
        }
        return new ModelPart(List.of(), kids);
    }

    /** 토시 + 이음대 */
    private static ModelPart gear(int side) {
        List<ModelPart.Cuboid> c = new ArrayList<>();
        for (float[] b : BOX) {
            float x = side > 0 ? b[1] : -(b[1] + b[4]);
            c.add(cub((int) b[0], x, b[2], b[3], b[4], b[5], b[6]));
        }
        ModelPart rods = rotParts(ROT, side);
        return new ModelPart(c, Map.of("rods", rods));
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
        if ("pl_awaken".equals(mo) && age < 31) grow = age < 27 ? 0f : (age - 27) / 4f;   // 각성: 27틱에 칼날이 돋음 (토시는 그대로)
        PlayerEntityModel<AbstractClientPlayerEntity> m = getContextModel();
        float[] rb = BbcClient.bend(player, "rightArm"), lb = BbcClient.bend(player, "leftArm");
        draw(GEAR_R, m.rightArm, rb, 4f, 1f, 0, 0, null, light, matrices, vcp);
        draw(GEAR_L, m.leftArm, lb, 4f, 1f, 0, 0, null, light, matrices, vcp);
        if (grow > 0f) {
            draw(BLADE_R, m.rightArm, rb, 4f, grow, GX, GY, null, light, matrices, vcp);
            draw(BLADE_L, m.leftArm, lb, 4f, grow, -GX, GY, null, light, matrices, vcp);
        }
        boolean leg = mo != null && age < 20 && Set.of("pl_4", "pl_leg", "pl_leg_q", "pl_leg_air").contains(mo);
        if (leg) {   // 오른 정강이: 같은 모양을 0.7배로, 토시 가운데(-1, 7.2) → 정강이(0, 8.4)
            float[] xf = {-1f, 7.2f, 0f, 8.4f, 0.7f};
            float[] kb = BbcClient.bend(player, "rightLeg");
            draw(GEAR_R, m.rightLeg, kb, 6f, 1f, 0, 0, xf, light, matrices, vcp);
            draw(BLADE_R, m.rightLeg, kb, 6f, Math.min(1f, age / 3f), GX, GY, xf, light, matrices, vcp);
        }
    }

    /** 굽힘 아래쪽: 팔다리 변환 → 굽힘 → (다리면 옮겨 줄임) → (돋는 중이면 뿌리에서부터 키움) → 그림 */
    private static void draw(ModelPart part, ModelPart follow, float[] bend, float mid, float grow, float bx, float by, float[] xf,
                             int light, MatrixStack matrices, VertexConsumerProvider vcp) {
        matrices.push();
        follow.rotate(matrices);
        if (bend != null && bend[1] != 0f) {
            float off = mid / 16f;
            matrices.translate(0, off, 0);
            float ax = -bend[0];
            matrices.multiply(new org.joml.Quaternionf().rotateAxis(bend[1], (float) Math.cos(ax), 0, (float) Math.sin(ax)));
            matrices.translate(0, -off, 0);
        }
        if (xf != null) {
            matrices.translate(xf[2] / 16f, xf[3] / 16f, 0);
            matrices.scale(xf[4], xf[4], xf[4]);
            matrices.translate(-xf[0] / 16f, -xf[1] / 16f, 0);
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

    private static ModelPart.Cuboid cub(int region, float x, float y, float z, float sx, float sy, float sz) {
        int[] uv = UV[region];
        return new ModelPart.Cuboid(uv[0], uv[1], x, y, z, sx, sy, sz, 0, 0, 0, false, TW, TH, EnumSet.allOf(Direction.class));
    }
}
