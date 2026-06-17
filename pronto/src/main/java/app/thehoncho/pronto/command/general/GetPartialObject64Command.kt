package app.thehoncho.pronto.command.general

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GetPartialObject64Command(
    session: Session,
    private val objectHandle: Int,
    private val offset: Long,   // 64-bit offset
    private val length: Int     // Chunk size
) : Command(session) {

    private var content: ByteArray? = null
    private var throwable: Throwable? = null

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            val startPos = byteBuffer.position()

            // Split 64-bit offset into low/high 32-bit parts
            val offsetLow = (offset and 0xFFFFFFFFL).toInt()
            val offsetHigh = (offset ushr 32).toInt()

            session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Encoding 0x9172 | Handle: 0x${objectHandle.toString(16)} | Offset: $offset (Low: 0x${offsetLow.toString(16)}, High: 0x${offsetHigh.toString(16)}) | Length: $length")

            encodeCommand(
                byteBuffer,
                PtpConstants.Operation.GetPartialObject64.toShort(), // 0x9172
                objectHandle,   // P1
                offsetLow,      // P2
                length,         // P3
                offsetHigh      // P4
            )

            // 🚨 LOG THE EXACT RAW BYTES SENT TO THE CAMERA
            val endPos = byteBuffer.position()
            val bytesSent = ByteArray(endPos - startPos)
            byteBuffer.position(startPos)
            byteBuffer.get(bytesSent)
            byteBuffer.position(endPos) // Restore position

            session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Raw packet sent (${bytesSent.size} bytes): ${bytesSent.joinToString(" ") { "%02X".format(it) }}")

        } catch (e: Throwable) {
            this.throwable = e
            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] encodeCommand failed: ${e.message}", e)
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            // The PTP stack has already skipped the 12-byte Data Header.
            // So b.remaining() is exactly the size of the actual image payload.
            val actualPayloadSize = b.remaining()

            session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] decodeData called | Container Length: $length | Actual Payload (Remaining): $actualPayloadSize")

            if (actualPayloadSize > 0) {
                content = ByteArray(actualPayloadSize)
                b.get(content) // Reads exactly the remaining bytes

                // 🚨 LOG THE FIRST 16 BYTES TO VERIFY DATA INTEGRITY
                // If the download is working, this MUST start with "FF D8" (JPEG Magic Header)
                val previewBytes = content!!.take(16).toByteArray()
                val previewHex = previewBytes.joinToString(" ") { "%02X".format(it) }
                session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Received $actualPayloadSize bytes. First 16 bytes: $previewHex")

            } else {
                session.log.w(TAG, "CanonCamera ⚠️ [R1 DOWNLOAD] Actual payload is 0 bytes! Camera likely rejected the command.")
            }
        } catch (e: Throwable) {
            this.throwable = e
            // Print exception class name so you can see if it's a BufferUnderflowException
            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] decodeData failed: ${e.javaClass.simpleName} - ${e.message}", e)
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] decodeResponse | Code: 0x${responseCode.toString(16)} (${PtpConstants.responseToString(responseCode.toInt())}) | Content is null: ${content == null}")
        when (responseCode) {
            PtpConstants.Response.Ok -> {
                session.log.d(TAG, "CanonCamera ✅ [R1 DOWNLOAD] GetPartialObject64: OK, received ${content?.size ?: 0} bytes")
            }
            PtpConstants.Response.GeneralError -> {
                session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] GetPartialObject64: General Error")
                throwable = Throwable("GetPartialObject64 failed: General Error")
            }
            PtpConstants.Response.DeviceBusy -> {
                session.log.w(TAG, "CanonCamera ⚠️ [R1 DOWNLOAD] GetPartialObject64: Device Busy - will retry")
            }
            PtpConstants.Response.StoreNotAvailable -> {
                session.log.w(TAG, "CanonCamera ⚠️ [R1 DOWNLOAD] GetPartialObject64: Store Not Available - will retry")
            }
            else -> {
                session.log.w(TAG, "CanonCamera ⚠️ [R1 DOWNLOAD] GetPartialObject64: Response ${PtpConstants.responseToString(responseCode.toInt())}")
                if (responseCode != PtpConstants.Response.Ok) {
                    throwable = Throwable("GetPartialObject64 failed: ${PtpConstants.responseToString(responseCode.toInt())}")
                }
            }
        }
    }

    fun getResult(): Result<ByteArray?> {
        return if (throwable != null) {
            Result.failure(throwable!!)
        } else {
            Result.success(content)
        }
    }

    companion object {
        private const val TAG = "GetPartialObject64Cmd"
    }
}