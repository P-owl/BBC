package bbc.job.alchemy;

import bbc.core.Tasks;
import bbc.core.ButtonMenu;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.text.DecimalFormat;

/** 등가교환 규칙: 분해(아이템 → 에너지) · 연성(에너지 → 아이템) */
public final class Alchemy {
    private Alchemy() {}

    public record Result(int count, double energy, String problem) {}

    public static boolean hasStone(ServerPlayerEntity p) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            if (p.getInventory().getStack(i).isOf(AlchemyItems.PHILOSOPHERS_STONE)) return true;
        }
        return false;
    }

    /** 직업 등급·현자의 돌을 반영한 연성 비용 배율 */
    public static double multiplier(ServerPlayerEntity p) {
        return 1.0 + Alch.tax(Alch.entry(p), hasStone(p));
    }

    public static double unitCost(ServerPlayerEntity p, Item item) {
        return AlchemyValues.valueOf(item) * multiplier(p);
    }

    /**
     * stack 을 분해한다. 분해된 개수만큼 stack 에서 빠진다(decrement).
     * 하루 한도에 걸리면 일부만 분해되고 나머지는 stack 에 남는다.
     */
    public static Result deposit(ServerPlayerEntity p, ItemStack stack) {
        String problem = AlchemyValues.depositProblem(stack);
        if (problem != null) return new Result(0, 0, problem);
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        double unit = AlchemyValues.valueOf(stack.getItem());
        int n = stack.getCount();
        double cap = Alch.dailyCap(e);
        if (cap > 0) {
            double used = e.gainedToday(cap);
            if (used >= cap) {
                double over = used - cap;
                return new Result(0, 0, "오늘 분해 한도(" + fmt(cap) + " E)를 다 썼습니다"
                        + (over > 1 ? " (이월된 초과분 " + fmt(over) + " E 포함)" : "") + ". 내일 다시 오세요");
            }
            // 남은 한도 안에서 분해. 1개 가치가 남은 한도보다 크면 1개만 받고, 넘친 만큼은 다음 날 한도에서 빠짐
            double left = cap - used;
            n = (int) Math.max(1, Math.min(n, Math.floor(left / unit + 1e-9)));
        }
        double gain = unit * n;
        e.energy += gain;
        e.gainedToday += gain;
        e.depositedTotal += gain;
        e.known.add(AlchemyValues.id(stack.getItem()));
        // 같은 계열 전체를 이해 (예: 화석 하나 → 모든 화석)
        for (String fam : AlchemyValues.familiesOf(stack.getItem())) {
            if (e.known.add("#" + fam)) {
                int size = AlchemyValues.itemsOf(fam).size();
                p.sendMessage(net.minecraft.text.Text.literal("✦ [" + fam + "] 계열 " + size + "종을 이해했습니다! 이제 모두 연성할 수 있습니다.")
                        .formatted(net.minecraft.util.Formatting.LIGHT_PURPLE), false);
            }
        }
        data.markDirty();
        stack.decrement(n);
        return new Result(n, gain, null);
    }

    /** item 을 최대 want 개 연성해서 지급. 에너지가 모자라면 살 수 있는 만큼만 */
    public static Result transmute(ServerPlayerEntity p, Item item, int want) {
        if (!AlchemyValues.canOutput(item)) return new Result(0, 0, "연성할 수 없는 아이템입니다");
        AlchemyData data = AlchemyData.get(p.getServer());
        AlchemyData.Entry e = data.of(p.getUuid());
        if (!AlchemyValues.isKnown(e, item)) return new Result(0, 0, "아직 이해하지 못한 아이템입니다. 먼저 한 번 분해하세요");
        double unit = unitCost(p, item);
        int n = (int) Math.min(want, Math.floor((e.energy + 1e-6) / unit));
        if (n <= 0) return new Result(0, 0, "에너지가 부족합니다 (1개에 " + fmt(unit) + " E 필요)");
        double cost = unit * n;
        e.energy = Math.max(0, e.energy - cost);
        Alch.addXp(p, e, cost);
        data.markDirty();
        ItemStack out = new ItemStack(item, n);
        while (!out.isEmpty()) {
            ItemStack part = out.split(Math.min(out.getMaxCount(), out.getCount()));
            p.getInventory().offerOrDrop(part);
        }
        return new Result(n, cost, null);
    }

    // ───────── 표시 ─────────

    private static final DecimalFormat INT = new DecimalFormat("#,##0");
    private static final DecimalFormat DEC = new DecimalFormat("#,##0.##");

    public static String fmt(double v) { return v >= 100 ? INT.format(Math.floor(v)) : DEC.format(v); }

    // ───────── 연출 ─────────

    private static final DustParticleEffect BLUE = new DustParticleEffect(new Vector3f(0.35f, 0.75f, 1.0f), 0.8f);
    private static final DustParticleEffect RED = new DustParticleEffect(new Vector3f(1.0f, 0.15f, 0.1f), 0.8f);

    /** 연성진 위에 원이 번쩍 */
    public static void fx(ServerWorld w, BlockPos pos, boolean stone) {
        Vec3d c = Vec3d.ofBottomCenter(pos).add(0, 0.1, 0);
        DustParticleEffect col = BLUE; // 연성 임팩트는 전부 푸른색 (현자 계열 붉은 연출은 나중에 따로)
        for (int i = 0; i < 28; i++) {
            double a = Math.PI * 2 * i / 28;
            w.spawnParticles(col, c.x + Math.cos(a) * 0.9, c.y, c.z + Math.sin(a) * 0.9, 1, 0, 0, 0, 0);
        }
        w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.3, c.z, 8, 0.3, 0.3, 0.3, 0.2);
        w.playSound(null, pos, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 0.6f, 1.2f);
    }

    /** 큰 연성 연출 (푸른 전기가 몸 전체에서 1초간 튀고 발밑에 원) — 입문·의식 등 */
    public static void bigFx(ServerPlayerEntity p) {
        ServerWorld w0 = p.getServerWorld();
        w0.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1.0f, 1.4f);
        for (int t = 0; t < 20; t += 2) {
            Tasks.later(t, () -> {
                if (p.isRemoved()) return;
                ServerWorld w = p.getServerWorld();
                Vec3d c = p.getPos().add(0, 1.0, 0);
                w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y, c.z, 10, 0.5, 0.8, 0.5, 0.35);
                w.spawnParticles(BLUE, c.x, c.y, c.z, 6, 0.4, 0.7, 0.4, 0);
            });
        }
        Tasks.later(20, () -> {
            if (p.isRemoved()) return;
            ServerWorld w = p.getServerWorld();
            Vec3d b = p.getPos().add(0, 0.1, 0);
            for (int i = 0; i < 48; i++) {
                double a = Math.PI * 2 * i / 48;
                w.spawnParticles(BLUE, b.x + Math.cos(a) * 1.5, b.y, b.z + Math.sin(a) * 1.5, 1, 0, 0, 0, 0);
            }
            w.playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        });
    }

    public static void depositFx(ServerWorld w, BlockPos pos) {
        Vec3d c = Vec3d.ofBottomCenter(pos).add(0, 0.2, 0);
        w.spawnParticles(ParticleTypes.ENCHANT, c.x, c.y + 0.4, c.z, 20, 0.4, 0.3, 0.4, 0.6);
        w.playSound(null, pos, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.5f, 1.4f);
    }

    static void sound(ServerWorld w, Vec3d at, String id, float vol) {
        Identifier sid = Identifier.tryParse(id);
        if (sid == null) return;
        RegistryEntry<SoundEvent> e = RegistryEntry.of(SoundEvent.of(sid));
        long seed = w.getRandom().nextLong();
        for (ServerPlayerEntity pl : w.getPlayers()) {
            if (pl.squaredDistanceTo(at) > 24 * 24) continue;
            pl.networkHandler.sendPacket(new PlaySoundS2CPacket(e, SoundCategory.BLOCKS, at.x, at.y, at.z, vol, 1.0f, seed));
        }
    }
}
