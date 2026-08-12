package net.rasanovum.roxyhost;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.rasanovum.roxy.loader.RoxyFabricRuntime;

@Mod("voxy")
public final class RoxyVoxyNeoForge {
    public RoxyVoxyNeoForge(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        RoxyFabricRuntime.initializeFabricEntrypoints();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (event.getDispatcher().getRoot().getChild("voxy") != null) return;
        try {
            Class<?> commands = Class.forName("me.cortex.voxy.client.VoxyCommands");
            Object command = commands.getMethod("register").invoke(null);
            event.getDispatcher().register((LiteralArgumentBuilder) command);
        } catch (ReflectiveOperationException | LinkageError exception) {
            System.err.println("Roxy: unable to register the Voxy client command: " + exception);
        }
    }
}
