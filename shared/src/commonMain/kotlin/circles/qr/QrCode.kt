package circles.qr

private const val MIN_VERSION = 1
private const val MAX_VERSION = 10
private const val DATA_MODE_BYTE = 0b0100
private const val PAD_BYTE_1 = 0xEC
private const val PAD_BYTE_2 = 0x11
private const val GF_POLYNOMIAL = 0x11D
private const val FORMAT_POLYNOMIAL = 0x537
private const val FORMAT_MASK = 0x5412
private const val VERSION_POLYNOMIAL = 0x1F25
private const val ECC_LEVEL_M_BITS = 0
private const val MASK_COUNT = 8
private const val PENALTY_N1 = 3
private const val PENALTY_N2 = 3
private const val PENALTY_N3 = 40
private const val PENALTY_N4 = 10

/** Error correction codewords per block and number of blocks at level M, for versions 1 to 10 (ISO 18004 table 9). */
private val ECC_PER_BLOCK_M = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
private val BLOCKS_M = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

/**
 * A QR code (ADR 0062): the text as bytes, error correction level M, the smallest version from 1 to 10 that holds it
 * (up to 213 bytes). Written for the circle's own links, drawn on the device, no library. [modules] is a square grid, true
 * for a dark module. Follows ISO 18004; the tests compare it module for module with an independent encoder.
 */
class QrCode private constructor(val version: Int, val mask: Int, private val grid: Array<BooleanArray>) {
    val size: Int get() = grid.size

    /** True for a dark module at column [x], row [y]. */
    fun module(x: Int, y: Int): Boolean = grid[y][x]

    companion object {
        /** The most bytes a code can hold. */
        const val MAX_BYTES = 213

        /** The code for [text] (UTF-8), or null if it is longer than [MAX_BYTES] bytes. [forceMask] (0 to 7) is for tests. */
        fun of(text: String, forceMask: Int? = null): QrCode? {
            val data = text.encodeToByteArray()
            val version = (MIN_VERSION..MAX_VERSION).firstOrNull { dataCapacityBytes(it) >= data.size } ?: return null
            val codewords = interleave(version, dataCodewords(version, data))
            val builder = Builder(version)
            builder.drawFunctionPatterns()
            builder.drawCodewords(codewords)
            val chosen = forceMask ?: (0 until MASK_COUNT).minBy { mask ->
                builder.applyMask(mask)
                builder.drawFormat(mask)
                val penalty = builder.penalty()
                builder.applyMask(mask)
                penalty
            }
            builder.applyMask(chosen)
            builder.drawFormat(chosen)
            return QrCode(version, chosen, builder.modules)
        }

        private fun rawDataModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val alignments = version / 7 + 2
                result -= (25 * alignments - 10) * alignments - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewordCount(version: Int): Int = rawDataModules(version) / 8 - ECC_PER_BLOCK_M[version - 1] * BLOCKS_M[version - 1]

        /** Bytes of text that fit: the data codewords less the mode and length header (12 bits, or 20 from version 10). */
        private fun dataCapacityBytes(version: Int): Int = dataCodewordCount(version) - if (version >= 10) 3 else 2

        private fun dataCodewords(version: Int, data: ByteArray): ByteArray {
            val bits = ArrayList<Boolean>()
            fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += ((value ushr i) and 1) == 1 }
            put(DATA_MODE_BYTE, 4)
            put(data.size, if (version >= 10) 16 else 8)
            for (b in data) put(b.toInt() and 0xFF, 8)
            val capacityBits = dataCodewordCount(version) * 8
            repeat(minOf(4, capacityBits - bits.size)) { bits += false }
            while (bits.size % 8 != 0) bits += false
            var pad = PAD_BYTE_1
            while (bits.size < capacityBits) {
                put(pad, 8)
                pad = if (pad == PAD_BYTE_1) PAD_BYTE_2 else PAD_BYTE_1
            }
            return ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 }.toByte() }
        }

        private fun interleave(version: Int, data: ByteArray): ByteArray {
            val blocks = BLOCKS_M[version - 1]
            val eccLength = ECC_PER_BLOCK_M[version - 1]
            val rawCodewords = rawDataModules(version) / 8
            val shortBlocks = blocks - rawCodewords % blocks
            val shortLength = rawCodewords / blocks
            val generator = reedSolomonGenerator(eccLength)
            val dataBlocks = ArrayList<ByteArray>()
            val eccBlocks = ArrayList<ByteArray>()
            var at = 0
            for (i in 0 until blocks) {
                val length = shortLength - eccLength + if (i < shortBlocks) 0 else 1
                val block = data.copyOfRange(at, at + length)
                at += length
                dataBlocks += block
                eccBlocks += reedSolomonRemainder(block, generator)
            }
            val out = ArrayList<Byte>()
            for (i in 0..shortLength - eccLength) for (j in 0 until blocks) if (i != shortLength - eccLength || j >= shortBlocks) out += dataBlocks[j][i]
            for (i in 0 until eccLength) for (j in 0 until blocks) out += eccBlocks[j][i]
            return out.toByteArray()
        }

        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * GF_POLYNOMIAL)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun reedSolomonGenerator(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 2)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, generator: IntArray): ByteArray {
            val result = IntArray(generator.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                for (i in 0 until result.size - 1) result[i] = result[i + 1]
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(generator[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }
    }

    private class Builder(val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        private val isFunction = Array(size) { BooleanArray(size) }

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
            val positions = alignmentPositions()
            for (a in positions.indices) for (b in positions.indices) {
                if ((a == 0 && b == 0) || (a == 0 && b == positions.size - 1) || (a == positions.size - 1 && b == 0)) continue
                drawAlignment(positions[a], positions[b])
            }
            drawFormat(0) // reserves the area; the real bits are drawn once the mask is chosen
            drawVersion()
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count)
            result[0] = 6
            var position = size - 7
            for (i in count - 1 downTo 1) { result[i] = position; position -= step }
            return result
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, distance != 2 && distance != 4)
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }

        fun drawFormat(mask: Int) {
            val data = (ECC_LEVEL_M_BITS shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * FORMAT_POLYNOMIAL) }
            val bits = ((data shl 10) or rem) xor FORMAT_MASK
            fun bit(i: Int) = ((bits ushr i) and 1) != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * VERSION_POLYNOMIAL) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val dark = ((bits ushr i) and 1) != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark); set(b, a, dark)
            }
        }

        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (!isFunction[y][x] && invert) modules[y][x] = !modules[y][x]
            }
        }

        /** The ISO 18004 penalty score of the current grid: runs, blocks, finder-like patterns and dark balance. */
        fun penalty(): Int {
            var result = 0
            val finderLeft = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)
            val finderRight = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
            for (pass in 0..1) {
                for (a in 0 until size) {
                    fun at(b: Int) = if (pass == 0) modules[a][b] else modules[b][a]
                    var runColour = false
                    var runLength = 0
                    for (b in 0 until size) {
                        val colour = at(b)
                        if (b > 0 && colour == runColour) {
                            runLength++
                            if (runLength == 5) result += PENALTY_N1 else if (runLength > 5) result++
                        } else {
                            runColour = colour
                            runLength = 1
                        }
                    }
                    for (start in 0..size - 11) {
                        if ((0 until 11).all { at(start + it) == finderLeft[it] }) result += PENALTY_N3
                        if ((0 until 11).all { at(start + it) == finderRight[it] }) result += PENALTY_N3
                    }
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += PENALTY_N2
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            result += ((kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1) * PENALTY_N4
            return result
        }
    }
}
