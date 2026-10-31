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
            skTitle = p.title(); skNames = p.names(); skState = p.state(); skLeft = p.left(); skMax = p.max(); skAt = clock;
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

    /** 체력 위 작은 스태미나 막대 (예전 보스바는 각성 막대와 겹쳐서 옮김) */
    private static void staminaBar(DrawContext ctx, MinecraftClient c) {
        if (stamMax <= 0 || c.player == null || c.player.isCreative() || c.player.isSpectator()) return;
        int w = 81, x = ctx.getScaledWindowWidth() / 2 - 91;
        int y = ctx.getScaledWindowHeight() - 39 - 10 - (c.player.getArmor() > 0 ? 10 : 0) + 4;  // 하트(갑옷) 바로 위
        float r = Math.max(0f, Math.min(1f, stam / (float) stamMax));
        ctx.fill(x - 1, y - 1, x + w + 1, y + 4, 0xFF000000);
        ctx.fill(x, y, x + w, y + 3, 0xFF3A3410);
        ctx.fill(x, y, x + (int) (w * r), y + 3, r < 0.25f ? 0xFFE04030 : 0xFFF0C830);
        var m = ctx.getMatrices();
        m.push();
        m.translate(x + w + 3, y - 1, 0);
        m.scale(0.6f, 0.6f, 1f);
        ctx.drawTextWithShadow(c.textRenderer, Text.literal("⚡" + stam), 0, 0, 0xFFF0D860);
        m.pop();
    }

    /** 화면 위 가운데 각성 막대 (ABA의 PRESS G 막대처럼). 각성 중엔 남은 시간 */
    private static void awakenBar(DrawContext ctx, MinecraftClient c) {
        TextRenderer tr = c.textRenderer;
        int w = 182, h = 9, x = (ctx.getScaledWindowWidth() - w) / 2, y = 6;
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        ctx.fill(x, y, x + w, y + h, 0xFF3A0A0A);
        String label;
        int text = 0xFFFFFFFF;
        if (awLeft > 0) {
            int fw = (int) (w * Math.min(1.0, awLeft / 900.0));
            ctx.fill(x, y, x + fw, y + h, 0xFFE0A020);
            label = "각성 " + (awLeft / 20 + 1) + "초" + (awUlt ? "  ·  G 각성기" : "");
        } else {
            int fw = (int) (w * awGauge / 100.0);
            boolean full = awGauge >= 100;
            int col = full ? ((clock / 5) % 2 == 0 ? 0xFFFF3030 : 0xFFC01818) : 0xFFC01818;
            ctx.fill(x, y, x + fw, y + h, col);
            label = full ? "PRESS G" : "각성 " + awGauge + "%";
            if (!full) text = 0xFFDDDDDD;
        }
        ctx.drawCenteredTextWithShadow(tr, net.minecraft.text.Text.literal(label).formatted(Formatting.BOLD), x + w / 2, y + 1, text);
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

    /**
     * 기술 카드 (ABA): 핫바 위에 빨간 카드가 칸마다 하나. 왼쪽 위 숫자키, 가운데 기술 이름.
     * 재사용 중이면 위에서부터 어둡게 덮이고 남은 초, 못 쓰면(등급·자원) 회색
     */
    private static void skillCards(DrawContext ctx, MinecraftClient c) {
        if (clock - skAt > 40 || skNames.isEmpty()) return;
        TextRenderer tr = c.textRenderer;
        int n = skNames.size(), cw = 46, ch = 28, gap = 3;
        int W = ctx.getScaledWindowWidth(), H = ctx.getScaledWindowHeight();
        int total = n * cw + (n - 1) * gap;
        int x0 = W / 2 - total / 2, y0 = H - 22 - 12 - 9 - ch - 4;
        if (!skTitle.isEmpty()) ctx.drawTextWithShadow(tr, skTitle, x0, y0 - 10, 0xFFFFD27A);
        for (int i = 0; i < n; i++) {
            int x = x0 + i * (cw + gap), y = y0;
            String name = skNames.get(i);
            int st = skState.get(i);
            boolean empty = name.isEmpty();
            int bg = empty ? 0x66303030 : st == 2 ? 0xCC4A4A4A : 0xDDB3141E;
            ctx.fill(x - 1, y - 1, x + cw + 1, y + ch + 1, empty ? 0x66000000 : 0xFF3A0508);
            ctx.fill(x, y, x + cw, y + ch, bg);
            if (!empty && st != 2) ctx.fill(x, y, x + cw, y + ch / 2, 0x22FFFFFF);   // 위쪽 밝은 띠
            ctx.drawTextWithShadow(tr, String.valueOf(i + 1), x + 2, y + 2, 0xFFFFFFFF);
            if (empty) continue;
            // 이름: 카드 폭에 맞게 줄임
            var m = ctx.getMatrices();
            float sc = Math.min(0.8f, (cw - 4) / (float) Math.max(1, tr.getWidth(name)));
            m.push();
            m.translate(x + cw / 2f, y + ch / 2f + 1, 0);
            m.scale(sc, sc, 1);
            ctx.drawCenteredTextWithShadow(tr, name, 0, -4, st == 2 ? 0xFFAAAAAA : 0xFFFFFFFF);
            m.pop();
            if (st == 1) {
                int left = skLeft.get(i), max = Math.max(1, skMax.get(i));
                int hgt = Math.min(ch, Math.round(ch * Math.min(1f, left / (float) max)));
                ctx.fill(x, y, x + cw, y + hgt, 0xB0000000);
                String sec = left >= 100 ? String.valueOf((left + 9) / 10) : String.format("%.1f", left / 10f);
                ctx.drawCenteredTextWithShadow(tr, sec, x + cw / 2, y + ch - 10, 0xFFFFE070);
            }
        }
    }

    /** 때린 상대: 화면 위 각성 막대 아래에 이름·체력(빨강, 깎인 만큼 흰색이 천천히 따라옴)·경직 표시 (6초 안 때리면 사라짐) */
    private static void targetBar(DrawContext ctx, MinecraftClient c) {
        if (targetId < 0 || clock - targetAt > 120 || c.world == null) return;
        if (!(c.world.getEntityById(targetId) instanceof net.minecraft.entity.LivingEntity t) || !t.isAlive()) { targetId = -1; return; }
        TextRenderer tr = c.textRenderer;
        int W = ctx.getScaledWindowWidth(), w = 160, h = 7;
        int x = W / 2 - w / 2, y = 34;
        float hp = Math.max(0, t.getHealth()), max = Math.max(1, t.getMaxHealth());
        if (targetShownHp < 0 || targetShownHp < hp) targetShownHp = hp;
        else targetShownHp = Math.max(hp, targetShownHp - max * 0.01f);
        boolean stunned = STUN.containsKey(targetId) && STUN.get(targetId) > clock;
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF101010);
        ctx.fill(x, y, x + w, y + h, 0xFF3A3A3A);
        ctx.fill(x, y, x + Math.round(w * targetShownHp / max), y + h, 0xFFF0F0F0);
        ctx.fill(x, y, x + Math.round(w * hp / max), y + h, stunned ? 0xFFE0281E : 0xFF35C83A);
        for (int k = 1; k < 10; k++) ctx.fill(x + w * k / 10, y, x + w * k / 10 + 1, y + h, 0x55000000);   // 칸 나눔
        String label = t.getDisplayName().getString() + (stunned ? "  경직" : "");
        ctx.drawCenteredTextWithShadow(tr, label, W / 2, y + h + 2, stunned ? 0xFFFF6A5A : 0xFFE0E0E0);
    }

    private static void render(DrawContext ctx) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.options.hudHidden) return;
        // 번쩍임: 화면 전체를 하얗게, 6틱에 걸쳐 사라짐
        float fa = flashStr * Math.max(0f, 1f - (clock - flashAt) / 6f);
        if (fa > 0.01f) ctx.fill(0, 0, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), ((int) (fa * 200) << 24) | 0xFFFFFF);
        concentrationLines(ctx);
        if (BbcClient.combat) { awakenBar(ctx, c); staminaBar(ctx, c); skillCards(ctx, c); targetBar(ctx, c); }
        if (comboHits <= 0) return;
        long age = clock - comboAt;
        if (age > 30) return;
        TextRenderer tr = c.textRenderer;
        int y = ctx.getScaledWindowHeight() / 2 - 40;
        float pop = age < 3 ? 1.25f - age * 0.08f : 1f; // 늘어날 때 살짝 튐
        var m = ctx.getMatrices();
        m.push();
        m.translate(24, y, 0);
        m.scale(4f * pop, 4f * pop, 1f);
        ctx.drawTextWithShadow(tr, Text.literal(String.valueOf(comboHits)).formatted(Formatting.BOLD), 0, 0, 0xFFE02020);
        m.pop();
        m.push();
        m.translate(24, y + 38, 0);
        m.scale(1.6f, 1.6f, 1f);
        ctx.drawTextWithShadow(tr, Text.literal("COMBO").formatted(Formatting.BOLD), 0, 0, 0xFFB01010);
        m.pop();
        ctx.drawTextWithShadow(tr, Text.literal(String.format("피해 %.1f", comboDamage)), 24, y + 56, 0xFFFFD040);
    }
}
