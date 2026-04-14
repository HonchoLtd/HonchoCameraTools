package app.thehoncho.pronto.camera

import android.graphics.BitmapFactory
import android.mtp.MtpConstants
import androidx.exifinterface.media.ExifInterface
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.WorkerExecutor
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.command.eos.EOSGetEventCommand
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
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ImageObject
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectImageWithExif
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class CanonCamera(
    private val session: Session
): BaseCamera() {
    private var isPartialSupport = false

    // In-memory deduplication sets
    private val localRawDatabase = mutableSetOf<Int>()
    private val localExifDatabaseExist = mutableSetOf<String>()
    private val localExifDatabaseNotFound = mutableSetOf<String>()
    private var isSkipAutoUpload = true


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
            session.log.e(
                TAG,
                "execute: failed when get storage ids, maybe storage empty. Manufacture: ${deviceInfo.manufacture} Model: ${deviceInfo.model} "
            )
            listenerCamera?.onError(Throwable("failed when get storage ids, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }
        val storageIds = getStorageIds.getResult().getOrNull() ?: IntArray(0)

        if (storageIds.isEmpty()) {
            session.log.e(
                TAG,
                "execute: storageIds is empty. Manufacture: ${deviceInfo.manufacture} Model: ${deviceInfo.model} "
            )
            listenerCamera?.onError(Throwable("storage ids is empty, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }

        listenerCamera?.onReady()
        // Pre-check storage info before looping
        val validStorageIds = mutableListOf<Int>()
        storageIds.forEach { storage ->
            val getStorageInfoCmd = GetStorageInfoCommand(session, storage)
            session.log.d(TAG, "Checking storage $storage with GetStorageInfoCommand")
            executor.handleCommand(getStorageInfoCmd)
            when (getStorageInfoCmd.responseCode) {
                PtpConstants.Response.Ok -> {
                    session.log.d(TAG, "Executed GetStorageInfoCommand for $storage")
                    val storageInfoResult = getStorageInfoCmd.getResult()
                    session.log.d(TAG, "Result for $storage: $storageInfoResult")
                    storageInfoResult.getOrNull()?.let { info ->
                        session.log.d(
                            TAG,
                            "Storage $storage info: type=${info.storageType}, fsType=${info.filesystemType}, maxCapacity=${info.maxCapacity}, freeSpace=${info.freeSpaceInBytes}"
                        )
                        if (info.maxCapacity > 0) {
                            validStorageIds.add(storage)
                            session.log.d(
                                TAG,
                                "Storage $storage is valid with capacity ${info.maxCapacity}"
                            )
                        } else {
                            session.log.e(TAG, "Storage $storage has maxCapacity=0, skipping")
                        }
                    }
                }

                PtpConstants.Response.StoreNotAvailable -> {
                    session.log.w(TAG, "Storage Not Available id: $storage")
                }

                else -> {
                    session.log.e(TAG, "Storage: Cannot populate storage, please check the storage")
                    listenerCamera?.onError(Throwable("Cannot populate storage, please check the storage"))
                    listenerCamera?.onStop()
                    return@runBlocking
                }
            }
        }

        val objectCountByStorage = mutableMapOf<Int, Int>()
        var finishLoadStorageImage = false



        while (executor.isRunning()) {
            // ========== PHASE 1: Initial scan with BATCH FILTERING ==========
            if (!finishLoadStorageImage) {


                val allObjectInfos = mutableListOf<ObjectInfo>()

                var dedupChecked = 0
                var dedupSkipped = 0

                for (storageId in validStorageIds) {
                    val getObjectHandles = handlerCommandRetry(session, executor) {
                        GetObjectHandlesCommand(session, storageId, MtpConstants.FORMAT_EXIF_JPEG)
                    }
                    val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: IntArray(0)

                    for (handlerId in handlersFromStorage) {
                        dedupChecked++
                        if (localRawDatabase.contains(handlerId)) {
                            dedupSkipped++
                            continue
                        }

                        val getObjectInfoCommand = handlerCommandRetry(session, executor) {
                            GetObjectInfoCommand(session, handlerId)
                        }
                        val objectInfo = getObjectInfoCommand.getResult().getOrNull()
                        if (objectInfo != null) {
                            objectInfo.handlerID = handlerId
                            objectInfo.storageId = storageId
                            allObjectInfos.add(objectInfo)
                        }
                    }
                }

                // Batch filter callback (kept separate if you still want to track it)
                val filterStart = System.currentTimeMillis()
                val handlersToSkip = listenerCamera?.onHandlersFilter(allObjectInfos) ?: emptyList()

                // Mark skipped handlers
                val skipSet = handlersToSkip.map { it.handlerID }.toSet()
                localRawDatabase.addAll(skipSet)

                // Process each storage
                for (storageId in validStorageIds) {
                    val initialCountCmd = handlerCommandRetry(session, executor) {
                        GetNumObjectsCommand(session, storageId)
                    }
                    val baselineCount = if (initialCountCmd.responseCode == PtpConstants.Response.Ok) {
                        initialCountCmd.getResult().getOrDefault(0)
                    } else { 0 }
                    objectCountByStorage[storageId] = baselineCount

                    processStorageImages(executor, session, storageId, skipAutoUpload = true)
                }


                finishLoadStorageImage = true
                isSkipAutoUpload = false
                continue
            }

            // ========== PHASE 2: Event polling ==========
            val getEventCommand = EOSGetEventCommand(session)
            executor.handleCommand(getEventCommand)
            val hasEvents = getEventCommand.getResult().getOrNull() ?: false

            if (hasEvents) {
                for (storageId in validStorageIds) {
                    val getNumObjects = handlerCommandRetry(session, executor) {
                        GetNumObjectsCommand(session, storageId)
                    }
                    val currentCount = getNumObjects.getResult().getOrDefault(-1)
                    val previousCount = objectCountByStorage[storageId] ?: 0

                    if (currentCount != previousCount || currentCount == -1) {
                        objectCountByStorage[storageId] = currentCount
                        processStorageImages(executor, session, storageId, skipAutoUpload = false)
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

        val eosRequestPCModeCommand = EOSRequestPCModeCommand(session)
        worker.handleCommand(eosRequestPCModeCommand)

        getDeviceInfoCommand.getResult().onFailure {
            session.log.e(TAG, "onConnecting: failed when get device info")
        }

        return getDeviceInfoCommand.getResult().getOrNull()
    }

    private suspend fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        val getObjectInfoCommand = handlerCommandRetry(session, worker) {
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

    private suspend fun downloadImage(
        worker: WorkerExecutor,
        handler: Int,
        objectInfo: ObjectInfo
    ): ObjectImage? {
        session.log.d(TAG, "downloadImage: start download image $handler")
        val getObjectCommand = handlerCommandRetry(session, worker) {
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

    private suspend fun downloadImagePartial(
        worker: WorkerExecutor,
        handler: Int,
        objectInfo: ObjectInfo
    ): ObjectImage? {
        session.log.d(TAG, "downloadImagePartial: start download image $handler")
        var offset = 0
        val totalBytes = objectInfo.objectCompressedSize
        val imageTotalBytes = ByteBuffer.allocate(totalBytes)
        imageTotalBytes.position(0)
        val maxPacketSize = 1024 * 1024

        while (offset < totalBytes) {
            var size = totalBytes - offset
            if (size > maxPacketSize) {
                size = maxPacketSize
            }

            val finalOffset = offset
            val finalSize = size
            val partialObjectCommand = handlerCommandRetry(session, worker) {
                GetObjectPartial(session, handler, finalOffset, finalSize)
            }
            session.log.d(TAG, "downloadImagePartial: consume command")

            partialObjectCommand.getResult().onFailure {
                session.log.w(
                    TAG,
                    "downloadImagePartial: failed when download partial image $handler"
                )
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

    private suspend fun <T : Command> handlerCommandRetry(
        session: Session,
        worker: WorkerExecutor,
        getCommand: () -> T
    ): T {
        while (worker.isRunning()) {
            val command = getCommand()
            worker.handleCommand(command)
            if (!command.isRetry()) {
                return command
            }
            session.log.e(TAG, "${command::class.simpleName} request retry")
            delay(500L)
        }
        error("Worker is shutting down")
    }

    private suspend fun processStorageImages(
        executor: WorkerExecutor,
        internalSession: Session,
        storageId: Int,
        skipAutoUpload: Boolean
    ): Boolean {
        val getObjectHandles = handlerCommandRetry(internalSession, executor) {
            GetObjectHandlesCommand(internalSession, storageId, MtpConstants.FORMAT_EXIF_JPEG)
        }
        val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: return false

        // Filter stages
        val handlerCleanFromRaw = handlersFromStorage.filter { !localRawDatabase.contains(it) }

        var processed = 0
        var skippedByFormat = 0
        var skippedByDedup = 0

        for (handlerId in handlerCleanFromRaw) {

            if (localRawDatabase.contains(handlerId)) continue

            val getObjectInfoCommand = handlerCommandRetry(internalSession, executor) {
                GetObjectInfoCommand(internalSession, handlerId)
            }
            val objectInfo = getObjectInfoCommand.getResult().getOrNull() ?: continue
            val filename = objectInfo.filename?.uppercase() ?: ""

            if ((filename.endsWith(".JPEG") || filename.endsWith(".JPG")) &&
                objectInfo.objectFormat == MtpConstants.FORMAT_EXIF_JPEG) {
                if(localExifDatabaseNotFound.contains(objectInfo.getAllDataKey())) {
                    continue
                }

                val objectImage = onDownloadImage(executor, handlerId) ?: continue

                val exifData = extractExifSignaturePartial(objectImage)

                val enrichedImage = objectImage.copy(exifKey = exifData)
                val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifData)

                if(localExifDatabaseExist.contains(objectInfoWithExif.exifKey)) {
                    continue
                }

                val isExist = listenerCamera?.onIsImageAlreadyInDatabase(
                    objectInfoWithExif,
                    skipAutoUpload
                ) ?: false

                if (isExist) {
                    skippedByDedup++
                    if (exifData.isNullOrEmpty()) {
                        localExifDatabaseNotFound.add(handlerId.toString())
                    } else {
                        localExifDatabaseExist.add(exifData)
                    }
                } else {
                    processed++
                    consumeImage(enrichedImage)
                }
            } else {
                skippedByFormat++
                localRawDatabase.add(handlerId)
            }
        }

        return true
    }


    private suspend fun extractExifSignaturePartial(objectImage: ObjectImage): String? {
        return try {
            val exifKey = generateExifUniqueKeyFromBytes(objectImage)
            return exifKey

        } catch (e: Exception) {
            session.log.w(
                TAG,
                "❌ EXIF: Exception | handler=${objectImage.handlerId} | filename=${objectImage.objectInfo.filename} | error=${e.message}",
                e
            )
            null
        }
    }

    private fun generateExifUniqueKeyFromBytes(objectImage: ObjectImage): String? {
        return try {
            val objectInfo = objectImage.objectInfo
            val imageBytes = objectImage.image.bytes

            // Quick size check before EXIF parsing
            if (imageBytes.size < 128) {
                session.log.d(
                    TAG,
                    "⚠️ SKIP EXIF: File too small | handler=${objectImage.handlerId} | filename=${objectInfo.filename} | size=${imageBytes.size}"
                )
                return null
            }

            val exif = ExifInterface(ByteArrayInputStream(imageBytes))

            fun clean(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: ""

            val make = clean(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = clean(exif.getAttribute(ExifInterface.TAG_MODEL))
            val dateTimeOriginal = clean(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))

            // Subsec priority: digitized → original → empty
            val subSecDigitized = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED))
            val subSecOriginal = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
            val subSecTime = if (subSecDigitized.isNotEmpty()) subSecDigitized else subSecOriginal

            val uniqueId = clean(
                exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID)
                    ?: exif.getAttribute("ImageUniqueID")
            )

            if (model.isEmpty() || dateTimeOriginal.isEmpty()) {
                session.log.d(
                    TAG,
                    "⚠️ EXIF: Missing required fields | model=$model | dateTime=$dateTimeOriginal | filename=${objectInfo.filename}"
                )
                return null
            }

            val key = "${make}_${model}_${dateTimeOriginal}_${subSecTime}_${uniqueId}"
            return key

        } catch (e: Exception) {
            session.log.w(
                TAG,
                "❌ EXIF: Parse failed | filename=${objectImage.objectInfo.filename} | error=${e.message}",
                e
            )
            null
        }
    }

    //Consume Image logic: dedup check then callback
    private suspend fun consumeImage(objectImage: ObjectImage) {
        val filename = objectImage.objectInfo.filename ?: "unknown"
        val exifData = objectImage.exifKey

        if (exifData.isNullOrEmpty()) {
            if (!localExifDatabaseNotFound.contains(objectImage.objectInfo.getAllDataKey())) {
                localExifDatabaseNotFound.add(objectImage.objectInfo.getAllDataKey())
                listenerCamera?.onImageDownloaded(objectImage)
            } else {
            }
        } else {
            if (!localExifDatabaseExist.contains(exifData)) {
                localExifDatabaseExist.add(exifData)
                localRawDatabase.add(objectImage.handlerId)
                listenerCamera?.onImageDownloaded(objectImage)
            } else {
            }
        }
    }

    companion object {
        private const val TAG = "CanonCamera"
    }
}