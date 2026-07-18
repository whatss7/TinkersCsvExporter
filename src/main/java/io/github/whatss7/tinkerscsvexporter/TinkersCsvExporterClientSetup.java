package io.github.whatss7.tinkerscsvexporter;

import net.minecraftforge.common.MinecraftForge;

/**
 * Performs client-only setup for this mod. It is invoked from the main mod
 * constructor via {@link net.minecraftforge.fml.DistExecutor#safeRunWhenOn} so
 * that the registration below only runs on the physical client.
 * <p>
 * The setup subscribes {@link MaterialsLoadedEventHandler#onMaterialsLoaded}
 * to the Forge event bus, which in turn registers this mod's client commands when
 * the command registration event fires.
 */
public class TinkersCsvExporterClientSetup {
    /**
     * Registers the client command registrar as a listener on the Forge event bus.
     * Called once during client-side mod construction.
     */
    public static void setup() {
        MinecraftForge.EVENT_BUS.addListener(
                MaterialsLoadedEventHandler::onMaterialsLoaded
        );
    }
}
