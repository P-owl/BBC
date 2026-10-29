package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static bus.muhyeon.duel.Politics.P;

/** 당대표·대통령 화면들 */
public final class PoliticsMenu {
    private PoliticsMenu() {}

    static void err(ServerPlayerEntity p, String m) { if (m != null) p.sendMessage(Text.literal(m).formatted(Formatting.RED), false); }

    // ═════════ 입문 ═════════
    public static class Detail extends ButtonMenu {
        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Detail(id, inv, p), Text.literal("직업 — 당대표")));
        }

        private Detail(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            button(0, icon(Items.GOAT_HORN, "당대표", Formatting.BLUE,
                    "당을 만들고 지지자를 모으는 정치인",
                    "지지 점수 = 플레이어 지지자 ×10 + 주민 지지자 ×2",
                    "대선 후보(5등급)가 되면 대통령 자리에 도전"));
            boolean money = Money.get(player) >= Politics.FOUND_PRICE;
            button(2, icon(money ? Items.LIME_DYE : Items.GRAY_DYE, JobMenu.mark(money) + "창당 자금 " + Money.fmt(Politics.FOUND_PRICE), money ? Formatting.GREEN : Formatting.RED,
                    "입문할 때 냅니다", "보유 " + Money.fmt(Money.get(player))));
            button(4, icon(Items.EXPERIENCE_BOTTLE, "등급과 혜택", Formatting.AQUA, Status.rankTable(0)));
            button(5, icon(Items.BELL, "기술 (전투 모드 ` 키 → 1~7)", Formatting.AQUA, JobSkills.describe(Politics.SKILLS, player, Jobs.entry(player), 0)));
            button(7, icon(Items.ARROW, "◀ 직업 선택으로", Formatting.WHITE));
            button(8, icon(money ? Items.NETHER_STAR : Items.BARRIER, money ? "당대표가 되기" : "자금이 부족합니다", money ? Formatting.BLUE : Formatting.GRAY,
                    money ? "▶ 클릭해서 입문 (그다음 /party create <이름> <색>)" : ""));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot == 7) { JobMenu.later(player, () -> JobMenu.Select.open(player)); return true; }
            if (slot != 8) return false;
            if (JobMenu.mustQuitFirst(player, Jobs.LEADER_ID)) return true;
            if (!Money.take(player, Politics.FOUND_PRICE)) { err(player, "코블달러가 부족합니다"); return true; }
            AlchemyData data = AlchemyData.get(player.getServer());
            AlchemyData.Entry e = data.of(player.getUuid());
            e.job = Jobs.LEADER_ID;
            Politics.INSTANCE.onJoin(player, e);
            data.markDirty();
            player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.GOAT_HORN_SOUNDS.get(0).value(), SoundCategory.PLAYERS, 2f, 1f);
            player.sendMessage(Text.literal("📣 창당인이 되었습니다. /party create <당 이름> <색> 으로 당을 만드세요.").formatted(Formatting.AQUA, Formatting.BOLD), false);
            player.sendMessage(Text.literal("   주민 지지: 웅크린 채 에메랄드 5개를 들고 주민 우클릭 · 플레이어 지지: /support " + player.getName().getString()).formatted(Formatting.GRAY), false);
            JobMenu.later(player, () -> Status.open(player));
            return true;
        }
    }

    // ═════════ 내 직업 ═════════
    public static class Status extends ButtonMenu {
        private static final int STATUS = 0, NEXT = 1, PARTY = 2, SKILLS = 3, PLEDGE = 4, FUND = 5, PRES = 6, HELP = 7, LIST = 8;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new Status(id, inv, p), Text.literal("내 직업 — 당대표")));
        }

        private Status(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            MinecraftServer s = player.getServer();
            AlchemyData.Entry e = Jobs.entry(player);
            Politics.checkRank(player, e);
            int r = Politics.rank(e);
            boolean pres = Politics.isPresident(s, player.getUuid());
            int ps = Politics.playerSupporters(s, player.getUuid());
            List<String> st = new ArrayList<>();
            st.add("지지 점수: " + Politics.score(s, player.getUuid()));
            st.add("플레이어 지지자 " + ps + "명 · 주민 지지자 " + Politics.villagers(e) + "/" + Politics.MAX_VILLAGERS);
            if (e.num(P + "malus") != 0) st.add("점수 보정: " + (int) -e.num(P + "malus") + " (벌점은 하루 10씩 회복)");
            st.add("배틀 승리: " + (int) e.num(P + "wins") + " · 당 자금 " + Money.fmt((long) e.num(P + "fund")));
            button(STATUS, icon(Items.NAME_TAG, "내 직업: " + (pres ? "대통령" : Politics.RANKS[r]), pres ? Formatting.GOLD : Formatting.BLUE, st.toArray(new String[0])));

            if (r < 5) {
                int n = r + 1;
                List<String> nl = new ArrayList<>();
                nl.add(JobMenu.mark(Politics.score(s, player.getUuid()) >= Politics.NEED_SCORE[n]) + "지지 점수 " + Politics.NEED_SCORE[n]);
                if (Politics.NEED_PLAYERS[n] > 0) nl.add(JobMenu.mark(ps >= Politics.NEED_PLAYERS[n]) + "플레이어 지지자 " + Politics.NEED_PLAYERS[n] + "명");
                if (n == 3) nl.add(JobMenu.mark(e.num(P + "wins") >= Politics.NEED_WINS) + "포켓몬 배틀 " + Politics.NEED_WINS + "승");
                if (n == 5) nl.add(JobMenu.mark(e.num(P + "fund") >= Politics.NEED_FUND) + "당 자금 " + Money.fmt(Politics.NEED_FUND));
                button(NEXT, icon(Items.EXPERIENCE_BOTTLE, "다음 등급: " + Politics.RANKS[n], Formatting.AQUA, nl.toArray(new String[0])));
            } else {
                button(NEXT, icon(Items.GOLDEN_HELMET, pres ? "대통령 재임 중" : "대선 후보", Formatting.GOLD,
                        pres ? "연속 방어 " + Politics.G(s).getInt("pres.defenses") + "회" : Politics.president(s) == null ? "대통령 자리가 비었습니다 — 7번 키 출마 선언" : "7번 키로 도전장을 받아 대통령에게 도전"));
            }
            NbtCompound party = Politics.G(s).getCompound("party." + player.getUuid());
            button(PARTY, icon(Items.WHITE_BANNER, party.isEmpty() ? "당 없음" : "[" + party.getString("name") + "]", Formatting.WHITE,
                    party.isEmpty() ? "/party create <이름> <색>" : "당원 " + Politics.members(s, player.getUuid()).size() + " / " + Politics.memberCap(r),
                    "/party invite <플레이어> · /party donate <금액>", "/party merge <당대표> (당대표 등급부터)"));
            button(SKILLS, icon(Items.BELL, "기술 (전투 모드 ` 키 → 1~7)", Formatting.AQUA, JobSkills.describe(Politics.SKILLS, player, e, r)));
            String pl = Politics.G(s).getLong("pledged." + player.getUuid()) == Politics.today() ? Politics.pledgeName(Politics.G(s).getString("pledge." + player.getUuid())) : "없음";
            button(PLEDGE, r >= 3 ? icon(Items.PAPER, "오늘의 공약: " + pl, Formatting.YELLOW, "▶ 클릭해서 고르기 (하루 1번)") : icon(Items.GRAY_DYE, "공약 (잠김)", Formatting.DARK_GRAY, "원내대표(등급 3)부터"));
            button(FUND, r >= 4 ? icon(Items.GOLD_INGOT, "당 금고", Formatting.GOLD, "자금 " + Money.fmt((long) e.num(P + "fund")), "▶ 당원에게 지급") : icon(Items.GRAY_DYE, "당 금고 (잠김)", Formatting.DARK_GRAY, "당대표(등급 4)부터"));
            if (pres) button(PRES, icon(Items.GOLDEN_HELMET, "대통령 집무실", Formatting.GOLD, "영토 · 행정 명령 · 수배령 · 특별 수송", "▶ 클릭"));
            button(HELP, icon(Items.BOOK, "명령어", Formatting.WHITE,
                    "/support <당대표> — 지지 (7일에 한 번 변경)",
                    "주민: 웅크린 채 에메랄드 5개 들고 우클릭 (연설 후 30초는 3개)",
                    "/party create|invite|join|leave|donate|pay|merge|info",
                    "/president accept|refuse|claim|unclaim|tp|biome|order|wanted|pardon",
                    "/martiallaw — 비상계엄 해제 투표"));
            button(LIST, icon(Items.BOOK, "직업 목록", Formatting.WHITE, "▶ 직업 선택 창 보기"));
        }

        static String[] rankTable(int rank) {
            String[] rows = {"",
                    "1 창당인 — 창당, 당원 3명, 주민 지지",
                    "2 지역위원장 (점수 20) — 연설, 당원 10명",
                    "3 원내대표 (점수 50 + 배틀 10승) — 공약, 유세",
                    "4 당대표 (점수 100 + 플레이어 2명) — 정치 자금, 경호원, 합당",
                    "5 대선 후보 (점수 150 + 플레이어 3명 + 자금 200,000) — 내란, 출마 선언"};
            String[] out = new String[5];
            for (int i = 1; i <= 5; i++) out[i - 1] = (i == rank ? "▶ " : "   ") + rows[i];
            return out;
        }

        @Override
        protected boolean onPick(int slot) {
            int r = Politics.rank(Jobs.entry(player));
            switch (slot) {
                case LIST -> JobMenu.later(player, () -> JobMenu.Select.open(player));
                case PLEDGE -> { if (r < 3) return false; JobMenu.later(player, () -> PledgeMenu.open(player)); }
                case FUND -> { if (r < 4) return false; JobMenu.later(player, () -> FundMenu.open(player)); }
                case PRES -> { if (!Politics.isPresident(player.getServer(), player.getUuid())) return false; JobMenu.later(player, () -> PresidentMenu.open(player)); }
                default -> { return false; }
            }
            return true;
        }
    }

    // ═════════ 공약 ═════════
    public static class PledgeMenu extends ButtonMenu {
        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new PledgeMenu(id, inv, p), Text.literal("오늘의 공약")));
        }

        private PledgeMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player);
            net.minecraft.item.Item[] icons = {PokemonDoctor.item("cobblemon:great_ball", Items.SNOWBALL), Items.EXPERIENCE_BOTTLE, Items.SUGAR};
            for (int i = 0; i < 3; i++) button(i * 3 + 1, icon(icons[i], Politics.pledgeName(Politics.PLEDGES[i]), Formatting.YELLOW, "오늘 하루 당원 전체", "▶ 클릭"));
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot % 3 != 1) return false;
            err(player, Politics.pledge(player, Politics.PLEDGES[slot / 3]));
            return true;
        }
    }

    // ═════════ 당 금고 ═════════
    public static class FundMenu extends ButtonMenu {
        private final List<UUID> members;

        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new FundMenu(id, inv, p), Text.literal("당 금고 — 클릭하면 10,000 지급")));
        }

        private FundMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player, 3);
            members = Politics.members(player.getServer(), player.getUuid());
            AlchemyData.Entry e = Jobs.entry(player);
            button(26, icon(Items.GOLD_BLOCK, "당 자금 " + Money.fmt((long) e.num(P + "fund")), Formatting.GOLD, "당원 머리를 클릭하면 10,000씩 지급", "/party pay <당원> <금액> 으로도 가능"));
            for (int i = 0; i < members.size() && i < 26; i++) {
                ServerPlayerEntity m = player.getServer().getPlayerManager().getPlayer(members.get(i));
                button(i, icon(Items.PLAYER_HEAD, m != null ? m.getName().getString() : "(접속 안 함)", m != null ? Formatting.WHITE : Formatting.DARK_GRAY, m != null ? "▶ 10,000 지급" : ""));
            }
        }

        @Override
        protected boolean onPick(int slot) {
            if (slot < 0 || slot >= members.size()) return false;
            ServerPlayerEntity m = player.getServer().getPlayerManager().getPlayer(members.get(slot));
            if (m == null) return false;
            err(player, Politics.pay(player, m, 10_000));
            return true;
        }
    }

    // ═════════ 대통령 집무실 ═════════
    public static class PresidentMenu extends ButtonMenu {
        public static void open(ServerPlayerEntity p) {
            p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new PresidentMenu(id, inv, p), Text.literal("대통령 집무실")));
        }

        private PresidentMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
            super(syncId, inv, player, 2);
            MinecraftServer s = player.getServer();
            NbtCompound t = Politics.G(s).getCompound("terr");
            int n = t.getList("c", NbtElement.LONG_TYPE).size();
            boolean here = Politics.inTerritory(s, player.getServerWorld(), player.getBlockPos());
            button(0, icon(Items.GRASS_BLOCK, here ? "이 청크를 영토에서 빼기" : "이 청크를 영토로", Formatting.GREEN, "영토 " + n + " / " + Politics.MAX_CHUNKS + "청크",
                    "영토 안: 허가한 사람만 블록·상자, 위치 대신 '관저' 표시"));
            button(1, icon(t.getBoolean("pvp") ? Items.IRON_SWORD : Items.SHIELD, "영토 PvP: " + (t.getBoolean("pvp") ? "켬" : "끔"), Formatting.WHITE, "▶ 클릭해서 바꾸기"));
            button(2, icon(Items.NAME_TAG, "영토 이름: " + (t.contains("name") ? t.getString("name") : "대통령 관저"), Formatting.WHITE,
                    "/president name <이름>", "/president allow <플레이어> · /president disallow <플레이어>"));
            button(4, icon(Items.ELYTRA, "특별 수송", Formatting.AQUA, "/president tp <x> <z>", "/president biome <바이옴 id>  (예: cherry_grove)", "재사용 10분 (계엄 중 1분) · 3초 시전 · 도착 지점 공지"));
            String ord = Politics.activeOrder(s);
            for (int i = 0; i < 4; i++) {
                String k = Politics.ORDERS[i];
                button(9 + i, icon(new ItemStack(i == 0 ? PokemonDoctor.item("cobblemon:ultra_ball", Items.SNOWBALL) : i == 1 ? Items.EXPERIENCE_BOTTLE : i == 2 ? Items.SUNFLOWER : Items.NETHER_STAR),
                        Text.literal("행정 명령: " + Politics.orderName(k)), k.equals(ord) ? Formatting.GREEN : Formatting.GOLD, "서버 전체 30분 · 하루 1번", "▶ 클릭"));
            }
            button(14, icon(Items.TARGET, "수배령", Formatting.RED, "/president wanted <플레이어> — 24시간 위치 공개 (하루 1명)", "/president pardon <플레이어> — 특별 사면 (주 1회)"));
            int refusals = Politics.G(s).getList("pres.refuse", NbtElement.LONG_TYPE).size();
            button(16, icon(Items.WRITABLE_BOOK, "기록", Formatting.WHITE, "연속 방어 " + Politics.G(s).getInt("pres.defenses") + "회",
                    "7일 안 도전 회피 " + refusals + "회 (3회면 탄핵)", "비상계엄: 7번 키 (7일 1회)"));
            button(17, icon(Items.ARROW, "◀ 돌아가기", Formatting.WHITE));
        }

        @Override
        protected boolean onPick(int slot) {
            MinecraftServer s = player.getServer();
            switch (slot) {
                case 0 -> err(player, Politics.claim(player, !Politics.inTerritory(s, player.getServerWorld(), player.getBlockPos())));
                case 1 -> {
                    NbtCompound t = Politics.G(s).getCompound("terr");
                    t.putBoolean("pvp", !t.getBoolean("pvp"));
                    Politics.G(s).put("terr", t);
                    Politics.dirty(s);
                }
                case 9, 10, 11, 12 -> err(player, Politics.order(player, Politics.ORDERS[slot - 9]));
                case 17 -> JobMenu.later(player, () -> Status.open(player));
                default -> { return false; }
            }
            if (slot <= 1) JobMenu.later(player, () -> open(player));
            return true;
        }
    }
}
