plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

dependencies {
    api("org.graalvm.polyglot:polyglot:${property("graalpy_version")}")
    // Depend on the concrete GraalPy leaf jars, NOT the org.graalvm.polyglot:python-community
    // aggregator POM: Fabric Loader's LibClassifier scans the dev/runtime classpath and tries to
    // unzip every entry, which fails on a pom-packaged module ("zip END header not found").
    // These leaves pull the same transitive jars (truffle-api/regex/llvm-api/icu4j/...).
    runtimeOnly("org.graalvm.python:python-language:${property("graalpy_version")}")
    runtimeOnly("org.graalvm.python:python-resources:${property("graalpy_version")}")
    runtimeOnly("org.graalvm.truffle:truffle-runtime:${property("graalpy_version")}")

    testImplementation(platform("org.junit:junit-bom:${property("junit_version")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
