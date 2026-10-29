package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 전투 모드 켜짐/꺼짐 (클라이언트는 이걸 보고 전투 키를 보낼지 정함) */
public record CombatStatePayload(boolean on) implements CustomPayload {
    public static final CustomPayload.Id<CombatStatePayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "combat_state"));
    public static final PacketCodec<RegistryByteBuf, CombatStatePayload> CODEC =
            PacketCodec.tuple(PacketCodecs.BOOL, CombatStatePayload::on, CombatStatePayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
