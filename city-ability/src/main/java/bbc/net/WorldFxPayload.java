package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 세상에 그릴 이펙트 모델 한 개 ("종류:값,값,…" — client/FxShapes 가 그림, combat/WorldFx 가 보냄) */
public record WorldFxPayload(String spec) implements CustomPayload {
    public static final CustomPayload.Id<WorldFxPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "world_fx"));
    public static final PacketCodec<RegistryByteBuf, WorldFxPayload> CODEC = PacketCodec.tuple(PacketCodecs.STRING, WorldFxPayload::spec, WorldFxPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
