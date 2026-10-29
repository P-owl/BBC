package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

import static bus.muhyeon.duel.Vampire.P;

/** 뱀파이어 화면 */
public final class VampireMenu {
    private VampireMenu() {}

    /** 뱀파이어가 아닌 사람이 직업 선택 창에서 눌렀을 때 */
    public static class Info extends ButtonMenu {
        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Info(id, inv, p), Text.literal("뱀파이어")));
        }

        private Info(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            button(0, icon(DuelMod.STONE_MASK, "석가면", Formatting.DARK_RED,
                    "사막 피라미드·정글 사원 상자 3%, 고대 도시 상자 5%",
                    "밤에 머리에 쓰고, 쓴 채로 피해를 입으면(피를 묻히면)…",
                    "지금 직업은 사라지고 되돌릴 수 없습니다",
                    "인간으로 돌아오는 길은 성자(신부 5등급)의 정화 의식뿐"));
            button(2, icon(Items.SUNFLOWER, "약점", Formatting.GOLD,
                    "햇빛: 1초마다 4 피해 + 불 (비·그늘·지하는 안전)",
                    "신성력: 신부의 공격과 성수는 2배",
                    "피가 떨어지면 갈증 — 약해지고 재생이 멈춤"));
            button(4, icon(Items.REDSTONE, "힘", Formatting.RED,
                    "밤: 힘 I · 야간 투시 · 피 없이 재생",
                    "흡혈 · 괴력 · 재생 · 피의 하인",
                    "숙련: 혈동 · 혈안 광선 · 육아 지배 · 하루 한 번 되살아남"));
            button(6, icon(Items.CHISELED_STONE_BRICKS, "그 너머", Formatting.DARK_PURPLE,
                    "숙련된 뱀파이어 → 기둥 속 사내 (바람·열·빛·흡수 중 하나)",
                    "그리고 서버에 단 하나, 완전생물"));
            button(8, icon(Items.ARROW, "◀ 직업 선택으로", Formatting.WHITE));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == 8) { JobMenu.later(player, () -> JobMenu.Select.open(player)); return true; }
            return false;
        }
    }

    public static class Status extends ButtonMenu {
        private static final int STATUS = 0, NEXT = 1, SKILLS = 2, RULES = 4, PILLAR = 6, LIST = 8;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Status(id, inv, p), Text.literal("내 직업 — 뱀파이어")));
        }

        private Status(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            AlchemyData.Entry e = Jobs.entry(player);
            int s = Vampire.stage(e);
            List<String> st = new ArrayList<>();
            st.add("피: " + (int) Vampire.blood(e) + " / 100" + (Vampire.thirsty(e) ? "  (갈증!)" : Vampire.blood(e) >= 70 ? "  (포만)" : ""));
            st.add("흡혈량: " + Alchemy.fmt(e.num(P + "drink")));
            st.add("플레이어 흡혈: " + (int) e.num(P + "pdrink") + "회 · 넘긴 밤: " + (int) e.num(P + "nights"));
            if (s == 3) st.add("루트: " + Pillar.routeName(e) + " · 숙련도 " + (int) Pillar.mastery(e) + "%");
            button(STATUS, icon(Items.NAME_TAG, "내 직업: " + Vampire.RANKS[s], Formatting.DARK_RED, st.toArray(new String[0])));

            if (s == 1) {
                button(NEXT, icon(Items.EXPERIENCE_BOTTLE, "숙련된 뱀파이어까지", Formatting.AQUA,
                        JobMenu.mark(e.num(P + "drink") >= Vampire.SKILLED_DRINK) + "흡혈량 " + Alchemy.fmt(Vampire.SKILLED_DRINK) + "  (" + Alchemy.fmt(e.num(P + "drink")) + ")",
                        JobMenu.mark(e.num(P + "pdrink") >= Vampire.SKILLED_PLAYERS) + "플레이어 흡혈 " + Vampire.SKILLED_PLAYERS + "회 (같은 사람 하루 1번)  (" + (int) e.num(P + "pdrink") + ")",
                        JobMenu.mark(e.num(P + "nights") >= Vampire.SKILLED_NIGHTS) + "뱀파이어로 밤 " + Vampire.SKILLED_NIGHTS + "번  (" + (int) e.num(P + "nights") + ")"));
            } else if (s == 2) {
                button(NEXT, icon(Items.CHISELED_STONE_BRICKS, "기둥 속 사내 각성까지", Formatting.DARK_PURPLE, Pillar.progressLore(player, e)));
            } else {
                button(NEXT, icon(Items.NETHER_STAR, s == 4 ? "완전생물" : "완전생물까지", Formatting.LIGHT_PURPLE, Ultimate.progressLore(player, e)));
            }
            button(SKILLS, icon(Items.REDSTONE, "기술 (전투 모드 ` 키 → 1~8)", Formatting.RED, JobSkills.describe(Vampire.SKILLS, player, e, s)));
            button(RULES, icon(Items.SUNFLOWER, "종족 규칙", Formatting.GOLD,
                    s >= 4 ? "햇빛·신성력 면역" : s == 3 ? "햇빛: 3초 안에 그늘로 못 가면 석화되어 죽음" : "햇빛: 1초마다 4 피해 + 불 (비·그늘·지하는 안전)",
                    s >= 3 ? "피 대신 스태미나로 기술 · 맨손 공격이 30% 흡수" : "피: 1분 2 (낮 5) 감소 · 흡혈로만 참 · 재생에 피 사용",
                    "70 이상 포만(힘 I) · 0 갈증(최대 체력 -6, 이동 -15%, 햇빛 2배)",
                    "신성력 피해 " + (s >= 4 ? "면역" : "2배")));
            if (s == 2) button(PILLAR, icon(Items.CHISELED_STONE_BRICKS, "기둥 각성 의식", Formatting.DARK_PURPLE,
                    "조건을 채우고, 고대의 기둥을 고대 도시·사막 피라미드 안에 설치한 뒤",
                    "밤에 기둥을 우클릭하면 의식이 시작됩니다"));
            button(LIST, icon(Items.BOOK, "직업 목록", Formatting.WHITE, "▶ 직업 선택 창 보기"));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == LIST) { JobMenu.later(player, () -> JobMenu.Select.open(player)); return true; }
            if (slot == SKILLS && Vampire.stage(Jobs.entry(player)) == 4) { JobMenu.later(player, () -> UltimateMenu.open(player)); return true; }
            return false;
        }
    }

    /** 완전생물: 기술 6개 고르기 */
    public static class UltimateMenu extends ButtonMenu {
        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new UltimateMenu(id, inv, p), Text.literal("완전생물 — 기술 6개 고르기")));
        }

        private UltimateMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player, 4);
            AlchemyData.Entry e = Jobs.entry(player);
            List<String> picks = Ultimate.picks(e);
            net.minecraft.item.Item[] icons = {Items.FEATHER, Items.BLAZE_POWDER, Items.GLOWSTONE_DUST, Items.SLIME_BALL};
            for (int r = 0; r < 4; r++) {
                String route = Pillar.ROUTES[r];
                JobSkills.Skill[] l = Routes.list(route);
                for (int i = 0; i < 7; i++) {
                    boolean on = picks.contains(route + ":" + i);
                    button(r * 9 + i, icon(on ? Items.LIME_DYE : icons[r], (on ? "✔ " : "") + l[i].name() + " (" + Pillar.routeName(route) + ")", on ? Formatting.GREEN : Formatting.WHITE,
                            l[i].desc(), "스태미나 " + (int) l[i].cost() + " · 재사용 " + l[i].cooldown() + "초", on ? "▶ 클릭해서 빼기" : "▶ 클릭해서 넣기"));
                }
                button(r * 9 + 8, icon(Items.PAPER, Pillar.routeName(route) + " 루트", Formatting.GRAY, "선택 " + picks.size() + " / 6", "7번 생명 창조 · 8번 파문은 고정"));
            }
        }

        @Override
        protected boolean onPick(int slot) {
            int r = slot / 9, i = slot % 9;
            if (r >= 4 || i >= 7) return false;
            AlchemyData.Entry e = Jobs.entry(player);
            List<String> picks = new ArrayList<>(Ultimate.picks(e));
            String key = Pillar.ROUTES[r] + ":" + i;
            if (picks.contains(key)) picks.remove(key);
            else if (picks.size() < 6) picks.add(key);
            else { player.sendMessage(Text.literal("6개까지 고를 수 있습니다 (먼저 하나를 빼세요)").formatted(Formatting.RED), true); return false; }
            e.str(Vampire.P + "ult", String.join(",", picks));
            AlchemyData.get(player.getServer()).markDirty();
            JobSkills.resetCooldown(player.getUuid());
            JobMenu.later(player, () -> open(player));
            return true;
        }
    }
}
