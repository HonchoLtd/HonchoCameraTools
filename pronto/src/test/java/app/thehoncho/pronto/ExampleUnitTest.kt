package app.thehoncho.pronto

import org.junit.Test

import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        val byteBuffer = ByteBuffer.allocate(5)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.put(1)
        byteBuffer.put(2)
        byteBuffer.put(3)
        byteBuffer.put(4)
        byteBuffer.put(5)
        val bytes = ByteArray(5)
        byteBuffer.position(0)
        byteBuffer[bytes, 0, 5]
        val withByteArray = byteBuffer.array()
        val withByteBuffer = ByteBuffer.allocate(5)
        withByteBuffer.put(byteBuffer)
        val byteBufferFromByteArray = ByteBuffer.wrap(bytes)


        println("bytesBuffer: ${byteBuffer.array().contentToString()}")
        println("bytes: ${bytes.contentToString()}")
        println("withByteArray: ${withByteArray.contentToString()}")
        println("withByteBuffer: ${withByteBuffer.array().contentToString()}")
        println("byteBufferFromByteArray: ${byteBufferFromByteArray.array().contentToString()}")

        byteBuffer.position(2)
        byteBuffer.put(0)

        println("bytesBuffer: ${byteBuffer.array().contentToString()}")
        println("bytes: ${bytes.contentToString()}")
        println("withByteArray: ${withByteArray.contentToString()}")
        println("withByteBuffer: ${withByteBuffer.array().contentToString()}")
        println("byteBufferFromByteArray: ${byteBufferFromByteArray.array().contentToString()}")
        assertEquals(4, 2 + 2)
    }
}