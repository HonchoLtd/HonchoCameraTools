package app.thehoncho.pronto.command.nikon

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.NikonEvent
import app.thehoncho.pronto.model.sony.SonyDevicePropDesc
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class NikonGetEventCommand(session: Session): Command(session) {
    private var content: List<NikonEvent>? = null
    private var throwable: Throwable? = null

    fun getResult(): Result<List<NikonEvent>> {
        return if (content != null) {
            Result.success(requireNotNull(content))
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            content = decode(b, length)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.NikonGetEvent.toShort())
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.GeneralError -> {
                session.log.e(NikonGetEventCommand.TAG, "response code its not OK")
                throwable = Throwable("response code its not OK")
            }
            PtpConstants.Response.Ok -> {
                session.log.d(NikonGetEventCommand.TAG, "response code its OK")
            }
            else -> {
                session.log.e(NikonGetEventCommand.TAG, "response code its not OK")
                throwable = Throwable("response code its not OK")
            }
        }
    }

    private fun decode(b: ByteBuffer, length: Int): List<NikonEvent> {
        val eventCount = b.short

        val nikonEvents = arrayListOf<NikonEvent>()

        while (b.position() < length) {
            val eventCode: Short = b.short
            val eventParameter: Int = b.int

            nikonEvents.add(NikonEvent(eventCode, eventParameter))
        }

        return nikonEvents
    }

    companion object {
        private const val TAG = "NikonGetEventCommand"
    }
}