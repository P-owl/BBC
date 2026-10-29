package bus.muhyeon.duel;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** 클라이언트 → 서버: 연금술 단축키. 0 = 손뼉 연성, 3 = 전투 모드 켜기/끄기, 10+n = n번째 기술 */
public record AlchKeyPayload(int action) implements CustomPayload {
    public static final int CLAP = 0, TOGGLE = 3, SKILL_BASE = 10;

    public static final CustomPayload.Id<AlchKeyPayload> ID = new CustomPayload.Id<>(Identifier.of(DuelMod.MOD_ID, "alch_key"));
    public static final PacketCodec<RegistryByteBuf, AlchKeyPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.VAR_INT, AlchKeyPayload::action, AlchKeyPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
