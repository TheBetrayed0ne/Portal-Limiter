package PortalLimiter;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PortalLimiter implements ModInitializer {
    public static final String MOD_ID = "untitled";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        LOGGER.info("Portal Limiter loaded, max open nether portals: {}, max open end portals: {}",
                Config.get().maxOpenPortals, Config.get().maxOpenEndPortals);
        Config.startWatching();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SERVER = server;
            enforcePortalLimits();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SERVER = null);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("portallimiter")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("reload")
                                .executes(context -> {
                                    Config.reload();
                                    Config config = Config.get();
                                    context.getSource().sendFeedback(() -> Text.literal(
                                            "PortalLimiter config reloaded. Max open nether portals: "
                                                    + config.maxOpenPortals + ", max open end portals: " + config.maxOpenEndPortals), true);
                                    return 1;
                                }))));
    }

    /**
     * Closes any portals in excess of the current config limits (per Config#closeOldestFirst),
     * so a reload that lowers a limit below the number of portals already open actually takes
     * effect instead of only blocking new ones. Safe to call before a server is running.
     */
    public static void enforcePortalLimits() {
        MinecraftServer server = SERVER;
        if (server == null) {
            return;
        }
        server.execute(() -> {
            Config config = Config.get();
            boolean oldestFirst = config.closeOldestFirst();

            int closedNether = PortalRegistryState.get(server, PortalRegistryState.NETHER_ID)
                    .enforceLimit(server, config.maxOpenPortals, oldestFirst, (world, pos) -> {
                        if (world.getBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
                            world.setBlockState(pos, Blocks.AIR.getDefaultState());
                        }
                    });
            int closedEnd = PortalRegistryState.get(server, PortalRegistryState.END_ID)
                    .enforceLimit(server, config.maxOpenEndPortals, oldestFirst, (world, pos) -> {
                        if (world.getBlockState(pos).isOf(Blocks.END_PORTAL)) {
                            world.setBlockState(pos, Blocks.AIR.getDefaultState());
                        }
                    });

            if (closedNether > 0 || closedEnd > 0) {
                LOGGER.info("PortalLimiter enforced new limits: closed {} nether portal(s), {} end portal(s)", closedNether, closedEnd);
            }
        });
    }
}
