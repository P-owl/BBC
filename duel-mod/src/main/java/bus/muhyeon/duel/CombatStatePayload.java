package bus.muhyeon.duel;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 서버 → 클라이언트: 전투 모드 켜짐/꺼짐 + 재생할 테마곡 (빈 문자열이면 음악 없음) */
public record CombatStatePayload(boolean on, String music) implements CustomPayload {
    public static final CustomPayload.Id<CombatStatePayload> ID = new CustomPayload.Id<>(Identifier.of(DuelMod.MOD_ID, "combat_state"));
    public static final PacketCodec<RegistryByteBuf, CombatStatePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOL, CombatStatePayload::on,
            PacketCodecs.STRING, CombatStatePayload::music,
            CombatStatePayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
