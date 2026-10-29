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
    /** + 0~4 = Z·X·C·V·B 칸 */
    public static final int SKILL_BASE = 10;
    public static final int GUARD_DOWN = 20, GUARD_UP = 21, DASH = 22, AWAKEN = 23;
    /** + 0~2 = 클래스·무기A·무기B 슬롯 */
    public static final int SLOT_BASE = 30;
    public static final int IMPLANT = 40, RELIC = 41, EYE = 42;

    public static final CustomPayload.Id<KeyPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "key"));
    public static final PacketCodec<RegistryByteBuf, KeyPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, KeyPayload::action, KeyPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
