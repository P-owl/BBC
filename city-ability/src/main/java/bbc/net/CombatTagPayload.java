package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 전투 중(in combat) 남은 틱 (0 = 풀림) */
public record CombatTagPayload(int ticks) implements CustomPayload {
    public static final CustomPayload.Id<CombatTagPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "combat_tag"));
    public static final PacketCodec<RegistryByteBuf, CombatTagPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, CombatTagPayload::ticks, CombatTagPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
