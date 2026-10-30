package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 엔티티(플레이어) 하나에 모션 재생. motion = "stop" 이면 멈춤 */
public record MotionPayload(int entityId, String motion) implements CustomPayload {
    public static final CustomPayload.Id<MotionPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "motion"));
    public static final PacketCodec<RegistryByteBuf, MotionPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, MotionPayload::entityId, PacketCodecs.STRING, MotionPayload::motion, MotionPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
