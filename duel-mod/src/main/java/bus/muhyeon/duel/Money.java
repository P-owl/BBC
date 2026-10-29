package bus.muhyeon.duel;

import net.minecraft.entity.player.PlayerEntity;

import java.lang.reflect.Method;
import java.math.BigInteger;

/**
 * 코블달러 연동 (CobbleDollars 모드가 있을 때만 동작, 없으면 돈이 필요한 기능은 막힘).
 * 직접 의존하지 않으려고 리플렉션으로 PlayerExtensionKt.get/setCobbleDollars 를 부른다.
 */
public final class Money {
    private Money() {}

    private static Method GET, SET;
    private static boolean tried;

    private static boolean init() {
        if (tried) return GET != null;
        tried = true;
        try {
            Class<?> k = Class.forName("fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt");
            GET = k.getMethod("getCobbleDollars", PlayerEntity.class);
            SET = k.getMethod("setCobbleDollars", PlayerEntity.class, BigInteger.class);
        } catch (Throwable t) {
            DuelMod.LOG.warn("[money] CobbleDollars not found — money features disabled");
            GET = SET = null;
        }
        return GET != null;
    }

    public static boolean available() { return init(); }

    public static long get(PlayerEntity p) {
        if (!init()) return 0;
        try { return ((BigInteger) GET.invoke(null, p)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue(); }
        catch (Throwable t) { return 0; }
    }

    /** 충분하면 빼고 true */
    public static boolean take(PlayerEntity p, long amount) {
        if (amount <= 0) return true;
        if (!init()) return false;
        try {
            BigInteger cur = (BigInteger) GET.invoke(null, p);
            BigInteger a = BigInteger.valueOf(amount);
            if (cur.compareTo(a) < 0) return false;
            SET.invoke(null, p, cur.subtract(a));
            return true;
        } catch (Throwable t) { return false; }
    }

    public static void give(PlayerEntity p, long amount) {
        if (amount <= 0 || !init()) return;
        try { SET.invoke(null, p, ((BigInteger) GET.invoke(null, p)).add(BigInteger.valueOf(amount))); }
        catch (Throwable ignored) {}
    }

    public static String fmt(long v) { return String.format("%,d", v) + " 코블달러"; }
}
