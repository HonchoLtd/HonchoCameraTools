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
import kotlin.math.min

class CanonCamera(
    private val session: Session
): BaseCamera() {
    private var isPartialSupport = false

    // In-memory deduplication sets
    private val localRawDatabase = mutableSetOf<Int>()
    private val processedHandlerIds = mutableSetOf<Int>()
    private val localExifDatabaseExist = mutableSetOf<String>()
    private val localExifDatabaseNotFound = mutableSetOf<String>()
    private var isSkipAutoUpload = true
    private var finishLoadStorageImage = false

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

        val currentTotalImageByStorage = mutableMapOf<Int, Int>()
        while (executor.isRunning()) {
            // ─────────────────────────────────────
            // PHASE 1: Initial scan of all storages
            // ─────────────────────────────────────
            if (!finishLoadStorageImage) {
                for (storage in storageIds) {
                    processStorageHandlers(executor, storage, deviceInfo)
                }
                finishLoadStorageImage = true
                isSkipAutoUpload = false
            }

            // ─────────────────────────────────────
            // PHASE 2: Event-based polling (Canon EOS)
            // ─────────────────────────────────────
            pollForNewEvents(executor, storageIds.toList(), deviceInfo, currentTotalImageByStorage)
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

    private fun generateExifUniqueKeyFromBytes(partialBytes: ByteArray, objectInfo: ObjectInfo): String? {
        return try {
            if (partialBytes.size < 128) return null
            val exif = ExifInterface(ByteArrayInputStream(partialBytes))

            fun clean(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: ""

            val make = clean(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = clean(exif.getAttribute(ExifInterface.TAG_MODEL))
            val dateTime = clean(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            val subSec = clean(
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED)
                    ?: exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
            )
            val uniqueId = clean(
                exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID)
                    ?: exif.getAttribute("ImageUniqueID")
            )

            if (model.isEmpty() || dateTime.isEmpty()) return null
            "${make}_${model}_${dateTime}_${subSec}_${uniqueId}"
        } catch (e: Exception) {
            session.log.w(TAG, "EXIF parse failed", e)
            null
        }
    }

    // Extracted: Extract EXIF key using partial fetch
    private suspend fun extractExifKeyFromImage(
        executor: WorkerExecutor,
        handler: Int,
        objectInfo: ObjectInfo
    ): String? {
        return try {
            if (objectInfo.objectCompressedSize < 128) return null
            val fetchSize = min(64 * 1024, objectInfo.objectCompressedSize)
            val partialCmd = handlerCommandRetry(session, executor) {
                GetObjectPartial(session, handler, 0, fetchSize)
            }
            val partialBytes = partialCmd.getResult().getOrNull() ?: return null
            generateExifUniqueKeyFromBytes(partialBytes, objectInfo)
        } catch (e: Exception) {
            session.log.w(TAG, "EXIF extraction failed", e)
            null
        }
    }

    // Extracted: Poll for new events (Canon-specific with GetNumObjects)
    private suspend fun pollForNewEvents(
        executor: WorkerExecutor,
        storageIds: List<Int>,
        deviceInfo: DeviceInfo,
        currentTotalImageByStorage: MutableMap<Int, Int>
    ) {
        // Canon EOS event polling (may not work reliably for all Canon models)
        val getEventCommand = EOSGetEventCommand(session)
        executor.handleCommand(getEventCommand)

        // Canon-specific: Use GetNumObjects to detect new images
        for (storage in storageIds) {
            val getNumObjectsCommand = handlerCommandRetry(session, executor) {
                GetNumObjectsCommand(session, storage)
            }
            val latestTotalImages = getNumObjectsCommand.getResult().getOrDefault(0)
            val previousCount = currentTotalImageByStorage[storage] ?: 0

            // ✅ Process if count increased OR if this is first check
            if (latestTotalImages > previousCount || previousCount == 0) {
                currentTotalImageByStorage[storage] = latestTotalImages
                // ✅ Re-use PHASE 1 logic for new handlers (matches pseudo-code)
                processStorageHandlers(executor, storage, deviceInfo)
            }
        }
    }

    // Extracted: Process a single handler
    private suspend fun processHandler(
        executor: WorkerExecutor,
        handlerId: Int,
        deviceInfo: DeviceInfo
    ) {
        val getObjectInfo = handlerCommandRetry(session, executor) {
            GetObjectInfoCommand(session, handlerId)
        }
        val objectInfo = getObjectInfo.getResult().getOrNull() ?: run {
            // ✅ Mark as processed even on GetObjectInfo failure to avoid retry spam
            processedHandlerIds.add(handlerId)
            return
        }

        val name = objectInfo.filename?.uppercase() ?: ""

        // ✅ Skip non-JPEG silently but mark as processed
        if (!(name.endsWith(".JPG") || name.endsWith(".JPEG")) ||
            objectInfo.objectFormat != MtpConstants.FORMAT_EXIF_JPEG) {
            processedHandlerIds.add(handlerId)  // ✅ Never re-check this handler
            return
        }

        // ✅ Download image
        val objectImage = onDownloadImage(executor, handlerId) ?: run {
            // ✅ Mark failed downloads to avoid infinite retry loop
            processedHandlerIds.add(handlerId)
            return
        }

        // ✅ Extract EXIF key
        val exifKey = extractExifKeyFromImage(executor, handlerId, objectInfo)

        // ✅ Build wrapper and let app decide if it exists
        val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifKey)
        val isExist = listenerCamera?.onIsImageAlreadyInDatabase(
            objectInfoWithExif,
            isSkipAutoUpload
        ) ?: false

        // ✅ Route through consumeImage for dedup + marking
        if (isExist) {
            // App says it exists → just track it without downloading again
            consumeImageAlreadyTracked(handlerId, exifKey)
        } else {
            // App says it's new → download + track
            consumeImage(objectImage, exifKey)
        }
    }

    private suspend fun processStorageHandlers(
        executor: WorkerExecutor,
        storage: Int,
        deviceInfo: DeviceInfo
    ) {
        val getHandlersCommand = handlerCommandRetry(session, executor) {
            GetObjectHandlesCommand(session, storage, MtpConstants.FORMAT_EXIF_JPEG)
        }
        val handlersFromStorage = getHandlersCommand.getResult().getOrNull() ?: IntArray(0)

        val handlerCleanFromRaw = handlersFromStorage.filterNot { localRawDatabase.contains(it) }

        for (handlerId in handlerCleanFromRaw) {
            // ✅ NEW: Skip if already fully processed (prevents re-download)
            if (processedHandlerIds.contains(handlerId)) {
                session.log.d(TAG, "⏭️ Skip handler $handlerId: already processed")
                continue
            }

            // ✅ Quick skip: if already tracked as "no EXIF" by handlerId
            if (localExifDatabaseNotFound.contains(handlerId.toString())) {
                processedHandlerIds.add(handlerId)  // ✅ Mark as processed to avoid re-check
                continue
            }

            // ✅ Process this handler
            processHandler(executor, handlerId, deviceInfo)
        }
    }

    private fun consumeImageAlreadyTracked(handlerId: Int, exifKey: String?) {
        if (exifKey.isNullOrEmpty()) {
            localExifDatabaseNotFound.add(handlerId.toString())
        } else {
            localExifDatabaseExist.add(exifKey)
        }
        // ✅ Critical: Mark as processed to skip future polls
        processedHandlerIds.add(handlerId)
        session.log.d(TAG, "✅ Tracked existing handler $handlerId (exifKey=${exifKey?.take(30) ?: "null"})")
    }

    // Update consumeImage to mark handler as processed:
    private suspend fun consumeImage(objectImage: ObjectImage, exifData: String?) {
        // ✅ Library-side deduplication before calling app callback
        if (exifData.isNullOrEmpty()) {
            val fallbackKey = objectImage.handlerId.toString()
            if (localExifDatabaseNotFound.contains(fallbackKey)) {
                session.log.d(TAG, "consumeImage: skip duplicate (no EXIF) handler=${objectImage.handlerId}")
                return
            }
            localExifDatabaseNotFound.add(fallbackKey)
        } else {
            if (localExifDatabaseExist.contains(exifData)) {
                session.log.d(TAG, "consumeImage: skip duplicate (with EXIF) key=${exifData.take(50)}")
                return
            }
            localExifDatabaseExist.add(exifData)
        }

        // ✅ Pass to app layer for saving
        listenerCamera?.onImageDownloaded(objectImage)

        // ✅ NEW: Mark handler as fully processed (prevents re-download on next poll)
        processedHandlerIds.add(objectImage.handlerId)
        session.log.d(TAG, "✅ Marked handler ${objectImage.handlerId} as processed")
    }

    companion object {
        private const val TAG = "CanonCamera"
    }
}