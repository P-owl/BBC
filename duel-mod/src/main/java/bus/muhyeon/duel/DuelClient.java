package bus.muhyeon.duel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;

/**
 * 클라이언트 전용.
 *  ` (물결표 키) : 전투 모드 켜기/끄기
 *  G             : 손뼉 연성 (진리를 본 자)
 *  1~7           : 전투 모드 중 연성 기술 — 이 동안은 같은 키에 묶인 다른 단축키(핫바 등)보다 우선
 * 전부 조작 설정 → '무현버스 연금술' 에서 바꿀 수 있음.
 */
public class DuelClient implements ClientModInitializer {
    private static final String CATEGORY = "category.duel.alchemy";
    private static final int KEY_GRAVE = 96, KEY_G = 71, KEY_1 = 49;

    /** 전투 모드 전용 단축키 (다른 단축키와 같은 키여도 평소엔 그쪽이 그대로 동작) */
    public static class CombatKeyBinding extends KeyBinding {
        public CombatKeyBinding(String id, int code) { super(id, InputUtil.Type.KEYSYM, code, CATEGORY); }
    }

    /** 1~7 연성 기술 + 8 오토메일 연성 */
    private static final CombatKeyBinding[] SKILL_KEYS = new CombatKeyBinding[AlchemySkills.SKILLS.length + 1];
    private static final boolean[] WAS_DOWN = new boolean[SKILL_KEYS.length];
    private static boolean combat = false;
    private static SoundInstance music;

    @Override
    public void onInitializeClient() {
        BlockRenderLayerMap.INSTANCE.putBlock(DuelMod.ALCHEMY_CIRCLE, RenderLayer.getCutout());

        KeyBinding toggle = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.duel.combat", InputUtil.Type.KEYSYM, KEY_GRAVE, CATEGORY));
        KeyBinding clap = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.duel.clap", InputUtil.Type.KEYSYM, KEY_G, CATEGORY));
        for (int i = 0; i < SKILL_KEYS.length; i++) {
            SKILL_KEYS[i] = (CombatKeyBinding) KeyBindingHelper.registerKeyBinding(new CombatKeyBinding("key.duel.skill" + (i + 1), KEY_1 + i));
        }

        ClientPlayNetworking.registerGlobalReceiver(CombatStatePayload.ID, (payload, ctx) -> {
            combat = payload.on();
            if (combat && !payload.music().isEmpty()) startMusic(payload.music()); else stopMusic();
        });
        ClientPlayNetworking.registerGlobalReceiver(BodyPayload.ID, (payload, ctx) -> {
            if (payload.toll().isEmpty() && payload.automail().isEmpty()) AutomailRender.BODIES.remove(payload.player());
            else AutomailRender.BODIES.put(payload.player(), new AutomailRender.Body(payload.toll(), payload.automail(), payload.buff()));
        });
        ClientPlayNetworking.registerGlobalReceiver(MarkerPayload.ID, (payload, ctx) -> XaeroBridge.set(payload.markers()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { combat = false; stopMusic(); AutomailRender.BODIES.clear(); XaeroBridge.clear(); });

        // 플레이어 모델에 오토메일·의족 그리기
        net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof net.minecraft.client.render.entity.PlayerEntityRenderer pr) helper.register(new AutomailRender(pr));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getNetworkHandler() == null) return;
            while (toggle.wasPressed()) send(AlchKeyPayload.TOGGLE);
            while (clap.wasPressed()) if (!combat) send(AlchKeyPayload.CLAP);

            if (combat) {
                // 키 상태를 직접 읽어서 눌린 순간만 보냄 (다른 단축키는 KeyBindingMixin 이 막음)
                long win = client.getWindow().getHandle();
                for (int i = 0; i < SKILL_KEYS.length; i++) {
                    InputUtil.Key k = KeyBindingHelper.getBoundKeyOf(SKILL_KEYS[i]);
                    boolean down = client.currentScreen == null && k.getCategory() == InputUtil.Type.KEYSYM
                            && k.getCode() != InputUtil.UNKNOWN_KEY.getCode() && InputUtil.isKeyPressed(win, k.getCode());
                    if (down && !WAS_DOWN[i]) send(AlchKeyPayload.SKILL_BASE + i);
                    WAS_DOWN[i] = down;
                }
                // 바닐라 배경음이 끼어들지 않게
                if (client.player.age % 20 == 0) client.getMusicTracker().stop();
            }
        });
    }

    /** KeyBindingMixin 에서 호출: 전투 모드 중이면 기술 키와 겹치는 다른 단축키 입력을 막음 */
    public static boolean blocks(InputUtil.Key key) {
        if (!combat || MinecraftClient.getInstance().currentScreen != null) return false;
        for (CombatKeyBinding k : SKILL_KEYS) if (k != null && KeyBindingHelper.getBoundKeyOf(k).equals(key)) return true;
        return false;
    }

    private static void startMusic(String id) {
        stopMusic();
        Identifier sid = Identifier.tryParse(id);
        if (sid == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.getMusicTracker().stop();
        music = new PositionedSoundInstance(sid, SoundCategory.MUSIC, 1.0f, 1.0f, SoundInstance.createRandom(),
                true, 0, SoundInstance.AttenuationType.NONE, 0, 0, 0, true);
        mc.getSoundManager().play(music);
    }

    private static void stopMusic() {
        if (music != null) MinecraftClient.getInstance().getSoundManager().stop(music);
        music = null;
    }

    private static void send(int action) {
        if (ClientPlayNetworking.canSend(AlchKeyPayload.ID)) ClientPlayNetworking.send(new AlchKeyPayload(action));
    }
}
