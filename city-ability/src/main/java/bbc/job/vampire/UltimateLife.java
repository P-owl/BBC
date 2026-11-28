package bbc.job.vampire;

import bbc.Bbc;
import bbc.combat.Combat;
import bbc.combat.CombatMode;
import bbc.combat.Fx;
import bbc.combat.Motion;
import bbc.combat.WorldFx;
import bbc.core.PlayerData;
import bbc.core.Tasks;
import bbc.job.Jobs;
import bbc.net.CutscenePayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static bbc.job.vampire.VampireJob.P;

/**
 * 완전생물 (뱀파이어 4단계) — 무현버스 설계를 이식. 서버에 1명, 에이자의 적석은 몸에 흡수되어 다시 돌아오지 않는다.
 * 의식: 기둥 속 사내 + 루트 숙련도 100% (기술로 준 피해 2,000) + 흡수량(흡혈량) 300,000 + 서버에 완전생물 없음
 *   → 석가면을 머리에 쓰고, 한낮(햇빛 아래, 의식 중엔 석화 안 됨)에 에이자의 적석을 손에 들고 우클릭
 *   → 60초 동안 움직일 수 없고, 위치가 15초마다 공개되며, 받는 피해 2배. 죽거나 나가면 숙련도 0 + 적석이 그 자리에 떨어짐
 *   → 버티면 변신 (140틱 컷신 "ul_transform", 참고 영상 2:53.5~3:05.5 — 붉은 돌 · 몸부림 · 금빛 껍질 · 바-앙 · 날개)
 * 능력: 햇빛 면역 · 피가 줄지 않음 · 최대 체력 +20 · 전투 밖(10초) 5초마다 완전 회복 · 불멸 (죽어도 10초 뒤 되살아남, 1시간에 1번)
 * 유일한 종말: 높이 320 위에서 죽거나, 공허에 빠져 죽으면 되살아나지 못하고 완전생물을 영구히 잃는다 (인간으로 돌아감)
 * 위치는 10분마다 모두에게 공개.
 *
 * 에이자의 적석 — 서버 역사상 단 한 번만 나타난다.
 *   등장: 보름달 자정 + 뇌우 + 숙련도 100% 기둥 속 사내 접속 + 접속자 4명 이상 → 그 자정에 5%
 *   성공하면 해저 유적 하나(없으면 누군가에게서 200~600칸 떨어진 땅)에 생김 (좌표 비공개, 128칸 안에서 붉은 빛기둥). 동틀 때까지 못 주우면 사라지고 다음에 다시 굴림
 *   누군가 한 번 주우면 다시는 안 생김. 부서지지 않음 (공허에 빠지면 스폰으로). 들고 있는 사람 위치는 2분마다 모두에게 공개
 */
public final class UltimateLife {
    private UltimateLife() {}

    public static final double NEED_DRINK = 300_000, NEED_MASTERY = 2_000;
    public static final long RITUAL_MS = 60_000;
    static final DustParticleEffect RED = new DustParticleEffect(new Vector3f(1f, 0.05f, 0.1f), 2.0f);
    private static final Identifier HP_ID = Identifier.of(Bbc.MOD_ID, "ultimate_hp");

    public static final Item AJA_STONE = Registry.register(Registries.ITEM, Identifier.of(Bbc.MOD_ID, "aja_stone"),
            new Stone(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));

    /** 에이자의 적석: 석가면을 쓴 기둥 속 사내가 한낮에 우클릭 → 의식 */
    public static class Stone extends Item {
        public Stone(Settings s) { super(s); }
        @Override public boolean hasGlint(ItemStack stack) { return true; }
        @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
            ItemStack st = user.getStackInHand(hand);
            if (world.isClient || !(user instanceof ServerPlayerEntity p)) return TypedActionResult.pass(st);
            return tryRitual(p, st) ? TypedActionResult.success(st) : TypedActionResult.fail(st);
        }
        @Override public void appendTooltip(ItemStack stack, TooltipContext c, List<Text> tip, TooltipType type) {
            tip.add(Text.literal("빛을 수만 배로 증폭시키는 붉은 보석").formatted(Formatting.RED));
            tip.add(Text.literal("세상에 단 하나뿐이다").formatted(Formatting.DARK_RED, Formatting.ITALIC));
            tip.add(Text.literal("들고 있으면 2분마다 위치가 모두에게 알려집니다").formatted(Formatting.GRAY));
            tip.add(Text.literal("기둥 속 사내가 석가면을 쓰고 한낮의 햇빛 아래에서 우클릭").formatted(Formatting.GRAY));
        }
    }

    static final Map<UUID, Long> RITUAL = new HashMap<>();
    static final Map<UUID, Long> LAST_HURT = new HashMap<>();
    static final Map<UUID, Long> DORMANT = new HashMap<>();
    static final Map<UUID, Long> SCENE = new HashMap<>();

    public static boolean inRitual(ServerPlayerEntity p) { return RITUAL.containsKey(p.getUuid()); }
    static NbtCompound G(MinecraftServer s) { return PlayerData.get(s).global; }
    static void dirty(MinecraftServer s) { PlayerData.get(s).markDirty(); }

    public static UUID ultimate(MinecraftServer s) {
        String id = G(s).getString("ult");
        try { return id.isEmpty() ? null : UUID.fromString(id); } catch (IllegalArgumentException ex) { return null; }
    }

    public static double mastery(PlayerData.Entry e) { return Math.min(100, e.num(P + "mastery") / NEED_MASTERY * 100); }

    static boolean immortalReady(ServerPlayerEntity p) { return System.currentTimeMillis() - (long) Jobs.entry(p).num(P + "immortal") >= 3_600_000L; }

    /** 조건 목록 (적석 우클릭 실패·/bbc info 에서) */
    public static List<String> progress(ServerPlayerEntity p) {
        PlayerData.Entry e = Jobs.entry(p);
        List<String> l = new ArrayList<>();
        l.add(mark(VampireJob.stage(e) == 3) + "기둥 속 사내");
        l.add(mark(mastery(e) >= 100) + "루트 숙련도 100%  (" + (int) mastery(e) + "% — 기술로 준 피해 " + (int) e.num(P + "mastery") + " / 2,000)");
        l.add(mark(e.num(P + "drink") >= NEED_DRINK) + "흡수량 300,000  (" + (long) e.num(P + "drink") + ")");
        l.add(mark(ultimate(p.getServer()) == null) + "서버에 완전생물이 없을 것");
        l.add(mark(p.getEquippedStack(EquipmentSlot.HEAD).isOf(VampireJob.STONE_MASK)) + "석가면을 머리에");
        l.add("한낮(정오 무렵) 햇빛 아래에서 적석을 들고 우클릭 → 60초 버티기 (움직일 수 없음 · 위치 공개 · 받는 피해 2배)");
        return l;
    }
    private static String mark(boolean ok) { return ok ? "✔ " : "✘ "; }

    // ───────── 의식 ─────────

    static boolean tryRitual(ServerPlayerEntity p, ItemStack stone) {
        PlayerData.Entry e = Jobs.entry(p);
        MinecraftServer s = p.getServer();
        String why = null;
        if (VampireJob.stage(e) != 3) why = "기둥 속 사내만 이 돌을 다룰 수 있다… 손이 타는 듯 뜨겁다";
        else if (mastery(e) < 100) why = "아직 루트를 완전히 익히지 못했다";
        else if (e.num(P + "drink") < NEED_DRINK) why = "흡수량이 부족하다";
        else if (ultimate(s) != null) why = "이미 완전생물이 존재한다";
        else if (!p.getEquippedStack(EquipmentSlot.HEAD).isOf(VampireJob.STONE_MASK)) why = "석가면을 머리에 써야 한다";
        else {
            ServerWorld w = p.getServerWorld();
            long t = w.getTimeOfDay() % 24000;
            if (t < 5000 || t > 7000 || !VampireJob.sunlit(w, p)) why = "한낮(정오 무렵)의 햇빛 아래에서만 의식을 치를 수 있다";
        }
        if (why != null) {
            p.sendMessage(Text.literal("🔴 " + why).formatted(Formatting.RED), false);
            for (String l : progress(p)) p.sendMessage(Text.literal("  " + l).formatted(l.startsWith("✔") ? Formatting.GREEN : Formatting.GRAY), false);
            return false;
        }
        if (RITUAL.containsKey(p.getUuid())) return false;
        stone.decrement(1);
        CombatMode.set(p, false);
        RITUAL.put(p.getUuid(), System.currentTimeMillis());
        Motion.play(p, "ul_ritual");
        UltimateForm.say(p, UltimateForm.S_RITUAL);
        s.getPlayerManager().broadcast(Text.literal("🔴 " + p.getName().getString() + "이(가) 석가면에 에이자의 적석을 끼웠다! 위치: " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ()
                + " — 60초 안에 쓰러뜨려라").formatted(Formatting.RED, Formatting.BOLD), false);
        for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) o.playSoundToPlayer(SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 1f, 0.5f);
        return true;
    }

    static void ritualTick(ServerPlayerEntity p, long start) {
        ServerWorld w = p.getServerWorld();
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 9, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, 30, 128, false, false));
        Vec3d head = p.getEyePos();
        w.spawnParticles(RED, head.x, head.y, head.z, 20, 0.3, 0.3, 0.3, 0);
        for (int y = 0; y < 40; y += 2) w.spawnParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + y, p.getZ(), 1, 0.05, 0.2, 0.05, 0);
        w.spawnParticles(UltimateForm.GOLD_AURA, p.getX(), p.getY() + 1, p.getZ(), 6, 0.4, 0.6, 0.4, 0);
        long left = start + RITUAL_MS - System.currentTimeMillis();
        long sec = left / 1000 + 1;
        p.sendMessage(Text.literal("🔴 빛이 몸을 꿰뚫는다… " + sec + "초").formatted(Formatting.RED, Formatting.BOLD), true);
        if (sec % 2 == 0) Motion.play(p, "ul_ritual");
        if (sec % 15 == 0 && sec < 60) p.getServer().getPlayerManager().broadcast(Text.literal("🔴 적석 의식: " + p.getName().getString() + " — " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ() + " (" + sec + "초 남음)").formatted(Formatting.RED), false);
        if (sec % 10 == 0) w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.6f);
        if (left <= 0) complete(p);
    }

    static void failRitual(ServerPlayerEntity p, String why) {
        if (RITUAL.remove(p.getUuid()) == null) return;
        PlayerData.Entry e = Jobs.entry(p);
        e.num(P + "mastery", 0);
        Jobs.dirty(p);
        ItemEntity ie = new ItemEntity(p.getServerWorld(), p.getX(), p.getY() + 0.5, p.getZ(), new ItemStack(AJA_STONE));
        protect(ie);
        p.getServerWorld().spawnEntity(ie);
        p.getServer().getPlayerManager().broadcast(Text.literal("🔴 의식이 무너졌다 (" + why + ") — 에이자의 적석이 " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ() + " 에 떨어졌다").formatted(Formatting.GOLD, Formatting.BOLD), false);
    }

    /** 완전생물이 됨 + 변신 장면. admin = 관리자 명령·선택창 (조건 없이, 이미 있던 완전생물은 그대로 둠) */
    public static void complete(ServerPlayerEntity p) { become(p, true); }

    public static void become(ServerPlayerEntity p, boolean scene) {
        RITUAL.remove(p.getUuid());
        MinecraftServer s = p.getServer();
        PlayerData.Entry e = Jobs.entry(p);
        if (!VampireJob.is(e)) { CombatMode.set(p, false); Jobs.set(p, VampireJob.INSTANCE); e = Jobs.entry(p); }
        if (e.str(P + "route").isEmpty()) e.str(P + "route", PillarLight.ROUTE);
        e.num(P + "rank", 4);
        G(s).putString("ult", p.getUuidAsString());
        G(s).putBoolean("aja.used", true);
        G(s).remove("aja.last");
        dirty(s);
        Jobs.dirty(p);
        ensureHp(p, true);
        p.setHealth(p.getMaxHealth());
        if (scene) transformScene(p);
    }

    // ───────── 탄생 장면 (320틱 = 16초) — 참고: TV 애니 카즈 변신 + '완전생물 탄생' 장면의 흐름·구도 ─────────
    //   시간을 새벽(21800)으로 돌렸다가 100~200틱에 해가 떠오르게 (23700), 끝엔 아침 — 몸은 서쪽을 보게 세워서 컷신 앞 카메라에선 해가 등 뒤 (역광 실루엣)
    //   0~24 가면 속 붉은 돌이 빛남 (붉은 빛줄기가 하늘로) → 26 보라·흰 섬광 (화면이 번쩍) → 40~58 고개를 듦 →
    //   60~78 땅을 할큄 (땅이 깨짐) → 78~100 등을 젖히고 빛에 삼켜짐 (몸에서 무지갯빛 빛줄기) → 100~124 빛기둥 속에서 일어섬 →
    //   124~156 해가 떠오름 (해를 등진 실루엣, 무지갯빛이 바닥을 휩쓺) → 157 바-앙 (모두에게 제목) → 190~218 역광 속 빛나는 눈 →
    //   220~250 해를 향해 손을 뻗음 → 252~266 어둠 속 눈 → 268 날개 → 272~ 떠오름 → 296~320 반짝이 속에서 내려다봄
    static final int SCENE_LEN = 320;
    static final long T_NIGHT = 21800, T_SUNRISE = 23700, T_END = 24150;
    static final int[] RAINBOW = {0xFF7AD0, 0x7ADFFF, 0x9CFF7A, 0xFFE07A, 0xC79CFF};

    /** 장면 틱 → 하루 중 시각 */
    static long sceneTime(int k) {
        if (k < 100) return T_NIGHT;
        if (k < 200) { double x = (k - 100) / 100.0; return T_NIGHT + (long) ((T_SUNRISE - T_NIGHT) * (x * x * (3 - 2 * x))); }
        double x = Math.min(1, (k - 200) / 120.0);
        return T_SUNRISE + (long) ((T_END - T_SUNRISE) * x);
    }

    static void transformScene(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        MinecraftServer s = p.getServer();
        ServerWorld ow = s.getOverworld();
        SCENE.put(p.getUuid(), System.currentTimeMillis());
        final float yaw = 90f;   // 서쪽을 봄 (해는 동쪽에서 뜸 → 앞 카메라에서 등 뒤)
        Vec3d at = p.getPos();
        p.networkHandler.requestTeleport(at.x, at.y, at.z, yaw, 0f);
        p.setYaw(yaw); p.setHeadYaw(yaw); p.setBodyYaw(yaw);
        final long day = ow.getTimeOfDay() / 24000L * 24000L;
        ow.setWeather(12000, 0, false, false);   // 해가 보이게 맑게
        Motion.play(p, "ul_transform");
        Tasks.later(1, () -> { if (alive(p) && ServerPlayNetworking.canSend(p, CutscenePayload.ID)) ServerPlayNetworking.send(p, new CutscenePayload("ul_transform", p.getId(), -1)); });
        Combat.invuln(p, SCENE_LEN);
        Vec3d fw = new Vec3d(-1, 0, 0), rt = new Vec3d(0, 0, -1);   // 서쪽을 볼 때 앞 · 오른쪽
        for (int i = 0; i < SCENE_LEN; i++) {
            final int k = i;
            Tasks.later(i, () -> {
                ow.setTimeOfDay(day + sceneTime(k));
                // 시각을 매 틱 보내서 해가 부드럽게 떠오르게 (보통은 1초에 한 번이라 뚝뚝 끊김)
                s.getPlayerManager().sendToDimension(new net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket(ow.getTime(), ow.getTimeOfDay(),
                        ow.getGameRules().getBoolean(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE)), net.minecraft.world.World.OVERWORLD);
                if (!alive(p) || k >= 272) return;
                p.networkHandler.requestTeleport(at.x, at.y, at.z, yaw, 0f);
                p.setVelocity(0, 0, 0);
            });
        }
        // 0~24 가면 속 붉은 돌: 얼굴에 붉은 반짝 + 붉은 빛줄기가 하늘로
        Tasks.later(2, () -> { if (alive(p)) AjaVoice.say(p, UltimateForm.S_TRANSFORM1); });
        Tasks.later(4, () -> { if (alive(p)) WorldFx.beam(w, p.getPos().add(0, 1.0, 0), p.getPos().add(0, 40, 0), 0.28, 22, 0xFF2030); });
        for (int t = 2; t < 24; t += 3) Tasks.later(t, () -> {
            if (!alive(p)) return;
            Vec3d face = p.getPos().add(0, 1.0, 0).add(fw.multiply(0.3));
            WorldFx.glint(w, face, 0.9, 0xFF3030);
            w.spawnParticles(RED, face.x, face.y, face.z, 8, 0.15, 0.15, 0.15, 0);
        });
        // 26 보라·흰 섬광
        Tasks.later(26, () -> {
            if (!alive(p)) return;
            Vec3d c = p.getPos().add(0, 1.0, 0).add(fw.multiply(0.8));
            WorldFx.flash(w, c, 3.2);
            WorldFx.ring(w, c, fw, 0.4, 6, 0.4, 10, 0xD8C0FF);
            WorldFx.sparks(w, c, 1.6, 10, 10, 0xE0D0FF);
            for (ServerPlayerEntity o : w.getPlayers()) if (o.squaredDistanceTo(p) < 40 * 40) Fx.flash(o, 1.0f);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 2f, 0.6f);
            w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 1.2f, 1.4f);
        });
        for (int t = 40; t < 58; t += 4) Tasks.later(t, () -> { if (alive(p)) eyes(w, p, fw, rt, 1.05, 0.6, 0xF0E0FF); });
        // 60~78 땅을 할큄: 손 밑이 깨지고 빛이 새어 나옴
        Tasks.later(60, () -> { if (alive(p)) AjaVoice.say(p, UltimateForm.S_TRANSFORM2); });
        Tasks.later(61, () -> {
            if (!alive(p)) return;
            Vec3d hand = p.getPos().add(fw.multiply(0.55)).add(rt.multiply(0.35));
            Fx.debris(w, hand, 10, 0.8);
            WorldFx.burst(w, hand.add(0, 0.2, 0), new Vec3d(0, 1, 0), 1.2, 0xFFFFFF, 0xC79CFF);
            WorldFx.wave(w, hand, 0.2, 3.0, 8, 0xE8D8FF);
            Fx.shakeAround(w, hand, 12, 0.6f);
        });
        for (int t = 64; t < 78; t += 3) Tasks.later(t, () -> { if (alive(p)) WorldFx.sparks(w, p.getPos().add(fw.multiply(0.5)).add(0, 0.3, 0), 0.8, 4, 3, 0xE8D8FF); });
        // 78~100 빛에 삼켜짐: 몸에서 사방으로 무지갯빛 빛줄기 + 금빛 기운 + 돌 껍질 조각
        for (int t = 78; t < 100; t += 2) {
            final int tt = t;
            Tasks.later(t, () -> {
                if (!alive(p)) return;
                Vec3d c = p.getPos().add(0, 1.0, 0);
                for (int q = 0; q < 3; q++) {
                    double a = w.random.nextDouble() * Math.PI * 2, el = (w.random.nextDouble() - 0.3) * 1.2;
                    Vec3d d = new Vec3d(Math.cos(a) * Math.cos(el), Math.sin(el), Math.sin(a) * Math.cos(el));
                    WorldFx.beam(w, c, c.add(d.multiply(5 + w.random.nextDouble() * 4)), 0.12, 4, RAINBOW[(tt / 2 + q) % RAINBOW.length]);
                }
                w.spawnParticles(UltimateForm.GOLD_AURA, c.x, c.y, c.z, 14, 0.45, 0.6, 0.45, 0);
                w.spawnParticles(new net.minecraft.particle.BlockStateParticleEffect(ParticleTypes.BLOCK, net.minecraft.block.Blocks.STONE.getDefaultState()), c.x, c.y, c.z, 3, 0.4, 0.5, 0.4, 0);
            });
        }
        // 100~124 빛기둥 속에서 일어섬
        Tasks.later(100, () -> {
            if (!alive(p)) return;
            WorldFx.beam(w, p.getPos(), p.getPos().add(0, 60, 0), 0.9, 26, 0xEADCFF);
            WorldFx.flash(w, p.getPos().add(0, 1.2, 0), 2.6);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 1.2f, 1.3f);
        });
        for (int t = 102; t < 124; t += 6) Tasks.later(t, () -> { if (alive(p)) WorldFx.ring(w, p.getPos().add(0, 0.2, 0), new Vec3d(0, 1, 0), 0.6, 7, 0.35, 10, 0xFFFFFF); });
        // 124~156 일출: 무지갯빛이 바닥을 휩쓺 + 둘레 반짝
        Tasks.later(124, () -> { if (alive(p)) AjaVoice.say(p, UltimateForm.S_TRANSFORM3); });   // 아름답다… 이 눈부심…
        Tasks.later(130, () -> {
            if (!alive(p)) return;
            for (int q = 0; q < RAINBOW.length; q++) WorldFx.wave(w, p.getPos(), 0.5 + q * 0.6, 22 + q * 3, 30, RAINBOW[q]);
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 2.0f, 0.6f);
        });
        for (int t = 132; t < 156; t += 4) Tasks.later(t, () -> {
            if (!alive(p)) return;
            Vec3d c = p.getPos().add((w.random.nextDouble() - 0.5) * 6, 0.5 + w.random.nextDouble() * 2.5, (w.random.nextDouble() - 0.5) * 6);
            WorldFx.glint(w, c, 0.8, RAINBOW[w.random.nextInt(RAINBOW.length)]);
        });
        // 157 바-앙
        Tasks.later(157, () -> {
            if (!alive(p)) return;
            UltimateForm.goldBurst(w, p, 1.6f);
            for (int q = 0; q < 3; q++) WorldFx.ring(w, p.getPos().add(0, 1.2, 0), new Vec3d(0, 1, 0), 0.6, 5 + q * 2.5, 0.35, 12 + q * 2, RAINBOW[q]);
            Fx.word(w, p.getPos().add(0, 2.9, 0).add(fw.multiply(0.4)), Fx.Sfx.DOGOOON, 2.4f);
            for (ServerPlayerEntity o : s.getPlayerManager().getPlayerList()) {
                o.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(p.getName().getString() + " — 태양을 넘어선 자").formatted(Formatting.GRAY)));
                o.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("완전생물").formatted(Formatting.GOLD, Formatting.BOLD)));
                o.playSoundToPlayer(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 0.5f);
                o.playSoundToPlayer(SoundEvents.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.MASTER, 0.8f, 0.6f);
            }
        });
        // 190~218 역광 속 빛나는 눈
        for (int t = 190; t < 218; t += 4) Tasks.later(t, () -> { if (alive(p)) eyes(w, p, fw, rt, 1.62, 0.5, 0xFFFFFF); });
        // 220~250 해를 향해 손을 뻗음 (몸을 돌려 동쪽을 봄 — 손이 해와 겹침)
        Tasks.later(220, () -> { if (alive(p)) AjaVoice.say(p, UltimateForm.S_TRANSFORM4); });   // 마침내 저 태양을… 극복했다!
        for (int t = 230; t < 250; t += 5) Tasks.later(t, () -> { if (alive(p)) WorldFx.glint(w, p.getPos().add(0, 2.45, 0).add(fw.multiply(-0.45)).add(rt.multiply(-0.3)), 1.3, 0xFFF0C0); });
        // 252~266 어둠 속 눈
        for (int t = 252; t < 266; t += 4) Tasks.later(t, () -> { if (alive(p)) eyes(w, p, fw, rt, 1.62, 0.4, 0xFF4040); });
        // 268 날개 → 272~280 떠오름
        Tasks.later(268, () -> { if (alive(p)) UltimateForm.wingBurst(w, p); });
        for (int t = 268; t < SCENE_LEN; t += 10) Tasks.later(t, () -> { if (alive(p)) Motion.play(p, "#wing:on"); });
        for (int t = 272; t < 280; t++) Tasks.later(t, () -> { if (alive(p)) { Combat.move(p, new Vec3d(0, 0.32, 0)); UltimateForm.feathers(w, p.getPos().add(0, 1, 0), 4, 0.6); } });
        Tasks.later(280, () -> { if (alive(p)) p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 80, 0, false, false)); });
        // 296~320 반짝이 속에서 내려다봄 + 웃음
        Tasks.later(292, () -> { if (alive(p)) AjaVoice.say(p, UltimateForm.S_TRANSFORM5); });
        for (int t = 296; t < SCENE_LEN; t += 4) Tasks.later(t, () -> {
            if (!alive(p)) return;
            Vec3d c = p.getPos().add(0, 1.6, 0).add(rt.multiply((w.random.nextDouble() - 0.5) * 1.6)).add(0, (w.random.nextDouble() - 0.3) * 1.2, 0);
            WorldFx.glint(w, c, 0.6, w.random.nextBoolean() ? 0x9CFF7A : 0xFFFFFF);
        });
        Tasks.later(SCENE_LEN, () -> {
            SCENE.remove(p.getUuid());
            if (!alive(p)) return;
            p.sendMessage(Text.literal("🔴 완전생물이 되었다 — 햇빛 면역 · 최대 체력 +20 · 1시간에 한 번 불멸. 높이 320 위나 공허에서 죽으면 끝.").formatted(Formatting.GOLD, Formatting.BOLD), false);
            p.sendMessage(Text.literal("   기술: 휘광 칼날 · 날개 강습 · 파문 역류 · 생명 창조(무리·날개) · 변형 팔 · 포식 — /bbc skills").formatted(Formatting.GRAY), false);
        });
    }

    /** 두 눈이 빛남 (눈 높이 h, 앞 0.27) */
    static void eyes(ServerWorld w, ServerPlayerEntity p, Vec3d fw, Vec3d rt, double h, double size, int rgb) {
        for (int sd = -1; sd <= 1; sd += 2) WorldFx.glint(w, p.getPos().add(0, h, 0).add(fw.multiply(0.27)).add(rt.multiply(0.12 * sd)), size, rgb);
    }

    /** 변신 대사는 컷신(화면 기준)으로 */
    static final class AjaVoice {
        static void say(ServerPlayerEntity p, net.minecraft.sound.SoundEvent s) { bbc.job.alchemy.AlchemySounds.voice(p, s, List.of(p)); }
    }

    /** 영원히 끝 — 인간으로 */
    static void ending(ServerPlayerEntity p) {
        MinecraftServer s = p.getServer();
        PlayerData.Entry e = Jobs.entry(p);
        if (!UltimateForm.is(e)) return;
        CombatMode.set(p, false);
        Jobs.quit(p);
        G(s).remove("ult");
        dirty(s);
        clear(p);
        s.getPlayerManager().broadcast(Text.literal("🔴 완전생물 " + p.getName().getString() + "은(는) 돌아오지 못하는 곳으로 떨어졌다… 그리고 생각하는 것을 그만두었다.").formatted(Formatting.DARK_RED, Formatting.ITALIC), false);
    }

    static boolean doomZone(ServerPlayerEntity p) { return p.getY() > 320 || p.getY() < p.getServerWorld().getBottomY() - 2; }

    // ───────── 능력 ─────────

    public static void setFlight(ServerPlayerEntity p, boolean on) {
        if (p.isCreative() || p.isSpectator()) return;
        p.getAbilities().allowFlying = on;
        p.getAbilities().setFlySpeed(on ? 0.085f : 0.05f);   // 날개: 기본 비행보다 빠르게
        if (!on) p.getAbilities().flying = false;
        p.sendAbilitiesUpdate();
        Jobs.entry(p).flag(P + "flying", on);
        if (!on) Motion.play(p, "#wing:off");
    }

    static void ensureHp(ServerPlayerEntity p, boolean on) {
        EntityAttributeInstance hp = p.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (hp == null) return;
        boolean has = hp.getModifier(HP_ID) != null;
        if (on && !has) hp.addPersistentModifier(new EntityAttributeModifier(HP_ID, 20, EntityAttributeModifier.Operation.ADD_VALUE));
        else if (!on && has) { hp.removeModifier(HP_ID); if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth()); }
    }

    /** 서버의 완전생물 자리를 비움 (이 사람이었으면) */
    static void release(ServerPlayerEntity p) {
        if (p.getServer() != null && p.getUuid().equals(ultimate(p.getServer()))) { G(p.getServer()).remove("ult"); dirty(p.getServer()); }
    }

    /** 직업을 그만두거나 단계가 바뀔 때 */
    static void clear(ServerPlayerEntity p) {
        release(p);
        ensureHp(p, false);
        if (Jobs.entry(p).flag(P + "flying")) setFlight(p, false);
    }

    /** 매초 (VampireJob.tick 에서, 모든 뱀파이어) */
    static void second(ServerPlayerEntity p, PlayerData.Entry e, int stage) {
        if (stage != 4) {
            release(p);
            ensureHp(p, false);
            if (e.flag(P + "flying") && !p.isCreative() && !p.isSpectator()) setFlight(p, false);
            return;
        }
        ensureHp(p, true);
        long now = System.currentTimeMillis();
        Long hurt = LAST_HURT.get(p.getUuid());
        if ((hurt == null || now - hurt > 10_000) && (now / 1000) % 5 == 0 && p.getHealth() < p.getMaxHealth() && !DORMANT.containsKey(p.getUuid())) {
            p.setHealth(p.getMaxHealth());
            p.getServerWorld().spawnParticles(RED, p.getX(), p.getY() + 1, p.getZ(), 15, 0.3, 0.6, 0.3, 0);
        }
        UltimateForm.second(p);
    }

    static void protect(ItemEntity ie) {
        ie.setInvulnerable(true);
        ie.setNeverDespawn();
        ie.setGlowing(true);
    }

    private static int clock = 0;

    public static void register() {
        UltimateForm.register();
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (!(entity instanceof ServerPlayerEntity p) || taken <= 0) return;
            LAST_HURT.put(p.getUuid(), System.currentTimeMillis());
            if (RITUAL.containsKey(p.getUuid()) && !source.isOf(net.minecraft.entity.damage.DamageTypes.GENERIC_KILL)) {   // 의식 중엔 받는 피해 2배
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
            if (RITUAL.containsKey(p.getUuid())) { failRitual(p, "쓰러짐"); return true; }
            PlayerData.Entry e = Jobs.entry(p);
            if (!UltimateForm.is(e)) return true;
            if (doomZone(p)) { Tasks.later(1, () -> ending(p)); return true; }
            long now = System.currentTimeMillis();
            if (now - (long) e.num(P + "immortal") < 3_600_000L) return true;
            e.num(P + "immortal", now);
            Jobs.dirty(p);
            p.setHealth(1);
            DORMANT.put(p.getUuid(), now + 10_000);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 200, 0, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 200, 9, false, false));
            p.getServerWorld().spawnParticles(RED, p.getX(), p.getY() + 1, p.getZ(), 80, 0.5, 1, 0.5, 0);
            p.sendMessage(Text.literal("🔴 몸이 흩어졌다가… 다시 모인다 (10초 · 다음 불멸은 1시간 뒤)").formatted(Formatting.DARK_RED), false);
            Tasks.later(200, () -> {
                DORMANT.remove(p.getUuid());
                if (!p.isAlive() || p.isDisconnected()) return;
                p.setHealth(p.getMaxHealth());
                p.removeStatusEffect(StatusEffects.INVISIBILITY);
                p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.5f, 1f);
            });
            return false;
        });
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> {
            ServerPlayerEntity p = h.getPlayer();
            if (RITUAL.containsKey(p.getUuid())) failRitual(p, "자리를 떠남");
        });
        // 바닥에 떨어진 적석은 무적 + 사라지지 않음
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> { if (entity instanceof ItemEntity ie && ie.getStack().isOf(AJA_STONE)) protect(ie); });
        ServerTickEvents.END_SERVER_TICK.register(UltimateLife::tick);
    }

    private static void tick(MinecraftServer s) {
        if (++clock % 20 != 0) return;
        for (UUID id : new ArrayList<>(RITUAL.keySet())) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(id);
            if (p == null) { RITUAL.remove(id); continue; }
            ritualTick(p, RITUAL.get(id));
        }
        // 완전생물 위치: 10분마다
        UUID ult = ultimate(s);
        if (ult != null && clock % 12000 == 0) {
            ServerPlayerEntity p = s.getPlayerManager().getPlayer(ult);
            if (p != null) s.getPlayerManager().broadcast(Text.literal("🔴 완전생물 " + p.getName().getString() + "의 기척: " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ()
                    + " (" + p.getServerWorld().getRegistryKey().getValue().getPath() + ")").formatted(Formatting.DARK_RED), false);
        }
        ajaTick(s);
    }

    // ───────── 에이자의 적석 ─────────

    static boolean ajaConditions(MinecraftServer s) {
        ServerWorld ow = s.getOverworld();
        if (!ow.isThundering()) return false;
        if ((ow.getTimeOfDay() / 24000L) % 8 != 0) return false;   // 보름달
        if (s.getPlayerManager().getPlayerList().size() < 4) return false;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            PlayerData.Entry e = Jobs.entry(p);
            if (VampireJob.stage(e) == 3 && mastery(e) >= 100) return true;
        }
        return false;
    }

    private static void ajaTick(MinecraftServer s) {
        NbtCompound g = G(s);
        ServerWorld ow = s.getOverworld();
        long t = ow.getTimeOfDay() % 24000;
        boolean found = false;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            if (!holds(p)) continue;
            found = true;
            if (!g.getBoolean("aja.picked")) {
                g.putBoolean("aja.picked", true);
                removeSpawn(s, false);
                s.getPlayerManager().broadcast(Text.literal("🔴 누군가 에이자의 적석을 손에 넣었다…").formatted(Formatting.RED, Formatting.BOLD), false);
                dirty(s);
            }
            if (clock % 2400 == 0) s.getPlayerManager().broadcast(Text.literal("🔴 에이자의 적석이 붉게 빛난다: " + p.getBlockX() + ", " + p.getBlockY() + ", " + p.getBlockZ()).formatted(Formatting.RED), false);
        }
        if (!found && (g.contains("aja.spawn") || g.getBoolean("aja.picked"))) {
            for (ServerWorld w : s.getWorlds()) {
                for (Entity e : w.iterateEntities()) {
                    if (!(e instanceof ItemEntity ie) || !ie.getStack().isOf(AJA_STONE)) continue;
                    if (ie.getY() < w.getBottomY() + 2) {
                        BlockPos sp = ow.getSpawnPos();
                        ie.teleport(ow, sp.getX() + 0.5, ow.getTopY(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ()) + 1, sp.getZ() + 0.5, java.util.Set.of(), 0, 0);
                    }
                    for (ServerPlayerEntity p : w.getPlayers()) {   // 128칸 안 플레이어에게 붉은 빛기둥
                        if (p.squaredDistanceTo(ie) > 128 * 128) continue;
                        for (int y = 0; y < 60; y += 2) w.spawnParticles(p, RED, true, ie.getX(), ie.getY() + y, ie.getZ(), 1, 0.1, 0.5, 0.1, 0);
                    }
                }
            }
        }
        // 등장 판정: 자정(18000)에 한 번
        if (!g.getBoolean("aja.picked") && !g.getBoolean("aja.used") && !g.contains("aja.spawn") && t >= 18000 && t < 18020) {
            long day = ow.getTimeOfDay() / 24000L;
            if (g.getLong("aja.rolled") != day + 1 && ajaConditions(s)) {
                g.putLong("aja.rolled", day + 1);
                dirty(s);
                if (new Random().nextInt(100) < 5) spawnAja(s);
                else s.getPlayerManager().broadcast(Text.literal("…어딘가 깊은 곳에서 무언가가 붉게 꿈틀거렸다가, 다시 잠잠해졌다.").formatted(Formatting.DARK_RED, Formatting.ITALIC), false);
            }
        }
        if (g.contains("aja.spawn") && !g.getBoolean("aja.picked") && t >= 23000) removeSpawn(s, true);
    }

    static boolean holds(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) if (p.getInventory().getStack(i).isOf(AJA_STONE)) return true;
        return false;
    }

    static void spawnAja(MinecraftServer s) {
        ServerWorld w = s.getOverworld();
        var players = s.getPlayerManager().getPlayerList();
        if (players.isEmpty()) return;
        Random rnd = new Random();
        ServerPlayerEntity anchor = players.get(rnd.nextInt(players.size()));
        BlockPos near = anchor.getServerWorld() == w ? anchor.getBlockPos() : w.getSpawnPos();
        BlockPos at = w.locateStructure(StructureTags.OCEAN_RUIN, near, 60, false);
        int y;
        if (at != null) {
            w.getChunk(at.getX() >> 4, at.getZ() >> 4);
            y = w.getTopY(Heightmap.Type.OCEAN_FLOOR, at.getX(), at.getZ());
        } else {   // 바다 유적이 없는 지도: 200~600칸 떨어진 땅
            double a = rnd.nextDouble() * Math.PI * 2, r = 200 + rnd.nextDouble() * 400;
            at = near.add((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r));
            w.getChunk(at.getX() >> 4, at.getZ() >> 4);
            y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, at.getX(), at.getZ());
        }
        ItemEntity ie = new ItemEntity(w, at.getX() + 0.5, y + 0.5, at.getZ() + 0.5, new ItemStack(AJA_STONE));
        ie.setVelocity(0, 0, 0);
        protect(ie);
        w.spawnEntity(ie);
        NbtCompound c = new NbtCompound();
        c.putUuid("id", ie.getUuid());
        c.putInt("x", at.getX()); c.putInt("z", at.getZ());
        G(s).put("aja.spawn", c);
        dirty(s);
        for (ServerPlayerEntity p : players) p.playSoundToPlayer(SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.MASTER, 1f, 0.5f);
        s.getPlayerManager().broadcast(Text.literal("🔴 어딘가에서 붉은 빛이 하늘로 치솟는다… (동이 트기 전에 찾아라)").formatted(Formatting.RED, Formatting.BOLD), false);
        Bbc.LOG.info("[aja] 에이자의 적석 등장: {} {} {}", at.getX(), y, at.getZ());
    }

    static void removeSpawn(MinecraftServer s, boolean despawn) {
        NbtCompound c = G(s).getCompound("aja.spawn");
        G(s).remove("aja.spawn");
        dirty(s);
        if (!despawn || c.isEmpty()) return;
        Entity e = s.getOverworld().getEntity(c.getUuid("id"));
        if (e != null) e.discard();
        s.getPlayerManager().broadcast(Text.literal("…동이 트자 붉은 빛이 사그라졌다.").formatted(Formatting.DARK_RED, Formatting.ITALIC), false);
    }

    /** (관리자) 적석 주기 */
    public static void give(ServerPlayerEntity p) {
        p.getInventory().offerOrDrop(new ItemStack(AJA_STONE));
        G(p.getServer()).putBoolean("aja.picked", true);
        dirty(p.getServer());
    }

    static boolean alive(ServerPlayerEntity p) { return !p.isDisconnected() && p.isAlive(); }
}
