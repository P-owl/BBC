package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.UUID;

/** 서버 → 모든 클라이언트: 플레이어 몸 상태 (통행료 · 오토메일 · 켜진 오토메일 기술). buff 0 없음 / 1 팔 칼날 / 2 다리 칼날 / 3 강철 심장 */
public record BodyPayload(UUID player, String toll, String automail, int buff) implements CustomPayload {
    public static final CustomPayload.Id<BodyPayload> ID = new CustomPayload.Id<>(Identifier.of("bbc", "body"));
    public static final PacketCodec<RegistryByteBuf, BodyPayload> CODEC = PacketCodec.tuple(
            Uuids.PACKET_CODEC, BodyPayload::player,
            PacketCodecs.STRING, BodyPayload::toll,
            PacketCodecs.STRING, BodyPayload::automail,
            PacketCodecs.VAR_INT, BodyPayload::buff,
            BodyPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
