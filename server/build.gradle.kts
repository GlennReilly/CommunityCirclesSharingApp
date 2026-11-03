plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.shadow)
    application
}

kotlin { jvmToolchain(17) }

// LibsodiumCipher lives in shared/testFixtures; only tests compile it, so the server jar has no libsodium (ADR 0018).
sourceSets.test { kotlin.srcDir("../shared/testFixtures/kotlin") }

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
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform { excludeTags("load") } }

// The load test (docs/operator/requirements.md): starts the real server jar as a child process and measures it.
// It is not part of `check`. Run: ./gradlew :server:loadTest   (options: -Pload.members=300 -Pload.seconds=60 -Pload.heapMb=512)
tasks.register<Test>("loadTest") {
    description = "Measures a synthetic circle against the real server jar; writes build/load-test/report.md"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("load") }
    dependsOn(tasks.shadowJar)
    systemProperty("circles.jar", tasks.shadowJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty("load.report", layout.buildDirectory.dir("load-test").get().asFile.absolutePath)
    listOf("members", "seconds", "heapMb", "rates").forEach { name ->
        providers.gradleProperty("load.$name").orNull?.let { systemProperty("load.$name", it) }
    }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    maxHeapSize = "2g"
}

// The web client (ADR 0037) is packed under web/ in the jar, and added to the classpath of `run`.
val webDistParent = project(":web").layout.buildDirectory.dir("dist")

tasks.run {
    dependsOn(":web:stampWorker")
    classpath(webDistParent)
}

// One runnable jar with everything inside (ADR 0017): java -jar server/build/libs/circles-server.jar serve
tasks.shadowJar {
    archiveBaseName = "circles-server"
    archiveClassifier = ""
    archiveVersion = ""
    mergeServiceFiles()
    dependsOn(":web:stampWorker")
    from(webDistParent)
    // BouncyCastle ships signature files that are invalid once its classes are repackaged.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // Netty ships HTTP/3 (QUIC) natives for five platforms; the server speaks HTTP over TCP only.
    exclude("META-INF/native/*quiche*")
}
