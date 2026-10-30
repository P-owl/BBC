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

    /** 매 틱: 손에 든 무기에 맞는 싸움 자세로 (맨손·검·창·대검) */
    static void tick(net.minecraft.client.MinecraftClient c) {
        if (c.world == null) { STANCE_ON.clear(); return; }
        var it = STANCE_ON.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (!(c.world.getEntityById(en.getKey()) instanceof AbstractClientPlayerEntity pl)) { it.remove(); continue; }
            var stack = pl.getMainHandStack();
            String want = stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) ? "stance_sword"
                    : stack.isOf(net.minecraft.item.Items.TRIDENT) ? "stance_spear"
                    : stack.isIn(net.minecraft.registry.tag.ItemTags.AXES) ? "stance_gs" : "stance";
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

    static void play(AbstractClientPlayerEntity player, String motion) {
        if (motion.equals("#stance") || motion.equals("#stance_off")) {
            ModifierLayer<IAnimation> st = layer(player, STANCE);
            if (motion.equals("#stance")) STANCE_ON.put(player.getId(), "");
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
