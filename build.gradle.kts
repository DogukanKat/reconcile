// Root build script. Shared conventions live here; per-module plugin
// configuration lives in each module's own build.gradle.kts.

allprojects {
    group = "dev.dogukankat"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }
}
