package app.thehoncho.pronto.command

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

abstract class Command(protected val session: Session): PTPAction {
    // protected var hasDataToSend = false
    protected var responseCode: Short = 0
    public var hasResponseReceived = false

    private val hasEventReceived = false
    private val byteBufferEvent: ByteBuffer? = null

    abstract fun encodeCommand(byteBuffer: ByteBuffer)

    /**
     * Derived classes should implement this method if they want to send data
     * from host to camera. The field `hasDataToSend` has to be set to
     * true for the sending to be done. The data to send must not be greater
     * than the USB max packet size, any size below 256 should be save.
     */
    // protected fun encodeData(b: ByteBuffer) {}

    /**
     * Derived classes should implement this method if they want to decode data
     * received in an data packet that has been sent by the camera. The
     * `ByteBuffer` already points to the first byte behind the
     * transaction id, i.e. the payload.
     */
    protected open fun decodeData(b: ByteBuffer, length: Int) {}

    /**
     * Override if any special response data has to be decoded. The
     * `ByteBuffer` already points to the first byte behind the
     * transaction id, i.e. the payload.
     */
    protected open fun decodeResponse(b: ByteBuffer, length: Int) {}

    open fun receivedRead(byteBuffer: ByteBuffer) {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.position(0)
        val length = byteBuffer.int
        val type = byteBuffer.short
        val code = byteBuffer.short
        val tx = byteBuffer.int

        val logMessage = String.format(Locale.ENGLISH,
            "received: %s packet for %s length %d code %s tx %d",
            PtpConstants.typeToString(type),
            javaClass.simpleName,
            length,
            PtpConstants.codeToString(type, code),
            tx
        )
        session.log.d(TAG, logMessage)

        when (type) {
            PtpConstants.Type.Data -> {
                decodeData(byteBuffer, length)
            }
            PtpConstants.Type.Response -> {
                // This response mean the data already end so there is no data anymore
                // we stop the worker loop with hasResponseReceived with true
                hasResponseReceived = true
                responseCode = code
                decodeResponse(byteBuffer, length)
            }
            else -> {
                session.log.e(TAG, "receivedRead: the type its not data and response but ${PtpConstants.typeToString(type)}")
            }
        }
    }

    open fun onError(message: String) {
        hasResponseReceived = true
        responseCode = PtpConstants.Response.GeneralError
        val byteBuffer = ByteBuffer.wrap(message.toByteArray())
        // force the decode response call to make the command done or it will be stuck and not going next task
        decodeResponse(byteBuffer, 0)
    }

    // This method call in encodeCommand to build command packet
    protected open fun encodeCommand(byteBuffer: ByteBuffer, code: Short) {
        byteBuffer.putInt(12)
        byteBuffer.putShort(PtpConstants.Type.Command)
        byteBuffer.putShort(code)
        byteBuffer.putInt(session.nextId)
    }

    protected open fun encodeCommand(byteBuffer: ByteBuffer, code: Short, p0: Int) {
        byteBuffer.putInt(16)
        byteBuffer.putShort(PtpConstants.Type.Command)
        byteBuffer.putShort(code)
        byteBuffer.putInt(session.nextId)
        byteBuffer.putInt(p0)
    }

    protected open fun encodeCommand(byteBuffer: ByteBuffer, code: Short, p0: Int, p1: Int) {
        byteBuffer.putInt(20)
        byteBuffer.putShort(PtpConstants.Type.Command)
        byteBuffer.putShort(code)
        byteBuffer.putInt(session.nextId)
        byteBuffer.putInt(p0)
        byteBuffer.putInt(p1)
    }

    protected open fun encodeCommand(byteBuffer: ByteBuffer, code: Short, p0: Int, p1: Int, p2: Int) {
        byteBuffer.putInt(24)
        byteBuffer.putShort(PtpConstants.Type.Command)
        byteBuffer.putShort(code)
        byteBuffer.putInt(session.nextId)
        byteBuffer.putInt(p0)
        byteBuffer.putInt(p1)
        byteBuffer.putInt(p2)
    }

    companion object {
        const val TAG = "Command"
    }
}