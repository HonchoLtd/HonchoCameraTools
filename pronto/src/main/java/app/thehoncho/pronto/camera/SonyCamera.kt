package app.thehoncho.pronto.camera

import app.thehoncho.pronto.Session
import app.thehoncho.pronto.WorkerExecutor
import app.thehoncho.pronto.command.general.GetDeviceInfoCommand
import app.thehoncho.pronto.command.general.GetObjectCommand
import app.thehoncho.pronto.command.general.GetObjectInfoCommand
import app.thehoncho.pronto.command.general.GetStorageIdsCommand
import app.thehoncho.pronto.command.general.OpenSessionCommand
import app.thehoncho.pronto.command.sony.SonyEventCheckCommand
import app.thehoncho.pronto.command.sony.SonyGetSDIOGetExtDeviceInfo
import app.thehoncho.pronto.command.sony.SonyRequestPCModeFirst
import app.thehoncho.pronto.command.sony.SonyRequestPCModeSecond
import app.thehoncho.pronto.command.sony.SonyRequestPCModeThird
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.model.sony.SonyDevicePropDesc
import app.thehoncho.pronto.utils.PacketUtil
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.runBlocking
import java.lang.Integer.max
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SonyCamera(private val session: Session): BaseCamera() {
    private var isPartialSupport = false
    private var devicePropsDescMap = mutableMapOf<Short, SonyDevicePropDesc>()
    private var globalHandlerID = -16383 // This my guess 01 c0 ff ff
    // private var globalHandlerID: Long = 0xffffc001 // This from the gPhoto2
    private var _deviceInfo: DeviceInfo? = null

    // this list camera that give value < 0 or minus not the 1
    private val minus0List = listOf(
        "ILCE-7SM2",
        "ILCE-7M3",
        "ILCE-7SM3",
        "ILCE-7RM4",
        "ILCE-7M2",
        "ILCE-7RM3",
        "ILCE-7S",
        "ILCE-7C",
    )

    override fun execute(executor: WorkerExecutor) = runBlocking {
        val deviceInfo = onConnecting(executor)
        onDeviceInfoCallback?.invoke(deviceInfo)
        _deviceInfo = deviceInfo

        deviceInfo.operationsSupported.forEach { opt ->
            if (opt.toShort() == PtpConstants.Operation.GetPartialObject) {
                isPartialSupport = true
                return@forEach
            }
        }

        requestPtpMode(executor)

        // This cause the problem on Sony A7IV
        // TODO please check this for the Sony A7IV, maybe this cause the problem of the image
//        val checkStorageID = GetStorageIdsCommand(session)
//        executor.handleCommand(checkStorageID)
//        val storageID = checkStorageID.getResult().getOrNull()
//
//        if (storageID != null) {
//            session.log.d(TAG, "getCommand: checkStorageID $storageID size ${storageID.size}")
//        }

        while (executor.isRunning()) {
            val eventCheckCommand = SonyEventCheckCommand(session)
            executor.handleCommand(eventCheckCommand)
            val eventCheckContent = eventCheckCommand.getResult().getOrNull() ?: listOf()

            if (checkInMemoryImage(eventCheckContent, deviceInfo)) {
                val objectImage = onDownloadImage(executor, globalHandlerID)
                objectImage?.let { onImageDownloadedCallback?.invoke(it) }
                session.log.d(TAG, "getCommand: finish download image")
            }

            val event = fetchEvent(executor)
            if (event != null) {
                val handlerID = getHandlerObjectAdded(event)
                if (handlerID != null) {
                    globalHandlerID = handlerID
                    session.log.d(TAG, "getCommand: handlerID $handlerID")
//                    if (eventCheckCommand.content.isEmpty()) {
//                        FirebaseCrashlytics.getInstance().log("getCommand: empty event check so we trigger with event object added")
//                        val objectImage = onDownloadImage(worker, handlerID) // -16383
//                        objectImage?.let { _onImageDownloaded?.invoke(it) }
//                        Log.d(TAG, String.format("getCommand: finish download image %d",handlerID))
//                    }
                }
            }
        }
    }

    private fun fetchEvent(worker: WorkerExecutor): ByteBuffer? {
        val eventIn = ByteBuffer.allocate(
            max(worker.getConnection().maxPacketInSize, worker.getConnection().maxPacketOutSize)
        )
        eventIn.order(ByteOrder.LITTLE_ENDIAN)
        val maxPacketSize = worker.getConnection().maxPacketInSize
        eventIn.position(0)

        val readSize = worker.getConnection().transferInEvent(eventIn.array(), maxPacketSize, 1000) // 0 its mean infinite
        if (readSize < 12) {
            return null
        }

        session.log.d(TAG, "event: " + PacketUtil.hexDumpToString(eventIn.duplicate().array(), 0, readSize))

        val length = eventIn.int
        val type = eventIn.short

        return if (type == PtpConstants.Type.Event.toShort() && length == readSize) {
            val sender = ByteBuffer.allocate(readSize)
            eventIn.position(0)
            eventIn.get(sender.array(), 0, readSize)
            sender
        } else {
            null
        }
    }

    private fun getHandlerObjectAdded(byteBuffer: ByteBuffer): Int? {
        // 10 00 00 00 04 00 03 c2 ff ff ff ff 1d d2 00 00
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.position(0)
        val length = byteBuffer.int
        val type = byteBuffer.short
        val code = byteBuffer.short
        val tx = byteBuffer.int

        return if (code == PtpConstants.Event.SonyObjectAdded.toShort()) {
            byteBuffer.int
        } else {
            session.log.e(TAG, "getHandlerObjectAdded: length $length, type $type, code $code, tx $tx")
            null
        }
    }

    private fun checkInMemoryImage(propDesc: List<SonyDevicePropDesc>, deviceInfo: DeviceInfo): Boolean {
        var isImagePending = false
        propDesc.forEach { desc ->
            if (devicePropsDescMap.containsKey(desc.propCode)) {
                val oldDesc = devicePropsDescMap[desc.propCode]
                if (oldDesc != null && oldDesc.currentValue != desc.currentValue) {
                    if (oldDesc.propCode == (0xd215).toShort()) {
                        session.log.d(TAG, "checkInMemoryImage: ${oldDesc.currentValue} -> ${desc.currentValue}")
                    }
                    devicePropsDescMap[desc.propCode] = desc
                }

                if (oldDesc != null && oldDesc.propCode == (0xd215).toShort()) {
                    // > 0x8000 its value from gPhoto2 -32765
                    // < 0 its my decide the value
                    val currentValue = desc.currentValue
                    if (deviceInfo.model == "ILCE-7M4") {
                        // A7 IV special case cause its show sometime 1 and sometime - minus
                        if (currentValue != 0L) {
                            session.log.d(TAG, "checkInMemoryImage current: ${desc.currentValue} and $currentValue")
                            isImagePending = true
                        }
                    } else if (minus0List.contains(deviceInfo.model)) {
                        if (currentValue < 0) {
                            session.log.d(TAG, "checkInMemoryImage current: ${desc.currentValue} and $currentValue")
                            isImagePending = true
                        }
                    } else {
                        if (currentValue < 0) {
                            session.log.d(TAG, "checkInMemoryImage current: ${desc.currentValue} and $currentValue")
                            isImagePending = true
                        }
                    }
                    // ILCE-7SM3
                }
            } else {
                devicePropsDescMap[desc.propCode] = desc
            }
        }

        return isImagePending
    }

    private fun onConnecting(worker: WorkerExecutor): DeviceInfo {
        val openSessionCommand = OpenSessionCommand(session)
        worker.handleCommand(openSessionCommand)

        val getDeviceInfoCommand = GetDeviceInfoCommand(session)
        worker.handleCommand(getDeviceInfoCommand)

        return getDeviceInfoCommand.getResult().getOrThrow()
    }

    private fun requestPtpMode(worker: WorkerExecutor) {
        val requestPCMode = SonyRequestPCModeFirst(session)
        worker.handleCommand(requestPCMode)

        val requestPCModeSecond = SonyRequestPCModeSecond(session)
        worker.handleCommand(requestPCModeSecond)

        val getSDIOGetExtDeviceInfo = SonyGetSDIOGetExtDeviceInfo(session)
        worker.handleCommand(getSDIOGetExtDeviceInfo)

        val requestPCModeThird = SonyRequestPCModeThird(session)
        worker.handleCommand(requestPCModeThird)
    }

    private fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        val getObjectInfoCommand = GetObjectInfoCommand(session, handler)
        worker.handleCommand(getObjectInfoCommand)
        val objectInfo = getObjectInfoCommand.getResult().getOrNull()

        if (objectInfo == null) {
            session.log.e(TAG, "onDownloadImage: failed when download info image")
            return null
        }

        if (objectInfo.objectFormat != PtpConstants.ObjectFormat.EXIF_JPEG) {
            session.log.e(TAG, "onDownloadImage: object format its not the JPEG but ${objectInfo.objectFormat}")
        }

        return downloadImage(worker, handler, objectInfo)
    }

    private fun downloadImage(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.i(TAG, "downloadImage: start download image")
        val getObjectCommand = GetObjectCommand(session, handler)
        worker.handleCommand(getObjectCommand)
        val imageObject = getObjectCommand.getResult().getOrNull()

        if (imageObject == null) {
            session.log.e(TAG, "downloadImage: failed when download image data")
            return null
        }

        session.log.i(TAG, "downloadImage: finish download image")
        return ObjectImage(objectInfo, handler, imageObject)
    }



    companion object {
        private const val TAG = "SonyCamera"
    }
}