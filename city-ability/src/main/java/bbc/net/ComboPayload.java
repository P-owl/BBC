package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 때린 사람: 지금 콤보 타수와 누적 피해. 화면 왼쪽에 크게 띄운다 */
public record ComboPayload(int hits, float damage) implements CustomPayload {
    public static final CustomPayload.Id<ComboPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "combo"));
    public static final PacketCodec<RegistryByteBuf, ComboPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, ComboPayload::hits, PacketCodecs.FLOAT, ComboPayload::damage, ComboPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
