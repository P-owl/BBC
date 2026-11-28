package bbc.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 완전생물: 등의 날개 + 변형 팔 (참고: 올스타 배틀 R 카즈 플레이 영상 3:02~3:05 · 3:33 날개, 3:30.5~3:34 붉은 팔 — 모양 원칙만, 우리 모델).
 *
 * 날개: 등 견갑골에서 뼈 두 마디 (위팔·아래팔 + 손목의 분홍 뼈 갈고리) 에 깃털 세 줄 — 아래팔에 긴 첫째 깃털 일곱 (짙은 보라 → 끝 분홍),
 *   위팔에 둘째 깃털 다섯, 위에 짧은 덮깃. 접으면 등 뒤로 비스듬히 내려가고, 펴면 옆 위로 활짝 + 깃털이 부채처럼 퍼짐. 공중이면 날갯짓.
 *   보이는 때: 서버 "#wing:on" (1초마다 — 비행·각성·변신) 또는 날개 기술 모션 중. 2.5초 소식 없으면 접어 사라짐
 * 변형 팔: 변형 팔 기술(ul_arm · ul_arm_q · ul_arm_air) 동안 오른손에서 가시 돋은 붉은 팔이 7칸까지 늘어났다 돌아옴 (팔꿈치 굽힘을 따라감)
 */
public class UltimateRender extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    private static final Identifier TEX = Identifier.of("bbc", "textures/entity/ultimate.png");
    private static final int TW = 64, TH = 48;
    /** 텍스처 구역 (16×16): 짙은 깃털 · 중간 깃털 · 밝은 덮깃 · 분홍 끝 / 뼈 · 붉은 살 · 짙은 붉은 · 흰 가시 */
    private static final int F_DARK = 0, F_MID = 1, F_LIGHT = 2, F_TIP = 3, BONE = 4, RED = 5, RED_DARK = 6, SPIKE = 7;
    private static final int[][] UV = {{0, 0}, {16, 0}, {32, 0}, {48, 0}, {0, 16}, {16, 16}, {32, 16}, {48, 16}};

    private static final ModelPart ARM1 = part(BONE, 0, -0.8f, -0.8f, 9, 1.6f, 1.6f);
    private static final ModelPart ARM2 = part(BONE, 0, -0.6f, -0.6f, 10, 1.2f, 1.2f);
    private static final ModelPart CLAW = part(SPIKE, -0.4f, -3.2f, -0.4f, 0.8f, 3.2f, 0.8f);
    private static final float[] P_LEN = {10, 11, 12, 13, 14, 15, 14};
    private static final float[] S_LEN = {8, 8.5f, 9, 9.5f, 10};
    private static final ModelPart[] PRIM = new ModelPart[P_LEN.length], PRIM_TIP = new ModelPart[P_LEN.length], SEC = new ModelPart[S_LEN.length];
    private static final ModelPart COVERT = part(F_LIGHT, -1.1f, 0, -0.3f, 2.2f, 4.5f, 0.6f);
    static {
        for (int i = 0; i < P_LEN.length; i++) {
            PRIM[i] = part(i % 2 == 0 ? F_DARK : F_MID, -1.0f, 0, -0.25f, 2.0f, P_LEN[i] - 2.5f, 0.5f);
            PRIM_TIP[i] = part(F_TIP, -0.8f, P_LEN[i] - 2.5f, -0.2f, 1.6f, 2.5f, 0.4f);
        }
        for (int i = 0; i < S_LEN.length; i++) SEC[i] = part(i % 2 == 0 ? F_MID : F_DARK, -1.1f, 0, -0.25f, 2.2f, S_LEN[i], 0.5f);
    }
    /** 변형 팔: 단위 상자를 늘려 그림 */
    private static final ModelPart UNIT_RED = part(RED, -0.5f, 0, -0.5f, 1, 1, 1), UNIT_DARK = part(RED_DARK, -0.5f, 0, -0.5f, 1, 1, 1), UNIT_SPIKE = part(SPIKE, -0.5f, 0, -0.5f, 1, 1, 1);
    /** 끝의 뼈 칼날 (휜 낫 모양: 세 마디) */
    private static final ModelPart CLAW_A = part(SPIKE, -1.2f, 0, -0.5f, 2.4f, 3.2f, 1.0f), CLAW_B = part(SPIKE, -0.9f, 0, -0.4f, 1.8f, 3.0f, 0.8f), CLAW_C = part(SPIKE, -0.5f, 0, -0.3f, 1.0f, 2.6f, 0.6f);

    private static final Map<Integer, Long> WING = new ConcurrentHashMap<>();
    private static final Map<Integer, Float> SPREAD = new ConcurrentHashMap<>();

    /** "#wing:on/off" 처리했으면 true */
    public static boolean handle(int id, String motion) {
        if (motion.equals("#wing:on")) { WING.put(id, System.currentTimeMillis()); return true; }
        if (motion.equals("#wing:off")) { WING.remove(id); return true; }
        return false;
    }
    static boolean flagged(int id) {
        Long t = WING.get(id);
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 2500) { WING.remove(id); return false; }
        return true;
    }
    public static void clear() { WING.clear(); SPREAD.clear(); }

    /** 날개를 펴는 모션: {길이, 펴기 시작 틱, 접기 시작 틱} */
    private static final Map<String, int[]> WING_MOTIONS = Map.of(
            "ul_wing", new int[]{30, 2, 24}, "ul_wing_q", new int[]{12, 2, 8}, "ul_wing_air", new int[]{16, 0, 12},
            "ul_fly", new int[]{14, 4, 99}, "ul_feather", new int[]{20, 6, 16}, "ul_awaken", new int[]{64, 34, 99},
            "ul_ult", new int[]{120, 2, 100}, "ul_transform", new int[]{140, 98, 999});

    public UltimateRender(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> ctx) { super(ctx); }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vcp, int light, AbstractClientPlayerEntity player,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        int id = player.getId();
        if (player.isInvisible() || StoneSkin.is(id)) return;
        String mo = Anim.lastMotion(id);
        float age = Anim.motionAge(id, tickDelta);
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(TEX));
        PlayerEntityModel<AbstractClientPlayerEntity> m = getContextModel();

        // ── 날개 ──
        int[] wm = mo != null ? WING_MOTIONS.get(mo) : null;
        boolean inMotion = wm != null && age < wm[0];
        boolean show = flagged(id) || (inMotion && age >= Math.max(0, wm[1] - 2));
        float want = 0f;
        if (show) {
            boolean air = !player.isOnGround();
            want = air ? 1f : 0.22f;
            if (inMotion) want = age >= wm[1] && age < wm[2] ? 1f : air ? 1f : 0.22f;
        }
        float cur = SPREAD.getOrDefault(id, 0f);
        cur += (want - cur) * 0.18f;
        if (!show && cur < 0.05f) { SPREAD.remove(id); }
        else {
            SPREAD.put(id, cur);
            float vis = show ? 1f : Math.min(1f, cur / 0.22f);   // 사라질 땐 접히며 작아짐
            boolean flap = !player.isOnGround() && cur > 0.6f;
            float tt = animationProgress;
            double spd = Math.hypot(player.getX() - player.prevX, player.getZ() - player.prevZ);
            boolean glide = flap && spd > 0.25;
            float fq = glide ? 0.22f : 0.55f, amp = glide ? 0.6f : 1f;
            float f1 = flap ? (float) Math.sin(tt * fq) * amp : (float) Math.sin(tt * 0.08f) * 0.15f;
            float f2 = flap ? (float) Math.sin(tt * fq - 0.7f) * amp : 0f;
            boolean trail = flap && spd > 0.2;
            matrices.push();
            m.body.rotate(matrices);
            for (int side = 1; side >= -1; side -= 2) {
                matrices.push();
                if (side < 0) matrices.scale(-1, 1, 1);
                matrices.translate(1.6f / 16f, 3.2f / 16f, 2.3f / 16f);
                float sc = 1.45f * vis;
                matrices.scale(sc, sc, sc);
                drawWing(matrices, vc, light, cur, f1, f2, trail ? id : -1, side > 0 ? 1 : 0);
                matrices.pop();
            }
            matrices.pop();
        }

        // ── 변형 팔 ──
        float ext = 0f, len = 0f;
        if (mo != null) switch (mo) {
            case "ul_arm" -> { ext = ramp(age, 4, 8, 14, 20); len = 112; }
            case "ul_arm_q" -> { ext = ramp(age, 2, 4, 8, 11); len = 96; }
            case "ul_arm_air" -> { ext = ramp(age, 3, 7, 11, 15); len = 96; }
            default -> { }
        }
        // 팔 모델에 붙여 그리던 변형 팔은 1인칭에서 안 보여서 세상에 그리는 촉수(UltimateFx.Tentacle)로 바꿈 — 여기선 팔 끝에 살덩이 뿌리만
        if (ext > 0.01f && false) drawArm(matrices, vc, light, m.rightArm, BbcClient.bend(player, "rightArm"), ext * len, animationProgress);
    }

    private static float ramp(float t, float a, float b, float c, float d) {
        if (t < a || t > d) return 0f;
        if (t < b) return (t - a) / (b - a);
        if (t <= c) return 1f;
        return 1f - (t - c) / (d - c);
    }

    /** 한쪽 날개 (왼쪽 기준: +x 바깥, -y 위, +z 뒤). s = 펴짐 0~1 */
    private static void drawWing(MatrixStack ms, VertexConsumer vc, int light, float s, float f1, float f2, int trailId, int side) {
        // 위팔: 접으면 등 뒤로 비스듬히 내려감 (아래 55° · 뒤 70°), 펴면 옆 위로 38° · 뒤 12°
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(lerp(-70, -12, s)));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(lerp(55, -38, s) + f1 * 24));
        render(ARM1, ms, vc, light);
        for (int i = 0; i < SEC.length; i++) {   // 둘째 깃털 (위팔)
            ms.push();
            ms.translate((1.2f + i * 1.8f) / 16f, 0.5f / 16f, 0.1f / 16f);
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(lerp(-4, -6 - i * 3, s)));
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(lerp(10, 2, s)));
            render(SEC[i], ms, vc, light);
            ms.pop();
            ms.push();   // 덮깃
            ms.translate((1.6f + i * 1.8f) / 16f, 0.2f / 16f, -0.3f / 16f);
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-8 - i * 2));
            render(COVERT, ms, vc, light);
            ms.pop();
        }
        // 아래팔
        ms.translate(9f / 16f, 0, 0);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(lerp(-35, -8, s)));
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(lerp(10, 26, s) + f2 * 18));
        render(ARM2, ms, vc, light);
        ms.push();   // 손목 뼈 갈고리 (분홍빛 흰 뼈가 위로)
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-10));
        render(CLAW, ms, vc, light);
        ms.pop();
        for (int i = 0; i < PRIM.length; i++) {   // 첫째 깃털: 바깥으로 갈수록 길고 많이 퍼짐
            ms.push();
            ms.translate((0.8f + i * 1.45f) / 16f, 0.4f / 16f, (i % 2) * 0.15f / 16f);
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(lerp(-2, -6 - 52f * i / (PRIM.length - 1), s)));
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(lerp(14, 3, s)));
            render(PRIM[i], ms, vc, light);
            render(PRIM_TIP[i], ms, vc, light);
            if (trailId >= 0 && i == PRIM.length - 2) {   // 가장 긴 깃털 끝 → 바람 자국 (세상 좌표 = 카메라 기준 + 카메라 자리)
                org.joml.Vector3f v = ms.peek().getPositionMatrix().transformPosition(new org.joml.Vector3f(0, P_LEN[i] / 16f, 0));
                net.minecraft.util.math.Vec3d c = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
                UltimateFx.trail(trailId, side, new net.minecraft.util.math.Vec3d(v.x() + c.x, v.y() + c.y, v.z() + c.z));
            }
            ms.pop();
        }
    }

    /** 오른손에서 늘어나는 붉은 팔: 16마디, 끝으로 갈수록 가늘고 바깥쪽에 흰 가시, 앞쪽에 짙은 빨판 */
    private static void drawArm(MatrixStack ms, VertexConsumer vc, int light, ModelPart follow, float[] bend, float length, float t) {
        ms.push();
        follow.rotate(ms);
        if (bend != null && bend[1] != 0f) {
            ms.translate(0, 4f / 16f, 0);
            float ax = -bend[0];
            ms.multiply(new org.joml.Quaternionf().rotateAxis(bend[1], (float) Math.cos(ax), 0, (float) Math.sin(ax)));
            ms.translate(0, -4f / 16f, 0);
        }
        ms.translate(-1f / 16f, 7.5f / 16f, 0);
        int n = 18;
        float seg = length / n;
        for (int i = 0; i < n; i++) {
            float u = i / (float) n;
            float pulse = 1f + 0.06f * (float) Math.sin(t * 0.5f - i * 0.7f);   // 살이 꿈틀대며 부풂
            float w = (5.2f - 3.4f * u + (i < 3 ? 1.4f - i * 0.45f : 0)) * pulse;   // 손목 쪽은 근육 덩어리
            ms.push();
            ms.scale(w / 16f, (seg + 0.6f) / 16f, w * 0.9f / 16f);
            render(UNIT_RED, ms, vc, light);
            ms.pop();
            if (i % 3 == 1) {   // 핏줄 고리 (짙은 띠)
                ms.push(); ms.translate(0, seg * 0.45f / 16f, 0); ms.scale((w + 0.35f) / 16f, Math.max(0.5f, seg * 0.18f) / 16f, (w * 0.9f + 0.35f) / 16f); render(UNIT_DARK, ms, vc, light); ms.pop();
            }
            if (i % 2 == 1 && i < n - 1) {
                for (int sd = -1; sd <= 1; sd += 2) {   // 양옆 흰 가시 (바깥쪽이 더 큼)
                    float sl = (sd < 0 ? 3.4f : 2.2f) * (1f - u * 0.5f);
                    ms.push();
                    ms.translate(sd * w / 2f / 16f, seg * 0.4f / 16f, 0);
                    ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(sd < 0 ? 62 : -62));
                    ms.scale(1.0f / 16f, sl / 16f, 1.0f / 16f);
                    render(UNIT_SPIKE, ms, vc, light);
                    ms.pop();
                }
                ms.push();   // 앞쪽 빨판 (-z)
                ms.translate(0, seg * 0.3f / 16f, -w * 0.45f / 16f);
                ms.scale(w * 0.45f / 16f, w * 0.45f / 16f, 0.7f / 16f);
                render(UNIT_DARK, ms, vc, light);
                ms.pop();
            }
            ms.translate(0, seg / 16f, 0);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees((float) Math.sin(t * 0.3f + i * 0.6f) * 3.5f));
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) Math.sin(t * 0.25f + i * 0.5f) * 2.5f));
        }
        // 끝: 바깥으로 휜 뼈 칼날
        render(CLAW_A, ms, vc, light);
        ms.translate(0, 3.0f / 16f, 0); ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(22)); render(CLAW_B, ms, vc, light);
        ms.translate(0, 2.8f / 16f, 0); ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(26)); render(CLAW_C, ms, vc, light);
        ms.pop();
    }

    private static float lerp(float a, float b, float s) { return a + (b - a) * s; }

    private static void render(ModelPart p, MatrixStack ms, VertexConsumer vc, int light) {
        p.setPivot(0, 0, 0);
        p.setAngles(0, 0, 0);
        p.render(ms, vc, light, OverlayTexture.DEFAULT_UV);
    }

    private static ModelPart part(int region, float x, float y, float z, float sx, float sy, float sz) {
        int[] uv = UV[region];
        return new ModelPart(List.of(new ModelPart.Cuboid(uv[0], uv[1], x, y, z, sx, sy, sz, 0, 0, 0, false, TW, TH, EnumSet.allOf(Direction.class))), Map.of());
    }
}
