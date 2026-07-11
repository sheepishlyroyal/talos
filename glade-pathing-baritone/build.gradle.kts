plugins {
    id("fabric-loom")
}

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    // glade-mod is a separate installed mod at runtime; compile against its named classes only
    // (plain compileOnly avoids Loom's mod-remapper reading glade-mod's not-yet-built dev jar).
    compileOnly(project(path = ":glade-mod", configuration = "namedElements"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to project.version)) }
}
