package net.rasanovum.roxy.core;

public record RoxyTargetProfile(
        String minecraftVersion,
        LoaderKind loaderKind,
        int targetClassFileVersion,
        SodiumApi sodiumApi,
        IrisApi irisApi,
        RuntimeNamespace runtimeNamespace
) {
    public enum LoaderKind {
        FABRIC,
        NEOFORGE,
        FORGE
    }

    public enum SodiumApi {
        SODIUM_0_5,
        SODIUM_0_8
    }

    public enum IrisApi {
        IRIS_1_7,
        IRIS_1_8
    }

    public enum RuntimeNamespace {
        INTERMEDIARY,
        NAMED,
        MOJMAP,
        SRG
    }

    public static RoxyTargetProfile fabric1211(String runtimeNamespaceName) {
        return new RoxyTargetProfile(
                "1.21.1",
                LoaderKind.FABRIC,
                65,
                SodiumApi.SODIUM_0_8,
                IrisApi.IRIS_1_8,
                parseFabricNamespace(runtimeNamespaceName)
        );
    }

    public static RoxyTargetProfile fabric1201(String runtimeNamespaceName) {
        return new RoxyTargetProfile(
                "1.20.1",
                LoaderKind.FABRIC,
                61,
                SodiumApi.SODIUM_0_5,
                IrisApi.IRIS_1_7,
                parseFabricNamespace(runtimeNamespaceName)
        );
    }

    public static RoxyTargetProfile neoforge1211() {
        return new RoxyTargetProfile(
                "1.21.1",
                LoaderKind.NEOFORGE,
                65,
                SodiumApi.SODIUM_0_8,
                IrisApi.IRIS_1_8,
                RuntimeNamespace.MOJMAP
        );
    }

    public static RoxyTargetProfile neoforge12111() {
        return new RoxyTargetProfile(
                "1.21.11",
                LoaderKind.NEOFORGE,
                65,
                SodiumApi.SODIUM_0_8,
                IrisApi.IRIS_1_8,
                RuntimeNamespace.MOJMAP
        );
    }

    public static RoxyTargetProfile forge1201() {
        return new RoxyTargetProfile(
                "1.20.1",
                LoaderKind.FORGE,
                61,
                SodiumApi.SODIUM_0_5,
                IrisApi.IRIS_1_7,
                RuntimeNamespace.SRG
        );
    }

    public static RoxyTargetProfile forge1192() {
        return new RoxyTargetProfile(
                "1.19.2",
                LoaderKind.FORGE,
                61,
                SodiumApi.SODIUM_0_5,
                IrisApi.IRIS_1_7,
                RuntimeNamespace.SRG
        );
    }

    private static RuntimeNamespace parseFabricNamespace(String name) {
        return "named".equals(name) ? RuntimeNamespace.NAMED : RuntimeNamespace.INTERMEDIARY;
    }

    public boolean requiresJava17Lowering() {
        return targetClassFileVersion <= 61;
    }

    public boolean outputsIntermediary() {
        return runtimeNamespace == RuntimeNamespace.INTERMEDIARY;
    }

    public boolean requiresRuntimeRemap() {
        return runtimeNamespace == RuntimeNamespace.INTERMEDIARY
                || runtimeNamespace == RuntimeNamespace.SRG;
    }
}
