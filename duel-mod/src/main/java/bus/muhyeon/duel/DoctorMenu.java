package bus.muhyeon.duel;

import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.Pokemon;
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
import java.util.function.Consumer;

import static bus.muhyeon.duel.PokemonDoctor.P;

/** 포켓몬 박사 화면들 */
public final class DoctorMenu {
    private DoctorMenu() {}

    public static final int JOIN_DEX = 30;

    // ═════════ 입문 ═════════
    public static class Detail extends ButtonMenu {
        private static final int INFO = 0, DEX = 2, RANKS = 4, BACK = 7, JOIN = 8;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Detail(id, inv, p), Text.literal("직업 — 포켓몬 박사")));
        }

        private Detail(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            button(INFO, icon(PokemonDoctor.INSTANCE.icon(), "포켓몬 박사", Formatting.AQUA,
                    "포획·진화·부화로 연구 포인트(RP)를 쌓습니다",
                    "등급이 오르면 개체값·성격을 보고 직접 바꿀 수 있습니다",
                    "입문하면 마스터볼 1개 (계정당 한 번)"));
            int dex = PokemonDoctor.dex(player);
            boolean ok = dex >= JOIN_DEX;
            button(DEX, icon(ok ? Items.LIME_DYE : Items.GRAY_DYE, JobMenu.mark(ok) + "도감 " + JOIN_DEX + "종 포획", ok ? Formatting.GREEN : Formatting.RED,
                    "현재 " + dex + "종"));
            button(RANKS, icon(Items.EXPERIENCE_BOTTLE, "등급과 혜택", Formatting.AQUA, Status.rankTable(0)));
            button(BACK, icon(Items.ARROW, "◀ 직업 선택으로", Formatting.WHITE));
            button(JOIN, icon(ok ? Items.NETHER_STAR : Items.BARRIER, ok ? "포켓몬 박사가 되기" : "조건을 채우세요", ok ? Formatting.AQUA : Formatting.GRAY,
                    ok ? "▶ 클릭해서 입문" : "✘ 표시된 조건을 확인하세요"));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == BACK) { JobMenu.later(player, () -> JobMenu.Select.open(player)); return true; }
            if (slot != JOIN) return false;
            if (JobMenu.mustQuitFirst(player, Jobs.DOCTOR_ID)) return true;
            if (PokemonDoctor.dex(player) < JOIN_DEX) { player.sendMessage(Text.literal("도감 " + JOIN_DEX + "종을 먼저 채우세요.").formatted(Formatting.RED), false); return true; }
            AlchemyData data = AlchemyData.get(player.getServer());
            AlchemyData.Entry e = data.of(player.getUuid());
            e.job = Jobs.DOCTOR_ID;
            PokemonDoctor.INSTANCE.onJoin(player, e);
            data.markDirty();
            player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.2f);
            player.sendMessage(Text.literal("✦ 연구 조수가 되었습니다. 웅크린 채 빈손으로 포켓몬을 우클릭하면 감정합니다.").formatted(Formatting.AQUA, Formatting.BOLD), false);
            player.getServer().getPlayerManager().broadcast(Text.literal("✦ " + player.getName().getString() + "님이 포켓몬 박사의 길에 들어섰습니다.").formatted(Formatting.AQUA), false);
            JobMenu.later(player, () -> Status.open(player));
            return true;
        }
    }

    // ═════════ 내 직업 ═════════
    public static class Status extends ButtonMenu {
        private static final int STATUS = 0, RANKS = 1, QUEST = 2, APPRAISE = 3, NATURE = 4, TRAIN = 5, SIXV = 6,
                KIT = 7, MASTER = 8, CLIENT = 9, HELP = 15, LIST = 17;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Status(id, inv, p), Text.literal("내 직업 — 포켓몬 박사")));
        }

        private Status(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player, 2);
            AlchemyData.Entry e = Jobs.entry(player);
            PokemonDoctor.rollDay(e);
            PokemonDoctor.checkRank(player, e);
            int r = PokemonDoctor.rank(e);
            int dex = PokemonDoctor.dex(player);
            List<String> st = new ArrayList<>();
            st.add("연구 포인트: " + Alchemy.fmt(e.num(P + "rp")) + (r < 5 ? " / " + Alchemy.fmt(PokemonDoctor.NEED_RP[r + 1]) : ""));
            st.add("도감: " + dex + "종" + (r < 5 && PokemonDoctor.NEED_DEX[r + 1] > 0 ? " / " + PokemonDoctor.NEED_DEX[r + 1] : ""));
            st.add("연구 키트: " + (int) e.num(P + "kits") + "개");
            if (Money.available()) st.add("소지금: " + Money.fmt(Money.get(player)));
            button(STATUS, icon(Items.NAME_TAG, "내 직업: " + PokemonDoctor.RANKS[r], Formatting.AQUA, st.toArray(new String[0])));
            button(RANKS, icon(Items.EXPERIENCE_BOTTLE, "등급과 혜택", Formatting.AQUA, rankTable(r)));

            PokemonDoctor.Quest[] qs = PokemonDoctor.quests(player.getUuid());
            int done = (int) e.num(P + "qdone");
            List<String> ql = new ArrayList<>();
            for (int i = 0; i < qs.length; i++) {
                boolean d = (done & (1 << i)) != 0;
                ql.add((d ? "✔ " : "· ") + qs[i].text() + "  (" + Math.min(qs[i].need(), (int) e.num(P + "q" + i)) + "/" + qs[i].need() + ")");
            }
            ql.add("완료마다 RP +30" + (r >= 3 ? " + 하이퍼볼 3개" : ""));
            ql.add("매일 자정에 바뀝니다");
            button(QUEST, icon(Items.WRITABLE_BOOK, "오늘의 연구 의뢰", Formatting.YELLOW, ql.toArray(new String[0])));

            button(APPRAISE, icon(Items.SPYGLASS, "내 포켓몬 감정", Formatting.WHITE,
                    r >= 2 ? "개체값·성격·특성을 정확히 봅니다" : "개체값 별점을 봅니다", "▶ 클릭해서 파티에서 고르기"));
            button(NATURE, gated(r, 3, Items.MANGROVE_PROPAGULE, "성격 연구",
                    "파티 포켓몬의 성격을 원하는 것으로", "하루 " + PokemonDoctor.NATURE_PER_DAY + "회 · " + Money.fmt(PokemonDoctor.NATURE_PRICE),
                    "오늘 " + (int) e.num(P + "nat") + " / " + PokemonDoctor.NATURE_PER_DAY));
            button(TRAIN, gated(r, 4, Items.GOLDEN_APPLE, "특훈 연구",
                    "개체값 하나를 31로", "하루 " + PokemonDoctor.TRAIN_PER_DAY + "회 · 연구 키트 1개",
                    "오늘 " + (int) e.num(P + "train") + " / " + PokemonDoctor.TRAIN_PER_DAY));
            button(SIXV, gated(r, 5, Items.ENCHANTED_GOLDEN_APPLE, "6V 연구",
                    "모든 개체값을 31로 (전설·환상 제외)", "하루 " + PokemonDoctor.SIXV_PER_DAY + "회 · 연구 키트 " + PokemonDoctor.SIXV_KITS + "개",
                    "오늘 " + (int) e.num(P + "sixv") + " / " + PokemonDoctor.SIXV_PER_DAY));
            button(KIT, icon(Items.BREWING_STAND, "연구 키트 구매", Formatting.GREEN,
                    Money.fmt(PokemonDoctor.KIT_PRICE) + " + 경험 사탕 L " + PokemonDoctor.KIT_CANDY + "개",
                    "특훈·6V 연구에 씁니다", "▶ 클릭해서 1개 구매"));
            long week = PokemonDoctor.today() / 7;
            boolean mb = r >= 5 && (long) e.num(P + "mbw") != week;
            button(MASTER, r >= 5 ? icon(PokemonDoctor.item("cobblemon:master_ball", Items.ENDER_PEARL), "주간 마스터볼", mb ? Formatting.LIGHT_PURPLE : Formatting.GRAY,
                    mb ? "▶ 클릭해서 받기" : "이번 주에는 이미 받았습니다")
                    : icon(Items.GRAY_DYE, "주간 마스터볼 (잠김)", Formatting.DARK_GRAY, "박사(등급 5)부터 주 1개"));
            ServerPlayerEntity c = PokemonDoctor.client(player);
            button(CLIENT, c != null ? icon(Items.PLAYER_HEAD, "의뢰 받은 포켓몬: " + c.getName().getString(), Formatting.GOLD,
                    "의뢰인의 파티 포켓몬을 연구합니다 (비용은 내가 냄)", "연구 1번마다 RP +5", "▶ 클릭")
                    : icon(Items.GRAY_DYE, "의뢰 받은 포켓몬 없음", Formatting.DARK_GRAY,
                    "다른 플레이어가 내 8칸 안에서", "/doctor ask " + player.getName().getString() + " 을 입력하면 맡길 수 있습니다", "(선임 연구원부터)"));
            button(HELP, icon(Items.BOOK, "도움말", Formatting.WHITE,
                    "감정: 웅크린 채 빈손으로 포켓몬 우클릭 (야생·남의 포켓몬도 가능)",
                    "RP: 새 종 +10 · 포획 +1 · 진화 +3 · 부화 +2 · 이로치 +50",
                    "교수: 64칸 안 야생 이로치를 빛기둥으로 표시",
                    "박사: 전설·환상 포켓몬이 나타나면 방향과 거리를 알림"));
            button(LIST, icon(Items.BOOK, "직업 목록", Formatting.WHITE, "▶ 직업 선택 창 보기"));
        }

        private static ItemStack gated(int r, int need, net.minecraft.item.Item item, String name, String... lore) {
            if (r >= need) {
                String[] l = new String[lore.length + 1];
                System.arraycopy(lore, 0, l, 0, lore.length);
                l[lore.length] = "▶ 클릭해서 파티에서 고르기";
                return icon(item, name, Formatting.GOLD, l);
            }
            return icon(Items.GRAY_DYE, name + " (잠김)", Formatting.DARK_GRAY, PokemonDoctor.RANKS[need] + "(등급 " + need + ")부터");
        }

        static String[] rankTable(int rank) {
            String[] rows = {"",
                    "1 연구 조수 — 마스터볼 1개(최초), 감정(별점)",
                    "2 연구원 (RP 500) — 정확한 감정, 포획률 +10%",
                    "3 선임 연구원 (RP 2,000 · 도감 100) — 성격 연구, 의뢰 보상 하이퍼볼, 연구 대행",
                    "4 교수 (RP 6,000 · 도감 200) — 특훈 연구(개체값 31), 이로치 감지",
                    "5 박사 (RP 15,000 · 도감 300) — 6V 연구, 주간 마스터볼, 전설 레이더"};
            String[] out = new String[5];
            for (int i = 1; i <= 5; i++) out[i - 1] = (i == rank ? "▶ " : "   ") + rows[i];
            return out;
        }

        @Override
        protected boolean onPick(int slot) {
            AlchemyData.Entry e = Jobs.entry(player);
            int r = PokemonDoctor.rank(e);
            switch (slot) {
                case LIST -> { JobMenu.later(player, () -> JobMenu.Select.open(player)); return true; }
                case APPRAISE -> { pick(player, player, "감정할 포켓몬", pk -> PokemonDoctor.appraise(player, pk, r)); return true; }
                case NATURE -> { if (r >= 3) pick(player, player, "성격 연구", pk -> natureFlow(player, pk, false)); return r >= 3; }
                case TRAIN -> { if (r >= 4) pick(player, player, "특훈 연구", pk -> trainFlow(player, pk, false)); return r >= 4; }
                case SIXV -> { if (r >= 5) pick(player, player, "6V 연구", pk -> sixV(player, pk, false)); return r >= 5; }
                case KIT -> { buyKit(player); return true; }
                case MASTER -> { claimMaster(player); return true; }
                case CLIENT -> {
                    ServerPlayerEntity c = PokemonDoctor.client(player);
                    if (c == null) return false;
                    JobMenu.later(player, () -> ClientMenu.open(player, c));
                    return true;
                }
                default -> { return false; }
            }
        }
    }

    // ═════════ 의뢰인 포켓몬 연구 ═════════
    public static class ClientMenu extends ButtonMenu {
        private final ServerPlayerEntity client;
        private static final int NATURE = 2, TRAIN = 4, SIXV = 6, BACK = 8;

        static void open(ServerPlayerEntity doc, ServerPlayerEntity client) {
            doc.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new ClientMenu(id, inv, doc, client),
                    Text.literal("의뢰 — " + client.getName().getString())));
        }

        private ClientMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player, ServerPlayerEntity client) {
            super(syncId, inv, player);
            this.client = client;
            int r = PokemonDoctor.rank(Jobs.entry(player));
            button(0, icon(Items.SPYGLASS, "감정", Formatting.WHITE, "▶ 의뢰인 파티에서 고르기"));
            button(NATURE, r >= 3 ? icon(Items.MANGROVE_PROPAGULE, "성격 연구", Formatting.GOLD, "내 하루 횟수·비용 사용") : icon(Items.GRAY_DYE, "성격 연구 (잠김)", Formatting.DARK_GRAY));
            button(TRAIN, r >= 4 ? icon(Items.GOLDEN_APPLE, "특훈 연구", Formatting.GOLD, "내 연구 키트 사용") : icon(Items.GRAY_DYE, "특훈 연구 (잠김)", Formatting.DARK_GRAY));
            button(SIXV, r >= 5 ? icon(Items.ENCHANTED_GOLDEN_APPLE, "6V 연구", Formatting.GOLD, "내 연구 키트 사용") : icon(Items.GRAY_DYE, "6V 연구 (잠김)", Formatting.DARK_GRAY));
            button(BACK, icon(Items.ARROW, "◀ 돌아가기", Formatting.WHITE));
        }

        @Override
        protected boolean onPick(int slot) {
            int r = PokemonDoctor.rank(Jobs.entry(player));
            switch (slot) {
                case 0 -> pick(player, client, "의뢰 — 감정", pk -> PokemonDoctor.appraise(player, pk, r));
                case NATURE -> { if (r < 3) return false; pick(player, client, "의뢰 — 성격", pk -> natureFlow(player, pk, true)); }
                case TRAIN -> { if (r < 4) return false; pick(player, client, "의뢰 — 특훈", pk -> trainFlow(player, pk, true)); }
                case SIXV -> { if (r < 5) return false; pick(player, client, "의뢰 — 6V", pk -> sixV(player, pk, true)); }
                case BACK -> JobMenu.later(player, () -> Status.open(player));
                default -> { return false; }
            }
            return true;
        }
    }

    // ═════════ 연구 처리 ═════════

    private static boolean useDaily(ServerPlayerEntity p, String key, int max) {
        AlchemyData.Entry e = Jobs.entry(p);
        PokemonDoctor.rollDay(e);
        if (e.num(P + key) >= max) { err(p, "오늘 횟수를 다 썼습니다 (" + max + "회)"); return false; }
        return true;
    }

    private static boolean useKits(ServerPlayerEntity p, int n) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (e.num(P + "kits") < n) { err(p, "연구 키트가 부족합니다 (" + n + "개 필요, 보유 " + (int) e.num(P + "kits") + ")"); return false; }
        return true;
    }

    private static void done(ServerPlayerEntity doc, Pokemon pk, String what, boolean forClient) {
        AlchemyData.Entry e = Jobs.entry(doc);
        doc.getServerWorld().playSound(null, doc.getBlockPos(), SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.0f, 1.2f);
        doc.sendMessage(Text.literal("✦ " + what + " 완료: ").formatted(Formatting.GREEN).append(pk.getSpecies().getTranslatedName()), false);
        ServerPlayerEntity owner = pk.getOwnerPlayer();
        if (forClient && owner != null && owner != doc) {
            owner.sendMessage(Text.literal("🔬 " + doc.getName().getString() + " 박사가 연구를 마쳤습니다 (" + what + "): ").formatted(Formatting.AQUA)
                    .append(pk.getSpecies().getTranslatedName()), false);
            PokemonDoctor.addRp(doc, e, 5, "연구 대행");
        }
        AlchemyData.get(doc.getServer()).markDirty();
    }

    static void natureFlow(ServerPlayerEntity doc, Pokemon pk, boolean forClient) {
        if (!useDaily(doc, "nat", PokemonDoctor.NATURE_PER_DAY)) return;
        if (!Money.available()) { err(doc, "코블달러 모드를 찾을 수 없습니다"); return; }
        JobMenu.later(doc, () -> NaturePick.open(doc, pk, n -> {
            if (!useDaily(doc, "nat", PokemonDoctor.NATURE_PER_DAY)) return;
            if (!Money.take(doc, PokemonDoctor.NATURE_PRICE)) { err(doc, "코블달러가 부족합니다 (" + Money.fmt(PokemonDoctor.NATURE_PRICE) + ")"); return; }
            Jobs.entry(doc).addNum(P + "nat", 1);
            pk.setNature(n);
            done(doc, pk, "성격 → " + Text.translatable(n.getDisplayName()).getString(), forClient);
        }));
    }

    static void trainFlow(ServerPlayerEntity doc, Pokemon pk, boolean forClient) {
        if (!useDaily(doc, "train", PokemonDoctor.TRAIN_PER_DAY) || !useKits(doc, 1)) return;
        JobMenu.later(doc, () -> StatPick.open(doc, pk, s -> {
            if (!useDaily(doc, "train", PokemonDoctor.TRAIN_PER_DAY) || !useKits(doc, 1)) return;
            if (PokemonDoctor.iv(pk, s) >= 31) { err(doc, "이미 31입니다"); return; }
            AlchemyData.Entry e = Jobs.entry(doc);
            e.addNum(P + "train", 1);
            e.addNum(P + "kits", -1);
            pk.setIV(s, 31);
            done(doc, pk, "특훈 (" + statName(s) + " 31)", forClient);
        }));
    }

    static void sixV(ServerPlayerEntity doc, Pokemon pk, boolean forClient) {
        if (pk.isLegendary() || pk.isMythical() || pk.hasLabels("ultra_beast")) { err(doc, "전설·환상·울트라비스트는 6V 연구를 할 수 없습니다"); return; }
        if (!useDaily(doc, "sixv", PokemonDoctor.SIXV_PER_DAY) || !useKits(doc, PokemonDoctor.SIXV_KITS)) return;
        AlchemyData.Entry e = Jobs.entry(doc);
        e.addNum(P + "sixv", 1);
        e.addNum(P + "kits", -PokemonDoctor.SIXV_KITS);
        for (Stat s : PokemonDoctor.SIX) pk.setIV(s, 31);
        done(doc, pk, "6V 연구", forClient);
        doc.getServerWorld().playSound(null, doc.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    static void buyKit(ServerPlayerEntity p) {
        if (!Money.available()) { err(p, "코블달러 모드를 찾을 수 없습니다"); return; }
        net.minecraft.item.Item candy = PokemonDoctor.item("cobblemon:exp_candy_l", Items.AIR);
        if (candy == Items.AIR || Jobs.count(p, candy) < PokemonDoctor.KIT_CANDY) { err(p, "경험 사탕 L " + PokemonDoctor.KIT_CANDY + "개가 필요합니다"); return; }
        if (Money.get(p) < PokemonDoctor.KIT_PRICE) { err(p, "코블달러가 부족합니다 (" + Money.fmt(PokemonDoctor.KIT_PRICE) + ")"); return; }
        if (!Money.take(p, PokemonDoctor.KIT_PRICE)) { err(p, "결제에 실패했습니다"); return; }
        Jobs.take(p, candy, PokemonDoctor.KIT_CANDY);
        AlchemyData.Entry e = Jobs.entry(p);
        e.addNum(P + "kits", 1);
        AlchemyData.get(p.getServer()).markDirty();
        p.sendMessage(Text.literal("✦ 연구 키트 1개 구매 (보유 " + (int) e.num(P + "kits") + "개)").formatted(Formatting.GREEN), false);
    }

    static void claimMaster(ServerPlayerEntity p) {
        AlchemyData.Entry e = Jobs.entry(p);
        long week = PokemonDoctor.today() / 7;
        if (PokemonDoctor.rank(e) < 5) { err(p, "박사(등급 5)부터 받을 수 있습니다"); return; }
        if ((long) e.num(P + "mbw") == week) { err(p, "이번 주에는 이미 받았습니다"); return; }
        e.num(P + "mbw", week);
        AlchemyData.get(p.getServer()).markDirty();
        p.getInventory().offerOrDrop(new ItemStack(PokemonDoctor.item("cobblemon:master_ball", Items.ENDER_PEARL)));
        p.sendMessage(Text.literal("✦ 주간 마스터볼을 받았습니다").formatted(Formatting.LIGHT_PURPLE), false);
    }

    static String statName(Stat s) {
        for (int i = 0; i < PokemonDoctor.SIX.length; i++) if (PokemonDoctor.SIX[i] == s) return PokemonDoctor.SIX_NAMES[i];
        return s.toString();
    }

    static void err(ServerPlayerEntity p, String m) { p.sendMessage(Text.literal(m).formatted(Formatting.RED), false); }

    // ═════════ 고르기 창들 ═════════

    /** 파티에서 포켓몬 하나 고르기 */
    static void pick(ServerPlayerEntity viewer, ServerPlayerEntity owner, String title, Consumer<Pokemon> then) {
        JobMenu.later(viewer, () -> viewer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (id, inv, x) -> new PartyPick(id, inv, viewer, owner, then), Text.literal(title))));
    }

    public static class PartyPick extends ButtonMenu {
        private final List<Pokemon> party;
        private final Consumer<Pokemon> then;

        private PartyPick(int syncId, PlayerInventory inv, ServerPlayerEntity player, ServerPlayerEntity owner, Consumer<Pokemon> then) {
            super(syncId, inv, player);
            this.then = then;
            this.party = PokemonDoctor.party(owner);
            int r = PokemonDoctor.rank(Jobs.entry(player));
            for (int i = 0; i < 6; i++) {
                Pokemon pk = party.get(i);
                if (pk == null) { button(i, icon(Items.GLASS_BOTTLE, "빈 칸", Formatting.DARK_GRAY)); continue; }
                String ivs = r >= 2 ? "개체값 합 " + PokemonDoctor.ivTotal(pk) + "/186" : "개체값 " + PokemonDoctor.stars(pk);
                button(i, icon(PokemonDoctor.icon(pk), pk.getSpecies().getTranslatedName(), Formatting.WHITE,
                        "Lv." + pk.getLevel() + (pk.getShiny() ? " ✨" : ""), ivs, "▶ 클릭"));
            }
            button(8, icon(Items.ARROW, "◀ 닫기", Formatting.WHITE));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == 8) return true;
            if (slot < 0 || slot >= 6 || party.get(slot) == null) return false;
            then.accept(party.get(slot));
            return true;
        }
    }

    public static class StatPick extends ButtonMenu {
        private final Pokemon pk;
        private final Consumer<Stat> then;

        static void open(ServerPlayerEntity p, Pokemon pk, Consumer<Stat> then) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new StatPick(id, inv, p, pk, then), Text.literal("특훈 — 31로 만들 능력치")));
        }

        private StatPick(int syncId, PlayerInventory inv, ServerPlayerEntity player, Pokemon pk, Consumer<Stat> then) {
            super(syncId, inv, player);
            this.pk = pk;
            this.then = then;
            net.minecraft.item.Item[] icons = {Items.RED_DYE, Items.ORANGE_DYE, Items.YELLOW_DYE, Items.LIGHT_BLUE_DYE, Items.LIME_DYE, Items.PINK_DYE};
            for (int i = 0; i < 6; i++) {
                int v = PokemonDoctor.iv(pk, PokemonDoctor.SIX[i]);
                button(i, icon(v >= 31 ? Items.GRAY_DYE : icons[i], PokemonDoctor.SIX_NAMES[i] + " " + v, v >= 31 ? Formatting.DARK_GRAY : Formatting.WHITE,
                        v >= 31 ? "이미 최대" : "▶ 클릭하면 31로 (연구 키트 1개)"));
            }
            button(8, icon(Items.ARROW, "◀ 닫기", Formatting.WHITE));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == 8) return true;
            if (slot < 0 || slot >= 6) return false;
            then.accept(PokemonDoctor.SIX[slot]);
            return true;
        }
    }

    public static class NaturePick extends ButtonMenu {
        private final List<Nature> natures;
        private final Consumer<Nature> then;

        static void open(ServerPlayerEntity p, Pokemon pk, Consumer<Nature> then) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new NaturePick(id, inv, p, pk, then), Text.literal("성격 연구 — 바꿀 성격")));
        }

        private NaturePick(int syncId, PlayerInventory inv, ServerPlayerEntity player, Pokemon pk, Consumer<Nature> then) {
            super(syncId, inv, player, 3);
            this.then = then;
            this.natures = new ArrayList<>(Natures.all());
            for (int i = 0; i < natures.size() && i < 26; i++) {
                Nature n = natures.get(i);
                boolean cur = n == pk.getNature();
                String up = n.getIncreasedStat() == null ? "-" : statName(n.getIncreasedStat());
                String down = n.getDecreasedStat() == null ? "-" : statName(n.getDecreasedStat());
                button(i, icon(new ItemStack(cur ? Items.GRAY_DYE : Items.PAPER), Text.translatable(n.getDisplayName()), cur ? Formatting.DARK_GRAY : Formatting.WHITE,
                        up.equals(down) || up.equals("-") ? "변화 없음 (무보정)" : "↑ " + up + "  ↓ " + down, cur ? "지금 성격" : "▶ 클릭"));
            }
            button(26, icon(Items.ARROW, "◀ 닫기", Formatting.WHITE));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == 26) return true;
            if (slot < 0 || slot >= natures.size()) return false;
            then.accept(natures.get(slot));
            return true;
        }
    }
}
