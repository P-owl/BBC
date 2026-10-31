package bbc.job.alchemy;

import bbc.core.Tasks;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * 직업 시스템. 지금은 연금술사 하나.
 *
 * 연금술사 등급 (숙련도 = 연성에 쓴 에너지 누적)
 *   1 견습 연금술사     0          손실 22%  분해 한도 ×1
 *   2 연금술사          100,000    손실 20%  ×1.25
 *   3 숙련 연금술사     400,000    손실 17%  ×1.5
 *   4 대연금술사        1,000,000  손실 15%  ×2     현자의 돌 연성 · 진리의 문 도전
 *   5 진리를 본 자      (진리의 문)  손실 5%   ×3     + 진리 혜택
 */
public final class Alch {
    private Alch() {}

    public static final String ALCHEMIST = "alchemist";
    /** 서버 (직업 데이터 조회용, 서버가 켜질 때 넣음) */
    public static net.minecraft.server.MinecraftServer SERVER;

    /** bbc 직업 데이터 (직업·등급) */
    static bbc.core.PlayerData.Entry job(AlchemyData.Entry e) {
        return SERVER == null || e.id == null ? null : bbc.core.PlayerData.get(SERVER).of(e.id);
    }

    /** 등급이 바뀌었으면 bbc 직업 데이터(al.rank)에 반영 — HUD·기술 잠금이 이 값을 씀 */
    public static void syncRank(AlchemyData.Entry e) {
        bbc.core.PlayerData.Entry j = job(e);
        if (j == null || !ALCHEMIST.equals(j.job)) return;
        j.num(AlchemistJob.PREFIX + "rank", rank(e));
        bbc.core.PlayerData.get(SERVER).markDirty();
    }

    public static void announceRank(ServerPlayerEntity p, String jobName, String rankName, boolean broadcast) {
        p.sendMessage(Text.literal("✦ 등급 상승: " + rankName + "!").formatted(Formatting.GOLD, Formatting.BOLD), false);
        p.getServerWorld().playSound(null, p.getBlockPos(), net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1.2f);
        if (broadcast) p.getServer().getPlayerManager().broadcast(
                Text.literal("✦ " + p.getName().getString() + "님이 " + rankName + "(" + jobName + ")이(가) 되었습니다.").formatted(Formatting.GOLD), false);
    }

    public static final String[] RANK_NAMES = {"무직", "견습 연금술사", "연금술사", "숙련 연금술사", "대연금술사", "진리를 본 자"};
    public static final double[] RANK_XP = {0, 0, 100_000, 400_000, 1_000_000};
    private static final double[] RANK_TAX = {-1, 0.22, 0.20, 0.17, 0.15, 0.05};
    private static final double[] RANK_CAP = {1, 1, 1.25, 1.5, 2, 3};

    // ── 입문 조건 ──
    public static final int JOIN_KNOWN = 50;
    public static final double JOIN_DEPOSITED = 50_000;
    public static final Item[] TRIBUTE_ITEMS = {Items.DIAMOND_BLOCK, Items.GOLD_BLOCK, Items.REDSTONE_BLOCK, Items.AMETHYST_SHARD};
    public static final int[] TRIBUTE_COUNTS = {1, 4, 8, 16};

    // ── 진리의 문 ──
    public static final double GATE_ENERGY = 1_048_576;
    public static final int GATE_LEVELS = 30;

    // ───────── 등급 ─────────

    public static AlchemyData.Entry entry(ServerPlayerEntity p) {
        return AlchemyData.get(p.getServer()).of(p.getUuid());
    }

    public static boolean isAlchemist(AlchemyData.Entry e) { bbc.core.PlayerData.Entry j = job(e); return j != null && ALCHEMIST.equals(j.job); }

    /** 0 = 무직, 1~4 = 연금술사 등급, 5 = 진리를 본 자 */
    public static int rank(AlchemyData.Entry e) {
        if (!isAlchemist(e)) return 0;
        if (e.truth) return 5;
        int r = 1;
        for (int i = 2; i <= 4; i++) if (e.xp >= RANK_XP[i]) r = i;
        return r;
    }

    public static String rankName(AlchemyData.Entry e) { return RANK_NAMES[rank(e)]; }

    /** 연성 손실률 (현자의 돌은 진리 미만일 때만 더 낮춰 줌) */
    public static double tax(AlchemyData.Entry e, boolean stone) {
        int r = rank(e);
        double t = r == 0 ? AlchemyValues.tax : RANK_TAX[r];
        if (r < 5 && stone) t = Math.min(t, AlchemyValues.stoneTax);
        return t;
    }

    public static double dailyCap(AlchemyData.Entry e) {
        if (AlchemyValues.dailyCap <= 0) return 0;
        return AlchemyValues.dailyCap * RANK_CAP[rank(e)];
    }

    /** 연성할 때마다 호출: 숙련도 증가, 등급이 오르면 알림 */
    public static void addXp(ServerPlayerEntity p, AlchemyData.Entry e, double spent) {
        if (!isAlchemist(e) || e.truth) return;
        int before = rank(e);
        e.xp += spent;
        int after = rank(e);
        if (after != before) syncRank(e);
        if (after > before) {
            p.sendMessage(Text.literal("✦ 등급 상승: " + RANK_NAMES[after] + "!").formatted(Formatting.GOLD, Formatting.BOLD), false);
            if (after == 4) {
                p.sendMessage(Text.literal("  현자의 돌 연성과 진리의 문에 도전할 자격을 얻었습니다. (/job)").formatted(Formatting.GOLD), false);
                p.getServer().getPlayerManager().broadcast(Text.literal("✦ " + p.getName().getString() + "님이 대연금술사가 되었습니다.")
                        .formatted(Formatting.GOLD), false);
            }
        }
    }

    // ───────── 공물 ─────────

    public static int count(ServerPlayerEntity p, Item item) {
        int n = 0;
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item) && s.getComponentChanges().isEmpty()) n += s.getCount();
        }
        return n;
    }

    public static void take(ServerPlayerEntity p, Item item, int n) {
        for (int i = 0; i < p.getInventory().size() && n > 0; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isOf(item) || !s.getComponentChanges().isEmpty()) continue;
            int k = Math.min(n, s.getCount());
            s.decrement(k);
            n -= k;
        }
        p.getInventory().markDirty();
    }

    // ───────── 통행료 (영구 능력치 감소) ─────────

    private static final Identifier TOLL_ID = Identifier.of("bbc", "truth_toll");

    public static String tollName(String toll) {
        return switch (toll) {
            case "arm" -> "한쪽 팔 (공격력 -30%, 채굴 속도 -20%)";
            case "leg" -> "한쪽 다리 (이동 속도 -15%)";
            case "heart" -> "심장의 일부 (최대 체력 -6, 하트 3칸)";
            default -> "없음";
        };
    }

    /** 접속·부활 때마다 다시 붙인다 (임시 수정자라서 데이터와 항상 일치) */
    public static void applyToll(ServerPlayerEntity p) {
        AlchemyData.Entry e = entry(p);
        clear(p, EntityAttributes.GENERIC_ATTACK_DAMAGE);
        clear(p, EntityAttributes.PLAYER_BLOCK_BREAK_SPEED);
        clear(p, EntityAttributes.GENERIC_MOVEMENT_SPEED);
        clear(p, EntityAttributes.GENERIC_MAX_HEALTH);
        clear(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE);
        clear(p, EntityAttributes.GENERIC_STEP_HEIGHT);
        clear(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        clear(p, EntityAttributes.GENERIC_ARMOR);
        boolean auto = !e.toll.isEmpty() && e.toll.equals(e.automail);
        var MUL = EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        var ADD = EntityAttributeModifier.Operation.ADD_VALUE;
        switch (e.toll) {
            case "arm" -> {
                if (auto) { // 오토메일 의수: 원래보다 강한 팔
                    add(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, 0.15, MUL);
                    add(p, EntityAttributes.PLAYER_BLOCK_BREAK_SPEED, 0.15, MUL);
                    add(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.2, ADD);
                } else {
                    add(p, EntityAttributes.GENERIC_ATTACK_DAMAGE, -0.30, MUL);
                    add(p, EntityAttributes.PLAYER_BLOCK_BREAK_SPEED, -0.20, MUL);
                }
            }
            case "leg" -> {
                if (auto) { // 오토메일 의족: 빠르고, 한 칸 턱을 그냥 오르고, 낙하 피해가 줄어듦
                    add(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.10, MUL);
                    add(p, EntityAttributes.GENERIC_STEP_HEIGHT, 0.5, ADD);
                    add(p, EntityAttributes.GENERIC_SAFE_FALL_DISTANCE, 4, ADD);
                } else {
                    add(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, -0.15, MUL);
                }
            }
            case "heart" -> {
                if (auto) { // 강철 심장: 체력·방어 증가
                    add(p, EntityAttributes.GENERIC_MAX_HEALTH, 4, ADD);
                    add(p, EntityAttributes.GENERIC_ARMOR, 3, ADD);
                } else {
                    add(p, EntityAttributes.GENERIC_MAX_HEALTH, -6, ADD);
                }
                if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
            }
            default -> { }
        }
        AutomailSkill.broadcast(p); // 다른 사람들 화면에 팔·다리 모양 반영
    }

    public static String automailName(String part) {
        return switch (part) {
            case "arm" -> "오토메일 의수";
            case "leg" -> "오토메일 의족";
            case "heart" -> "강철 심장";
            default -> "없음";
        };
    }

    private static void clear(ServerPlayerEntity p, RegistryEntry<EntityAttribute> attr) {
        EntityAttributeInstance inst = p.getAttributeInstance(attr);
        if (inst != null) inst.removeModifier(TOLL_ID);
    }

    private static void add(ServerPlayerEntity p, RegistryEntry<EntityAttribute> attr, double v, EntityAttributeModifier.Operation op) {
        EntityAttributeInstance inst = p.getAttributeInstance(attr);
        if (inst != null) inst.addTemporaryModifier(new EntityAttributeModifier(TOLL_ID, v, op));
    }

    // ───────── 이벤트 ─────────

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s -> SERVER = s);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            Tasks.later(5, () -> {
                if (p.isDisconnected()) return;
                applyToll(p);
                TruthGate.rescue(p); // 진리의 문 연출 중에 나갔다면 원래 자리로
            });
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> applyToll(newPlayer));
        // 현자의 돌 제물: 대연금술사가 된 뒤 쓰러뜨린 생명 = 영혼 (몹 1 · 주민 5 · 플레이어 25)
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((dead, src) -> {
            if (!(src.getAttacker() instanceof ServerPlayerEntity k) || dead == k || dead.getCommandTags().contains("bbc_dummy")) return;
            AlchemyData.Entry e = entry(k);
            if (rank(e) < 4) return;
            int add = dead instanceof ServerPlayerEntity ? 25 : dead instanceof net.minecraft.entity.passive.MerchantEntity ? 5 : 1;
            e.addNum("souls", add);
            AlchemyData.get(k.getServer()).markDirty();
        });

    }
}
