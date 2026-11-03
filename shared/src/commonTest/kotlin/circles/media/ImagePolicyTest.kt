package circles.media

import circles.event.MAX_BLOB_PLAINTEXT_BYTES
import circles.media.ImagePolicy.EncodeAttempt
import circles.media.ImagePolicy.ImageFormat
import circles.media.ImagePolicy.InputCheck
import circles.media.ImagePolicy.Size
import circles.media.ImagePolicy.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ADR 0028: the numbers, the size arithmetic and the metadata scan every client applies to a photo. */
class ImagePolicyTest {
    // ---- the numbers ----

    @Test
    fun theNumbersAreThoseOfAdr0028() {
        assertEquals(25L * 1024 * 1024, ImagePolicy.MAX_INPUT_BYTES)
        assertEquals(40_000_000L, ImagePolicy.MAX_INPUT_PIXELS)
        assertEquals(1600, ImagePolicy.MAX_EDGE_PX)
        assertEquals(1200, ImagePolicy.FALLBACK_EDGE_PX)
        assertEquals(320, ImagePolicy.THUMBNAIL_EDGE_PX)
        assertEquals(400 * 1024, ImagePolicy.TARGET_PHOTO_BYTES)
        assertEquals(40 * 1024, ImagePolicy.TARGET_THUMBNAIL_BYTES)
        assertEquals(6, ImagePolicy.MAX_PHOTOS_PER_ITEM)
    }

    @Test
    fun aTypicalPhotoFitsEasilyInOneBlob() {
        assertTrue(ImagePolicy.TARGET_PHOTO_BYTES < MAX_BLOB_PLAINTEXT_BYTES / 4)
    }

    // ---- input checks ----

    @Test
    fun inputsAreRefusedWhenEmptyTooBigOrTooWide() {
        assertEquals(InputCheck.Ok, ImagePolicy.checkInput(5_000_000, 4032, 3024))
        assertEquals(InputCheck.Ok, ImagePolicy.checkInput(ImagePolicy.MAX_INPUT_BYTES, 100, 100))
        assertEquals(InputCheck.TooLarge, ImagePolicy.checkInput(ImagePolicy.MAX_INPUT_BYTES + 1, 100, 100))
        assertEquals(InputCheck.Ok, ImagePolicy.checkInput(1_000, 8000, 5000), "exactly 40 megapixels")
        assertEquals(InputCheck.TooManyPixels, ImagePolicy.checkInput(1_000, 8001, 5000))
        assertEquals(InputCheck.TooManyPixels, ImagePolicy.checkInput(1_000, 100_000, 100_000), "no overflow on huge dimensions")
        assertEquals(InputCheck.Empty, ImagePolicy.checkInput(0, 100, 100))
        assertEquals(InputCheck.Empty, ImagePolicy.checkInput(1_000, 0, 100))
        assertEquals(InputCheck.Empty, ImagePolicy.checkInput(1_000, 100, -5))
        assertEquals(InputCheck.TooLarge, ImagePolicy.checkInput(Long.MAX_VALUE, 100, 100))
    }

    // ---- scaling ----

    @Test
    fun scalingKeepsTheAspectAndNeverEnlarges() {
        assertEquals(Size(1600, 1200), ImagePolicy.scaledSize(4000, 3000, 1600))
        assertEquals(Size(1200, 1600), ImagePolicy.scaledSize(3000, 4000, 1600))
        assertEquals(Size(1600, 1200), ImagePolicy.scaledSize(4032, 3024, 1600))
        assertEquals(Size(1600, 1600), ImagePolicy.scaledSize(2000, 2000, 1600))
        assertEquals(Size(800, 600), ImagePolicy.scaledSize(800, 600, 1600), "smaller photos are left alone")
        assertEquals(Size(1600, 900), ImagePolicy.scaledSize(1600, 900, 1600), "exactly at the limit")
        assertEquals(Size(320, 240), ImagePolicy.scaledSize(1600, 1200, 320))
    }

    @Test
    fun extremeShapesNeverScaleBelowOnePixel() {
        assertEquals(Size(1600, 2), ImagePolicy.scaledSize(10_000, 10, 1600))
        assertEquals(Size(1, 1600), ImagePolicy.scaledSize(1, 10_000, 1600))
        assertEquals(Size(1600, 1), ImagePolicy.scaledSize(100_000, 1, 1600))
    }

    @Test
    fun scalingRoundsToTheNearestPixel() {
        assertEquals(Size(1600, 1067), ImagePolicy.scaledSize(6000, 4000, 1600), "1066.67 rounds up")
        assertEquals(Size(1600, 1066), ImagePolicy.scaledSize(6000, 3999, 1600), "1066.4 rounds down")
        assertEquals(Size(1200, 900), ImagePolicy.scaledSize(1600, 1200, 1200))
    }

    @Test
    fun scalingRefusesNonsenseSizes() {
        assertFailsWith<IllegalArgumentException> { ImagePolicy.scaledSize(0, 10, 100) }
        assertFailsWith<IllegalArgumentException> { ImagePolicy.scaledSize(10, -1, 100) }
        assertFailsWith<IllegalArgumentException> { ImagePolicy.scaledSize(10, 10, 0) }
    }

    // ---- attempts and judging ----

    @Test
    fun thePhotoAttemptsAreTheLadderOfAdr0028() {
        val expected = listOf(80, 75, 70, 65, 60).map { EncodeAttempt(1600, it) } + listOf(80, 75, 70, 65, 60).map { EncodeAttempt(1200, it) }
        assertEquals(expected, ImagePolicy.photoAttempts)
    }

    @Test
    fun theThumbnailAttemptsStepDownFromSeventy() {
        assertEquals(listOf(70, 65, 60, 55, 50).map { EncodeAttempt(320, it) }, ImagePolicy.thumbnailAttempts)
    }

    @Test
    fun aPhotoUnderTheTargetIsAcceptedAtOnce() {
        assertEquals(Step.Accept, ImagePolicy.judgePhoto(0, 150_000))
        assertEquals(Step.Accept, ImagePolicy.judgePhoto(0, ImagePolicy.TARGET_PHOTO_BYTES), "exactly at the target")
    }

    @Test
    fun aPhotoOverTheTargetTriesTheNextAttemptInOrderThenGivesUp() {
        val over = ImagePolicy.TARGET_PHOTO_BYTES + 1
        ImagePolicy.photoAttempts.indices.toList().dropLast(1).forEach { i ->
            assertEquals(Step.Retry(ImagePolicy.photoAttempts[i + 1]), ImagePolicy.judgePhoto(i, over), "after attempt $i")
        }
        assertEquals(Step.GiveUp, ImagePolicy.judgePhoto(ImagePolicy.photoAttempts.lastIndex, over))
    }

    @Test
    fun aLaterAttemptThatFitsIsAccepted() {
        assertEquals(Step.Accept, ImagePolicy.judgePhoto(7, 380_000))
        assertEquals(EncodeAttempt(1200, 80), (ImagePolicy.judgePhoto(4, 500_000) as Step.Retry).next, "after quality 60 at 1600 comes 1200 at 80")
    }

    @Test
    fun anEmptyResultIsNeverAccepted() {
        assertIs<Step.Retry>(ImagePolicy.judgePhoto(0, 0))
        assertEquals(Step.GiveUp, ImagePolicy.judgePhoto(ImagePolicy.photoAttempts.lastIndex, 0))
    }

    @Test
    fun aThumbnailIsAcceptedUnderTargetAndOtherwiseBestEffort() {
        assertEquals(Step.Accept, ImagePolicy.judgeThumbnail(0, 20_000))
        assertEquals(Step.Accept, ImagePolicy.judgeThumbnail(0, ImagePolicy.TARGET_THUMBNAIL_BYTES))
        assertEquals(Step.Retry(EncodeAttempt(320, 65)), ImagePolicy.judgeThumbnail(0, ImagePolicy.TARGET_THUMBNAIL_BYTES + 1))
        val last = ImagePolicy.thumbnailAttempts.lastIndex
        assertEquals(Step.Accept, ImagePolicy.judgeThumbnail(last, 60_000), "still over 40 KB after the last attempt, but it fits a blob")
        assertEquals(Step.GiveUp, ImagePolicy.judgeThumbnail(last, MAX_BLOB_PLAINTEXT_BYTES + 1))
    }

    @Test
    fun theBlobCapIsEnforced() {
        assertTrue(ImagePolicy.fitsInBlob(MAX_BLOB_PLAINTEXT_BYTES))
        assertFalse(ImagePolicy.fitsInBlob(MAX_BLOB_PLAINTEXT_BYTES + 1))
        assertFalse(ImagePolicy.fitsInBlob(0))
    }

    @Test
    fun anItemTakesAtMostSixPhotos() {
        assertTrue(ImagePolicy.canAddPhoto(0))
        assertTrue(ImagePolicy.canAddPhoto(5))
        assertFalse(ImagePolicy.canAddPhoto(6))
        assertFalse(ImagePolicy.canAddPhoto(7))
        assertFalse(ImagePolicy.canAddPhoto(-1))
    }

    // ---- the metadata scan ----

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private val jfif = segment(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".encodeToByteArray())
    private val scanAndEnd = segment(0xDA, byteArrayOf(1, 2, 3, 4)) + byteArrayOf(5, 6, 7, 0xFF.toByte(), 0xD9.toByte())
    private fun jpeg(vararg segments: ByteArray) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segments.fold(ByteArray(0)) { a, b -> a + b } + scanAndEnd

    private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = "WEBP".encodeToByteArray() + chunks.fold(ByteArray(0)) { acc, (name, data) ->
            val size = data.size
            val header = name.encodeToByteArray() + byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte())
            acc + header + data + if (size % 2 == 1) byteArrayOf(0) else ByteArray(0)
        }
        val total = body.size
        return "RIFF".encodeToByteArray() + byteArrayOf(total.toByte(), (total shr 8).toByte(), (total shr 16).toByte(), (total shr 24).toByte()) + body
    }

    @Test
    fun aCleanJpegHasNoMetadata() {
        val scan = ImagePolicy.scanMetadata(jpeg(jfif, segment(0xDB, ByteArray(64)), segment(0xC0, ByteArray(15))))
        assertEquals(ImageFormat.JPEG, scan.format)
        assertEquals(emptyList(), scan.found)
        assertTrue(scan.isClean)
    }

    @Test
    fun exifXmpIptcIccAndCommentsAreFoundInAJpeg() {
        val exif = segment(0xE1, "Exif\u0000\u0000".encodeToByteArray() + ByteArray(30))
        val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".encodeToByteArray())
        val icc = segment(0xE2, "ICC_PROFILE\u0000\u0001\u0001".encodeToByteArray() + ByteArray(20))
        val iptc = segment(0xED, "Photoshop 3.0\u0000".encodeToByteArray())
        val comment = segment(0xFE, "taken at home".encodeToByteArray())
        assertEquals(listOf("EXIF"), ImagePolicy.scanMetadata(jpeg(jfif, exif)).found)
        assertEquals(listOf("XMP"), ImagePolicy.scanMetadata(jpeg(jfif, xmp)).found)
        assertEquals(listOf("ICC"), ImagePolicy.scanMetadata(jpeg(jfif, icc)).found)
        assertEquals(listOf("IPTC"), ImagePolicy.scanMetadata(jpeg(jfif, iptc)).found)
        assertEquals(listOf("COMMENT"), ImagePolicy.scanMetadata(jpeg(jfif, comment)).found)
        val all = ImagePolicy.scanMetadata(jpeg(jfif, exif, xmp, icc, iptc, comment))
        assertEquals(listOf("EXIF", "XMP", "ICC", "IPTC", "COMMENT"), all.found)
        assertFalse(all.isClean)
    }

    @Test
    fun anUnknownApplicationSegmentCountsAsMetadata() {
        assertEquals(listOf("OTHER"), ImagePolicy.scanMetadata(jpeg(jfif, segment(0xE1, "Unknown\u0000".encodeToByteArray()))).found)
        assertEquals(listOf("OTHER"), ImagePolicy.scanMetadata(jpeg(jfif, segment(0xE2, "MPF\u0000".encodeToByteArray()))).found)
    }

    @Test
    fun metadataAfterTheImageDataIsNotLookedFor() {
        // The scan stops at the start of the image data; a client's encoder never writes metadata after it.
        val scan = ImagePolicy.scanMetadata(jpeg(jfif))
        assertTrue(scan.wellFormed)
    }

    @Test
    fun aCleanWebpHasNoMetadataAndTheChunksAreFound() {
        val image = "VP8 " to ByteArray(25) { 1 }
        assertTrue(ImagePolicy.scanMetadata(riff(image)).isClean)
        assertEquals(listOf("EXIF"), ImagePolicy.scanMetadata(riff(image, "EXIF" to ByteArray(11))).found)
        assertEquals(listOf("XMP"), ImagePolicy.scanMetadata(riff(image, "XMP " to ByteArray(8))).found)
        assertEquals(listOf("ICC"), ImagePolicy.scanMetadata(riff("VP8X" to ByteArray(10), "ICCP" to ByteArray(6), image)).found)
        assertEquals(listOf("ICC", "EXIF", "XMP"), ImagePolicy.scanMetadata(riff("ICCP" to ByteArray(3), image, "EXIF" to ByteArray(3), "XMP " to ByteArray(3))).found)
        assertEquals(ImageFormat.WEBP, ImagePolicy.scanMetadata(riff(image)).format)
    }

    @Test
    fun aJpegCutBeforeItsImageDataIsNeverClean() {
        val header = jfif + segment(0xDB, ByteArray(64))
        val whole = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + header + scanAndEnd
        assertTrue(ImagePolicy.scanMetadata(whole).isClean)
        val firstImageDataByte = 2 + header.size
        (2 until firstImageDataByte).forEach { cut ->
            assertFalse(ImagePolicy.scanMetadata(whole.copyOf(cut)).isClean, "cut at $cut of ${whole.size}")
        }
    }

    @Test
    fun unrecognisedOrTruncatedDataIsNeverClean() {
        assertEquals(ImageFormat.UNKNOWN, ImagePolicy.scanMetadata("GIF89a....".encodeToByteArray()).format)
        assertFalse(ImagePolicy.scanMetadata("GIF89a....".encodeToByteArray()).isClean)
        assertFalse(ImagePolicy.scanMetadata(ByteArray(0)).isClean)
        assertFalse(ImagePolicy.scanMetadata(byteArrayOf(0xFF.toByte(), 0xD8.toByte())).isClean, "just the start marker")
        val webp = riff("VP8 " to ByteArray(25))
        assertTrue(ImagePolicy.scanMetadata(webp).isClean)
        assertFalse(ImagePolicy.scanMetadata(webp.copyOf(20)).isClean, "a WebP cut inside a chunk")
        assertFalse(ImagePolicy.scanMetadata(webp.copyOf(14)).isClean, "a WebP cut inside a chunk header")
        assertFalse(ImagePolicy.scanMetadata(segment(0xE1, ByteArray(5))).isClean, "not an image")
    }

    @Test
    fun aSegmentWithALyingLengthIsMalformedNotACrash() {
        val lying = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3)
        val scan = ImagePolicy.scanMetadata(lying)
        assertFalse(scan.wellFormed)
        assertFalse(scan.isClean)
        val tiny = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 1, 9, 9)
        assertFalse(ImagePolicy.scanMetadata(tiny).wellFormed, "a length below 2 cannot be right")
    }

    @Test
    fun randomBytesNeverCrashTheScan() {
        var seed = 12345L
        fun next(): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (seed ushr 33).toInt()
        }
        repeat(3_000) { round ->
            val bytes = ByteArray(next() % 200) { next().toByte() }
            val prefixed = when (round % 3) {
                0 -> byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + bytes
                1 -> "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + bytes
                else -> bytes
            }
            val scan = ImagePolicy.scanMetadata(prefixed)
            if (scan.isClean) assertTrue(scan.format != ImageFormat.UNKNOWN && scan.wellFormed && scan.found.isEmpty())
        }
    }
}
