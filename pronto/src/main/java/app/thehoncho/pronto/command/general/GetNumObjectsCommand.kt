package app.thehoncho.pronto.command.general

import android.mtp.MtpConstants
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PacketUtil
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GetNumObjectsCommand(session: Session, val storageId: Int): Command(session) {
    private var throwable: Throwable? = null
    private var value: Int = 0

    fun getResult(): Result<Int> {
        return if (throwable == null) {
            Result.success(value)
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.GetNumObjects, storageId, MtpConstants.FORMAT_EXIF_JPEG)
        }catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.GeneralError -> {
                session.log.e(TAG, "response code its not OK $responseCode")
                throwable = Throwable("response code its not OK")
            }
            PtpConstants.Response.Ok -> {
                val buffer = ByteBuffer.wrap(b.array())
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                value = buffer.getInt(12)
                session.log.d(TAG, "response code its OK")
            }
            else -> {
                session.log.e(TAG, "response code its not OK $responseCode")
                throwable = Throwable("response code its not OK")
            }
        }
    }

    companion object {
        const val TAG = "GetNumObjects"
    }
}