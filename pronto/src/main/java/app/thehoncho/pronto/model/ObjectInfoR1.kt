package app.thehoncho.pronto.model

import android.util.Log
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ObjectInfoR1 {
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

    var objectSize64: Long = 0L
    var timestampUnix: Int = 0
    var blockSize: Int = 0

    constructor()
    constructor(b: ByteBuffer, length: Int) : this() { decode(b, length) }

    private fun decode(b: ByteBuffer, length: Int) {
        val originalOrder = b.order()
        b.order(ByteOrder.LITTLE_ENDIAN)
        val structStart = b.position()

        try {
            blockSize = b.int
            handlerID = b.int

            storageId = b.int
            objectFormat = b.int
            protectionStatus = b.int
            val reserved = b.int

            objectSize64 = b.long
            objectCompressedSize = objectSize64.toInt()

            parentObject = b.int
            associationType = b.int
            timestampUnix = b.int

            val nameLength = b.get().toInt() and 0xFF

            if (nameLength > 0 && nameLength <= 255) {
                if (b.remaining() >= nameLength * 2) {
                    val nameBytes = ByteArray(nameLength * 2)
                    b.get(nameBytes)

                    val cleanBytes = ByteArray(nameLength)
                    for (i in 0 until nameLength) {
                        cleanBytes[i] = nameBytes[i * 2]
                    }

                    filename = try {
                        String(cleanBytes, Charsets.US_ASCII).trimEnd('\u0000', '\n', '\r')
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    filename = ""
                }
            } else {
                filename = ""
            }
        } finally {
            b.order(originalOrder)
        }
    }

    companion object {
        private const val TAG = "ObjectInfoR1"

        fun parseList(data: ByteBuffer, totalLength: Int = 0): List<ObjectInfoR1> {
            val results = mutableListOf<ObjectInfoR1>()
            val originalOrder = data.order()
            data.order(ByteOrder.LITTLE_ENDIAN)
            try {
                if (data.position() < 12 && data.remaining() > 12) {
                    data.position(12)
                }

                if (data.remaining() < 4) return results

                val itemCount = data.int
                if (itemCount <= 0) return results

                repeat(itemCount) { index ->
                    if (data.remaining() < 4) {
                        return@repeat
                    }

                    val obj = ObjectInfoR1(data, data.remaining())

                    if (obj.handlerID != 0 || !obj.filename.isNullOrEmpty()) {
                        results.add(obj)
                    }
                }
            } finally {
                data.order(originalOrder)
            }
            return results
        }
    }

    override fun toString(): String {
        return buildString {
            append("ObjectInfoR1\n")
            append("HandlerID: 0x%08x\n".format(handlerID))
            append("StorageId: 0x%08x\n".format(storageId))
            append("ObjectFormat: ${app.thehoncho.pronto.utils.PtpConstants.objectFormatToString(objectFormat)}\n")
            append("ProtectionStatus: $protectionStatus\n")
            append("ObjectCompressedSize: $objectCompressedSize (64-bit: $objectSize64)\n")
            append("ParentObject: 0x%08x\n".format(parentObject))
            append("Filename: $filename\n")
            append("CaptureDate: $captureDate\n")
            append("TimestampUnix: $timestampUnix\n")
        }
    }

    fun getID(): String {
        return listOf(handlerID.toString(), storageId.toString(), objectFormat.toString(),
            parentObject.toString(), captureDate ?: "", modificationDate ?: "", filename ?: ""
        ).joinToString(".").replace("\u0000", "")
    }

    fun getAllDataKey(): String {
        return listOf(handlerID.toString(), storageId.toString(), objectFormat.toString(),
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

    fun requires64BitDownload(): Boolean = objectSize64 > Int.MAX_VALUE.toLong()

    fun toObjectInfo(): ObjectInfo {
        return ObjectInfo().apply {
            handlerID = this@ObjectInfoR1.handlerID
            storageId = this@ObjectInfoR1.storageId
            objectFormat = this@ObjectInfoR1.objectFormat
            protectionStatus = this@ObjectInfoR1.protectionStatus
            objectCompressedSize = this@ObjectInfoR1.objectCompressedSize
            thumbFormat = this@ObjectInfoR1.thumbFormat
            thumbCompressedSize = this@ObjectInfoR1.thumbCompressedSize
            thumbPixWidth = this@ObjectInfoR1.thumbPixWidth
            thumbPixHeight = this@ObjectInfoR1.thumbPixHeight
            imagePixWidth = this@ObjectInfoR1.imagePixWidth
            imagePixHeight = this@ObjectInfoR1.imagePixHeight
            imageBitDepth = this@ObjectInfoR1.imageBitDepth
            parentObject = this@ObjectInfoR1.parentObject
            associationType = this@ObjectInfoR1.associationType
            associationDesc = this@ObjectInfoR1.associationDesc
            sequenceNumber = this@ObjectInfoR1.sequenceNumber
            filename = this@ObjectInfoR1.filename
            captureDate = this@ObjectInfoR1.captureDate
            modificationDate = this@ObjectInfoR1.modificationDate
            keywords = this@ObjectInfoR1.keywords
        }
    }
}