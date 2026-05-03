package app.thehoncho.pronto.command.general

// File: GetDirObjectInfoListCommand.kt
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.ObjectInfoR1
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GetDirObjectInfoListCommand(
    session: Session,
    private val storageId: Int,
    private val parentHandle: Int = 0,          // 0 = root directory
    private val formatFilter: Int = 0x00200000  // Property code filter (0x200000 = JPEG)
) : Command(session) {

    private var objectList: List<ObjectInfoR1> = emptyList()
    private var throwable: Throwable? = null

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

            // OpCode 0x917A, params: [storageId, parentHandle, formatFilter]
            // Uses existing 3-param overload: code + p0, p1, p2
            encodeCommand(
                byteBuffer,
                PtpConstants.Operation.GetDirObjectInfoList.toShort(),
                storageId,          // p0: Storage ID
                parentHandle,       // p1: Parent/Folder Handle (0 = root)
                formatFilter        // p2: Format/Property filter
            )
        } catch (e: Throwable) {
            this.throwable = e
            session.log.e(TAG, "encodeCommand failed: ${e.message}", e)
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        try {
            // Parse R1-specific object list format
            objectList = ObjectInfoR1.parseList(b, length)
            session.log.d(TAG, "decodeData: parsed ${objectList.size} objects")
        } catch (e: Exception) {
            session.log.e(TAG, "Failed to decode GetDirObjectInfoList data", e)
            this.throwable = e
        }
    }

    override fun decodeResponse(b: ByteBuffer, length: Int) {
        when (responseCode) {
            PtpConstants.Response.Ok -> {
                session.log.d(TAG, "GetDirObjectInfoList: OK, ${objectList.size} items")
            }
            PtpConstants.Response.GeneralError -> {
                session.log.e(TAG, "GetDirObjectInfoList: General Error")
                throwable = Throwable("GetDirObjectInfoList failed: General Error")
            }
            PtpConstants.Response.InvalidStorageID -> {
                session.log.e(TAG, "GetDirObjectInfoList: Invalid Storage ID $storageId")
                throwable = Throwable("Invalid storage ID: $storageId")
            }
            else -> {
                session.log.w(TAG, "GetDirObjectInfoList: Response ${PtpConstants.responseToString(responseCode.toInt())}")
                if (responseCode != PtpConstants.Response.Ok) {
                    throwable = Throwable("GetDirObjectInfoList failed: ${PtpConstants.responseToString(responseCode.toInt())}")
                }
            }
        }
    }

    fun getResult(): Result<List<ObjectInfoR1>> {
        return if (throwable != null) {
            Result.failure(throwable!!)
        } else {
            Result.success(objectList)
        }
    }

    companion object {
        private const val TAG = "GetDirObjectInfoListCmd"
    }
}