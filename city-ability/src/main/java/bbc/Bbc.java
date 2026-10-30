package bbc;

import bbc.combat.Combat;
import bbc.combat.CombatMode;
import bbc.core.Commands;
import bbc.core.Tasks;
import bbc.job.Jobs;
import bbc.job.Stamina;
import bbc.job.TraineeJob;
import bbc.net.CombatStatePayload;
import bbc.net.KeyPayload;
import bbc.net.MotionPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 도시 능력자 서버 모드 진입점 */
public class Bbc implements ModInitializer {
    public static final String MOD_ID = "bbc";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(KeyPayload.ID, KeyPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CombatStatePayload.ID, CombatStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(MotionPayload.ID, MotionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(bbc.net.StunPayload.ID, bbc.net.StunPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(bbc.net.ComboPayload.ID, bbc.net.ComboPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(KeyPayload.ID,
                (payload, ctx) -> ctx.server().execute(() -> CombatMode.onKey(ctx.player(), payload.action())));

        Jobs.register(TraineeJob.INSTANCE);

        Tasks.register();
        Stamina.register();
        Combat.register();
        CombatMode.register();
        Commands.register();
        LOG.info("BBC 도시 능력자 로드됨");
    }
}
