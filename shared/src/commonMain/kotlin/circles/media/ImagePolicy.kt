package circles.media

import circles.event.MAX_BLOB_PLAINTEXT_BYTES

/**
 * The numbers and checks of ADR 0028: how a client must prepare a photo before it is sealed and uploaded. Decoding and
 * encoding need platform image APIs and stay in each client; everything here is arithmetic and byte inspection, so the
 * browser and the tests apply identical rules.
 */
object ImagePolicy {
    /** Refuse inputs larger than this many bytes, or with more pixels than [MAX_INPUT_PIXELS] (decompression bombs). */
    const val MAX_INPUT_BYTES = 25L * 1024 * 1024
    const val MAX_INPUT_PIXELS = 40_000_000L

    const val MAX_EDGE_PX = 1600
    const val FALLBACK_EDGE_PX = 1200
    const val THUMBNAIL_EDGE_PX = 320

    const val QUALITY_START = 80
    const val QUALITY_FLOOR = 60
    const val QUALITY_STEP = 5
    const val THUMBNAIL_QUALITY_START = 70
    const val THUMBNAIL_QUALITY_FLOOR = 50

    const val TARGET_PHOTO_BYTES = 400 * 1024
    const val TARGET_THUMBNAIL_BYTES = 40 * 1024
    const val MAX_PHOTOS_PER_ITEM = 6

    /** Whether an input may be decoded at all, judged from its size and, once the header is read, its dimensions. */
    sealed interface InputCheck {
        data object Ok : InputCheck

        data object Empty : InputCheck

        data object TooLarge : InputCheck

        data object TooManyPixels : InputCheck
    }

    fun checkInput(byteSize: Long, width: Int, height: Int): InputCheck =
        when {
            byteSize <= 0 || width <= 0 || height <= 0 -> InputCheck.Empty
            byteSize > MAX_INPUT_BYTES -> InputCheck.TooLarge
            width.toLong() * height.toLong() > MAX_INPUT_PIXELS -> InputCheck.TooManyPixels
            else -> InputCheck.Ok
        }

    data class Size(val width: Int, val height: Int)

    /** The size to scale to so the longest edge is at most [maxEdge]: aspect kept, never enlarged, never below 1 pixel. */
    fun scaledSize(width: Int, height: Int, maxEdge: Int): Size {
        require(width > 0 && height > 0 && maxEdge > 0) { "sizes must be positive" }
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return Size(width, height)
        // Integer rounding to the nearest pixel: (value * maxEdge + longest / 2) / longest.
        fun scale(value: Int): Int = ((value.toLong() * maxEdge + longest / 2) / longest).toInt().coerceAtLeast(1)
        return Size(scale(width), scale(height))
    }

    /** One way to encode an image: scale to [maxEdge] and encode at [quality] (1 to 100). */
    data class EncodeAttempt(val maxEdge: Int, val quality: Int)

    private fun qualitySteps(start: Int, floor: Int): List<Int> = generateSequence(start) { it - QUALITY_STEP }.takeWhile { it >= floor }.toList()

    /** The photo attempts in order: 1600 px at 80, 75 ... 60, then 1200 px at the same qualities, then give up. */
    val photoAttempts: List<EncodeAttempt> =
        listOf(MAX_EDGE_PX, FALLBACK_EDGE_PX).flatMap { edge -> qualitySteps(QUALITY_START, QUALITY_FLOOR).map { EncodeAttempt(edge, it) } }

    /** The thumbnail attempts: 320 px at 70, 65 ... 50. A thumbnail is best effort (see [judgeThumbnail]). */
    val thumbnailAttempts: List<EncodeAttempt> =
        qualitySteps(THUMBNAIL_QUALITY_START, THUMBNAIL_QUALITY_FLOOR).map { EncodeAttempt(THUMBNAIL_EDGE_PX, it) }

    /** What to do after an encode attempt. */
    sealed interface Step {
        data object Accept : Step

        data class Retry(val next: EncodeAttempt) : Step

        /** Tell the user the photo cannot be made small enough; upload nothing. */
        data object GiveUp : Step
    }

    /** True if a plain (unsealed) result still fits in one blob once sealed (ADR 0026). */
    fun fitsInBlob(plainBytes: Int): Boolean = plainBytes in 1..MAX_BLOB_PLAINTEXT_BYTES

    /** After attempt number [attemptIndex] (from 0) produced [resultBytes] bytes: accept, try the next one, or give up. */
    fun judgePhoto(attemptIndex: Int, resultBytes: Int): Step {
        require(attemptIndex in photoAttempts.indices) { "no such attempt" }
        return when {
            resultBytes <= TARGET_PHOTO_BYTES && fitsInBlob(resultBytes) -> Step.Accept
            attemptIndex + 1 < photoAttempts.size -> Step.Retry(photoAttempts[attemptIndex + 1])
            else -> Step.GiveUp
        }
    }

    /**
     * Thumbnails aim for [TARGET_THUMBNAIL_BYTES]; if the last attempt is still over, it is accepted as long as it fits
     * in a blob, because a slightly large thumbnail is better than none.
     */
    fun judgeThumbnail(attemptIndex: Int, resultBytes: Int): Step {
        require(attemptIndex in thumbnailAttempts.indices) { "no such attempt" }
        return when {
            resultBytes <= TARGET_THUMBNAIL_BYTES && fitsInBlob(resultBytes) -> Step.Accept
            attemptIndex + 1 < thumbnailAttempts.size -> Step.Retry(thumbnailAttempts[attemptIndex + 1])
            fitsInBlob(resultBytes) -> Step.Accept
            else -> Step.GiveUp
        }
    }

    /** True if an item with [existingPhotos] photos may take another. */
    fun canAddPhoto(existingPhotos: Int): Boolean = existingPhotos in 0 until MAX_PHOTOS_PER_ITEM

    // ---- metadata scan ----

    enum class ImageFormat { JPEG, WEBP, UNKNOWN }

    /**
     * What a scan of an encoded image found. [found] names metadata containers present (`EXIF`, `XMP`, `IPTC`, `ICC`,
     * `COMMENT`); [wellFormed] is false if the structure ran past the end of the data.
     */
    data class MetadataScan(val format: ImageFormat, val found: List<String>, val wellFormed: Boolean) {
        /** True only for a recognised, well-formed image carrying none of the metadata above: safe to seal and upload. */
        val isClean: Boolean get() = format != ImageFormat.UNKNOWN && wellFormed && found.isEmpty()
    }

    /** Walks the container structure of a JPEG or WebP and reports metadata segments. Never throws on bad input. */
    fun scanMetadata(bytes: ByteArray): MetadataScan =
        when {
            bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> scanJpeg(bytes)
            bytes.size >= 12 && bytes.startsWith("RIFF") && bytes.matchesAt(8, "WEBP") -> scanWebp(bytes)
            else -> MetadataScan(ImageFormat.UNKNOWN, emptyList(), wellFormed = false)
        }

    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_SOS = 0xDA
    private const val JPEG_EOI = 0xD9
    private const val JPEG_APP1 = 0xE1
    private const val JPEG_APP2 = 0xE2
    private const val JPEG_APP13 = 0xED
    private const val JPEG_COM = 0xFE
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    private fun scanJpeg(bytes: ByteArray): MetadataScan {
        val found = linkedSetOf<String>()
        var at = 2
        while (at + 1 < bytes.size) {
            if ((bytes[at].toInt() and BYTE_MASK) != JPEG_MARKER_PREFIX) return MetadataScan(ImageFormat.JPEG, found.toList(), wellFormed = false)
            val marker = bytes[at + 1].toInt() and BYTE_MASK
            if (marker == JPEG_MARKER_PREFIX) { // fill byte
                at++
                continue
            }
            if (marker == JPEG_SOS || marker == JPEG_EOI) return MetadataScan(ImageFormat.JPEG, found.toList(), wellFormed = true)
            if (at + 3 >= bytes.size) return MetadataScan(ImageFormat.JPEG, found.toList(), wellFormed = false)
            val length = ((bytes[at + 2].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (bytes[at + 3].toInt() and BYTE_MASK)
            val end = at + 2 + length
            if (length < 2 || end > bytes.size) return MetadataScan(ImageFormat.JPEG, found.toList(), wellFormed = false)
            val body = at + 4
            when (marker) {
                JPEG_APP1 -> when {
                    bytes.matchesAt(body, "Exif\u0000\u0000") -> found += "EXIF"
                    bytes.matchesAt(body, "http://ns.adobe.com/xap/") -> found += "XMP"
                    else -> found += "OTHER" // any other APP1 is unknown metadata; treat it as unsafe
                }
                JPEG_APP2 -> found += if (bytes.matchesAt(body, "ICC_PROFILE")) "ICC" else "OTHER"
                JPEG_APP13 -> found += "IPTC"
                JPEG_COM -> found += "COMMENT"
            }
            at = end
        }
        return MetadataScan(ImageFormat.JPEG, found.toList(), wellFormed = false) // no SOS or EOI before the end
    }

    private const val RIFF_HEADER_BYTES = 12
    private const val RIFF_CHUNK_HEADER_BYTES = 8

    private fun scanWebp(bytes: ByteArray): MetadataScan {
        val found = linkedSetOf<String>()
        var at = RIFF_HEADER_BYTES
        while (at < bytes.size) {
            if (at + RIFF_CHUNK_HEADER_BYTES > bytes.size) return MetadataScan(ImageFormat.WEBP, found.toList(), wellFormed = false)
            val size = (bytes[at + 4].toLong() and BYTE_MASK.toLong()) or
                ((bytes[at + 5].toLong() and BYTE_MASK.toLong()) shl 8) or
                ((bytes[at + 6].toLong() and BYTE_MASK.toLong()) shl 16) or
                ((bytes[at + 7].toLong() and BYTE_MASK.toLong()) shl 24)
            val next = at + RIFF_CHUNK_HEADER_BYTES + size + (size and 1)
            if (next > bytes.size) return MetadataScan(ImageFormat.WEBP, found.toList(), wellFormed = false)
            when {
                bytes.matchesAt(at, "EXIF") -> found += "EXIF"
                bytes.matchesAt(at, "XMP ") -> found += "XMP"
                bytes.matchesAt(at, "ICCP") -> found += "ICC"
            }
            at = next.toInt()
        }
        return MetadataScan(ImageFormat.WEBP, found.toList(), wellFormed = true)
    }

    // ---- removing the colour profile ----

    private const val VP8X_ICC_FLAG = 0x20
    private const val RIFF_SIZE_AT = 4

    /**
     * Removes an embedded colour profile (ICC) from a JPEG or WebP, as ADR 0028 asks: a browser's encoder adds an sRGB
     * profile, which says nothing about the person but is still a metadata container, and the scan refuses any. Everything
     * else is copied byte for byte. Returns [bytes] unchanged if it is not a well-formed JPEG or WebP.
     */
    fun stripIcc(bytes: ByteArray): ByteArray =
        when {
            bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> stripJpegIcc(bytes)
            bytes.size >= RIFF_HEADER_BYTES && bytes.startsWith("RIFF") && bytes.matchesAt(8, "WEBP") -> stripWebpIcc(bytes)
            else -> bytes
        }

    private fun stripJpegIcc(bytes: ByteArray): ByteArray {
        val out = ArrayList<Byte>(bytes.size)
        out += bytes[0]
        out += bytes[1]
        var at = 2
        while (at + 3 < bytes.size) {
            if ((bytes[at].toInt() and BYTE_MASK) != JPEG_MARKER_PREFIX) return bytes
            val marker = bytes[at + 1].toInt() and BYTE_MASK
            if (marker == JPEG_MARKER_PREFIX) return bytes
            if (marker == JPEG_SOS || marker == JPEG_EOI) {
                for (i in at until bytes.size) out += bytes[i] // the picture data and the end, untouched
                return out.toByteArray()
            }
            val length = ((bytes[at + 2].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (bytes[at + 3].toInt() and BYTE_MASK)
            val end = at + 2 + length
            if (length < 2 || end > bytes.size) return bytes
            if (!(marker == JPEG_APP2 && bytes.matchesAt(at + 4, "ICC_PROFILE"))) for (i in at until end) out += bytes[i]
            at = end
        }
        return bytes
    }

    private fun stripWebpIcc(bytes: ByteArray): ByteArray {
        // The declared size must match the data we hold, or the file is cut short or padded and is left alone.
        val declared = (bytes[RIFF_SIZE_AT].toLong() and BYTE_MASK.toLong()) or ((bytes[RIFF_SIZE_AT + 1].toLong() and BYTE_MASK.toLong()) shl 8) or
            ((bytes[RIFF_SIZE_AT + 2].toLong() and BYTE_MASK.toLong()) shl 16) or ((bytes[RIFF_SIZE_AT + 3].toLong() and BYTE_MASK.toLong()) shl 24)
        if (declared != (bytes.size - RIFF_CHUNK_HEADER_BYTES).toLong()) return bytes
        val out = ArrayList<Byte>(bytes.size)
        for (i in 0 until RIFF_HEADER_BYTES) out += bytes[i]
        var at = RIFF_HEADER_BYTES
        while (at < bytes.size) {
            if (at + RIFF_CHUNK_HEADER_BYTES > bytes.size) return bytes
            val size = (bytes[at + 4].toLong() and BYTE_MASK.toLong()) or ((bytes[at + 5].toLong() and BYTE_MASK.toLong()) shl 8) or
                ((bytes[at + 6].toLong() and BYTE_MASK.toLong()) shl 16) or ((bytes[at + 7].toLong() and BYTE_MASK.toLong()) shl 24)
            val next = at + RIFF_CHUNK_HEADER_BYTES + size + (size and 1)
            if (next > bytes.size) return bytes
            if (!bytes.matchesAt(at, "ICCP")) {
                for (i in at until next.toInt()) out += bytes[i]
                // The extended header lists what the file holds; the colour profile flag must go with the chunk.
                if (bytes.matchesAt(at, "VP8X") && size >= 1) out[out.size - (next.toInt() - at) + RIFF_CHUNK_HEADER_BYTES] = (bytes[at + RIFF_CHUNK_HEADER_BYTES].toInt() and VP8X_ICC_FLAG.inv()).toByte()
            }
            at = next.toInt()
        }
        val result = out.toByteArray()
        val total = result.size - RIFF_CHUNK_HEADER_BYTES
        for (i in 0 until 4) result[RIFF_SIZE_AT + i] = (total shr (BITS_PER_BYTE * i)).toByte()
        return result
    }

    private fun ByteArray.matchesAt(at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > size) return false
        return text.indices.all { this[at + it] == text[it].code.toByte() }
    }

    private fun ByteArray.startsWith(text: String): Boolean = matchesAt(0, text)
}
