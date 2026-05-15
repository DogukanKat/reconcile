plugins {
    `java-library`
    alias(libs.plugins.avro)
}

// generateAvroJava is wired ahead of compileJava by the plugin; the
// generated sources land under build/ which .gitignore already covers,
// so nothing generated is committed — the .avsc is the only source of
// truth.
avro {
    // decimal → java.math.BigDecimal (not ByteBuffer); the whole point
    // of the logical type. ADR-0004: BigDecimal in the domain, decimal
    // on the wire, never float. Avro 1.11 already generates
    // java.time.Instant for timestamp-micros by default, so no
    // date-time knob is needed here.
    isEnableDecimalLogicalType.set(true)
    // CharSequence defaults are a footgun (Utf8 leaks into equals());
    // String keeps the generated record ergonomic.
    stringType.set("String")
}

dependencies {
    api(libs.avro)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
}
