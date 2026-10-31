package bbc.net;

import bbc.Bbc;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 서버 → 클라이언트: 기술 칸 HUD (ABA 처럼 화면 아래 빨간 카드). 4틱마다.
 *  title = 지금 쓰는 칸 이름 (직업 또는 무기), names = 칸마다 기술 이름 ("" = 빈 칸),
 *  ult = 궁극기(각성기) 이름 (기술 목록 맨 아래 칸)
 *  state = 0 쓸 수 있음 / 1 재사용 대기 / 2 못 씀 (등급·자원 부족), left·max = 남은·전체 재사용 (0.1초 단위)
 */
public record SkillHudPayload(String title, List<String> names, List<Integer> state, List<Integer> left, List<Integer> max, String ult) implements CustomPayload {
    public static final CustomPayload.Id<SkillHudPayload> ID = new CustomPayload.Id<>(Identifier.of(Bbc.MOD_ID, "skill_hud"));
    public static final PacketCodec<RegistryByteBuf, SkillHudPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SkillHudPayload::title,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), SkillHudPayload::names,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), SkillHudPayload::state,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), SkillHudPayload::left,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), SkillHudPayload::max,
            PacketCodecs.STRING, SkillHudPayload::ult,
            SkillHudPayload::new);

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
