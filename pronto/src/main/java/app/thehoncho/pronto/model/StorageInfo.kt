package app.thehoncho.pronto.model

import app.thehoncho.pronto.utils.PacketUtil
import java.nio.ByteBuffer

class StorageInfo(b: ByteBuffer, length: Int) {
    var storageType: Short  = 0
    var filesystemType: Short = 0
    var accessCapability: Short = 0
    var maxCapacity: Long = 0
    var freeSpaceInBytes: Long = 0
    var freeSpaceInImages = 0
    var storageDescription: String? = null
    var volumeLabel: String? = null

    init { decode(b, length) }

    private fun decode(b: ByteBuffer, length: Int) {
        storageType = b.short
        filesystemType = b.short
        accessCapability = b.short
        maxCapacity = b.long
        freeSpaceInBytes = b.long
        freeSpaceInImages = b.int
        storageDescription = PacketUtil.readString(b)
        if (storageDescription.isNullOrEmpty()) {
            // Canon: field not used
            storageDescription = null
        }
        volumeLabel = PacketUtil.readString(b)
    }
}