package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 성장 상태 전체 (JSON). open = 0 그냥 갱신, 1 성장 창 열기, 2 재능 카드 고르기 창 열기 */
public record GrowthSyncPayload(String json, int open) implements CustomPayload {
    public static final CustomPayload.Id<GrowthSyncPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "growth_sync"));
    public static final PacketCodec<RegistryByteBuf, GrowthSyncPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, GrowthSyncPayload::json, PacketCodecs.VAR_INT, GrowthSyncPayload::open, GrowthSyncPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
