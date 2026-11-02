package app.thehoncho.pronto.command.general

import android.graphics.BitmapFactory
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.ImageObject
import app.thehoncho.pronto.utils.PtpConstants
import java.io.File
import java.nio.ByteBuffer

class GetObjectCommand(
    session: Session,
    private val objectHandler: Int,
    private val sampleSize: Int = 2
) : Command(session) {
    private var options: BitmapFactory.Options = BitmapFactory.Options()
    private var content: ImageObject? = null
    private var throwable: Throwable? = null

    init {
        options.inSampleSize = if (sampleSize in 1..4) sampleSize else 2
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
            b.position(12)
            val bytes = ByteArray(length - 12)
            b[bytes, 0, length - 12]

            // ✅ Write to temp file (consistent with chunked downloads)
            val tempFile = File.createTempFile("pronto_getobj_${objectHandler}_", ".tmp")
            tempFile.writeBytes(bytes)

            // ✅ Decode bitmap directly from file (no extra ByteArray copy)
            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, options)
            content = ImageObject(tempFile, bitmap)
        } catch (throwable: Throwable) {
            this.throwable = throwable
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
        } else {
            session.log.e(TAG, "response code its not OK $responseCode")
            throwable = Throwable("response code its not OK")
        }
    }

    companion object {
        private const val TAG = "GetObjectCommand"
    }
}