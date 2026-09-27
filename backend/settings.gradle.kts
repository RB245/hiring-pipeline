plugins {
    // Lets Gradle download a JDK 21 toolchain when the local JDK is a different version.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "pipeline"
