package app.thehoncho.pronto.camera

import android.graphics.BitmapFactory
import android.mtp.MtpConstants
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.WorkerExecutor
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.command.eos.EOSRequestPCModeCommand
import app.thehoncho.pronto.command.general.CloseSessionCommand
import app.thehoncho.pronto.command.general.GetDeviceInfoCommand
import app.thehoncho.pronto.command.general.GetNumObjectsCommand
import app.thehoncho.pronto.command.general.GetObjectCommand
import app.thehoncho.pronto.command.general.GetObjectHandlesCommand
import app.thehoncho.pronto.command.general.GetObjectInfoCommand
import app.thehoncho.pronto.command.general.GetObjectPartial
import app.thehoncho.pronto.command.general.GetStorageIdsCommand
import app.thehoncho.pronto.command.general.GetStorageInfoCommand
import app.thehoncho.pronto.command.general.OpenSessionCommand
import app.thehoncho.pronto.command.nikon.NikonGetDevicePropCommand
import app.thehoncho.pronto.command.nikon.NikonGetEventCommand
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ImageObject
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

class NikonCamera(
    private val session: Session
): BaseCamera() {
    private var isPartialSupport = false
    private var cacheImage = mutableMapOf<Int, ObjectInfo>()

    override fun execute(executor: WorkerExecutor) = runBlocking {
        val deviceInfo = onConnecting(executor)
        if (deviceInfo == null) {
            session.log.e(TAG, "execute: failed when connecting")
            listenerCamera?.onDeviceFailedToConnect(Throwable("failed when get device info, please check the cable or port"))
            listenerCamera?.onStop()
            return@runBlocking
        }
        listenerCamera?.onDeviceConnected(deviceInfo)
        // onDeviceInfoCallback?.invoke(deviceInfo)

        deviceInfo.operationsSupported.forEach { opt ->
            if (opt.toShort() == PtpConstants.Operation.GetPartialObject) {
                isPartialSupport = true
                return@forEach
            }
        }

        val getStorageIds = GetStorageIdsCommand(session)
        executor.handleCommand(getStorageIds)
        getStorageIds.getResult().onFailure {
            session.log.e(TAG, "execute: failed when get storage ids, maybe storage empty. Manufacture: ${deviceInfo.manufacture} Model: ${deviceInfo.model} ")
            listenerCamera?.onError(Throwable("failed when get storage ids, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }
        val storageIds = getStorageIds.getResult().getOrNull() ?: return@runBlocking

        if (storageIds.isEmpty()) {
            session.log.e(TAG, "execute: storageIds is empty. Manufacture: ${deviceInfo.manufacture} Model: ${deviceInfo.model} ")
            listenerCamera?.onError(Throwable("storage ids is empty, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }

        var finishLoadStorageImage = false
        listenerCamera?.onReady()
        // Pre-check storage info before looping
        val validStorageIds = mutableListOf<Int>()
        storageIds.forEach { storage ->
            val getStorageInfoCmd = GetStorageInfoCommand(session, storage)
            session.log.d(TAG, "Checking storage $storage with GetStorageInfoCommand")
            executor.handleCommand(getStorageInfoCmd)
            session.log.d(TAG, "Executed GetStorageInfoCommand for $storage")
            val storageInfoResult = getStorageInfoCmd.getResult()
            session.log.d(TAG, "Result for $storage: $storageInfoResult")
            storageInfoResult.onFailure {
                session.log.e(TAG, "Failed to get storage info for $storage: ${it.message}")
            }
            storageInfoResult.getOrNull()?.let { info ->
                session.log.d(TAG, "Storage $storage info: type=${info.storageType}, fsType=${info.filesystemType}, maxCapacity=${info.maxCapacity}, freeSpace=${info.freeSpaceInBytes}")
                if (info.maxCapacity > 0) {
                    validStorageIds.add(storage)
                    session.log.d(TAG, "Storage $storage is valid with capacity ${info.maxCapacity}")
                } else {
                    session.log.e(TAG, "Storage $storage has maxCapacity=0, skipping")
                }
            }
        }

        val currentTotalImageByStorage = mutableMapOf<Int, Int>()
        while (executor.isRunning()) {
            if (!finishLoadStorageImage) {
                session.log.d(TAG, "execute: get handlers with total ${storageIds.size} storage")
                validStorageIds.forEach { storage ->
                    val getHandlersCommand = handlerCommandRetry(session,executor) {
                        GetObjectHandlesCommand(session, storage, MtpConstants.FORMAT_EXIF_JPEG)
                    }

                    getHandlersCommand.getResult().onFailure {
                        session.log.w(TAG, "execute: failed when get handlers, maybe the handler its empty")
                    }

                    val handlers = getHandlersCommand.getResult().getOrNull() ?: IntArray(0)
                    session.log.d(TAG, "execute: get handlers with total ${handlers.size} handlers on storage $storage")

                    val jpegInfos = mutableListOf<Pair<Int, ObjectInfo>>()

                    handlers.forEach { handler ->
                        val infoCmd = GetObjectInfoCommand(session, handler)
                        executor.handleCommand(infoCmd)
                        val info = infoCmd.getResult().getOrNull()

                        val name = info?.filename?.uppercase() ?: ""
                        val suffix = if (name.length >= 4) name.takeLast(4) else name

                        // Always log the handler being processed
                        session.log.d(TAG, "Processing handler=$handler, Filename=$name, Format=${info?.objectFormat}, CaptureDate=${info?.captureDate}, Suffix=$suffix, Sequence=${info?.sequenceNumber}")

                        // Log the full info object for debugging
                        session.log.d(TAG, "Full ObjectInfo for handler=$handler: $info")

                        if (info?.objectFormat == MtpConstants.FORMAT_EXIF_JPEG &&
                            (name.endsWith(".JPG") || name.endsWith(".JPEG")) &&
                            !cacheImage.containsKey(handler)) {

                            session.log.d(TAG, "Candidate JPEG handler: $handler, Filename=$name, CaptureDate=${info.captureDate}, Suffix=$suffix")
                            jpegInfos.add(handler to info)
                        } else {
                            session.log.d(TAG, "Skipping handler: $handler, Filename=$name, Format=${info?.objectFormat}, CaptureDate=${info?.captureDate}, Suffix=$suffix")
                        }
                    }
                    // ✅ Add log before starting jpegInfos loop
                    session.log.d(TAG, "Starting to loop jpegInfos with total ${jpegInfos.size} candidates")
                    // Now process only the filtered JPEGs not already in cache
                    jpegInfos.forEach { (handler, info) ->
                        // Log handler and full info object before processing
                        session.log.d(TAG, "Processing jpegInfos entry: Handler=$handler, Info=$info")
                        val name = info.filename?.uppercase() ?: ""
                        val suffix = if (name.length >= 4) name.takeLast(4) else name
                        val alreadyCached = cacheImage.values.any {
                            it.filename?.uppercase() == name
                        }

                        // Log after duplicate check
                        session.log.d(TAG, "Duplicate check result for Handler=$handler, Filename=$name, CaptureDate=${info.captureDate}, alreadyCached=$alreadyCached, Suffix=$suffix")

                        if (!alreadyCached) {
                            cacheImage[handler] = info
                            session.log.d(TAG, "Cached JPEG object: $name (Handler: $handler, CaptureDate=${info.captureDate}, Suffix=$suffix)")
                        } else {
                            session.log.d(TAG, "Skipping duplicate filename+date: $name (Handler: $handler, CaptureDate=${info.captureDate}, Suffix=$suffix)")
                        }
                    }
                }

                // val getCleanHandlers = onHandlersFilterCallback?.invoke(cacheImage.values.toList()) ?: listOf()
                val getCleanHandlers = listenerCamera?.onHandlersFilter(cacheImage.values.toList()) ?: listOf()
                session.log.d(TAG, "execute: get handlers with total ${getCleanHandlers.size} handlers clean")

                getCleanHandlers.forEach { info ->
                    val name = info.filename?.uppercase() ?: ""
                    val suffix = if (name.length >= 4) name.takeLast(4) else name
                    session.log.d(TAG, "Downloading handler: ${info.handlerID}, Filename=$name, CaptureDate=${info.captureDate}, Suffix=$suffix")

                    val objectImage = onDownloadImage(executor, info.handlerID)
                    if (objectImage == null) {
                        session.log.e(TAG, "execute: failed when download image $name (Handler: ${info.handlerID}, CaptureDate=${info.captureDate}, Suffix=$suffix)")
                        listenerCamera?.onError(Throwable("failed when download image $name, please restart the camera"))
                        listenerCamera?.onStop()
                        return@runBlocking
                    }
                    listenerCamera?.onImageDownloaded(objectImage)
                }

                finishLoadStorageImage = true
            }

            val getEventCommand = NikonGetEventCommand(session)
            executor.handleCommand(getEventCommand)

            getEventCommand.getResult().onFailure {
                session.log.e(TAG, "execute: failed when get event. Manufacture: ${deviceInfo.manufacture} Model: ${deviceInfo.model} ")
                listenerCamera?.onError(Throwable("failed when get event"))
                listenerCamera?.onStop()
                return@runBlocking
            }

            getEventCommand.getResult().getOrNull()?.forEach {
                if (it.code.toInt() == PtpConstants.Event.ObjectAdded) {
                    val handler = it.parameter
                    val infoCmd = GetObjectInfoCommand(session, it.parameter)
                    executor.handleCommand(infoCmd)

                    val info = infoCmd.getResult().getOrNull()

                    val name = info?.filename?.uppercase() ?: ""
                    val suffix = if (name.length >= 4) name.takeLast(4) else name

                    // Always log the handler being processed
                    session.log.d(TAG, "Processing handler=$handler, Filename=$name, Format=${info?.objectFormat}, CaptureDate=${info?.captureDate}, Suffix=$suffix, Sequence=${info?.sequenceNumber}")

                    // Log the full info object for debugging
                    session.log.d(TAG, "Full ObjectInfo for handler=$handler: $info")

                    if (info?.objectFormat == MtpConstants.FORMAT_EXIF_JPEG &&
                        (name.endsWith(".JPG") || name.endsWith(".JPEG"))) {
                        session.log.d(TAG, "Candidate JPEG handler: $handler, Filename=$name, CaptureDate=${info.captureDate}, Suffix=$suffix")
                        cacheImage[handler] = info
                        val objectImage = onDownloadImage(executor, handler)
                        if (objectImage == null) {
                            session.log.e(TAG, "execute: failed when download image $it.parameter")
                            listenerCamera?.onError(Throwable("failed when download image $it.parameter, please restart the camera"))
                            listenerCamera?.onStop()
                            return@runBlocking
                        }
                        objectImage.let { image -> listenerCamera?.onImageDownloaded(image) }
                    } else {
                        session.log.d(TAG, "Skipping handler: $handler, Filename=$name, Format=${info?.objectFormat}, CaptureDate=${info?.captureDate}, Suffix=$suffix")
                    }
                }
            }
        }
        listenerCamera?.onStop()
    }

    private fun onConnecting(worker: WorkerExecutor): DeviceInfo? {
        val closeSessionCommand = CloseSessionCommand(session)
        worker.handleCommand(closeSessionCommand)

        val openSessionCommand = OpenSessionCommand(session)
        worker.handleCommand(openSessionCommand)

        val getDeviceInfoCommand = GetDeviceInfoCommand(session)
        worker.handleCommand(getDeviceInfoCommand)

        val getNikonGetDevicePropCommand = NikonGetDevicePropCommand(session)
        worker.handleCommand(getNikonGetDevicePropCommand)

        getDeviceInfoCommand.getResult().onFailure {
            session.log.e(TAG, "onConnecting: failed when get device info")
        }

        return getDeviceInfoCommand.getResult().getOrNull()
    }

    private suspend fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        val getObjectInfoCommand =  handlerCommandRetry(session,worker) {
            GetObjectInfoCommand(session, handler)
        }

        getObjectInfoCommand.getResult().onFailure {
            session.log.w(TAG, "onDownloadImage: failed when download info image $handler")
        }
        val objectInfo = getObjectInfoCommand.getResult().getOrNull()

        if (objectInfo == null) {
            session.log.e(TAG, "onDownloadImage: failed when download info image")
            return null
        }

        return if (isPartialSupport) {
            downloadImagePartial(worker, handler, objectInfo)
        } else {
            downloadImage(worker, handler, objectInfo)
        }
    }

    private suspend fun downloadImage(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.d(TAG, "downloadImage: start download image $handler")
        val getObjectCommand =  handlerCommandRetry(session,worker) {
            GetObjectCommand(session, handler)
        }
        getObjectCommand.getResult().onFailure {
            session.log.w(TAG, "downloadImage: failed when download image data $handler")
        }
        val imageObject = getObjectCommand.getResult().getOrNull()

        if (imageObject == null) {
            session.log.d(TAG, "downloadImage: failed when download image data")
            return null
        }

        session.log.d(TAG, "downloadImage: finish download image")
        return ObjectImage(objectInfo, handler, imageObject)
    }

    private suspend fun downloadImagePartial(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
//        session.log.d(TAG, "downloadImagePartial: start download image $handler")
        var offset = 0
        val totalBytes = objectInfo.objectCompressedSize
        val imageTotalBytes = ByteBuffer.allocate(totalBytes)
        imageTotalBytes.position(0)
        val maxPacketSize =  1024 * 1024

        while(offset < totalBytes) {
            var size = totalBytes - offset
            if (size > maxPacketSize) {
                size = maxPacketSize
            }

            val finalOffset = offset
            val finalSize = size
            val partialObjectCommand =  handlerCommandRetry(session,worker) {
                GetObjectPartial(session, handler, finalOffset, finalSize)
            }

//            session.log.d(TAG, "downloadImagePartial: consume command")
            partialObjectCommand.getResult().onFailure {
                session.log.w(TAG, "downloadImagePartial: failed when download partial image $handler")
            }
//            session.log.d(TAG, "downloadImagePartial: consume command done")

            if (partialObjectCommand.getResult().getOrNull() == null) {
                session.log.e(TAG, "downloadImagePartial: failed when download partial image")
                return null
            }

            val imageBytes = partialObjectCommand.getResult().getOrNull()?.clone() ?: return null
            imageTotalBytes.put(imageBytes, 0, imageBytes.size)

//            session.log.d(TAG, "downloadImagePartial: download with offset $offset with size $size")
            offset += size

            if (!worker.isRunning()) {
                return null
            }
        }

        val imageBytes = imageTotalBytes.array()
        val bitmapOptions = BitmapFactory.Options()
        bitmapOptions.inSampleSize = 2
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bitmapOptions)

//        session.log.d(TAG, "downloadImagePartial: finish download image")
        return ObjectImage(objectInfo, handler, ImageObject(imageBytes, bitmap))
    }

    private suspend fun <T: Command>handlerCommandRetry(session: Session,worker: WorkerExecutor, getCommand: ()-> T): T {
        while(worker.isRunning()) {
            val command = getCommand()
            worker.handleCommand(command)
            if(!command.isRetry()) {
                return command
            }
            session.log.e(TAG, "${command::class.simpleName} request retry")
            delay(500L)
        }
        error("Worker is shutting down")
    }

    companion object {
        private const val TAG = "NikonCamera"
    }
}