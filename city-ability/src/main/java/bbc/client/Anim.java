package bbc.client;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
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

    static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000, player -> new ModifierLayer<>());
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(STANCE, 500, player -> new ModifierLayer<>());
    }

    /** 매 틱: 손에 든 무기에 맞는 싸움 자세로 (맨손·검·창·대검), 달리면 달리기 모션 */
    static void tick(net.minecraft.client.MinecraftClient c) {
        if (c.world == null) { STANCE_ON.clear(); return; }
        var it = STANCE_ON.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (!(c.world.getEntityById(en.getKey()) instanceof AbstractClientPlayerEntity pl)) { it.remove(); continue; }
            var stack = pl.getMainHandStack();
            boolean alSpear = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString().equals("bbc:alchemy_spear");
            boolean weapon = stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) || stack.isOf(net.minecraft.item.Items.TRIDENT)
                    || alSpear || stack.isIn(net.minecraft.registry.tag.ItemTags.AXES);
            String want = stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) ? "stance_sword"
                    : stack.isOf(net.minecraft.item.Items.TRIDENT) || alSpear ? "stance_spear"
                    : stack.isIn(net.minecraft.registry.tag.ItemTags.AXES) ? "stance_gs" : STANCE_BASE.getOrDefault(en.getKey(), "stance");
            // 이동: 땅에서 달리면(빠르게) 달리기 모션, 걸으면 다리는 바닐라 걷기 (다리까지 잡은 자세는 '_walk' 판으로)
            double sp = Math.hypot(pl.getX() - pl.prevX, pl.getZ() - pl.prevZ);
            int id = en.getKey();
            if (sp > 0.03) MOVED_AT.put(id, c.world.getTime());
            boolean moving = c.world.getTime() - MOVED_AT.getOrDefault(id, -100L) <= 3;
            if (sp > 0.19 || pl.isSprinting() && moving) RAN_AT.put(id, c.world.getTime());
            boolean running = moving && c.world.getTime() - RAN_AT.getOrDefault(id, -100L) <= 3;
            if (running) want = weapon ? "run_w" : "run";
            else if (moving && WALK_VARIANT.contains(want)) want = want + "_walk";
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

    /** 싸움 자세를 켠 플레이어 → 지금 깔린 자세 이름 (손에 든 무기에 따라 바뀜) */
    private static final java.util.Map<Integer, String> STANCE_ON = new java.util.HashMap<>();
    /** 직업 대기 자세 (맨손일 때) */
    private static final java.util.Map<Integer, String> STANCE_BASE = new java.util.HashMap<>();
    /** 마지막으로 움직인·달린 틱 (잠깐 멈칫해도 모션이 깜빡이지 않게 3틱 여유) */
    private static final java.util.Map<Integer, Long> MOVED_AT = new java.util.HashMap<>(), RAN_AT = new java.util.HashMap<>();
    /** 다리까지 잡은 대기 자세 → 걸을 땐 팔만 잡은 '_walk' 판으로 */
    private static final java.util.Set<String> WALK_VARIANT = java.util.Set.of("stance_al");

    /** 지금 이 플레이어 모션의 굽힘 {축, 각도} (playerAnimator HeldItemMixin 과 같은 값) */
    static float[] bend(AbstractClientPlayerEntity player, String part) {
        if (!(player instanceof dev.kosmx.playerAnim.impl.IAnimatedPlayer ap)) return null;
        var a = ap.playerAnimator_getAnimation();
        if (a == null || !a.isActive()) return null;
        var v = a.get3DTransform(part, dev.kosmx.playerAnim.api.TransformType.BEND, new dev.kosmx.playerAnim.core.util.Vec3f(0f, 0f, 0f));
        return new float[]{v.getX(), v.getY()};
    }

    static void play(AbstractClientPlayerEntity player, String motion) {
        if (motion.startsWith("#stance")) {
            ModifierLayer<IAnimation> st = layer(player, STANCE);
            if (motion.startsWith("#stance:")) STANCE_BASE.put(player.getId(), motion.substring(8));   // 직업 대기 자세 (예: stance_al)
            else if (motion.equals("#stance")) STANCE_BASE.remove(player.getId());
            if (!motion.equals("#stance_off")) STANCE_ON.put(player.getId(), "");
            else { STANCE_ON.remove(player.getId()); if (st != null) st.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(5, Ease.INOUTSINE), null); }
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
