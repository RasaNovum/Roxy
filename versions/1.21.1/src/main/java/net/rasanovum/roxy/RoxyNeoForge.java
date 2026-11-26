package net.rasanovum.roxy;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.rasanovum.roxy.client.RoxyClientWarnings;
import net.rasanovum.roxy.loader.RoxyCrashReportHeader;
import net.rasanovum.roxy.loader.RoxyFabricRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = "roxy", dist = Dist.CLIENT)
public final class RoxyNeoForge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");

    public RoxyNeoForge(IEventBus modBus) {
        RoxyNeoForgePlatform.ensureConfigured();
        RoxyCrashReportHeader.register();
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(RoxyClientWarnings::onScreenOpening);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        RoxyFabricRuntime.initializeFabricEntrypoints();
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (event.getDispatcher().getRoot().getChild("voxy") != null) return;
        try {
            Class<?> commands = Class.forName("me.cortex.voxy.client.VoxyCommands");
            Object command = commands.getMethod("register").invoke(null);
            event.getDispatcher().register((LiteralArgumentBuilder) command);
        } catch (ReflectiveOperationException | LinkageError exception) {
            LOGGER.warn("Unable to register the Voxy client command", exception);
        }
    }
}
