package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 본인: 각성 게이지(0~100), 각성 남은 틱(0 = 각성 아님), 각성기를 아직 쓸 수 있나, 스태미나 */
public record AwakenPayload(int gauge, int left, boolean ultReady, int stamina, int staminaMax) implements CustomPayload {
    public static final CustomPayload.Id<AwakenPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "awaken"));
    public static final PacketCodec<RegistryByteBuf, AwakenPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, AwakenPayload::gauge, PacketCodecs.VAR_INT, AwakenPayload::left,
                    PacketCodecs.BOOL, AwakenPayload::ultReady, PacketCodecs.VAR_INT, AwakenPayload::stamina,
                    PacketCodecs.VAR_INT, AwakenPayload::staminaMax, AwakenPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
