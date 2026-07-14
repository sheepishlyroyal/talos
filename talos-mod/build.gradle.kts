plugins {
    id("net.fabricmc.fabric-loom")
}

// MC 26.1 targets Java 25. Only JDK 26 is installed locally, which compiles --release 25
// fine; keep the release floor at 25 rather than pinning an exact toolchain that Gradle
// would then try to auto-provision.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

loom {
    accessWidenerPath = file("src/main/resources/talos.accesswidener")
}

dependencies {
    // 26.1 is unobfuscated: official Mojang mappings, no Yarn line, non-mod dependency scopes.
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation(project(":talos-graalpy-runtime"))
    include(project(":talos-graalpy-runtime"))
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    include("org.java-websocket:Java-WebSocket:1.5.7")
    testImplementation("net.fabricmc:fabric-loader-junit:${property("fabric_loader_version")}")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to project.version)) }
}
