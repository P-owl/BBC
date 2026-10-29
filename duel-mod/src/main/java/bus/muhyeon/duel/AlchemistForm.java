package bus.muhyeon.duel;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.Abilities;
import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.feature.StringSpeciesFeature;
import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.List;

/** 연금술사 피카츄 → 현자 피카츄 (영구 전환, 되돌릴 수 없음) */
public final class AlchemistForm {
    public static final String FEATURE = "alchemist_form";
    public static final String ALCHEMIST = "alchemist";
    public static final String SAGE = "sage";

    private static final List<String> ALCHEMIST_MOVES = List.of("weaponforge", "steelarmblade", "groundforge");
    private static final List<String> SAGE_MOVES = List.of("stoneforge", "earthrebuild", "soulbarrier", "lifeforge");

    private AlchemistForm() {}

    public static boolean isPikachu(Pokemon p) { return p.getSpecies().getName().equalsIgnoreCase("pikachu"); }
    public static boolean isAlchemist(Pokemon p) { return isPikachu(p) && p.getAspects().contains(ALCHEMIST); }
    public static boolean isSage(Pokemon p) { return isPikachu(p) && p.getAspects().contains(SAGE); }

    /** 일반 피카츄(기본 폼) 인지 — 연금술사/현자/파트너 등 다른 폼은 제외 */
    public static boolean isPlainPikachu(Pokemon p) {
        return isPikachu(p) && !p.getAspects().contains(ALCHEMIST) && !p.getAspects().contains(SAGE)
                && p.getForm().getName().equalsIgnoreCase("normal");
    }

    /** 일반 피카츄 → 연금술사 피카츄. 첫 번째 기술은 남기고 2~4번 칸에 전용기 */
    public static void toAlchemist(Pokemon p) {
        new StringSpeciesFeature(FEATURE, ALCHEMIST).apply(p);
        try {
            AbilityTemplate tpl = Abilities.get("equivalentexchange");
            if (tpl != null) p.updateAbility(tpl.create(false, Priority.LOW));
        } catch (Throwable t) {
            DuelMod.LOG.warn("등가교환 특성 부여 실패: {}", t.toString());
        }
        try {
            for (int i = 0; i < ALCHEMIST_MOVES.size(); i++) {
                MoveTemplate t = Moves.getByName(ALCHEMIST_MOVES.get(i));
                if (t != null) p.getMoveSet().setMove(i + 1, t.create(t.getMaxPp()));
            }
        } catch (Throwable t) {
            DuelMod.LOG.warn("연금술사 폼 기술 부여 실패: {}", t.toString());
        }
    }

    public static void toSage(Pokemon p) {
        // 이 한 줄이 aspect 갱신 + 폼 교체까지 처리 (그라니테에서 검증한 방식)
        new StringSpeciesFeature(FEATURE, SAGE).apply(p);

        try {
            AbilityTemplate tpl = Abilities.get("philosophersstone");
            if (tpl != null) p.updateAbility(tpl.create(false, Priority.LOW));
        } catch (Throwable t) {
            DuelMod.LOG.warn("현자의 돌 특성 부여 실패: {}", t.toString());
        }

        try {
            for (int i = 0; i < SAGE_MOVES.size(); i++) {
                MoveTemplate t = Moves.getByName(SAGE_MOVES.get(i));
                if (t != null) p.getMoveSet().setMove(i, t.create(t.getMaxPp()));
            }
        } catch (Throwable t) {
            DuelMod.LOG.warn("현자 폼 기술 부여 실패: {}", t.toString());
        }
    }
}
