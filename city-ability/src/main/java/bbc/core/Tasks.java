package bbc.core;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** n틱 뒤 실행. (server.execute 는 같은 틱에 소비돼서 지연이 안 됨) */
public final class Tasks {
    private record Task(long due, Runnable run) {}
    private static final ConcurrentLinkedQueue<Task> INCOMING = new ConcurrentLinkedQueue<>();
    private static final List<Task> ACTIVE = new ArrayList<>();
    private static long tick = 0;

    private Tasks() {}

    public static void later(int ticks, Runnable r) { INCOMING.add(new Task(tick + ticks, r)); }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register((MinecraftServer server) -> {
            tick++;
            Task t;
            while ((t = INCOMING.poll()) != null) ACTIVE.add(t);
            List<Task> due = new ArrayList<>();
            for (int i = ACTIVE.size() - 1; i >= 0; i--) {
                if (ACTIVE.get(i).due() <= tick) due.add(ACTIVE.remove(i));
            }
            for (Task d : due) {
                try { d.run().run(); } catch (Exception e) { bbc.Bbc.LOG.error("delayed task failed", e); }
            }
        });
    }
}
