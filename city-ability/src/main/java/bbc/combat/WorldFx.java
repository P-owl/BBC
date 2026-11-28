package bbc.combat;

import bbc.net.WorldFxPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;

/**
 * 세상에 그릴 이펙트 모델을 근처 플레이어(96칸)에게 보냄 — 그리는 쪽은 client/FxShapes.
 * 예전엔 서버가 블록 디스플레이 판 수십 장을 띄워 칼자국·고리를 만들었는데, 이제 클라이언트가 매끈한 띠·고리·가시·빛줄기로 그림
 * (엔티티를 만들지 않아 서버도 가벼움). 색은 0xRRGGBB.
 */
public final class WorldFx {
    private WorldFx() {}

    public static void send(ServerWorld w, Vec3d at, String spec) {
        WorldFxPayload pl = new WorldFxPayload(spec);
        for (ServerPlayerEntity p : w.getPlayers()) {
            if (p.squaredDistanceTo(at) > 96 * 96 || !ServerPlayNetworking.canSend(p, WorldFxPayload.ID)) continue;
            ServerPlayNetworking.send(p, pl);
        }
    }

    static String n(double v) { return String.format(Locale.ROOT, "%.2f", v); }
    static String v(Vec3d v) { return n(v.x) + "," + n(v.y) + "," + n(v.z); }

    /** 블록 색 → 이펙트 색 (자주 쓰는 유리·콘크리트는 밝게 고른 색, 나머지는 지도 색) */
    public static int rgb(BlockState s) {
        if (s == null) return 0xFFFFFF;
        if (s.isOf(Blocks.WHITE_STAINED_GLASS) || s.isOf(Blocks.WHITE_CONCRETE) || s.isOf(Blocks.GLASS)) return 0xFFFFFF;
        if (s.isOf(Blocks.YELLOW_STAINED_GLASS)) return 0xFFD84A;
        if (s.isOf(Blocks.LIGHT_BLUE_STAINED_GLASS)) return 0x9AD8FF;
        if (s.isOf(Blocks.BLUE_STAINED_GLASS)) return 0x5A8CFF;
        if (s.isOf(Blocks.CYAN_STAINED_GLASS)) return 0x6FE6E6;
        if (s.isOf(Blocks.PURPLE_STAINED_GLASS)) return 0xB57CE6;
        if (s.isOf(Blocks.MAGENTA_STAINED_GLASS) || s.isOf(Blocks.PINK_STAINED_GLASS)) return 0xFF8AD0;
        if (s.isOf(Blocks.RED_STAINED_GLASS) || s.isOf(Blocks.RED_CONCRETE)) return 0xFF4A3A;
        if (s.isOf(Blocks.ORANGE_STAINED_GLASS) || s.isOf(Blocks.ORANGE_CONCRETE)) return 0xFF9A30;
        if (s.isOf(Blocks.LIME_STAINED_GLASS)) return 0x9AF06A;
        if (s.isOf(Blocks.BLACK_CONCRETE) || s.isOf(Blocks.BLACK_STAINED_GLASS)) return 0x101010;
        if (s.isOf(Blocks.GRAY_STAINED_GLASS)) return 0x9A9A9A;
        int c = s.getMapColor(null, BlockPos.ORIGIN).color;
        return c == 0 ? 0xFFFFFF : c;
    }

    // ───────── 모양 ─────────

    /** 칼자국: o 에서 앞 f 쪽 반원 호 (tilt 0 = 가로, 90 = 세로), reverse 면 반대로 그어짐 */
    public static void slash(ServerWorld w, Vec3d o, Vec3d f, double radius, double tilt, boolean reverse, int rgb) {
        send(w, o, "slash:" + v(o) + "," + n(f.x) + "," + n(f.z) + "," + n(radius) + "," + n(tilt) + "," + (reverse ? 1 : 0) + "," + rgb);
    }
    /** 바닥 충격파: r0 → r1 로 퍼지며 얇아짐 */
    public static void wave(ServerWorld w, Vec3d c, double r0, double r1, int life, int rgb) {
        send(w, c, "wave:" + v(c) + "," + n(r0) + "," + n(r1) + "," + life + "," + rgb);
    }
    /** 고리: axis 에 수직인 면에서 r0 → r1 */
    public static void ring(ServerWorld w, Vec3d c, Vec3d axis, double r0, double r1, double width, int life, int rgb) {
        send(w, c, "ring:" + v(c) + "," + v(axis) + "," + n(r0) + "," + n(r1) + "," + n(width) + "," + life + "," + rgb);
    }
    /** 파열 가시: dir 쪽으로 더 길게 (dir 없으면 0,0,0) */
    public static void burst(ServerWorld w, Vec3d c, Vec3d dir, double size, int main, int accent) {
        Vec3d d = dir == null ? Vec3d.ZERO : dir;
        send(w, c, "burst:" + v(c) + "," + v(d) + "," + n(size) + "," + main + "," + accent + "," + w.getRandom().nextInt(100000));
    }
    public static void flash(ServerWorld w, Vec3d c, double size) { send(w, c, "flash:" + v(c) + "," + n(size)); }
    public static void ink(ServerWorld w, Vec3d c, int count, double size) { send(w, c, "ink:" + v(c) + "," + count + "," + n(size) + "," + w.getRandom().nextInt(100000)); }
    public static void swirl(ServerWorld w, Vec3d c, int count, double size) { send(w, c, "swirl:" + v(c) + "," + count + "," + n(size) + "," + w.getRandom().nextInt(100000)); }
    /** 날아가는 몸 뒤 속도선 (클라이언트가 몸을 따라가며 그림) */
    public static void lines(ServerWorld w, Entity e, int ticks) { send(w, e.getPos(), "lines:" + e.getId() + "," + ticks); }
    /** 드롭킥 줄기: 맞은 자리에서 차는 쪽으로 긴 흰 원뿔 + 고리 */
    public static void kick(ServerWorld w, Vec3d at, Vec3d dir) { send(w, at, "kick:" + v(at) + "," + v(dir) + "," + w.getRandom().nextInt(100000)); }
    /** 굵은 선이 점들을 이음 (혈관 등). glow = 빛나게 */
    public static void path(ServerWorld w, List<Vec3d> pts, double width, int life, int rgb, boolean glow) {
        if (pts.size() < 2) return;
        StringBuilder b = new StringBuilder("path:").append(n(width)).append(',').append(life).append(',').append(rgb).append(',').append(glow ? 1 : 0);
        for (Vec3d q : pts) b.append(',').append(v(q));
        send(w, pts.get(pts.size() / 2), b.toString());
    }
    /** 나선 (바람 소용돌이): from 에서 dir 로 len, 반지름 r, turns 바퀴 */
    public static void helix(ServerWorld w, Vec3d from, Vec3d dir, double len, double r, double turns, double phase, int life, int rgb, double width) {
        send(w, from, "helix:" + v(from) + "," + v(dir) + "," + n(len) + "," + n(r) + "," + n(turns) + "," + n(phase) + "," + life + "," + rgb + "," + n(width));
    }
    /** 빛줄기: a → b (가운데 밝고 둘레 번짐) */
    public static void beam(ServerWorld w, Vec3d a, Vec3d b, double width, int life, int rgb) {
        send(w, a.lerp(b, 0.5), "beam:" + v(a) + "," + v(b) + "," + n(width) + "," + life + "," + rgb);
    }
    /** 반짝 (네 갈래 빛줄기 별) */
    public static void glint(ServerWorld w, Vec3d c, double size, int rgb) { send(w, c, "glint:" + v(c) + "," + n(size) + "," + rgb + "," + w.getRandom().nextInt(100000)); }
    /** 번개 한 줄기 a → b */
    public static void bolt(ServerWorld w, Vec3d a, Vec3d b, int life, int rgb) { send(w, a, "bolt:" + v(a) + "," + v(b) + "," + life + "," + rgb); }
    /** 사방으로 튀는 번개 (연성 반응 등): c 둘레 반지름 r 안에 n 줄기, life 틱 동안 깜빡임 */
    public static void sparks(ServerWorld w, Vec3d c, double r, int count, int life, int rgb) { send(w, c, "sparks:" + v(c) + "," + n(r) + "," + count + "," + life + "," + rgb); }
    /** 연성진 (바닥 원 + 삼각·육각 무늬 + 글자 눈금이 돌며 빛남). style 0 = 기본 연성, 1 = 현자의 돌(붉은), 2 = 큰 의식 */
    public static void circle(ServerWorld w, Vec3d c, double radius, int life, int rgb, int style) {
        send(w, c, "circle:" + v(c) + "," + n(radius) + "," + life + "," + rgb + "," + style);
    }
    /** 김·연기 덩어리 (반투명하게 부풀며 사라짐) */
    public static void puff(ServerWorld w, Vec3d c, double size, int life, int rgb) { send(w, c, "puff:" + v(c) + "," + n(size) + "," + life + "," + rgb + "," + w.getRandom().nextInt(100000)); }
}
