package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 이 엔티티가 ticks 동안 경직 (0 = 풀림). 클라이언트가 몸을 붉게 칠한다 */
public record StunPayload(int entityId, int ticks) implements CustomPayload {
    public static final CustomPayload.Id<StunPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "stun"));
    public static final PacketCodec<RegistryByteBuf, StunPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, StunPayload::entityId, PacketCodecs.VAR_INT, StunPayload::ticks, StunPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
