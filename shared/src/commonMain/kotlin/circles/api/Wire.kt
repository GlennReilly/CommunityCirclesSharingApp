package circles.api

import circles.event.EnvelopeDto
import circles.export.ExportManifest
import circles.json.Json
import circles.json.JsonNode
import circles.keys.StoredIdentity
import circles.keys.WrappedSeed

/**
 * The JSON of the shapes the browser sends, reads and stores, written by hand over [Json] so the bundle does not carry
 * kotlinx-serialization's JSON support (ADR 0054). The server and the tests still use [ApiJson]; tests check both agree on
 * every shape, byte for byte on the way out and value for value on the way in. Readers refuse unknown fields, missing
 * required fields and wrong types, like [ApiJson]; they return null instead of throwing.
 */
object Wire {
    // ---- writing ----

    private fun obj(vararg fields: Pair<String, JsonNode?>) = JsonNode.Obj(fields.filter { it.second != null }.associate { it.first to it.second!! })

    private fun str(value: String?): JsonNode? = value?.let { JsonNode.Str(it) }

    private fun num(value: Number?): JsonNode? = value?.let { JsonNode.Num(it.toLong()) }

    fun envelopeNode(d: EnvelopeDto): JsonNode = obj(
        "v" to num(d.v), "circle" to str(d.circle), "author" to str(d.author), "device" to str(d.device), "type" to str(d.type), "day" to num(d.day),
        "ctr" to num(d.ctr), "epoch" to num(d.epoch), "public" to str(d.public), "nonce" to str(d.nonce), "encrypted" to str(d.encrypted), "sig" to str(d.sig),
    )

    fun encode(d: EnvelopeDto): String = Json.write(envelopeNode(d))

    fun encode(d: SingleEventRequest): String = Json.write(obj("event" to envelopeNode(d.event)))

    fun encode(d: PushRequest): String = Json.write(obj("events" to JsonNode.Arr(d.events.map { envelopeNode(it) })))

    fun encode(d: ChallengeRequest): String = Json.write(obj("deviceId" to str(d.deviceId)))

    fun encode(d: SessionRequest): String = Json.write(obj("deviceId" to str(d.deviceId), "nonce" to str(d.nonce), "sig" to str(d.sig)))

    private fun wrappedNode(w: WrappedSeed): JsonNode = obj(
        "v" to num(w.v), "method" to str(w.method), "memoryKib" to num(w.memoryKib), "iterations" to num(w.iterations),
        "parallelism" to num(w.parallelism), "salt" to str(w.salt), "sealed" to str(w.sealed),
    )

    fun encode(d: StoredIdentity): String = Json.write(
        obj(
            "member" to str(d.member), "device" to str(d.device), "wrapped" to wrappedNode(d.wrapped), "circle" to str(d.circle),
            "autoLockMinutes" to num(d.autoLockMinutes), "pairing" to JsonNode.Bool(d.pairing).takeIf { d.pairing },
            "passkey" to d.passkey?.let { obj("credential" to str(it.credential), "salt" to str(it.salt), "sealed" to str(it.sealed)) },
        ),
    )

    fun encode(d: ExportManifest): String = Json.write(
        obj(
            "format" to str(d.format), "circle" to str(d.circle), "eventCount" to num(d.eventCount), "lastSeq" to num(d.lastSeq), "eventsHash" to str(d.eventsHash),
            "blobs" to JsonNode.Arr(d.blobs.map { obj("hash" to str(it.hash), "size" to num(it.size)) }),
        ),
    )

    // ---- reading ----

    /** An object whose fields are all in [allowed]; null from any accessor means missing or the wrong type. */
    private class Fields(node: JsonNode?, allowed: Set<String>) {
        private val fields: Map<String, JsonNode>? = (node as? JsonNode.Obj)?.fields?.takeIf { f -> f.keys.all { it in allowed } }
        val ok: Boolean get() = fields != null
        private fun get(name: String): JsonNode? = fields?.get(name)?.takeIf { it != JsonNode.Null }
        fun str(name: String): String? = (get(name) as? JsonNode.Str)?.value
        fun long(name: String): Long? = (get(name) as? JsonNode.Num)?.value
        fun int(name: String): Int? = long(name)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        fun bool(name: String): Boolean? = (get(name) as? JsonNode.Bool)?.value
        fun node(name: String): JsonNode? = get(name)
        fun has(name: String): Boolean = get(name) != null
    }

    private fun envelopeFrom(node: JsonNode?): EnvelopeDto? {
        val f = Fields(node, setOf("v", "circle", "author", "device", "type", "day", "ctr", "epoch", "public", "nonce", "encrypted", "sig"))
        if (!f.ok) return null
        return EnvelopeDto(
            v = f.int("v") ?: return null, circle = f.str("circle") ?: return null, author = f.str("author") ?: return null, device = f.str("device") ?: return null,
            type = f.str("type") ?: return null, day = f.int("day") ?: return null, ctr = f.long("ctr") ?: return null, epoch = f.int("epoch") ?: return null,
            public = f.str("public") ?: return null, nonce = f.str("nonce") ?: return null, encrypted = f.str("encrypted") ?: return null, sig = f.str("sig") ?: return null,
        )
    }

    fun decodeEnvelope(text: String): EnvelopeDto? = envelopeFrom(Json.parse(text))

    fun decodeChallenge(text: String): ChallengeResponse? {
        val f = Fields(Json.parse(text), setOf("nonce"))
        return if (f.ok) ChallengeResponse(f.str("nonce") ?: return null) else null
    }

    fun decodeSession(text: String): SessionResponse? {
        val f = Fields(Json.parse(text), setOf("token", "expiresIn"))
        return if (f.ok) SessionResponse(f.str("token") ?: return null, f.long("expiresIn") ?: return null) else null
    }

    private fun pushResultFrom(node: JsonNode?): PushResult? {
        val f = Fields(node, setOf("id", "status", "serverSeq", "reason", "detail"))
        if (!f.ok) return null
        val status = PushStatus.entries.firstOrNull { it.wire == f.str("status") } ?: return null
        if (f.has("serverSeq") && f.long("serverSeq") == null) return null
        return PushResult(f.str("id"), status, f.long("serverSeq"), f.str("reason"), f.str("detail"))
    }

    fun decodePushResult(text: String): PushResult? = pushResultFrom(Json.parse(text))

    fun decodePushResults(text: String): List<PushResult>? {
        val items = (Json.parse(text) as? JsonNode.Arr)?.items ?: return null
        return items.map { pushResultFrom(it) ?: return null }
    }

    fun decodePull(text: String): PullResponse? {
        val f = Fields(Json.parse(text), setOf("events", "next", "hasMore"))
        if (!f.ok) return null
        val events = (f.node("events") as? JsonNode.Arr)?.items?.map { item ->
            val e = Fields(item, setOf("serverSeq", "envelope"))
            if (!e.ok) return null
            StoredEvent(e.long("serverSeq") ?: return null, envelopeFrom(e.node("envelope")) ?: return null)
        } ?: return null
        return PullResponse(events, f.long("next") ?: return null, f.bool("hasMore") ?: return null)
    }

    private fun wrappedFrom(node: JsonNode?): WrappedSeed? {
        val f = Fields(node, setOf("v", "method", "memoryKib", "iterations", "parallelism", "salt", "sealed"))
        if (!f.ok) return null
        return WrappedSeed(
            f.int("v") ?: return null, f.str("method") ?: return null, f.int("memoryKib") ?: return null, f.int("iterations") ?: return null,
            f.int("parallelism") ?: return null, f.str("salt") ?: return null, f.str("sealed") ?: return null,
        )
    }

    fun decodeStoredIdentity(text: String): StoredIdentity? {
        val f = Fields(Json.parse(text), setOf("member", "device", "wrapped", "circle", "autoLockMinutes", "pairing", "passkey"))
        if (!f.ok) return null
        if (f.has("circle") && f.str("circle") == null) return null
        if (f.has("autoLockMinutes") && f.int("autoLockMinutes") == null) return null
        if (f.has("pairing") && f.bool("pairing") == null) return null
        val passkey = if (f.has("passkey")) {
            val p = Fields(f.node("passkey"), setOf("credential", "salt", "sealed"))
            if (!p.ok) return null
            circles.keys.PasskeyWrap(p.str("credential") ?: return null, p.str("salt") ?: return null, p.str("sealed") ?: return null)
        } else {
            null
        }
        return StoredIdentity(
            member = f.str("member") ?: return null, device = f.str("device") ?: return null, wrapped = wrappedFrom(f.node("wrapped")) ?: return null,
            circle = f.str("circle"), autoLockMinutes = f.int("autoLockMinutes"), pairing = f.bool("pairing") ?: false, passkey = passkey,
        )
    }
}
