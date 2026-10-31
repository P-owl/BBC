package bbc.job.alchemy;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 현자의 돌 ① 리스크 없는 결투 (새 전투로 하는 1대1).
 *  - 3분. 둘 중 하나의 체력이 다하면 쓰러지는 대신 체력 1로 버티고 결투가 끝남 (진짜 죽지 않음)
 *  - 돌을 쓴 사람이 이기면 상대가 손에 든 아이템을 빼앗음 / 지면 잃는 것 없음
 *  - 시간이 다 되거나 한 명이 나가면 무승부 (나간 쪽이 상대라면 돌을 쓴 사람 승)
 */
public final class StoneDuel {
    private StoneDuel() {}

    private record Duel(UUID caster, UUID target, long until) {}
    private static final Map<UUID, Duel> BY_PLAYER = new HashMap<>();
    private static final long LENGTH = 3 * 60 * 1000;

    public static boolean in(ServerPlayerEntity p) { return BY_PLAYER.containsKey(p.getUuid()); }

    /** 결투 시작. 못 하면 이유 */
    public static String start(ServerPlayerEntity caster, ServerPlayerEntity target) {
        if (in(caster)) return "이미 결투 중입니다";
        if (in(target)) return target.getName().getString() + "님은 이미 결투 중입니다";
        if (caster.getServer() != null && !caster.getServer().isPvpEnabled()) return "서버 PvP 가 꺼져 있습니다";
        Duel d = new Duel(caster.getUuid(), target.getUuid(), System.currentTimeMillis() + LENGTH);
        BY_PLAYER.put(caster.getUuid(), d);
        BY_PLAYER.put(target.getUuid(), d);
        caster.getServerWorld().playSound(null, target.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 0.6f);
        target.sendMessage(Text.literal("✦ " + caster.getName().getString() + "님이 현자의 돌로 결투를 걸었습니다! (3분) 지면 손에 든 아이템을 빼앗깁니다.")
                .formatted(Formatting.DARK_PURPLE, Formatting.BOLD), false);
        caster.sendMessage(Text.literal("✦ 리스크 없는 결투 시작 (3분). 져도 잃는 것이 없습니다.").formatted(Formatting.LIGHT_PURPLE), false);
        return null;
    }

    private static void end(net.minecraft.server.MinecraftServer s, Duel d, UUID winner) {
        BY_PLAYER.remove(d.caster);
        BY_PLAYER.remove(d.target);
        ServerPlayerEntity c = s.getPlayerManager().getPlayer(d.caster), t = s.getPlayerManager().getPlayer(d.target);
        if (winner == null) {
            for (ServerPlayerEntity p : new ServerPlayerEntity[]{c, t}) if (p != null) p.sendMessage(Text.literal("결투가 무승부로 끝났습니다.").formatted(Formatting.GRAY), false);
            return;
        }
        ServerPlayerEntity w = winner.equals(d.caster) ? c : t, l = winner.equals(d.caster) ? t : c;
        String wn = w == null ? "?" : w.getName().getString(), ln = l == null ? "?" : l.getName().getString();
        s.getPlayerManager().broadcast(Text.literal("⚔ 현자의 돌 결투: " + wn + " 승리 (vs " + ln + ")").formatted(Formatting.GOLD), false);
        if (winner.equals(d.caster) && w != null && l != null) {   // 돌을 쓴 쪽이 이김 → 상대가 든 것 빼앗기
            ItemStack held = l.getMainHandStack();
            if (!held.isEmpty()) {
                ItemStack took = held.copy();
                l.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
                w.getInventory().offerOrDrop(took);
                w.sendMessage(Text.literal("✦ 전리품: " + took.getName().getString()).formatted(Formatting.LIGHT_PURPLE), false);
                l.sendMessage(Text.literal("손에 든 " + took.getName().getString() + "을(를) 빼앗겼습니다.").formatted(Formatting.RED), false);
            }
        } else if (w != null) w.sendMessage(Text.literal("✦ 결투에서 이겼습니다. (돌을 쓴 쪽이 져서 빼앗는 것은 없음)").formatted(Formatting.GRAY), false);
    }

    public static void register() {
        // 쓰러지는 대신 결투 끝
        ServerLivingEntityEvents.ALLOW_DEATH.register((ent, src, amount) -> {
            if (!(ent instanceof ServerPlayerEntity p)) return true;
            Duel d = BY_PLAYER.get(p.getUuid());
            if (d == null) return true;
            p.setHealth(1f);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 60, 4, false, false));
            UUID other = p.getUuid().equals(d.caster) ? d.target : d.caster;
            end(p.getServer(), d, other);
            return false;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (s.getTicks() % 20 != 0 || BY_PLAYER.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Duel d : new java.util.HashSet<>(BY_PLAYER.values())) if (now > d.until) end(s, d, null);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            Duel d = BY_PLAYER.get(h.getPlayer().getUuid());
            if (d == null) return;
            end(s, d, h.getPlayer().getUuid().equals(d.target) ? d.caster : null);
        });
    }
}
