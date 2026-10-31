package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 진리의 문.
 * 새하얀 차원(bbc:truth, 짙은 흰 안개 + 바닥 없는 공허)에 거대한 돌문을 세우고,
 * 통행료를 치르면 문이 열리며 검은 안쪽이 쏟아져 나온 뒤 원래 자리로 돌아온다.
 */
public final class TruthGate {
    private TruthGate() {}

    public static final RegistryKey<net.minecraft.world.World> WORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("bbc", "truth"));

    private static final int Y = 100;              // 바닥 높이
    private static final int HALF_W = 6;           // 문 너비 13
    private static final int H = 19;               // 문 높이
    private static final DustParticleEffect BLACK = new DustParticleEffect(new Vector3f(0.02f, 0.02f, 0.02f), 2.0f);

    /** 대가를 이미 치른 뒤 호출. 연출이 끝나면 원래 자리로 돌려보낸다 */
    public static void begin(ServerPlayerEntity p, String toll) { begin(p, toll, false); }

    /** test = true 면 연출만 (공지·안내 메시지 없음) */
    public static void begin(ServerPlayerEntity p, String toll, boolean test) {
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        e.retDim = p.getServerWorld().getRegistryKey().getValue().toString();
        e.retX = p.getX(); e.retY = p.getY(); e.retZ = p.getZ();
        data.markDirty();

        ServerWorld truth = p.getServer().getWorld(WORLD);
        int ox = (Math.abs(p.getUuid().hashCode()) % 64) * 256; // 사람마다 다른 자리에 문을 세움

        // 0초: 연성진이 번쩍이며 끌려감
        Alchemy.bigFx(p);
        p.getServerWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_END_PORTAL_SPAWN, SoundCategory.PLAYERS, 0.8f, 0.6f);
        title(p, "", "…!?", 5, 30, 10);

        if (truth == null) {
            // 데이터팩 차원을 못 찾으면 연출만 하고 끝
            bbc.Bbc.LOG.error("[truth] dimension bbc:truth not loaded — 연출 없이 진행합니다");
            Tasks.later(40, () -> finish(p, toll, test));
            return;
        }

        final int END = 900;
        List<Entity> props = new ArrayList<>();
        Tasks.later(30, () -> {
            if (p.isDisconnected()) return;
            build(truth, ox);
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, END, 10, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.JUMP_BOOST, END, 200, false, false)); // 점프 못 함
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, END, 10, false, false));
            p.teleport(truth, ox + 0.5, Y, 16.5, 180f, -8f);
            truth.playSound(null, BlockPos.ofFloored(ox, Y, 14), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.0f, 0.5f);
        });

        // ① 정적 — 하얀 공간과 거대한 문
        line(p, 70, "……", "여긴… 어디지?");
        line(p, 130, "", "……누군가 이쪽을 보고 있다");

        // ② 진리가 문 앞에 앉은 채로 나타남 (흰 실루엣)
        Tasks.later(170, () -> {
            if (gone(p, truth)) return;
            Vec3d at = new Vec3d(ox + 0.5, Y, 9.5);
            for (int i = 0; i < 40; i++) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                truth.spawnParticles(ParticleTypes.END_ROD, at.x + r.nextGaussian() * 2, at.y + r.nextDouble() * 3, at.z + r.nextGaussian() * 2, 1, 0, 0, 0, 0.02);
            }
            props.addAll(figure(truth, at, null));
            truth.playSound(null, BlockPos.ofFloored(at), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.5f, 0.5f);
            truth.playSound(null, BlockPos.ofFloored(at), SoundEvents.AMBIENT_CAVE.value(), SoundCategory.PLAYERS, 1.0f, 0.6f);
        });
        Entity[] speech = {null};
        final int D = 225; // 대화가 길어진 만큼 뒤 연출을 미룸
        say(p, truth, props, speech, ox, 200, "……또 하나, 문을 두드린 자가 왔군.");
        line(p, 270, "", "……넌 누구지?");
        say(p, truth, props, speech, ox, 330, "이름이라면 너희가 이미 여럿 붙여 두었지. 세계, 우주, 신… 무엇이든 상관없다.");
        say(p, truth, props, speech, ox, 405, "다만 알아 둬라. 나는 처음부터 네 안에 있었다.");
        say(p, truth, props, speech, ox, 480, "인간은 늘 같은 것을 바라지. 더 알고, 더 가지고, 끝내 신의 자리에 닿기를.");
        line(p, 555, "", "……진리를 알고 싶어.");
        say(p, truth, props, speech, ox, 615, "좋다. 문을 연 자에게 보여주는 것, 그게 나의 일이니.");
        say(p, truth, props, speech, ox, 690, "단, 무언가를 얻으려면 같은 것을 내놓아야 한다. 너희가 등가교환이라 부르는 법칙이지.");

        // ③ 문이 가운데부터 천천히 열리고 거대한 눈이 뜸
        for (int i = 0; i <= HALF_W; i++) {
            final int col = i;
            Tasks.later(520 + D + i * 8, () -> {
                if (gone(p, truth)) return;
                open(truth, ox, col);
                truth.playSound(null, BlockPos.ofFloored(ox, Y + 8, 0), SoundEvents.BLOCK_PISTON_CONTRACT, SoundCategory.BLOCKS, 2.0f, 0.3f);
            });
        }
        Tasks.later(520 + D, () -> truth.playSound(null, BlockPos.ofFloored(ox, Y + 8, 0), SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.2f, 0.4f));
        Tasks.later(580 + D, () -> {
            if (gone(p, truth)) return;
            props.addAll(eye(truth, ox));
            truth.playSound(null, BlockPos.ofFloored(ox, Y + 9, 0), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 2.0f, 0.5f);
            title(p, "", "문 안에서 무언가가 이쪽을 본다", 5, 40, 10);
        });

        // ④ 검은 손들이 뻗어 나와 붙잡고 문 안으로 끌고 감
        for (int h = 0; h < 6; h++) {
            final int hand = h;
            Vec3d[] tip = {new Vec3d(ox + 0.5 + (h - 2.5) * 1.6, Y + 3 + (h % 3) * 3, -0.2)};
            for (int t = 0; t < 50; t++) {
                final int tick = t;
                Tasks.later(620 + D + hand * 4 + t, () -> {
                    if (gone(p, truth)) return;
                    Vec3d target = p.getPos().add((hand - 2.5) * 0.3, 0.6 + (hand % 3) * 0.4, 0);
                    Vec3d dir = target.subtract(tip[0]);
                    if (dir.length() < 0.6) return;
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    Vec3d step = dir.normalize().multiply(0.7).add(r.nextGaussian() * 0.15, r.nextGaussian() * 0.15, 0);
                    Vec3d next = tip[0].add(step);
                    Entity seg = StonePillarFx.segment(truth, tip[0], next, Blocks.BLACK_CONCRETE.getDefaultState(), 0.35f);
                    if (seg != null) props.add(seg);
                    tip[0] = next;
                    if (tick % 6 == 0) truth.spawnParticles(BLACK, next.x, next.y, next.z, 4, 0.2, 0.2, 0.2, 0);
                });
            }
        }
        Tasks.later(640 + D, () -> { if (!gone(p, truth)) title(p, "", "검은 손이 몸을 붙잡는다", 5, 50, 10); });
        for (int t = 690 + D; t < 790 + D; t += 2) {
            final int k = t;
            Tasks.later(t, () -> {
                if (gone(p, truth)) return;
                double z = 16.5 - (k - 690 - D) / 100.0 * 15.5; // 문 쪽으로 끌려감
                p.requestTeleport(ox + 0.5, Y, z);
                if (k % 10 == 0) truth.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_TENDRIL_CLICKS, SoundCategory.PLAYERS, 1.0f, 0.6f);
            });
        }

        // ⑤ 문 안: 지식이 쏟아져 들어옴
        Tasks.later(790 + D, () -> {
            if (gone(p, truth)) return;
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 140, 0, false, false));
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 120, 0, false, false));
            truth.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 1.0f, 0.6f);
        });
        String[] flood = {"이해", "분해", "재구축", "등가교환", "물질", "생명", "영혼", "정신", "세계", "우주", "하나는 전부", "전부는 하나", "진리"};
        for (int i = 0; i < flood.length; i++) {
            final String word = flood[i];
            final int at = 790 + D + i * 7;
            Tasks.later(at, () -> {
                if (gone(p, truth)) return;
                p.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 6, 2));
                p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal("")));
                p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(word).formatted(Formatting.WHITE, Formatting.BOLD)));
                truth.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, 0.6f + (at - 790 - D) / 90f);
                truth.spawnParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 1, p.getZ(), 60, 1.5, 1.5, 1.5, 1.0);
            });
        }

        // ⑥ 다시 하얀 공간 — 진리가 통행료를 몸에 지니고 있음
        Tasks.later(890 + D, () -> {
            if (gone(p, truth)) return;
            p.requestTeleport(ox + 0.5, Y, 14.5);
            p.removeStatusEffect(StatusEffects.DARKNESS);
            p.removeStatusEffect(StatusEffects.NAUSEA);
            for (Entity pe : props) pe.discard();
            props.clear();
            close(truth, ox);
            props.addAll(figure(truth, new Vec3d(ox + 0.5, Y, 9.5), toll));
            title(p, "진리를 보았다", "", 10, 50, 20);
            truth.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.PLAYERS, 1.0f, 0.6f);
        });
        say(p, truth, props, speech, ox, 960 + D, tollLine(toll));
        say(p, truth, props, speech, ox, 1030 + D, "그것이 네가 원한 진리다. 그리고 네가 치른 대가이기도 하지.");
        say(p, truth, props, speech, ox, 1105 + D, "돌아가라, 연금술사. 네가 잃은 것이 곧 네가 배운 것이다.");
        Tasks.later(1180 + D, () -> {
            for (Entity pe : props) pe.discard();
            if (speech[0] != null) speech[0].discard();
            finish(p, toll, test);
        });
    }

    /** 통행료를 받아 간 뒤 진리가 비웃는 말 (부위별) */
    private static String tollLine(String toll) {
        return switch (toll) {
            case "arm" -> "통행료로 네 팔을 받았다. 진리를 움켜쥐려던 손이니, 그 값으로 충분하겠지.";
            case "leg" -> "통행료로 네 다리를 받았다. 신의 자리까지 걸어가려던 발이었으니.";
            case "heart" -> "통행료로 네 심장의 일부를 받았다. 알고자 하는 욕심이 그만큼 컸던 거다.";
            default -> "이번엔 아무것도 받지 않겠다. 다음은 없을 거다.";
        };
    }

    private static boolean gone(ServerPlayerEntity p, ServerWorld truth) {
        return p.isDisconnected() || p.getServerWorld() != truth;
    }

    // ───────── 진리 (앉아 있는 흰 실루엣) ─────────

    /**
     * 진리: 양반다리로 앉아 고개를 갸웃한 흰 실루엣 + 검은 윤곽선 + 귀까지 찢어진 웃음.
     * toll 이 있으면 받아 간 부위만 '사람 살' 색으로 지니고 있음. (오른쪽 = -X, 이쪽(+Z)을 바라봄)
     */
    private static List<Entity> figure(ServerWorld w, Vec3d at, String toll) {
        BlockState body = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState skin = Blocks.ORANGE_TERRACOTTA.getDefaultState();
        BlockState black = Blocks.BLACK_CONCRETE.getDefaultState();
        boolean arm = "arm".equals(toll), leg = "leg".equals(toll), heart = "heart".equals(toll);
        List<Entity> l = new ArrayList<>();
        Vec3d o = at;
        org.joml.Quaternionf none = new org.joml.Quaternionf();

        // 몸통 (살짝 앞으로 숙임)
        org.joml.Quaternionf lean = new org.joml.Quaternionf().rotateX((float) Math.toRadians(10));
        l.add(part(w, body, o, new org.joml.Vector3f(0, 0.16f, 0), 0.62f, 0.28f, 0.42f, none, true));   // 골반
        l.add(part(w, body, o, new org.joml.Vector3f(0, 0.42f, 0), 0.58f, 0.76f, 0.32f, lean, true));   // 가슴
        if (heart) l.add(part(w, Blocks.RED_STAINED_GLASS.getDefaultState(), o, new org.joml.Vector3f(-0.08f, 0.78f, 0.21f), 0.16f, 0.16f, 0.04f, lean, false));
        l.add(part(w, body, o, new org.joml.Vector3f(0, 1.15f, 0.12f), 0.17f, 0.13f, 0.17f, none, true)); // 목

        // 머리 (갸웃) + 웃는 입
        org.joml.Quaternionf tilt = new org.joml.Quaternionf().rotateZ((float) Math.toRadians(-14)).rotateX((float) Math.toRadians(6));
        org.joml.Vector3f head = new org.joml.Vector3f(0, 1.26f, 0.14f);
        l.add(part(w, body, o, head, 0.5f, 0.5f, 0.48f, tilt, true));
        for (int i = -3; i <= 3; i++) {
            float x = i * 0.065f;
            float y = 0.15f + 2.4f * x * x;                     // 입꼬리가 올라간 호
            org.joml.Vector3f m = new org.joml.Vector3f(x, y, 0.245f).rotate(tilt).add(head);
            l.add(part(w, black, o, m, 0.07f, 0.045f, 0.02f, tilt, false));
        }

        // 다리: 허벅지는 앞·바깥으로, 정강이는 앞에서 엇갈림
        for (int side : new int[]{-1, 1}) {
            boolean taken = leg && side < 0;
            BlockState st = taken ? skin : body;
            org.joml.Vector3f hip = new org.joml.Vector3f(side * 0.17f, 0.17f, 0.02f);
            org.joml.Vector3f thighDir = new org.joml.Vector3f(side * 0.48f, 0, 0.88f).normalize();
            l.add(limb(w, st, o, hip, thighDir, 0.6f, 0.25f));
            org.joml.Vector3f knee = new org.joml.Vector3f(thighDir).mul(0.6f).add(hip);
            org.joml.Vector3f shinDir = new org.joml.Vector3f(-side * 0.93f, 0, -0.36f).normalize();
            l.add(limb(w, st, o, new org.joml.Vector3f(knee.x, knee.y + (side > 0 ? 0.06f : 0), knee.z), shinDir, 0.62f, 0.22f));
        }

        // 팔: 어깨에서 내려와 팔꿈치를 무릎에 걸침
        for (int side : new int[]{-1, 1}) {
            boolean taken = arm && side < 0;
            BlockState st = taken ? skin : body;
            org.joml.Vector3f sh = new org.joml.Vector3f(side * 0.36f, 1.08f, 0.14f);
            org.joml.Vector3f upDir = new org.joml.Vector3f(side * 0.12f, -0.78f, 0.62f).normalize();
            l.add(limb(w, st, o, sh, upDir, 0.5f, 0.17f));
            org.joml.Vector3f el = new org.joml.Vector3f(upDir).mul(0.5f).add(sh);
            org.joml.Vector3f foreDir = new org.joml.Vector3f(-side * 0.25f, -0.2f, 0.95f).normalize();
            l.add(limb(w, st, o, el, foreDir, 0.42f, 0.15f));
            org.joml.Vector3f hand = new org.joml.Vector3f(foreDir).mul(0.42f).add(el);
            l.add(limb(w, st, o, hand, new org.joml.Vector3f(0, -0.4f, 1).normalize(), 0.16f, 0.15f));
        }
        l.removeIf(java.util.Objects::isNull);
        return l;
    }

    /** base 에서 dir 방향으로 뻗는 팔다리 토막 */
    private static Entity limb(ServerWorld w, BlockState st, Vec3d o, org.joml.Vector3f base, org.joml.Vector3f dir, float len, float thick) {
        org.joml.Quaternionf rot = new org.joml.Quaternionf().rotationTo(0, 1, 0, dir.x, dir.y, dir.z);
        return part(w, st, o, base, thick, len, thick, rot, true);
    }

    /** 회전된 상자 디스플레이. outline = 검은 윤곽선(발광) */
    private static Entity part(ServerWorld w, BlockState st, Vec3d o, org.joml.Vector3f base, float sx, float sy, float sz,
                               org.joml.Quaternionf rot, boolean outline) {
        org.joml.Vector3f c = new org.joml.Vector3f(sx / 2, 0, sz / 2).rotate(new org.joml.Quaternionf(rot));
        org.joml.Vector3f t = new org.joml.Vector3f(base).sub(c);
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(st));
        NbtCompound tr = new NbtCompound();
        tr.put("translation", floats(t.x, t.y, t.z));
        tr.put("left_rotation", floats(rot.x, rot.y, rot.z, rot.w));
        tr.put("scale", floats(sx, sy, sz));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        n.put("transformation", tr);
        n.putFloat("view_range", 3.0f);
        if (outline) {
            n.putBoolean("Glowing", true);
            n.putInt("glow_color_override", 0x161616);
        }
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> { ent.refreshPositionAndAngles(o.x, o.y, o.z, 0, 0); return ent; });
        if (e != null) w.spawnEntity(e);
        return e;
    }

    /** 열린 문 안쪽의 거대한 눈 */
    private static List<Entity> eye(ServerWorld w, int ox) {
        List<Entity> l = new ArrayList<>();
        double x = ox + 0.5, y = Y + 9, z = -0.9;
        BlockState white = Blocks.WHITE_CONCRETE.getDefaultState();
        l.add(box(w, white, x, y - 0.8, z, 6.0f, 1.6f, 0.1f));
        l.add(box(w, white, x, y - 1.3, z, 4.2f, 2.6f, 0.1f));
        l.add(box(w, white, x, y - 1.6, z, 2.2f, 3.2f, 0.1f));
        l.add(box(w, Blocks.RED_CONCRETE.getDefaultState(), x, y - 1.1, z + 0.05, 1.8f, 2.0f, 0.1f));
        l.add(box(w, Blocks.BLACK_CONCRETE.getDefaultState(), x, y - 0.7, z + 0.1, 0.9f, 1.2f, 0.1f));
        return l;
    }

    /** 발밑 가운데 기준 상자 디스플레이 */
    private static Entity box(ServerWorld w, BlockState state, double x, double y, double z, float sx, float sy, float sz) {
        NbtCompound n = new NbtCompound();
        n.putString("id", "minecraft:block_display");
        n.put("block_state", NbtHelper.fromBlockState(state));
        NbtCompound t = new NbtCompound();
        t.put("translation", floats(-sx / 2, 0, -sz / 2));
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("scale", floats(sx, sy, sz));
        t.put("right_rotation", floats(0, 0, 0, 1));
        n.put("transformation", t);
        n.putFloat("view_range", 3.0f);
        Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> { ent.refreshPositionAndAngles(x, y, z, 0, 0); return ent; });
        if (e != null) w.spawnEntity(e);
        return e;
    }

    /** 진리의 머리 위 말풍선 + 자막 */
    private static void say(ServerPlayerEntity p, ServerWorld w, List<Entity> props, Entity[] speech, int ox, int at, String msg) {
        Tasks.later(at, () -> {
            if (gone(p, w)) return;
            if (speech[0] != null) speech[0].discard();
            NbtCompound n = new NbtCompound();
            n.putString("id", "minecraft:text_display");
            n.putString("text", Text.Serialization.toJsonString(Text.literal(msg).formatted(Formatting.BLACK), w.getRegistryManager()));
            n.putString("billboard", "center");
            n.putInt("background", 0);
            n.putInt("line_width", 220);
            Entity e = EntityType.loadEntityWithPassengers(n, w, ent -> { ent.refreshPositionAndAngles(ox + 0.5, Y + 2.4, 9.5, 0, 0); return ent; });
            if (e != null) { w.spawnEntity(e); speech[0] = e; }
            title(p, "진리", msg, 10, 70, 10);
            w.playSound(null, BlockPos.ofFloored(ox, Y, 9), SoundEvents.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM, SoundCategory.PLAYERS, 0.8f, 0.4f);
        });
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }

    private static void finish(ServerPlayerEntity p, String toll, boolean test) {
        if (p.isDisconnected()) return;
        returnHome(p);
        p.removeStatusEffect(StatusEffects.SLOWNESS);
        p.removeStatusEffect(StatusEffects.JUMP_BOOST);
        Alch.applyToll(p);
        Alchemy.fx(p.getServerWorld(), p.getBlockPos(), true);
        if (test) {
            p.sendMessage(Text.literal("[테스트] 진리의 문 연출이 끝났습니다. (대가·등급 변화 없음)").formatted(Formatting.GRAY), false);
            return;
        }
        p.sendMessage(Text.literal("✦ 진리를 보았습니다. 이제 연성진 없이 연성할 수 있습니다.").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
        p.sendMessage(Text.literal("  G 키(손뼉 연성) → 어디서든 연성 화면 (또는 /alchemy open)").formatted(Formatting.GRAY), false);
        p.sendMessage(Text.literal("  치른 대가: " + Alch.tollName(toll)).formatted(Formatting.DARK_RED), false);
        p.getServer().getPlayerManager().broadcast(Text.literal("✦ " + p.getName().getString() + "님이 진리의 문을 열고 진리를 보았습니다.")
                .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
    }

    /** 접속했을 때 아직 진리의 공간에 있으면 원래 자리로 */
    public static void rescue(ServerPlayerEntity p) {
        if (p.getServerWorld().getRegistryKey() != WORLD) return;
        returnHome(p);
        p.removeStatusEffect(StatusEffects.SLOWNESS);
        p.removeStatusEffect(StatusEffects.JUMP_BOOST);
    }

    private static void returnHome(ServerPlayerEntity p) {
        AlchemyData.Entry e = Alch.entry(p);
        ServerWorld dest = null;
        Identifier id = Identifier.tryParse(e.retDim);
        if (id != null) dest = p.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
        if (dest == null || dest.getRegistryKey() == WORLD) {
            dest = p.getServer().getOverworld();
            BlockPos s = dest.getSpawnPos();
            p.teleport(dest, s.getX() + 0.5, s.getY(), s.getZ() + 0.5, p.getYaw(), p.getPitch());
            return;
        }
        if (p.getServerWorld() != dest || p.squaredDistanceTo(e.retX, e.retY, e.retZ) > 4) {
            p.teleport(dest, e.retX, e.retY, e.retZ, p.getYaw(), p.getPitch());
        }
    }

    // ───────── 건축 ─────────

    /** 새하얀 바닥 + 거대한 돌문 (문짝은 z=0, 그 뒤 z=-1 은 칠흑) */
    private static void build(ServerWorld w, int ox) {
        BlockState floor = Blocks.WHITE_CONCRETE.getDefaultState();
        for (int x = -14; x <= 14; x++)
            for (int z = -6; z <= 24; z++)
                w.setBlockState(new BlockPos(ox + x, Y - 1, z), floor);

        BlockState frame = Blocks.STONE_BRICKS.getDefaultState();
        BlockState carve = Blocks.CHISELED_STONE_BRICKS.getDefaultState();
        BlockState dark = Blocks.BLACK_CONCRETE.getDefaultState();
        for (int x = -HALF_W - 2; x <= HALF_W + 2; x++) {
            for (int y = 0; y <= H + 2; y++) {
                for (int z = -2; z <= 0; z++) {
                    BlockPos bp = new BlockPos(ox + x, Y + y, z);
                    boolean outer = Math.abs(x) > HALF_W || y > H;
                    if (outer) {
                        // 기둥·상인방: 바깥 틀, 모서리와 줄무늬는 조각 석재
                        boolean accent = (y % 4 == 0) || y == H + 2 || Math.abs(x) == HALF_W + 2 && y % 2 == 0;
                        w.setBlockState(bp, accent ? carve : frame);
                    } else if (z == -2) {
                        w.setBlockState(bp, frame);
                    } else if (z == -1) {
                        w.setBlockState(bp, dark);
                    } else {
                        w.setBlockState(bp, panel(x, y));
                    }
                }
            }
        }
    }

    /** 문짝 무늬: 가운데 세로 이음매 + 원·나무 모양 부조 느낌 */
    private static BlockState panel(int x, int y) {
        if (x == 0) return Blocks.POLISHED_DEEPSLATE.getDefaultState();             // 두 문짝 사이 틈
        int ax = Math.abs(x);
        double cx = ax - 3.0, cy = y - 10.0;
        double r = Math.sqrt(cx * cx + cy * cy * 0.6);
        if (Math.abs(r - 2.6) < 0.6) return Blocks.CHISELED_STONE_BRICKS.getDefaultState(); // 문짝마다 원 문양
        if (ax == 3 && (y < 7 || y > 13)) return Blocks.CHISELED_STONE_BRICKS.getDefaultState(); // 세로 줄기
        if (y == 1 || y == H) return Blocks.POLISHED_ANDESITE.getDefaultState();
        return Blocks.SMOOTH_STONE.getDefaultState();
    }

    /** col 번째 세로줄(가운데에서 바깥으로)을 열어 칠흑을 드러냄 */
    /** 문을 다시 닫음 (새로 세움) */
    private static void close(ServerWorld w, int ox) { build(w, ox); }

    private static void open(ServerWorld w, int ox, int col) {
        for (int y = 0; y <= H; y++) {
            for (int side : new int[]{-1, 1}) {
                int x = col == 0 ? 0 : side * col;
                w.setBlockState(new BlockPos(ox + x, Y + y, 0), Blocks.AIR.getDefaultState());
            }
        }
    }

    // ───────── 자막 ─────────

    private static void line(ServerPlayerEntity p, int at, String title, String sub) {
        Tasks.later(at, () -> { if (!p.isDisconnected()) title(p, title, sub, 10, 50, 10); });
    }

    private static void title(ServerPlayerEntity p, String title, String sub, int in, int stay, int out) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(in, stay, out));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(sub).formatted(Formatting.DARK_GRAY)));
        p.networkHandler.sendPacket(new TitleS2CPacket(Text.literal(title).formatted(Formatting.BLACK, Formatting.BOLD)));
    }
}
