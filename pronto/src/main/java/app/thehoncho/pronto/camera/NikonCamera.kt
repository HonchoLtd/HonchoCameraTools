package app.thehoncho.pronto.camera

import android.graphics.BitmapFactory
import android.mtp.MtpConstants
import androidx.exifinterface.media.ExifInterface
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
import app.thehoncho.pronto.model.ObjectImageWithExif
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.min

class NikonCamera(
    private val session: Session
): BaseCamera() {
    private var isPartialSupport = false

    // ✅ In-memory deduplication sets
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
            when(getStorageInfoCmd.responseCode) {
                PtpConstants.Response.Ok -> {
                    session.log.d(TAG, "Executed GetStorageInfoCommand for $storage")
                    val storageInfoResult = getStorageInfoCmd.getResult()
                    session.log.d(TAG, "Result for $storage: $storageInfoResult")
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

        while (executor.isRunning()) {
            // ─────────────────────────────────────
            // PHASE 1: Initial scan of all storages
            // ─────────────────────────────────────
            if (!finishLoadStorageImage) {
                for (storageId in validStorageIds) {
                    processStorageImages(executor, session, storageId, isSkipAutoUpload)
                }
                finishLoadStorageImage = true
                isSkipAutoUpload = false  // Enable real-time after initial scan
            }

            // ─────────────────────────────────────
            // PHASE 2: Event polling (Nikon)
            // ─────────────────────────────────────
            val getEventCommand = NikonGetEventCommand(session)
            executor.handleCommand(getEventCommand)

            getEventCommand.getResult().getOrNull()?.forEach { event ->
                if (event.code.toInt() == PtpConstants.Event.ObjectAdded) {
                    val handlerId = event.parameter

                    // Quick skip only for known non-JPEG or known no-EXIF
                    if (localRawDatabase.contains(handlerId) ||
                        localExifDatabaseNotFound.contains(handlerId.toString())) {
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

    private suspend fun processSingleImage(
        executor: WorkerExecutor,
        internalSession: Session,
        handlerId: Int,
        skipAutoUpload: Boolean
    ): Boolean {

        // 🔹 Get object info
        val getObjectInfoCommand = handlerCommandRetry(internalSession, executor) {
            GetObjectInfoCommand(internalSession, handlerId)
        }
        val objectInfo = getObjectInfoCommand.getResult().getOrNull() ?: return false
        val filename = objectInfo.filename?.uppercase() ?: ""

        // 🔹 Skip non-JPEG, track in raw database
        if (!(filename.endsWith(".JPG") || filename.endsWith(".JPEG")) ||
            objectInfo.objectFormat != MtpConstants.FORMAT_EXIF_JPEG) {
            localRawDatabase.add(handlerId)
            return false
        }

        // 🔹 Download image
        val objectImage = onDownloadImage(executor, handlerId) ?: return false

        // 🔹 Extract EXIF using the already-downloaded ObjectImage (✅ single param!)
        val exifData = extractExifSignaturePartial(objectImage)

        // 🔹 Enrich ObjectImage with the extracted exifKey (✅ populate exifKey!)
        val enrichedImage = objectImage.copy(exifKey = exifData)

        // 🔹 Dedup check: Ask app layer if image already exists
        val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifData)
        val isExist = listenerCamera?.onIsImageAlreadyInDatabase(
            objectInfoWithExif,
            skipAutoUpload
        ) ?: false


        if (isExist) {
            internalSession.log.d(
                TAG,
                "✅ DUPLICATE - Skipping | filename=${objectInfo.filename} | handler=$handlerId"
            )
            // 🗂️ Update dedup caches
            if (exifData.isNullOrEmpty()) {
                localExifDatabaseNotFound.add(handlerId.toString())
            } else {
                localExifDatabaseExist.add(exifData)
            }
        } else {
            internalSession.log.d(
                TAG,
                "🆕 NEW IMAGE - Will consume | filename=${objectInfo.filename} | handler=$handlerId"
            )
            consumeImage(enrichedImage)  // ✅ Pass enriched object
        }
        return true
    }

    private suspend fun processStorageImages(
        executor: WorkerExecutor,
        internalSession: Session,
        storageId: Int,
        skipAutoUpload: Boolean
    ): Boolean {
        // Get object handles (Nikon may use different format filter)
        val getObjectHandles = handlerCommandRetry(internalSession, executor) {
            GetObjectHandlesCommand(internalSession, storageId, MtpConstants.FORMAT_EXIF_JPEG)
        }
        val handlersFromStorage = getObjectHandles.getResult().getOrNull() ?: return false

        // 🧹 Same dedup filtering logic
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

            // ✅ Same JPEG filter logic
            if ((filename.endsWith(".JPEG") || filename.endsWith(".JPG")) &&
                objectInfo.objectFormat == MtpConstants.FORMAT_EXIF_JPEG) {

                internalSession.log.d(TAG, "🔍 Checking image | handler=$handlerId | filename=${objectInfo.filename}")

                val objectImage = onDownloadImage(executor, handlerId) ?: continue

                // ✅ Extract EXIF from the downloaded image
                val exifData = extractExifSignaturePartial(objectImage)

                // ✅ Enrich ObjectImage with the extracted exifKey
                val enrichedImage = objectImage.copy(exifKey = exifData)

                // ✅ Use original objectInfo for dedup check (library interface)
                val objectInfoWithExif = ObjectImageWithExif(objectInfo, exifData)

                val isExist = listenerCamera?.onIsImageAlreadyInDatabase(
                    objectInfoWithExif,
                    skipAutoUpload
                ) ?: false

                if (isExist) {
                    internalSession.log.d(TAG, "✅ DUPLICATE - Skipping | handler=$handlerId")
                    if (exifData.isNullOrEmpty()) {
                        localExifDatabaseNotFound.add(handlerId.toString())
                    } else {
                        localExifDatabaseExist.add(exifData)
                    }
                } else {
                    internalSession.log.d(TAG, "🆕 NEW IMAGE - Will consume | handler=$handlerId")
                    consumeImage(enrichedImage)  // ✅ Pass enriched object
                }
            } else {
                // 🚫 Mark non-JPEG/unsupported formats to skip in future scans
                internalSession.log.d(
                    TAG,
                    "⏭️ Non-JPEG - Skipping | filename=${objectInfo.filename} | handler=$handlerId | format=0x${objectInfo.objectFormat.toString(16)}"
                )
                localRawDatabase.add(handlerId)
            }
        }
        return true
    }

    private suspend fun consumeImage(objectImage: ObjectImage) {
        val exifData = objectImage.exifKey  // ✅ Read from enriched model

        if (exifData.isNullOrEmpty()) {
            if (!localExifDatabaseNotFound.contains(objectImage.handlerId.toString())) {
                session.log.d(TAG, "📥 consumeImage: No EXIF | handler=${objectImage.handlerId}")
                localExifDatabaseNotFound.add(objectImage.handlerId.toString())
                listenerCamera?.onImageDownloaded(objectImage)  // ✅ exifKey populated!
            }
        } else {
            if (!localExifDatabaseExist.contains(exifData)) {
                session.log.d(TAG, "📥 consumeImage: Has EXIF | key=${exifData.take(40)}...")
                localExifDatabaseExist.add(exifData)
                listenerCamera?.onImageDownloaded(objectImage)  // ✅ exifKey populated!
            }
        }
    }

    private fun extractExifSignaturePartial(objectImage: ObjectImage): String? {
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

            if (imageBytes.size < 128) {
                session.log.d(
                    "DEBUG_DEDUP_LIB",
                    "⚠️ SKIP EXIF: File too small | handler=${objectImage.handlerId} | filename=${objectInfo.filename} | size=${imageBytes.size}"
                )
                return null
            }

            val exif = ExifInterface(ByteArrayInputStream(imageBytes))
            fun clean(value: String?): String = value?.trim()?.takeIf { it.isNotBlank() } ?: ""

            val make = clean(exif.getAttribute(ExifInterface.TAG_MAKE))
            val model = clean(exif.getAttribute(ExifInterface.TAG_MODEL))
            val dateTimeOriginal = clean(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            val subSecDigitized = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED))
            val subSecOriginal = clean(exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
            val subSecTime = if (subSecDigitized.isNotEmpty()) subSecDigitized else subSecOriginal
            val uniqueId = clean(
                exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID)
                    ?: exif.getAttribute("ImageUniqueID")
            )

            if (model.isEmpty() || dateTimeOriginal.isEmpty()) {
                session.log.d(
                    "DEBUG_DEDUP_LIB",
                    "⚠️ EXIF: Missing required fields | model=$model | dateTime=$dateTimeOriginal | filename=${objectInfo.filename}"
                )
                return null
            }

            val key = "${make}_${model}_${dateTimeOriginal}_${subSecTime}_${uniqueId}"
            session.log.d(
                "DEBUG_DEDUP_LIB",
                "✅ EXIF Key Generated | filename=${objectInfo.filename} | key=${key.take(60)}..."
            )
            return key

        } catch (e: Exception) {
            session.log.w(
                "DEBUG_DEDUP_LIB",
                "❌ EXIF: Parse failed | filename=${objectImage.objectInfo.filename} | error=${e.message}",
                e
            )
            null
        }
    }

    companion object {
        private const val TAG = "NikonCamera"
    }
}