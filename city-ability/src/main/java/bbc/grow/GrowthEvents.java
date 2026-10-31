package bbc.grow;

import bbc.core.PlayerData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 성장 경험치가 쌓이는 행동들과 카드·종족의 상시 효과.
 *  힘: 근접 타격(Combat), 나무·광석 캐기 / 내구: 피해 받기, 가드로 막기(Combat) / 민첩: 달리기, 대시·회피(Combat)
 *  지능: 책 우클릭(독서, 10초마다) / 무기 숙련: 그 무기로 타격(Combat) / 직업 숙련: 직업 기술(JobSkills)
 *  존재치: 몬스터 처치 (최대 체력의 절반, 플레이어 20), 새 바이옴 발견 15
 */
public final class GrowthEvents {
    private GrowthEvents() {}

    private static final Map<UUID, Vec3d> LAST_POS = new HashMap<>();
    private static final Map<UUID, Double> SPRINT = new HashMap<>();
    private static final Map<UUID, Integer> STILL = new HashMap<>();
    private static final Map<UUID, Long> READ_AT = new HashMap<>();
    private static final Map<UUID, Long> ONCE_MORE_AT = new HashMap<>();
    private static long clock;

    public static void register() {
        ServerPlayerEvents.JOIN.register(p -> { Growth.ensureRace(p); Growth.apply(p); });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> Growth.apply(newP));

        // 처치 → 존재치, 사냥꾼의 허기
        ServerLivingEntityEvents.AFTER_DEATH.register((dead, src) -> {
            if (!(src.getAttacker() instanceof ServerPlayerEntity p) || dead == p) return;
            double ep;
            if (dead instanceof ServerPlayerEntity) ep = 20;
            else if (dead instanceof Monster) ep = dead.getMaxHealth() * 0.5;
            else ep = dead.getMaxHealth() * 0.15;
            if (dead.getCommandTags().contains("bbc_ai_boss")) ep *= 6;
            if (dead.getCommandTags().contains("bbc_dummy")) ep = 0;
            Growth.gainEp(p, ep, "처치");
            if (Growth.has(Growth.e(p), Talent.HUNTER)) p.heal(p.getMaxHealth() * 0.05f);
        });

        // 피해 받기 → 내구
        ServerLivingEntityEvents.AFTER_DAMAGE.register((ent, src, base, taken, blocked) -> {
            if (ent instanceof ServerPlayerEntity p && taken > 0) Growth.gainExp(p, Stat.END, Math.min(6, taken));
        });

        // 한 번 더: 체력이 0 이 될 때 1 로 버팀 (10분에 1번)
        ServerLivingEntityEvents.ALLOW_DEATH.register((ent, src, amount) -> {
            if (!(ent instanceof ServerPlayerEntity p) || !Growth.has(Growth.e(p), Talent.ONCE_MORE)) return true;
            long now = p.getServer().getTicks();
            if (now - ONCE_MORE_AT.getOrDefault(p.getUuid(), -100000L) < 20 * 600) return true;
            ONCE_MORE_AT.put(p.getUuid(), now);
            p.setHealth(1f);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 20, 4, false, false));
            p.sendMessage(Text.literal("한 번 더! (10분 뒤 다시)").formatted(Formatting.GOLD), true);
            return false;
        });

        // 나무·광석 → 힘
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, be) -> {
            if (!(player instanceof ServerPlayerEntity p)) return;
            if (isLogOrOre(state)) Growth.gainExp(p, Stat.STR, Growth.has(Growth.e(p), Talent.LUMBERJACK) ? 4 : 2);
        });

        // 수련 도구 → 확정 / 책 → 지능
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack st = player.getStackInHand(hand);
            if (world.isClient || !(player instanceof ServerPlayerEntity p)) return TypedActionResult.pass(st);
            Stat tool = toolOf(st);
            if (tool != null) {
                if (Growth.confirm(p, tool)) st.decrement(1);
                return TypedActionResult.success(st);
            }
            if (st.isOf(Items.BOOK) && hand == Hand.MAIN_HAND) {
                long now = p.getServer().getTicks();
                if (now - READ_AT.getOrDefault(p.getUuid(), -1000L) >= 200) {
                    READ_AT.put(p.getUuid(), now);
                    Growth.gainExp(p, Stat.INT, Growth.has(Growth.e(p), Talent.SELF_TAUGHT) ? 4.5 : 3);
                    p.sendMessage(Text.literal("책을 읽었다 (지능 경험치)").formatted(Formatting.GRAY), true);
                }
            }
            return TypedActionResult.pass(st);
        });

        ServerTickEvents.END_SERVER_TICK.register(GrowthEvents::tick);
    }

    private static boolean isLogOrOre(BlockState s) {
        return s.isIn(BlockTags.LOGS) || s.isIn(BlockTags.COAL_ORES) || s.isIn(BlockTags.IRON_ORES) || s.isIn(BlockTags.COPPER_ORES)
                || s.isIn(BlockTags.GOLD_ORES) || s.isIn(BlockTags.REDSTONE_ORES) || s.isIn(BlockTags.LAPIS_ORES)
                || s.isIn(BlockTags.DIAMOND_ORES) || s.isIn(BlockTags.EMERALD_ORES);
    }

    private static void tick(MinecraftServer server) {
        if (++clock % 20 != 0) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            UUID id = p.getUuid();
            PlayerData.Entry e = Growth.e(p);
            Vec3d pos = p.getPos(), last = LAST_POS.put(id, pos);
            double moved = last == null ? 0 : Math.hypot(pos.x - last.x, pos.z - last.z);
            // 달리기 → 민첩 (8칸마다 1)
            if (p.isSprinting() && moved < 20) {
                double d = SPRINT.getOrDefault(id, 0.0) + moved;
                while (d >= 8) { d -= 8; Growth.gainExp(p, Stat.AGI, 1); }
                SPRINT.put(id, d);
            }
            // 숨 고르기: 3초 가만히 → 초당 0.5 회복
            int still = moved < 0.05 ? STILL.getOrDefault(id, 0) + 1 : 0;
            STILL.put(id, still);
            if (still >= 3 && Growth.has(e, Talent.BREATH) && p.getHealth() < p.getMaxHealth()) p.heal(0.5f);
            // 새 바이옴 → 존재치
            var biome = p.getServerWorld().getBiome(p.getBlockPos()).getKey();
            if (biome.isPresent()) {
                String b = biome.get().getValue().getPath();
                String seen = e.str("g.bio");
                if (!("," + seen + ",").contains("," + b + ",")) {
                    e.str("g.bio", seen.isEmpty() ? b : seen + "," + b);
                    Growth.gainEp(p, 15, "새 장소: " + b);
                }
            }
            // 수인 밤눈
            if (Growth.race(e) == Race.BEAST && p.getServerWorld().isNight())
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 260, 0, true, false));
        }
    }

    // ═════════ 수련 도구 ═════════

    public static Stat toolOf(ItemStack st) {
        NbtComponent c = st.get(DataComponentTypes.CUSTOM_DATA);
        if (c == null) return null;
        NbtCompound n = c.copyNbt();
        return n.contains("bbc_tool") ? Stat.byId(n.getString("bbc_tool")) : null;
    }

    /** 수련 도구 아이템 (레시피 결과와 같은 모양) */
    public static ItemStack tool(Stat s, int count) {
        ItemStack st = new ItemStack(s.icon == Items.WRITABLE_BOOK ? Items.WRITABLE_BOOK : baseOf(s), count);
        NbtCompound n = new NbtCompound();
        n.putString("bbc_tool", s.id);
        st.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(n));
        st.set(DataComponentTypes.CUSTOM_NAME, Text.literal(s.tool).formatted(Formatting.YELLOW).styled(x -> x.withItalic(false)));
        st.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("우클릭: " + s.name + " 확정 대기 1점을 확정").formatted(Formatting.GRAY).styled(x -> x.withItalic(false)))));
        st.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return st;
    }

    public static net.minecraft.item.Item baseOf(Stat s) {
        return switch (s) {
            case STR -> Items.IRON_INGOT;
            case END -> Items.COBBLESTONE;
            case AGI -> Items.LEATHER;
            case INT -> Items.BOOK;
            case LIGHT, MEDIUM, HEAVY -> Items.BOOK;
            case CLASS -> Items.WRITABLE_BOOK;
        };
    }

    static boolean isPlayer(LivingEntity e) { return e instanceof ServerPlayerEntity; }
}
