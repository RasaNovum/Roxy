import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import java.util.zip.ZipFile

plugins {
    id("dev.prism")
    id("java-base")
}

group = "net.rasanovum"
version = "0.5.0"

val roxyZink = providers.gradleProperty("roxyZink").map(String::toBoolean).orElse(false)
val macRuntime = providers.gradleProperty("roxyMacOSRuntime")
    .map { file(it) }.orElse(layout.projectDirectory.dir(".roxy-macos/runtime").asFile)
val macNatives = providers.gradleProperty("roxyMacOSNatives")
    .map { file(it) }.orElse(layout.projectDirectory.dir(".roxy-macos/natives").asFile)
val roxyTestWorld = providers.gradleProperty("roxyTestWorld")
val roxyClientProbe = providers.gradleProperty("roxyClientProbe").map(String::toBoolean).orElse(false)

fun JavaExec.useRoxyZink() {
    val runtime = macRuntime.get()
    val nativePath = macNatives.get()
    jvmArgs("-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED", "-Djoml.nounsafe=true",
        "-Dorg.lwjgl.opengl.libname=${runtime.resolve("lib/libGL.dylib")}",
        "-Dorg.lwjgl.egl.libname=${runtime.resolve("lib/libEGL.dylib")}",
        "-Dorg.lwjgl.glfw.libname=${runtime.resolve("lib/libglfw.3.dylib")}",
        "-Droxy.macos.nativePath=$nativePath",
        "-Dvoxy.geometryBufferSizeOverrideMB=${providers.gradleProperty("roxyGeometryMB").orElse("512").get()}")
    environment("MESA_EGL_LIBRARY", runtime.resolve("lib/libEGL.dylib").absolutePath)
    environment("MESA_VULKAN_LIBRARY", runtime.resolve("lib/libvulkan.1.dylib").absolutePath)
    environment("LIBGL_DRIVERS_PATH", runtime.resolve("lib/dri").absolutePath)
    environment("VK_DRIVER_FILES", runtime.resolve("share/vulkan/icd.d/kosmickrisp_icd.json").absolutePath)
    environment("EGL_PLATFORM", "surfaceless")
    environment("MESA_LOADER_DRIVER_OVERRIDE", "zink")
    environment("MESA_KK_EXPERIMENTAL", "custom_border")
    environment("MESA_SHADER_CACHE_DISABLE", "true")
    environment.remove("MESA_GL_VERSION_OVERRIDE")
    environment.remove("MESA_GLSL_VERSION_OVERRIDE")
    environment.remove("MESA_EXTENSION_OVERRIDE")
    if (providers.gradleProperty("roxyDiagnosticGL46").map(String::toBoolean).orElse(false).get()) {
        environment("MESA_GL_VERSION_OVERRIDE", "4.6")
        environment("MESA_GLSL_VERSION_OVERRIDE", "460")
    }
    doFirst {
        check(System.getProperty("os.name").lowercase().contains("mac")
                && System.getProperty("os.arch") in listOf("aarch64", "arm64")) {
            "Roxy's experimental Zink runtime requires a native arm64 macOS JVM."
        }
        listOf("lib/libGL.dylib", "lib/libEGL.dylib", "lib/libglfw.3.dylib",
            "lib/libvulkan.1.dylib", "share/vulkan/icd.d/kosmickrisp_icd.json")
            .forEach { check(runtime.resolve(it).isFile) {
                "Missing ${runtime.resolve(it)}. Run bash scripts/macos/build-runtime.sh first."
            } }
    }
}

fun JavaExec.useRoxyMacNatives() {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread", "--enable-native-access=ALL-UNNAMED")
        if (providers.gradleProperty("roxyMacOSNatives").isPresent || macNatives.get().isDirectory) {
            jvmArgs("-Droxy.macos.nativePath=${macNatives.get()}")
        }
    }
}

val macGraphicsProbe by configurations.creating
repositories { mavenCentral() }
dependencies {
    for (component in listOf("lwjgl", "lwjgl-opengl", "lwjgl-glfw")) {
        add(macGraphicsProbe.name, "org.lwjgl:$component:3.3.3")
        add(macGraphicsProbe.name, "org.lwjgl:$component:3.3.3:natives-macos-arm64")
    }
}
val compileMacGraphicsProbe = tasks.register<JavaCompile>("compileMacGraphicsProbe") {
    source(fileTree("scripts/macos") { include("GlCapabilityProbe.java", "VoxyShaderProbe.java") })
    classpath = macGraphicsProbe
    destinationDirectory.set(layout.buildDirectory.dir("macos-probe/classes"))
    javaCompiler.set(project.extensions.getByType<JavaToolchainService>().compilerFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}
tasks.register<Jar>("macGraphicsProbeJar") {
    group = "distribution"
    description = "Builds the standalone graphics probe bundle for the portable macOS runtime."
    dependsOn(compileMacGraphicsProbe)
    archiveFileName.set("roxy-macos-graphics-probe.jar")
    from(compileMacGraphicsProbe.flatMap { it.destinationDirectory })
}
tasks.register<Sync>("prepareMacGraphicsProbeLibs") {
    group = "distribution"
    description = "Copies LWJGL Java APIs and native classifiers needed by the standalone graphics probe."
    from(macGraphicsProbe)
    into(layout.buildDirectory.dir("macos-probe-libs"))
}
tasks.register<JavaExec>("probeMacGraphics") {
    group = "verification"
    description = "Executes Voxy's graphics contract and actual shaders on native macOS Zink."
    dependsOn(compileMacGraphicsProbe)
    classpath = macGraphicsProbe + files(compileMacGraphicsProbe.flatMap { it.destinationDirectory })
    mainClass.set("GlCapabilityProbe")
    workingDir = rootDir
    useRoxyZink()
    args(providers.gradleProperty("roxyVoxyJar")
        .orElse(".roxy-macos/artifacts/voxy-0.2.16-beta+1.21.11.jar").get())
}

prism {
    metadata {
        modId = "roxy"
        name = "Roxy"
        description = "Loads the Voxy LoD rendering mod on NeoForge by bridging its Fabric entrypoints and APIs."
        license = "MIT"
        author("RasaNovum")
    }

    modrinthMaven()
    maven("NeoForged", "https://maven.neoforged.net/releases")
    maven("Sinytra", "https://maven.su5ed.dev/releases")

    version("1.21.11") {
        neoforge {
            loaderVersion = "21.11.44"

            dependencies {
                compileOnly("maven.modrinth:sodium:mc1.21.11-0.8.12-neoforge")
                runtimeOnly("maven.modrinth:sodium:mc1.21.11-0.8.12-neoforge")
                compileOnly("net.neoforged.fancymodloader:loader:4.0.42")
            }

        }
    }

    version("1.21.1") {
        neoforge {
            loaderVersion = "21.1.241"

            dependencies {
                compileOnly("maven.modrinth:sodium:mc1.21.1-0.8.12-neoforge")
                runtimeOnly("maven.modrinth:sodium:mc1.21.1-0.8.12-neoforge")
                compileOnly("net.neoforged.fancymodloader:loader:4.0.42")
            }
        }
    }

}

fun Project.registerRuntimeMappingsTask(mcVersion: String, intermediaryConfig: Configuration) =
    tasks.register("generateRuntimeMappings") {
        dependsOn(tasks.named("createMinecraftArtifacts"))
        val outputDir = layout.buildDirectory.dir("roxy-runtime-mappings")
        val mojmapFile = rootProject.gradle.gradleUserHomeDir
            .resolve("caches/neoformruntime/artifacts/minecraft_${mcVersion}_client_mappings.txt")
        inputs.files(intermediaryConfig)
        inputs.file(mojmapFile)
        outputs.dir(outputDir)

        doLast {
            check(mojmapFile.isFile) { "Missing NeoFormRuntime client mappings: ${mojmapFile.absolutePath}" }
            val intermediaryJar = intermediaryConfig.files.firstOrNull { it.isFile }
                ?: error("Missing Fabric intermediary jar for $mcVersion")

            val mojToOfficial = HashMap<String, String>(16384)
            val officialToMojmap = HashMap<String, String>(16384)
            val mojMembers = HashMap<String, String>(131072)

            fun stripLineRange(raw: String): String {
                val firstColon = raw.indexOf(':')
                if (firstColon <= 0) return raw
                for (i in 0 until firstColon) if (!raw[i].isDigit()) return raw
                val secondColon = raw.indexOf(':', firstColon + 1)
                if (secondColon <= firstColon + 1) return raw
                for (i in (firstColon + 1) until secondColon) if (!raw[i].isDigit()) return raw
                return raw.substring(secondColon + 1).trim()
            }

            fun typeDescriptor(raw: String): String {
                var type = raw.trim().replace("...", "[]")
                var arrayDepth = 0
                while (type.endsWith("[]")) {
                    arrayDepth++
                    type = type.substring(0, type.length - 2).trim()
                }
                val generic = type.indexOf('<')
                if (generic >= 0) type = type.substring(0, generic).trim()
                val base = when (type) {
                    "byte" -> "B"
                    "char" -> "C"
                    "double" -> "D"
                    "float" -> "F"
                    "int" -> "I"
                    "long" -> "J"
                    "short" -> "S"
                    "boolean" -> "Z"
                    "void" -> "V"
                    else -> "L" + (mojToOfficial[type] ?: type).replace('.', '/') + ";"
                }
                return "[".repeat(arrayDepth) + base
            }

            fun splitTypes(input: String): List<String> {
                if (input.isBlank()) return emptyList()
                val result = ArrayList<String>()
                var depth = 0
                var start = 0
                for (i in input.indices) {
                    val c = input[i]
                    if (c == '<') depth++
                    else if (c == '>') depth--
                    else if (c == ',' && depth == 0) {
                        result.add(input.substring(start, i).trim())
                        start = i + 1
                    }
                }
                result.add(input.substring(start).trim())
                return result
            }

            fun mapDescriptorWith(descriptor: String, classMap: Map<String, String>): String {
                val sb = StringBuilder(descriptor.length)
                var i = 0
                while (i < descriptor.length) {
                    val c = descriptor[i]
                    sb.append(c)
                    if (c == 'L') {
                        val end = descriptor.indexOf(';', i + 1)
                        if (end < 0) break
                        val cls = descriptor.substring(i + 1, end)
                        sb.append(classMap[cls] ?: cls)
                        sb.append(';')
                        i = end
                    }
                    i++
                }
                return sb.toString()
            }

            val mojLines = mojmapFile.readLines(Charsets.UTF_8)
            for (line in mojLines) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith(" ") || !line.endsWith(":")) continue
                val arrow = line.indexOf(" -> ")
                if (arrow <= 0) continue
                val moj = line.substring(0, arrow).trim()
                val official = line.substring(arrow + 4, line.length - 1).trim()
                mojToOfficial[moj] = official
                officialToMojmap[official] = moj.replace('.', '/')
            }

            var currentOfficial: String? = null
            for (line in mojLines) {
                if (line.isBlank() || line.startsWith("#")) continue
                if (!line.startsWith(" ") && line.endsWith(":")) {
                    val arrow = line.indexOf(" -> ")
                    if (arrow > 0) {
                        currentOfficial = line.substring(arrow + 4, line.length - 1).trim()
                        continue
                    }
                }
                val owner = currentOfficial ?: continue
                if (!line.startsWith(" ")) continue
                val arrow = line.lastIndexOf(" -> ")
                if (arrow < 0) continue
                val source = stripLineRange(line.substring(0, arrow).trim())
                val officialName = line.substring(arrow + 4).trim()
                if (officialName.isEmpty()) continue
                val open = source.indexOf('(')
                val memberName: String
                val descriptor: String
                if (open >= 0) {
                    val close = source.lastIndexOf(')')
                    val nameEnd = source.lastIndexOf(' ', open)
                    if (close < open || nameEnd < 0) continue
                    val retType = source.substring(0, nameEnd).trim()
                    memberName = source.substring(nameEnd + 1, open).trim()
                    val params = splitTypes(source.substring(open + 1, close).trim())
                    val sb = StringBuilder("(")
                    for (p in params) sb.append(typeDescriptor(p))
                    sb.append(')').append(typeDescriptor(retType))
                    descriptor = sb.toString()
                } else {
                    val space = source.lastIndexOf(' ')
                    if (space < 0) continue
                    memberName = source.substring(space + 1).trim()
                    descriptor = typeDescriptor(source.substring(0, space).trim())
                }
                mojMembers["$owner\u0000$officialName\u0000$descriptor"] = memberName
            }

            val classes = LinkedHashMap<String, String>(16384)
            val officialToIntermediary = HashMap<String, String>(16384)
            val targetToIntermediary = HashMap<String, String>(16384)
            data class TinyMember(
                val field: Boolean,
                val officialOwner: String,
                val intermediaryOwner: String,
                val officialDesc: String,
                val officialName: String,
                val intermediaryName: String
            )
            val tinyMembers = ArrayList<TinyMember>(131072)

            ZipFile(intermediaryJar).use { zip ->
                val entry = zip.getEntry("mappings/mappings.tiny")
                    ?: error("Missing mappings/mappings.tiny in ${intermediaryJar.name}")
                zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                    var officialOwner: String? = null
                    var intermediaryOwner: String? = null
                    reader.forEachLine { line ->
                        val parts = line.split('\t')
                        if (parts.isEmpty()) return@forEachLine
                        if ((parts[0] == "c" || parts[0] == "CLASS") && parts.size >= 3) {
                            officialOwner = parts[1]
                            intermediaryOwner = parts[2]
                            val targetOwner = officialToMojmap[parts[1]] ?: parts[1]
                            classes[parts[2]] = targetOwner
                            officialToIntermediary[parts[1]] = parts[2]
                            targetToIntermediary[targetOwner] = parts[2]
                        } else if (parts.size >= 5 && parts[0].isEmpty() && (parts[1] == "f" || parts[1] == "m")) {
                            val oOwner = officialOwner
                            val iOwner = intermediaryOwner
                            if (oOwner != null && iOwner != null) {
                                tinyMembers.add(TinyMember(parts[1] == "f", oOwner, iOwner, parts[2], parts[3], parts[4]))
                            }
                        } else if (parts.size >= 5 && (parts[0] == "FIELD" || parts[0] == "METHOD")) {
                            val owner = parts[1]
                            var oOwner = owner
                            var iOwner = officialToIntermediary[owner] ?: targetToIntermediary[owner]
                            if (iOwner == null) {
                                oOwner = mojToOfficial[owner.replace('/', '.')] ?: owner
                                iOwner = officialToIntermediary[oOwner]
                            }
                            if (iOwner != null) {
                                tinyMembers.add(TinyMember(parts[0] == "FIELD", oOwner, iOwner, parts[2], parts[3], parts[4]))
                            }
                        }
                    }
                }
            }

            val component = classes["net/minecraft/class_2561"]
            check(component == "net/minecraft/network/chat/Component") {
                "Invalid $mcVersion runtime mappings: Component mapped to $component"
            }

            val outFile = outputDir.get().file("roxy/mappings/runtime-$mcVersion.map").asFile
            outFile.parentFile.mkdirs()
            outFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                for ((intermediary, target) in classes) {
                    writer.write("C\t")
                    writer.write(intermediary)
                    writer.write("\t")
                    writer.write(target)
                    writer.newLine()
                }
                for (member in tinyMembers) {
                    val intermediaryDesc = mapDescriptorWith(member.officialDesc, officialToIntermediary)
                    val targetName = mojMembers["${member.officialOwner}\u0000${member.officialName}\u0000${member.officialDesc}"]
                        ?: member.officialName
                    writer.write(if (member.field) "F\t" else "M\t")
                    writer.write(member.intermediaryOwner)
                    writer.write("\t")
                    writer.write(member.intermediaryName)
                    writer.write("\t")
                    writer.write(intermediaryDesc)
                    writer.write("\t")
                    writer.write(targetName)
                    writer.newLine()
                }
            }
        }
    }

project(":1.21.11") {
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
    }

    val roxyIntermediary by configurations.creating
    dependencies {
        add(roxyIntermediary.name, "net.fabricmc:intermediary:1.21.11")
    }

    afterEvaluate {
        val mainSourceSet = extensions.getByType<SourceSetContainer>().getByName("main")
        val compileMacClientProbe = tasks.register<JavaCompile>("compileMacClientProbe") {
            source(rootProject.fileTree("scripts/macos/client-probe/src") { include("**/*.java") })
            classpath = mainSourceSet.compileClasspath
            destinationDirectory.set(layout.buildDirectory.dir("macos-client-probe/classes"))
            javaCompiler.set(project.extensions.getByType<JavaToolchainService>().compilerFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            })
        }
        tasks.register<Jar>("macClientProbeJar") {
            archiveFileName.set("roxy-macos-client-probe.jar")
            from(compileMacClientProbe.flatMap { it.destinationDirectory })
            from(rootProject.file("scripts/macos/client-probe/resources-1.21.11"))
        }
        val generateRuntimeMappings = registerRuntimeMappingsTask("1.21.11", roxyIntermediary)
        tasks.named<ProcessResources>("processResources") {
            includeEmptyDirs = false
            from(generateRuntimeMappings)
        }
        tasks.named<JavaExec>("runClient") {
            if (roxyZink.get()) useRoxyZink() else useRoxyMacNatives()
            if (roxyClientProbe.get()) jvmArgs(
                "-Droxy.macos.clientProbe=true",
                "-Droxy.macos.clientProbe.reportPath=${rootProject.file(".roxy-macos/reports/client-render-1.21.11")}",
                "-Droxy.macos.clientProbe.exitAfter=${providers.gradleProperty("roxyProbeExit").orElse("false").get()}",
                "-Droxy.macos.clientProbe.fullscreen=${providers.gradleProperty("roxyProbeFullscreen").orElse("false").get()}",
                "-Droxy.macos.clientProbe.lifecycle=${providers.gradleProperty("roxyProbeLifecycle").orElse("false").get()}")
            doFirst {
                if (roxyTestWorld.isPresent) args("--quickPlaySingleplayer", roxyTestWorld.get())
                else if (!roxyZink.get()) args("--quickPlaySingleplayer", "RoxyProofWorld-1.21.1")
            }
        }
    }
}

project(":1.21.1") {
    plugins.apply("java")
    val sodiumCompileApi by configurations.creating
    dependencies {
        add(sodiumCompileApi.name, "maven.modrinth:sodium:mc1.21.1-0.8.12-neoforge")
        add("testImplementation", "org.junit.jupiter:junit-jupiter:5.11.4")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher:1.11.4")
    }
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        useJUnitPlatform()
        workingDir = layout.buildDirectory.get().asFile
    }
    val sodiumApiDirectory = layout.buildDirectory.dir("roxy-sodium-api")
    val extractSodiumCompileApi = tasks.register<Copy>("extractSodiumCompileApi") {
        from({ sodiumCompileApi.files.map { zipTree(it) } })
        include("META-INF/jarjar/net.caffeinemc.sodium-*-mod.jar")
        eachFile { path = "sodium-api.jar" }
        includeEmptyDirs = false
        into(sodiumApiDirectory)
    }
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
    }

    val roxyIntermediary by configurations.creating
    dependencies {
        add(roxyIntermediary.name, "net.fabricmc:intermediary:1.21.1")
    }

    afterEvaluate {
        val sourceSets = extensions.getByType<SourceSetContainer>()
        val minecraftSources = sourceSets.getByName("main")
        sourceSets.named("test") {
            compileClasspath += minecraftSources.compileClasspath
            runtimeClasspath += minecraftSources.runtimeClasspath
        }
        val generateRuntimeMappings = registerRuntimeMappingsTask("1.21.1", roxyIntermediary)
        val prepareEmbeddedHostClasses = tasks.register("prepareEmbeddedHostClasses") {
            dependsOn(tasks.named("compileJava"))
            val classesRoot = layout.buildDirectory.dir("classes/java/main")
            val outputDir = layout.buildDirectory.dir("roxy-embedded")
            inputs.dir(classesRoot)
            outputs.dir(outputDir)
            doLast {
                val root = classesRoot.get().asFile
                val out = outputDir.get().asFile
                out.deleteRecursively()
                val embeddedDir = out.resolve("roxy/embedded")
                embeddedDir.mkdirs()

                val selected = fileTree(root) {
                    include("net/rasanovum/roxyhost/**/*.class")
                    include("net/rasanovum/roxy/client/RoxyClientWarnings*.class")
                    include("net/rasanovum/roxy/client/RoxyWarningScreen*.class")
                }.files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }

                check(selected.isNotEmpty()) { "No embedded host classes found under ${root.absolutePath}" }
                val usedNames = HashSet<String>()
                val manifestLines = ArrayList<String>(selected.size)
                for (file in selected) {
                    val rel = file.relativeTo(root).invariantSeparatorsPath
                    val simpleBin = file.name.removeSuffix(".class") + ".bin"
                    val binName = if (usedNames.add(simpleBin)) {
                        simpleBin
                    } else {
                        rel.removeSuffix(".class").replace('/', '_') + ".bin"
                    }
                    file.copyTo(embeddedDir.resolve(binName), overwrite = true)
                    manifestLines.add("$rel\troxy/embedded/$binName")
                }
                embeddedDir.resolve("manifest.txt").writeText(
                    manifestLines.joinToString("\n") + "\n",
                    Charsets.UTF_8
                )
            }
        }
        tasks.named<ProcessResources>("processResources") {
            includeEmptyDirs = false
            from(generateRuntimeMappings)
            from(prepareEmbeddedHostClasses)
        }
        dependencies {
            add("compileOnly", files(sodiumApiDirectory.map { it.file("sodium-api.jar") }).builtBy(extractSodiumCompileApi))
        }
        tasks.named<JavaExec>("runClient") {
            if (roxyZink.get()) useRoxyZink() else useRoxyMacNatives()
            doFirst {
                if (!roxyZink.get()) args("--quickPlaySingleplayer", "RoxyOCLProofWorld-1.21.1")
            }
        }

        val runClient = tasks.named<JavaExec>("runClient")
        val mainSourceSet = extensions
            .getByType<SourceSetContainer>()
            .getByName("main")
        val mainOutput = mainSourceSet.output
        val compileMacClientProbe = tasks.register<JavaCompile>("compileMacClientProbe") {
            source(rootProject.fileTree("scripts/macos/client-probe/src") { include("**/*.java") })
            classpath = mainSourceSet.compileClasspath
            destinationDirectory.set(layout.buildDirectory.dir("macos-client-probe/classes"))
            javaCompiler.set(project.extensions.getByType<JavaToolchainService>().compilerFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            })
        }
        val macClientProbeJar = tasks.register<Jar>("macClientProbeJar") {
            archiveFileName.set("roxy-macos-client-probe.jar")
            from(compileMacClientProbe.flatMap { it.destinationDirectory })
            from(rootProject.file("scripts/macos/client-probe/resources"))
        }
        val fabricStubsJar = tasks.register<Jar>("fabricStubsJar") {
            archiveFileName.set("roxy-fabric-stubs.jar")
            from(mainOutput) {
                include("net/fabricmc/**")
            }
            manifest {
                attributes["FMLModType"] = "LIBRARY"
            }
        }
        tasks.named<Jar>("jar") {
            dependsOn(fabricStubsJar)
            exclude("net/fabricmc/**")
            exclude("net/rasanovum/roxyhost/**")
            exclude("net/rasanovum/roxy/client/RoxyClientWarnings*.class")
            exclude("net/rasanovum/roxy/client/RoxyWarningScreen*.class")
            from(fabricStubsJar.map { it.archiveFile }) {
                into("META-INF/jars")
            }
        }
        val runtimeClasspath = configurations.named("runtimeClasspath")
        val prepareClientRun = tasks.named("prepareClientRun")
        val packagedLegacyClasspath = layout.buildDirectory.file("moddev/clientLegacyClasspath-packaged.txt")
        val packagedMainClassesDir = layout.buildDirectory.dir("moddev/packaged-roxy-classes")
        val packagedMainClasses = tasks.register<Sync>("copyPackagedRoxyClasses") {
            dependsOn(tasks.named("classes"))
            from(mainOutput) {
                exclude("net/fabricmc/**")
                exclude("net/rasanovum/roxyhost/**")
                exclude("net/rasanovum/roxy/client/RoxyClientWarnings*.class")
                exclude("net/rasanovum/roxy/client/RoxyWarningScreen*.class")
            }
            into(packagedMainClassesDir)
        }
        val packagedRoxy = tasks.register<Copy>("copyPackagedRoxyToRunMods") {
            dependsOn(prepareClientRun, tasks.named<Jar>("jar"), fabricStubsJar, packagedMainClasses)
            from(tasks.named<Jar>("jar"))
            if (roxyClientProbe.get()) from(macClientProbeJar)
            into(layout.projectDirectory.dir("runs/client/mods"))
            // Always remove a previous optional probe when the switch is off.
            outputs.upToDateWhen { false }
            doFirst {
                fileTree(layout.projectDirectory.dir("runs/client/mods")) {
                    include("roxy-*.jar")
                }.files.forEach { it.delete() }
                layout.projectDirectory.file("runs/client/mods/roxy-fabric-stubs.jar").asFile.delete()
            }
        }

        if (System.getProperty("os.name").lowercase().contains("mac")) {
            // Sodium's distribution wraps its actual mod inside JarJar. Loading
            // the wrapper on the legacy classpath makes FML select the wrapper
            // as its own nested dependency and drop the Sodium mod. Discover it
            // from mods instead, as an ordinary launcher does.
            val copyMacSodium = tasks.register<Copy>("copyMacSodiumToRunMods") {
                from(runtimeClasspath.map { files -> files.filter { it.name.startsWith("sodium-") } })
                into(layout.projectDirectory.dir("runs/client/mods"))
            }
            packagedRoxy.configure { dependsOn(copyMacSodium) }
        }

        tasks.register<JavaExec>("runClientPackaged") {
            group = "mod development"
            description = "Runs the 1.21.1 client with Roxy loaded as a packaged mod."
            if (roxyZink.get()) useRoxyZink() else useRoxyMacNatives()
            if (roxyClientProbe.get()) jvmArgs(
                "-Droxy.macos.clientProbe=true",
                "-Droxy.macos.clientProbe.reportPath=${rootProject.file(".roxy-macos/reports/client-render")}",
                "-Droxy.macos.clientProbe.exitAfter=${providers.gradleProperty("roxyProbeExit").orElse("false").get()}",
                "-Droxy.macos.clientProbe.fullscreen=${providers.gradleProperty("roxyProbeFullscreen").orElse("false").get()}",
                "-Droxy.macos.clientProbe.lifecycle=${providers.gradleProperty("roxyProbeLifecycle").orElse("false").get()}")
            dependsOn(packagedRoxy)
            if (System.getenv("ROXY_OCL_JAVA_HOME") != null) {
                javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                })
            }
            doFirst {
                val devRun = runClient.get()
                val bundledLibraryNames = fileTree(layout.projectDirectory.dir("runs/client/mods"))
                    .matching { include("voxy*.jar") }
                    .files
                    .flatMap { mod ->
                        ZipFile(mod).use { zip ->
                            zip.entries().asSequence()
                                .filter { it.name.startsWith("META-INF/jars/") && it.name.endsWith(".jar") }
                                .map { it.name.substringAfterLast('/') }
                                .toList()
                        }
                    }
                    .filterNot { it.startsWith("lz4-java-") && it.endsWith(".jar") }
                    .toSet()
                val installedModNames = fileTree(layout.projectDirectory.dir("runs/client/mods"))
                    .matching { include("*.jar") }.files.map { it.name }.toSet()
                val mainClassDirectories = setOf(packagedMainClassesDir.get().asFile)
                val mainResourceDirectory = mainOutput.resourcesDir
                    ?: error("Roxy main resources directory is unavailable")
                val legacyClasspath = layout.buildDirectory.file("moddev/clientLegacyClasspath.txt").get().asFile
                val filteredLegacyClasspath = legacyClasspath.readLines()
                    .filter { line ->
                        val file = File(line.trim())
                        file.name !in bundledLibraryNames
                            && file.name !in installedModNames
                            && file != mainResourceDirectory
                            && file !in mainClassDirectories
                    }
                    .joinToString(System.lineSeparator())
                legacyClasspath.writeText(filteredLegacyClasspath)
                packagedLegacyClasspath.get().asFile.writeText(filteredLegacyClasspath)
                mainClass.set(devRun.mainClass)
                val packagedClasspath = runtimeClasspath.get().files.filter { file ->
                        file != mainOutput.resourcesDir
                            && !mainOutput.classesDirs.files.contains(file)
                            && file.name !in bundledLibraryNames
                            && file.name !in installedModNames
                    }
                classpath = files(packagedClasspath)
                workingDir = layout.projectDirectory.dir("runs/client").asFile
                val graphicsEnvironment = environment.filterKeys {
                    it.startsWith("MESA_") || it == "VK_DRIVER_FILES"
                        || it == "LIBGL_DRIVERS_PATH" || it == "EGL_PLATFORM"
                }
                environment = devRun.environment + graphicsEnvironment
                environment.remove("MESA_EXTENSION_OVERRIDE")
                if (!providers.gradleProperty("roxyDiagnosticGL46")
                        .map(String::toBoolean).orElse(false).get()) {
                    environment.remove("MESA_GL_VERSION_OVERRIDE")
                    environment.remove("MESA_GLSL_VERSION_OVERRIDE")
                }
                environment.remove("CLASSPATH")
                environment.remove("MOD_CLASSES")
                jvmArgs = (devRun.jvmArgs + jvmArgs.orEmpty())
                    .filterNot { it.startsWith("-DlegacyClassPath.file=") }
                    .distinct()
                    .plus("-DlegacyClassPath.file=${packagedLegacyClasspath.get().asFile.absolutePath}")
                args = devRun.args.toMutableList().apply {
                    if (roxyTestWorld.isPresent && !contains("--quickPlaySingleplayer")) {
                        add("--quickPlaySingleplayer")
                        add(roxyTestWorld.get())
                    } else if (!roxyZink.get() && !contains("--quickPlaySingleplayer")) {
                        add("--quickPlaySingleplayer")
                        add("RoxyOCLProofWorld-1.21.1")
                    }
                }
            }
        }
    }
}
