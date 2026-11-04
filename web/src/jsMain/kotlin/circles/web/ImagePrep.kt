package circles.web

import circles.media.ImagePolicy
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise

/** A photo ready to seal: the picture and its thumbnail, already scaled, re-encoded and free of metadata (ADR 0028). */
class PreparedImage(val photo: ByteArray, val thumb: ByteArray)

sealed interface PrepareResult {
    class Ready(val image: PreparedImage) : PrepareResult

    /** The browser could not read it as a picture. */
    data object NotAnImage : PrepareResult

    /** Empty, over 25 MB or over 40 megapixels. */
    data object TooLarge : PrepareResult

    /** Even the smallest setting stays over the size limit. */
    data object CannotShrink : PrepareResult

    /** The result still carried metadata (it never should; the picture is not used if so). */
    class NotClean(val why: String) : PrepareResult
}

private const val PERCENT = 100.0
private const val WEBP_MARKER_AT = 8

/**
 * Decodes [file] (applying its orientation), scales it and re-encodes it from pixels, so location and camera details are
 * gone by construction, then checks the result for metadata anyway. Follows the ladders in [ImagePolicy] (ADR 0028).
 */
suspend fun prepareImage(file: dynamic): PrepareResult {
    val options = js("({})")
    options.imageOrientation = "from-image"
    val bitmap = try {
        js("createImageBitmap")(file, options).unsafeCast<Promise<dynamic>>().await()
    } catch (_: Throwable) {
        return PrepareResult.NotAnImage
    }
    val width = bitmap.width as Int
    val height = bitmap.height as Int
    if (ImagePolicy.checkInput((file.size as Number).toLong(), width, height) != ImagePolicy.InputCheck.Ok) return PrepareResult.TooLarge
    var index = 0
    var attempt = ImagePolicy.photoAttempts[index]
    val photo: ByteArray
    while (true) {
        val bytes = encode(bitmap, width, height, attempt)?.let { ImagePolicy.stripIcc(it) } ?: return PrepareResult.NotAnImage
        when (val step = ImagePolicy.judgePhoto(index, bytes.size)) {
            ImagePolicy.Step.Accept -> { photo = bytes; break }
            is ImagePolicy.Step.Retry -> { attempt = step.next; index++ }
            ImagePolicy.Step.GiveUp -> return PrepareResult.CannotShrink
        }
    }
    index = 0
    attempt = ImagePolicy.thumbnailAttempts[index]
    val thumb: ByteArray
    while (true) {
        val bytes = encode(bitmap, width, height, attempt)?.let { ImagePolicy.stripIcc(it) } ?: return PrepareResult.NotAnImage
        when (val step = ImagePolicy.judgeThumbnail(index, bytes.size)) {
            ImagePolicy.Step.Accept -> { thumb = bytes; break }
            is ImagePolicy.Step.Retry -> { attempt = step.next; index++ }
            ImagePolicy.Step.GiveUp -> return PrepareResult.CannotShrink
        }
    }
    for ((name, bytes) in listOf("photo" to photo, "thumbnail" to thumb)) {
        val scan = ImagePolicy.scanMetadata(bytes)
        if (!scan.isClean) return PrepareResult.NotClean("$name ${scan.format} found ${scan.found} wellFormed ${scan.wellFormed}")
    }
    return PrepareResult.Ready(PreparedImage(photo, thumb))
}

/** One encode attempt: WebP where the browser can make it, JPEG otherwise. */
private suspend fun encode(bitmap: dynamic, width: Int, height: Int, attempt: ImagePolicy.EncodeAttempt): ByteArray? {
    val size = ImagePolicy.scaledSize(width, height, attempt.maxEdge)
    val canvas = kotlinx.browser.document.createElement("canvas").asDynamic()
    canvas.width = size.width
    canvas.height = size.height
    canvas.getContext("2d").drawImage(bitmap, 0, 0, size.width, size.height)
    val quality = attempt.quality / PERCENT
    val webp = toBytes(canvas, "image/webp", quality)
    if (webp != null && isWebp(webp)) return webp
    return toBytes(canvas, "image/jpeg", quality)
}

private fun isWebp(bytes: ByteArray): Boolean =
    bytes.size > WEBP_MARKER_AT + 4 && CharArray(4) { bytes[WEBP_MARKER_AT + it].toInt().toChar() }.concatToString() == "WEBP"

private suspend fun toBytes(canvas: dynamic, type: String, quality: Double): ByteArray? {
    val blob = Promise<dynamic> { resolve, _ -> canvas.toBlob({ b: dynamic -> resolve(b) }, type, quality) }.await() ?: return null
    val buffer = (blob.arrayBuffer() as Promise<ArrayBuffer>).await()
    return Int8Array(buffer).unsafeCast<ByteArray>()
}

/** The MIME type to show [bytes] with, from their first bytes. */
fun imageType(bytes: ByteArray): String = if (isWebp(bytes)) "image/webp" else "image/jpeg"
