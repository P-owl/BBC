package bbc.job.alchemy;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/** 연금술사 아이템·블록 (무현버스 duel 모드에서 옮김, 이름공간 bbc) */
public final class AlchemyItems {
    private AlchemyItems() {}

    private static Identifier id(String p) { return Identifier.of("bbc", p); }

    public static final Item PHILOSOPHERS_STONE = Registry.register(Registries.ITEM, id("philosophers_stone"),
            new PhilosopherStoneItem(new Item.Settings().maxCount(16).rarity(Rarity.EPIC).fireproof()
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)));

    /** 등가교환 연성진 (바닥에 설치하는 블록) */
    public static final Block ALCHEMY_CIRCLE = Registry.register(Registries.BLOCK, id("alchemy_circle"),
            new AlchemyCircleBlock(AbstractBlock.Settings.create().mapColor(MapColor.BLACK).strength(1.5f)
                    .nonOpaque().luminance(state -> 7).sounds(BlockSoundGroup.AMETHYST_BLOCK)));

    public static final Item ALCHEMY_CIRCLE_ITEM = Registry.register(Registries.ITEM, id("alchemy_circle"),
            new BlockItem(ALCHEMY_CIRCLE, new Item.Settings().rarity(Rarity.RARE)));

    /** 연성 장갑 (진리 전 연성 기술 사용 조건) */
    public static final Item ALCHEMY_GLOVE = Registry.register(Registries.ITEM, id("alchemy_glove"),
            new AlchemyGloveItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE)));

    /** 오토메일 (진리의 통행료 보완) */
    public static final Item AUTOMAIL_ARM = Registry.register(Registries.ITEM, id("automail_arm"),
            new AutomailItem("arm", new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));
    public static final Item AUTOMAIL_LEG = Registry.register(Registries.ITEM, id("automail_leg"),
            new AutomailItem("leg", new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));
    public static final Item AUTOMAIL_HEART = Registry.register(Registries.ITEM, id("automail_heart"),
            new AutomailItem("heart", new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));

    /** 연성 창 (기술 무기 연성) */
    public static final Item ALCHEMY_SPEAR = Registry.register(Registries.ITEM, id("alchemy_spear"),
            new AlchemySpearItem(new Item.Settings().maxCount(1).maxDamage(AlchemySpearItem.HITS)
                    .rarity(Rarity.RARE).attributeModifiers(AlchemySpearItem.attributes())));

    public static void register() {
        // 클래스 로딩만으로 등록됨 (크리에이티브 탭은 생략)
    }
}
