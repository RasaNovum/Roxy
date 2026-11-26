plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

sourceSets {
    named("main") {
        java.setSrcDirs(listOf("shared/src/main/java"))
        resources.setSrcDirs(listOf("shared/src/main/resources"))
    }
    named("test") {
        java.setSrcDirs(listOf("shared/src/test/java"))
        resources.setSrcDirs(listOf("shared/src/test/resources"))
    }
}

dependencies {
    compileOnly("org.ow2.asm:asm:9.7.1")
    compileOnly("org.ow2.asm:asm-commons:9.7.1")
    compileOnly("org.ow2.asm:asm-tree:9.7.1")
    compileOnly("org.ow2.asm:asm-util:9.7.1")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.slf4j:slf4j-api:2.0.12")
    compileOnly("org.joml:joml:1.10.5")
    compileOnly("org.lwjgl:lwjgl:3.3.1")
    compileOnly("org.lwjgl:lwjgl-opengl:3.3.1")
    testImplementation("org.ow2.asm:asm:9.7.1")
    testImplementation("org.ow2.asm:asm-commons:9.7.1")
    testImplementation("org.ow2.asm:asm-tree:9.7.1")
    testImplementation("org.ow2.asm:asm-util:9.7.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.slf4j:slf4j-api:2.0.12")
    testRuntimeOnly("org.joml:joml:1.10.5")
    testRuntimeOnly("org.lwjgl:lwjgl:3.3.1")
    testRuntimeOnly("org.lwjgl:lwjgl-opengl:3.3.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
