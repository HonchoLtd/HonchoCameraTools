package app.thehoncho.pronto.command.sony

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class SonyGetSDIOGetExtDeviceInfo(session: Session): Command(session) {
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
            encodeCommand(byteBuffer, PtpConstants.Operation.PTP_OC_SONY_GetSDIOGetExtDeviceInfo.toShort(), 0xc8)
        } catch (e: Throwable) {
            this.throwable = e
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.GeneralError -> {
                session.log.e(SonyRequestPCMode.TAG, "response code its not OK")
                throwable = Throwable("response code its not OK")
            }
            PtpConstants.Response.Ok -> {
                session.log.d(SonyRequestPCMode.TAG, "response code its OK")
            }
            else -> {
                session.log.e(SonyRequestPCMode.TAG, "response code its not OK")
                throwable = Throwable("response code its not OK")
            }
        }
    }

    companion object {
        private const val TAG = "SonyGetSDIOGetExtDeviceInfo"
    }
}