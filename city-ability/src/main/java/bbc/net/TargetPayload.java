package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 때린 쪽 클라이언트: 방금 때린 상대 (화면 위 상대 체력·경직 막대용) */
public record TargetPayload(int entityId) implements CustomPayload {
    public static final CustomPayload.Id<TargetPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "target"));
    public static final PacketCodec<RegistryByteBuf, TargetPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, TargetPayload::entityId, TargetPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
