pluginManagement {
    val loom_version: String by settings
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("fabric-loom") version loom_version
    }
}

rootProject.name = "talos"
include("talos-mod", "talos-pathing-baritone", "talos-graalpy-runtime")
