package bus.muhyeon.duel;

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

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 클라이언트: 진리의 통행료로 잃은 몸과 오토메일을 플레이어 모델에 그림.
 *   팔(오른팔): 잃으면 팔이 사라짐 → 오토메일 의수를 달면 강철 팔
 *   다리(왼다리): 잃으면 다리가 사라지고 나무 의족 → 오토메일 의족을 달면 강철 다리
 *   오토메일 연성 중에는 의수/의족 바깥쪽에 칼날이 솟음
 */
public class AutomailRender extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    public record Body(String toll, String automail, int buff) {
        public boolean armGone() { return "arm".equals(toll); }
        public boolean legGone() { return "leg".equals(toll); }
        public boolean armMetal() { return armGone() && "arm".equals(automail); }
        public boolean legMetal() { return legGone() && "leg".equals(automail); }
    }

    public static final Map<UUID, Body> BODIES = new HashMap<>();

    public static Body body(UUID id) { return BODIES.get(id); }

    private static final Identifier METAL = Identifier.of(DuelMod.MOD_ID, "textures/entity/automail_metal.png");
    private static final Identifier WOOD = Identifier.of(DuelMod.MOD_ID, "textures/entity/wood_peg.png");
    private static final Identifier BLADE = Identifier.of(DuelMod.MOD_ID, "textures/entity/automail_blade.png");

    // 텍스처 영역 (64x32): 금속 L=밝은 판(0,0) D=어두운 강철(32,0) P=광택(0,16) B=볼트·놋쇠(32,16)
    //                      칼날 S=칼몸(0,0) E=날 끝 하이라이트(32,0) M=손잡이·받침(0,16)
    //                      나무 W=나무(0,0) T=가죽끈(32,0) I=쇠(32,16)
    private static final int[] L = {0, 0}, D = {32, 0}, P = {0, 16}, B = {32, 16};

    // ── 오토메일 의수 (오른팔 좌표: x -3~1, y -2~10, z -2~2) ──
    static final ModelPart METAL_ARM = part(
            box(D, -2.6f, -1.5f, -1.6f, 3.2f, 5.5f, 3.2f),          // 위팔 뼈대
            box(L, -3.9f, -2.8f, -2.8f, 5.6f, 3.4f, 5.6f),          // 어깨 장갑
            box(P, -4.0f, 0.4f, -2.9f, 5.8f, 0.7f, 5.8f),           // 어깨 테두리
            box(L, -3.2f, 1.2f, -2.2f, 4.4f, 2.4f, 4.4f),           // 위팔 판
            box(D, -3.3f, 3.6f, -2.3f, 4.6f, 1.8f, 4.6f),           // 팔꿈치 관절
            box(B, -3.7f, 4.1f, -0.5f, 0.5f, 0.9f, 1.0f),           // 관절 볼트(바깥)
            box(B, 0.9f, 4.1f, -0.5f, 0.5f, 0.9f, 1.0f),            // 관절 볼트(안쪽)
            box(L, -3.15f, 5.3f, -2.15f, 4.3f, 3.6f, 4.3f),         // 아래팔 판
            box(P, -3.45f, 5.7f, -1.3f, 0.35f, 2.8f, 2.6f),         // 아래팔 홈(바깥)
            box(D, -2.7f, 8.9f, -1.7f, 3.4f, 0.7f, 3.4f),           // 손목
            box(L, -2.95f, 9.5f, -1.85f, 3.9f, 1.7f, 3.7f),         // 손바닥
            box(D, -2.7f, 11.1f, -1.75f, 3.4f, 1.3f, 0.75f),        // 손가락 1
            box(D, -2.7f, 11.1f, -0.85f, 3.4f, 1.5f, 0.75f),        // 손가락 2
            box(D, -2.7f, 11.1f, 0.05f, 3.4f, 1.4f, 0.75f),         // 손가락 3
            box(D, -2.7f, 11.1f, 0.95f, 3.4f, 1.1f, 0.75f),         // 손가락 4
            box(P, -0.4f, 9.7f, -2.3f, 1.0f, 1.9f, 0.7f));          // 엄지

    // ── 오토메일 의족 (왼다리 좌표: x -2~2, y 0~12, z -2~2) ──
    static final ModelPart METAL_LEG = part(
            box(D, -2.6f, 0, -2.6f, 5.2f, 1.0f, 5.2f),              // 허벅지 고정 링
            box(L, -2.35f, 1.0f, -2.35f, 4.7f, 3.5f, 4.7f),         // 허벅지 판
            box(D, -2.25f, 4.5f, -2.25f, 4.5f, 1.6f, 4.5f),         // 무릎 관절
            box(P, -1.6f, 4.1f, -2.9f, 3.2f, 2.4f, 0.8f),           // 무릎 덮개
            box(B, -2.6f, 4.9f, -0.5f, 0.4f, 0.8f, 1.0f),           // 무릎 볼트
            box(B, 2.2f, 4.9f, -0.5f, 0.4f, 0.8f, 1.0f),
            box(L, -2.1f, 6.1f, -2.1f, 4.2f, 4.4f, 4.2f),           // 정강이 판
            box(D, -1.4f, 6.4f, -2.6f, 2.8f, 3.6f, 0.6f),           // 정강이 보호대
            box(P, -0.5f, 6.3f, 2.0f, 1.0f, 4.0f, 0.6f),            // 뒤쪽 피스톤 막대
            box(D, -1.7f, 10.5f, -1.7f, 3.4f, 0.7f, 3.4f),          // 발목
            box(L, -2.3f, 11.2f, -3.4f, 4.6f, 0.8f, 5.6f),          // 발
            box(D, -2.2f, 11.1f, -3.7f, 4.4f, 0.9f, 1.1f));         // 발끝 덮개

    // ── 나무 의족 ──
    private static final int[] W = {0, 0}, T = {32, 0}, I = {32, 16};
    static final ModelPart PEG_LEG = part(
            box(W, -2.3f, 0, -2.3f, 4.6f, 3.6f, 4.6f),              // 허벅지 받침
            box(T, -2.45f, 0.6f, -2.45f, 4.9f, 0.7f, 4.9f),         // 가죽끈 1
            box(T, -2.45f, 2.3f, -2.45f, 4.9f, 0.7f, 4.9f),         // 가죽끈 2
            box(W, -1.3f, 3.6f, -1.3f, 2.6f, 2.0f, 2.6f),           // 이음목
            box(W, -0.95f, 5.6f, -0.95f, 1.9f, 3.4f, 1.9f),         // 막대 위
            box(W, -0.75f, 9.0f, -0.75f, 1.5f, 2.4f, 1.5f),         // 막대 아래 (가늘어짐)
            box(I, -1.0f, 11.3f, -1.0f, 2.0f, 0.7f, 2.0f));         // 쇠 마개

    // ── 칼날 (오토메일 연성) ──
    private static final int[] S = {0, 0}, E = {32, 0}, M = {0, 16};
    /** 오른팔 아래팔 바깥쪽에서 뻗어 주먹 너머로 길게 */
    static final ModelPart ARM_BLADE = bladePart(-3.6f, 5.6f, 0f, 0.12f, 0f, 13.5f, true);
    /** 왼다리 정강이 바깥쪽에서 아래·앞쪽으로 */
    static final ModelPart LEG_BLADE = bladePart(2.4f, 5.8f, -0.4f, -0.12f, -0.35f, 10f, false);

    /** 받침 + 칼몸(점점 가늘어짐) + 날 하이라이트 + 등줄 */
    private static ModelPart bladePart(float px, float py, float pz, float roll, float pitch, float len, boolean outerNegX) {
        float sx = outerNegX ? -0.5f : 0.1f; // 칼날 두께 방향 위치
        java.util.List<ModelPart.Cuboid> c = new java.util.ArrayList<>();
        c.add(boxAt(M, outerNegX ? -0.9f : 0.0f, -1.2f, -1.3f, 0.9f, 2.6f, 2.6f));   // 받침
        float[][] segs = {{0, 0.34f, 3.0f}, {0.34f, 0.62f, 2.5f}, {0.62f, 0.84f, 1.8f}, {0.84f, 0.95f, 1.0f}, {0.95f, 1.0f, 0.4f}};
        for (float[] sg : segs) {
            float y0 = sg[0] * len, y1 = sg[1] * len, wdt = sg[2];
            c.add(boxAt(S, sx, y0, -wdt / 2, 0.4f, y1 - y0 + 0.02f, wdt));                 // 칼몸
            c.add(boxAt(E, sx - 0.02f, y0, -wdt / 2 - 0.18f, 0.44f, y1 - y0 + 0.02f, 0.2f)); // 앞날 하이라이트
        }
        c.add(boxAt(M, sx - 0.05f, 0, 1.3f, 0.5f, len * 0.6f, 0.25f));                    // 등줄
        ModelPart blade = new ModelPart(c, Map.of());
        blade.setPivot(px, py, pz);
        blade.roll = roll;
        blade.pitch = pitch;
        return new ModelPart(List.of(), Map.of("blade", blade));
    }

    public AutomailRender(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> ctx) {
        super(ctx);
    }

    @Override
    public void render(MatrixStack matrices, VertexConsumerProvider vcp, int light, AbstractClientPlayerEntity player,
                       float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        Body b = body(player.getUuid());
        if (b == null || player.isInvisible()) return;
        PlayerEntityModel<AbstractClientPlayerEntity> m = getContextModel();
        if (b.armMetal()) {
            draw(METAL_ARM, m.rightArm, METAL, matrices, vcp, light);
            if (b.buff() == 1) draw(ARM_BLADE, m.rightArm, BLADE, matrices, vcp, light);
        }
        if (b.legGone()) {
            draw(b.legMetal() ? METAL_LEG : PEG_LEG, m.leftLeg, b.legMetal() ? METAL : WOOD, matrices, vcp, light);
            if (b.legMetal() && b.buff() == 2) draw(LEG_BLADE, m.leftLeg, BLADE, matrices, vcp, light);
        }
    }

    /** 1인칭 팔 (PlayerRendererMixin 에서 호출) */
    public static void drawFirstPersonArm(Body b, ModelPart arm, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        draw(METAL_ARM, arm, METAL, matrices, vcp, light);
        if (b.buff() == 1) draw(ARM_BLADE, arm, BLADE, matrices, vcp, light);
    }

    private static void draw(ModelPart part, ModelPart follow, Identifier tex, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        part.copyTransform(follow);
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(tex));
        part.render(matrices, vc, light, OverlayTexture.DEFAULT_UV);
    }

    private static ModelPart.Cuboid box(int[] uv, float x, float y, float z, float sx, float sy, float sz) {
        return new ModelPart.Cuboid(uv[0], uv[1], x, y, z, sx, sy, sz, 0, 0, 0, false, 64, 32, EnumSet.allOf(Direction.class));
    }

    private static ModelPart.Cuboid boxAt(int[] uv, float x, float y, float z, float sx, float sy, float sz) { return box(uv, x, y, z, sx, sy, sz); }

    private static ModelPart part(ModelPart.Cuboid... cuboids) {
        return new ModelPart(List.of(cuboids), Map.of());
    }
}
