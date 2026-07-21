import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.SourceSetContainer

plugins {
    id("dev.prism")
}

group = "com.leclowndu93150"
version = "1.0.0"

prism {
    metadata {
        modId = "roxy"
        name = "Roxy"
        description = "Loads the Voxy LoD rendering mod on NeoForge by bridging its Fabric entrypoints and APIs."
        license = "MIT"
        author("leclowndu93150")
    }

    modrinthMaven()
    maven("NeoForged", "https://maven.neoforged.net/releases")
    maven("Sinytra", "https://maven.su5ed.dev/releases")

    version("26.1.2") {
        neoforge {
            loaderVersion = "26.1.2.48-beta"

            dependencies {
                compileOnly("maven.modrinth:sodium:mc26.1.2-0.8.12-neoforge")
                runtimeOnly("maven.modrinth:sodium:mc26.1.2-0.8.12-neoforge")
                compileOnly("maven.modrinth:voxy:0.2.16-beta")
                implementation("maven.modrinth:voxy:0.2.16-beta")
                compileOnly("cpw.mods:modlauncher:11.0.5")
                compileOnly("cpw.mods:securejarhandler:3.0.8")
                compileOnly("net.neoforged.fancymodloader:loader:11.0.13")

                compileOnly("maven.modrinth:chunky:hEXc6nbN")
            }
        }
    }

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
                compileOnly("org.sinytra.forgified-fabric-api:fabric-api-base:0.4.42+d1308ded19")
            }
        }
    }

}

project(":1.21.11") {
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
    }

    val foxyIntermediary by configurations.creating
    dependencies {
        add(foxyIntermediary.name, "net.fabricmc:intermediary:1.21.11")
    }

    afterEvaluate {
        tasks.named<ProcessResources>("processResources") {
            from({ foxyIntermediary.files.map { zipTree(it) } }) {
                include("mappings/mappings.tiny")
                eachFile { path = "foxy/mappings/intermediary-1.21.11.tiny" }
            }
        }
        tasks.named<JavaExec>("runClient") {
            doFirst {
                args("--quickPlaySingleplayer", "RoxyProofWorld-1.21.1")
            }
        }
    }
}

project(":1.21.1") {
    repositories {
        maven { url = uri("https://maven.fabricmc.net/") }
    }

    val roxyIntermediary by configurations.creating
    dependencies {
        add(roxyIntermediary.name, "net.fabricmc:intermediary:1.21.1")
    }

    afterEvaluate {
        tasks.named<ProcessResources>("processResources") {
            from({ roxyIntermediary.files.map { zipTree(it) } }) {
                include("mappings/mappings.tiny")
                eachFile { path = "roxy/mappings/intermediary-1.21.1.tiny" }
            }
        }
        tasks.named<JavaExec>("runClient") {
            doFirst {
                args("--quickPlaySingleplayer", "RoxyProofWorld-1.21.1")
            }
        }

        val runClient = tasks.named<JavaExec>("runClient")
        val mainOutput = extensions
            .getByType<SourceSetContainer>()
            .getByName("main")
            .output
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
                // The packaged jar provides these only through its conditional
                // fallback library.  Keeping them out of MOD_CLASSES also
                // prevents a dev run with Forgified Fabric API from exposing a
                // second supplier for net.fabricmc.* packages.
                exclude("net/fabricmc/**")
            }
            into(packagedMainClassesDir)
        }
        val packagedRoxy = tasks.register<Copy>("copyPackagedRoxyToRunMods") {
            dependsOn(prepareClientRun, tasks.named<Jar>("jar"), packagedMainClasses)
            from(tasks.named<Jar>("jar"))
            into(layout.projectDirectory.dir("runs/client/packaged-mods"))
        }

        tasks.register<JavaExec>("runClientPackaged") {
            group = "mod development"
            description = "Runs the 1.21.1 client with Roxy loaded as a packaged mod."
            dependsOn(packagedRoxy)
            doFirst {
                val devRun = runClient.get()
                val bundledLibraryNames = fileTree(layout.projectDirectory.dir("runs/client/mods"))
                    .matching { include("voxy*.jar") }
                    .files
                    .flatMap { mod ->
                        java.util.zip.ZipFile(mod).use { zip ->
                            zip.entries().asSequence()
                                .filter { it.name.startsWith("META-INF/jars/") && it.name.endsWith(".jar") }
                                .map { it.name.substringAfterLast('/') }
                                .toList()
                        }
                    }
                    // Minecraft already supplies lz4-java on the shared
                    // runtime classpath; Roxy deliberately does not extract a
                    // second copy of that automatic module.
                    .filterNot { it.startsWith("lz4-java-") && it.endsWith(".jar") }
                    .toSet()
                val mainClassDirectories = setOf(packagedMainClassesDir.get().asFile)
                val mainResourceDirectory = mainOutput.resourcesDir
                    ?: error("Roxy main resources directory is unavailable")
                val legacyClasspath = layout.buildDirectory.file("moddev/clientLegacyClasspath.txt").get().asFile
                val filteredLegacyClasspath = legacyClasspath.readLines()
                    .filter { line ->
                        val file = java.io.File(line.trim())
                        file.name !in bundledLibraryNames
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
                    }
                classpath = files(packagedClasspath)
                workingDir = layout.projectDirectory.dir("runs/client").asFile
                environment = devRun.environment
                // Do not let the desktop/Gradle process inject another copy of
                // the development output into this packaged test launch.
                environment.remove("CLASSPATH")
                val mainClasses = mainClassDirectories.joinToString(File.pathSeparator) { "roxy%%${it.absolutePath}" }
                val mainResources = "roxy%%${mainResourceDirectory.absolutePath}"
                environment("MOD_CLASSES", "$mainClasses${File.pathSeparator}$mainResources")
                jvmArgs = devRun.jvmArgs
                    .filterNot { it.startsWith("-DlegacyClassPath.file=") }
                    .plus("-DlegacyClassPath.file=${packagedLegacyClasspath.get().asFile.absolutePath}")
                args = devRun.args.toMutableList().apply {
                    if (!contains("--quickPlaySingleplayer")) {
                        add("--quickPlaySingleplayer")
                        add("RoxyProofWorld-1.21.1")
                    }
                }
            }
        }
    }
}
