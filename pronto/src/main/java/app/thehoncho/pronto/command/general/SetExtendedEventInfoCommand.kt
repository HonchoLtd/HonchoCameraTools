package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class SetExtendedEventInfoCommand(
    session: Session,
    private val mode: Int = 1  // 1 = enable extended events
) : Command(session) {

    private var throwable: Throwable? = null

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            // OpCode 0x9115, single parameter: mode
            encodeCommand(byteBuffer, PtpConstants.Operation.EosSetEventMode.toShort(), mode)
        } catch (e: Throwable) {
            this.throwable = e
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        // No data payload for this command
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.Ok -> {
                session.log.d(TAG, "SetExtendedEventInfoCommand: OK")
            }
            PtpConstants.Response.GeneralError -> {
                session.log.e(TAG, "SetExtendedEventInfoCommand: General Error")
                throwable = Throwable("SetExtendedEventInfo failed: General Error")
            }
            else -> {
                session.log.w(TAG, "SetExtendedEventInfoCommand: Response $responseCode")
                if (responseCode != PtpConstants.Response.Ok) {
                    throwable = Throwable("SetExtendedEventInfo failed: $responseCode")
                }
            }
        }
    }

    fun getResult(): Result<Unit> {
        return if (throwable != null) {
            Result.failure(throwable!!)
        } else {
            Result.success(Unit)
        }
    }

    companion object {
        private const val TAG = "SetExtendedEventInfoCmd"
    }
}