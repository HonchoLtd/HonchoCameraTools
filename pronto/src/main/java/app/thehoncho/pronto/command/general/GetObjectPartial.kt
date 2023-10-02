package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class GetObjectPartial(
    session: Session,
    private val objectHandle: Int,
    private val offset: Int,
    private val length: Int
): Command(session) {

    private var content: ByteArray? = null
    private var throwable: Throwable? = null

    fun getResult(): Result<ByteArray> {
        return if (content != null) {
            Result.success(requireNotNull(content))
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.GetPartialObject, objectHandle, offset, length)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            val bytes = ByteArray(length - 12)
            b.position(12)
            b[bytes, 0, length - 12]
            content = bytes.copyOf(bytes.size)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        if (responseCode == PtpConstants.Response.GeneralError) {
            session.log.e(TAG, "response code its not OK")
            throwable = Throwable("response code its not OK")
        } else if (responseCode == PtpConstants.Response.Ok) {
            if (content != null) {
                session.log.d(TAG, "response code its OK")
            } else {
                session.log.e(TAG, "response code its OK but content is null")
                throwable = Throwable("response code its OK but content is null")
            }
        } else {
            session.log.e(TAG, "response code its not OK")
            throwable = Throwable("response code its not OK")
        }
    }


    companion object {
        private const val TAG = "GetObjectPartial"
    }
}