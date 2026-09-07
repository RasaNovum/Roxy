package net.rasanovum.roxy.mixin.tfc;

import net.rasanovum.roxyhost.tfc.RoxyTfcAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.dries007.tfc.util.calendar.Calendar", remap = false)
public abstract class RoxyTfcCalendarMixin {
    @Inject(method = "<clinit>", at = @At("RETURN"), remap = false)
    private static void roxy$installCalendarHook(CallbackInfo callback) {
        RoxyTfcAdapter.calendarHookInstalled();
    }

    @Inject(method = "getCalendarTicks()J", at = @At("HEAD"), cancellable = true, remap = false)
    private void roxy$calendarTicks(CallbackInfoReturnable<Long> callback) {
        long[] snapshot = RoxyTfcAdapter.scopedCalendar();
        if (snapshot != null) callback.setReturnValue(snapshot[0]);
    }

    @Inject(method = "getCalendarDaysInMonth()I", at = @At("HEAD"), cancellable = true, remap = false)
    private void roxy$calendarDays(CallbackInfoReturnable<Integer> callback) {
        long[] snapshot = RoxyTfcAdapter.scopedCalendar();
        if (snapshot != null) callback.setReturnValue((int) snapshot[1]);
    }
}
