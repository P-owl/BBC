package bus.muhyeon.duel;

import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

import static bus.muhyeon.duel.Priest.P;

/** 신부 화면들 */
public final class PriestMenu {
    private PriestMenu() {}

    public static final int TRIBUTE_GOLD = 4;

    static boolean hasGhost(ServerPlayerEntity p) {
        for (Pokemon pk : PokemonDoctor.party(p)) if (Priest.ghost(pk)) return true;
        return false;
    }

    // ═════════ 입문 ═════════
    public static class Detail extends ButtonMenu {
        private static final int INFO = 0, GHOST = 2, GOLD = 3, RANKS = 5, SKILLS = 6, BACK = 7, JOIN = 8;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Detail(id, inv, p), Text.literal("직업 — 신부")));
        }

        private Detail(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            button(INFO, icon(Items.TOTEM_OF_UNDYING, "신부", Formatting.YELLOW,
                    "고스트 포켓몬을 거두면 명성과 신성력이 오릅니다",
                    "신성력이 담긴 공격은 뱀파이어에게 2배",
                    "성자가 되면 뱀파이어를 인간으로 되돌립니다"));
            boolean g = hasGhost(player);
            button(GHOST, icon(g ? Items.LIME_DYE : Items.GRAY_DYE, JobMenu.mark(g) + "고스트 타입 포켓몬과 함께", g ? Formatting.GREEN : Formatting.RED,
                    "파티에 고스트 타입이 1마리 이상"));
            int have = Jobs.count(player, Items.GOLD_BLOCK);
            boolean gold = have >= TRIBUTE_GOLD;
            button(GOLD, icon(gold ? Items.LIME_DYE : Items.GRAY_DYE, JobMenu.mark(gold) + "봉헌: 금 블록 " + TRIBUTE_GOLD + "개", gold ? Formatting.GREEN : Formatting.RED,
                    "보유 " + have + "개 · 입문할 때 소모"));
            button(RANKS, icon(Items.EXPERIENCE_BOTTLE, "등급과 혜택", Formatting.AQUA, Status.rankTable(0)));
            button(SKILLS, icon(Items.BELL, "기술 (전투 모드 ` 키 → 1~8)", Formatting.AQUA, JobSkills.describe(Priest.SKILLS, player, Jobs.entry(player), 0)));
            button(BACK, icon(Items.ARROW, "◀ 직업 선택으로", Formatting.WHITE));
            boolean all = g && gold;
            button(JOIN, icon(all ? Items.NETHER_STAR : Items.BARRIER, all ? "신부가 되기" : "조건을 채우세요", all ? Formatting.YELLOW : Formatting.GRAY,
                    all ? "▶ 클릭해서 서품" : "✘ 표시된 조건을 확인하세요"));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == BACK) { JobMenu.later(player, () -> JobMenu.Select.open(player)); return true; }
            if (slot != JOIN) return false;
            if (JobMenu.mustQuitFirst(player, Jobs.PRIEST_ID)) return true;
            if (!hasGhost(player) || Jobs.count(player, Items.GOLD_BLOCK) < TRIBUTE_GOLD) {
                player.sendMessage(Text.literal("조건을 아직 채우지 못했습니다.").formatted(Formatting.RED), false);
                return true;
            }
            Jobs.take(player, Items.GOLD_BLOCK, TRIBUTE_GOLD);
            AlchemyData data = AlchemyData.get(player.getServer());
            AlchemyData.Entry e = data.of(player.getUuid());
            e.job = Jobs.PRIEST_ID;
            Priest.INSTANCE.onJoin(player, e);
            data.markDirty();
            player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.5f, 0.8f);
            player.sendMessage(Text.literal("✝ 수사가 되었습니다. 웅크리고 가만히 있으면 기도로 신성력이 빨리 찹니다.").formatted(Formatting.YELLOW, Formatting.BOLD), false);
            player.getServer().getPlayerManager().broadcast(Text.literal("✝ " + player.getName().getString() + "님이 성직의 길에 들어섰습니다.").formatted(Formatting.YELLOW), false);
            JobMenu.later(player, () -> Status.open(player));
            return true;
        }
    }

    // ═════════ 내 직업 ═════════
    public static class Status extends ButtonMenu {
        private static final int STATUS = 0, RANKS = 1, SKILLS = 2, WATER1 = 4, WATER_ALL = 5, RELIC = 6, HELP = 7, LIST = 8;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Status(id, inv, p), Text.literal("내 직업 — 신부")));
        }

        private Status(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            AlchemyData.Entry e = Jobs.entry(player);
            int r = Priest.rank(e);
            List<String> st = new ArrayList<>();
            st.add("명성: " + Alchemy.fmt(e.num(P + "fame")) + (r < 5 ? " / " + Alchemy.fmt(Priest.NEED[r + 1]) : ""));
            st.add("신성력: " + (int) Priest.holy(player) + " / " + (int) Priest.maxHoly(e));
            st.add("성수: " + Jobs.count(player, DuelMod.HOLY_WATER) + "병");
            button(STATUS, icon(Items.NAME_TAG, "내 직업: " + Priest.RANKS[r], Formatting.YELLOW, st.toArray(new String[0])));
            button(RANKS, icon(Items.EXPERIENCE_BOTTLE, "등급과 혜택", Formatting.AQUA, rankTable(r)));
            button(SKILLS, icon(Items.BELL, "기술 (전투 모드 ` 키 → 1~8)", Formatting.AQUA, JobSkills.describe(Priest.SKILLS, player, e, r)));
            int water = waterBottles(player), nug = Jobs.count(player, Items.GOLD_NUGGET);
            button(WATER1, icon(new ItemStack(DuelMod.HOLY_WATER), Text.literal("성수 만들기 ×1"), Formatting.AQUA,
                    "물병 1 + 금 조각 1 → 성수 1", "보유: 물병 " + water + " · 금 조각 " + nug, "▶ 클릭"));
            button(WATER_ALL, icon(new ItemStack(DuelMod.HOLY_WATER, 16), Text.literal("성수 만들기 (전부)"), Formatting.AQUA,
                    "가진 재료로 만들 수 있는 만큼", "▶ 클릭"));
            if (r >= 5) {
                boolean has = e.flag("pri.aja");
                button(RELIC, icon(new ItemStack(DuelMod.AJA_STONE), Text.literal(has ? "성유물: 에이자의 적석 (보관 중)" : "성유물 보관"), Formatting.RED,
                        has ? "▶ 클릭해서 꺼내기" : "적석을 들고 클릭하면 봉인 (위치 숨김)", "보관한 성자가 쓰러지면 그 자리에 떨어집니다"));
            }
            button(HELP, icon(Items.BOOK, "도움말", Formatting.WHITE,
                    "명성: 고스트 포켓몬 포획 +10 (최종 진화 +30, 이로치 ×5, 기라티나 +500)",
                    "      야생 고스트 배틀 승리 +3 (하루 30) · 축복 +5 (하루 5명) · 주민 축복 +1",
                    "      뱀파이어 퇴치 일반 +50 / 숙련 +150 / 기둥 속 사내 +500 / 완전생물 +3,000",
                    "신성력: 초당 1, 웅크리고 가만히(기도) 초당 5, 고스트 포획 +10",
                    "신성 피해는 뱀파이어·기둥 속 사내에게 2배 (완전생물은 면역)"));
            button(LIST, icon(Items.BOOK, "직업 목록", Formatting.WHITE, "▶ 직업 선택 창 보기"));
        }

        static String[] rankTable(int rank) {
            String[] rows = {"",
                    "1 수사 — 성수 제작, 성스러운 일격, 성수 투척",
                    "2 신부 (명성 300) — 축복, 밤에 근처 고스트 포켓몬 감지",
                    "3 사제 (1,500) — 퇴마, 십자가 결계, 고스트 포획률 +20%, 고스트 경험치 +30%",
                    "4 주교 (5,000) — 빛의 십자가, 뱀파이어 감지 24칸, 하루 1번 아군 죽음을 막음",
                    "5 성자 (15,000) — 성자의 기적, 정화 의식, 에이자의 적석 보관"};
            String[] out = new String[5];
            for (int i = 1; i <= 5; i++) out[i - 1] = (i == rank ? "▶ " : "   ") + rows[i];
            return out;
        }

        @Override
        protected boolean onPick(int slot) {
            switch (slot) {
                case LIST -> { JobMenu.later(player, () -> JobMenu.Select.open(player)); return true; }
                case WATER1 -> { brew(player, 1); return true; }
                case WATER_ALL -> { brew(player, 64); return true; }
                case RELIC -> {
                    String err = Aja.store(player, !Jobs.entry(player).flag("pri.aja"));
                    if (err != null) player.sendMessage(Text.literal(err).formatted(Formatting.RED), false);
                    return true;
                }
                default -> { return false; }
            }
        }
    }

    // ═════════ 성수 ═════════

    static boolean isWater(ItemStack s) {
        if (!s.isOf(Items.POTION)) return false;
        PotionContentsComponent c = s.get(DataComponentTypes.POTION_CONTENTS);
        return c != null && c.matches(Potions.WATER);
    }

    static int waterBottles(ServerPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < p.getInventory().size(); i++) if (isWater(p.getInventory().getStack(i))) n += p.getInventory().getStack(i).getCount();
        return n;
    }

    static void brew(ServerPlayerEntity p, int want) {
        int made = 0;
        while (made < want && Jobs.count(p, Items.GOLD_NUGGET) > 0) {
            int slot = -1;
            for (int i = 0; i < p.getInventory().size(); i++) if (isWater(p.getInventory().getStack(i))) { slot = i; break; }
            if (slot < 0) break;
            p.getInventory().getStack(slot).decrement(1);
            Jobs.take(p, Items.GOLD_NUGGET, 1);
            p.getInventory().offerOrDrop(new ItemStack(DuelMod.HOLY_WATER));
            made++;
        }
        if (made == 0) { p.sendMessage(Text.literal("물병과 금 조각이 필요합니다").formatted(Formatting.RED), false); return; }
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BREWING_STAND_BREW, SoundCategory.PLAYERS, 1.0f, 1.2f);
        p.sendMessage(Text.literal("✝ 성수 " + made + "병을 축복했습니다").formatted(Formatting.YELLOW), false);
    }
}
