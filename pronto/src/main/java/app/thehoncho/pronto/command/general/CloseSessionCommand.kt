package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class CloseSessionCommand(session: Session): Command(session) {

    private var throwable: Throwable? = null

    fun getResult(): Result<Boolean> {
        return if (throwable == null) {
            Result.success(true)
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.CloseSession)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.GeneralError -> {
                session.log.e(TAG, "response code its not OK")
            }
            PtpConstants.Response.Ok -> {
                session.log.d(TAG, "response code its OK")
            }
            else -> {
                session.log.e(TAG, "response code its not OK")
            }
        }
    }

    companion object {
        private const val TAG = "CloseSessionCommand"
    }
}