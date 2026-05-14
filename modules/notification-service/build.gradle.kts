plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.kafka)
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(libs.bundles.test)
    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    useJUnitPlatform()
}
