import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The web client (ADR 0009): Kotlin/JS with kotlinx-html, no TypeScript. View code is kept apart from state and sync
// logic so the logic is tested in Node without a browser (the browser test task is off).
kotlin {
    js {
        browser {
            commonWebpackConfig { outputFileName = "circles.js" }
            testTask { enabled = false }
        }
        nodejs { testTask { useMocha { timeout = "20s" } } }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.html)
            implementation(libs.kotlinx.coroutines.core)
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The size budget of ADR 0009 / spec section 6: the application script, gzipped, stays under 250 KB.
val bundleBudgetBytes = 250 * 1024

abstract class BundleBudgetTask : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:Input abstract val budgetBytes: Property<Int>

    @TaskAction
    fun check() {
        val raw = script.get().asFile.readBytes()
        val gzipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(raw) } }.size()
        println("web bundle: ${raw.size} bytes raw, $gzipped bytes gzipped, budget ${budgetBytes.get()}")
        if (gzipped > budgetBytes.get()) {
            throw GradleException("The web bundle is $gzipped bytes gzipped, over the budget of ${budgetBytes.get()}")
        }
    }
}

val checkBundleBudget = tasks.register<BundleBudgetTask>("checkBundleBudget") {
    val webpack = tasks.named("jsBrowserProductionWebpack")
    dependsOn(webpack)
    script.set(layout.buildDirectory.file("kotlin-webpack/js/productionExecutable/circles.js"))
    budgetBytes.set(bundleBudgetBytes)
}

tasks.named("check") { dependsOn(checkBundleBudget) }

// The deployable client: the bundle plus the static files, in one folder that the server packs (ADR 0037).
val webDist = tasks.register<Sync>("webDist") {
    from(tasks.named("jsBrowserProductionWebpack").map { (it as org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack).outputDirectory }) {
        include("circles.js")
    }
    from(layout.projectDirectory.dir("src/jsMain/resources")) { include("index.html", "styles.css") }
    into(layout.buildDirectory.dir("dist/web"))
}
