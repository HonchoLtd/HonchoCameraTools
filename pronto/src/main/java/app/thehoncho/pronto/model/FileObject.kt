package app.thehoncho.pronto.model

import java.nio.ByteBuffer

class FileObject(b: ByteBuffer, length: Int) {
    var objectHandler = 0
    var storageID = 0
    var objectFormat: Short = 0
    var flag = 0
    var objectSize = 0
    var filename: String? = null
    var time = 0

    init {
        decode(b, length)
    }

    val isFile: Boolean
        get() = flag == 20

    private fun decode(b: ByteBuffer, length: Int) {
        //0010  38 00 00 00 00 00 00 90-01 00 02 00 01 30 00 00  8............0..
        //0020  01 00 00 00 11 00 00 00-00 00 00 00 00 00 00 00  ................
        //0030  00 00 00 90 44 43 49 4d-00 00 00 00 00 00 00 00  ....DCIM........
        //0040  00 00 00 00 7a b0 f8 64-38 00 00 00 00 00 08 a0  ....z..d8.......
        //0050  01 00 02 00 01 30 00 00-01 00 00 00 11 00 00 00  .....0..........
        //0060  00 00 00 00 00 00 00 00-00 00 08 a0 4d 49 53 43  ............MISC
        //0070  00 00 00 00 00 00 00 00-00 00 00 00 7a b0 f8 64  ............z..d
        if (length < 12) {
            return
        }

        // flag = 11 folder
        // flag = 20 file
        val fileNameBufferLen = 13 // this max length of filename
        val byteLength = b.int // its 38 00 00 00 this the length size include this
        objectHandler = b.int
        storageID = b.int // 01 00 02 00
        objectFormat = b.short // 01 30
        b.int // 00 00 01 00 skip
        b.short // 00 00 skip
        flag = b.int // 11 00 00 00
        objectSize = b.int // 00 00 00 00
        b.int // skip
        b.int // skip
        val fileNameBuffer = StringBuilder() // 44 43 49 4d-00 00 00 00 00 00 00 00 00 00
        for (j in 0 until fileNameBufferLen) {
            fileNameBuffer.append(Char(b.get().toUShort()))
        }
        fileNameBuffer.append(0)
        filename = fileNameBuffer.toString()
        b.short // 00 00 skip
        time = b.int // 7a b0 f8 64
    }
}