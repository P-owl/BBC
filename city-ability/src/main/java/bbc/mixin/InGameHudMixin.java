package bbc.mixin;

import bbc.client.Cutscene;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 컷신 동안 아이템창·체력칸·조준점을 숨김 (영화처럼 검은 띠와 자막만). 이름이 안 맞는 판이면 그냥 건너뜀 (require = 0) */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderMainHud", at = @At("HEAD"), cancellable = true, require = 0)
    private void bbc$hideMain(DrawContext ctx, RenderTickCounter counter, CallbackInfo ci) { if (Cutscene.active()) ci.cancel(); }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void bbc$hideCross(DrawContext ctx, RenderTickCounter counter, CallbackInfo ci) { if (Cutscene.active()) ci.cancel(); }

    @Inject(method = "renderStatusEffectOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private void bbc$hideEffects(DrawContext ctx, RenderTickCounter counter, CallbackInfo ci) { if (Cutscene.active()) ci.cancel(); }
}
