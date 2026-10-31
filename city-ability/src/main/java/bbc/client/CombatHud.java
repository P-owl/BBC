package bbc.client;

import bbc.net.AwakenPayload;
import bbc.net.ComboPayload;
import bbc.net.FxPayload;
import bbc.net.StunPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 전투 연출 (클라이언트).
 *  - 경직 중인 엔티티 몸을 붉게 칠함 (피격 붉은빛을 경직 동안 계속 켜 둠). ABA 경직 더미처럼 경직이 눈에 보인다.
 *  - 몬스터(사람 모양)는 맞을 때 움찔하는 자세, 공중 경직 중엔 팔다리를 벌린 자세 (플레이어는 playerAnimator 모션)
 *  - 내가 넣는 콤보 타수를 화면 왼쪽에 크게 띄움 (1.5초 동안 안 늘면 사라짐)
 */
final class CombatHud {
    private CombatHud() {}

    private static final Map<Integer, Long> STUN = new HashMap<>();
    /** 엔티티 → {맞은 틱, 상태} */
    private static final Map<Integer, long[]> HURT = new HashMap<>();
    /** 몬스터 모션: 엔티티 → (이름, 시작 틱) */
    private static final Map<Integer, String> MOB_MOTION = new HashMap<>();
    private static final Map<Integer, Long> MOB_MOTION_AT = new HashMap<>();

    static void mobMotion(int id, String motion) {
        if (motion.equals("stop") || motion.startsWith("#")) { MOB_MOTION.remove(id); MOB_MOTION_AT.remove(id); return; }
        MOB_MOTION.put(id, motion);
        MOB_MOTION_AT.put(id, clock);
    }
    private static int comboHits;
    private static float comboDamage;
    private static long comboAt = -1000;
    private static long clock;
    private static int awGauge, awLeft, stam, stamMax;
    private static boolean awUlt;
    private static float shakeStr, flashStr;
    private static long shakeAt = -100, flashAt = -100;
    private static float linesStr;
    // 기술 카드 (서버가 4틱마다 보냄)
    private static String skTitle = "";
    private static java.util.List<String> skNames = java.util.List.of();
    private static java.util.List<Integer> skState = java.util.List.of(), skLeft = java.util.List.of(), skMax = java.util.List.of();
    private static long skAt = -100;
    private static String skUlt = "난무";
    // 때린 상대 (체력·경직 막대)
    private static int targetId = -1;
    private static long targetAt = -1000;
    private static float targetShownHp = -1;
    private static long linesAt = -100;

    static void init() {
        ClientPlayNetworking.registerGlobalReceiver(StunPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            if (p.ticks() <= 0) { STUN.remove(p.entityId()); HURT.remove(p.entityId()); return; }
            STUN.put(p.entityId(), clock + p.ticks());
            long[] h = HURT.computeIfAbsent(p.entityId(), k -> new long[]{-100, 0});
            if (p.fresh()) h[0] = clock;
            h[1] = p.state();
        }));
        ClientPlayNetworking.registerGlobalReceiver(ComboPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            comboHits = p.hits();
            comboDamage = p.damage();
            comboAt = clock;
        }));
        ClientPlayNetworking.registerGlobalReceiver(AwakenPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            awGauge = p.gauge(); awLeft = p.left(); awUlt = p.ultReady(); stam = p.stamina(); stamMax = p.staminaMax();
        }));
        ClientPlayNetworking.registerGlobalReceiver(FxPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            if (p.kind() == FxPayload.SHAKE) {
                float left = shakeStr * Math.max(0f, 1f - (clock - shakeAt) / 8f);
                shakeStr = Math.min(2.5f, Math.max(left, p.strength()) + (left > 0 ? p.strength() * 0.3f : 0));
                shakeAt = clock;
            } else if (p.kind() == FxPayload.LINES) { linesStr = Math.max(p.strength(), linesStr * Math.max(0f, 1f - (clock - linesAt) / 7f)); linesAt = clock; }
            else { flashStr = p.strength(); flashAt = clock; }
        }));
        ClientPlayNetworking.registerGlobalReceiver(bbc.net.SkillHudPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            skTitle = p.title(); skUlt = p.ult(); skNames = p.names(); skState = p.state(); skLeft = p.left(); skMax = p.max(); skAt = clock;
        }));
        ClientPlayNetworking.registerGlobalReceiver(bbc.net.StatusPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            guardHeldHud = p.guard() >= 1000; guard = p.guard() % 1000; evadeLeft = p.evadeLeft(); parryLeft = p.parryLeft();
        }));
        ClientPlayNetworking.registerGlobalReceiver(bbc.net.TargetPayload.ID, (p, ctx) -> ctx.client().execute(() -> {
            if (p.entityId() != targetId) targetShownHp = -1;
            targetId = p.entityId(); targetAt = clock;
        }));
        ClientTickEvents.END_CLIENT_TICK.register(CombatHud::tick);
        HudRenderCallback.EVENT.register((ctx, counter) -> render(ctx));
    }

    private static void tick(MinecraftClient c) {
        clock++;
        if (c.world == null) { STUN.clear(); HURT.clear(); return; }
        Iterator<Map.Entry<Integer, Long>> it = STUN.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> en = it.next();
            Entity e = c.world.getEntityById(en.getKey());
            if (e == null || clock > en.getValue()) { it.remove(); HURT.remove(en.getKey()); continue; }
            // 내 화면은 흔들리면 안 되니 나는 빼고
            if (e != c.player && e instanceof LivingEntity le) le.hurtTime = Math.max(le.hurtTime, 2);
        }
    }

    /** 사람 모양 몬스터 모델에 피격 자세를 덧씌움 (LivingEntityRendererMixin 이 setAngles 직후 부름). 각도는 라디안 */
    static void pose(LivingEntity e, BipedEntityModel<?> m, float delta) {
        attackPose(e, m, delta);
        long[] h = HURT.get(e.getId());
        if (h == null) { m.hat.copyTransform(m.head); return; }
        float age = clock - h[0] + delta;
        // 맞은 순간 1틱 만에 확 젖혀졌다가 6틱에 걸쳐 돌아옴
        float f = age < 0 ? 0 : age < 1 ? age : Math.max(0f, 1f - (age - 1f) / 6f);
        int state = (int) h[1];
        if (state == 2) { // 공중 경직: 팔다리를 벌리고 젖혀진 채로
            m.head.pitch -= 0.35f;
            m.rightArm.pitch = -0.6f; m.rightArm.roll = 1.0f;
            m.leftArm.pitch = -0.6f; m.leftArm.roll = -1.0f;
            m.rightLeg.pitch = 0.45f; m.leftLeg.pitch = -0.3f;
            m.rightLeg.roll = 0.15f; m.leftLeg.roll = -0.15f;
        } else if (state == 3) { // 다운: 고개를 떨구고 팔을 늘어뜨림
            m.head.pitch = 0.7f;
            m.rightArm.pitch = 0.3f; m.leftArm.pitch = 0.3f;
            m.rightArm.roll = 0.25f; m.leftArm.roll = -0.25f;
        }
        if (f > 0) { // 움찔: 고개·몸통이 뒤로, 팔이 뒤로 튐
            boolean alt = (h[0] & 1) == 0; // 번갈아 좌우
            m.head.pitch -= 0.55f * f;
            m.head.yaw += (alt ? 0.3f : -0.3f) * f;
            m.body.pitch -= 0.22f * f;
            m.body.yaw += (alt ? 0.2f : -0.2f) * f;
            m.rightArm.pitch += 0.7f * f; m.rightArm.roll += 0.45f * f;
            m.leftArm.pitch += 0.55f * f; m.leftArm.roll -= 0.45f * f;
            m.rightLeg.pitch += 0.2f * f; m.leftLeg.pitch -= 0.15f * f;
        }
        m.hat.copyTransform(m.head);
    }

    /** 몬스터 공격 자세 (전투 AI 몹). 각도는 라디안, 팔 pitch - = 앞으로 */
    private static void attackPose(LivingEntity e, BipedEntityModel<?> m, float delta) {
        String mo = MOB_MOTION.get(e.getId());
        if (mo == null) return;
        if (STUN.containsKey(e.getId())) { MOB_MOTION.remove(e.getId()); return; }
        float a = clock - MOB_MOTION_AT.get(e.getId()) + delta;
        if (mo.startsWith("guard")) { // 팔뚝을 얼굴 앞에 교차 (놓을 때까지)
            m.rightArm.pitch = -1.7f; m.rightArm.yaw = -0.7f; m.leftArm.pitch = -1.75f; m.leftArm.yaw = 0.7f;
            m.body.yaw = 0; return;
        }
        boolean right = !(mo.endsWith("_2") || mo.endsWith("_4"));
        var arm = right ? m.rightArm : m.leftArm;
        var other = right ? m.leftArm : m.rightArm;
        float side = right ? 1f : -1f;
        int hitAt = mo.equals("m1_5") ? 5 : mo.equals("guard_break") ? 10 : 4;
        float end = hitAt + 6;
        if (a > end + 4) { MOB_MOTION.remove(e.getId()); return; }
        // 0 → hitAt: 당김, hitAt: 1틱 만에 뻗음, 이후 천천히 돌아옴
        float wind = a < hitAt ? a / hitAt : 0f;
        float out = a < hitAt ? 0f : a < end ? 1f : Math.max(0f, 1f - (a - end) / 4f);
        float snap = a < hitAt ? 0f : Math.min(1f, (a - hitAt) * 1.5f);
        out *= snap;
        switch (mo) {
            case "launch" -> {
                arm.pitch = lerp(0.4f, -3.0f, out) * (wind > 0 ? 1 : 1);
                if (wind > 0) arm.pitch = 0.4f * wind;
                m.body.pitch = 0.25f * wind - 0.15f * out;
            }
            case "slam" -> {
                float up = a < hitAt ? Math.min(1f, a / 2f) : 0f;
                m.rightArm.pitch = up > 0 ? -3.0f * up : lerp(-3.0f, -0.6f, snap);
                m.leftArm.pitch = m.rightArm.pitch;
                m.body.pitch = 0.45f * out;
                m.head.pitch += 0.4f * out;
            }
            case "m1_5", "guard_break" -> {
                m.rightArm.pitch = lerp(0.5f * wind, -1.7f, out); m.leftArm.pitch = lerp(0.5f * wind, -1.7f, out);
                m.body.yaw = side * (0.6f * wind - 0.5f * out);
            }
            case "parry" -> { m.rightArm.pitch = -2.3f; m.rightArm.yaw = -0.5f; }
            case "barrage" -> { // 2틱마다 좌우 번갈아 지르기
                boolean r = ((int) (a / 2)) % 2 == 0;
                float ph = (a % 2) / 2f;
                (r ? m.rightArm : m.leftArm).pitch = -1.7f + 0.6f * ph;
                (r ? m.leftArm : m.rightArm).pitch = -0.5f;
                m.body.yaw = (r ? 0.5f : -0.5f);
                if (a > 18) MOB_MOTION.remove(e.getId());
            }
            case "awaken" -> { m.rightArm.pitch = -0.4f; m.rightArm.roll = 1.2f; m.leftArm.pitch = -0.4f; m.leftArm.roll = -1.2f; m.head.pitch = -0.4f; }
            default -> { // 평타·공중 평타·기술: 한 손 지르기
                arm.pitch = lerp(0.35f * wind, -1.75f, out);
                arm.yaw = side * -0.15f * out;
                other.pitch = -0.9f;
                m.body.yaw = side * (0.45f * wind - 0.5f * out);
            }
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /**
     * 배고픔 위 스태미나 막대 (체력 줄이 늘어나도 안 겹치게 오른쪽으로 옮김).
     * 숨 방울이 보이면 그 위로, 탈것 체력이 여러 줄이면 그만큼 위로
     */
    private static void staminaBar(DrawContext ctx, MinecraftClient c) {
        if (stamMax <= 0 || c.player == null || c.player.isCreative() || c.player.isSpectator()) return;
        int w = 81, x = ctx.getScaledWindowWidth() / 2 + 91 - w;
        int y = ctx.getScaledWindowHeight() - 39 - 6;
        if (c.player.getVehicle() instanceof net.minecraft.entity.LivingEntity v) {
            int hearts = Math.min(30, (int) (v.getMaxHealth() + 0.5f) / 2);
            y -= (Math.max(1, (hearts + 9) / 10) - 1) * 10;
        }
        if (c.player.isSubmergedIn(net.minecraft.registry.tag.FluidTags.WATER) || c.player.getAir() < c.player.getMaxAir()) y -= 10;
        float r = Math.max(0f, Math.min(1f, stam / (float) stamMax));
        boolean low = r < 0.25f;
        int fw = (int) (w * r);
        ctx.fill(x - 1, y - 1, x + w + 1, y + 4, 0xFF000000);
        ctx.fill(x, y, x + w, y + 3, 0xFF2A2610);
        if (fw > 0) {
            ctx.fill(x + w - fw, y, x + w, y + 3, low ? 0xFFD83A2A : 0xFFE8B820);   // 오른쪽에서 왼쪽으로 줄어듦 (배고픔과 같은 방향)
            ctx.fill(x + w - fw, y, x + w, y + 1, low ? 0xFFFF7A5A : 0xFFFFE070);
        }
        for (int k = 1; k < 4; k++) ctx.fill(x + w * k / 4, y, x + w * k / 4 + 1, y + 3, 0x66000000);   // 4칸 눈금
        var m = ctx.getMatrices();
        m.push();
        String t = "⚡" + stam;
        m.translate(x - 2, y - 1, 0);
        m.scale(0.6f, 0.6f, 1f);
        ctx.drawTextWithShadow(c.textRenderer, Text.literal(t), -c.textRenderer.getWidth(t), 0, low ? 0xFFFF8A6A : 0xFFF0D860);
        m.pop();
    }

    /**
     * HUD 크기: 마인크래프트 'GUI 크기'가 커도 우리 HUD 는 GUI 크기 3 일 때 크기로 고정 (2는 너무 작았음).
     * 그리는 동안 이 배율로 줄이고, 좌표는 줄인 화면 기준(가상 W·H)으로 계산
     */
    static float hudScale(MinecraftClient c) {
        double g = c.getWindow().getScaleFactor();
        return (float) Math.min(1.0, 3.0 / Math.max(1.0, g));
    }

    /** 색의 투명도만 f 배 */
    static int fade(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        return ((int) (a * f) << 24) | (argb & 0xFFFFFF);
    }

    /** 사각형이 아닌 도형용: 네 점 (시계 반대든 시계든 상관없게 두 번 그림) */
    static void quad(DrawContext ctx, float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4, int col) {
        var vc = ctx.getVertexConsumers().getBuffer(net.minecraft.client.render.RenderLayer.getGui());
        var m = ctx.getMatrices().peek().getPositionMatrix();
        vc.vertex(m, x1, y1, 0).color(col); vc.vertex(m, x2, y2, 0).color(col); vc.vertex(m, x3, y3, 0).color(col); vc.vertex(m, x4, y4, 0).color(col);
        vc.vertex(m, x4, y4, 0).color(col); vc.vertex(m, x3, y3, 0).color(col); vc.vertex(m, x2, y2, 0).color(col); vc.vertex(m, x1, y1, 0).color(col);
        ctx.draw();   // 바로 그려서 뒤에 오는 글자·사각형과 순서가 섞이지 않게
    }

    /** 왼쪽이 비스듬한 평행사변형 막대 (x..x+w, 위쪽 왼끝이 slant 만큼 오른쪽) */
    static void slab(DrawContext ctx, float x, float y, float w, float h, float slant, int col) {
        quad(ctx, x + slant, y, x + w, y, x + w, y + h, x, y + h, col);
    }

    /** HSV 무지개 색 (각성기 준비 막대) */
    static int rainbow(float t) {
        int rgb = java.awt.Color.HSBtoRGB(t - (float) Math.floor(t), 0.75f, 1f);
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /**
     * 화면 위 가운데 각성 막대 (ABA PRESS G 막대): 굵은 검은 테두리, 5칸으로 나뉜 빨간 막대, 위쪽 광택, 양끝 마름모 장식.
     * 다 차면 'PRESS G' 가 깜빡이며 빛나고, 각성 중엔 금색으로 남은 시간
     */
    private static void awakenBar(DrawContext ctx, MinecraftClient c) {
        TextRenderer tr = c.textRenderer;
        float k = hudScale(c);
        var m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);
        int Wv = (int) (ctx.getScaledWindowWidth() / k);
        int w = 220, h = 11, x = (Wv - w) / 2, y = 5;
        boolean awake = awLeft > 0, full = !awake && awGauge >= 100;
        float ratio = awake ? (float) Math.min(1.0, awLeft / 900.0) : awGauge / 100f;
        int fill = awake ? 0xFFE8B020 : full ? ((clock / 4) % 2 == 0 ? 0xFFFF3A3A : 0xFFD01C1C) : 0xFFC01818;
        // 테두리·바탕
        ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF000000);
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF5A5A5A);
        ctx.fill(x, y, x + w, y + h, 0xFF2A0606);
        ctx.fill(x, y, x + (int) (w * ratio), y + h, fill);
        ctx.fill(x, y, x + (int) (w * ratio), y + 3, 0x40FFFFFF);                 // 광택
        ctx.fill(x, y + h - 2, x + (int) (w * ratio), y + h, 0x30000000);         // 아래 그늘
        for (int q = 1; q < 5; q++) ctx.fill(x + w * q / 5 - 1, y, x + w * q / 5 + 1, y + h, 0xFF000000);   // 5칸
        // 양끝 마름모
        for (int side = 0; side < 2; side++) {
            float cx = side == 0 ? x - 6 : x + w + 6, cy = y + h / 2f;
            quad(ctx, cx, cy - 7, cx + 7, cy, cx, cy + 7, cx - 7, cy, 0xFF000000);
            quad(ctx, cx, cy - 5, cx + 5, cy, cx, cy + 5, cx - 5, cy, awake ? 0xFFE8B020 : full ? fill : 0xFF7A1010);
        }
        if (full) ctx.fill(x - 2, y + h + 2, x + w + 2, y + h + 3, fade(0xFFFF5050, 0.5f + 0.5f * (float) Math.sin(clock * 0.6)));
        String label = awake ? "각성 " + (awLeft / 20 + 1) + "초" + (awUlt ? "  -  G 각성기" : "") : full ? "PRESS G" : "각성 " + awGauge + "%";
        m.push();
        m.translate(x + w / 2f, y + 1.5f, 0);
        ctx.drawCenteredTextWithShadow(tr, net.minecraft.text.Text.literal(label).formatted(Formatting.BOLD), 0, 0, full || awake ? 0xFFFFFFFF : 0xFFE0D0D0);
        m.pop();
        m.pop();
    }

    /** 화면 흔들림: 8틱 동안 줄어드는 떨림 (세기 1 ≈ 2.5°) */
    static float[] shake(float delta) {
        float age = clock - shakeAt + delta;
        if (age > 8 || shakeStr <= 0) return null;
        float amp = shakeStr * 2.5f * (1f - age / 8f);
        return new float[]{ (float) Math.sin(age * 9.1) * amp, (float) Math.cos(age * 7.3) * amp * 0.8f };
    }

    /**
     * 집중선 (만화의 集中線): 화면 가장자리에서 가운데로 몰리는 가는 검은 쐐기들. 7틱 동안 매 틱 새로 그려 떨리듯 보이고 점점 옅어짐
     */
    private static void concentrationLines(DrawContext ctx) {
        float la = linesStr * Math.max(0f, 1f - (clock - linesAt) / 7f);
        if (la < 0.03f) return;
        float W = ctx.getScaledWindowWidth(), H = ctx.getScaledWindowHeight();
        float cx = W / 2f, cy = H / 2f, R = (float) Math.hypot(W, H) / 2f + 8;
        java.util.Random r = new java.util.Random(linesAt * 7919L + clock);
        var vc = ctx.getVertexConsumers().getBuffer(net.minecraft.client.render.RenderLayer.getGui());
        var m = ctx.getMatrices().peek().getPositionMatrix();
        int n = 70 + (int) (50 * Math.min(1f, la));
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            float inner = R * (0.38f + 0.4f * r.nextFloat()) * (1.1f - 0.15f * Math.min(1f, la));
            double wa = 0.004 + 0.018 * r.nextDouble();
            int alpha = (int) (Math.min(1f, la) * (150 + r.nextInt(100)));
            int col = (alpha << 24) | (r.nextInt(6) == 0 ? 0xFFFFFF : 0x000000);
            float x1 = cx + (float) Math.cos(a - wa) * R, y1 = cy + (float) Math.sin(a - wa) * R;
            float x2 = cx + (float) Math.cos(a + wa) * R, y2 = cy + (float) Math.sin(a + wa) * R;
            float ix = cx + (float) Math.cos(a) * inner, iy = cy + (float) Math.sin(a) * inner;
            vc.vertex(m, x1, y1, 0).color(col); vc.vertex(m, ix, iy, 0).color(col); vc.vertex(m, ix, iy, 0).color(col); vc.vertex(m, x2, y2, 0).color(col);
            vc.vertex(m, x2, y2, 0).color(col); vc.vertex(m, ix, iy, 0).color(col); vc.vertex(m, ix, iy, 0).color(col); vc.vertex(m, x1, y1, 0).color(col);
        }
        ctx.draw();
    }

    private static int guard = 100, evadeLeft, parryLeft;
    private static boolean guardHeldHud;

    /**
     * 기술 목록 (ABA 오른쪽 아래): 비스듬한 검은 막대가 위에서부터 1~5번, 맨 아래는 각성(G) 막대.
     * 이름은 오른쪽 정렬 + (숫자키). 재사용 중이면 막대가 붉게 물들고 줄어드는 만큼 검게 돌아오며 남은 초,
     * 못 쓰면 글자가 흐림. 각성기가 준비되면 G 막대가 무지개로 흐름. 맨 위에 작은 키 탭 (Q 대시·회피, F 가드)
     * 한동안 싸우지 않고 전부 준비돼 있으면 반투명
     */
    private static void skillCards(DrawContext ctx, MinecraftClient c) {
        if (clock - skAt > 40 || skNames.isEmpty()) return;
        TextRenderer tr = c.textRenderer;
        float k = hudScale(c);
        var m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);
        int Wv = (int) (ctx.getScaledWindowWidth() / k), Hv = (int) (ctx.getScaledWindowHeight() / k);
        int n = skNames.size(), w = 150, h = 14, gap = 3, slant = 6;
        int right = Wv - 8;
        int rows = n + 1;
        int top = Hv - 8 - rows * (h + gap);
        boolean anyCd = skState.contains(1);
        boolean idle = !anyCd && clock - comboAt > 100 && clock - targetAt > 100 && !guardHeldHud && awGauge < 100 && awLeft <= 0;
        float f = idle ? 0.5f : 1f;
        // 키 탭 (오른쪽 정렬, 위로)
        int ty = top - 15;
        String[][] tabs = {
                {"F", guardHeldHud || guard < 100 ? "가드 " + guard : "가드", guard < 30 ? "1" : "0"},
                {"Q", evadeLeft > 0 ? "회피 " + (evadeLeft / 20 + 1) + "s" : "대시·회피", evadeLeft > 0 ? "1" : "0"}};
        int tx = right;
        for (String[] t : tabs) {
            int tw = tr.getWidth(t[1]) + 22;
            int x = tx - tw;
            slab(ctx, x - 1, ty - 1, tw + 2, 13, 4, fade(0xFFB0B0B0, f));
            slab(ctx, x, ty, tw, 11, 4, fade(t[2].equals("1") ? 0xE0401010 : 0xE0181818, f));
            ctx.fill(x + 5, ty + 1, x + 15, ty + 10, fade(0xFFE0E0E0, f));
            ctx.drawCenteredTextWithShadow(tr, t[0], x + 10, ty + 2, fade(0xFF101010, f));
            ctx.drawTextWithShadow(tr, t[1], x + 18, ty + 2, fade(t[2].equals("1") ? 0xFFFF8070 : 0xFFFFFFFF, f));
            tx = x - 4;
        }
        if (!skTitle.isEmpty()) {
            m.push();
            m.translate(right - w, ty - 10, 0);
            m.scale(0.85f, 0.85f, 1);
            ctx.drawTextWithShadow(tr, skTitle, 0, 0, fade(0xFFFFD27A, f));
            m.pop();
        }
        for (int i = 0; i < rows; i++) {
            boolean ult = i == n;
            int y = top + i * (h + gap);
            int x = right - w - (i % 2 == 1 ? 4 : 0);   // 한 줄씩 살짝 들여쓰기 (ABA 처럼 들쭉날쭉)
            String name; int st; int left = 0, max = 1;
            if (ult) {   // 궁극기: 각성 중이고 아직 안 썼을 때만 쓸 수 있음 (각성 게이지는 위쪽 막대)
                name = skUlt;
                st = awLeft > 0 && awUlt ? 0 : 2;
            } else { name = skNames.get(i); st = skState.get(i); if (st == 1) { left = skLeft.get(i); max = Math.max(1, skMax.get(i)); } }
            boolean empty = !ult && name.isEmpty();
            // 테두리 + 바탕
            slab(ctx, x - 1, y - 1, w + 2, h + 2, slant, fade(empty ? 0x66808080 : 0xFFC8C8C8, f));
            slab(ctx, x, y, w, h, slant, fade(0xE6101014, f));
            if (ult && awLeft > 0 && awUlt) {   // 궁극기 준비: 무지개로 흐름
                int seg = 30;
                for (int q = 0; q < seg; q++) {
                    float x0 = x + slant + (w - slant) * q / (float) seg, x1 = x + slant + (w - slant) * (q + 1) / (float) seg;
                    ctx.fill((int) x0, y + 1, (int) Math.ceil(x1), y + h - 1, fade(rainbow(q / (float) seg * 0.8f - clock * 0.02f), f * 0.85f));
                }
            } else if (st == 1) {   // 재사용: 남은 만큼 붉게
                float r = Math.min(1f, left / (float) max);
                slab(ctx, x, y, slant + (w - slant) * r, h, slant, 0xD08A1414);
            }
            ctx.fill(x + slant, y + 1, x + w - 1, y + 3, fade(0x18FFFFFF, f));   // 위 광택
            // 왼쪽 장식 (숫자키 칩)
            float ex = x - 12;
            quad(ctx, ex, y + h / 2f, ex + 7, y - 1, ex + 14, y + h / 2f, ex + 7, y + h + 1, fade(0xFF000000, f));
            quad(ctx, ex + 2, y + h / 2f, ex + 7, y + 1, ex + 12, y + h / 2f, ex + 7, y + h - 1, fade(ult ? (st == 0 ? rainbow(clock * 0.02f) : 0xFF505050) : st == 1 ? 0xFF8A1414 : 0xFFB0B0B0, f));
            if (empty) continue;
            String label = name + (ult ? (st == 0 ? " (G)" : awLeft > 0 ? " · 사용함" : " · 각성 필요") : " (" + (i + 1) + ")");
            int col = st == 2 ? 0xFF8A8A8A : st == 1 ? 0xFFD0A0A0 : 0xFFFFFFFF;
            float sc = Math.min(0.9f, (w - slant - 26) / (float) Math.max(1, tr.getWidth(label)));
            m.push();
            m.translate(x + w - 4, y + h / 2f - 3.5f * sc, 0);
            m.scale(sc, sc, 1);
            ctx.drawTextWithShadow(tr, label, -tr.getWidth(label), 0, fade(col, f));
            m.pop();
            if (!ult && st == 1) {
                String sec = left >= 100 ? String.valueOf((left + 9) / 10) : String.format("%.1f", left / 10f);
                m.push();
                m.translate(x + slant + 4, y + h / 2f - 3.2f, 0);
                m.scale(0.8f, 0.8f, 1);
                ctx.drawTextWithShadow(tr, sec, 0, 0, 0xFFFFE070);
                m.pop();
            }
        }
        m.pop();
    }

    /** 때린 상대: 각성 막대 바로 아래 얇은 막대 (이름·체력·경직). 6초 안 때리면 사라짐 */
    private static void targetBar(DrawContext ctx, MinecraftClient c) {
        if (targetId < 0 || clock - targetAt > 120 || c.world == null) return;
        if (!(c.world.getEntityById(targetId) instanceof net.minecraft.entity.LivingEntity t) || !t.isAlive()) { targetId = -1; return; }
        TextRenderer tr = c.textRenderer;
        float k = hudScale(c);
        var m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);
        int Wv = (int) (ctx.getScaledWindowWidth() / k), w = 150, h = 6;
        int x = Wv / 2 - w / 2, y = 21;
        float hp = Math.max(0, t.getHealth()), max = Math.max(1, t.getMaxHealth());
        if (targetShownHp < 0 || targetShownHp < hp) targetShownHp = hp;
        else targetShownHp = Math.max(hp, targetShownHp - max * 0.01f);
        boolean stunned = STUN.containsKey(targetId) && STUN.get(targetId) > clock;
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xCC101010);
        ctx.fill(x, y, x + w, y + h, 0xAA3A3A3A);
        ctx.fill(x, y, x + Math.round(w * targetShownHp / max), y + h, 0xFFF0F0F0);
        ctx.fill(x, y, x + Math.round(w * hp / max), y + h, stunned ? 0xFFE0281E : 0xFF35C83A);
        for (int q = 1; q < 10; q++) ctx.fill(x + w * q / 10, y, x + w * q / 10 + 1, y + h, 0x55000000);
        m.push();
        m.translate(Wv / 2f, y + h + 1.5f, 0);
        m.scale(0.7f, 0.7f, 1);
        ctx.drawCenteredTextWithShadow(tr, t.getDisplayName().getString() + (stunned ? "  경직" : ""), 0, 0, stunned ? 0xFFFF6A5A : 0xFFE0E0E0);
        m.pop();
        m.pop();
    }

    /**
     * 전투 중 표시 (Deepwoken IN DANGER): 화면 위 가운데에 붉은 해골 마름모 + '전투 중' + 남은 초, 아래 작은 문구.
     * 전투 모드가 꺼져 있어도 보임
     */
    private static void dangerBar(DrawContext ctx, MinecraftClient c) {
        float left = GrowthClient.combatLeft();
        if (left <= 0) return;
        TextRenderer tr = c.textRenderer;
        float k = hudScale(c);
        var m = ctx.getMatrices();
        m.push();
        m.scale(k, k, 1);
        int Wv = (int) (ctx.getScaledWindowWidth() / k);
        int y = BbcClient.combat ? (targetId >= 0 && clock - targetAt <= 120 ? 36 : 24) : 6;
        float pulse = 0.75f + 0.25f * (float) Math.sin(clock * 0.3);
        int red = fade(0xFFE03A30, pulse);
        String label = "전투 중  " + (int) Math.ceil(left) + "초";
        int w = tr.getWidth(label);
        int cx = Wv / 2;
        quad(ctx, cx - w / 2f - 12, y + 4, cx - w / 2f - 6, y - 2, cx - w / 2f, y + 4, cx - w / 2f - 6, y + 10, 0xFF000000);
        quad(ctx, cx - w / 2f - 11, y + 4, cx - w / 2f - 6, y - 1, cx - w / 2f - 1, y + 4, cx - w / 2f - 6, y + 9, red);
        ctx.drawTextWithShadow(tr, net.minecraft.text.Text.literal(label).formatted(Formatting.BOLD), cx - w / 2 + 2, y, red);
        m.push();
        m.translate(cx, y + 11, 0);
        m.scale(0.7f, 0.7f, 1);
        ctx.drawCenteredTextWithShadow(tr, "긴장감이 짙어지며 아드레날린이 솟구친다 — 지금 나가면 도주 처리", 0, 0, 0xFFE0C0B0);
        m.pop();
        m.pop();
    }

    private static void render(DrawContext ctx) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.options.hudHidden) return;
        // 번쩍임: 화면 전체를 하얗게, 6틱에 걸쳐 사라짐
        float fa = flashStr * Math.max(0f, 1f - (clock - flashAt) / 6f);
        if (fa > 0.01f) ctx.fill(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), ((int) (fa * 200) << 24) | 0xFFFFFF);
        concentrationLines(ctx);
        dangerBar(ctx, c);
        if (BbcClient.combat) { awakenBar(ctx, c); staminaBar(ctx, c); skillCards(ctx, c); targetBar(ctx, c); }
        if (comboHits <= 0) return;
        long age = clock - comboAt;
        if (age > 30) return;
        TextRenderer tr = c.textRenderer;
        float kk = hudScale(c);
        ctx.getMatrices().push();
        ctx.getMatrices().scale(kk, kk, 1);
        int y = (int) (ctx.getScaledWindowHeight() / kk) / 2 - 40;
        float pop = age < 3 ? 1.25f - age * 0.08f : 1f; // 늘어날 때 살짝 튐
        var m = ctx.getMatrices();
        m.push();
        m.translate(24, y, 0);
        m.scale(3f * pop, 3f * pop, 1f);
        ctx.drawTextWithShadow(tr, Text.literal(String.valueOf(comboHits)).formatted(Formatting.BOLD), 0, 0, 0xFFE02020);
        m.pop();
        m.push();
        m.translate(24, y + 30, 0);
        m.scale(1.6f, 1.6f, 1f);
        ctx.drawTextWithShadow(tr, Text.literal("COMBO").formatted(Formatting.BOLD), 0, 0, 0xFFB01010);
        m.pop();
        ctx.drawTextWithShadow(tr, Text.literal(String.format("피해 %.1f", comboDamage)), 24, y + 46, 0xFFFFD040);
        ctx.getMatrices().pop();
    }
}
