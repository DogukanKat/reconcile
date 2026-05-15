// Root build script. Shared conventions live here; per-module plugin
// configuration lives in each module's own build.gradle.kts.

allprojects {
    group = "dev.dogukankat"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        // Confluent's kafka-avro-serializer and schema-registry-client
        // are not on Maven Central. Phase 3 (Avro on the wire) needs
        // them; nothing earlier did.
        maven { url = uri("https://packages.confluent.io/maven/") }
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
