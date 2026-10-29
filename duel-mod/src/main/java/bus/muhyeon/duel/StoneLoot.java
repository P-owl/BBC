package bus.muhyeon.duel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.LootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.util.Identifier;

/**
 * 관동 챔피언(그린) 첫 승리 보상에 현자의 돌 1개 추가.
 * COBBLEVERSE RCT 데이터팩의 보상표를 덮어쓰지 않고 이벤트로 끼워 넣어서
 * 데이터팩 순서와 상관없이 동작한다.
 */
public final class StoneLoot {
    private static final Identifier TABLE = Identifier.of("rctmod", "trainers/single/kanto_champion_blue");
    /** RCT 데이터팩과 같은 조건: 처음 이겼을 때 1번만 */
    private static final String FIRST_WIN =
            "{\"condition\":\"rctmod:defeat_count\",\"comparator\":\"==\",\"count\":1}";

    private StoneLoot() {}

    public static void register() {
        LootTableEvents.MODIFY.register((key, builder, source, registries) -> {
            if (!key.getValue().equals(TABLE)) return;
            JsonObject json = JsonParser.parseString(FIRST_WIN).getAsJsonObject();
            var parsed = LootCondition.CODEC.parse(registries.getOps(JsonOps.INSTANCE), json);
            if (parsed.result().isEmpty()) {
                // 조건을 못 만들면 매번 지급되는 걸 막기 위해 아예 추가하지 않음
                DuelMod.LOG.error("현자의 돌 보상 조건을 만들지 못했습니다 (RCT 모드 확인): {}", parsed.error());
                return;
            }
            LootCondition firstWin = parsed.result().get();
            builder.pool(LootPool.builder()
                    .rolls(ConstantLootNumberProvider.create(1))
                    .with(ItemEntry.builder(DuelMod.PHILOSOPHERS_STONE))
                    .conditionally(() -> firstWin));
            DuelMod.LOG.info("관동 챔피언 보상에 현자의 돌을 추가했습니다.");
        });
    }
}
