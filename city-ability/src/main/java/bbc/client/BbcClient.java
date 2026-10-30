package bbc.client;

import bbc.net.CombatStatePayload;
import bbc.net.MotionPayload;
import bbc.net.KeyPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

/**
 * 클라이언트: 전투 키 등록과 전송.
 * 전투 모드에서는
 *  - 좌클릭 = 평타 (MinecraftClientMixin). 스페이스를 누른 채면 띄우기·래더. 평타 중엔 바닐라 점프가 막힘 (KeyboardInputMixin)
 *  - 숫자 1~5 (핫바 키) = 기술. 전투 모드 동안 핫바 1~5 전환은 먹어 버린다
 *  - E (인벤토리 키) = 스위치 (클래스 → 무기 A → 무기 B). 전투 모드 동안 인벤토리는 ` 로 모드를 끄고 연다
 *  - F(손 바꾸기 키) = 가드, 누르는 순간 패링 입력 창
 *  - Q(버리기 키) = 대시 (움직이는 방향으로), 경직 중이면 회피기
 * 바닐라 F·Q 동작은 전투 모드 동안 먹어 버린다. 키를 바꾸면 바뀐 키를 따른다.
 * 슬롯(1~3) 스위치는 2단계.
 */
public class BbcClient implements ClientModInitializer {
    private static final String CAT = "category.bbc";
    // GLFW 키 코드
    private static final int KEY_GRAVE = 96, KEY_G = 71, KEY_H = 72, KEY_R = 82, KEY_T = 84;
    public static boolean combat = false;
    /** playerAnimator 가 깔려 있나 (없으면 모션 없이 돈다) */
    static final boolean ANIM = hasAnimator();
    private static boolean guardSent = false;
    private static long lastM1 = 0;

    private static KeyBinding toggle, awaken, eye, implant, relic;
    private static final KeyBinding[] SKILL = new KeyBinding[5];
    private static final String[] SKILL_NAMES = {"z", "x", "c", "v", "b"};

    private static KeyBinding key(String id, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bbc." + id, InputUtil.Type.KEYSYM, code, CAT));
    }

    @Override
    public void onInitializeClient() {
        toggle = key("toggle", KEY_GRAVE);
        // 기본은 숫자키(핫바 키)로 쓰고, 따로 키를 지정하고 싶은 사람만 설정에서 이 키를 정함 (기본 없음)
        for (int i = 0; i < 5; i++) SKILL[i] = key("skill_" + SKILL_NAMES[i], InputUtil.UNKNOWN_KEY.getCode());
        awaken = key("awaken", KEY_G);
        eye = key("eye", KEY_R);
        implant = key("implant", KEY_H);
        relic = key("relic", KEY_T);

        if (ANIM) Anim.init();
        CombatHud.init();
        ClientPlayNetworking.registerGlobalReceiver(MotionPayload.ID, (payload, ctx) -> ctx.client().execute(() -> {
            if (ctx.client().world == null) return;
            var ent = ctx.client().world.getEntityById(payload.entityId());
            if (ent instanceof AbstractClientPlayerEntity pl) { if (ANIM) Anim.play(pl, payload.motion()); }
            else if (ent != null) CombatHud.mobMotion(payload.entityId(), payload.motion());
        }));
        ClientPlayNetworking.registerGlobalReceiver(CombatStatePayload.ID,
                (payload, ctx) -> ctx.client().execute(() -> combat = payload.on()));
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> { combat = false; guardSent = false; });

        // 바닐라가 F·Q를 처리하기 전에 가로챔
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            GameOptions o = client.options;
            boolean usable = combat && client.currentScreen == null;
            if (!usable) {
                if (guardSent) { send(KeyPayload.GUARD_UP); guardSent = false; }
                return;
            }
            while (o.swapHandsKey.wasPressed()) { /* 손 바꾸기 막기 */ }
            boolean g = o.swapHandsKey.isPressed();
            if (g != guardSent) { send(g ? KeyPayload.GUARD_DOWN : KeyPayload.GUARD_UP); guardSent = g; }
            while (o.dropKey.wasPressed()) send(KeyPayload.DASH_BASE + dashDir(client));
            for (int i = 0; i < 5; i++) while (o.hotbarKeys[i].wasPressed()) send(KeyPayload.SKILL_BASE + i);
            while (o.inventoryKey.wasPressed()) send(KeyPayload.SLOT_BASE); // E = 스위치 (전투 모드 동안 인벤토리 대신)
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (ANIM) Anim.tick(client);
            if (client.player == null) return;
            while (toggle.wasPressed()) send(KeyPayload.TOGGLE);
            for (int i = 0; i < 5; i++) while (SKILL[i].wasPressed()) if (combat) send(KeyPayload.SKILL_BASE + i);
            while (awaken.wasPressed()) if (combat) send(KeyPayload.AWAKEN);
            while (eye.wasPressed()) if (combat) send(KeyPayload.EYE);
            while (implant.wasPressed()) if (combat) send(KeyPayload.IMPLANT);
            while (relic.wasPressed()) if (combat) send(KeyPayload.RELIC);
        });
    }

    /** 평타. press = 클릭한 순간, false = 누르고 있는 중 (2틱마다) */
    public static void m1(boolean press) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player == null || c.world == null) return;
        long t = c.world.getTime();
        if (!press && t - lastM1 < 2) return;
        lastM1 = t;
        send(c.options.jumpKey.isPressed() ? KeyPayload.M1_UP : KeyPayload.M1);
    }

    /** 평타를 치는 중이면 바닐라 점프를 막음 (점프 키는 평타 입력 판정으로만 씀) */
    public static boolean blockJump() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (!combat || c.player == null || c.world == null || c.currentScreen != null) return false;
        return c.options.attackKey.isPressed() || c.world.getTime() - lastM1 < 6;
    }

    /** 0 앞 1 뒤 2 왼쪽 3 오른쪽 */
    private static int dashDir(MinecraftClient c) {
        var in = c.player.input;
        if (in.pressingBack && !in.pressingForward) return 1;
        if (in.pressingLeft && !in.pressingRight && !in.pressingForward) return 2;
        if (in.pressingRight && !in.pressingLeft && !in.pressingForward) return 3;
        return 0;
    }

    private static boolean hasAnimator() {
        try { Class.forName("dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess", false, BbcClient.class.getClassLoader()); return true; }
        catch (Throwable t) { return false; }
    }

    private static void send(int action) {
        if (ClientPlayNetworking.canSend(KeyPayload.ID)) ClientPlayNetworking.send(new KeyPayload(action));
    }
}
