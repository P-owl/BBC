package bbc.client;

import bbc.net.CombatStatePayload;
import bbc.net.KeyPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * 클라이언트: 전투 키 등록과 전송.
 * 가드(F)·대시(Q)·슬롯(1~3)은 바닐라 키(손 바꾸기·버리기·핫바)를 전투 모드에서만 가로채야 해서 1단계에서 믹스인으로 붙인다.
 */
public class BbcClient implements ClientModInitializer {
    private static final String CAT = "category.bbc";
    // GLFW 키 코드
    private static final int KEY_GRAVE = 96, KEY_B = 66, KEY_C = 67, KEY_G = 71, KEY_H = 72, KEY_R = 82, KEY_T = 84, KEY_V = 86, KEY_X = 88, KEY_Z = 90;
    public static boolean combat = false;

    private static KeyBinding toggle, awaken, eye, implant, relic;
    private static final KeyBinding[] SKILL = new KeyBinding[5];
    private static final int[] SKILL_KEYS = {KEY_Z, KEY_X, KEY_C, KEY_V, KEY_B};
    private static final String[] SKILL_NAMES = {"z", "x", "c", "v", "b"};

    private static KeyBinding key(String id, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bbc." + id, InputUtil.Type.KEYSYM, code, CAT));
    }

    @Override
    public void onInitializeClient() {
        toggle = key("toggle", KEY_GRAVE);
        for (int i = 0; i < 5; i++) SKILL[i] = key("skill_" + SKILL_NAMES[i], SKILL_KEYS[i]);
        awaken = key("awaken", KEY_G);
        eye = key("eye", KEY_R);
        implant = key("implant", KEY_H);
        relic = key("relic", KEY_T);

        ClientPlayNetworking.registerGlobalReceiver(CombatStatePayload.ID,
                (payload, ctx) -> ctx.client().execute(() -> combat = payload.on()));
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> combat = false);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            while (toggle.wasPressed()) send(KeyPayload.TOGGLE);
            for (int i = 0; i < 5; i++) while (SKILL[i].wasPressed()) if (combat) send(KeyPayload.SKILL_BASE + i);
            while (awaken.wasPressed()) if (combat) send(KeyPayload.AWAKEN);
            while (eye.wasPressed()) if (combat) send(KeyPayload.EYE);
            while (implant.wasPressed()) if (combat) send(KeyPayload.IMPLANT);
            while (relic.wasPressed()) if (combat) send(KeyPayload.RELIC);
        });
    }

    private static void send(int action) {
        if (ClientPlayNetworking.canSend(KeyPayload.ID)) ClientPlayNetworking.send(new KeyPayload(action));
    }
}
