package app.thehoncho.pronto.command.sony

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class SonyRequestPCModeThird(session: Session): SonyRequestPCMode(session) {

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.PTP_OC_SONY_SDIOConnect.toShort(), 3, 0, 0)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

}