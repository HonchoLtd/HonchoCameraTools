package app.thehoncho.pronto.camera

import android.graphics.BitmapFactory
import android.mtp.MtpConstants
import androidx.exifinterface.media.ExifInterface
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.WorkerExecutor
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.command.general.CloseSessionCommand
import app.thehoncho.pronto.command.general.GetDeviceInfoCommand
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
import app.thehoncho.pronto.model.ObjectImageWithExif
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class NikonCamera(private val session: Session) : BaseCamera() {
    private var isPartialSupport = false
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

        deviceInfo.operationsSupported.forEach { opt ->
            if (opt.toShort() == PtpConstants.Operation.GetPartialObject) {
                isPartialSupport = true
                return@forEach
            }
        }

        val getStorageIds = GetStorageIdsCommand(session)
        executor.handleCommand(getStorageIds)
        getStorageIds.getResult().onFailure {
            session.log.e(TAG, "execute: failed when get storage ids. Manufacture: ${deviceInfo.manufacture} Model: ${deviceInfo.model}")
            listenerCamera?.onError(Throwable("failed when get storage ids, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }
        val storageIds = getStorageIds.getResult().getOrNull() ?: return@runBlocking
        if (storageIds.isEmpty()) {
            session.log.e(TAG, "execute: storageIds is empty.")
            listenerCamera?.onError(Throwable("storage ids is empty, please check the sd card"))
            listenerCamera?.onStop()
            return@runBlocking
        }

        var finishLoadStorageImage = false
        listenerCamera?.onReady()

        val validStorageIds = mutableListOf<Int>()
        storageIds.forEach { storage ->
            val getStorageInfoCmd = GetStorageInfoCommand(session, storage)
            executor.handleCommand(getStorageInfoCmd)
            when (getStorageInfoCmd.responseCode) {
                PtpConstants.Response.Ok -> {
                    getStorageInfoCmd.getResult().getOrNull()?.let { info ->
                        if (info.maxCapacity > 0) validStorageIds.add(storage)
                    }
                }
                PtpConstants.Response.StoreNotAvailable -> {
                    session.log.w(TAG, "Storage Not Available id: $storage")
                }
                else -> {
                    session.log.e(TAG, "Storage: Cannot populate storage")
                    listenerCamera?.onError(Throwable("Cannot populate storage"))
                    listenerCamera?.onStop()
                    return@runBlocking
                }
            }
        }

        while (executor.isRunning()) {
            if (!finishLoadStorageImage) {
                for (storageId in validStorageIds) {
                    processStorageImages(executor, session, storageId, isSkipAutoUpload)
                }
                finishLoadStorageImage = true
                isSkipAutoUpload = false
            }

            val getEventCommand = NikonGetEventCommand(session)
            executor.handleCommand(getEventCommand)
            getEventCommand.getResult().getOrNull()?.forEach { event ->
                if (event.code.toInt() == PtpConstants.Event.ObjectAdded) {
                    val handlerId = event.parameter
                    if (localRawDatabase.contains(handlerId) || localExifDatabaseNotFound.contains(handlerId.toString())) {
                        return@forEach
                    }
                    processSingleImage(executor, session, handlerId, isSkipAutoUpload)
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
        session.log.d(TAG, "⬇️ [DOWNLOAD ENTRY] handler=$handler | partialSupport=$isPartialSupport")

        val getObjectInfoCommand = handlerCommandRetry(session, worker) {
            GetObjectInfoCommand(session, handler)
        }
        val objectInfo = getObjectInfoCommand.getResult().getOrNull()
        if (objectInfo == null) {
            session.log.e(TAG, "onDownloadImage: failed when download info image")
            return null
        }

        return if (isPartialSupport) {
            session.log.d(TAG, "⬇️ [DOWNLOAD ENTRY] → chunked path")
            downloadImagePartial(worker, handler, objectInfo)
        } else {
            session.log.d(TAG, "⬇️ [DOWNLOAD ENTRY] → FULL path (no chunking)")
            downloadImage(worker, handler, objectInfo)
        }
    }

    // ✅ FALLBACK: Full download written to file
    private suspend fun downloadImage(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.d(TAG, "📦 [NikonCamera] Downloading FULL image to file | handler=$handler | size=${objectInfo.objectCompressedSize}")
        val getObjectCommand = handlerCommandRetry(session, worker) { GetObjectCommand(session, handler) }
        val imageObject = getObjectCommand.getResult().getOrNull()
        if (imageObject == null) {
            session.log.e(TAG, "📦 [NikonCamera] Full download FAILED | handler=$handler")
            return null
        }
        session.log.d(TAG, "📦 [NikonCamera] Full download FINISH | handler=$handler | file=${imageObject.file.absolutePath}")
        return ObjectImage(objectInfo, handler, imageObject)
    }

    // ✅ NIKON: Chunks written DIRECTLY to file (HARDENED)
    private suspend fun downloadImagePartial(
        worker: WorkerExecutor,
        handler: Int,
        objectInfo: ObjectInfo
    ): ObjectImage? {
        session.log.d(TAG, "📦 [NikonCamera] Chunking to file START | handler=$handler | totalBytes=${objectInfo.objectCompressedSize}")
        val totalBytes = objectInfo.objectCompressedSize.toLong()
        val maxPacketSize = 1024 * 1024
        return withContext(Dispatchers.IO) {
            var offset = 0L
            var chunkIndex = 1
            val tempFile = File.createTempFile("pronto_nikon_${handler}_", ".tmp")
            try {
                java.io.FileOutputStream(tempFile).use { fos ->
                    while (offset < totalBytes && worker.isRunning()) {
                        val remaining = totalBytes - offset
                        val requestedSize = remaining.coerceAtMost(maxPacketSize.toLong()).toInt()
                        val finalOffset = offset
                        session.log.d(TAG, "📦 [NikonCamera] Writing chunk to file | handler=$handler | chunk=$chunkIndex | offset=$finalOffset | requested=$requestedSize")
                        val partialObjectCommand = handlerCommandRetry(session, worker) {
                            GetObjectPartial(session, handler, finalOffset.toInt(), requestedSize)
                        }
                        val chunk = partialObjectCommand.getResult().getOrNull()

                        if (chunk == null) {
                            session.log.e(TAG, "📦 [NikonCamera] Chunking ABORTED (Null) | handler=$handler | chunk=$chunkIndex")
                            return@withContext null
                        }
                        // ✅ FIX 1: Prevent infinite loop on 0-byte USB glitch
                        if (chunk.isEmpty()) {
                            session.log.e(TAG, "📦 [NikonCamera] Chunking ABORTED (Empty Chunk) | handler=$handler | chunk=$chunkIndex")
                            return@withContext null
                        }

                        fos.write(chunk)
                        offset += chunk.size.toLong()
                        chunkIndex++
                    }
                }

                // ✅ FIX 2: Prevent corrupted file if USB is yanked mid-download
                if (offset != totalBytes) {
                    session.log.e(TAG, "📦 [NikonCamera] Download INCOMPLETE | handler=$handler | expected=$totalBytes | got=$offset")
                    tempFile.delete()
                    return@withContext null
                }

                session.log.d(TAG, "📦 [NikonCamera] Chunking to file FINISH | handler=$handler | totalChunks=${chunkIndex - 1} | File: ${tempFile.absolutePath}")

                if (tempFile.length() == 0L) {
                    session.log.e(TAG, "📦 [NikonCamera] Downloaded file is empty!")
                    tempFile.delete()
                    return@withContext null
                }

                val bitmapOptions = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, bitmapOptions)
                ObjectImage(objectInfo, handler, ImageObject(tempFile, bitmap))
            } catch (e: Exception) {
                session.log.e(TAG, "📦 [NikonCamera] Chunking to file ERROR | handler=$handler", e)
                if (tempFile.exists()) tempFile.delete()
                null
            }
        }
    }

    private suspend fun <T : Command> handlerCommandRetry(session: Session, worker: WorkerExecutor, getCommand: () -> T): T {
        while (worker.isRunning()) {
            val command = getCommand()
            worker.handleCommand(command)
            if (!command.isRetry()) return command
            session.log.e(TAG, "${command::class.simpleName} request retry")
            delay(500L)
        }
        error("Worker is shutting down")
    }

    private suspend fun processSingleImage(
        executor: WorkerExecutor, internalSession: Session,
        handlerId: Int, skipAutoUpload: Boolean
    ): Boolean {
        val getObjectInfoCommand = handlerCommandRetry(internalSession, executor) {
            GetObjectInfoCommand(internalSession, handlerId)
        }
        val objectInfo = getObjectInfoCommand.getResult().getOrNull() ?: return false
        val filename = objectInfo.filename?.uppercase() ?: ""

        if (!(filename.endsWith(".JPG") || filename.endsWith(".JPEG")) ||
            objectInfo.objectFormat != MtpConstants.FORMAT_EXIF_JPEG) {
            localRawDatabase.add(handlerId)
            return false
        }

        val objectImage = onDownloadImage(executor, handlerId) ?: return false
        val exifData = extractExifSignatureFromFile(objectImage)
        val enrichedImage = objectImage.copy(exifKey = exifData)
        val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifData)
        val isExist = listenerCamera?.onIsImageAlreadyInDatabase(objectInfoWithExif, skipAutoUpload) ?: false

        if (isExist) {
            internalSession.log.d(TAG, "✅ DUPLICATE - Skipping | filename=${objectInfo.filename} | handler=$handlerId")
            if (exifData.isNullOrEmpty()) localExifDatabaseNotFound.add(handlerId.toString())
            else localExifDatabaseExist.add(exifData)
        } else {
            internalSession.log.d(TAG, "🆕 NEW IMAGE - Will consume | filename=${objectInfo.filename} | handler=$handlerId")
            consumeImage(enrichedImage)
        }
        return true
    }

    private suspend fun processStorageImages(
        executor: WorkerExecutor, internalSession: Session,
        storageId: Int, skipAutoUpload: Boolean
    ): Boolean {
        val getObjectHandles = handlerCommandRetry(internalSession, executor) {
            GetObjectHandlesCommand(internalSession, storageId, MtpConstants.FORMAT_EXIF_JPEG)
        }
        val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: return false
        val handlerFinalClean = handlersFromStorage
            .filter { !localRawDatabase.contains(it) }
            .filter { !localExifDatabaseExist.contains(it.toString()) }
            .filter { !localExifDatabaseNotFound.contains(it.toString()) }

        for (handlerId in handlerFinalClean) {
            val getObjectInfoCommand = handlerCommandRetry(internalSession, executor) {
                GetObjectInfoCommand(internalSession, handlerId)
            }
            val objectInfo = getObjectInfoCommand.getResult().getOrNull() ?: continue
            val filename = objectInfo.filename?.uppercase() ?: ""

            if ((filename.endsWith(".JPEG") || filename.endsWith(".JPG")) &&
                objectInfo.objectFormat == MtpConstants.FORMAT_EXIF_JPEG) {
                val objectImage = onDownloadImage(executor, handlerId) ?: continue
                val exifData = extractExifSignatureFromFile(objectImage)
                val enrichedImage = objectImage.copy(exifKey = exifData)
                val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifData)
                val isExist = listenerCamera?.onIsImageAlreadyInDatabase(objectInfoWithExif, skipAutoUpload) ?: false

                if (isExist) {
                    internalSession.log.d(TAG, "✅ DUPLICATE - Skipping | handler=$handlerId")
                    if (exifData.isNullOrEmpty()) localExifDatabaseNotFound.add(handlerId.toString())
                    else localExifDatabaseExist.add(exifData)
                } else {
                    internalSession.log.d(TAG, "🆕 NEW IMAGE - Will consume | handler=$handlerId")
                    consumeImage(enrichedImage)
                }
            } else {
                localRawDatabase.add(handlerId)
            }
        }
        return true
    }

    private suspend fun consumeImage(objectImage: ObjectImage) {
        val exifData = objectImage.exifKey
        if (exifData.isNullOrEmpty()) {
            if (!localExifDatabaseNotFound.contains(objectImage.handlerId.toString())) {
                localExifDatabaseNotFound.add(objectImage.handlerId.toString())
                listenerCamera?.onImageDownloaded(objectImage)
            }
        } else {
            if (!localExifDatabaseExist.contains(exifData)) {
                localExifDatabaseExist.add(exifData)
                listenerCamera?.onImageDownloaded(objectImage)
            }
        }
    }

    // ✅ RENAMED: Now reads EXIF directly from File
    private fun extractExifSignatureFromFile(objectImage: ObjectImage): String? {
        return try {
            generateExifUniqueKeyFromFile(objectImage)
        } catch (e: Exception) {
            session.log.w(TAG, "❌ EXIF: Exception | handler=${objectImage.handlerId} | error=${e.message}", e)
            null
        }
    }

    // ✅ CHANGED: Reads EXIF directly from File (no ByteArray in RAM!)
    private fun generateExifUniqueKeyFromFile(objectImage: ObjectImage): String? {
        return try {
            val objectInfo = objectImage.objectInfo
            val file = objectImage.image.file

            if (file.length() < 128) {
                session.log.d("DEBUG_DEDUP_LIB", "⚠️ SKIP EXIF: File too small | handler=${objectImage.handlerId} | size=${file.length()}")
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

            if (model.isEmpty() || dateTimeOriginal.isEmpty()) return null

            "${make}_${model}_${dateTimeOriginal}_${subSecTime}_${uniqueId}"
        } catch (e: Exception) {
            session.log.w("DEBUG_DEDUP_LIB", "❌ EXIF: Parse failed | error=${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "NikonCamera"
    }
}