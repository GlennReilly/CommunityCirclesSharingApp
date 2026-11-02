plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
    application
}

kotlin { jvmToolchain(17) }

application { mainClass = "circles.server.MainKt" }

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.bouncycastle)
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// One runnable jar with everything inside (ADR 0017): java -jar server/build/libs/circles-server.jar serve
tasks.shadowJar {
    archiveBaseName = "circles-server"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
    // BouncyCastle ships signature files that are invalid once its classes are repackaged.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Netty ships HTTP/3 (QUIC) natives for five platforms; the server speaks HTTP over TCP only.
    exclude("META-INF/native/*quiche*")
}
