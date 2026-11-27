package bbc.job;

import bbc.combat.Awaken;
import bbc.combat.Combat;
import bbc.combat.CombatMode;
import bbc.combat.MobAI;
import bbc.core.ButtonMenu;
import bbc.core.PlayerData;
import bbc.job.alchemy.Alch;
import bbc.job.alchemy.AlchemistJob;
import bbc.job.alchemy.AlchemyData;
import bbc.job.alchemy.AlchemyItems;
import bbc.job.alchemy.AutomailSkill;
import bbc.job.vampire.VampireJob;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * (임시 · 테스트용) 캐릭터 선택창. /bbc pick 또는 F7.
 * 줄마다 직업 하나 — 칸을 누르면 그 직업·등급·루트로 바로 바뀌고 전투 모드가 켜진다.
 * 맨 아랫줄은 테스트 도구 (각성 게이지·쿨타임·회복·허수아비·상대).
 * 관리자(권한 2) 또는 싱글플레이 주인만 열 수 있다.
 */
public class PickMenu extends ButtonMenu {

    /** 캐릭터 하나 = 칸 · 아이콘 · 이름 · 설명 · 적용 */
    private record Pick(int slot, Item icon, String name, Formatting color, String desc, String key, java.util.function.Consumer<ServerPlayerEntity> apply) {}

    private static final Pick[] PICKS = {
            // ── 수련생 ──
            new Pick(1, Items.WOODEN_SWORD, "수련생", Formatting.WHITE, "기본 맨손 격투", "tr1", p -> trainee(p, 1)),
            new Pick(2, Items.IRON_SWORD, "숙련 수련생", Formatting.WHITE, "수련생 2등급", "tr2", p -> trainee(p, 2)),
            // ── 연금술사 ──
            new Pick(10, Items.BOOK, "견습 연금술사", Formatting.LIGHT_PURPLE, "연금술사 1등급", "al1", p -> alchemist(p, 1, "")),
            new Pick(11, Items.WRITABLE_BOOK, "연금술사", Formatting.LIGHT_PURPLE, "연금술사 2등급", "al2", p -> alchemist(p, 2, "")),
            new Pick(12, Items.ENCHANTED_BOOK, "숙련 연금술사", Formatting.LIGHT_PURPLE, "연금술사 3등급", "al3", p -> alchemist(p, 3, "")),
            new Pick(13, AlchemyItems.ALCHEMY_GLOVE, "대연금술사", Formatting.LIGHT_PURPLE, "연금술사 4등급", "al4", p -> alchemist(p, 4, "")),
            new Pick(14, AlchemyItems.AUTOMAIL_ARM, "진리를 본 자 · 의수", Formatting.GOLD, "팔을 바치고 오토메일 의수", "al5arm", p -> alchemist(p, 5, "arm")),
            new Pick(15, AlchemyItems.AUTOMAIL_LEG, "진리를 본 자 · 의족", Formatting.GOLD, "다리를 바치고 오토메일 의족", "al5leg", p -> alchemist(p, 5, "leg")),
            new Pick(16, AlchemyItems.AUTOMAIL_HEART, "진리를 본 자 · 강철 심장", Formatting.GOLD, "심장을 바치고 강철 심장", "al5heart", p -> alchemist(p, 5, "heart")),
            // ── 뱀파이어 ──
            new Pick(19, Items.REDSTONE, "일반 뱀파이어", Formatting.RED, "뱀파이어 1단계", "vm1", p -> vampire(p, 1, "")),
            new Pick(20, VampireJob.STONE_MASK, "숙련된 뱀파이어", Formatting.RED, "뱀파이어 2단계", "vm2", p -> vampire(p, 2, "")),
            new Pick(21, Items.BLAZE_POWDER, "기둥 속 사내 · 열", Formatting.GOLD, "비등점 (에시디시)", "vm3heat", p -> vampire(p, 3, "heat")),
            new Pick(22, Items.WIND_CHARGE, "기둥 속 사내 · 바람", Formatting.AQUA, "바람 (와무우)", "vm3wind", p -> vampire(p, 3, "wind")),
            new Pick(23, Items.END_ROD, "기둥 속 사내 · 빛", Formatting.WHITE, "빛 칼날 (카즈)", "vm3light", p -> vampire(p, 3, "light")),
    };

    private static final int ROW_TOOLS = 27;
    private static final int T_NONE = 27, T_GAUGE = 29, T_COOL = 30, T_HEAL = 31, T_DUMMY = 33, T_GRUNT = 34, T_SKILLED = 35;

    public static boolean allowed(ServerPlayerEntity p) {
        return p.hasPermissionLevel(2) || (p.getServer() != null && p.getServer().isHost(p.getGameProfile()));
    }

    public static void open(ServerPlayerEntity p) {
        if (!allowed(p)) { JobSkills.bar(p, "캐릭터 선택창은 관리자만 열 수 있습니다", Formatting.GRAY); return; }
        p.openHandledScreen(new SimpleNamedScreenHandlerFactory((id, inv, x) -> new PickMenu(id, inv, p), Text.literal("캐릭터 선택 (테스트용)")));
    }

    private PickMenu(int syncId, PlayerInventory inv, ServerPlayerEntity player) {
        super(syncId, inv, player, 4);
        draw();
    }

    private void draw() {
        String cur = current(player);
        button(0, icon(Items.WOODEN_SWORD, "수련생", Formatting.WHITE, "→"));
        button(9, icon(AlchemyItems.PHILOSOPHERS_STONE, "연금술사", Formatting.LIGHT_PURPLE, "→"));
        button(18, icon(Items.WITHER_ROSE, "뱀파이어", Formatting.RED, "→"));
        for (Pick k : PICKS) {
            boolean on = k.key().equals(cur);
            ItemStack s = icon(k.icon(), (on ? "● " : "") + k.name(), on ? Formatting.GREEN : k.color(), k.desc(), "", on ? "지금 이 캐릭터" : "▶ 클릭해서 바꾸기 (전투 모드 켜짐)");
            if (on) s.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
            button(k.slot(), s);
        }
        button(T_NONE, icon(Items.BARRIER, "무직으로", Formatting.GRAY, "직업을 비움"));
        button(T_GAUGE, icon(Items.NETHER_STAR, "각성 게이지 가득", Formatting.YELLOW, "G 로 각성 → 궁극기 테스트"));
        button(T_COOL, icon(Items.CLOCK, "쿨타임 초기화", Formatting.AQUA, "모든 기술 재사용 대기 지움"));
        button(T_HEAL, icon(Items.GOLDEN_APPLE, "회복", Formatting.GREEN, "체력·배고픔 가득 (뱀파이어는 피도)"));
        button(T_DUMMY, icon(Items.ARMOR_STAND, "허수아비", Formatting.WHITE, "맞기만 하는 표적"));
        button(T_GRUNT, icon(Items.ZOMBIE_HEAD, "상대 · 졸개", Formatting.WHITE, "싸우는 상대 (약함)"));
        button(T_SKILLED, icon(Items.SKELETON_SKULL, "상대 · 숙련", Formatting.WHITE, "싸우는 상대 (보통)"));
        refresh();
    }

    @Override
    protected boolean onPick(int slot) {
        for (Pick k : PICKS) {
            if (k.slot() != slot) continue;
            CombatMode.set(player, false);
            k.apply().accept(player);
            Jobs.dirty(player);
            JobSkills.resetCooldown(player.getUuid());
            CombatMode.set(player, true);
            player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.6f, 1.4f);
            JobSkills.bar(player, "✦ " + k.name(), Formatting.GREEN);
            return true;
        }
        switch (slot) {
            case T_NONE -> { CombatMode.set(player, false); resetAlchemy(player); Jobs.quit(player); }
            case T_GAUGE -> Awaken.add(player, Awaken.MAX);
            case T_COOL -> JobSkills.resetCooldown(player.getUuid());
            case T_HEAL -> {
                player.setHealth(player.getMaxHealth());
                player.getHungerManager().setFoodLevel(20);
                player.getHungerManager().setSaturationLevel(20);
                PlayerData.Entry e = Jobs.entry(player);
                if (VampireJob.is(e)) { VampireJob.addBlood(e, 100); Jobs.dirty(player); }
            }
            case T_DUMMY -> Combat.spawnDummy(player);
            case T_GRUNT -> MobAI.spawnOpponent(player, MobAI.Tier.GRUNT);
            case T_SKILLED -> MobAI.spawnOpponent(player, MobAI.Tier.SKILLED);
            default -> { return false; }
        }
        player.getServerWorld().playSound(null, player.getBlockPos(), SoundEvents.UI_BUTTON_CLICK.value(), SoundCategory.PLAYERS, 0.5f, 1.2f);
        draw();
        return slot == T_NONE;
    }

    // ───────── 적용 ─────────

    private static void trainee(ServerPlayerEntity p, int rank) {
        resetAlchemy(p);
        Jobs.set(p, TraineeJob.INSTANCE);
        Jobs.entry(p).num(TraineeJob.INSTANCE.prefix() + "rank", rank);
    }

    /** rank 1~4 = 숙련도로 등급, 5 = 진리 (toll 부위를 바치고 같은 부위 오토메일) */
    private static void alchemist(ServerPlayerEntity p, int rank, String toll) {
        if (!AlchemistJob.INSTANCE.id().equals(Jobs.entry(p).job)) Jobs.set(p, AlchemistJob.INSTANCE);
        AlchemyData.Entry a = Alch.entry(p);
        a.truth = rank >= 5;
        a.toll = rank >= 5 ? toll : "";
        a.automail = a.toll;
        a.xp = Alch.RANK_XP[Math.min(4, rank)];
        AlchemyData.get(p.getServer()).markDirty();
        Alch.syncRank(a);
        Alch.applyToll(p);
        AutomailSkill.broadcast(p);
        p.setHealth(p.getMaxHealth());
    }

    /** stage 1~3, route = heat / wind / light (3단계만) */
    private static void vampire(ServerPlayerEntity p, int stage, String route) {
        resetAlchemy(p);
        PlayerData.Entry e = Jobs.entry(p);
        if (!VampireJob.is(e)) { Jobs.set(p, VampireJob.INSTANCE); e = Jobs.entry(p); }
        e.num(VampireJob.P + "rank", stage);
        e.str(VampireJob.P + "route", stage >= 3 ? route : "");
        VampireJob.addBlood(e, 100);
    }

    /** 연금술사가 아닌 캐릭터로 갈 때: 진리의 대가(잃은 부위)·오토메일이 남지 않게 */
    private static void resetAlchemy(ServerPlayerEntity p) {
        AlchemyData.Entry a = Alch.entry(p);
        if (!a.truth && a.toll.isEmpty() && a.automail.isEmpty()) return;
        a.truth = false;
        a.toll = "";
        a.automail = "";
        AlchemyData.get(p.getServer()).markDirty();
        Alch.applyToll(p);
        AutomailSkill.broadcast(p);
    }

    /** 지금 캐릭터의 키 (선택창에 표시) */
    private static String current(ServerPlayerEntity p) {
        PlayerData.Entry e = Jobs.entry(p);
        JobType t = Jobs.type(e);
        if (t == null) return "";
        int r = Jobs.rank(e);
        if (t == TraineeJob.INSTANCE) return "tr" + r;
        if (t == AlchemistJob.INSTANCE) { AlchemyData.Entry a = Alch.entry(p); return a.truth ? "al5" + a.toll : "al" + r; }
        if (VampireJob.is(e)) return r >= 3 ? "vm3" + e.str(VampireJob.P + "route") : "vm" + r;
        return "";
    }
}
