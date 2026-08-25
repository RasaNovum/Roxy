package net.rasanovum.roxyhost;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.rasanovum.roxy.compat.RoxyPowerGridCompat;
import net.rasanovum.roxy.patch.RoxyVoxyHierarchySweep;
import net.rasanovum.roxy.patch.RoxyVoxyLifecycle;
import net.rasanovum.roxy.loader.RoxyFabricRuntime;

@Mod("voxy")
public final class RoxyVoxyNeoForge {
    public RoxyVoxyNeoForge(IEventBus modBus) {
        modBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onRegisterClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onEntityLeaveLevel);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        RoxyFabricRuntime.initializeFabricEntrypoints();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (event.getDispatcher().getRoot().getChild("voxy") == null) {
            try {
                Class<?> commands = Class.forName("me.cortex.voxy.client.VoxyCommands");
                Object command = commands.getMethod("register").invoke(null);
                event.getDispatcher().register((LiteralArgumentBuilder) command);
            } catch (ReflectiveOperationException | LinkageError exception) {
                System.err.println("Roxy: unable to register the Voxy client command: " + exception);
            }
        }
        if (event.getDispatcher().getRoot().getChild("roxy") == null) {
            event.getDispatcher().register(
                    LiteralArgumentBuilder.<CommandSourceStack>literal("roxy")
                            .then(LiteralArgumentBuilder.<CommandSourceStack>literal("fixStaleLoDs")
                                    .executes(RoxyVoxyNeoForge::fixStaleLoDs))
            );
        }
    }

    private static int fixStaleLoDs(CommandContext<CommandSourceStack> context) {
        boolean requested = RoxyVoxyHierarchySweep.requestManualSweep();
        context.getSource().sendSuccess(
                () -> Component.literal(requested
                        ? "Started background stale LoD fix"
                        : "Voxy is not ready for a stale LoD fix"),
                false
        );
        return requested ? 1 : 0;
    }

    private void onRenderLevelStage(RenderLevelStageEvent event) {
        RoxyPowerGridCompat.render(event);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        RoxyVoxyLifecycle.tick();
    }

    private void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        RoxyPowerGridCompat.markServerRemoval(event.getEntity(), event.getLevel());
    }
}
