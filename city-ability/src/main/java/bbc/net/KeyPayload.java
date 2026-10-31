package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 클라이언트 → 서버: 전투 키 입력 */
public record KeyPayload(int action) implements CustomPayload {
    public static final int TOGGLE = 0;
    /** 평타 / 스페이스를 누른 채 평타 (띄우기·래더) */
    public static final int M1 = 1, M1_UP = 2;
    /** + 0~4 = 1~5 칸 */
    public static final int SKILL_BASE = 10;
    public static final int GUARD_DOWN = 20, GUARD_UP = 21, DASH = 22, AWAKEN = 23;
    /** 스위치 (E): 클래스 → 무기 A → 무기 B 순서로 */
    public static final int SLOT_BASE = 30;
    public static final int IMPLANT = 40, RELIC = 41, EYE = 42;
    /** + 0 앞 1 뒤 2 왼쪽 3 오른쪽 (경직 중이면 회피기) */
    public static final int DASH_BASE = 50;
    /** + 0~4 = 숫자 칸을 뗌 (꾹 누르는 기술 끝내기) */
    public static final int SKILL_UP_BASE = 60;
    /** 성장 창 (K, 전투 모드와 상관없이) */
    public static final int GROWTH = 70;

    public static final CustomPayload.Id<KeyPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "key"));
    public static final PacketCodec<RegistryByteBuf, KeyPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, KeyPayload::action, KeyPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
