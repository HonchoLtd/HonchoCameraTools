package app.thehoncho.pronto.command.sony

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

abstract class SonyRequestPCMode(session: Session): Command(session) {

    protected var throwable: Throwable? = null

    fun getResult(): Result<Boolean> {
        return if (throwable == null) {
            Result.success(true)
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.GeneralError -> {
                session.log.e(TAG, "response code its not OK")
                throwable = Throwable("response code its not OK")
            }
            PtpConstants.Response.Ok -> {
                session.log.d(TAG, "response code its OK")
            }
            else -> {
                session.log.e(TAG, "response code its not OK")
                throwable = Throwable("response code its not OK")
            }
        }
    }

    companion object {
        const val TAG = "SonyRequestPCMode"
    }
}