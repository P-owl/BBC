package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 서버 → 클라이언트: 컷신 재생. shot = 클라이언트 Cutscene 에 적힌 카메라 대본 이름 (예: "al_awaken"),
 * actor = 카메라가 따라갈 엔티티(시전자), target = 대본이 바라볼 대상 (없으면 -1)
 */
public record CutscenePayload(String shot, int actor, int target) implements CustomPayload {
    public static final CustomPayload.Id<CutscenePayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "cutscene"));
    public static final PacketCodec<RegistryByteBuf, CutscenePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, CutscenePayload::shot, PacketCodecs.VAR_INT, CutscenePayload::actor,
            PacketCodecs.VAR_INT, CutscenePayload::target, CutscenePayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
