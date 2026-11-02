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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

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
                        val dirList = crawlStorage(executor, session, storageId)
                        if (dirList.isNotEmpty()) {
                            for (objR1 in dirList) {
                                if (localRawDatabase.contains(objR1.handlerID)) continue
                                allObjectInfos.add(objR1.toObjectInfo())
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

            if (isR1Camera) {
                session.log.d(TAG, "CanonCamera 🔍 [R1 EVENT] Poll result: hasEvents=$hasEvents, responseCode=0x${responseCode.toString(16)}")
            }

            if (hasEvents) {
                for (storageId in validStorageIds) {
                    if (isR1Camera) {
                        processStorageImages(executor, session, storageId, skipAutoUpload = false, isR1Camera = true)
                    } else {
                        val cmd = handlerCommandRetry(session, executor) { GetNumObjectsCommand(session, storageId) }
                        val numResult = cmd.getResult()
                        if (numResult.isSuccess) {
                            val currentCount = numResult.getOrDefault(0)
                            val previousCount = objectCountByStorage[storageId] ?: 0
                            session.log.d(TAG, "🔢 [GATE] storage=$storageId prev=$previousCount now=$currentCount knownHandles=${localRawDatabase.size}")

                            if (currentCount != previousCount) {
                                session.log.d(TAG, "🔢 [GATE] ✅ COUNT CHANGED → scanning storage $storageId")
                                objectCountByStorage[storageId] = currentCount
                                processStorageImages(executor, session, storageId, false, isR1Camera = false)
                            } else {
                                // keep this, but it's now filtered out by FilteredCameraLogger
                                session.log.d(TAG, "CanonCamera 🔍 [EVENT] Count unchanged ($currentCount). Ignoring redundant event.")
                            }
                        } else {
                            session.log.w(TAG, "⚠️ [GATE] GetNumObjects FAILED → fallback scan")
                            processStorageImages(executor, session, storageId, false, isR1Camera = false)
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

        // ✅ ADD THIS — dump every supported opcode once
        session.log.d(TAG, "🔧 [MODEL] name='$modelName' | opsSupported=${
            deviceInfo.operationsSupported?.joinToString(",") { "0x${it.toString(16)}" }
        }")

        if (modelName.contains("EOS R1", ignoreCase = true) || modelName.equals("R1", true)) {
            cameraModel = CameraModel.R1
            isR1Camera = true
            session.log.d(TAG, "🔧 [MODEL] → R1 (64-bit chunking)")
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
        cameraModel = when {
            has64Bit || has32Bit -> CameraModel.Normal
            else -> CameraModel.Legacy
        }
        isR1Camera = false

        // ✅ ADD THIS
        session.log.d(TAG, "🔧 [MODEL] → $cameraModel | has32=$has32Bit has64=$has64Bit | willChunk=${cameraModel != CameraModel.Legacy}")
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

    // ✅ CANON R1: Chunks written DIRECTLY to file (HARDENED)
    private suspend fun downloadImagePartial64R1(worker: WorkerExecutor, objR1: ObjectInfoR1): ObjectImage? {
        session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Chunking to file START | handler=0x${objR1.handlerID.toString(16)} | File: ${objR1.filename} | Size: ${objR1.objectSize64}")
        var offset: Long = 0L
        val totalBytes = objR1.objectSize64
        val maxChunkSize = 2 * 1024 * 1024L
        if (totalBytes <= 0) {
            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Total bytes is 0 or negative! Aborting.")
            return null
        }
        return withContext(Dispatchers.IO) {
            var chunkIndex = 0
            val tempFile = File.createTempFile("pronto_r1_${objR1.handlerID}_", ".tmp")
            try {
                java.io.FileOutputStream(tempFile).use { fos ->
                    while (offset < totalBytes && worker.isRunning()) {
                        chunkIndex++
                        val remaining = totalBytes - offset
                        val requestedSize = remaining.coerceAtMost(maxChunkSize).toInt()
                        val finalOffset = offset
                        session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Writing chunk to file | chunk=$chunkIndex | offset=$finalOffset | requested=$requestedSize")
                        val partialCmd = handlerCommandRetry(session, worker) {
                            GetPartialObject64Command(session, objR1.handlerID, finalOffset, requestedSize)
                        }
                        val chunk = partialCmd.getResult().getOrNull()

                        if (chunk == null) {
                            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Chunk #$chunkIndex returned NULL at offset $finalOffset")
                            return@withContext null
                        }
                        // ✅ FIX 1: Prevent infinite loop on 0-byte USB glitch
                        if (chunk.isEmpty()) {
                            session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Chunk #$chunkIndex returned EMPTY (0 bytes) at offset $finalOffset")
                            return@withContext null
                        }

                        fos.write(chunk)
                        offset += chunk.size.toLong()
                        session.log.d(TAG, "CanonCamera 🔍 [R1 DOWNLOAD] Chunk #$chunkIndex success | Received: ${chunk.size} bytes | File size: ${tempFile.length()} / $totalBytes")
                    }
                }

                // ✅ FIX 2: Prevent corrupted file if USB is yanked mid-download
                if (offset != totalBytes) {
                    session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Download INCOMPLETE | expected=$totalBytes | got=$offset")
                    tempFile.delete()
                    return@withContext null
                }

                session.log.d(TAG, "CanonCamera ✅ [R1 DOWNLOAD] Chunking to file FINISH | Total chunks: $chunkIndex | File: ${tempFile.absolutePath}")

                if (tempFile.length() == 0L) {
                    session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Downloaded file is empty!")
                    tempFile.delete()
                    return@withContext null
                }

                val bitmapOptions = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, bitmapOptions)
                if (bitmap == null) {
                    session.log.w(TAG, "CanonCamera ⚠️ [R1 DOWNLOAD] Failed to decode bitmap for ${objR1.filename}")
                }
                val objectInfo = objR1.toObjectInfo()
                ObjectImage(objectInfo, objR1.handlerID, ImageObject(tempFile, bitmap))
            } catch (e: Exception) {
                session.log.e(TAG, "CanonCamera ❌ [R1 DOWNLOAD] Chunking to file ERROR | handler=0x${objR1.handlerID.toString(16)}", e)
                if (tempFile.exists()) tempFile.delete()
                null
            }
        }
    }

    private suspend fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        session.log.d(TAG, "⬇️ [DOWNLOAD ENTRY] handler=$handler | model=$cameraModel")

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
            session.log.d(TAG, "⬇️ [DOWNLOAD ENTRY] → chunked path")
            downloadImagePartial(worker, handler, objectInfo)
        } else {
            session.log.d(TAG, "⬇️ [DOWNLOAD ENTRY] → FULL path (no chunking, Legacy model)")
            downloadImage(worker, handler, objectInfo)
        }
    }

    // ✅ FALLBACK: Full download written to file (consistent API)
    private suspend fun downloadImage(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.d(TAG, "📦 [CanonCamera] Downloading FULL image to file | handler=$handler | size=${objectInfo.objectCompressedSize}")
        val getObjectCommand = handlerCommandRetry(session, worker) { GetObjectCommand(session, handler) }
        val imageObject = getObjectCommand.getResult().getOrNull()
        if (imageObject == null) {
            session.log.e(TAG, "📦 [CanonCamera] Full download FAILED | handler=$handler")
            return null
        }
        session.log.d(TAG, "📦 [CanonCamera] Full download FINISH | handler=$handler | file=${imageObject.file.absolutePath}")
        return ObjectImage(objectInfo, handler, imageObject)
    }

    // ✅ NORMAL CANON: Chunks written DIRECTLY to file (HARDENED)
    private suspend fun downloadImagePartial(
        worker: WorkerExecutor,
        handler: Int,
        objectInfo: ObjectInfo
    ): ObjectImage? {
        session.log.d(TAG, "📦 [CanonCamera] Chunking to file START | handler=$handler | totalBytes=${objectInfo.objectCompressedSize}")
        val totalBytes = objectInfo.objectCompressedSize.toLong()
        val maxPacketSize = 1024 * 1024
        return withContext(Dispatchers.IO) {
            var offset = 0L
            var chunkIndex = 1
            val tempFile = File.createTempFile("pronto_canon_${handler}_", ".tmp")
            try {
                java.io.FileOutputStream(tempFile).use { fos ->
                    while (offset < totalBytes && worker.isRunning()) {
                        val remaining = totalBytes - offset
                        val requestedSize = remaining.coerceAtMost(maxPacketSize.toLong()).toInt()
                        val finalOffset = offset
                        session.log.d(TAG, "📦 [CanonCamera] Writing chunk to file | handler=$handler | chunk=$chunkIndex | offset=$finalOffset | requested=$requestedSize")
                        val partialObjectCommand = handlerCommandRetry(session, worker) {
                            GetObjectPartial(session, handler, finalOffset.toInt(), requestedSize)
                        }
                        val chunk = partialObjectCommand.getResult().getOrNull()

                        if (chunk == null) {
                            session.log.e(TAG, "📦 [CanonCamera] Chunking ABORTED (Null) | handler=$handler | chunk=$chunkIndex")
                            return@withContext null
                        }
                        // ✅ FIX 1: Prevent infinite loop on 0-byte USB glitch
                        if (chunk.isEmpty()) {
                            session.log.e(TAG, "📦 [CanonCamera] Chunking ABORTED (Empty Chunk) | handler=$handler | chunk=$chunkIndex")
                            return@withContext null
                        }

                        fos.write(chunk)
                        offset += chunk.size.toLong()
                        chunkIndex++
                    }
                }

                // ✅ FIX 2: Prevent corrupted file if USB is yanked mid-download
                if (offset != totalBytes) {
                    session.log.e(TAG, "📦 [CanonCamera] Download INCOMPLETE | handler=$handler | expected=$totalBytes | got=$offset")
                    tempFile.delete()
                    return@withContext null
                }

                session.log.d(TAG, "📦 [CanonCamera] Chunking to file FINISH | handler=$handler | totalChunks=${chunkIndex - 1} | File: ${tempFile.absolutePath}")

                if (tempFile.length() == 0L) {
                    session.log.e(TAG, "📦 [CanonCamera] Downloaded file is empty!")
                    tempFile.delete()
                    return@withContext null
                }

                val bitmapOptions = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, bitmapOptions)
                ObjectImage(objectInfo, handler, ImageObject(tempFile, bitmap))
            } catch (e: Exception) {
                session.log.e(TAG, "📦 [CanonCamera] Chunking to file ERROR | handler=$handler", e)
                if (tempFile.exists()) tempFile.delete()
                null
            }
        }
    }

    private suspend fun crawlStorage(
        executor: WorkerExecutor,
        session: Session,
        storageId: Int
    ): List<ObjectInfoR1> {
        val allObjects = mutableListOf<ObjectInfoR1>()
        data class CrawlFolder(val handle: Int, val name: String, val parentHandle: Int)
        val foldersToProcess = ArrayDeque<CrawlFolder>()
        val visitedFolders = mutableSetOf<Int>()
        val addedObjectIds = mutableSetOf<Int>()
        val rootHandle = -1
        foldersToProcess.add(CrawlFolder(rootHandle, "Storage Root", 0))
        visitedFolders.add(rootHandle)

        while (foldersToProcess.isNotEmpty() && executor.isRunning()) {
            val currentFolder = foldersToProcess.removeFirst()
            val dirListCmd = GetDirObjectInfoListCommand(session, storageId, currentFolder.handle)
            executor.handleCommand(dirListCmd)
            val dirList = dirListCmd.getResult().getOrNull() ?: continue
            if (dirList.isEmpty()) {
                session.log.w(TAG, "CanonCamera 🔍 [R1 CRAWL] ⚠️ Folder 0x${currentFolder.handle.toString(16)} returned 0 items.")
                continue
            }
            for (obj in dirList) {
                if (obj.objectFormat == MtpConstants.FORMAT_ASSOCIATION) {
                    val folderName = obj.filename ?: "UnknownFolder"
                    if (!visitedFolders.contains(obj.handlerID)) {
                        visitedFolders.add(obj.handlerID)
                        foldersToProcess.add(CrawlFolder(obj.handlerID, folderName, obj.parentObject))
                    }
                } else {
                    val name = obj.filename?.uppercase() ?: ""
                    val isJpeg = name.endsWith(".JPG") || name.endsWith(".JPEG") || obj.objectFormat == MtpConstants.FORMAT_EXIF_JPEG
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
            val dirList = crawlStorage(executor, internalSession, storageId)
            if (dirList.isNotEmpty()) {
                val newObjects = dirList.filter { !localRawDatabase.contains(it.handlerID) }
                if (newObjects.isEmpty()) return true
                for (objR1 in newObjects) {
                    val filename = objR1.filename?.uppercase() ?: ""
                    if (objR1.objectSize64 == 0L) {
                        session.log.w(TAG, "CanonCamera ⚠️ [R1 PROCESS] File $filename has size 0. Waiting for next loop...")
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
                        val exifData = extractExifSignatureFromFile(objectImage)
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
            val getObjectHandles = handlerCommandRetry(internalSession, executor) { GetObjectHandlesCommand(internalSession, storageId, MtpConstants.FORMAT_EXIF_JPEG) }
            val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: intArrayOf()
            val handlerCleanFromRaw = handlersFromStorage.filter { !localRawDatabase.contains(it) }
            session.log.d(TAG, "📂 [SCAN] storage=$storageId total=${handlersFromStorage.size} new=${handlerCleanFromRaw.size} → ${handlerCleanFromRaw.joinToString(",")}")
            if (handlerCleanFromRaw.isEmpty()) {
                session.log.d(TAG, "📂 [SCAN] nothing new, all handles already in localRawDatabase")
            }
            for (handlerId in handlerCleanFromRaw) {
                if (localRawDatabase.contains(handlerId)) continue
                val getObjectInfoCommand = handlerCommandRetry(internalSession, executor) { GetObjectInfoCommand(internalSession, handlerId) }
                val objectInfo = getObjectInfoCommand.getResult().getOrNull() ?: continue
                val filename = objectInfo.filename?.uppercase() ?: ""
                if ((filename.endsWith(".JPEG") || filename.endsWith(".JPG")) && objectInfo.objectFormat == MtpConstants.FORMAT_EXIF_JPEG) {
                    val dedupKey = objectInfo.getAllDataKey()
                    if (localExifDatabaseNotFound.contains(dedupKey)) continue
                    val objectImage = onDownloadImage(executor, handlerId) ?: continue
                    val exifData = extractExifSignatureFromFile(objectImage)
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

    // Now reads EXIF directly from File
    private fun extractExifSignatureFromFile(objectImage: ObjectImage): String? {
        return try {
            generateExifUniqueKeyFromFile(objectImage)
        } catch (e: Exception) {
            session.log.w(TAG, "❌ EXIF: Exception | handler=${objectImage.handlerId} | filename=${objectImage.objectInfo.filename} | error=${e.message}", e)
            null
        }
    }

    // Reads EXIF directly from File (no ByteArray in RAM!)
    private fun generateExifUniqueKeyFromFile(objectImage: ObjectImage): String? {
        return try {
            val objectInfo = objectImage.objectInfo
            val file = objectImage.image.file

            if (file.length() < 128) {
                session.log.d("DEBUG_DEDUP_LIB", "⚠️ SKIP EXIF: File too small | handler=${objectImage.handlerId} | filename=${objectInfo.filename} | size=${file.length()}")
                return null
            }

            // ✅ ExifInterface accepts File directly!
            val exif = ExifInterface(file)

            fun clean(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: ""
            val make = clean(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = clean(exif.getAttribute(ExifInterface.TAG_MODEL))
            val dateTimeOriginal = clean(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            val subSecDigitized = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED))
            val subSecOriginal = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
            val subSecTime = if (subSecDigitized.isNotEmpty()) subSecDigitized else subSecOriginal
            val uniqueId = clean(exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID) ?: exif.getAttribute("ImageUniqueID"))

            if (model.isEmpty() || dateTimeOriginal.isEmpty()) {
                session.log.d("DEBUG_DEDUP_LIB", "⚠️ EXIF: Missing required fields | model=$model | dateTime=$dateTimeOriginal | filename=${objectInfo.filename}")
                return null
            }

            val key = "${make}_${model}_${dateTimeOriginal}_${subSecTime}_${uniqueId}"
            session.log.d("DEBUG_DEDUP_LIB", "✅ EXIF Key Generated | filename=${objectInfo.filename} | key=${key.take(60)}...")
            key
        } catch (e: Exception) {
            session.log.w("DEBUG_DEDUP_LIB", "❌ EXIF: Parse failed | filename=${objectImage.objectInfo.filename} | error=${e.message}", e)
            null
        }
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