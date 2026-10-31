package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 가드 게이지(0~100, 들고 있으면 +1000), 회피·패링 남은 재사용(틱). 예전 액션바 글자를 HUD 로 옮김 */
public record StatusPayload(int guard, int evadeLeft, int parryLeft) implements CustomPayload {
    public static final CustomPayload.Id<StatusPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "status"));
    public static final PacketCodec<RegistryByteBuf, StatusPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, StatusPayload::guard, PacketCodecs.VAR_INT, StatusPayload::evadeLeft, PacketCodecs.VAR_INT, StatusPayload::parryLeft,
            StatusPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
