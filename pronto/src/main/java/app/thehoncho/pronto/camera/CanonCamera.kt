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
import app.thehoncho.pronto.command.general.GetDirObjectInfoListCommand
import app.thehoncho.pronto.command.general.GetNumObjectsCommand
import app.thehoncho.pronto.command.general.GetObjectCommand
import app.thehoncho.pronto.command.general.GetObjectHandlesCommand
import app.thehoncho.pronto.command.general.GetObjectInfoCommand
import app.thehoncho.pronto.command.general.GetObjectPartial
import app.thehoncho.pronto.command.general.GetPartialObject64Command
import app.thehoncho.pronto.command.general.GetStorageIdsCommand
import app.thehoncho.pronto.command.general.GetStorageInfoCommand
import app.thehoncho.pronto.command.general.OpenSessionCommand
import app.thehoncho.pronto.command.general.SetExtendedEventInfoCommand
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ImageObject
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectImageWithExif
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.model.ObjectInfoR1
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CanonCamera(private val session: Session) : BaseCamera() {

    private sealed class CameraModel {
        object Normal : CameraModel()
        object R1 : CameraModel()
        object Legacy : CameraModel()
    }

    private var cameraModel: CameraModel = CameraModel.Legacy
    private var isR1Camera: Boolean = false
    private var isExtendedEventEnabled: Boolean = false

    private val localRawDatabase = mutableSetOf<Int>()
    private val localExifDatabaseExist = mutableSetOf<String>()
    private val localExifDatabaseNotFound = mutableSetOf<String>()
    private var isSkipAutoUpload = true
    private var hasUnfinishedFiles = false

    override fun execute(executor: WorkerExecutor) = runBlocking {
        val deviceInfo = onConnecting(executor)
        if (deviceInfo == null) {
            session.log.e(TAG, "❌ [EXECUTE] CanonCamera Failed to connect!")
            listenerCamera?.onDeviceFailedToConnect(Throwable("failed when get device info"))
            listenerCamera?.onStop()
            return@runBlocking
        }
        listenerCamera?.onDeviceConnected(deviceInfo)

        detectCameraModel(deviceInfo)

        if (isR1Camera) {
            val setExtendedEventCmd = SetExtendedEventInfoCommand(session, mode = 1)
            executor.handleCommand(setExtendedEventCmd)
            setExtendedEventCmd.getResult().onSuccess {
                isExtendedEventEnabled = true
                session.log.d(TAG, "CanonCamera 🔍 [R1 INIT] ✅ Extended events enabled successfully.")
            }.onFailure {
                session.log.w(TAG, "CanonCamera ⚠️ [R1 INIT] Failed to enable extended events. Will use standard polling.")
            }
        }

        val getStorageIds = GetStorageIdsCommand(session)
        executor.handleCommand(getStorageIds)
        val storageIds = getStorageIds.getResult().getOrNull() ?: IntArray(0)

        if (storageIds.isEmpty()) {
            listenerCamera?.onError(Throwable("storage ids is empty"))
            listenerCamera?.onStop()
            return@runBlocking
        }

        listenerCamera?.onReady()

        val validStorageIds = mutableListOf<Int>()
        storageIds.forEach { storage ->
            val getStorageInfoCmd = GetStorageInfoCommand(session, storage)
            executor.handleCommand(getStorageInfoCmd)
            if (getStorageInfoCmd.responseCode == PtpConstants.Response.Ok) {
                getStorageInfoCmd.getResult().getOrNull()?.let { info ->
                    if (info.maxCapacity > 0) validStorageIds.add(storage)
                }
            }
        }

        val objectCountByStorage = mutableMapOf<Int, Int>()
        var finishLoadStorageImage = false

        while (executor.isRunning()) {
            if (!finishLoadStorageImage) {
                val allObjectInfos = mutableListOf<ObjectInfo>()

                for (storageId in validStorageIds) {
                    if (isR1Camera && isExtendedEventEnabled) {
                        // 🔄 Use the new recursive crawler to get ALL files from ALL folders
                        val dirList = crawlStorage(executor, session, storageId)

                        if (dirList.isNotEmpty()) {
                            for (objR1 in dirList) {
                                if (localRawDatabase.contains(objR1.handlerID)) continue
                                val objectInfo = objR1.toObjectInfo()
                                allObjectInfos.add(objectInfo)
                            }
                        } else {
                            val getObjectHandles = handlerCommandRetry(session, executor) { GetObjectHandlesCommand(session, storageId, 0) }
                            val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: intArrayOf()
                            for (handlerId in handlersFromStorage) {
                                if (localRawDatabase.contains(handlerId)) continue
                                val getObjectInfoCommand = handlerCommandRetry(session, executor) { GetObjectInfoCommand(session, handlerId) }
                                val objectInfo = getObjectInfoCommand.getResult().getOrNull()
                                if (objectInfo != null) {
                                    objectInfo.handlerID = handlerId
                                    objectInfo.storageId = storageId
                                    allObjectInfos.add(objectInfo)
                                }
                            }
                        }
                    } else {
                        val getObjectHandles = handlerCommandRetry(session, executor) { GetObjectHandlesCommand(session, storageId, MtpConstants.FORMAT_EXIF_JPEG) }
                        val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: intArrayOf()
                        for (handlerId in handlersFromStorage) {
                            if (localRawDatabase.contains(handlerId)) continue
                            val getObjectInfoCommand = handlerCommandRetry(session, executor) { GetObjectInfoCommand(session, handlerId) }
                            val objectInfo = getObjectInfoCommand.getResult().getOrNull()
                            if (objectInfo != null) {
                                objectInfo.handlerID = handlerId
                                objectInfo.storageId = storageId
                                allObjectInfos.add(objectInfo)
                            }
                        }
                    }
                }

                val handlersToSkip = listenerCamera?.onHandlersFilter(allObjectInfos) ?: emptyList()
                localRawDatabase.addAll(handlersToSkip.map { it.handlerID })

                for (storageId in validStorageIds) {
                    val initialCountCmd = handlerCommandRetry(session, executor) { GetNumObjectsCommand(session, storageId) }
                    val baselineCount = if (initialCountCmd.responseCode == PtpConstants.Response.Ok) initialCountCmd.getResult().getOrDefault(0) else 0
                    objectCountByStorage[storageId] = baselineCount
                    processStorageImages(executor, session, storageId, skipAutoUpload = true, isR1Camera = isR1Camera)
                }

                finishLoadStorageImage = true
                isSkipAutoUpload = false
                continue
            }

            val getEventCommand = EOSGetEventCommand(session)
            executor.handleCommand(getEventCommand)

            val responseCode = getEventCommand.responseCode
            val result = getEventCommand.getResult()
            val hasEvents = result.getOrNull() ?: false

            // 🔍 LOG EVENT POLLING RESULT
            if (isR1Camera) {
                session.log.d(TAG, "CanonCamera 🔍 [R1 EVENT] Poll result: hasEvents=$hasEvents, responseCode=0x${responseCode.toString(16)}")
            }

            // 🚨 FIX 1: Trigger processing if we have events OR if we are waiting for a 0-byte file to finish writing
            if (hasEvents) {
                for (storageId in validStorageIds) {
                    if (isR1Camera) {
                        processStorageImages(executor, session, storageId, skipAutoUpload = false, isR1Camera = true)
                    } else {
                        // Normal cameras still use GetNumObjects
                        val cmd = handlerCommandRetry(session, executor) { GetNumObjectsCommand(session, storageId) }
                        val numResult = cmd.getResult()

                        if (numResult.isSuccess) {
                            val currentCount = numResult.getOrDefault(0)
                            val previousCount = objectCountByStorage[storageId] ?: 0

                            if (currentCount != previousCount) {
                                objectCountByStorage[storageId] = currentCount
                                processStorageImages(executor, session, storageId, skipAutoUpload = false, isR1Camera = false)
                            } else {
                                session.log.d(TAG, "CanonCamera 🔍 [EVENT] Count unchanged ($currentCount). Ignoring redundant event.")
                            }
                        } else {
                            session.log.w(TAG, "CanonCamera ⚠️ [EVENT] GetNumObjects failed. Falling back to directory read...")
                            processStorageImages(executor, session, storageId, skipAutoUpload = false, isR1Camera = false)
                        }
                    }
                }
            }

            if (result.isFailure || !hasEvents) delay(100L)

        }
        listenerCamera?.onStop()
    }

    private fun detectCameraModel(deviceInfo: DeviceInfo) {
        val modelName = deviceInfo.model?.trim() ?: ""

        if (modelName.equals("Canon EOS R1", ignoreCase = true) ||
            modelName.equals("R1", ignoreCase = true) ||
            modelName.contains("EOS R1", ignoreCase = true)) {
            cameraModel = CameraModel.R1
            isR1Camera = true
            return
        }

        var has64Bit = false
        var has32Bit = false
        deviceInfo.operationsSupported?.forEach { opt ->
            when (opt.toShort()) {
                PtpConstants.Operation.GetPartialObject64.toShort() -> has64Bit = true
                PtpConstants.Operation.GetPartialObject.toShort() -> has32Bit = true
            }
        }

        if (has64Bit) {
            cameraModel = CameraModel.Normal
            isR1Camera = false
        } else if (has32Bit) {
            cameraModel = CameraModel.Normal
            isR1Camera = false
        } else {
            cameraModel = CameraModel.Legacy
            isR1Camera = false
        }
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
            session.log.e(TAG, "CanonCamera onConnecting: failed when get device info")
        }
        return getDeviceInfoCommand.getResult().getOrNull()
    }

    private suspend fun onDownloadImageR1(worker: WorkerExecutor, objR1: ObjectInfoR1): ObjectImage? {
        return downloadImagePartial64R1(worker, objR1)
    }

    private suspend fun downloadImagePartial64R1(worker: WorkerExecutor, objR1: ObjectInfoR1): ObjectImage? {
        session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Starting download for handler 0x${objR1.handlerID.toString(16)} | File: ${objR1.filename} | Size: ${objR1.objectSize64}")
        var offset: Long = 0L
        val totalBytes = objR1.objectSize64

        if (totalBytes <= 0) {
            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Total bytes is 0 or negative! Aborting.")
            return null
        }

        val imageBuffer = ByteArrayOutputStream()
        val maxChunkSize = 2 * 1024 * 1024L
        var chunkIndex = 0

        while (offset < totalBytes && worker.isRunning()) {
            chunkIndex++
            val remaining = totalBytes - offset
            val chunkSize = remaining.coerceAtMost(maxChunkSize).toInt()
            session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] --- Chunk #$chunkIndex --- | Offset: $offset | Requested Size: $chunkSize")

            val partialCmd = handlerCommandRetry(session, worker) { GetPartialObject64Command(session, objR1.handlerID, offset, chunkSize) }
            val result = partialCmd.getResult()
            val chunk = result.getOrNull()

            if (chunk == null) {
                session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Chunk #$chunkIndex returned NULL at offset $offset. Error: ${result.exceptionOrNull()?.message}")
                return null
            }

            imageBuffer.write(chunk)
            offset += chunkSize
            session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Chunk #$chunkIndex success | Received: ${chunk.size} bytes | Total accumulated: ${imageBuffer.size()} / $totalBytes")
        }

        val imageBytes = imageBuffer.toByteArray()
        session.log.d(TAG, "CanonCamera ✅ [R1 DOWNLOAD] Download complete for ${objR1.filename}! Total bytes: ${imageBytes.size}")

        if (imageBytes.isEmpty()) {
            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Downloaded file is empty!")
            return null
        }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
        if (bitmap == null) {
            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Failed to decode bitmap for ${objR1.filename}! Data might be corrupted.")
        }

        val objectInfo = objR1.toObjectInfo()
        return ObjectImage(objectInfo, objR1.handlerID, ImageObject(imageBytes, bitmap))
    }

    private suspend fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        val getObjectInfoCommand = handlerCommandRetry(session, worker) {
            GetObjectInfoCommand(session, handler)
        }

        getObjectInfoCommand.getResult().onFailure {
            session.log.w(TAG, "CanonCamera onDownloadImage: failed when download info image $handler")
        }

        val objectInfo = getObjectInfoCommand.getResult().getOrNull()
        if (objectInfo == null) {
            session.log.e(TAG, "CanonCamera onDownloadImage: failed when download info image")
            return null
        }

        return if (cameraModel != CameraModel.Legacy) {
            downloadImagePartial(worker, handler, objectInfo)
        } else {
            downloadImage(worker, handler, objectInfo)
        }
    }

    private suspend fun downloadImage(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        val getObjectCommand = handlerCommandRetry(session, worker) { GetObjectCommand(session, handler) }
        val imageObject = getObjectCommand.getResult().getOrNull() ?: return null
        return ObjectImage(objectInfo, handler, imageObject)
    }

    private suspend fun downloadImagePartial(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        var offset = 0
        val totalBytes = objectInfo.objectCompressedSize
        val imageTotalBytes = ByteBuffer.allocate(totalBytes)
        val maxPacketSize = 1024 * 1024

        while (offset < totalBytes) {
            var size = totalBytes - offset
            if (size > maxPacketSize) size = maxPacketSize
            val partialObjectCommand = handlerCommandRetry(session, worker) { GetObjectPartial(session, handler, offset, size) }
            if (partialObjectCommand.getResult().getOrNull() == null) return null
            val imageBytes = partialObjectCommand.getResult().getOrNull()?.clone() ?: return null
            imageTotalBytes.put(imageBytes, 0, imageBytes.size)
            offset += size
            if (!worker.isRunning()) return null
        }

        val imageBytes = imageTotalBytes.array()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
        return ObjectImage(objectInfo, handler, ImageObject(imageBytes, bitmap))
    }

    /**
     * Recursively crawls through the Storage Root and all its subfolders (DCIM, 100CANON, etc.)
     * to gather all JPEG files into a single flat list.
     */
    private suspend fun crawlStorage(
        executor: WorkerExecutor,
        session: Session,
        storageId: Int
    ): List<ObjectInfoR1> {
        val allObjects = mutableListOf<ObjectInfoR1>()

        data class CrawlFolder(val handle: Int, val name: String, val parentHandle: Int)
        val foldersToProcess = ArrayDeque<CrawlFolder>()
        val visitedFolders = mutableSetOf<Int>()
        val addedObjectIds = mutableSetOf<Int>() // Prevent duplicates if camera returns overlapping data

        // 🚨 Start at -1 (0xFFFFFFFF), which is the standard PTP root handle.
        val rootHandle = -1
        foldersToProcess.add(CrawlFolder(rootHandle, "Storage Root", 0))
        visitedFolders.add(rootHandle)
        while (foldersToProcess.isNotEmpty() && executor.isRunning()) {
            val currentFolder = foldersToProcess.removeFirst()

            // Because parentHandle and formatFilter default to -1, we don't need to pass them!
            val dirListCmd = GetDirObjectInfoListCommand(session, storageId, currentFolder.handle)
            executor.handleCommand(dirListCmd)
            val dirResult = dirListCmd.getResult()
            val dirList = dirResult.getOrNull() ?: continue

            if (dirList.isEmpty()) {
                session.log.w(TAG, "CanonCamera 🔍 [R1 CRAWL] ⚠️ Folder 0x${currentFolder.handle.toString(16)} returned 0 items.")
                continue
            }

            for (obj in dirList) {
                // 1. Check if it's a Folder (Format Code 0x3001)
                if (obj.objectFormat == MtpConstants.FORMAT_ASSOCIATION) {
                    val folderName = obj.filename ?: "UnknownFolder"

                    // Add to queue to be crawled on the next loop
                    if (!visitedFolders.contains(obj.handlerID)) {
                        visitedFolders.add(obj.handlerID)
                        foldersToProcess.add(CrawlFolder(obj.handlerID, folderName, obj.parentObject))
                    }
                }
                // 2. It's a file. Check if it's a JPEG!
                else {
                    val name = obj.filename?.uppercase() ?: ""
                    val isJpeg = name.endsWith(".JPG") ||
                            name.endsWith(".JPEG") ||
                            obj.objectFormat == MtpConstants.FORMAT_EXIF_JPEG

                    if (isJpeg) {
                        if (!addedObjectIds.contains(obj.handlerID)) {
                            addedObjectIds.add(obj.handlerID)
                            allObjects.add(obj)
                        }
                    } else {
                        session.log.w(TAG, "CanonCamera 🔍 [R1 CRAWL] ⏭️ Ignoring non-JPEG file: '${obj.filename}'")
                    }
                }
            }
        }

        return allObjects
    }

    private suspend fun processStorageImages(
        executor: WorkerExecutor, internalSession: Session, storageId: Int,
        skipAutoUpload: Boolean, isR1Camera: Boolean = false
    ): Boolean {
        if (isR1Camera && isExtendedEventEnabled) {
            // 🔄 Use the new recursive crawler to get ALL files from ALL folders
            val dirList = crawlStorage(executor, internalSession, storageId)

            if (dirList.isNotEmpty()) {
                val newObjects = dirList.filter { !localRawDatabase.contains(it.handlerID) }

                if (newObjects.isEmpty()) {
                    return true
                }

                for (objR1 in newObjects) {
                    val filename = objR1.filename?.uppercase() ?: ""

                    if (objR1.objectSize64 == 0L) {
                        session.log.w(TAG, "CanonCamera ⚠️ [R1 PROCESS] File $filename has size 0. Camera is still writing. Waiting for next loop...")
                        // DO NOT add to localRawDatabase! We will catch it on the next loop when size > 0.
                        continue
                    }

                    if ((filename.endsWith(".JPEG") || filename.endsWith(".JPG")) && objR1.objectFormat == MtpConstants.FORMAT_EXIF_JPEG) {
                        val objectInfo = objR1.toObjectInfo()
                        val dedupKey = objectInfo.getAllDataKey()
                        if (localExifDatabaseNotFound.contains(dedupKey)) {
                            localRawDatabase.add(objR1.handlerID)
                            continue
                        }

                        val objectImage = onDownloadImageR1(executor, objR1) ?: continue

                        val exifData = extractExifSignaturePartial(objectImage)
                        val enrichedImage = objectImage.copy(exifKey = exifData)
                        val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifData)

                        if (exifData != null && exifData.isNotEmpty() && localExifDatabaseExist.contains(exifData)) {
                            localRawDatabase.add(objR1.handlerID)
                            continue
                        }

                        val isExist = listenerCamera?.onIsImageAlreadyInDatabase(objectInfoWithExif, skipAutoUpload) ?: false
                        if (isExist) {
                            if (exifData.isNullOrEmpty()) localExifDatabaseNotFound.add(dedupKey) else localExifDatabaseExist.add(exifData)
                            localRawDatabase.add(objR1.handlerID)
                        } else {
                            consumeImage(enrichedImage)
                            localRawDatabase.add(objR1.handlerID)
                        }
                    } else {
                        localRawDatabase.add(objR1.handlerID)
                    }
                }
            } else {
                session.log.w(TAG, "CanonCamera ❌ [R1 PROCESS] DirList failed or empty.")
            }
        } else {
            // --- NORMAL CAMERA LOGIC (Unchanged) ---
            val getObjectHandles = handlerCommandRetry(internalSession, executor) { GetObjectHandlesCommand(internalSession, storageId, MtpConstants.FORMAT_EXIF_JPEG) }
            val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: intArrayOf()
            val handlerCleanFromRaw = handlersFromStorage.filter { !localRawDatabase.contains(it) }

            for (handlerId in handlerCleanFromRaw) {
                if (localRawDatabase.contains(handlerId)) continue
                val getObjectInfoCommand = handlerCommandRetry(internalSession, executor) { GetObjectInfoCommand(internalSession, handlerId) }
                val objectInfo = getObjectInfoCommand.getResult().getOrNull() ?: continue
                val filename = objectInfo.filename?.uppercase() ?: ""

                if ((filename.endsWith(".JPEG") || filename.endsWith(".JPG")) && objectInfo.objectFormat == MtpConstants.FORMAT_EXIF_JPEG) {
                    val dedupKey = objectInfo.getAllDataKey()
                    if (localExifDatabaseNotFound.contains(dedupKey)) continue
                    val objectImage = onDownloadImage(executor, handlerId) ?: continue
                    val exifData = extractExifSignaturePartial(objectImage)
                    val enrichedImage = objectImage.copy(exifKey = exifData)
                    val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifData)
                    if (exifData != null && exifData.isNotEmpty() && localExifDatabaseExist.contains(exifData)) continue
                    val isExist = listenerCamera?.onIsImageAlreadyInDatabase(objectInfoWithExif, skipAutoUpload) ?: false
                    if (isExist) {
                        if (exifData.isNullOrEmpty()) localExifDatabaseNotFound.add(dedupKey) else localExifDatabaseExist.add(exifData)
                    } else {
                        consumeImage(enrichedImage)
                    }
                } else {
                    localRawDatabase.add(handlerId)
                }
            }
        }
        return true
    }

    private suspend fun <T : Command> handlerCommandRetry(session: Session, worker: WorkerExecutor, getCommand: () -> T): T {
        while (worker.isRunning()) {
            val command = getCommand()
            worker.handleCommand(command)
            if (!command.isRetry()) return command
            delay(500L)
        }
        error("Worker is shutting down")
    }

    private suspend fun extractExifSignaturePartial(objectImage: ObjectImage): String? {
        return try { generateExifUniqueKeyFromBytes(objectImage) } catch (e: Exception) { null }
    }

    private fun generateExifUniqueKeyFromBytes(objectImage: ObjectImage): String? {
        return try {
            val imageBytes = objectImage.image.bytes
            if (imageBytes.size < 128) return null
            val exif = ExifInterface(ByteArrayInputStream(imageBytes))
            fun clean(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: ""
            val make = clean(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = clean(exif.getAttribute(ExifInterface.TAG_MODEL))
            val dateTimeOriginal = clean(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            val subSecDigitized = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED))
            val subSecOriginal = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
            val subSecTime = if (subSecDigitized.isNotEmpty()) subSecDigitized else subSecOriginal
            val uniqueId = clean(exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID) ?: exif.getAttribute("ImageUniqueID"))
            if (model.isEmpty() || dateTimeOriginal.isEmpty()) return null
            "${make}_${model}_${dateTimeOriginal}_${subSecTime}_${uniqueId}"
        } catch (e: Exception) { null }
    }

    private suspend fun consumeImage(objectImage: ObjectImage) {
        val exifData = objectImage.exifKey
        if (exifData.isNullOrEmpty()) {
            if (!localExifDatabaseNotFound.contains(objectImage.objectInfo.getAllDataKey())) {
                localExifDatabaseNotFound.add(objectImage.objectInfo.getAllDataKey())
                listenerCamera?.onImageDownloaded(objectImage)
            }
        } else {
            if (!localExifDatabaseExist.contains(exifData)) {
                localExifDatabaseExist.add(exifData)
                localRawDatabase.add(objectImage.handlerId)
                listenerCamera?.onImageDownloaded(objectImage)
            }
        }
    }

    companion object { private const val TAG = "CanonCamera" }
}