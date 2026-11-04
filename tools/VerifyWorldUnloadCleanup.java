import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.rasanovum.roxy.mixin.RoxyMinecraftLevelMixin;
import net.rasanovum.roxy.patch.RoxyVoxyRenderPatch;
import net.rasanovum.roxy.patch.RoxyVoxyRequestPatch;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class VerifyWorldUnloadCleanup {
    public static void main(String[] args) throws Exception {
        RoxyVoxyRenderPatch.prepareFullInstanceReload();
        RoxyVoxyRequestPatch.reset();

        FakeNodeManager skippedObservationManager = new FakeNodeManager();
        RoxyVoxyRequestPatch.defer(skippedObservationManager, 17L);
        check(requestPendingCount() == 1, "request-only strong state was created before any world observation");
        unloadThroughMinecraftLifecycle(false);
        assertClean("unload clears request-only state when observeWorld was skipped");

        long generationAfterUnload = worldGeneration();
        unloadThroughMinecraftLifecycle(true);
        check(worldGeneration() == generationAfterUnload, "repeated null-world lifecycle callbacks are idempotent");
        assertClean("repeated null-world callback keeps strong state clear");

        RoxyVoxyRenderPatch.activateWorld();
        FakeNodeManager reenteredWithoutObservation = new FakeNodeManager();
        RoxyVoxyRequestPatch.defer(reenteredWithoutObservation, 18L);
        check(requestPendingCount() == 1, "request-only state can recur after a world enters without observeWorld");
        unloadThroughMinecraftLifecycle(true);
        assertClean("disconnect clears state after world entry without observeWorld");

        Object world = new Object();
        FakeRenderer renderer = new FakeRenderer();
        RoxyVoxyRenderPatch.observeWorld(world);
        RoxyVoxyRenderPatch.registerRenderer(renderer);
        FakeTask task = new FakeTask(42L);
        RoxyVoxyRenderPatch.recordRenderTaskCreation(renderer.renderGen, task.position, task, true);
        RoxyVoxyRenderPatch.beginRenderTask(renderer.renderGen, task);
        RoxyVoxyRequestPatch.defer(renderer.nodeManager, task.position);
        check(mapSize("IN_FLIGHT_TASKS") == 1, "in-flight render task is tracked");
        check(mapSize("IN_FLIGHT_COUNTS") == 1, "in-flight position count is tracked");
        check(mapSize("LATEST_EPOCHS") == 1, "latest task epoch is tracked");
        check(mapSize("PENDING") == 1, "request patch retains a pending node request");

        unloadThroughMinecraftLifecycle(false);
        assertClean("Minecraft unload callback retires render and request state");
        check(!worldObserved(), "world observation token is cleared on unload");

        RoxyVoxyRenderPatch.observeWorld(world);
        RoxyVoxyRenderPatch.registerRenderer(renderer);
        FakeTask reenteredTask = new FakeTask(43L);
        RoxyVoxyRenderPatch.recordRenderTaskCreation(renderer.renderGen, reenteredTask.position, reenteredTask, true);
        check(mapSize("QUEUED_TASKS") == 1, "same world identity can be observed again after unload");
        check(worldObserved(), "world observation is restored on reentry");
        RoxyVoxyRequestPatch.defer(renderer.nodeManager, reenteredTask.position);
        unloadThroughMinecraftLifecycle(true);
        assertClean("a later unload clears state created after same-identity reentry");

        RoxyVoxyRenderPatch.prepareFullInstanceReload();
        System.out.println("PASS: skipped-observation, repeated-null, renderer state cleanup, and world reentry");
    }

    private static void unloadThroughMinecraftLifecycle(boolean disconnect) throws Exception {
        if (disconnect) {
            Method callback = RoxyMinecraftLevelMixin.class.getDeclaredMethod(
                        "roxy$retireVoxyWorldOnDisconnect",
                        net.minecraft.client.gui.screens.Screen.class,
                        boolean.class,
                        CallbackInfo.class
                );
            callback.setAccessible(true);
            callback.invoke(new TestLevelMixin(), null, false, new CallbackInfo("disconnect", false));
            return;
        }
        invokeLevelSet(null);
    }

    private static void invokeLevelSet(Object level) throws Exception {
        Method callback = RoxyMinecraftLevelMixin.class.getDeclaredMethod(
                "roxy$clearVoxyWorldgenQueue",
                ClientLevel.class,
                ReceivingLevelScreen.Reason.class,
                CallbackInfo.class
        );
        callback.setAccessible(true);
        callback.invoke(new TestLevelMixin(), level, null, new CallbackInfo("setLevel", false));
    }

    private static void assertClean(String message) throws Exception {
        check(mapSize("IN_FLIGHT_TASKS") == 0, message + ": in-flight tasks");
        check(mapSize("QUEUED_TASKS") == 0, message + ": queued tasks");
        check(mapSize("DIRTY_POSITIONS") == 0, message + ": dirty positions");
        check(mapSize("LATEST_EPOCHS") == 0, message + ": latest task epochs");
        check(mapSize("IN_FLIGHT_COUNTS") == 0, message + ": in-flight position counts");
        check(mapSize("PENDING_RENDER_TASKS") == 0, message + ": pending render tasks");
        check(mapSize("PENDING") == 0, message + ": pending requests");
        check(field(RoxyVoxyRenderPatch.class, "activeRenderGenerationService") == null,
                message + ": active renderer service");
        check((boolean) field(RoxyVoxyRenderPatch.class, "worldRetired"), message + ": retired-world marker");
        check(!worldObserved(), message + ": world observation token");
        check(((WeakReference<?>) field(RoxyVoxyRenderPatch.class, "worldToken")).get() == null,
                message + ": weak world reference");
        check(((WeakReference<?>) field(RoxyVoxyRenderPatch.class, "worldEngineToken")).get() == null,
                message + ": weak world-engine reference");
    }

    private static int requestPendingCount() throws Exception {
        return mapSize("PENDING");
    }

    private static int mapSize(String name) throws Exception {
        Object value = field(name.equals("PENDING")
                ? RoxyVoxyRequestPatch.class
                : RoxyVoxyRenderPatch.class, name);
        if (!(value instanceof Map<?, ?> map)) throw new AssertionError(name + " is not a map");
        if (name.equals("PENDING")) {
            int count = 0;
            for (Object nested : map.values()) count += ((Map<?, ?>) nested).size();
            return count;
        }
        return map.size();
    }

    private static Object field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean worldObserved() throws Exception {
        return (boolean) field(RoxyVoxyRenderPatch.class, "worldObserved");
    }

    private static long worldGeneration() throws Exception {
        return ((AtomicLong) field(RoxyVoxyRenderPatch.class, "WORLD_GENERATION")).get();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestLevelMixin extends RoxyMinecraftLevelMixin {
    }

    public static final class FakeRenderer {
        public final FakeGeneration renderGen = new FakeGeneration();
        public final FakeNodeManager nodeManager = new FakeNodeManager();
    }

    public static final class FakeGeneration {
        public void enqueueTask(long position) {
        }
    }

    public static final class FakeNodeManager {
    }

    public static final class FakeTask {
        private final long position;

        private FakeTask(long position) {
            this.position = position;
        }
    }
}
