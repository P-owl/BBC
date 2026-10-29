package bbc.combat;

import bbc.job.JobSkills;
import bbc.job.Jobs;
import bbc.job.Stamina;
import bbc.net.CombatStatePayload;
import bbc.net.KeyPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 전투 모드. 켜져 있는 동안 Z~B 키가 기술이 되고, 스태미나 보스바와 기술 액션바가 보인다.
 * 경직·가드·대시·평타 콤보 같은 실제 전투 엔진은 1~2단계에서 이 위에 얹는다.
 */
public final class CombatMode {
    private CombatMode() {}

    private static final Set<UUID> ACTIVE = new HashSet<>();
    private static int clock = 0;

    public static boolean active(ServerPlayerEntity p) { return ACTIVE.contains(p.getUuid()); }

    public static void toggle(ServerPlayerEntity p) { set(p, !active(p)); }

    public static void set(ServerPlayerEntity p, boolean on) {
        if (on && !Jobs.hasCombat(Jobs.entry(p))) {
            JobSkills.bar(p, "전투 기술이 있는 직업이 아닙니다", Formatting.GRAY);
            on = false;
        }
        if (on == active(p)) { ServerPlayNetworking.send(p, new CombatStatePayload(on)); return; }
        if (on) {
            ACTIVE.add(p.getUuid());
            Stamina.showBar(p);
            p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 0.8f, 1.2f);
            JobSkills.bar(p, "⚔ 전투 모드", Formatting.GOLD);
        } else {
            ACTIVE.remove(p.getUuid());
            Stamina.hideBar(p);
            p.sendMessage(Text.empty(), true);
        }
        ServerPlayNetworking.send(p, new CombatStatePayload(on));
    }

    /** 클라이언트 키 입력 처리 (서버 스레드에서 호출) */
    public static void onKey(ServerPlayerEntity p, int action) {
        if (action == KeyPayload.TOGGLE) { toggle(p); return; }
        if (!active(p)) return;
        if (action >= KeyPayload.SKILL_BASE && action < KeyPayload.SKILL_BASE + JobSkills.SLOTS) {
            JobSkills.useSlot(p, action - KeyPayload.SKILL_BASE);
            return;
        }
        // 가드·대시·각성·스위치·임플란트·유물·마안은 1단계 이후에 연결
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CombatMode::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ACTIVE.remove(handler.getPlayer().getUuid()));
    }

    private static void tick(MinecraftServer server) {
        if (++clock % 10 != 0) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!active(p)) continue;
            if (!Jobs.hasCombat(Jobs.entry(p))) { set(p, false); continue; } // 직업을 잃음
            p.sendMessage(JobSkills.hud(p, Jobs.entry(p)), true);
        }
    }
}
