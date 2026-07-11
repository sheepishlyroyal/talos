plugins {
    id("fabric-loom")
}

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation(project(":glade-graalpy-runtime"))
    include(project(":glade-graalpy-runtime"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand(mapOf("version" to project.version)) }
}
