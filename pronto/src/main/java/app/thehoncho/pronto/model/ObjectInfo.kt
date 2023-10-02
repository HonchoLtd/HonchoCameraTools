package app.thehoncho.pronto.model

import app.thehoncho.pronto.utils.PacketUtil
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class ObjectInfo(b: ByteBuffer, length: Int) {
    var handlerID = 0
    var storageId = 0
    var objectFormat = 0
    var protectionStatus = 0
    var objectCompressedSize = 0
    var thumbFormat = 0
    var thumbCompressedSize = 0
    var thumbPixWidth = 0
    var thumbPixHeight = 0
    var imagePixWidth = 0
    var imagePixHeight = 0
    var imageBitDepth = 0
    var parentObject = 0
    var associationType = 0
    var associationDesc = 0
    var sequenceNumber = 0
    var filename: String? = null
    var captureDate: String? = null
    var modificationDate: String? = null
    var keywords = 0

    init {
        decode(b, length)
    }

    private fun decode(b: ByteBuffer, length: Int) {
        storageId = b.int
        objectFormat = b.short.toInt()
        protectionStatus = b.short.toInt()
        objectCompressedSize = b.int
        thumbFormat = b.short.toInt()
        thumbCompressedSize = b.int
        thumbPixWidth = b.int
        thumbPixHeight = b.int
        imagePixWidth = b.int
        imagePixHeight = b.int
        imageBitDepth = b.int
        parentObject = b.int
        associationType = b.short.toInt()
        associationDesc = b.int
        sequenceNumber = b.int
        filename = PacketUtil.readString(b)
        captureDate = PacketUtil.readString(b)
        modificationDate = PacketUtil.readString(b)
        keywords = b.get().toInt() // string, not used on camera?
    }

    override fun toString(): String {
        val b = StringBuilder()
        b.append("ObjectInfo\n")
        b.append("StorageId: ").append(String.format("0x%08x\n", storageId))
        b.append("ObjectFormat: ").append(PtpConstants.objectFormatToString(objectFormat))
            .append('\n')
        b.append("ProtectionStatus: ").append(protectionStatus).append('\n')
        b.append("ObjectCompressedSize: ").append(objectCompressedSize).append('\n')
        b.append("ThumbFormat: ").append(PtpConstants.objectFormatToString(thumbFormat))
            .append('\n')
        b.append("ThumbCompressedSize: ").append(thumbCompressedSize).append('\n')
        b.append("ThumbPixWith: ").append(thumbPixWidth).append('\n')
        b.append("ThumbPixHeight: ").append(thumbPixHeight).append('\n')
        b.append("ImagePixWidth: ").append(imagePixWidth).append('\n')
        b.append("ImagePixHeight: ").append(imagePixHeight).append('\n')
        b.append("ImageBitDepth: ").append(imageBitDepth).append('\n')
        b.append("ParentObject: ").append(String.format("0x%08x", parentObject)).append('\n')
        b.append("AssociationType: ").append(associationType).append('\n')
        b.append("AssociationDesc: ").append(associationDesc).append('\n')
        b.append("Filename: ").append(filename).append('\n')
        b.append("CaptureDate: ").append(captureDate).append('\n')
        b.append("ModificationDate: ").append(modificationDate).append('\n')
        b.append("Keywords: ").append(keywords).append('\n')
        return b.toString()
    }
}