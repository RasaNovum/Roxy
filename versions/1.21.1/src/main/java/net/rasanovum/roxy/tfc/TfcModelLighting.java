package net.rasanovum.roxy.tfc;

public final class TfcModelLighting {
    private TfcModelLighting() {}

    public static int modelKey(int tint) {
        return TfcVoxyBridge.isBakingVariant() && tint == -1 ? -2 : tint;
    }

    public static int flags(int flags) {
        return TfcVoxyBridge.isBakingVariant() ? flags | 16 : flags;
    }
}
