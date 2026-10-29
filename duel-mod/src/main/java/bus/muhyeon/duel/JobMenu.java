package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * 직업 화면 3종.
 *   /job → 무직이면 [직업 선택] 창, 직업이 있으면 [내 직업] 창
 *   [직업 선택] → 직업 아이콘 클릭 → [직업 상세] (소개·조건·되기 버튼)
 */
public final class JobMenu {
    private JobMenu() {}

    public static void open(ServerPlayerEntity p) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (Jobs.isAlchemist(e)) { Status.open(p); return; }
        JobType t = Jobs.type(e);
        if (t != null) t.openStatus(p);
        else Select.open(p);
    }

    static void later(ServerPlayerEntity p, Runnable r) {
        Tasks.later(1, () -> { if (!p.isDisconnected()) r.run(); });
    }

    static String mark(boolean ok) { return ok ? "✔ " : "✘ "; }

    /** 다른 직업을 고르려면 먼저 지금 직업을 그만둬야 함 */
    static boolean mustQuitFirst(ServerPlayerEntity p, String target) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (!Jobs.hasJob(e) || e.job.equals(target)) return false;
        p.sendMessage(Text.literal("이미 다른 직업(" + Jobs.rankName(e) + ")이 있습니다. 직업 선택 창의 [직업 그만두기]를 먼저 누르세요.").formatted(Formatting.RED), false);
        return true;
    }

    private static final java.util.Map<java.util.UUID, Long> QUIT_CONFIRM = new java.util.HashMap<>();

    /** 직업 그만두기 (10초 안에 두 번 눌러야 확정) */
    static void quit(ServerPlayerEntity p) {
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        if (!Jobs.hasJob(e)) return;
        if (Jobs.VAMPIRE_ID.equals(e.job)) {
            p.sendMessage(Text.literal("뱀파이어는 스스로 그만둘 수 없습니다. 신부의 정화 의식만이 인간으로 되돌릴 수 있습니다.").formatted(Formatting.DARK_RED), false);
            return;
        }
        long now = System.currentTimeMillis();
        Long first = QUIT_CONFIRM.get(p.getUuid());
        if (first == null || now - first > 10_000) {
            QUIT_CONFIRM.put(p.getUuid(), now);
            p.sendMessage(Text.literal("정말 " + Jobs.rankName(e) + "을(를) 그만두시겠습니까? 등급과 진행도가 사라집니다. 10초 안에 한 번 더 누르면 확정됩니다.").formatted(Formatting.YELLOW), false);
            return;
        }
        QUIT_CONFIRM.remove(p.getUuid());
        CombatMode.exit(p, null);
        String old = Jobs.rankName(e);
        if (Jobs.isAlchemist(e)) {
            e.xp = 0;
            e.truth = false; // 몸에 남은 대가(통행료)는 그대로
        } else {
            JobType t = Jobs.type(e);
            if (t != null) { t.onQuit(p, e); e.clearJobData(t.prefix()); }
        }
        e.job = "";
        data.markDirty();
        JobSkills.resetCooldown(p.getUuid());
        p.sendMessage(Text.literal(old + "을(를) 그만두었습니다.").formatted(Formatting.GRAY), false);
    }

    // ═════════ 직업 선택 ═════════
    public static class Select extends ButtonMenu {
        private static final int HELP = 0, ALCH = 1, QUIT = 16, MINE = 17;
        private static final int FIRST_TYPE = 2;
        private final List<JobType> shown = new ArrayList<>();

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inv, x) -> new Select(syncId, inv, p), Text.literal("직업 선택")));
        }

        private Select(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player, 2);
            AlchemyData.Entry e = Jobs.entry(player);
            button(HELP, icon(Items.BOOK, "직업이란?", Formatting.WHITE,
                    "직업은 하나만 가질 수 있습니다",
                    "직업마다 입문 조건이 있고, 활동할수록 등급이 오릅니다",
                    "다른 직업으로 바꾸려면 [직업 그만두기] 후 새로 입문하세요",
                    "아이콘을 클릭하면 자세한 내용과 조건을 볼 수 있습니다"));
            boolean mine = Jobs.isAlchemist(e);
            button(ALCH, icon(Items.BREWING_STAND, "연금술사" + (mine ? " (내 직업)" : ""), Formatting.GOLD,
                    "이해 · 분해 · 재구축",
                    "연성 손실이 줄고, 연성 전투 기술을 씁니다",
                    "정점은 진리를 보는 것",
                    "▶ 클릭해서 자세히 보기"));
            int slot = FIRST_TYPE;
            for (JobType t : Jobs.TYPES.values()) {
                if (slot >= QUIT) break;
                shown.add(t);
                boolean my = t.id().equals(e.job);
                List<String> lore = new ArrayList<>(List.of(t.summary()));
                lore.add(t.selectable() ? "▶ 클릭해서 자세히 보기" : "▶ 클릭해서 보기 (선택으로는 될 수 없음)");
                button(slot++, icon(t.icon(), t.name() + (my ? " (내 직업)" : ""), t.color(), lore.toArray(new String[0])));
            }
            if (Jobs.hasJob(e)) button(QUIT, icon(Items.BARRIER, "직업 그만두기", Formatting.RED,
                    "지금 직업: " + Jobs.rankName(e), "등급과 진행도가 사라집니다", "▶ 10초 안에 두 번 클릭하면 확정"));
            button(MINE, icon(Items.NAME_TAG, "내 직업: " + Jobs.rankName(e), Formatting.WHITE,
                    Jobs.hasJob(e) ? "▶ 클릭해서 내 직업 창" : "아직 직업이 없습니다"));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == ALCH) { later(player, () -> AlchemistDetail.open(player)); return true; }
            if (slot == MINE && Jobs.hasJob(Jobs.entry(player))) { later(player, () -> JobMenu.open(player)); return true; }
            if (slot == QUIT) { quit(player); return true; }
            int i = slot - FIRST_TYPE;
            if (i >= 0 && i < shown.size()) {
                JobType t = shown.get(i);
                later(player, () -> { if (t.id().equals(Jobs.entry(player).job)) t.openStatus(player); else t.openDetail(player); });
                return true;
            }
            return false;
        }
    }

    // ═════════ 연금술사 상세 / 입문 ═════════
    public static class AlchemistDetail extends ButtonMenu {
        private static final int INFO = 0, KNOWN = 1, DEPOSIT = 2, TRIBUTE = 3, RANKS = 5, SKILLS = 6, BACK = 7, JOIN = 8;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inv, x) -> new AlchemistDetail(syncId, inv, p), Text.literal("직업 — 연금술사")));
        }

        private AlchemistDetail(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            AlchemyData.Entry e = Jobs.entry(player);
            button(INFO, icon(Items.BREWING_STAND, "연금술사", Formatting.GOLD,
                    "연성진으로 아이템을 분해하고 재구축하는 자",
                    "연성할수록 숙련도가 오르고 등급이 올라갑니다",
                    "등급 4 대연금술사는 진리의 문에 도전할 수 있습니다"));

            int kinds = AlchemyValues.depositedKinds(e);
            boolean k = kinds >= Jobs.JOIN_KNOWN;
            button(KNOWN, icon(k ? Items.LIME_DYE : Items.GRAY_DYE, mark(k) + "분해해 본 아이템 " + Jobs.JOIN_KNOWN + "종", k ? Formatting.GREEN : Formatting.RED,
                    "현재 " + kinds + "종 (계열로 이해한 것은 제외, 직접 분해한 종류만)"));

            boolean d = e.depositedTotal >= Jobs.JOIN_DEPOSITED;
            button(DEPOSIT, icon(d ? Items.LIME_DYE : Items.GRAY_DYE, mark(d) + "누적 분해 " + Alchemy.fmt(Jobs.JOIN_DEPOSITED) + " E", d ? Formatting.GREEN : Formatting.RED,
                    "현재 " + Alchemy.fmt(e.depositedTotal) + " E"));

            List<String> tl = new ArrayList<>();
            boolean t = true;
            for (int i = 0; i < Jobs.TRIBUTE_ITEMS.length; i++) {
                int have = Jobs.count(player, Jobs.TRIBUTE_ITEMS[i]);
                boolean ok = have >= Jobs.TRIBUTE_COUNTS[i];
                t &= ok;
                tl.add(mark(ok) + new ItemStack(Jobs.TRIBUTE_ITEMS[i]).getName().getString() + " " + Jobs.TRIBUTE_COUNTS[i] + "개  (" + have + ")");
            }
            tl.add("입문할 때 소모됩니다");
            button(TRIBUTE, icon(t ? Items.LIME_DYE : Items.GRAY_DYE, mark(t) + "공물", t ? Formatting.GREEN : Formatting.RED, tl.toArray(new String[0])));

            button(RANKS, icon(Items.EXPERIENCE_BOTTLE, "등급과 혜택", Formatting.AQUA, Status.rankTable(Jobs.rank(e))));
            button(SKILLS, icon(Items.POINTED_DRIPSTONE, "연성 기술 (전투 모드 ` 키 → 1~7)", Formatting.AQUA, AlchemySkills.describe(Jobs.rank(e))));
            button(BACK, icon(Items.ARROW, "◀ 직업 선택으로", Formatting.WHITE));

            boolean mine = Jobs.isAlchemist(e);
            boolean all = k && d && t;
            button(JOIN, icon(mine ? Items.NAME_TAG : all ? Items.NETHER_STAR : Items.BARRIER,
                    mine ? "이미 연금술사입니다" : all ? "연금술사가 되기" : "조건을 모두 채우세요",
                    mine ? Formatting.GRAY : all ? Formatting.GOLD : Formatting.GRAY,
                    mine ? "" : all ? "▶ 클릭하면 공물을 바치고 입문합니다" : "✘ 표시된 조건을 확인하세요"));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == BACK) { later(player, () -> Select.open(player)); return true; }
            if (slot != JOIN) return false;
            AlchemyData data = AlchemyData.get(player.getServer());
            AlchemyData.Entry e = data.of(player.getUuid());
            if (Jobs.isAlchemist(e)) return false;
            if (mustQuitFirst(player, Jobs.ALCHEMIST)) return true;
            if (AlchemyValues.depositedKinds(e) < Jobs.JOIN_KNOWN || e.depositedTotal < Jobs.JOIN_DEPOSITED) {
                player.sendMessage(Text.literal("입문 조건을 아직 채우지 못했습니다.").formatted(Formatting.RED), false);
                return true;
            }
            for (int i = 0; i < Jobs.TRIBUTE_ITEMS.length; i++) {
                if (Jobs.count(player, Jobs.TRIBUTE_ITEMS[i]) < Jobs.TRIBUTE_COUNTS[i]) {
                    player.sendMessage(Text.literal("공물이 부족합니다.").formatted(Formatting.RED), false);
                    return true;
                }
            }
            for (int i = 0; i < Jobs.TRIBUTE_ITEMS.length; i++) Jobs.take(player, Jobs.TRIBUTE_ITEMS[i], Jobs.TRIBUTE_COUNTS[i]);
            e.job = Jobs.ALCHEMIST;
            e.xp = 0;
            data.markDirty();
            Alchemy.bigFx(player);
            player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
            player.sendMessage(Text.literal("✦ 견습 연금술사가 되었습니다. 연성할수록 숙련도가 오릅니다.").formatted(Formatting.GOLD, Formatting.BOLD), false);
            player.getServer().getPlayerManager().broadcast(Text.literal("✦ " + player.getName().getString() + "님이 연금술사의 길에 들어섰습니다.")
                    .formatted(Formatting.GOLD), false);
            later(player, () -> Status.open(player));
            return true;
        }
    }

    // ═════════ 내 직업 (연금술사) ═════════
    public static class Status extends ButtonMenu {
        private static final int STATUS = 0, RANKS = 2, SKILLS = 3, GATE = 5, RAID = 6, LIST = 8;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inv, x) -> new Status(syncId, inv, p), Text.literal("내 직업")));
        }

        private Status(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            AlchemyData.Entry e = Jobs.entry(player);
            int rank = Jobs.rank(e);
            button(STATUS, icon(Items.NAME_TAG, "내 직업: " + Jobs.rankName(e), Formatting.WHITE, statusLore(e, rank)));
            button(RANKS, icon(Items.EXPERIENCE_BOTTLE, "등급과 혜택", Formatting.AQUA, rankTable(rank)));
            List<String> sk = new ArrayList<>(List.of(AlchemySkills.describe(rank)));
            if (rank == 5) sk.add("G: 손뼉 연성 (어디서든 연성 화면)");
            button(SKILLS, icon(Items.POINTED_DRIPSTONE, "연성 기술 (전투 모드 ` 키 → 1~7)", Formatting.AQUA, sk.toArray(new String[0])));
            if (rank == 4) {
                button(GATE, icon(Items.IRON_DOOR, "진리의 문", Formatting.LIGHT_PURPLE, "대연금술사만 도전할 수 있습니다", "▶ 클릭해서 통행료 확인"));
            } else if (rank == 5) {
                button(GATE, icon(Items.ENDER_EYE, "진리를 보았다", Formatting.LIGHT_PURPLE, "치른 대가: " + Jobs.tollName(e.toll),
                        e.toll.equals(e.automail) ? "⚙ " + Jobs.automailName(e.toll) + " 장착 중 — " + AutomailItem.effect(e.toll)
                                : "▶ " + Jobs.automailName(e.toll) + "를 만들어 우클릭하면 잃은 부위를 보완 + 강화"));
            } else {
                button(GATE, icon(Items.IRON_DOOR, "진리의 문 (잠김)", Formatting.DARK_GRAY, "대연금술사(등급 4)가 되면 열립니다"));
            }
            boolean rOk = rank >= RAID_RANK;
            long left = e.lastRaidCircle <= 0 ? 0 : e.lastRaidCircle + RAID_DAYS * 86_400_000L - System.currentTimeMillis();
            button(RAID, icon(DuelMod.TRANSMUTATION_CIRCLE, "레이드 연성진 연성" + (rOk ? "" : " (잠김)"), rOk ? Formatting.AQUA : Formatting.DARK_GRAY,
                    "연금술사 피카츄 레이드를 여는 연성진 (현자의 돌 불필요)",
                    "필요: " + Alchemy.fmt(RAID_ENERGY) + " E + 천둥의돌 " + RAID_THUNDER + "개 + 철 블록 " + RAID_IRON + "개",
                    RAID_DAYS + "일에 1번" + (left > 0 ? "  (남은 시간 " + (left / 3_600_000L) + "시간)" : ""),
                    "연금술사는 레이드 승리 시 피카츄를 반드시 포획",
                    rOk ? "▶ 클릭해서 연성" : "연금술사(등급 2)부터"));
            button(LIST, icon(Items.BOOK, "직업 목록", Formatting.WHITE, "▶ 직업 선택 창 보기"));
        }

        static final int RAID_RANK = 2, RAID_THUNDER = 4, RAID_IRON = 4, RAID_DAYS = 3;
        static final double RAID_ENERGY = 262_144;

        private void forgeRaidCircle() {
            AlchemyData data = AlchemyData.get(player.getServer());
            AlchemyData.Entry e = data.of(player.getUuid());
            net.minecraft.item.Item thunder = StoneMenu.cobblemon("thunder_stone");
            if (Jobs.rank(e) < RAID_RANK) { msg("연금술사(등급 2)부터 연성할 수 있습니다."); return; }
            long left = e.lastRaidCircle <= 0 ? 0 : e.lastRaidCircle + RAID_DAYS * 86_400_000L - System.currentTimeMillis();
            if (left > 0) { msg("아직 연성할 수 없습니다. 남은 시간 " + (left / 3_600_000L + 1) + "시간"); return; }
            if (e.energy + 1e-6 < RAID_ENERGY) { msg("연성 에너지가 부족합니다."); return; }
            if (Jobs.count(player, thunder) < RAID_THUNDER || Jobs.count(player, Items.IRON_BLOCK) < RAID_IRON) { msg("재료가 부족합니다."); return; }
            Jobs.take(player, thunder, RAID_THUNDER);
            Jobs.take(player, Items.IRON_BLOCK, RAID_IRON);
            e.energy -= RAID_ENERGY;
            e.lastRaidCircle = System.currentTimeMillis();
            data.markDirty();
            player.getInventory().offerOrDrop(new ItemStack(DuelMod.TRANSMUTATION_CIRCLE));
            Alchemy.fx(player.getServerWorld(), player.getBlockPos(), false);
            player.sendMessage(Text.literal("✦ 레이드 연성진을 연성했습니다.").formatted(Formatting.AQUA), false);
        }

        private void msg(String m) { player.sendMessage(Text.literal(m).formatted(Formatting.RED), false); }

        @Override
        protected boolean onPick(int slot) {
            if (slot == LIST) { later(player, () -> Select.open(player)); return true; }
            if (slot == RAID) { forgeRaidCircle(); return true; }
            if (slot == GATE && Jobs.rank(Jobs.entry(player)) == 4) { later(player, () -> GateMenu.open(player)); return true; }
            return false;
        }

        static String[] statusLore(AlchemyData.Entry e, int rank) {
            List<String> l = new ArrayList<>();
            if (rank >= 1 && rank < 4) {
                l.add("숙련도: " + Alchemy.fmt(e.xp) + " / " + Alchemy.fmt(Jobs.RANK_XP[rank + 1]));
                l.add("(연성에 쓴 에너지만큼 오릅니다)");
            } else if (rank == 4) {
                l.add("숙련도 최대. 남은 것은 진리뿐");
            } else if (rank == 5) {
                l.add("모든 것을 이해한 자");
            }
            l.add("연성 손실 " + Math.round(Jobs.tax(e, false) * 100) + "%  ·  하루 분해 한도 " + Alchemy.fmt(Jobs.dailyCap(e)) + " E");
            return l.toArray(new String[0]);
        }

        static String[] rankTable(int rank) {
            String[] rows = {
                    "",
                    "1 견습 연금술사 — 손실 22%",
                    "2 연금술사 (숙련도 100,000) — 손실 20%, 분해 한도 ×1.25",
                    "3 숙련 연금술사 (400,000) — 손실 17%, ×1.5",
                    "4 대연금술사 (1,000,000) — 손실 15%, ×2, 현자의 돌 연성, 진리의 문",
                    "5 진리를 본 자 — 손실 5%, ×3, 손뼉 연성, 모든 아이템 연성"
            };
            String[] out = new String[5];
            for (int i = 1; i <= 5; i++) out[i - 1] = (i == rank ? "▶ " : "   ") + rows[i];
            return out;
        }
    }
}
