package circles.server.load

import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val CLOCK_TICKS_PER_SECOND = 100.0
private const val NANOS_PER_MILLI = 1_000_000.0

/** The real server jar as a child process, with its memory and CPU readable from /proc. */
class ServerProcess(jar: Path, val dataDir: Path, heapMb: Int, extraEnv: Map<String, String> = emptyMap()) : AutoCloseable {
    val port: Int = ServerSocket(0).use { it.localPort }
    val baseUrl = "http://127.0.0.1:$port"
    private val process: Process

    init {
        val builder = ProcessBuilder("java", "-Xmx${heapMb}m", "-jar", jar.toString(), "serve")
        builder.environment().putAll(
            mapOf(
                "CIRCLES_DATA_DIR" to dataDir.toString(),
                "CIRCLES_PORT" to port.toString(),
                "CIRCLES_BIND" to "127.0.0.1",
                "CIRCLES_RATE_LIMIT" to "false",
                "CIRCLES_BLOB_QUOTA_MIB" to "4096",
                "CIRCLES_LOG_LEVEL" to "warn",
            ) + extraEnv,
        )
        builder.redirectErrorStream(true)
        builder.redirectOutput(dataDir.resolveSibling("${dataDir.fileName}-server.log").toFile())
        process = builder.start()
        waitUntilReady()
    }

    val pid: Long get() = process.pid()

    private fun waitUntilReady() {
        val http = HttpClient.newHttpClient()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            check(process.isAlive) { "the server exited early" }
            try {
                val r = http.send(HttpRequest.newBuilder(URI.create("$baseUrl/readyz")).build(), HttpResponse.BodyHandlers.ofString())
                if (r.statusCode() == 200) return
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        error("the server did not become ready")
    }

    fun rssKb(): Long = File("/proc/$pid/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()

    fun threads(): Int = File("/proc/$pid/status").readLines().first { it.startsWith("Threads:") }.split(Regex("\\s+"))[1].toInt()

    /** Seconds of CPU the process has used (user plus system). */
    fun cpuSeconds(): Double {
        val fields = File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(" ")
        return (fields[11].toLong() + fields[12].toLong()) / CLOCK_TICKS_PER_SECOND
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

/** One measured window: wall time, CPU, and the largest memory seen. */
class ResourceWindow(val seconds: Double, val cpuCores: Double, val rssMaxMb: Double, val threadsMax: Int)

/** Samples a [ServerProcess] once a second so a window's CPU and peak memory can be reported. */
class ResourceSampler(private val server: ServerProcess) : AutoCloseable {
    @Volatile private var running = true
    @Volatile private var rssMaxKb = 0L
    @Volatile private var threadsMax = 0
    private val startedAt = System.nanoTime()
    private val startCpu = server.cpuSeconds()
    private val worker = thread(isDaemon = true) {
        while (running) {
            runCatching {
                rssMaxKb = maxOf(rssMaxKb, server.rssKb())
                threadsMax = maxOf(threadsMax, server.threads())
            }
            Thread.sleep(500)
        }
    }

    fun window(): ResourceWindow {
        val wall = (System.nanoTime() - startedAt) / 1e9
        return ResourceWindow(wall, (server.cpuSeconds() - startCpu) / wall, rssMaxKb / 1024.0, threadsMax)
    }

    override fun close() {
        running = false
        worker.join(2_000)
    }
}

class LatencySummary(val count: Int, val p50: Double, val p95: Double, val p99: Double, val max: Double) {
    override fun toString(): String = if (count == 0) "n/a" else "p50 %.0f ms, p95 %.0f ms, p99 %.0f ms (n=%d)".format(p50, p95, p99, count)
}

/** Thread-safe latency recorder with percentiles. */
class Latencies {
    private val nanos = ConcurrentLinkedQueue<Long>()

    fun record(durationNanos: Long) {
        nanos.add(durationNanos)
    }

    inline fun <T> time(block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(System.nanoTime() - start)
        }
    }

    fun summary(): LatencySummary {
        val sorted = nanos.sorted()
        if (sorted.isEmpty()) return LatencySummary(0, 0.0, 0.0, 0.0, 0.0)
        fun at(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / NANOS_PER_MILLI
        return LatencySummary(sorted.size, at(0.50), at(0.95), at(0.99), sorted.last() / NANOS_PER_MILLI)
    }
}

fun directorySize(path: Path): Long =
    if (!Files.exists(path)) 0 else Files.walk(path).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

fun mib(bytes: Long): String = "%.1f MiB".format(bytes / 1_048_576.0)
