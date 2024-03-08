package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GetDeviceInfoCommand(session: Session) : Command(session) {
    private var content: DeviceInfo? = null
    private var throwable: Throwable? = null

    fun getResult(): Result<DeviceInfo> {
        return if (content != null) {
            Result.success(requireNotNull(content))
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.GetDeviceInfo)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            val currentPosition = b.position()
            b.position(0)

            val bytes = ByteArray(length)
            b[bytes, 0, length]

            val bytesBuffer = ByteBuffer.wrap(bytes)
            bytesBuffer.order(ByteOrder.LITTLE_ENDIAN)
            bytesBuffer.position(currentPosition)

            content = DeviceInfo(bytesBuffer, length)
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
        private const val TAG = "GetDeviceInfoCommand"
    }
}