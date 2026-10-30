package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 화면 연출. kind 0 = 흔들림(strength = 세기), 1 = 번쩍임(strength = 진하기) */
public record FxPayload(int kind, float strength) implements CustomPayload {
    public static final int SHAKE = 0, FLASH = 1;
    public static final CustomPayload.Id<FxPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "fx"));
    public static final PacketCodec<RegistryByteBuf, FxPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, FxPayload::kind, PacketCodecs.FLOAT, FxPayload::strength, FxPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
