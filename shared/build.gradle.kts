plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(17)

    // The same policy and encoding code runs on the server (JVM) and in the browser (JS).
    jvm()
    js { nodejs() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            // ADR 0010: JDK Ed25519 plus BouncyCastle BLAKE2b on the JVM; no native library.
            implementation(libs.bouncycastle)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.mockk)
            runtimeOnly(libs.junit.platform.launcher)
            // ADR 0018: libsodium for the JVM test clients only, never in the server jar.
            implementation(libs.lazysodium.java)
            implementation(libs.jna)
        }
    }
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
