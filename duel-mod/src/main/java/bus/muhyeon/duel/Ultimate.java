package bus.muhyeon.duel;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static bus.muhyeon.duel.Vampire.P;

/**
 * 완전생물 (뱀파이어 4단계). 서버에 1명, 에이자의 적석은 몸에 흡수되어 다시 돌아오지 않는다.
 * 의식: 기둥 속 사내 + 루트 숙련도 100% + 흡수량(흡혈량) 300,000 + 서버에 완전생물 없음
 *   → 석가면을 머리에 쓰고, 한낮(햇빛 아래)에 에이자의 적석을 손에 들고 우클릭
 *   → 60초 동안 움직일 수 없고, 위치가 공개되며, 받는 피해 2배. 죽으면 숙련도 0 + 적석이 그 자리에 떨어짐
 * 능력: 햇빛·신성력 면역 · 최대 체력 +20 · 전투 밖(10초) 5초마다 완전 회복 · 고른 루트 기술 6개 + 생명 창조(비행) + 파문(빛의 십자가)
 *   불멸: 죽어도 10초 뒤 되살아남 (1시간에 1번)
 * 유일한 종말: 높이 320 위에서 죽거나, 공허에 빠져 죽으면 되살아나지 못하고 완전생물을 영구히 잃는다 (인간으로 돌아감)
 * 위치는 10분마다 지도에 공개. 포켓몬 배틀·대통령 도전에는 아무 이점 없음.
 */
public final class Ultimate {
    private Ultimate() {}

    public static final double NEED_DRINK = 300_000;
    public static final long RITUAL_MS = 60_000;

    static final Map<UUID, Long> RITUAL = new HashMap<>();
    static final Map<UUID, Long> LAST_HURT = new HashMap<>();
    static final Map<UUID, Long> DORMANT = new HashMap<>();

    public static boolean inRitual(ServerPlayerEntity p) { return RITUAL.containsKey(p.getUuid()); }

    static UUID ultimate(MinecraftServer s) {
        String id = AlchemyData.get(s).global.getString("ultimate");
        return id.isEmpty() ? null : UUID.fromString(id);
    }

    static String[] progressLore(ServerPlayerEntity p, AlchemyData.Entry e) {
        List<String> l = new ArrayList<>();
        if (Vampire.stage(e) == 4) {
            l.add("햇빛·신성력 면역 · 최대 체력 +20");
            l.add("전투 밖 5초마다 완전 회복 · 1시간에 1번 불멸");
            l.add("높이 320 위나 공허에서 죽으면 끝");
            l.add("▶ 기술 고르기: /job 창 [기술] 클릭");
            return l.toArray(new String[0]);
        }
        l.add(JobMenu.mark(Pillar.mastery(e) >= 100) + "루트 숙련도 100%  (" + (int) Pillar.mastery(e) + "%)");
        l.add(JobMenu.mark(e.num(P + "drink") >= NEED_DRINK) + "흡수량 300,000  (" + Alchemy.fmt(e.num(P + "drink")) + ")");
        l.add(JobMenu.mark(ultimate(p.getServer()) == null) + "서버에 완전생물이 없을 것");
        l.add(JobMenu.mark(Jobs.count(p, DuelMod.AJA_STONE) > 0) + "에이자의 적석 (서버에 단 하나)");
        l.add("석가면을 쓰고, 한낮 햇빛 아래에서 적석을 들고 우클릭");
        l.add("60초 동안 움직이지 못하고 위치 공개 · 받는 피해 2배");
        return l.toArray(new String[0]);
    }

    // ───────── 의식 ─────────

    public static ActionResult tryRitual(ServerPlayerEntity p, ItemStack stone) {
        AlchemyData.Entry e = Jobs.entry(p);
        MinecraftServer s = p.getServer();
        if (Vampire.stage(e) != 3) { msg(p, "기둥 속 사내만 이 돌을 다룰 수 있다… 손이 타는 듯 뜨겁다"); return ActionResult.FAIL; }
        if (Pillar.mastery(e) < 100) { msg(p, "아직 루트를 완전히 익히지 못했습니다"); return ActionResult.FAIL; }
        if (e.num(P + "drink") < NEED_DRINK) { msg(p, "흡수량이 부족합니다 (" + Alchemy.fmt(e.num(P + "drink")) + " / 300,000)"); return ActionResult.FAIL; }
        if (ultimate(s) != null) { msg(p, "이미 완전생물이 존재합니다"); return ActionResult.FAIL; }
        if (!p.getEquippedStack(EquipmentSlot.HEAD).isOf(DuelMod.STONE_MASK)) { msg(p, "석가면을 머리에 써야 합니다"); return ActionResult.FAIL; }
        ServerWorld w = p.getServerWorld();
        long t = w.getTimeOfDay() % 24000;
        if (t < 5000 || t > 7000 || !Vampire.sunlit(w, p)) { msg(p, "한낮(정오 무렵)의 햇빛 아래에서만 의식을 치를 수 있습니다"); return ActionResult.FAIL; }
        if (RITUAL.containsKey(p.getUuid())) return ActionResult.FAIL;
        stone.decrement(1);
        RITUAL.put(p.getUuid(), System.currentTimeMillis());
        s.getPlayerManager().broadcast(Text.literal("🔴 " + p.getName().getString() + "이(가) 석가면에 에이자의 적석을 끼웠다! 위치: " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ()
                + " — 60초 안에 쓰러뜨려라").formatted(Formatting.RED, Formatting.BOLD), false);
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) o.playSoundToPlayer(SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 1f, 0.5f);
        return ActionResult.SUCCESS;
    }

    static void ritualTick(ServerPlayerEntity p, long start) {
        ServerWorld w = p.getServerWorld();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 9, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 30, 128, false, false));
        w.spawnParticles(Aja.RED, p.getX(), p.getEyeY(), p.getZ(), 20, 0.3, 0.3, 0.3, 0);
        for (int y = 0; y < 40; y += 2) w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + y, p.getZ(), 1, 0.05, 0.2, 0.05, 0);
        long left = start + RITUAL_MS - System.currentTimeMillis();
        p.sendMessage(Text.literal("🔴 빛이 몸을 꿰뚫는다… " + (left / 1000 + 1) + "초").formatted(Formatting.RED, Formatting.BOLD), true);
        if (left <= 0) complete(p);
    }

    static void complete(ServerPlayerEntity p) {
        RITUAL.remove(p.getUuid());
        MinecraftServer s = p.getServer();
        AlchemyData d = AlchemyData.get(s);
        AlchemyData.Entry e = d.of(p.getUuid());
        e.num(P + "rank", 4);
        d.global.putString("ultimate", p.getUuidAsString());
        d.global.putBoolean("aja.used", true);
        d.global.remove("aja.last");
        d.global.remove("aja.shown");
        d.markDirty();
        Pillar.ensureMods(p, e);
        p.setHealth(p.getMaxHealth());
        ServerWorld w = p.getServerWorld();
        for (int i = 0; i < 4; i++) {
            LightningEntity l = EntityType.LIGHTNING_BOLT.create(w);
            if (l == null) continue;
            l.setCosmetic(true);
            l.refreshPositionAfterTeleport(p.getX() + (i % 2 == 0 ? 3 : -3), p.getY(), p.getZ() + (i < 2 ? 3 : -3));
            w.spawnEntity(l);
        }
        w.spawnParticles(ParticleTypes.FLASH, p.getX(), p.getY() + 1, p.getZ(), 3, 0.5, 0.5, 0.5, 0);
        w.spawnParticles(Aja.RED, p.getX(), p.getY() + 1, p.getZ(), 300, 2, 3, 2, 0);
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) {
            o.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(p.getName().getString() + " — 태양을 극복한 자").formatted(Formatting.GRAY)));
            o.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("완전생물").formatted(Formatting.DARK_RED, Formatting.BOLD)));
            o.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 0.5f);
            o.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 1f, 0.6f);
        }
        p.sendMessage(Text.literal("🔴 완전생물이 되었다. /job 에서 루트 기술 6개를 고르세요 (7번 생명 창조, 8번 파문).").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
    }

    static void failRitual(ServerPlayerEntity p) {
        if (RITUAL.remove(p.getUuid()) == null) return;
        AlchemyData.Entry e = Jobs.entry(p);
        e.num(P + "mastery", 0);
        AlchemyData.get(p.getServer()).markDirty();
        ItemEntity ie = new ItemEntity(p.getServerWorld(), p.getX(), p.getY() + 0.5, p.getZ(), new ItemStack(DuelMod.AJA_STONE));
        Aja.protect(ie);
        p.getServerWorld().spawnEntity(ie);
        p.getServer().getPlayerManager().broadcast(Text.literal("🔴 의식이 무너졌다 — 에이자의 적석이 " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ() + " 에 떨어졌다").formatted(Formatting.GOLD, Formatting.BOLD), false);
    }

    /** 영원히 끝 — 인간으로 */
    static void ending(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        AlchemyData d = AlchemyData.get(s);
        AlchemyData.Entry e = d.of(p.getUuid());
        Vampire.INSTANCE.onQuit(p, e);
        e.clearJobData(P);
        e.job = "";
        d.global.remove("ultimate");
        d.markDirty();
        setFlight(p, false);
        s.getPlayerManager().broadcast(Text.literal("🔴 완전생물 " + p.getName().getString() + "은(는) 돌아오지 못하는 곳으로 떨어졌다… 그리고 생각하는 것을 그만두었다.").formatted(Formatting.DARK_RED, Formatting.ITALIC), false);
    }

    static boolean doomZone(ServerPlayerEntity p) { return p.getY() > 320 || p.getY() < p.getServerWorld().getBottomY() - 2; }

    // ───────── 비행 (생명 창조) ─────────

    static void setFlight(ServerPlayerEntity p, boolean on) {
        if (p.isCreative() || p.isSpectator()) return;
        p.getAbilities().allowFlying = on;
        if (!on) p.getAbilities().flying = false;
        p.sendAbilitiesUpdate();
    }

    // ───────── 기술 ─────────

    static final JobSkills.Skill CREATION = new JobSkills.Skill("생명 창조", "날개", 4, 0, 3, "등에 날개를 만들어 자유 비행 (켜기/끄기)");
    static final JobSkills.Skill RIPPLE = new JobSkills.Skill("파문 역이용", "파문", 4, 60, 30, "신부의 빛의 십자가를 그대로 — 빛기둥 3개, 각 12 피해");

    /** 고른 기술 목록 "route:idx" (없으면 기본) */
    static List<String> picks(AlchemyData.Entry e) {
        List<String> l = new ArrayList<>();
        for (String x : e.str(P + "ult").split(",")) if (x.contains(":")) l.add(x);
        if (l.isEmpty()) {
            String own = e.str(P + "route");
            l.add(own + ":0"); l.add(own + ":5"); l.add(own + ":6");
            for (String r : Pillar.ROUTES) if (!r.equals(own) && l.size() < 6) l.add(r + ":" + (r.equals(Pillar.WIND) ? 5 : r.equals(Pillar.HEAT) ? 0 : r.equals(Pillar.LIGHT) ? 0 : 6));
        }
        return l;
    }

    static JobSkills.Skill[] skills(AlchemyData.Entry e) {
        List<String> pk = picks(e);
        JobSkills.Skill[] out = new JobSkills.Skill[8];
        for (int i = 0; i < 6; i++) {
            if (i >= pk.size()) continue;
            String[] a = pk.get(i).split(":");
            JobSkills.Skill sk = Routes.list(a[0])[Integer.parseInt(a[1])];
            out[i] = new JobSkills.Skill(sk.name() + " (" + Pillar.routeName(a[0]) + ")", sk.shortName(), 4, sk.cost(), sk.cooldown(), sk.desc());
        }
        out[6] = CREATION;
        out[7] = RIPPLE;
        return out;
    }

    static boolean use(ServerPlayerEntity p, AlchemyData.Entry e, int idx) {
        if (idx == 6) {
            boolean on = !p.getAbilities().allowFlying;
            setFlight(p, on);
            p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 1.5f, 0.5f);
            p.sendMessage(Text.literal(on ? "등에서 거대한 날개가 돋는다 (비행 가능)" : "날개를 접었다").formatted(Formatting.DARK_PURPLE), true);
            return true;
        }
        if (idx == 7) return Priest.lightCross(p);
        List<String> pk = picks(e);
        if (idx >= pk.size()) return false;
        String[] a = pk.get(idx).split(":");
        return Routes.use(p, e, a[0], Integer.parseInt(a[1]));
    }

    // ───────── 등록 ─────────

    private static int clock = 0;

    public static void register() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayerEntity p) || taken <= 0) return;
            LAST_HURT.put(p.getUuid(), System.currentTimeMillis());
            // 의식 중엔 받는 피해 2배
            if (RITUAL.containsKey(p.getUuid()) && !source.isOf(net.minecraft.entity.damage.DamageTypes.GENERIC_KILL)) {
                p.timeUntilRegen = 0;
                float extra = Math.min(taken, p.getHealth() - 0.01f);
                if (extra > 0) p.setHealth(p.getHealth() - extra);
            }
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return true;
            Long d = DORMANT.get(p.getUuid());
            return d == null || d < System.currentTimeMillis() || doomZone(p);
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity p)) return true;
            if (RITUAL.containsKey(p.getUuid())) { failRitual(p); return true; }
            AlchemyData.Entry e = Jobs.entry(p);
            if (Vampire.stage(e) != 4) return true;
            if (doomZone(p)) { Tasks.later(1, () -> ending(p)); return true; }
            long now = System.currentTimeMillis();
            if (now - e.time(P + "immortal") < 3_600_000L) return true;
            e.time(P + "immortal", now);
            AlchemyData.get(p.getServer()).markDirty();
            p.setHealth(1);
            DORMANT.put(p.getUuid(), now + 10_000);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 200, 0, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 200, 9, false, false));
            p.getServerWorld().spawnParticles(Aja.RED, p.getX(), p.getY() + 1, p.getZ(), 80, 0.5, 1, 0.5, 0);
            p.sendMessage(Text.literal("🔴 몸이 흩어졌다가… 다시 모인다 (10초)").formatted(Formatting.DARK_RED), false);
            Tasks.later(200, () -> {
                DORMANT.remove(p.getUuid());
                if (!p.isAlive()) return;
                p.setHealth(p.getMaxHealth());
                p.removeStatusEffect(StatusEffects.INVISIBILITY);
                p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.5f, 1f);
            });
            return false;
        });
        Markers.PROVIDERS.add((s, out) -> {
            UUID id = ultimate(s);
            if (id != null) {
                ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
                var g = AlchemyData.get(s).global;
                if (p != null && s.getTicks() % 12000 < 20) {
                    var c = new net.minecraft.nbt.NbtCompound();
                    c.putString("dim", p.getServerWorld().getRegistryKey().getValue().toString());
                    c.putInt("x", p.getBlockX()); c.putInt("y", p.getBlockY()); c.putInt("z", p.getBlockZ());
                    g.put("ult.shown", c);
                }
                var c = g.getCompound("ult.shown");
                if (!c.isEmpty()) out.add(new MarkerPayload.Marker("ultimate", "완전생물 (10분 전)", "完", c.getString("dim"), c.getInt("x"), c.getInt("y"), c.getInt("z"), Markers.DARK_RED));
            }
            for (UUID r : RITUAL.keySet()) {
                ServerPlayerEntity p = s.getPlayerManager().getPlayer(r);
                if (p != null) out.add(Markers.at("ult_ritual", "적석 의식: " + p.getName().getString(), "赤", p, Markers.RED));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(Ultimate::tick);
    }

    private static void tick(MinecraftServer s) {
        if (++clock % 20 != 0) return;
        long now = System.currentTimeMillis();
        for (UUID id : new ArrayList<>(RITUAL.keySet())) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            if (p == null) { RITUAL.remove(id); continue; }
            ritualTick(p, RITUAL.get(id));
        }
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            AlchemyData.Entry e = Jobs.entry(p);
            int st = Vampire.stage(e);
            if (st != 4) {
                if (p.getAbilities().allowFlying && !p.isCreative() && !p.isSpectator() && e.flag(P + "flying")) { setFlight(p, false); e.flag(P + "flying", false); }
                continue;
            }
            e.flag(P + "flying", p.getAbilities().allowFlying);
            Long hurt = LAST_HURT.get(p.getUuid());
            if ((hurt == null || now - hurt > 10_000) && clock % 100 == 0 && p.getHealth() < p.getMaxHealth()) {
                p.setHealth(p.getMaxHealth());
                p.getServerWorld().spawnParticles(Aja.RED, p.getX(), p.getY() + 1, p.getZ(), 15, 0.3, 0.6, 0.3, 0);
            }
        }
    }

    static void msg(ServerPlayerEntity p, String m) { p.sendMessage(Text.literal(m).formatted(Formatting.RED), false); }
}
