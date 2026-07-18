package io.github.whatss7.tinkerscsvexporter;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Main mod entry point. The {@link Mod} annotation registers this class with
 * Forge using the mod id declared in {@link #MOD_ID}.
 */
@Mod(TinkersCsvExporter.MOD_ID)
public class TinkersCsvExporter {
    /**
     * Unique identifier of this mod, used by Forge and for namespacing.
     */
    public static final String MOD_ID = "tinkerscsvexporter";

    /**
     * Default constructor invoked by Forge during mod construction. Schedules the
     * client-only setup through {@link net.minecraftforge.fml.DistExecutor#safeRunWhenOn}
     * so that client-side command registration only runs on the physical client.
     */
    public TinkersCsvExporter() {
        // Set this mod to client-only
        ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.fml.ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(
                        () -> FMLNetworkConstants.IGNORESERVERONLY,
                        (serverVersion, isRemote) -> true
                )
        );

        // Run client-only setup
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> TinkersCsvExporterClientSetup::setup);
    }
}
