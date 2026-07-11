plugins {
    base
}

allprojects {
    group = "dev.glade"
    version = property("mod_version") as String

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}
