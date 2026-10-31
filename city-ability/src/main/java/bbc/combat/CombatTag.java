package bbc.combat;

import bbc.core.PlayerData;
import bbc.grow.Growth;
import bbc.job.Jobs;
import bbc.net.CombatTagPayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 전투 중 (in combat, Deepwoken 의 IN DANGER).
 *  - 플레이어끼리, 또는 플레이어와 적대 몹·전투 AI 몹이 서로 때리거나 맞으면 둘 다 15초 동안 '전투 중' (때릴 때마다 갱신)
 *  - 전투 중엔 전투 모드로 켤 수는 있지만 끌 수는 없음 (도망치듯 평범한 마크로 돌아가지 못하게)
 *  - 전투 중에 접속을 끊으면 '도주': 그 자리에서 쓰러지고(일반 죽음 규칙), 이번 파워 존재치 진행의 15% 를 잃고,
 *    다시 들어오면 10분 동안 '부상'(최대 체력 −15%)
 *  - 허수아비는 전투 중을 걸지 않음
 */
public final class CombatTag {
    private CombatTag() {}

    public static final int DURATION = 15 * 20;
    private static final Map<UUID, Long> UNTIL = new HashMap<>();
    private static final Map<UUID, Integer> SENT = new HashMap<>();
    private static long now;

    public static boolean tagged(ServerPlayerEntity p) { return UNTIL.getOrDefault(p.getUuid(), 0L) > now; }
    public static int left(ServerPlayerEntity p) { return (int) Math.max(0, UNTIL.getOrDefault(p.getUuid(), 0L) - now); }

    private static boolean counts(LivingEntity e) {
        if (e.getCommandTags().contains(Combat.DUMMY_TAG)) return false;
        if (e instanceof ServerPlayerEntity sp) return !sp.isCreative() && !sp.isSpectator();
        if (e instanceof Monster) return true;
        for (String t : e.getCommandTags()) if (t.startsWith("bbc_ai_")) return true;
        return false;
    }

    /** a 와 b 가 싸웠다 → 둘 중 플레이어를 전투 중으로 */
    public static void fight(LivingEntity a, LivingEntity b) {
        if (a == null || b == null || a == b || !counts(a) || !counts(b)) return;
        if (a instanceof ServerPlayerEntity pa) mark(pa);
        if (b instanceof ServerPlayerEntity pb) mark(pb);
    }

    private static void mark(ServerPlayerEntity p) {
        boolean was = tagged(p);
        UNTIL.put(p.getUuid(), now + DURATION);
        if (!was) send(p);
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((ent, src, base, taken, blocked) -> {
            if (src.getAttacker() instanceof LivingEntity atk) fight(atk, ent);
        });
        ServerTickEvents.END_SERVER_TICK.register(CombatTag::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            ServerPlayerEntity p = h.getPlayer();
            if (tagged(p)) flee(p);
            UNTIL.remove(p.getUuid());
            SENT.remove(p.getUuid());
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> {
            ServerPlayerEntity p = h.getPlayer();
            PlayerData.Entry e = Jobs.entry(p);
            if (e.flag("g.fled")) {
                e.flag("g.fled", false);
                e.num("g.injury_until", System.currentTimeMillis() + 10 * 60 * 1000L);
                Jobs.dirty(p);
                Growth.apply(p);
                p.sendMessage(Text.literal("⚠ 전투 중에 접속을 끊어 '도주'로 처리됐습니다 — 존재치 일부를 잃고 10분 동안 부상 (최대 체력 −15%)").formatted(Formatting.RED), false);
            }
        });
    }

    /** 도주: 쓰러지고, 이번 파워 진행의 15% 존재치를 잃음. 부상은 다시 접속할 때 */
    private static void flee(ServerPlayerEntity p) {
        PlayerData.Entry e = Jobs.entry(p);
        double floor = Growth.epFor(Growth.power(e));
        double ep = Growth.ep(e);
        if (ep > floor) e.num("g.ep", ep - (ep - floor) * 0.15);
        e.flag("g.fled", true);
        Jobs.dirty(p);
        if (p.isAlive() && !p.isCreative()) p.kill();
    }

    private static void tick(MinecraftServer server) {
        now++;
        if (now % 10 != 0) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            int left = left(p);
            int sent = SENT.getOrDefault(p.getUuid(), -1);
            if (left > 0 || sent > 0) send(p);
        }
    }

    private static void send(ServerPlayerEntity p) {
        int left = left(p);
        SENT.put(p.getUuid(), left);
        if (ServerPlayNetworking.canSend(p, CombatTagPayload.ID)) ServerPlayNetworking.send(p, new CombatTagPayload(left));
    }
}
