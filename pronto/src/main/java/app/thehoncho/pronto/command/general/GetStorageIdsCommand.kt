package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PacketUtil
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class GetStorageIdsCommand(session: Session): Command(session) {

    private var content: IntArray? = null
    private var throwable: Throwable? = null

    fun getResult(): Result<IntArray> {
        return if (content != null) {
            Result.success(requireNotNull(content))
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.GetStorageIDs)
        } catch (throwable: Throwable) {
            this.throwable = throwable
            // this.listener.onComplete(Task.error(throwable))
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            content = PacketUtil.readU32Array(b)
            // this.listener.onComplete(Task.of(storageIds));
        } catch (throwable: Throwable) {
            this.throwable = throwable
            // this.listener.onComplete(Task.error(throwable));
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
        private const val TAG = "GetStorageIdsCommand"
    }
}