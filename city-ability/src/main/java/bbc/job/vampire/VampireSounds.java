package bbc.job.vampire;

import bbc.job.alchemy.AlchemySounds;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 뱀파이어 기술 음성·각성 테마곡. 소리 이벤트만 모드에 등록하고, 실제 소리 파일(sounds.json + ogg)은 따로 주는
 * 리소스팩 'BBC 뱀파이어 사운드' (BBC_Vampire_Sounds.zip) 에 들어 있음 (게임 음성·음악이라 공개 저장소·모드 jar 에는 안 넣음).
 * 리소스팩이 없으면 소리만 안 남. 연금술사 사운드 팩과 같이 켜도 됨 (sounds.json 은 팩끼리 합쳐짐)
 */
public final class VampireSounds {
    private VampireSounds() {}

    public static final SoundEvent LUNGE = reg("vampire.lunge");            // 흡혈 돌진
    public static final SoundEvent STAB = reg("vampire.stab");              // 흡혈 돌진 (평타 중) 손끝 흡혈
    public static final SoundEvent DIVE = reg("vampire.dive");              // 흡혈 돌진 (공중) 내리꽂아 흡혈
    public static final SoundEvent FREEZE = reg("vampire.freeze");          // 기화냉동법
    public static final SoundEvent ICECLAW = reg("vampire.iceclaw");        // 기화냉동법 (평타 중) 얼음 손톱
    public static final SoundEvent ICEDROP = reg("vampire.icedrop");        // 기화냉동법 (공중) 얼려 내리꽂기
    public static final SoundEvent KUA_L = reg("vampire.kua_l");            // 괴력 연격 ① 수도 연타
    public static final SoundEvent KUA_M = reg("vampire.kua_m");            // 괴력 연격 ② 얼려 올려치기
    public static final SoundEvent KUA_H = reg("vampire.kua_h");            // 괴력 연격 ③ 머리 잡아 내리꽂기
    public static final SoundEvent KUA_D = reg("vampire.kua_d");            // 괴력 연격 ④ 목 물어 흡혈
    public static final SoundEvent REGEN = reg("vampire.regen");            // 재생
    public static final SoundEvent THRALL = reg("vampire.thrall");          // 피의 하인
    public static final SoundEvent COUNTER = reg("vampire.counter");        // 냉동 반격 (받아쳤을 때)
    public static final SoundEvent BEAM = reg("vampire.beam");              // 혈안 광선
    public static final SoundEvent BEAM_QUICK = reg("vampire.beam_quick");  // 혈안 광선 (평타 중) 단발
    public static final SoundEvent BEAM_AIR = reg("vampire.beam_air");      // 혈안 광선 (공중)
    public static final SoundEvent MUDA = reg("vampire.muda");              // 무다 연타
    public static final SoundEvent BUD = reg("vampire.bud");                // 육아 지배
    public static final SoundEvent AWAKEN = reg("vampire.awaken");          // 각성 대사 (해는 저물었다…)
    public static final SoundEvent ULT_START = reg("vampire.ult_start");    // 각성기 발동
    public static final SoundEvent ULT1 = reg("vampire.ult1");              // 각성기 대사 1
    public static final SoundEvent ULT2 = reg("vampire.ult2");              // 각성기 대사 2
    public static final SoundEvent ULT3 = reg("vampire.ult3");              // 각성기 대사 3
    public static final Identifier THEME = Identifier.of("bbc", "vampire.theme");   // 각성 중 테마곡 (음악)
    public static final SoundEvent THEME_EVENT = Registry.register(Registries.SOUND_EVENT, THEME, SoundEvent.of(THEME));

    private static SoundEvent reg(String path) {
        Identifier id = Identifier.of("bbc", path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** 클래스 로딩 = 등록 (모드 초기화에서 부름) */
    public static void register() {}

    /** 기술 음성: 시전자 자리에서 (주변 사람도 들림, 조금 멀리까지) */
    public static void play(ServerPlayerEntity p, SoundEvent s) { AlchemySounds.play(p, s, 1.6f); }

    /** 컷신 대사: 컷신을 보는 사람에겐 화면 기준으로 크게, 주변 사람에겐 시전자 자리에서 */
    public static void voice(ServerPlayerEntity p, SoundEvent s, List<ServerPlayerEntity> listeners) { AlchemySounds.voice(p, s, listeners); }
}
