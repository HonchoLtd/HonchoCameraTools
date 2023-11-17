package app.thehoncho.pronto.command.general

import android.graphics.BitmapFactory
import android.util.Log
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.ImageObject
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer

class GetObjectCommand(
    session: Session,
    private val objectHandler: Int,
    private val sampleSize: Int = 2
): Command(session) {
    private var options: BitmapFactory.Options = BitmapFactory.Options()
    private var content: ImageObject? = null
    private var throwable: Throwable? = null

    init {
        if (sampleSize in 1..4) {
            options.inSampleSize = sampleSize
        } else {
            options.inSampleSize = 2
        }
    }

    fun getResult(): Result<ImageObject> {
        return if (content != null) {
            Result.success(requireNotNull(content))
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.GetObject, objectHandler)
        } catch (throwable: Throwable) {
            this.throwable = throwable
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            val bytes = ByteArray(length - 12)
            b.position(12)
            b[bytes, 0, length - 12]
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            content = ImageObject(bytes, bitmap)
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
        private const val TAG = "GetObjectCommand"
    }
}