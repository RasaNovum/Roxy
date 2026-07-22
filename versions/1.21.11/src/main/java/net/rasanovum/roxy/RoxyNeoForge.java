package net.rasanovum.roxy;

import net.rasanovum.roxy.loader.RoxyFabricLoaderImpl;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod("roxy")
public final class RoxyNeoForge {
    public RoxyNeoForge(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        RoxyFabricLoaderImpl.initializeFabricEntrypoints();
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        ClientCommandRegistrationCallback.fire(event.getDispatcher(), event.getBuildContext());
    }
}
