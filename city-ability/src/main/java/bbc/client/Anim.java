package bbc.client;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
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

    static void init() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000, player -> new ModifierLayer<>());
    }

    @SuppressWarnings("unchecked")
    static void play(AbstractClientPlayerEntity player, String motion) {
        Object data = PlayerAnimationAccess.getPlayerAssociatedData(player).get(LAYER);
        if (!(data instanceof ModifierLayer<?> raw)) return;
        ModifierLayer<IAnimation> layer = (ModifierLayer<IAnimation>) raw;
        if (motion.equals("stop")) {
            layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(4, Ease.INOUTSINE), null);
            return;
        }
        KeyframeAnimation anim = PlayerAnimationRegistry.getAnimation(Identifier.of("bbc", motion));
        if (anim == null) return;
        KeyframeAnimationPlayer k = new KeyframeAnimationPlayer(anim);
        k.setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL);
        k.setFirstPersonConfiguration(new FirstPersonConfiguration(true, true, true, false));
        // 앞 모션에서 2틱 동안 섞어서 넘어감 (뚝뚝 끊기지 않게)
        layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(2, Ease.OUTQUAD), k);
    }
}
