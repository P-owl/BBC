package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 클라이언트 → 서버: 성장 창 조작. action: 0 상태 요청, 1 특성 투자(arg=번호), 2 종족 다시 돌리기, 3 카드 고르기(arg=id), 4 카드 소각(arg=id), 5 스탯 확정(arg=스탯 id, 가방의 수련 도구 사용) */
public record GrowthActionPayload(int action, String arg) implements CustomPayload {
    public static final int SYNC = 0, TRAIT = 1, REROLL = 2, CHOOSE = 3, BURN = 4, CONFIRM = 5;
    public static final CustomPayload.Id<GrowthActionPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "growth_action"));
    public static final PacketCodec<RegistryByteBuf, GrowthActionPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, GrowthActionPayload::action, PacketCodecs.STRING, GrowthActionPayload::arg, GrowthActionPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
