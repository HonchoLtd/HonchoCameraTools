package app.thehoncho.pronto.camera

import android.graphics.BitmapFactory
import android.mtp.MtpConstants
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.WorkerExecutor
import app.thehoncho.pronto.command.eos.EOSRequestPCModeCommand
import app.thehoncho.pronto.command.general.GetDeviceInfoCommand
import app.thehoncho.pronto.command.general.GetObjectCommand
import app.thehoncho.pronto.command.general.GetObjectHandlesCommand
import app.thehoncho.pronto.command.general.GetObjectInfoCommand
import app.thehoncho.pronto.command.general.GetObjectPartial
import app.thehoncho.pronto.command.general.GetStorageIdsCommand
import app.thehoncho.pronto.command.general.OpenSessionCommand
import app.thehoncho.pronto.command.nikon.NikonGetDevicePropCommand
import app.thehoncho.pronto.command.nikon.NikonGetEventCommand
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ImageObject
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PtpConstants
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
            session.log.e(TAG, "execute: failed when get storage ids, maybe storage empty")
            listenerCamera?.onError(Throwable("failed when get storage ids, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }
        val storageIds = getStorageIds.getResult().getOrNull() ?: return@runBlocking

        if (storageIds.isEmpty()) {
            session.log.e(TAG, "execute: storageIds is empty")
            listenerCamera?.onError(Throwable("storage ids is empty, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }

        var finishLoadStorageImage = false
        listenerCamera?.onReady()
        while (executor.isRunning()) {
            if (!finishLoadStorageImage) {
                session.log.d(TAG, "execute: get handlers with total ${storageIds.size} storage")
                storageIds.forEach { storage ->
                    session.log.d(TAG, "execute: get handlers with storage $storage")
                    val getHandlersCommand = GetObjectHandlesCommand(session, storage, MtpConstants.FORMAT_EXIF_JPEG)
                    executor.handleCommand(getHandlersCommand)
                    getHandlersCommand.getResult().onFailure {
                        session.log.w(TAG, "execute: failed when get handlers, maybe the handler its empty")
                    }

                    val handlers = getHandlersCommand.getResult().getOrNull() ?: IntArray(0)
                    session.log.d(TAG, "execute: get handlers with total ${handlers.size} handlers on storage $storage")

                    val handlerNotCache = handlers.filterNot { cacheImage.keys.contains(it) }
                    session.log.d(TAG, "execute: get handlers with total ${handlerNotCache.size} handlers not cache on storage $storage")
                    handlerNotCache.forEach { handler ->
                        val getObjectInfo = GetObjectInfoCommand(session, handler)
                        executor.handleCommand(getObjectInfo)
                        getObjectInfo.getResult().onFailure {
                            session.log.e(TAG, "execute: failed when get object info, please restart the camera")
                            listenerCamera?.onError(Throwable("failed when get object info $handler, please restart the camera"))
                            listenerCamera?.onStop()
                            return@runBlocking
                        }
                        getObjectInfo.getResult().getOrNull()?.let { cacheImage[handler] = it }
                    }
                }

                // val getCleanHandlers = onHandlersFilterCallback?.invoke(cacheImage.values.toList()) ?: listOf()
                val getCleanHandlers = listenerCamera?.onHandlersFilter(cacheImage.values.toList()) ?: listOf()
                session.log.d(TAG, "execute: get handlers with total ${getCleanHandlers.size} handlers clean")

                getCleanHandlers.forEach { handler ->
                    val objectImage = onDownloadImage(executor, handler.handlerID)
                    if (objectImage == null) {
                        session.log.e(TAG, "execute: failed when download image $handler")
                        listenerCamera?.onError(Throwable("failed when download image $handler, please restart the camera"))
                        listenerCamera?.onStop()
                        return@runBlocking
                    }
                    objectImage.let { image -> listenerCamera?.onImageDownloaded(image) }
                }

                finishLoadStorageImage = true
            }

            val getEventCommand = NikonGetEventCommand(session)
            executor.handleCommand(getEventCommand)

            getEventCommand.getResult().onFailure {
                session.log.e(TAG, "execute: failed when get event")
                listenerCamera?.onError(Throwable("failed when get event"))
                listenerCamera?.onStop()
                return@runBlocking
            }

            getEventCommand.getResult().getOrNull()?.forEach {
                if (it.code.toInt() == PtpConstants.Event.ObjectAdded) {
                    val objectImage = onDownloadImage(executor, it.parameter)
                    if (objectImage == null) {
                        session.log.e(TAG, "execute: failed when download image $it.parameter")
                        listenerCamera?.onError(Throwable("failed when download image $it.parameter, please restart the camera"))
                        listenerCamera?.onStop()
                        return@runBlocking
                    }
                    objectImage.let { image -> listenerCamera?.onImageDownloaded(image) }
                }
            }
        }
        listenerCamera?.onStop()
    }

    private fun onConnecting(worker: WorkerExecutor): DeviceInfo? {
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

    private fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        val getObjectInfoCommand = GetObjectInfoCommand(session, handler)
        worker.handleCommand(getObjectInfoCommand)
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

    private fun downloadImage(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.d(TAG, "downloadImage: start download image $handler")
        val getObjectCommand = GetObjectCommand(session, handler)
        worker.handleCommand(getObjectCommand)
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

    private fun downloadImagePartial(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.d(TAG, "downloadImagePartial: start download image $handler")
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
            val partialObjectCommand = GetObjectPartial(session, handler, finalOffset, finalSize)
            session.log.d(TAG, "downloadImagePartial: consume command")
            worker.handleCommand(partialObjectCommand)
            partialObjectCommand.getResult().onFailure {
                session.log.w(TAG, "downloadImagePartial: failed when download partial image $handler")
            }
            session.log.d(TAG, "downloadImagePartial: consume command done")

            if (partialObjectCommand.getResult().getOrNull() == null) {
                session.log.e(TAG, "downloadImagePartial: failed when download partial image")
                return null
            }

            val imageBytes = partialObjectCommand.getResult().getOrNull()?.clone() ?: return null
            imageTotalBytes.put(imageBytes, 0, imageBytes.size)

            session.log.d(TAG, "downloadImagePartial: download with offset $offset with size $size")
            offset += size

            if (!worker.isRunning()) {
                return null
            }
        }

        val imageBytes = imageTotalBytes.array()
        val bitmapOptions = BitmapFactory.Options()
        bitmapOptions.inSampleSize = 2
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bitmapOptions)

        session.log.d(TAG, "downloadImagePartial: finish download image")
        return ObjectImage(objectInfo, handler, ImageObject(imageBytes, bitmap))
    }

    companion object {
        private const val TAG = "NikonCamera"
    }
}