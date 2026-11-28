package bbc.client;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.SpeedModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * playerAnimator 연결. 이 클래스는 playerAnimator 가 깔려 있을 때만 불러온다 (BbcClient.ANIM).
 * 1인칭에서도 팔이 보이게 해서 내 평타가 어떻게 나가는지 보인다.
 */
final class Anim {
    private Anim() {}

    private static final Identifier LAYER = Identifier.of("bbc", "combat");
    /** 전투 모드 동안 깔리는 싸움 자세 (아래층). 공격 모션이 끝나면 팔이 차렷으로 떨어지지 않고 이 자세로 돌아온다 */
    private static final Identifier STANCE = Identifier.of("bbc", "stance");
    /** 자세 층 아래 다리 층: 공중에 뜨면 다리만 (뛰어오름 = 한쪽 무릎 접어 듦, 떨어짐 = 다리 내려 땅을 찾음). 팔은 위 자세 그대로 */
    private static final Identifier LEGS = Identifier.of("bbc", "legs");

    static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000, player -> new ModifierLayer<>());
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LEGS, 400, player -> new ModifierLayer<>());
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(STANCE, 500, player -> {
            SpeedModifier sm = new SpeedModifier(1f);   // 달리기 재생 속도를 실제 이동 속도에 맞춤 (발이 안 미끄러지게)
            SPEED.put(player, sm);
            return new ModifierLayer<>(null, sm);
        });
    }

    /** 매 틱: 손에 든 무기에 맞는 싸움 자세로 (맨손·검·창·대검), 달리면 달리기 모션 */
    static void tick(net.minecraft.client.MinecraftClient c) {
        if (c.world == null) { STANCE_ON.clear(); LEGS_ON.clear(); return; }
        var it = STANCE_ON.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (!(c.world.getEntityById(en.getKey()) instanceof AbstractClientPlayerEntity pl)) { LEGS_ON.remove(en.getKey()); GROUND_AT.remove(en.getKey()); it.remove(); continue; }
            var stack = pl.getMainHandStack();
            boolean alSpear = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString().equals("bbc:alchemy_spear");
            boolean weapon = stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) || stack.isOf(net.minecraft.item.Items.TRIDENT)
                    || alSpear || stack.isIn(net.minecraft.registry.tag.ItemTags.AXES);
            String want = stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) ? "stance_sword"
                    : stack.isOf(net.minecraft.item.Items.TRIDENT) || alSpear ? "stance_spear"
                    : stack.isIn(net.minecraft.registry.tag.ItemTags.AXES) ? "stance_gs" : STANCE_BASE.getOrDefault(en.getKey(), "stance");
            // 이동: 땅에서 달리면(빠르게) 달리기 모션, 걸으면 그 자세의 걷기 판 (_walk: 위몸은 자세 그대로, 다리는 걷기)
            double sp = Math.hypot(pl.getX() - pl.prevX, pl.getZ() - pl.prevZ);
            int id = en.getKey();
            if (sp > 0.03) MOVED_AT.put(id, c.world.getTime());
            boolean moving = c.world.getTime() - MOVED_AT.getOrDefault(id, -100L) <= 3;
            if (sp > 0.235 || pl.isSprinting() && moving) RAN_AT.put(id, c.world.getTime());   // 전력질주(0.28)만 달리기, 걷기(0.22)는 걷기 자세
            boolean running = moving && c.world.getTime() - RAN_AT.getOrDefault(id, -100L) <= 3;
            // 공중: 2틱 넘게 떠 있으면 (계단·턱에서 깜빡이지 않게) 팔만 잡은 자세 + 다리 층에 공중 다리
            boolean free = !pl.isOnGround() && !pl.isClimbing() && !pl.isTouchingWater() && !pl.isFallFlying() && !pl.getAbilities().flying && !pl.hasVehicle();
            if (!free) GROUND_AT.put(id, c.world.getTime());
            boolean air = free && c.world.getTime() - GROUND_AT.getOrDefault(id, c.world.getTime()) >= 2;
            String legs = null;
            if (air) {
                want = ARMS_ONLY.getOrDefault(want, want);
                legs = pl.getY() - pl.prevY > 0.02 ? "air_up" : "air_fall";
            }
            else if (running) want = weapon ? "run_w" : RUN_VARIANT.getOrDefault(STANCE_BASE.get(id), "run");
            else if (moving && WALKS.contains(want)) want = want + "_walk";
            boolean walking = !air && !running && moving && want.endsWith("_walk");
            SpeedModifier sm = SPEED.get(pl);
            if (sm != null) {   // 달리기·걷기 재생 속도를 실제 이동 속도에 맞춤 (부드럽게 따라감)
                float target = running ? (float) Math.max(0.7, Math.min(1.9, sp / RUN_SPEED))
                        : walking ? (float) Math.max(0.5, Math.min(1.6, sp / WALK_SPEED)) : 1f;
                sm.speed = (running || walking) && want.equals(en.getValue()) ? sm.speed * 0.6f + target * 0.4f : target;
            }
            if (!java.util.Objects.equals(legs, LEGS_ON.get(id))) {
                if (legs == null) LEGS_ON.remove(id); else LEGS_ON.put(id, legs);
                ModifierLayer<IAnimation> lg = layer(pl, LEGS);
                if (lg != null) lg.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(legs == null ? 3 : 4, Ease.INOUTSINE), legs == null ? null : load(legs));
            }
            if (want.equals(en.getValue())) continue;
            en.setValue(want);
            ModifierLayer<IAnimation> st = layer(pl, STANCE);
            if (st != null) st.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(4, Ease.INOUTSINE), load(want));
        }
    }

    @SuppressWarnings("unchecked")
    private static ModifierLayer<IAnimation> layer(AbstractClientPlayerEntity player, Identifier id) {
        Object data = PlayerAnimationAccess.getPlayerAssociatedData(player).get(id);
        return data instanceof ModifierLayer<?> raw ? (ModifierLayer<IAnimation>) raw : null;
    }

    private static IAnimation load(String motion) {
        var playable = PlayerAnimationRegistry.getAnimation(Identifier.of("bbc", motion));
        if (playable == null) return null;
        IAnimation a = playable.playAnimation();
        if (a instanceof KeyframeAnimationPlayer k) {
            k.setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL);
            k.setFirstPersonConfiguration(new FirstPersonConfiguration(true, true, true, false));
        }
        return a;
    }

    /** 달리기 모션이 만들어진 이동 속도 (칸/틱, 바닐라 전력질주) */
    private static final double RUN_SPEED = 0.28;
    /** 플레이어별 자세 층 재생 속도 */
    private static final java.util.Map<AbstractClientPlayerEntity, SpeedModifier> SPEED = new java.util.WeakHashMap<>();

    /** 싸움 자세를 켠 플레이어 → 지금 깔린 자세 이름 (손에 든 무기에 따라 바뀜) */
    private static final java.util.Map<Integer, String> STANCE_ON = new java.util.HashMap<>();
    /** 직업 대기 자세 (맨손일 때) */
    private static final java.util.Map<Integer, String> STANCE_BASE = new java.util.HashMap<>(), LAST_BASE = new java.util.HashMap<>();
    /** 마지막으로 움직인·달린 틱 (잠깐 멈칫해도 모션이 깜빡이지 않게 3틱 여유) */
    private static final java.util.Map<Integer, Long> MOVED_AT = new java.util.HashMap<>(), RAN_AT = new java.util.HashMap<>();
    /** 걷기 판이 있는 자세 (자세 + '_walk' = 위몸은 그 자세, 다리는 걷기. 재생 속도 = 이동 속도) */
    private static final java.util.Set<String> WALKS = java.util.Set.of("stance", "stance_sword", "stance_spear", "stance_gs",
            "stance_al", "stance_vm", "stance_ph", "stance_pw", "stance_pl", "stance_ul");
    /** 다리까지 잡은 대기 자세 → 공중에선 팔만 잡은 판으로 (다리는 다리 층) */
    private static final java.util.Map<String, String> ARMS_ONLY = java.util.Map.of("stance_al", "stance_al_arms", "stance_vm", "stance_vm_arms",
            "stance_ph", "stance_ph_arms", "stance_pw", "stance_pw_arms", "stance_pl", "stance_pl_arms", "stance_ul", "stance_ul_arms");
    /** 걷기 모션이 1배속으로 맞는 이동 속도 (칸/틱). 바닐라 걷기(0.216)에선 1.35배 = 두 걸음에 약 12틱 (바닐라 다리 흔들기와 비슷한 빠르기) */
    private static final double WALK_SPEED = 0.16;
    /** 마지막으로 땅에 있던 틱 · 지금 다리 층에 깐 모션 */
    private static final java.util.Map<Integer, Long> GROUND_AT = new java.util.HashMap<>();
    private static final java.util.Map<Integer, String> LEGS_ON = new java.util.HashMap<>();
    /** 직업 대기 자세 → 맨손으로 달릴 때 쓸 달리기 (연금술사 = 주먹 모은 잔걸음, 뱀파이어 = 숙이고 팔을 뒤로 펼친 질주,
     *  바람 = 출렁임 없이 미끄러지는 질주). 열(에시디시)과 직업 없으면 공용 run */
    private static final java.util.Map<String, String> RUN_VARIANT = java.util.Map.of("stance_al", "run_al", "stance_vm", "run_vm", "stance_pw", "run_pw");

    /** 지금 이 플레이어 모션의 굽힘 {축, 각도} (playerAnimator HeldItemMixin 과 같은 값) */
    static float[] bend(AbstractClientPlayerEntity player, String part) {
        if (!(player instanceof dev.kosmx.playerAnim.impl.IAnimatedPlayer ap)) return null;
        var a = ap.playerAnimator_getAnimation();
        if (a == null || !a.isActive()) return null;
        var v = a.get3DTransform(part, dev.kosmx.playerAnim.api.TransformType.BEND, new dev.kosmx.playerAnim.core.util.Vec3f(0f, 0f, 0f));
        return new float[]{v.getX(), v.getY()};
    }

    /** 마지막으로 튼 기술·평타 모션 (빛 칼날 그리기용: 각성 때 칼날이 돋는 순간 · 발의 칼날) */
    private static final java.util.Map<Integer, String> LAST_MOTION = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Long> LAST_MOTION_AT = new java.util.HashMap<>();
    /** 기둥 속 사내 · 빛 이고 전투 모드인가 (아래팔 칼날을 그림) */
    public static boolean lightBlades(int id) { String b = STANCE_BASE.get(id); return ("stance_pl".equals(b) || "stance_ul".equals(b)) && STANCE_ON.containsKey(id); }
    public static String lastMotion(int id) { return LAST_MOTION.get(id); }
    /** 마지막 모션을 튼 뒤 지난 틱 (없으면 큰 값) */
    public static float motionAge(int id, float tickDelta) {
        var w = net.minecraft.client.MinecraftClient.getInstance().world;
        Long at = LAST_MOTION_AT.get(id);
        return w == null || at == null ? 1e6f : w.getTime() - at + tickDelta;
    }

    static void play(AbstractClientPlayerEntity player, String motion) {
        if (!motion.startsWith("#") && net.minecraft.client.MinecraftClient.getInstance().world != null) {
            LAST_MOTION.put(player.getId(), motion);
            LAST_MOTION_AT.put(player.getId(), net.minecraft.client.MinecraftClient.getInstance().world.getTime());
        }
        if (motion.startsWith("#stance")) {
            ModifierLayer<IAnimation> st = layer(player, STANCE);
            if (motion.startsWith("#stance:")) STANCE_BASE.put(player.getId(), motion.substring(8));   // 직업 대기 자세 (예: stance_al)
            else if (motion.equals("#stance")) STANCE_BASE.remove(player.getId());
            if (!motion.equals("#stance_off")) {
                String base = STANCE_BASE.get(player.getId());
                // 이미 켜져 있으면 지금 틀고 있는 자세는 그대로 (2초마다 오는 재알림에 깜빡이지 않게), 직업 자세가 바뀌었으면 다시 고름
                if (!STANCE_ON.containsKey(player.getId()) || !java.util.Objects.equals(base, LAST_BASE.get(player.getId()))) STANCE_ON.put(player.getId(), "");
                if (base == null) LAST_BASE.remove(player.getId()); else LAST_BASE.put(player.getId(), base);
            }
            else {
                STANCE_ON.remove(player.getId());
                if (st != null) st.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(5, Ease.INOUTSINE), null);
                if (LEGS_ON.remove(player.getId()) != null) { ModifierLayer<IAnimation> lg = layer(player, LEGS); if (lg != null) lg.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(3, Ease.INOUTSINE), null); }
            }
            return;
        }
        ModifierLayer<IAnimation> layer = layer(player, LAYER);
        if (layer == null) return;
        if (motion.equals("stop")) {
            layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(4, Ease.INOUTSINE), null);
            return;
        }
        IAnimation a = load(motion);
        if (a == null) return;
        // 앞 모션에서 2틱 동안 섞어서 넘어감 (뚝뚝 끊기지 않게)
        layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(2, Ease.INOUTSINE), a);
    }
}
