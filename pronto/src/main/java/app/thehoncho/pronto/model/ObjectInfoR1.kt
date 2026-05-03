package app.thehoncho.pronto.model

// File: ObjectInfoR1.kt
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * R1-specific object info that mirrors ObjectInfo structure but parses R1 wire format.
 * Matches Swift ObjectInfoR1 struct pattern: properties can be set directly without buffer.
 */
class ObjectInfoR1 {
    // ========== Standard ObjectInfo-compatible properties (with defaults) ==========
    var handlerID: Int = 0
    var storageId: Int = 0
    var objectFormat: Int = 0
    var protectionStatus: Int = 0
    var objectCompressedSize: Int = 0
    var thumbFormat: Int = 0
    var thumbCompressedSize: Int = 0
    var thumbPixWidth: Int = 0
    var thumbPixHeight: Int = 0
    var imagePixWidth: Int = 0
    var imagePixHeight: Int = 0
    var imageBitDepth: Int = 0
    var parentObject: Int = 0
    var associationType: Int = 0
    var associationDesc: Int = 0
    var sequenceNumber: Int = 0
    var filename: String? = null
    var captureDate: String? = null
    var modificationDate: String? = null
    var keywords: Int = 0

    // ========== R1-specific extra fields ==========
    var objectSize64: Long = 0L          // Full 64-bit file size
    var timestampUnix: Int = 0           // Original Unix timestamp
    var blockSize: Int = 0               // Struct size for padding skip

    // ✅ Empty constructor - matches Swift struct pattern (no buffer required)
    constructor()

    // Optional: Constructor for parsing from ByteBuffer (when needed)
    constructor(b: ByteBuffer, length: Int) : this() {
        decode(b, length)
    }

    /**
     * Decodes a SINGLE ObjectInfoR1 from ByteBuffer at current position.
     * Format: [BlockSize 4][Handle 4][Storage 4][Format 4][Protect 4][Reserved 4]
     *         [Size 8][Parent 4][Assoc 4][Timestamp 4][NameLen 1][UTF-16LE Name][Padding]
     */
    private fun decode(b: ByteBuffer, length: Int) {
        val originalOrder = b.order()
        b.order(ByteOrder.LITTLE_ENDIAN)
        val structStart = b.position()

        try {
            // Parse R1 struct fields
            blockSize = b.int
            handlerID = b.int
            storageId = b.int
            objectFormat = b.int                 // R1 uses full int
            protectionStatus = b.int
            b.int                                // Skip reserved field

            objectSize64 = b.long
            objectCompressedSize = objectSize64.toInt()  // Truncate for compatibility

            parentObject = b.int
            associationType = b.int              // R1: associationHandle
            timestampUnix = b.int                // Unix epoch timestamp

            // Filename: 1-byte length + UTF-16LE string
            val nameLength = b.get().toInt() and 0xFF
            if (nameLength > 0) {
                val nameBytes = ByteArray(nameLength * 2)
                b.get(nameBytes)
                filename = try {
                    String(nameBytes, Charset.forName("UTF-16LE"))
                        .trimEnd { it == '\u0000' || it == '\n' || it == '\r' }
                } catch (e: Exception) {
                    ""
                }
            }

            // Convert Unix timestamp → PTP datetime string "YYYYMMDDTHHMMSS"
            try {
                val date = Date(timestampUnix.toLong() * 1000)
                val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val ptpDate = sdf.format(date)
                captureDate = ptpDate
                modificationDate = ptpDate
            } catch (e: Exception) {
                captureDate = ""
                modificationDate = ""
            }

            // Skip to end of struct block (handle padding)
            val structEnd = structStart + blockSize
            if (b.position() < structEnd) {
                b.position(structEnd)
            }
        } finally {
            b.order(originalOrder)
        }
    }

    companion object {
        private const val TAG = "ObjectInfoR1"

        /**
         * Parse a list of ObjectInfoR1 from PTP response data.
         * Matches Swift: ObjectInfoR1.parseList(from: data)
         *
         * Format: [Header 12 bytes][Item Count 4 bytes][Struct Array...]
         */
        fun parseList(data: ByteBuffer, totalLength: Int = 0): List<ObjectInfoR1> {
            val results = mutableListOf<ObjectInfoR1>()
            val originalOrder = data.order()

            data.order(ByteOrder.LITTLE_ENDIAN)

            try {
                // Skip 12-byte PTP data header
                if (data.position() < 12) data.position(12)

                if (data.remaining() < 4) return results
                val itemCount = data.int
                if (itemCount <= 0) return results

                repeat(itemCount) {
                    if (data.remaining() < 4) return@repeat
                    val obj = ObjectInfoR1(data, data.remaining())
                    if (obj.handlerID != 0 || !obj.filename.isNullOrEmpty()) {
                        results.add(obj)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to parse ObjectInfoR1 list", e)
            } finally {
                data.order(originalOrder)
            }

            return results
        }
    }

    // ========== Compatibility Methods (match ObjectInfo) ==========

    override fun toString(): String {
        return buildString {
            append("ObjectInfoR1\n")
            append("HandlerID: 0x%08x\n".format(handlerID))
            append("StorageId: 0x%08x\n".format(storageId))
            append("ObjectFormat: ${PtpConstants.objectFormatToString(objectFormat)}\n")
            append("ProtectionStatus: $protectionStatus\n")
            append("ObjectCompressedSize: $objectCompressedSize (64-bit: $objectSize64)\n")
            append("ParentObject: 0x%08x\n".format(parentObject))
            append("Filename: $filename\n")
            append("CaptureDate: $captureDate\n")
            append("TimestampUnix: $timestampUnix\n")
        }
    }

    fun getID(): String {
        return listOf(
            handlerID.toString(), storageId.toString(), objectFormat.toString(),
            parentObject.toString(), captureDate ?: "", modificationDate ?: "", filename ?: ""
        ).joinToString(".").replace("\u0000", "")
    }

    fun getAllDataKey(): String {
        return listOf(
            handlerID.toString(), storageId.toString(), objectFormat.toString(),
            protectionStatus.toString(), objectCompressedSize.toString(),
            thumbFormat.toString(), thumbCompressedSize.toString(),
            thumbPixWidth.toString(), thumbPixHeight.toString(),
            imagePixWidth.toString(), imagePixHeight.toString(),
            imageBitDepth.toString(), parentObject.toString(),
            associationType.toString(), associationDesc.toString(),
            sequenceNumber.toString(), (filename ?: "").trim().uppercase(),
            (captureDate ?: "").trim(), (modificationDate ?: "").trim(),
            keywords.toString()
        ).joinToString(".").replace("\u0000", "")
    }

    // ========== R1-Specific Helpers ==========

    fun requires64BitDownload(): Boolean = objectSize64 > Int.MAX_VALUE.toLong()

    /**
     * Convert to standard ObjectInfo for compatibility.
     *
     * ⚠️ Since ObjectInfo requires (ByteBuffer, Int) constructor, we have two options:
     */
    fun toObjectInfo(): ObjectInfo {
        // OPTION 1 (Recommended): Add no-arg constructor to ObjectInfo (see below)
        // return ObjectInfo().apply { copyFieldsFrom(this@ObjectInfoR1) }

        // OPTION 2 (Workaround): Use a minimal dummy buffer if you can't modify ObjectInfo
        return createObjectInfoWithDummyBuffer().apply { copyFieldsFrom(this@ObjectInfoR1) }
    }

    // Helper to copy all compatible fields
    private fun ObjectInfo.copyFieldsFrom(r1: ObjectInfoR1) {
        handlerID = r1.handlerID
        storageId = r1.storageId
        objectFormat = r1.objectFormat
        protectionStatus = r1.protectionStatus
        objectCompressedSize = r1.objectCompressedSize
        thumbFormat = r1.thumbFormat
        thumbCompressedSize = r1.thumbCompressedSize
        thumbPixWidth = r1.thumbPixWidth
        thumbPixHeight = r1.thumbPixHeight
        imagePixWidth = r1.imagePixWidth
        imagePixHeight = r1.imagePixHeight
        imageBitDepth = r1.imageBitDepth
        parentObject = r1.parentObject
        associationType = r1.associationType
        associationDesc = r1.associationDesc
        sequenceNumber = r1.sequenceNumber
        filename = r1.filename
        captureDate = r1.captureDate
        modificationDate = r1.modificationDate
        keywords = r1.keywords
    }

    // Workaround: Create ObjectInfo using minimal dummy buffer
    private fun createObjectInfoWithDummyBuffer(): ObjectInfo {
        // Allocate buffer with minimal valid data for decode() to not crash
        val dummy = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        // Write minimal valid structure for decode()
        dummy.putInt(0)       // storageId
        dummy.putShort(0)     // objectFormat
        dummy.putShort(0)     // protectionStatus
        dummy.putInt(0)       // objectCompressedSize
        dummy.putShort(0)     // thumbFormat
        dummy.putInt(0)       // thumbCompressedSize
        dummy.putInt(0)       // thumbPixWidth
        dummy.putInt(0)       // thumbPixHeight
        dummy.putInt(0)       // imagePixWidth
        dummy.putInt(0)       // imagePixHeight
        dummy.putInt(0)       // imageBitDepth
        dummy.putInt(0)       // parentObject
        dummy.putShort(0)     // associationType
        dummy.putInt(0)       // associationDesc
        dummy.putInt(0)       // sequenceNumber
        // Minimal strings for PacketUtil.readString()
        dummy.put(byteArrayOf(0))  // empty filename
        dummy.put(byteArrayOf(0))  // empty captureDate
        dummy.put(byteArrayOf(0))  // empty modificationDate
        dummy.put(0)          // keywords
        dummy.position(0)     // Reset for decode()

        return ObjectInfo(dummy, 256)
    }
}