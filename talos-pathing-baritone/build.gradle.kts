plugins {
    id("net.fabricmc.fabric-loom")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

dependencies {
    // 26.1: official Mojang mappings, no Yarn line, non-mod dependency scopes.
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    // talos-mod is a separate installed mod at runtime; compile against its classes only.
    // 26.1's non-remap Loom has no `namedElements` variant (nothing is remapped), so this is
    // now a plain project dependency.
    compileOnly(project(":talos-mod"))
    // Baritone is an optional, separately-installed LGPL mod with no resolvable 1.21.11 artifact.
    // It is accessed purely via reflection in BaritonePathingEngine (no compile-time dependency).
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to project.version)) }
}
