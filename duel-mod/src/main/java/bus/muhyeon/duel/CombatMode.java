package bus.muhyeon.duel;

import com.cobblemon.mod.common.battles.BattleRegistry;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 직업 전투 모드 (지금은 연금술사).
 *  - 켜면: 1~7 키가 연성 기술로 바뀜(다른 단축키보다 우선), 현자 피카츄 테마곡 재생,
 *          등급 2부터 파티의 연금술사/현자 피카츄가 나와서 같이 싸움
 *  - 끄면 / 죽으면 / 포켓몬 배틀이 시작되면 / 나가면: 자동 해제
 */
public final class CombatMode {
    private CombatMode() {}

    public static final String THEME = "cobblemon:battle.alchemy.hoen";
    public static final int COMPANION_RANK = 2;

    private static final Set<UUID> ACTIVE = new HashSet<>();

    public static boolean active(ServerPlayerEntity p) { return ACTIVE.contains(p.getUuid()); }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(CombatStatePayload.ID, CombatStatePayload.CODEC);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            if (ACTIVE.remove(p.getUuid())) { PikachuCompanion.stop(p); Stamina.hideBar(p); }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> {
            if (ACTIVE.contains(newP.getUuid())) exit(newP, "쓰러져서 전투 모드가 풀렸습니다");
        });

        ServerTickEvents.END_SERVER_TICK.register(CombatMode::tick);
    }

    private static int clock = 0;

    private static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        clock++;
        List<UUID> ids = new ArrayList<>(ACTIVE);
        for (UUID id : ids) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p == null) { ACTIVE.remove(id); continue; }
            if (!p.isAlive()) continue;
            if (!Jobs.hasCombat(Jobs.entry(p))) { exit(p, "전투 기술이 있는 직업이 아니라 전투 모드가 풀렸습니다"); continue; }
            if (BLOCKERS.stream().anyMatch(b -> b.test(p))) { exit(p, "전투 모드가 봉인되었습니다"); continue; }
            if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) { exit(p, "포켓몬 배틀이 시작되어 전투 모드가 풀렸습니다"); continue; }
            if (clock % 5 == 0 && Jobs.isAlchemist(Jobs.entry(p))) PikachuCompanion.tick(p);
            if (clock % 20 == 0) hud(p);
        }
    }

    public static void toggle(ServerPlayerEntity p) {
        if (active(p)) { exit(p, null); return; }
        AlchemyData.Entry e = Jobs.entry(p);
        if (!Jobs.hasCombat(e)) {
            p.sendMessage(Text.literal(Jobs.hasJob(e) ? "이 직업은 전투 기술이 없습니다" : "전투 모드는 직업이 있어야 쓸 수 있습니다 (/job)").formatted(Formatting.RED), true);
            return;
        }
        if (BLOCKERS.stream().anyMatch(b -> b.test(p))) {
            p.sendMessage(Text.literal("지금은 전투 모드를 켤 수 없습니다").formatted(Formatting.RED), true);
            return;
        }
        boolean alch = Jobs.isAlchemist(e);
        if (alch && !e.truth && !AlchemyGloveItem.wearing(p)) {
            p.sendMessage(Text.literal("연성 장갑을 손에 껴야 싸울 수 있습니다 (진리를 보면 맨손으로 가능)").formatted(Formatting.RED), true);
            return;
        }
        if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) {
            p.sendMessage(Text.literal("포켓몬 배틀 중에는 전투 모드를 켤 수 없습니다").formatted(Formatting.RED), true);
            return;
        }
        ACTIVE.add(p.getUuid());
        String theme = alch ? THEME : JobSkills.of(e).theme();
        ServerPlayNetworking.send(p, new CombatStatePayload(true, theme));
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE.value(), SoundCategory.PLAYERS, 1.0f, 0.8f);
        if (alch) Alchemy.fx(p.getServerWorld(), p.getBlockPos(), e.truth);
        p.sendMessage(Text.literal(alch ? "⚔ 전투 모드 — 1~7 키로 연성 기술 (다시 누르면 해제)"
                : "⚔ 전투 모드 — 숫자 키로 " + Jobs.type(e).name() + " 기술 (다시 누르면 해제)").formatted(Formatting.GOLD, Formatting.BOLD), false);
        Stamina.showBar(p);
        if (alch && Jobs.rank(e) >= COMPANION_RANK) PikachuCompanion.start(p);
    }

    public static void exit(ServerPlayerEntity p, String reason) {
        if (!ACTIVE.remove(p.getUuid())) return;
        PikachuCompanion.stop(p);
        Stamina.hideBar(p);
        if (!p.isDisconnected()) {
            ServerPlayNetworking.send(p, new CombatStatePayload(false, ""));
            p.sendMessage(Text.literal(reason != null ? reason : "전투 모드 해제").formatted(Formatting.GRAY), true);
        }
    }

    /** 액션바: 기술 재사용 상태 */
    /** 전투 모드를 막는 조건 (비상계엄 등). true 면 해제 + 켜기 불가 */
    public static final java.util.List<java.util.function.Predicate<ServerPlayerEntity>> BLOCKERS = new java.util.ArrayList<>();

    private static void hud(ServerPlayerEntity p) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (!Jobs.isAlchemist(e)) {
            JobSkills.Set set = JobSkills.of(e);
            MutableText t = JobSkills.hud(p, e);
            if (set != null && !"스태미나".equals(set.costName())) t.append(Text.literal("| " + set.costName() + " " + (int) set.resource(p, e) + "  ").formatted(Formatting.AQUA));
            t.append(Text.literal("| ⚡" + (int) Stamina.get(p)).formatted(Formatting.YELLOW));
            p.sendMessage(t, true);
            return;
        }
        int rank = Jobs.rank(e);
        MutableText t = Text.literal("⚔ ").formatted(Formatting.GOLD);
        for (int i = 0; i < AlchemySkills.SKILLS.length; i++) {
            AlchemySkills.Skill s = AlchemySkills.SKILLS[i];
            if (s.rank() > rank) continue;
            int left = AlchemySkills.cooldownLeft(p.getUuid(), i);
            boolean poor = Stamina.get(p) + 1e-6 < s.cost();
            Formatting c = left > 0 ? Formatting.DARK_GRAY : poor ? Formatting.RED : Formatting.GREEN;
            t.append(Text.literal((i + 1) + s.shortName() + (left > 0 ? " " + left + "s" : "") + "  ").formatted(c));
        }
        if (AutomailSkill.available(e)) {
            int left = AutomailSkill.cooldownLeft(p.getUuid());
            Formatting c = left > 0 ? Formatting.DARK_GRAY : e.energy + 1e-6 < AutomailSkill.cost(e) ? Formatting.RED : Formatting.AQUA;
            t.append(Text.literal("8오토" + (left > 0 ? " " + left + "s" : "") + "  ").formatted(c));
        }
        t.append(Text.literal("| ⚡" + (int) Stamina.get(p)).formatted(Formatting.YELLOW));
        p.sendMessage(t, true);
    }
}
