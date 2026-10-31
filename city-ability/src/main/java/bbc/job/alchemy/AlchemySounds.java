package bbc.job.alchemy;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * 연금술사 효과음·음성·테마곡. 소리 이벤트만 모드에 등록하고, 실제 소리 파일(sounds.json + ogg)은 따로 주는
 * 리소스팩 'BBC 연금술사 사운드' 에 들어 있음 (애니메이션 음성·OST 라 공개 저장소·모드 jar 에는 안 넣음).
 * 리소스팩이 없으면 소리만 안 남.
 */
public final class AlchemySounds {
    private AlchemySounds() {}

    public static final SoundEvent SPARK = reg("alchemist.spark");          // 연성음 (손뼉 연성마다)
    public static final SoundEvent WEAPON = reg("alchemist.weapon_ed");     // 무기 연성
    public static final SoundEvent ATTACK = reg("alchemist.atk_ed");        // 창조의 기둥
    public static final SoundEvent GROUND = reg("alchemist.ground_ed");     // 각성기 (대사)
    public static final SoundEvent RUSH = reg("alchemist.rush_ed");         // 최후의 일격
    public static final SoundEvent AWAKEN = reg("alchemist.awaken_ed");     // 각성 컷신 음성
    public static final Identifier THEME = Identifier.of("bbc", "alchemist.theme");   // 각성 중 테마곡 (음악)
    public static final SoundEvent THEME_EVENT = Registry.register(Registries.SOUND_EVENT, THEME, SoundEvent.of(THEME));

    private static SoundEvent reg(String path) {
        Identifier id = Identifier.of("bbc", path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** 클래스 로딩 = 등록 (모드 초기화에서 부름) */
    public static void register() {}

    /** 시전자 자리에서 (주변 사람도 들림) */
    public static void play(ServerPlayerEntity p, SoundEvent s, float volume) {
        p.getServerWorld().playSound(null, p.getX(), p.getY(), p.getZ(), s, SoundCategory.PLAYERS, volume, 1.0f);
    }
}
