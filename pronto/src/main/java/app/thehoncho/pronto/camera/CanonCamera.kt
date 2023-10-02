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
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ImageObject
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

class CanonCamera(
    private val session: Session
): BaseCamera() {
    private var isPartialSupport = false
    private var cacheImage = mutableMapOf<Int, ObjectInfo>()

    override fun execute(executor: WorkerExecutor) = runBlocking {
        val deviceInfo = onConnecting(executor)
        onDeviceInfoCallback?.invoke(deviceInfo)

        deviceInfo.operationsSupported.forEach { opt ->
            if (opt.toShort() == PtpConstants.Operation.GetPartialObject) {
                isPartialSupport = true
                return@forEach
            }
        }

        val getStorageIds = GetStorageIdsCommand(session)
        executor.handleCommand(getStorageIds)
        val storageIds = getStorageIds.getResult().getOrNull() ?: IntArray(0)

        if (storageIds.isEmpty()) {
            session.log.e(TAG, "execute: storageIds is empty")
            return@runBlocking
        }

        while (executor.isRunning()) {
            // val handlerCollections = mutableListOf<Int>()

//            if (deviceInfo.model != "Canon EOS RP") {
//
//            } else {
//                storageIds.forEach { storage ->
//                    val fileObjectCollection = walkerFile(worker, storage, 0xffffffff.toInt())
//                    handlerCollections.addAll(fileObjectCollection.filter { it.objectFormat.toInt() == 20 }
//                        .map { fileObject -> fileObject.objectHandler })
//                }
//            }

            session.log.d(TAG, "execute: get handlers with total ${storageIds.size} storage")
            storageIds.forEach { storage ->
                session.log.d(TAG, "execute: get handlers with storage $storage")
                val getHandlersCommand = GetObjectHandlesCommand(session, storage, MtpConstants.FORMAT_EXIF_JPEG)
                executor.handleCommand(getHandlersCommand)
                val handlers = getHandlersCommand.getResult().getOrNull() ?: IntArray(0)
                session.log.d(TAG, "execute: get handlers with total ${handlers.size} handlers on storage $storage")

                val handlerNotCache = handlers.filterNot { cacheImage.keys.contains(it) }
                session.log.d(TAG, "execute: get handlers with total ${handlerNotCache.size} handlers not cache on storage $storage")
                handlerNotCache.forEach { handler ->
                    val getObjectInfo = GetObjectInfoCommand(session, handler)
                    executor.handleCommand(getObjectInfo)
                    getObjectInfo.getResult().getOrNull()?.let { cacheImage[handler] = it  }
                }
            }

            val getCleanHandlers = onHandlersFilterCallback?.invoke(cacheImage.values.toList()) ?: listOf()
            session.log.d(TAG, "execute: get handlers with total ${getCleanHandlers.size} handlers clean")

            getCleanHandlers.forEach { handler ->
                val objectImage = onDownloadImage(executor, handler.handlerID)
                objectImage?.let { image -> onImageDownloadedCallback?.invoke(image) }
            }
        }
    }

    private fun onConnecting(worker: WorkerExecutor): DeviceInfo {
        val openSessionCommand = OpenSessionCommand(session)
        worker.handleCommand(openSessionCommand)

        val getDeviceInfoCommand = GetDeviceInfoCommand(session)
        worker.handleCommand(getDeviceInfoCommand)

        val eosRequestPCModeCommand = EOSRequestPCModeCommand(session)
        worker.handleCommand(eosRequestPCModeCommand)

        return getDeviceInfoCommand.getResult().getOrThrow()
    }

    private fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        val getObjectInfoCommand = GetObjectInfoCommand(session, handler)
        worker.handleCommand(getObjectInfoCommand)
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
        session.log.d(TAG, "downloadImage: start download image")
        val getObjectCommand = GetObjectCommand(session, handler)
        worker.handleCommand(getObjectCommand)
        val imageObject = getObjectCommand.getResult().getOrNull()

        if (imageObject == null) {
            session.log.d(TAG, "downloadImage: failed when download image data")
            return null
        }

        session.log.d(TAG, "downloadImage: finish download image")
        return ObjectImage(objectInfo, handler, imageObject)
    }

    private fun downloadImagePartial(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.d(TAG, "downloadImagePartial: start download image")
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
        private const val TAG = "CanonCamera"
    }
}