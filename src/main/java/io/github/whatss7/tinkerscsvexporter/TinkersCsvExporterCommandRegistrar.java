package io.github.whatss7.tinkerscsvexporter;

import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Listens on the Forge event bus and registers this mod's client-side commands
 * when Minecraft fires the command registration event.
 */
@Mod.EventBusSubscriber(modid = TinkersCsvExporter.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class TinkersCsvExporterCommandRegistrar {
    /**
     * Called by Forge when client commands should be registered. Delegates the
     * actual command building to {@link TinkersCsvExporterExportCommand}.
     *
     * @param event the event carrying the command dispatcher to register into
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        TinkersCsvExporterExportCommand.register(event.getDispatcher());
    }
}
