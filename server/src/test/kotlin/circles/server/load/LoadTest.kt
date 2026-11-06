package circles.server.load

import circles.api.MAX_PUSH_EVENTS
import circles.api.PushStatus
import circles.crypto.JvmCrypto
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.ItemKeys
import circles.event.KeyRing
import circles.event.ReturnOutcome
import circles.event.StewardSettings
import circles.event.newCircleId
import circles.event.newDetailKeyId
import circles.event.newItemId
import circles.event.newLoanId
import circles.policy.CircleId
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.server.support.ApiClient
import circles.server.support.SocketTransport
import circles.server.support.SseReader
import circles.server.support.testCipher
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag

private const val ITEMS_PER_MEMBER = 2
private const val LOANS_PER_MEMBER = 5
private const val PHOTO_BYTES = 250 * 1024
private const val SETUP_THREADS = 8
private const val WRITER_THREADS = 16
private const val BLOB_READS_PER_SECOND = 5
private const val PULL_DEBOUNCE_MILLIS = 2_000
private const val PULL_PAGE = 500
private const val SAMPLE_READERS_FOR_DELIVERY = 10
private const val BREAKING_P95_PUSH_MILLIS = 1_000.0
private const val BREAKING_ERROR_RATE = 0.01

/**
 * Builds a synthetic circle through the real API against the real server jar, then measures it (docs/operator/requirements.md).
 * Run with `./gradlew :server:loadTest`; it is excluded from `check`.
 */
@Tag("load")
class LoadTest {
    private val crypto = JvmCrypto()
    private val today = LocalDate.now(ZoneOffset.UTC).toEpochDay().toInt()
    private val http = HttpClient.newHttpClient()
    private val report = StringBuilder()

    private fun prop(name: String, default: Int) = System.getProperty("load.$name")?.toInt() ?: default

    private fun say(line: String = "") {
        println(line)
        report.appendLine(line)
    }

    private class ItemRef(val owner: Int, val id: ItemId, val keyId: String, val key: ByteString, val blobHash: String)

    private class Window(val push: Latencies = Latencies(), val pull: Latencies = Latencies(), val delivery: Latencies = Latencies(), val hint: Latencies = Latencies()) {
        val sent = AtomicInteger()
        val failed = AtomicInteger()
    }

    private fun <T> parallel(items: List<T>, threads: Int = SETUP_THREADS, action: (T) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            items.map { pool.submit { action(it) } }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun aCircleOfManyMembers() {
        val members = prop("members", 300)
        val steadySeconds = prop("seconds", 60)
        val heapMb = prop("heapMb", 512)
        val rates = (System.getProperty("load.rates") ?: "2,10,25,50,100,200").split(",").map { it.trim().toInt() }
        val jar = Paths.get(System.getProperty("circles.jar"))
        val outDir = Paths.get(System.getProperty("load.report")).also { Files.createDirectories(it) }
        val work = Files.createTempDirectory("circles-load")
        val dataDir = work.resolve("data")

        say("# Load test: ${members}-member circle")
        say()
        say("- Machine: ${cpuModel()}, ${Runtime.getRuntime().availableProcessors()} hardware threads, ${memTotalGb()} GB RAM")
        say("- Server runtime: ${serverJava()}")
        say("- Server: the shadow jar in its own JVM with `-Xmx${heapMb}m`, rate limits off, listening on localhost; the load generator runs on the same machine, so the server shares the CPU with it")
        say()

        ServerProcess(jar, dataDir, heapMb).use { server ->
            val transport = SocketTransport(server.baseUrl)
            val api = ApiClient(transport, crypto)
            val circle = newCircleId(crypto)
            val circleKey = KeyRing.newCircleKey(crypto)
            val people = List(members) { EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher).also { a -> a.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) } }
            val tokens = arrayOfNulls<String>(members)
            val head = AtomicLong(0)

            fun push(member: Int, events: List<Envelope>) {
                val token = tokens[member] ?: error("member $member has no session")
                events.chunked(MAX_PUSH_EVENTS).forEach { chunk ->
                    val results = runBlocking { api.push(token, circle, chunk) }.second
                    results.forEach { r ->
                        check(r.status == PushStatus.ACCEPTED) { "push refused: ${r.reason} ${r.detail}" }
                        head.accumulateAndGet(r.serverSeq ?: 0) { a, b -> maxOf(a, b) }
                    }
                }
            }

            // ---- setup ----
            val setupStart = System.nanoTime()
            val founder = people[0]
            check(runBlocking { api.createCircle(founder.circleCreate(circle, today, StewardSettings(quorum = 1))) }.second.status == PushStatus.ACCEPTED)
            tokens[0] = runBlocking { api.login(founder.identity) }
            push(0, listOf(founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, today)))
            val inviteKeys = List(members) { InviteKey(crypto.generateSigningKeyPair()) }
            val invites = (1 until members).map { founder.invite(circle, inviteKeys[it], today + 30, 1, today) }
            push(0, invites)
            parallel((1 until members).toList()) { i ->
                val join = people[i].join(circle, invites[i - 1].eventId(crypto), inviteKeys[i], today)
                check(runBlocking { api.join(circle, join) }.second.status == PushStatus.ACCEPTED)
                tokens[i] = runBlocking { api.login(people[i].identity) }
            }
            push(0, (1 until members).map { founder.keyWrap(circle, people[it].identity.registration, FIRST_EPOCH, circleKey, today) })
            (1..minOf(4, members - 1)).forEach { push(0, listOf(founder.stewardAdd(circle, people[it].identity.member, today))) }

            val random = Random(42)
            val items = mutableListOf<ItemRef>()
            val itemBlobs = ConcurrentHashMap<String, ByteArray>()
            parallel((0 until members).toList()) { m ->
                val events = mutableListOf<Envelope>()
                repeat(ITEMS_PER_MEMBER) { n ->
                    val item = newItemId(crypto)
                    val keyId = newDetailKeyId(crypto)
                    val itemKey = ItemKeys.newKey(crypto)
                    val photo = ByteArray(PHOTO_BYTES).also { Random(m * 31L + n).nextBytes(it) }
                    val hash = crypto.hash256(photo).toHex()
                    itemBlobs[hash] = ByteArray(0)
                    events += people[m].itemUpsert(circle, today, item, "Tool $m-$n", ItemClass.EVERYDAY, keyId, itemKey, listOf(people[m].identity.registration))
                    events += people[m].itemDetail(circle, today, item, keyId, itemKey, CborMap.of("address" to CborText("${m + 1} Example Street")))
                    putBlob(server.baseUrl, circle, tokens[m]!!, hash, photo)
                    synchronized(items) { items += ItemRef(m, item, keyId, itemKey, hash) }
                }
                push(m, events)
            }

            val loanCount = members * LOANS_PER_MEMBER
            class Loan(val id: circles.event.LoanId, val borrower: Int, val item: ItemRef)
            val loans = List(loanCount) {
                val borrower = random.nextInt(members)
                var item = items[random.nextInt(items.size)]
                while (item.owner == borrower) item = items[random.nextInt(items.size)]
                Loan(newLoanId(crypto), borrower, item)
            }
            val from = today + 1
            val to = today + 8
            fun phase(build: (Loan) -> Pair<Int, Envelope>) {
                val byAuthor = loans.map(build).groupBy({ it.first }, { it.second })
                parallel(byAuthor.keys.toList()) { author -> push(author, byAuthor.getValue(author)) }
            }
            phase { it.borrower to people[it.borrower].loanRequest(circle, today, it.id, it.item.id, from, to, "Please, for the weekend") }
            phase { it.item.owner to people[it.item.owner].loanApprove(circle, today, it.id, it.item.id, from, to, it.item.key, listOf(people[it.borrower].identity.registration)) }
            phase { it.item.owner to people[it.item.owner].loanHandover(circle, today, it.id) }
            phase { it.borrower to people[it.borrower].loanHandover(circle, today, it.id) }
            phase { it.borrower to people[it.borrower].loanReturn(circle, today, it.id, ReturnOutcome.OK) }
            val setupSeconds = (System.nanoTime() - setupStart) / 1e9
            val events = head.get()

            say("## The circle")
            say()
            say("- $members members, 5 stewards, ${items.size} items each with sealed detail and one ${PHOTO_BYTES / 1024} KiB photo blob, $loanCount completed loans")
            say("- ${events} events in the log, built through the public API in %.0f s".format(setupSeconds))
            val dbBytes = directorySize(dataDir) - directorySize(dataDir.resolve("blobs"))
            val blobBytes = directorySize(dataDir.resolve("blobs"))
            say("- On disk: database and write-ahead log ${mib(dbBytes)} (${dbBytes / events} bytes per event), blobs ${mib(blobBytes)}")
            say()

            // ---- steady state and ramp, with every member holding an event stream ----
            val window = java.util.concurrent.atomic.AtomicReference(Window())
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val pushedAt = ConcurrentHashMap<Long, Long>()
            val streams = mutableListOf<SseReader>()
            val readers = (0 until members).map { m ->
                val stream = SseReader(http, "${server.baseUrl}/api/v1/circles/${circle.v}/events/stream", tokens[m]).also { streams += it }
                thread(isDaemon = true, name = "reader-$m") {
                    var cursor = head.get()
                    val jitter = Random(m.toLong())
                    while (running.get()) {
                        val hint = stream.nextHint(1_000) ?: continue
                        if (hint <= cursor) continue
                        if (m % SAMPLE_READERS_FOR_DELIVERY == 0) pushedAt[hint]?.let { sent -> window.get().hint.record(System.nanoTime() - sent) }
                        Thread.sleep(jitter.nextInt(PULL_DEBOUNCE_MILLIS).toLong())
                        while (running.get()) {
                            val w = window.get()
                            val started = System.nanoTime()
                            val page = runCatching { runBlocking { api.pull(tokens[m]!!, circle, cursor, PULL_PAGE) }.second }.getOrNull() ?: break
                            val ended = System.nanoTime()
                            w.pull.record(ended - started)
                            if (m % SAMPLE_READERS_FOR_DELIVERY == 0) page.events.forEach { e -> pushedAt[e.serverSeq]?.let { sent -> w.delivery.record(ended - sent) } }
                            cursor = page.next
                            if (!page.hasMore) break
                        }
                    }
                }
            }
            Thread.sleep(2_000) // let every stream connect and be greeted
            val blobReader = thread(isDaemon = true, name = "blob-reader") {
                val r = Random(7)
                while (running.get()) {
                    val item = items[r.nextInt(items.size)]
                    runCatching { getBlob(server.baseUrl, circle, tokens[r.nextInt(members)]!!, item.blobHash) }
                    Thread.sleep(1_000L / BLOB_READS_PER_SECOND)
                }
            }

            say("## Steady state and ramp")
            say()
            say("Every member holds an event stream open (so ${members} streams). A hint triggers a pull after a random delay of up to ${PULL_DEBOUNCE_MILLIS / 1000} s, as a client would debounce. Writers push one sealed event per request. About $BLOB_READS_PER_SECOND photo downloads per second run alongside.")
            say()
            say("| Writes/s asked | Writes/s done | Push p50 / p95 / p99 (ms) | Hint reaches a member p50 / p95 (ms) | Pull p95 (ms) | Event pulled, incl. debounce p50 / p95 (ms) | Errors | Server CPU (cores) | Server RSS peak | Threads |")
            say("|---|---|---|---|---|---|---|---|---|---|")
            var lastGood = 0
            for ((index, rate) in rates.withIndex()) {
                val seconds = if (index == 0) steadySeconds else 20
                val w = Window()
                window.set(w)
                ResourceSampler(server).use { sampler ->
                    val deadline = System.nanoTime() + seconds * 1_000_000_000L
                    val perThreadDelayNanos = (WRITER_THREADS * 1_000_000_000.0 / rate).toLong()
                    val writers = (0 until WRITER_THREADS).map { t ->
                        thread(isDaemon = true, name = "writer-$t") {
                            val r = Random(1000L + t)
                            var next = System.nanoTime() + r.nextInt((perThreadDelayNanos / 1_000_000).toInt().coerceAtLeast(1)) * 1_000_000L
                            while (System.nanoTime() < deadline) {
                                val now = System.nanoTime()
                                if (now < next) {
                                    Thread.sleep(((next - now) / 1_000_000).coerceAtLeast(1))
                                    continue
                                }
                                next += perThreadDelayNanos
                                val m = r.nextInt(members)
                                val event = people[m].invite(circle, InviteKey(crypto.generateSigningKeyPair()), today + 30, 1, today, "n".repeat(120))
                                val sent = System.nanoTime()
                                w.sent.incrementAndGet()
                                try {
                                    val result = runBlocking { api.push(tokens[m]!!, circle, listOf(event)) }.second.single()
                                    w.push.record(System.nanoTime() - sent)
                                    if (result.status == PushStatus.ACCEPTED) {
                                        pushedAt[result.serverSeq!!] = sent
                                        head.accumulateAndGet(result.serverSeq!!) { a, b -> maxOf(a, b) }
                                    } else {
                                        w.failed.incrementAndGet()
                                    }
                                } catch (_: Exception) {
                                    w.failed.incrementAndGet()
                                }
                            }
                        }
                    }
                    writers.forEach { it.join() }
                    Thread.sleep(PULL_DEBOUNCE_MILLIS + 1_000L) // let the last hints be delivered
                    val res = sampler.window()
                    val done = w.sent.get() - w.failed.get()
                    val push = w.push.summary()
                    val errors = w.failed.get()
                    say("| $rate | %.1f | %.0f / %.0f / %.0f | %.0f / %.0f | %.0f | %.0f / %.0f | %d | %.2f | %.0f MiB | %d |".format(
                        done / (seconds.toDouble()), push.p50, push.p95, push.p99, w.hint.summary().p50, w.hint.summary().p95, w.pull.summary().p95, w.delivery.summary().p50, w.delivery.summary().p95,
                        errors, res.cpuCores, res.rssMaxMb, res.threadsMax,
                    ))
                    lastGood = rate
                    if (push.p95 > BREAKING_P95_PUSH_MILLIS || (w.sent.get() > 0 && errors.toDouble() / w.sent.get() > BREAKING_ERROR_RATE)) {
                        say()
                        say("Stopped the ramp at $rate writes/s: push p95 over ${BREAKING_P95_PUSH_MILLIS.toInt()} ms or more than 1% errors.")
                        return@use
                    }
                }
                if (lastGood != rate) break
            }
            running.set(false)
            readers.forEach { it.join(5_000) }
            blobReader.join(5_000)
            streams.forEach { it.close() }
            say()

            // ---- a new device catches up ----
            val coldToken = tokens[members - 1]!!
            val coldStart = System.nanoTime()
            var cursor = 0L
            var bytes = 0L
            var pages = 0
            while (true) {
                val reply = runBlocking { api.pull(coldToken, circle, cursor, PULL_PAGE) }
                bytes += reply.first.body.length
                pages++
                val page = reply.second ?: break
                cursor = page.next
                if (!page.hasMore) break
            }
            val coldSeconds = (System.nanoTime() - coldStart) / 1e9
            say("## A new device catches up")
            say()
            say("Pulling the whole log (${head.get()} events) in $pages pages: %.1f s, %s downloaded.".format(coldSeconds, mib(bytes)))
            say()

            // ---- idle and on disk ----
            Thread.sleep(5_000)
            val idleRss = server.rssKb() / 1024.0
            say("## After the load")
            say()
            say("- Idle server memory (RSS): %.0f MiB, %d threads".format(idleRss, server.threads()))
            val finalDb = directorySize(dataDir) - directorySize(dataDir.resolve("blobs"))
            say("- On disk: database and log ${mib(finalDb)}, blobs ${mib(directorySize(dataDir.resolve("blobs")))}")
            say()

            // ---- operator tools, against the data directory the server is using ----
            say("## Operator tools (run while the server is up and idle)")
            say()
            val backupDir = Files.createDirectories(work.resolve("backups"))
            val exportFile = work.resolve("circle.zip")
            val env = mapOf("LENDGER_DATA_DIR" to dataDir.toString())
            val backup = timed(jar, heapMb, env, "backup", backupDir.toString())
            val backupFile = Files.list(backupDir).use { it.toList() }.single()
            say("- `backup`: %.1f s, archive %s".format(backup, mib(Files.size(backupFile))))
            val verifyBackup = timed(jar, heapMb, env, "verify", backupFile.toString())
            say("- `verify` of that backup: %.1f s".format(verifyBackup))
            val export = timed(jar, heapMb, env, "export", circle.v, exportFile.toString())
            say("- `export`: %.1f s, file %s".format(export, mib(Files.size(exportFile))))
            val verifyExport = timed(jar, heapMb, env, "verify", exportFile.toString())
            say("- `verify` of that export: %.1f s".format(verifyExport))
        }

        Files.writeString(outDir.resolve("report.md"), report.toString())
        say()
        say("(Report written to ${outDir.resolve("report.md")})")
        work.toFile().deleteRecursively()
    }

    private fun timed(jar: Path, heapMb: Int, env: Map<String, String>, vararg args: String): Double {
        val builder = ProcessBuilder(listOf("java", "-Xmx${heapMb}m", "-jar", jar.toString()) + args)
        builder.environment().putAll(env)
        builder.redirectErrorStream(true)
        val started = System.nanoTime()
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES) && process.exitValue() == 0) { "${args.first()} failed: $output" }
        return (System.nanoTime() - started) / 1e9
    }

    private fun putBlob(baseUrl: String, circle: CircleId, token: String, hash: String, bytes: ByteArray) {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1/circles/${circle.v}/blobs/$hash"))
            .header("Authorization", "Bearer $token").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()
        val response = http.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..201) { "blob upload answered ${response.statusCode()}" }
    }

    private fun getBlob(baseUrl: String, circle: CircleId, token: String, hash: String) {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/api/v1/circles/${circle.v}/blobs/$hash")).header("Authorization", "Bearer $token").build()
        http.send(request, HttpResponse.BodyHandlers.discarding())
    }

    private fun serverJava(): String {
        val process = ProcessBuilder("java", "-version").redirectErrorStream(true).start()
        return process.inputStream.bufferedReader().readLines().firstOrNull { it.contains("version") } ?: "unknown"
    }

    private fun cpuModel() = java.io.File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(":")?.trim() ?: "unknown CPU"

    private fun memTotalGb() = java.io.File("/proc/meminfo").readLines().firstOrNull { it.startsWith("MemTotal") }?.split(Regex("\\s+"))?.get(1)?.toLong()?.div(1_048_576) ?: 0
}
