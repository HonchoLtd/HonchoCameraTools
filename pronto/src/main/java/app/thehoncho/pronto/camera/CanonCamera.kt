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
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.min
import app.thehoncho.pronto.model.CachedImageEntry

class CanonCamera(
    private val session: Session
): BaseCamera() {
    private var isPartialSupport = false

    // OLD:
    // private var cacheImage = mutableMapOf<Int, ObjectInfo>()

    // Volatile session exclusion list for O(1) duplicate skip
    private var sessionExclusionList = mutableSetOf<String>() // Format: "handlerId_parentId"
    private var cacheImage = mutableMapOf<String, CachedImageEntry>() // Key = compositeKey
//    // Cache: composite key → ObjectInfo (metadata only, no image bytes)
//    private var cacheImage = mutableMapOf<String, ObjectInfo>()
//
//    // Reverse lookup: handlerID → composite key (for download phase)
//    private var handlerToCompositeKey = mutableMapOf<Int, String>()

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

        sessionExclusionList.clear()
        session.log.d(TAG, "Session exclusion list reset for new camera connection")

        val currentTotalImageByStorage = mutableMapOf<Int, Int>()
        while (executor.isRunning()) {
            session.log.d(TAG, "execute: get handlers with total ${storageIds.size} storage")
            val getEventCommand = EOSGetEventCommand(session)
            executor.handleCommand(getEventCommand)
            session.log.d(TAG, "execute: flush")
            validStorageIds.forEach { storage ->
                session.log.d(TAG, "execute: get handlers with storage $storage")
                val getNumObjectsCommand = handlerCommandRetry(session, executor) {
                    GetNumObjectsCommand(session, storage)
                }
                val latestTotalImages = getNumObjectsCommand.getResult().getOrDefault(0)
                session.log.d(TAG, "execute: Total objects: $latestTotalImages ")

                session.log.d(
                    TAG,
                    "execute: Total current=${currentTotalImageByStorage[storage]} latest=$latestTotalImages"
                )

                if (latestTotalImages > 0 && currentTotalImageByStorage[storage] == latestTotalImages) {
                    session.log.d(
                        TAG,
                        "execute: counts match for storage $storage, skipping handler query"
                    )
                    currentTotalImageByStorage[storage] = latestTotalImages
                    return@forEach
                } else {
                    session.log.d(
                        TAG,
                        "execute: counts differ for storage $storage, updating and fetching handlers"
                    )
                    currentTotalImageByStorage[storage] = latestTotalImages
                }

                val getHandlersCommand = handlerCommandRetry(session, executor) {
                    GetObjectHandlesCommand(session, storage, MtpConstants.FORMAT_EXIF_JPEG)
                }

                val handlers = getHandlersCommand.getResult().getOrNull() ?: IntArray(0)
                session.log.d(
                    TAG,
                    "execute: get handlers with total ${handlers.size} handlers on storage $storage"
                )

                val handlerNotCache = handlers.filterNot { handlerId ->
                    cacheImage.values.any {
                        it.handlerId == handlerId
                    }
                }

                session.log.d(
                    TAG,
                    "execute: get handlers with total ${handlerNotCache.size} handlers not cache on storage $storage"
                )
                handlerNotCache.forEach { handler ->
                    val getObjectInfo = handlerCommandRetry(session, executor) {
                        GetObjectInfoCommand(session, handler)
                    }
                    getObjectInfo.getResult().onFailure {
                        session.log.e(TAG, "execute: failed when get object info, please restart the camera. Manufacture: ${deviceInfo.manufacture} Model: ${deviceInfo.model} ")
                        listenerCamera?.onError(Throwable("failed when get object info $handler, please restart the camera"))
                        listenerCamera?.onStop()
                        return@runBlocking
                    }
                    getObjectInfo.getResult().getOrNull()?.let { info ->
                        val name = info.filename?.uppercase() ?: ""

                        if (info.objectFormat == MtpConstants.FORMAT_EXIF_JPEG &&
                            (name.endsWith(".JPG") || name.endsWith(".JPEG"))
                        ) {
                            // ─────────────────────────────────────
                            // PHASE 1: Scan & Deduplicate (NO full download)
                            // ─────────────────────────────────────

                            // Check exclusion list BEFORE fetching partial bytes
                            val exclusionKey = "${handler}::${info.parentObject}"
                            if (exclusionKey in sessionExclusionList) {
                                session.log.d(TAG, "Handler $handler already processed this session; skipping EXIF fetch")
                                return@let // Skip to next handler
                            }

                            // 1. Fetch only partial bytes for EXIF parsing
                            val partialBytes = fetchPartialBytesForExif(executor, handler, info)

                            // 2. Generate EXIF-based unique key from partial data
                            val exifKey = if (partialBytes != null) {
                                generateExifUniqueKeyFromBytes(partialBytes, info)
                            } else {
                                null // Fallback if partial fetch fails
                            }

                            // 3. Build composite unique key
                            val compositeKey = generateCompositeUniqueKey(info, exifKey)

                            // 4. Check if already cached (duplicate detection)
                            if (cacheImage.containsKey(compositeKey)) {
                                session.log.d(
                                    TAG,
                                    "Skipping duplicate image with composite key: $compositeKey"
                                )
                                // Mark as processed in exclusion list even if duplicate
                                sessionExclusionList.add(exclusionKey)
                                return@let // ❌ Skip: already cached, don't add again
                            }

                            // 5. ✅ Unique: Add to cache (metadata only, no image bytes yet)
                            val entry = CachedImageEntry(info, compositeKey, info.handlerID)
                            cacheImage[compositeKey] = entry

                            session.log.d(
                                TAG,
                                "Cached unique image: $name with key: $compositeKey, handler: ${info.handlerID}"
                            )

                            // Mark as processed in exclusion list after successful caching
                            sessionExclusionList.add(exclusionKey)
                        } else {
                            session.log.d(TAG, "Skipping non-JPEG object: $name")
                        }
                    }
                }
            }

            val filteredEntries = listenerCamera?.onHandlersFilter(cacheImage.values.toList()) ?: listOf()
            val approvedCompositeKeys = filteredEntries.map { it.compositeKey }.toSet() // ← Key change!

            session.log.d(
                TAG,
                "Download phase: ${approvedCompositeKeys.size} approved entries, ${cacheImage.size} cached"
            )

            // Download only entries that were approved
            cacheImage.values.forEach { entry ->
                // 🔑 Check by compositeKey
                if (entry.compositeKey in approvedCompositeKeys) {
                    val getEventCommand = EOSGetEventCommand(session)
                    executor.handleCommand(getEventCommand)
                    session.log.d(TAG, "execute getCleanHandlers: flush before download image")

                    val objectImage = onDownloadImage(executor, entry.handlerId)

                    // ✅ Attach CachedImageEntry to ObjectImage
                    val enrichedImage = objectImage?.copy(cachedEntry = entry)
                    // ✅ Check enrichedImage directly (cleaner flow)
                    if (enrichedImage == null) {
                        session.log.e(
                            TAG,
                            "execute: failed when download image ${entry.objectInfo.filename}"
                        )
                        listenerCamera?.onError(Throwable("failed when download image ${entry.objectInfo.filename}"))
                        listenerCamera?.onStop()
                        return@runBlocking
                    }

                    // ✅ Pass enriched image to listener
                    listenerCamera?.onImageDownloaded(enrichedImage)
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

    private suspend fun fetchPartialBytesForExif(
        worker: WorkerExecutor,
        handler: Int,
        objectInfo: ObjectInfo
    ): ByteArray? {
        return try {
            // ➕ ADDED: Minimum size check before EXIF parsing attempt
            if (objectInfo.objectCompressedSize < 128) {
                session.log.w(TAG, "File too small for EXIF: handler=$handler, size=${objectInfo.objectCompressedSize}")
                return null
            }

            val fetchSize = min(64 * 1024, objectInfo.objectCompressedSize)
            val partialCmd = handlerCommandRetry(session, worker) {
                GetObjectPartial(session, handler, 0, fetchSize)
            }
            partialCmd.getResult().getOrNull()
        } catch (e: Exception) {
            session.log.w(TAG, "Failed to fetch partial bytes for EXIF: handler=$handler", e)
            null
        }
    }


    private fun generateCompositeUniqueKey(objectInfo: ObjectInfo, exifUniqueKey: String?): String {
        val safeExifKey = exifUniqueKey ?: run {
            session.log.w(
                TAG,
                "EXIF extraction failed for handler ${objectInfo.handlerID}; using fallback key (filename/size/date). Risk: false unique."
            )
            // Use unsigned hex for fallback too
            "${objectInfo.filename}_${objectInfo.objectCompressedSize}_${objectInfo.captureDate}"
                .hashCode().toUnsignedHex()
        }
        // Use '::' delimiter to avoid collision with hash content
        return "${objectInfo.storageId}::${objectInfo.parentObject}::$safeExifKey"
    }

    private fun generateExifUniqueKeyFromBytes(partialBytes: ByteArray, objectInfo: ObjectInfo): String? {
        return try {
            // Minimum byte check before parsing
            if (partialBytes.size < 128) {
                session.log.w(TAG, "Partial bytes too small for EXIF parsing: ${partialBytes.size}")
                return null
            }

            val exif = ExifInterface(ByteArrayInputStream(partialBytes))

            val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                ?: ""

            // 👇 ADD THIS: Sub-second precision for burst shots
            val subSec = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
                ?: exif.getAttribute("SubSecTimeOriginal")
                ?: exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED)
                ?: exif.getAttribute("SubSecTimeDigitized")
                ?: ""

            val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
            val uniqueId = exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID)
                ?: exif.getAttribute("ImageUniqueID")
                ?: ""

            // Deterministic hash with sub-second + unique ID
            val rawKey = "$dateTime-$subSec-$uniqueId-$make-$model-${objectInfo.objectCompressedSize}"
            // Use unsigned hex to avoid negative hash values
            rawKey.hashCode().toUnsignedHex()
        } catch (e: Exception) {
            session.log.w(TAG, "EXIF parse failed for handler ${objectInfo.handlerID}", e)
            null // → Triggers fail-safe fallback
        }
    }

    private fun Int.toUnsignedHex(): String = this.toUInt().toString(16)

    companion object {
        private const val TAG = "CanonCamera"
    }
}