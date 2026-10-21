package net.rasanovum.roxy.tfc;

public final class TfcModelLighting {
    private TfcModelLighting() {}

    public static int modelKey(int tint) {
        return TfcVoxyBridge.modelKey(tint);
    }

    public static int modelTint(int tint) { return TfcVoxyBridge.modelTint(tint); }

    public static int flags(int flags) {
        return TfcVoxyBridge.isBakingVariant() ? flags | 32 : flags;
    }
}
