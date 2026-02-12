package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.StorageInfo
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GetStorageInfoCommand(session: Session, val storageId: Int)  : Command(session) {
    private var content: StorageInfo? = null
    private var throwable: Throwable? = null

    fun getResult(): Result<StorageInfo?> {
        return if (throwable == null) {
            Result.success(content)
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.GetStorageInfo, storageId)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            val currentPosition = b.position()
            session.log.d(TAG, "decodeData start: length=$length, pos=$currentPosition, limit=${b.limit()}, remaining=${b.remaining()}, order=${b.order()}")

            // Snapshot without advancing original buffer
            val snapshot = ByteArray(b.remaining())
            b.slice().get(snapshot)
            session.log.d(TAG, "decodeData raw snapshot: ${snapshot.joinToString(" ") { String.format("%02X", it) }}")
            b.position(0)

            val bytes = ByteArray(length)
            b[bytes, 0, length]

            val bytesBuffer = ByteBuffer.wrap(bytes)
            bytesBuffer.order(ByteOrder.LITTLE_ENDIAN)
            bytesBuffer.position(currentPosition)

            content = StorageInfo(bytesBuffer, length)
        } catch (throwable: Throwable) {
            this.throwable = throwable
            session.log.e(TAG, "decodeData failed: ${throwable.message}", throwable)
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        if (responseCode == PtpConstants.Response.GeneralError) {
            session.log.e(TAG, "response code its not OK $responseCode")
            throwable = Throwable("response code its not OK")
        } else if (responseCode == PtpConstants.Response.Ok) {
            if (content != null) {
                session.log.d(TAG, "response code its OK")
            } else {
                session.log.e(TAG, "response code its OK but content is null $responseCode")
                throwable = Throwable("response code its OK but content is null")
            }
        } else if (responseCode == PtpConstants.Response.StoreNotAvailable) {
            content = null
            session.log.e(TAG, "response code Store Not Available $responseCode")
            throwable = Throwable("response code Store Not Available content is null")
        } else {
            session.log.e(TAG, "response code its not OK $responseCode")
            throwable = Throwable("response code its not OK")
        }
    }

    companion object {
        private const val TAG = "GetStorageInfo"
    }
}