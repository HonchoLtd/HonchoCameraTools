package app.thehoncho.pronto.command.sony

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.model.sony.SonyDevicePropDesc
import app.thehoncho.pronto.utils.PacketUtil
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

class SonyEventCheckCommand(session: Session): Command(session) {

    private var content: List<SonyDevicePropDesc>? = null
    private var throwable: Throwable? = null

    fun getResult(): Result<List<SonyDevicePropDesc>> {
        return if (content != null) {
            Result.success(requireNotNull(content))
        } else {
            Result.failure(throwable ?: Throwable("Unknown error"))
        }
    }

    override fun encodeCommand(byteBuffer: ByteBuffer) {
        try {
            encodeCommand(byteBuffer, PtpConstants.Operation.PTP_OC_SONY_GetAllDevicePropData.toShort())
        } catch (e: Throwable) {
            throwable = e
        }
    }

    override fun decodeData(b: ByteBuffer, length: Int) {
        PacketUtil.hexDumpToString(b.array(), 0, length)
        b.position(0)
        b.order(ByteOrder.LITTLE_ENDIAN)
        content = decode(b, length)
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

    private fun decode(b: ByteBuffer, length: Int): List<SonyDevicePropDesc> {
        val sonyDevicePropDescriptions = arrayListOf<SonyDevicePropDesc>()

        if (b.array().size <= 20) {
            return sonyDevicePropDescriptions
        }

        b.order(ByteOrder.LITTLE_ENDIAN)
        val currentPos = 20
        b.position(currentPos) // This start for parse the properties

        while (b.position() < length) {
            val propCode = b.short
            val dataType = b.short
            val changeMethod = b.get()
            val getSet: Short = b.get().toShort()
            var defaultValue = 0L
            var currentValue = 0L
            var formFlag: Short = 0
            // int[] description = null;

            val message = String.format(
                "propCode 0x%04X, dataType 0x%04X, changeMethod 0x%02X, getSet 0x%02X",
                propCode,
                dataType,
                changeMethod,
                getSet
            );
            session.log.d(TAG, message);

            if (dataType == PtpConstants.Datatype.int8.toShort() || dataType == PtpConstants.Datatype.uint8.toShort()) {
                defaultValue = b.get().toLong()
                currentValue = b.get().toLong()
                formFlag = b.get().toShort()
                if (formFlag == 2.toShort()) {
                    // get size, and then read the byte to fill array size
                    PacketUtil.readU8Enumeration(b)
                } else if (formFlag == 1.toShort()) {
                    // get size, and then read the byte to fill array size
                    val minI: Short = b.get().toShort()
                    val maxI = b.get().toShort()
                    val step = b.get().toShort()
//                    int arraySize = (maxI - minI) / step + 1;
//                    if (arraySize < 0) {
//                        arraySize = arraySize * -1;
//                    }
//                    description = new int[arraySize];
//                    for (int i = 0; i < description.length; ++i) {
//                        description[i] = minI + i * step;
//                    }
                }
            } else if (dataType == PtpConstants.Datatype.int16.toShort() || dataType == PtpConstants.Datatype.uint16.toShort()) {
                defaultValue = b.short.toLong()
                currentValue = b.short.toLong()
                formFlag = b.get().toShort()
                if (formFlag == 2.toShort()) {
                    // get size, and then read the byte to fill array size
                    PacketUtil.readU16Enumeration(b)
                } else if (formFlag == 1.toShort()) {
                    // get size, and then read the byte to fill array size
                    val minI = b.short
                    val maxI = b.short
                    val step = b.short
//                    int arraySize = (maxI - minI) / step + 1;
//                    if (arraySize < 0) {
//                        arraySize = arraySize * -1;
//                    }
//                    description = new int[arraySize];
//                    for (int i = 0; i < description.length; ++i) {
//                        description[i] = minI + i * step;
//                    }
                }
            } else if (dataType == PtpConstants.Datatype.int32.toShort() || dataType == PtpConstants.Datatype.uint32.toShort()) {
                defaultValue = b.int.toLong()
                currentValue = b.int.toLong()
                formFlag = b.get().toShort()
                if (formFlag == 2.toShort()) {
                    // get size, and then read the byte to fill array size
                    PacketUtil.readU32Enumeration(b)
                } else if (formFlag == 1.toShort()) {
                    // get size, and then read the byte to fill array size
                    val minI = b.int
                    val maxI = b.int
                    val step = b.int
//                    int arraySize = (maxI - minI) / step + 1;
//                    if (arraySize < 0) {
//                        arraySize = arraySize * -1;
//                    }
//                    description = new int[arraySize];
//                    for (int i = 0; i < description.length; ++i) {
//                        description[i] = minI + i * step;
//                    }
                }
            } else if (dataType == PtpConstants.Datatype.int64.toShort() || dataType == PtpConstants.Datatype.uint64.toShort()) {
                defaultValue = b.long
                currentValue = b.long
                formFlag = b.get().toShort()
                if (formFlag == 2.toShort()) {
                    // get size, and then read the byte to fill array size
                    PacketUtil.readU32Enumeration(b)
                } else if (formFlag == 1.toShort()) {
                    // get size, and then read the byte to fill array size
                    val minI = b.long
                    val maxI = b.long
                    val step = b.long
//                    int arraySize = (maxI - minI) / step + 1;
//                    if (arraySize < 0) {
//                        arraySize = arraySize * -1;
//                    }
//                    description = new int[arraySize];
//                    for (int i = 0; i < description.length; ++i) {
//                        description[i] = (int) (minI + i * step);
//                    }
                }
            } else {
                throw UnsupportedOperationException(
                    String.format(
                        "not support other dataType 0x%04X",
                        dataType
                    )
                )
            }

            val sonyDevicePropDesc = SonyDevicePropDesc()
            sonyDevicePropDesc.propCode = propCode
            sonyDevicePropDesc.dataType = dataType
            sonyDevicePropDesc.changeMethod = changeMethod.toShort()
            sonyDevicePropDesc.getSet = getSet
            sonyDevicePropDesc.defaultValue = defaultValue
            sonyDevicePropDesc.currentValue = currentValue
            sonyDevicePropDesc.formFlag = formFlag
//            devicePropDesc.description = description;

//            if (devicePropDesc.dataType == PtpConstants.Datatype.uint8 || devicePropDesc.dataType == PtpConstants.Datatype.int8) {
//                Log.d(TAG, "defaultValue: " + String.format("%02X", defaultValue));
//                Log.d(TAG, "currentValue: " + String.format("%02X", currentValue));
//            } else if (devicePropDesc.dataType == PtpConstants.Datatype.uint16 || devicePropDesc.dataType == PtpConstants.Datatype.int16) {
//                Log.d(TAG, "defaultValue: " + String.format("%04X", defaultValue));
//                Log.d(TAG, "currentValue: " + String.format("%04X", currentValue));
//            } else if (devicePropDesc.dataType == PtpConstants.Datatype.uint32 || devicePropDesc.dataType == PtpConstants.Datatype.int32) {
//                Log.d(TAG, "defaultValue: " + String.format("%08X", defaultValue));
//                Log.d(TAG, "currentValue: " + String.format("%08X", currentValue));
//            } else if (devicePropDesc.dataType == PtpConstants.Datatype.uint64 || devicePropDesc.dataType == PtpConstants.Datatype.int64) {
//                Log.d(TAG, "defaultValue: " + String.format("%016X", defaultValue));
//                Log.d(TAG, "currentValue: " + String.format("%016X", currentValue));
//            }
//            Log.d(TAG, "formFlag: " + String.format("%02X", formFlag));
//
//            if (description != null) {
//                StringBuilder descriptionString = new StringBuilder("[");
//
//                for (int i = 0; i < description.length; i++) {
//                    descriptionString.append(description[i]);
//                    if (i < description.length - 1) {
//                        descriptionString.append(", ");
//                    }
//                }
//
//                descriptionString.append("]");
//
//                Log.d(TAG, "description: " + descriptionString);
//            }

            val messageResult = String.format(
                Locale.getDefault(),
                "Current Value: %d defaultValue: %d",
                currentValue,
                defaultValue
            )
            session.log.d(TAG, messageResult)

            sonyDevicePropDescriptions.add(sonyDevicePropDesc)
        }

        return sonyDevicePropDescriptions
    }

    companion object {
        private const val TAG = "SonyEventCheckCommand"
    }
}