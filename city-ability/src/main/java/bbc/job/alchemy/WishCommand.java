package bbc.job.alchemy;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 현자의 돌 ④ 등가교환의 끝 — /wish <아이템> 으로 일반 아이템·재료 1개 받기 (유물·임플란트·무기·가보·현자의 돌 제외) */
public final class WishCommand {
    private static final Set<UUID> ARMED = ConcurrentHashMap.newKeySet();

    /** 금지 아이템 */
    private static final Set<String> BANNED = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:command_block_minecart", "minecraft:structure_block", "minecraft:structure_void",
            "minecraft:jigsaw", "minecraft:barrier", "minecraft:light", "minecraft:debug_stick",
            "minecraft:bedrock", "minecraft:end_portal_frame", "minecraft:reinforced_deepslate",
            "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:knowledge_book",
            "bbc:philosophers_stone"
    );

    private WishCommand() {}

    public static void arm(ServerPlayerEntity p) { ARMED.add(p.getUuid()); }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                dispatcher.register(CommandManager.literal("wish")
                        .executes(WishCommand::cancel)
                        .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                                .executes(WishCommand::wish))));
    }

    private static int cancel(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        if (ARMED.remove(p.getUuid())) p.sendMessage(Text.literal("소원을 취소했습니다. 돌은 소모되지 않았습니다.").formatted(Formatting.GRAY), false);
        else p.sendMessage(Text.literal("현자의 돌에서 ④ 원하는 아이템을 먼저 고르세요.").formatted(Formatting.GRAY), false);
        return 1;
    }

    private static int wish(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        if (!ARMED.contains(p.getUuid())) {
            p.sendMessage(Text.literal("현자의 돌에서 ④ 원하는 아이템을 먼저 고르세요.").formatted(Formatting.RED), false);
            return 0;
        }
        Item item = ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem();
        String id = Registries.ITEM.getId(item).toString();
        String path = Registries.ITEM.getId(item).getPath();
        // 무기·갑옷·우리 모드 아이템(유물·임플란트 등)·생성 알은 안 됨
        boolean weapon = item instanceof net.minecraft.item.SwordItem || item instanceof net.minecraft.item.AxeItem || item instanceof net.minecraft.item.TridentItem
                || item instanceof net.minecraft.item.MaceItem || item instanceof net.minecraft.item.RangedWeaponItem || item instanceof net.minecraft.item.ArmorItem;
        if (BANNED.contains(id) || weapon || id.startsWith("bbc:") || item instanceof SpawnEggItem) {
            p.sendMessage(Text.literal("[" + id + "] 은(는) 받을 수 없는 아이템입니다. 다른 아이템을 입력하세요.").formatted(Formatting.RED), false);
            return 0;
        }
        if (!PhilosopherStoneItem.consumeOne(p)) {
            ARMED.remove(p.getUuid());
            p.sendMessage(Text.literal("현자의 돌이 없습니다.").formatted(Formatting.RED), false);
            return 0;
        }
        ARMED.remove(p.getUuid());
        // 입력에 붙인 인챈트·컴포넌트는 무시하고 기본 아이템 1개만 지급
        ItemStack stack = new ItemStack(item, 1);
        String name = stack.getName().getString();
        p.getInventory().offerOrDrop(stack);
        StoneFx.big(p);
        p.getWorld().playSound(null, p.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.0f, 1.0f);
        p.sendMessage(Text.literal("✦ 소원이 이루어졌습니다: [" + name + "]").formatted(Formatting.LIGHT_PURPLE), false);
        return 1;
    }
}
