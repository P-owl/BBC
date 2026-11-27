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
 * 기둥 속 사내 · 빛 칼날 (참고: 받은 그림 둘). 아래팔 아래쪽을 감싼 강철 토시 (위아래 테 · 가운데 홈 · 앞뒤 판과 못 · 바깥 판 · 안쪽 끈과 고리) →
 * 바깥쪽 둥근 장식에서 받침대가 뻗어 죔쇠로 칼날 가운데를 붙잡음 → 칼날은 주먹 아래에서 안으로 살짝 휜 갈고리로 시작해 바깥으로 크게 휘며 어깨 위까지
 * (가운데가 두껍고 양끝 뾰족, 오목한 쪽이 팔을 향함 · 푸른 강철 칼몸 + 밝은 바깥 날 + 어두운 안쪽 등줄과 가운데 홈).
 * 치수는 tools/crescent.py (렌더러 미리보기와 같은 값). 전투 모드에서만 보이고 모션의 팔꿈치·무릎 굽힘을 따라감.
 * 각성 땐 27틱에 칼날이 받침대에서부터 돋고, 발의 칼날 기술 땐 오른 정강이에도 같은 모양을 작게
 */
public class LightBladeRender extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private static final Identifier TEX = Identifier.of("bbc", "textures/entity/light_blade.png");
    private static final int TW = 64, TH = 48;
    /** 텍스처 구역: S 칼몸 · E 바깥 날 · M 토시 강철 · B 은빛 테·못·장식 · K 검은 홈·끈 */
    private static final int[] S = {0, 0}, E = {32, 0}, M = {0, 16}, B = {32, 16}, K = {0, 32};
    private static final float T = 0.5f;   // 칼날 두께
    /** 칼날이 돋기 시작하는 자리 (받침대가 칼날을 무는 곳) */
    private static final float GX = -6.2f, GY = 7.4f;

    /** 초승달 마디 {가운데 x, 가운데 y, 폭(반지름 방향), 길이(접선), 각도°} — 오른팔 좌표 (바깥 = -x, 아래 = +y) */
    private static final float[][] SEGS = {
            {-3.471f, 12.101f, 0.321f, 0.856f, 133.16f},
            {-4.06f, 11.675f, 0.559f, 0.866f, 137.48f},
            {-4.606f, 11.186f, 0.757f, 0.875f, 141.8f},
            {-5.106f, 10.644f, 0.929f, 0.883f, 146.11f},
            {-5.557f, 10.055f, 1.077f, 0.89f, 150.43f},
            {-5.954f, 9.423f, 1.2f, 0.895f, 154.75f},
            {-6.293f, 8.755f, 1.3f, 0.9f, 159.07f},
            {-6.571f, 8.056f, 1.375f, 0.903f, 163.39f},
            {-6.783f, 7.332f, 1.424f, 0.906f, 167.7f},
            {-6.927f, 6.591f, 1.448f, 0.907f, 172.02f},
            {-7.003f, 5.84f, 1.447f, 0.907f, 176.34f},
            {-7.013f, 5.085f, 1.428f, 0.906f, 180.66f},
            {-6.958f, 4.333f, 1.391f, 0.904f, 184.98f},
            {-6.838f, 3.59f, 1.337f, 0.902f, 189.3f},
            {-6.654f, 2.862f, 1.267f, 0.898f, 193.61f},
            {-6.409f, 2.155f, 1.18f, 0.894f, 197.93f},
            {-6.106f, 1.475f, 1.077f, 0.89f, 202.25f},
            {-5.746f, 0.826f, 0.958f, 0.884f, 206.57f},
            {-5.334f, 0.215f, 0.823f, 0.878f, 210.89f},
            {-4.873f, -0.355f, 0.671f, 0.871f, 215.2f},
            {-4.366f, -0.877f, 0.499f, 0.864f, 219.52f},
            {-3.814f, -1.344f, 0.296f, 0.854f, 223.84f}};

    static final ModelPart BLADE_R = blade(1), BLADE_L = blade(-1);
    static final ModelPart BRACER_R = bracer(1), BRACER_L = bracer(-1);

    private static ModelPart blade(int side) {
        Map<String, ModelPart> kids = new HashMap<>();
        for (int i = 0; i < SEGS.length; i++) {
            float[] s = SEGS[i];
            float w = s[2], l = s[3];
            List<ModelPart.Cuboid> c = new ArrayList<>();
            c.add(cub(S, -w / 2, -l / 2, -T / 2, w, l, T, 1));                                   // 칼몸
            c.add(cub(E, w / 2 - 0.22f, -l / 2, -T / 2 - 0.04f, 0.3f, l, T + 0.08f, 1));           // 바깥 날 (밝게)
            if (w > 0.5f) c.add(cub(K, -w / 2 - 0.05f, -l / 2, -T / 2 - 0.03f, 0.2f, l, T + 0.06f, 1));     // 안쪽 등줄
            if (w > 0.85f) c.add(cub(K, -w * 0.1f - 0.06f, -l / 2, -T / 2 - 0.02f, 0.12f, l, T + 0.04f, 1)); // 가운데 홈
            ModelPart seg = new ModelPart(c, Map.of());
            seg.setPivot(s[0] * side, s[1], 0);
            seg.roll = (float) Math.toRadians(side > 0 ? s[4] : 180f - s[4]);   // 왼팔은 좌우를 뒤집음
            kids.put("s" + i, seg);
        }
        return new ModelPart(List.of(), kids);
    }

    /** 토시 + 받침대 (오른팔 좌표로 적고 왼팔은 x 를 뒤집음) */
    private static ModelPart bracer(int side) {
        float[][] b = {
            box(M, -3.45f, 5.4f, -2.45f, 4.9f, 4.3f, 4.9f),
            box(B, -3.55f, 5.2f, -2.55f, 5.1f, 0.45f, 5.1f),
            box(B, -3.55f, 9.35f, -2.55f, 5.1f, 0.45f, 5.1f),
            box(K, -3.5f, 7.25f, -2.5f, 5f, 0.3f, 5f),
            box(M, -3f, 5.85f, -2.75f, 4f, 3.3f, 0.3f),
            box(M, -3f, 5.85f, 2.45f, 4f, 3.3f, 0.3f),
            box(M, -3.85f, 5.85f, -1.6f, 0.4f, 3.3f, 3.2f),
            box(K, 1.45f, 6.4f, -1.1f, 0.3f, 2.1f, 2.2f),
            box(B, 1.7f, 7f, -0.55f, 0.2f, 0.9f, 1.1f),
            box(B, -4.35f, 6.8f, -1.1f, 0.5f, 1.2f, 2.2f),
            box(B, -4.35f, 6.6f, -0.9f, 0.5f, 1.6f, 1.8f),
            box(K, -4.5f, 7.15f, -0.35f, 0.2f, 0.5f, 0.7f),
            box(M, -6.186f, 6.85f, -0.55f, 1.886f, 1.1f, 1.1f),
            box(B, -6.186f, 6.7f, -0.3f, 1.886f, 0.2f, 0.6f),
            box(B, -6.186f, 7.95f, -0.3f, 1.886f, 0.2f, 0.6f),
            box(B, -6.686f, 6.35f, -0.5f, 0.9f, 2.1f, 1f),
            box(K, -6.486f, 7.1f, -0.6f, 0.45f, 0.55f, 1.2f),
            box(B, -2.6f, 6.15f, -2.95f, 0.5f, 0.5f, 0.25f),
            box(B, -2.6f, 6.15f, 2.7f, 0.5f, 0.5f, 0.25f),
            box(B, -2.6f, 8.5f, -2.95f, 0.5f, 0.5f, 0.25f),
            box(B, -2.6f, 8.5f, 2.7f, 0.5f, 0.5f, 0.25f),
            box(B, 0.1f, 6.15f, -2.95f, 0.5f, 0.5f, 0.25f),
            box(B, 0.1f, 6.15f, 2.7f, 0.5f, 0.5f, 0.25f),
            box(B, 0.1f, 8.5f, -2.95f, 0.5f, 0.5f, 0.25f),
            box(B, 0.1f, 8.5f, 2.7f, 0.5f, 0.5f, 0.25f)
        };
        List<ModelPart.Cuboid> c = new ArrayList<>();
        for (float[] x : b) c.add(cub(region((int) x[0]), x[1], x[2], x[3], x[4], x[5], x[6], side));
        return new ModelPart(c, Map.of());
    }
    private static final int[][] REGIONS = {S, E, M, B, K};
    private static int[] region(int i) { return REGIONS[i]; }
    private static float[] box(int[] r, float x, float y, float z, float sx, float sy, float sz) {
        int i = 0; while (REGIONS[i] != r) i++;
        return new float[]{i, x, y, z, sx, sy, sz};
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
        draw(BRACER_R, m.rightArm, rb, 4f, 1f, 0, 0, null, light, matrices, vcp);
        draw(BRACER_L, m.leftArm, lb, 4f, 1f, 0, 0, null, light, matrices, vcp);
        if (grow > 0f) {
            draw(BLADE_R, m.rightArm, rb, 4f, grow, GX, GY, null, light, matrices, vcp);
            draw(BLADE_L, m.leftArm, lb, 4f, grow, -GX, GY, null, light, matrices, vcp);
        }
        boolean leg = mo != null && age < 20 && Set.of("pl_4", "pl_leg", "pl_leg_q", "pl_leg_air").contains(mo);
        if (leg) {   // 오른 정강이: 같은 모양을 0.7배로, 토시 가운데(-1, 7.4) → 정강이(0, 8.6)
            float[] xf = {-1f, 7.4f, 0f, 8.6f, 0.7f};
            float[] kb = BbcClient.bend(player, "rightLeg");
            draw(BRACER_R, m.rightLeg, kb, 6f, 1f, 0, 0, xf, light, matrices, vcp);
            draw(BLADE_R, m.rightLeg, kb, 6f, Math.min(1f, age / 3f), GX, GY, xf, light, matrices, vcp);
        }
    }

    /** 굽힘 아래쪽: 팔다리 변환 → 굽힘 → (다리면 옮겨 줄임) → (돋는 중이면 받침대에서부터 키움) → 그림 */
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

    /** side < 0 이면 x 를 뒤집음 */
    private static ModelPart.Cuboid cub(int[] uv, float x, float y, float z, float sx, float sy, float sz, int side) {
        if (side < 0) x = -(x + sx);
        return new ModelPart.Cuboid(uv[0], uv[1], x, y, z, sx, sy, sz, 0, 0, 0, false, TW, TH, EnumSet.allOf(Direction.class));
    }
}
