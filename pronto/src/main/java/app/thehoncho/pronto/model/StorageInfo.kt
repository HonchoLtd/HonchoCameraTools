package app.thehoncho.pronto.model

import app.thehoncho.pronto.utils.PacketUtil
import java.nio.ByteBuffer

class StorageInfo(b: ByteBuffer, length: Int) {
    var storageType = 0
    var filesystemType = 0
    var accessCapability = 0
    var maxCapacity: Long = 0
    var freeSpaceInBytes: Long = 0
    var freeSpaceInImages = 0
    var storageDescription: String? = null
    var volumeLabel: String? = null

    init { decode(b, length) }

    private fun decode(b: ByteBuffer, length: Int) {
        storageType = b.short.toInt() and 0xffff
        filesystemType = b.short.toInt() and 0xffff
        accessCapability = b.short.toInt() and 0xff
        maxCapacity = b.long
        freeSpaceInBytes = b.long
        freeSpaceInImages = b.int
        storageDescription = PacketUtil.readString(b)
        volumeLabel = PacketUtil.readString(b)
    }
}