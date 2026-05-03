package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GetPartialObject64Command(
    session: Session,
    private val objectHandle: Int,
    private val offset: Long,   // 64-bit offset
    private val length: Int     // Chunk size (32-bit is enough for chunks)
) : Command(session) {

    private var content: ByteArray? = null
    private var throwable: Throwable? = null

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

            // Split 64-bit offset into low/high 32-bit parts
            val offsetLow = (offset and 0xFFFFFFFFL).toInt()
            val offsetHigh = (offset ushr 32).toInt()

            // Now this 4-param call will resolve to the new overload ✅
            encodeCommand(
                byteBuffer,
                PtpConstants.Operation.GetPartialObject64.toShort(),
                objectHandle,   // p0
                offsetLow,      // p1
                length,         // p2
                offsetHigh      // p3
            )
        } catch (e: Throwable) {
            this.throwable = e
            session.log.e(TAG, "encodeCommand failed: ${e.message}", e)
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            if (length > 0) {
                content = ByteArray(length)
                b.get(content)
                session.log.d(TAG, "decodeData: received $length bytes")
            }
        } catch (e: Throwable) {
            this.throwable = e
            session.log.e(TAG, "decodeData failed: ${e.message}", e)
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.Ok -> {
                session.log.d(TAG, "GetPartialObject64: OK, received ${content?.size ?: 0} bytes")
            }
            PtpConstants.Response.GeneralError -> {
                session.log.e(TAG, "GetPartialObject64: General Error")
                throwable = Throwable("GetPartialObject64 failed: General Error")
            }
            PtpConstants.Response.DeviceBusy -> {
                session.log.w(TAG, "GetPartialObject64: Device Busy - will retry")
            }
            PtpConstants.Response.StoreNotAvailable -> {
                session.log.w(TAG, "GetPartialObject64: Store Not Available - will retry")
            }
            else -> {
                session.log.w(TAG, "GetPartialObject64: Response ${PtpConstants.responseToString(responseCode.toInt())}")
                if (responseCode != PtpConstants.Response.Ok) {
                    throwable = Throwable("GetPartialObject64 failed: ${PtpConstants.responseToString(responseCode.toInt())}")
                }
            }
        }
    }

    fun getResult(): Result<ByteArray?> {
        return if (throwable != null) {
            Result.failure(throwable!!)
        } else {
            Result.success(content)
        }
    }

    companion object {
        private const val TAG = "GetPartialObject64Cmd"
    }
}