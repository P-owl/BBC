package bus.muhyeon.duel;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** 서버 → 클라이언트: 지도 마커 전체 목록 (대통령 위치, 수배자, 기둥 등). 받을 때마다 통째로 교체 */
public record MarkerPayload(List<Marker> markers) implements CustomPayload {
    public record Marker(String id, String label, String initials, String dim, int x, int y, int z, int color) {}

    public static final CustomPayload.Id<MarkerPayload> ID = new CustomPayload.Id<>(Identifier.of(DuelMod.MOD_ID, "markers"));

    private static final PacketCodec<RegistryByteBuf, Marker> ONE = new PacketCodec<>() {
        @Override public Marker decode(RegistryByteBuf b) {
            return new Marker(b.readString(), b.readString(), b.readString(), b.readString(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        }
        @Override public void encode(RegistryByteBuf b, Marker m) {
            b.writeString(m.id()); b.writeString(m.label()); b.writeString(m.initials()); b.writeString(m.dim());
            b.writeVarInt(m.x()); b.writeVarInt(m.y()); b.writeVarInt(m.z()); b.writeVarInt(m.color());
        }
    };

    public static final PacketCodec<RegistryByteBuf, MarkerPayload> CODEC =
            ONE.collect(PacketCodecs.toList()).xmap(MarkerPayload::new, MarkerPayload::markers);

    public MarkerPayload { markers = new ArrayList<>(markers); }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
