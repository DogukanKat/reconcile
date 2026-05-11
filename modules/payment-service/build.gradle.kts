plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.postgres.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    testImplementation(libs.bundles.test)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.archunit.junit5)
}

tasks.test {
    useJUnitPlatform()
    // Help Testcontainers find Docker Desktop's user-mode socket on macOS
    // when /var/run/docker.sock is root-owned. No-op on Linux daemon installs.
    val desktopSocket = file("${System.getProperty("user.home")}/.docker/run/docker.sock")
    if (desktopSocket.exists()) {
        environment("DOCKER_HOST", "unix://${desktopSocket.absolutePath}")
    }
}
