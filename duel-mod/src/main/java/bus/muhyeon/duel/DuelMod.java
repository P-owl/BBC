package bus.muhyeon.duel;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DuelMod implements ModInitializer {
    public static final String MOD_ID = "duel";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static final Item DUEL_GLOVE = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "duel_glove"),
            new DuelGloveItem(new Item.Settings().maxCount(16).rarity(Rarity.RARE)));

    public static final Item PHILOSOPHERS_STONE = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "philosophers_stone"),
            new PhilosopherStoneItem(new Item.Settings()
                    .maxCount(16)
                    .rarity(Rarity.EPIC)
                    .fireproof()
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)));

    public static final Item TRANSMUTATION_CIRCLE = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "transmutation_circle"),
            new TransmutationCircleItem(new Item.Settings().maxCount(16).rarity(Rarity.EPIC)));

    /** 등가교환 연성진 (바닥에 설치하는 블록) */
    public static final Block ALCHEMY_CIRCLE = Registry.register(
            Registries.BLOCK,
            Identifier.of(MOD_ID, "alchemy_circle"),
            new AlchemyCircleBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.BLACK)
                    .strength(1.5f)
                    .nonOpaque()
                    .luminance(state -> 7)
                    .sounds(BlockSoundGroup.AMETHYST_BLOCK)));

    public static final Item ALCHEMY_CIRCLE_ITEM = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "alchemy_circle"),
            new BlockItem(ALCHEMY_CIRCLE, new Item.Settings().rarity(Rarity.RARE)));

    /** 연성 장갑 (진리 전 연성 기술 사용 조건) */
    public static final Item ALCHEMY_GLOVE = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "alchemy_glove"),
            new AlchemyGloveItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE)));

    /** 오토메일 (진리의 통행료 보완) */
    public static final Item AUTOMAIL_ARM = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "automail_arm"),
            new AutomailItem("arm", new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));
    public static final Item AUTOMAIL_LEG = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "automail_leg"),
            new AutomailItem("leg", new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));
    public static final Item AUTOMAIL_HEART = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "automail_heart"),
            new AutomailItem("heart", new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));

    /** 성수 (신부 기술 2번으로 던짐) */
    public static final Item HOLY_WATER = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "holy_water"),
            new HolyWaterItem(new Item.Settings().maxCount(16).rarity(Rarity.UNCOMMON)));

    /** 석가면 (뱀파이어) */
    public static final Item STONE_MASK = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "stone_mask"),
            new StoneMaskItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));

    /** 고대의 기둥 (기둥 속 사내 각성) */
    public static final Item ANCIENT_PILLAR = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "ancient_pillar"),
            new AncientPillarItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC)));

    /** 도전장 (대통령 결정전) */
    public static final Item CHALLENGE_LETTER = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "challenge_letter"),
            new ChallengeLetterItem(new Item.Settings().maxCount(1).rarity(Rarity.RARE)));

    /** 에이자의 적석 (서버에 하나, 불에 안 탐) */
    public static final Item AJA_STONE = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "aja_stone"),
            new AjaStoneItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));

    /** 연성 창 (전투 3번 무기 연성) */
    public static final Item ALCHEMY_SPEAR = Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "alchemy_spear"),
            new AlchemySpearItem(new Item.Settings().maxCount(1).maxDamage(AlchemySpearItem.HITS)
                    .rarity(Rarity.RARE).attributeModifiers(AlchemySpearItem.attributes())));

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> {
            e.add(DUEL_GLOVE);
            e.add(PHILOSOPHERS_STONE);
            e.add(TRANSMUTATION_CIRCLE);
            e.add(ALCHEMY_CIRCLE_ITEM);
            e.add(ALCHEMY_GLOVE);
            e.add(AUTOMAIL_ARM);
            e.add(AUTOMAIL_LEG);
            e.add(AUTOMAIL_HEART);
            e.add(ALCHEMY_SPEAR);
            e.add(HOLY_WATER);
            e.add(STONE_MASK);
            e.add(ANCIENT_PILLAR);
            e.add(CHALLENGE_LETTER);
        });
        Tasks.register();
        DuelRewards.register();
        WishCommand.register();
        StoneLoot.register();
        AlchemyState.register(); BattleTheme.register(); RaidBoss.register();
        AlchemyValues.register(); AlchemyCommand.register(); AlchemyCommand.registerJob(); Jobs.register(); AlchemySkills.register(); CombatMode.register(); Stamina.register(); AutomailSkill.register();
        // 연금술사 이외 직업 (선택 창 순서대로 등록)
        Markers.register();
        PokemonDoctor.register();
        Priest.register();
        Vampire.register();
        Politics.register();
        JobCommands.register();
    }
}
