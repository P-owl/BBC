package bus.muhyeon.duel;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.block.Blocks;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 연금술사/현자 피카츄의 연출 상태를 aspect 로 클라이언트에 알린다.
 *  - forged  : 무기 연성 강화 중 (꼬리 칼날 유지) → 리졸버가 forged 포저로 전환
 *  - victory : 배틀 승리 직후 3초 → victory 포저로 전환 후 회수
 * 실제 위력 증가/소모는 배틀 엔진(weaponforge.js)이 처리하고, 여기선 보이는 것만 맞춘다.
 */
public final class AlchemyState {
    public static final String FORGED = "forged";
    public static final String VICTORY = "victory";
    public static final String BLADING = "blading";
    private static final int BLADE_TICKS = 40;
    private static final int VICTORY_TICKS = 60;

    private AlchemyState() {}

    public static void register() {
        // 교체로 다시 나오면 강화는 사라진 상태 (배틀 엔진에서 교체 시 볼라틸 제거)
        CobblemonEvents.POKEMON_SENT_POST.subscribe(Priority.NORMAL, e -> {
            setForged(e.getPokemon(), false);
            setAspect(e.getPokemon(), VICTORY, false);
            setAspect(e.getPokemon(), BLADING, false);
            return kotlin.Unit.INSTANCE;
        });
    }

    static boolean isAlchemyForm(Pokemon p) {
        return p != null && AlchemistForm.isPikachu(p)
                && (p.getAspects().contains(AlchemistForm.ALCHEMIST) || p.getAspects().contains(AlchemistForm.SAGE));
    }

    public static void setForged(Pokemon p, boolean on) {
        if (p == null || !AlchemistForm.isAlchemist(p)) return;
        setAspect(p, FORGED, on);
    }

    /** 무기 연성/강철팔 칼날: 기술 직전 꼬리를 칼날로 바꿨다가 2초 뒤 되돌림 */
    public static void blade(Pokemon p) {
        if (p == null || !AlchemistForm.isAlchemist(p)) return;
        setAspect(p, BLADING, true);
        Tasks.later(BLADE_TICKS, () -> setAspect(p, BLADING, false));
    }

    private static void setAspect(Pokemon p, String aspect, boolean on) {
        if (p == null) return;
        Set<String> s = new HashSet<>(p.getForcedAspects());
        boolean changed = on ? s.add(aspect) : s.remove(aspect);
        if (!changed) return;
        p.setForcedAspects(s);
        p.updateAspects();
    }

    /** true 를 돌려주면 회수를 미룬다 (승리 포즈 재생 후 직접 회수) */
    public static boolean victoryPose(Pokemon p) {
        if (!isAlchemyForm(p)) return false;
        setForged(p, false);
        setAspect(p, BLADING, false);
        PokemonEntity e = p.getEntity();
        if (e == null || p.getCurrentHealth() <= 0 || e.isRemoved()) return false;

        setAspect(p, VICTORY, true);
        if (e.getWorld() instanceof ServerWorld w) {
            if (AlchemistForm.isAlchemist(p)) alchemistVictoryFx(w, e);
            else sageVictoryFx(w, e);
        }
        Tasks.later(VICTORY_TICKS, () -> {
            setAspect(p, VICTORY, false);
            p.tryRecallWithAnimation();
        });
        return true;
    }

    /** 땅을 짚는 순간 전기 스파크 + 주변에 바위가시 필드 */
    private static void alchemistVictoryFx(ServerWorld w, PokemonEntity e) {
        Tasks.later(8, () -> {
            if (e.isRemoved()) return;
            Vec3d c = e.getPos();
            w.playSound(null, e.getBlockPos(), SoundEvents.BLOCK_POINTED_DRIPSTONE_LAND, SoundCategory.NEUTRAL, 1.0f, 0.7f);
            w.playSound(null, e.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.NEUTRAL, 0.4f, 1.6f);
            w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.3, c.z, 40, 0.6, 0.3, 0.6, 0.4);
            var stone = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.POINTED_DRIPSTONE.getDefaultState());
            ThreadLocalRandom r = ThreadLocalRandom.current();
            for (int i = 0; i < 10; i++) {
                double a = Math.PI * 2 * i / 10 + r.nextDouble() * 0.3;
                double d = 1.2 + r.nextDouble() * 0.6;
                double x = c.x + Math.cos(a) * d, z = c.z + Math.sin(a) * d;
                for (int k = 0; k < 5; k++) w.spawnParticles(stone, x, c.y + k * 0.18, z, 4, 0.05, 0.05, 0.05, 0.0);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, x, c.y + 0.9, z, 3, 0.05, 0.1, 0.05, 0.1);
            }
        });
    }

    /** 먼 산을 바라볼 때 발밑 붉은 연성진이 은은하게 빛남 */
    private static void sageVictoryFx(ServerWorld w, PokemonEntity e) {
        DustParticleEffect red = new DustParticleEffect(new Vector3f(1.0f, 0.15f, 0.15f), 0.8f);
        for (int t = 0; t < VICTORY_TICKS; t += 10) {
            Tasks.later(t, () -> {
                if (e.isRemoved()) return;
                Vec3d c = e.getPos();
                for (int i = 0; i < 24; i++) {
                    double a = Math.PI * 2 * i / 24;
                    w.spawnParticles(red, c.x + Math.cos(a) * 1.1, c.y + 0.05, c.z + Math.sin(a) * 1.1, 1, 0, 0, 0, 0);
                }
            });
        }
    }
}
