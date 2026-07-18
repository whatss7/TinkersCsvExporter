package io.github.whatss7.tinkerscsvexporter;

import net.minecraftforge.client.event.RegisterClientCommandsEvent;

/**
 * Holds the handler that registers this mod's client-side commands. This class
 * itself does not subscribe to the Forge event bus; the subscription is performed
 * by {@link TinkersCsvExporterClientSetup}, which invokes
 * {@link #onRegisterClientCommands} when Minecraft fires the command registration
 * event.
 */
public class TinkersCsvExporterCommandRegistrar {
    /**
     * Called by Forge when client commands should be registered. Delegates the
     * actual command building to {@link TinkersCsvExporterExportCommand}.
     *
     * @param event the event carrying the command dispatcher to register into
     */
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        TinkersCsvExporterExportCommand.register(event.getDispatcher());
    }
}
