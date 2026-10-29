package bus.muhyeon.duel;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import org.joml.Vector3f;

import java.util.Random;
import java.util.UUID;

/**
 * 에이자의 적석 — 서버 역사상 단 한 번만 나타난다.
 * 등장: 보름달 자정 + 뇌우 + 루트 숙련도 100% 기둥 속 사내 접속 + 접속자 4명 이상 → 그 자정에 5%
 *   성공하면 해저 유적 하나에 생김 (좌표 비공개, 128칸 안에서 붉은 빛기둥). 동틀 때까지 못 주우면 사라지고 다음에 다시 굴림
 *   누군가 한 번 주우면 다시는 안 생김. 부서지지 않음 (용암·선인장·폭발 무시, 공허에 빠지면 스폰으로)
 * 들고 있는 사람 위치는 30초마다 지도에 공개 (신부 5등급이 '보관'하면 숨김, 그 신부가 죽으면 떨굼)
 */
public final class Aja {
    private Aja() {}

    static NbtCompound G(MinecraftServer s) { return AlchemyData.get(s).global; }
    static final DustParticleEffect RED = new DustParticleEffect(new Vector3f(1f, 0.05f, 0.1f), 2.0f);

    public static boolean picked(MinecraftServer s) { return G(s).getBoolean("aja.picked"); }

    static boolean isStone(ItemStack st) { return st.isOf(DuelMod.AJA_STONE); }

    static boolean conditions(MinecraftServer s) {
        ServerWorld ow = s.getOverworld();
        if (!ow.isThundering()) return false;
        if ((ow.getTimeOfDay() / 24000L) % 8 != 0) return false; // 보름달
        if (s.getPlayerManager().getPlayerList().size() < 4) return false;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            AlchemyData.Entry e = Jobs.entry(p);
            if (Vampire.stage(e) == 3 && Pillar.mastery(e) >= 100) return true;
        }
        return false;
    }

    private static int clock = 0;

    public static void register() {
        // 바닥에 떨어진 적석은 무적 + 사라지지 않음
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ItemEntity ie && isStone(ie.getStack())) protect(ie);
        });
        // 적석을 보관한 성자가 죽으면 그 자리에 떨굼
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return;
            AlchemyData.Entry e = Jobs.entry(p);
            if (!e.flag("pri.aja")) return;
            e.flag("pri.aja", false);
            AlchemyData.get(p.getServer()).markDirty();
            ItemEntity ie = p.dropItem(new ItemStack(DuelMod.AJA_STONE), true, false);
            if (ie != null) protect(ie);
            p.getServer().getPlayerManager().broadcast(Text.literal("🔴 성자가 쓰러지며 에이자의 적석이 굴러 나왔다 — " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ()).formatted(Formatting.RED, Formatting.BOLD), false);
        });
        // 마지막으로 알려진 위치를 30초마다 갱신해서 지도에 표시
        Markers.PROVIDERS.add((s, out) -> {
            NbtCompound g = G(s);
            if (s.getTicks() % 600 < 20 && g.contains("aja.last")) g.put("aja.shown", g.getCompound("aja.last").copy());
            NbtCompound shown = g.getCompound("aja.shown");
            if (!shown.isEmpty())
                out.add(new MarkerPayload.Marker("aja", "에이자의 적석", "赤", shown.getString("dim"), shown.getInt("x"), shown.getInt("y"), shown.getInt("z"), Markers.RED));
        });
        ServerTickEvents.END_SERVER_TICK.register(Aja::tick);
    }

    static void protect(ItemEntity ie) {
        ie.setInvulnerable(true);
        ie.setNeverDespawn();
        ie.setGlowing(true);
    }

    private static void tick(MinecraftServer s) {
        if (++clock % 20 != 0) return;
        NbtCompound g = G(s);
        ServerWorld ow = s.getOverworld();
        long t = ow.getTimeOfDay() % 24000;

        // 1) 누가 들고 있나 / 바닥에 있나 → 마지막 위치
        boolean found = false;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            if (Jobs.count(p, DuelMod.AJA_STONE) > 0 || isStone(p.getOffHandStack())) {
                found = true;
                if (!g.getBoolean("aja.picked")) {
                    g.putBoolean("aja.picked", true);
                    removeSpawn(s, false);
                    s.getPlayerManager().broadcast(Text.literal("🔴 누군가 에이자의 적석을 손에 넣었다…").formatted(Formatting.RED, Formatting.BOLD), false);
                }
                remember(s, p.getServerWorld(), p.getBlockPos());
            }
        }
        if (!found) {
            for (ServerWorld w : s.getWorlds()) {
                for (Entity e : w.iterateEntities()) {
                    if (!(e instanceof ItemEntity ie) || !isStone(ie.getStack())) continue;
                    if (ie.getY() < w.getBottomY() + 2) {
                        BlockPos sp = s.getOverworld().getSpawnPos();
                        ie.teleport(s.getOverworld(), sp.getX() + 0.5, s.getOverworld().getTopY(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ()) + 1, sp.getZ() + 0.5, java.util.Set.of(), 0, 0);
                    }
                    if (g.getBoolean("aja.picked")) remember(s, w, ie.getBlockPos());
                    // 128칸 안 플레이어에게 붉은 빛기둥
                    for (ServerPlayerEntity p : w.getPlayers()) {
                        if (p.squaredDistanceTo(ie) > 128 * 128) continue;
                        for (int y = 0; y < 60; y += 2) w.spawnParticles(p, RED, true, ie.getX(), ie.getY() + y, ie.getZ(), 1, 0.1, 0.5, 0.1, 0);
                    }
                }
            }
        }

        // 2) 등장 판정: 자정(18000)에 한 번
        if (!g.getBoolean("aja.picked") && !g.contains("aja.spawn") && t >= 18000 && t < 18020) {
            long day = ow.getTimeOfDay() / 24000L;
            if (g.getLong("aja.rolled") != day + 1 && conditions(s)) {
                g.putLong("aja.rolled", day + 1);
                AlchemyData.get(s).markDirty();
                if (new Random().nextInt(100) < 5) spawn(s);
                else s.getPlayerManager().broadcast(Text.literal("…바다 깊은 곳에서 무언가가 꿈틀거렸다가, 다시 잠잠해졌다.").formatted(Formatting.DARK_RED, Formatting.ITALIC), false);
            }
        }
        // 3) 동이 트면 못 주운 적석은 사라짐
        if (g.contains("aja.spawn") && !g.getBoolean("aja.picked") && t >= 23000) removeSpawn(s, true);
    }

    static void remember(MinecraftServer s, ServerWorld w, BlockPos pos) {
        NbtCompound c = new NbtCompound();
        c.putString("dim", w.getRegistryKey().getValue().toString());
        c.putInt("x", pos.getX()); c.putInt("y", pos.getY()); c.putInt("z", pos.getZ());
        G(s).put("aja.last", c);
    }

    static void spawn(MinecraftServer s) {
        ServerWorld w = s.getOverworld();
        var players = s.getPlayerManager().getPlayerList();
        ServerPlayerEntity anchor = players.get(new Random().nextInt(players.size()));
        BlockPos near = anchor.getServerWorld() == w ? anchor.getBlockPos() : w.getSpawnPos();
        BlockPos ruin = w.locateStructure(StructureTags.OCEAN_RUIN, near, 100, false);
        if (ruin == null) { DuelMod.LOG.warn("[aja] 해저 유적을 못 찾아 적석이 나타나지 못함"); return; }
        w.getChunk(ruin.getX() >> 4, ruin.getZ() >> 4);
        int y = w.getTopY(Heightmap.Type.OCEAN_FLOOR, ruin.getX(), ruin.getZ());
        ItemEntity ie = new ItemEntity(w, ruin.getX() + 0.5, y + 0.5, ruin.getZ() + 0.5, new ItemStack(DuelMod.AJA_STONE));
        ie.setVelocity(0, 0, 0);
        protect(ie);
        w.spawnEntity(ie);
        NbtCompound c = new NbtCompound();
        c.putUuid("id", ie.getUuid());
        c.putInt("x", ruin.getX()); c.putInt("z", ruin.getZ());
        G(s).put("aja.spawn", c);
        AlchemyData.get(s).markDirty();
        for (ServerPlayerEntity p : players) p.playSoundToPlayer(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.MASTER, 1f, 0.5f);
        s.getPlayerManager().broadcast(Text.literal("🔴 어딘가 바다 밑에서 붉은 빛이 치솟는다… (동이 트기 전에 찾아라)").formatted(Formatting.RED, Formatting.BOLD), false);
        DuelMod.LOG.info("[aja] 에이자의 적석 등장: {} {} {}", ruin.getX(), y, ruin.getZ());
    }

    static void removeSpawn(MinecraftServer s, boolean despawn) {
        NbtCompound c = G(s).getCompound("aja.spawn");
        G(s).remove("aja.spawn");
        AlchemyData.get(s).markDirty();
        if (!despawn || c.isEmpty()) return;
        UUID id = c.getUuid("id");
        Entity e = s.getOverworld().getEntity(id);
        if (e != null) e.discard();
        s.getPlayerManager().broadcast(Text.literal("…동이 트자 붉은 빛이 바다 속으로 가라앉았다.").formatted(Formatting.DARK_RED, Formatting.ITALIC), false);
    }

    /** 성자(신부 5)가 적석을 보관 / 꺼냄 */
    public static String store(ServerPlayerEntity p, boolean put) {
        AlchemyData.Entry e = Jobs.entry(p);
        if (Priest.rank(e) < 5) return "성자(신부 5등급)만 보관할 수 있습니다";
        if (put) {
            if (Jobs.count(p, DuelMod.AJA_STONE) < 1) return "적석을 들고 있지 않습니다";
            Jobs.take(p, DuelMod.AJA_STONE, 1);
            e.flag("pri.aja", true);
            G(p.getServer()).remove("aja.last");
            G(p.getServer()).remove("aja.shown");
            p.sendMessage(Text.literal("✝ 에이자의 적석을 성유물로 봉인했습니다 (위치가 숨겨집니다. 당신이 쓰러지면 떨어집니다)").formatted(Formatting.GOLD), false);
        } else {
            if (!e.flag("pri.aja")) return "보관 중인 적석이 없습니다";
            e.flag("pri.aja", false);
            p.getInventory().offerOrDrop(new ItemStack(DuelMod.AJA_STONE));
        }
        AlchemyData.get(p.getServer()).markDirty();
        return null;
    }
}
