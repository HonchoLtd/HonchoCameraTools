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
                                return@let // ❌ Skip: already cached, don't add again
                            }

                            // 5. ✅ Unique: Add to cache (metadata only, no image bytes yet)
                            val entry = CachedImageEntry(info, compositeKey, info.handlerID)
                            cacheImage[compositeKey] = entry

                            session.log.d(
                                TAG,
                                "Cached unique image: $name with key: $compositeKey, handler: ${info.handlerID}"
                            )

                            session.log.d(
                                TAG,
                                "Cached unique image metadata: $name with key: $compositeKey"
                            )
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
                    if (objectImage == null) {
                        session.log.e(
                            TAG,
                            "execute: failed when download image ${entry.objectInfo.filename}"
                        )
                        listenerCamera?.onError(Throwable("failed when download image ${entry.objectInfo.filename}"))
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
            // EXIF data is typically in the first 64KB of JPEG files
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
            // Fallback: hash of immutable fields if EXIF fails
            "${objectInfo.filename}_${objectInfo.objectCompressedSize}_${objectInfo.captureDate}".hashCode()
                .toString(16)
        }
        return "${objectInfo.storageId}-${objectInfo.parentObject}-$safeExifKey"
    }

//    private fun generateExifUniqueKeyFromBytes(
//        partialBytes: ByteArray,
//        objectInfo: ObjectInfo
//    ): String? {
//        return try {
//            val exif = ExifInterface(ByteArrayInputStream(partialBytes))
//
//
//            val subSecTime = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME) ?: ""
//            val imageUniqueId = exif.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID) ?: ""
//
//            // === DEVICE INFO ===
//            val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
//            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
//            val lensMake = exif.getAttribute(ExifInterface.TAG_LENS_MAKE) ?: ""
//            val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL) ?: ""
//            val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: ""
//            val artist = exif.getAttribute(ExifInterface.TAG_ARTIST) ?: ""
//            val copyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT) ?: ""
//
//            // === EXPOSURE & CAMERA SETTINGS ===
//            val fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: ""
//            val exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: ""
//            val iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS) ?: ""
//            val focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH) ?: ""
//            val focalLengthIn35mm =
//                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM) ?: ""
//            val flash = exif.getAttribute(ExifInterface.TAG_FLASH) ?: ""
//            val flashEnergy = exif.getAttribute(ExifInterface.TAG_FLASH_ENERGY) ?: ""
//            val whiteBalance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE) ?: ""
//            val exposureProgram = exif.getAttribute(ExifInterface.TAG_EXPOSURE_PROGRAM) ?: ""
//            val meteringMode = exif.getAttribute(ExifInterface.TAG_METERING_MODE) ?: ""
//            val exposureBias = exif.getAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE) ?: ""
//            val maxApertureValue = exif.getAttribute(ExifInterface.TAG_MAX_APERTURE_VALUE) ?: ""
//            val subjectDistance = exif.getAttribute(ExifInterface.TAG_SUBJECT_DISTANCE) ?: ""
//            val sensingMethod = exif.getAttribute(ExifInterface.TAG_SENSING_METHOD) ?: ""
//            val sceneCaptureType = exif.getAttribute(ExifInterface.TAG_SCENE_CAPTURE_TYPE) ?: ""
//            val gainControl = exif.getAttribute(ExifInterface.TAG_GAIN_CONTROL) ?: ""
//            val contrast = exif.getAttribute(ExifInterface.TAG_CONTRAST) ?: ""
//            val saturation = exif.getAttribute(ExifInterface.TAG_SATURATION) ?: ""
//            val sharpness = exif.getAttribute(ExifInterface.TAG_SHARPNESS) ?: ""
//            val subjectDistanceRange =
//                exif.getAttribute(ExifInterface.TAG_SUBJECT_DISTANCE_RANGE) ?: ""
//
//            // === IMAGE PROPERTIES ===
//            val pixelX = exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION) ?: ""
//            val pixelY = exif.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION) ?: ""
//            val orientation = exif.getAttribute(ExifInterface.TAG_ORIENTATION) ?: ""
//            val xResolution = exif.getAttribute(ExifInterface.TAG_X_RESOLUTION) ?: ""
//            val yResolution = exif.getAttribute(ExifInterface.TAG_Y_RESOLUTION) ?: ""
//            val resolutionUnit = exif.getAttribute(ExifInterface.TAG_RESOLUTION_UNIT) ?: ""
//            val colorSpace = exif.getAttribute(ExifInterface.TAG_COLOR_SPACE) ?: ""
//            val componentsConfig =
//                exif.getAttribute(ExifInterface.TAG_COMPONENTS_CONFIGURATION) ?: ""
//            val compressedBitsPerPixel =
//                exif.getAttribute(ExifInterface.TAG_COMPRESSED_BITS_PER_PIXEL) ?: ""
//            val imageDescription = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: ""
//            val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT) ?: ""
//            val imageWidth = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH) ?: ""
//            val imageLength = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH) ?: ""
//
//            // === GPS DATA (sanitize for logging) ===
//            val gpsLat = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) ?: ""
//            val gpsLatRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) ?: ""
//            val gpsLon = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) ?: ""
//            val gpsLonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) ?: ""
//            val gpsAltitude = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) ?: ""
//            val gpsAltitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF) ?: ""
//            val gpsTimeStamp = exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP) ?: ""
//            val gpsDateStamp = exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP) ?: ""
//            val gpsProcessingMethod =
//                exif.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD) ?: ""
//            val gpsAreaInformation = exif.getAttribute(ExifInterface.TAG_GPS_AREA_INFORMATION) ?: ""
//            val gpsDifferential = exif.getAttribute(ExifInterface.TAG_GPS_DIFFERENTIAL) ?: ""
//            val gpsSpeedRef = exif.getAttribute(ExifInterface.TAG_GPS_SPEED_REF) ?: ""
//            val gpsSpeed = exif.getAttribute(ExifInterface.TAG_GPS_SPEED) ?: ""
//            val gpsTrackRef = exif.getAttribute(ExifInterface.TAG_GPS_TRACK_REF) ?: ""
//            val gpsTrack = exif.getAttribute(ExifInterface.TAG_GPS_TRACK) ?: ""
//            val gpsImgDirectionRef =
//                exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF) ?: ""
//            val gpsImgDirection = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION) ?: ""
//            val gpsDestLat = exif.getAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE) ?: ""
//            val gpsDestLon = exif.getAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE) ?: ""
//
////            val logLines = listOf(
////                // Timestamps
////                "dateTimeOriginal: $dateTimeOriginal",
////                "dateTimeDigitized: $dateTimeDigitized",
////                "dateTime: $dateTime",
////                "subSecOriginal: $subSecOriginal",
////                "subSecDigitized: $subSecDigitized",
////                "subSecTime: $subSecTime",
////                "imageUniqueId: $imageUniqueId",
////
////                // Device
////                "make: $make",
////                "model: $model",
////                "lensMake: $lensMake",
////                "lensModel: $lensModel",
////                "software: $software",
////                "artist: $artist",
////                "copyright: $copyright",
////
////                // Exposure
////                "fNumber: $fNumber",
////                "exposureTime: $exposureTime",
////                "iso: $iso",
////                "focalLength: $focalLength",
////                "focalLengthIn35mm: $focalLengthIn35mm",
////                "flash: $flash",
////                "flashEnergy: $flashEnergy",
////                "whiteBalance: $whiteBalance",
////                "exposureProgram: $exposureProgram",
////                "meteringMode: $meteringMode",
////                "exposureBias: $exposureBias",
////                "maxApertureValue: $maxApertureValue",
////                "subjectDistance: $subjectDistance",
////                "sensingMethod: $sensingMethod",
////                "sceneCaptureType: $sceneCaptureType",
////                "gainControl: $gainControl",
////                "contrast: $contrast",
////                "saturation: $saturation",
////                "sharpness: $sharpness",
////                "subjectDistanceRange: $subjectDistanceRange",
////
////                // Image props
////                "pixelX: $pixelX",
////                "pixelY: $pixelY",
////                "orientation: $orientation",
////                "xResolution: $xResolution",
////                "yResolution: $yResolution",
////                "resolutionUnit: $resolutionUnit",
////                "colorSpace: $colorSpace",
////                "componentsConfig: $componentsConfig",
////                "compressedBitsPerPixel: $compressedBitsPerPixel",
////                "imageDescription: $imageDescription",
////                "userComment: ${userComment.take(100)}", // Truncate long comments
////                "imageWidth: $imageWidth",
////                "imageLength: $imageLength",
////
////                // GPS (sanitized - hash raw coords if needed for privacy)
////                "gpsLat: ${if (gpsLat.isNotEmpty()) gpsLat.hashCode().toString(16).take(8) else ""}",
////                "gpsLatRef: $gpsLatRef",
////                "gpsLon: ${if (gpsLon.isNotEmpty()) gpsLon.hashCode().toString(16).take(8) else ""}",
////                "gpsLonRef: $gpsLonRef",
////                "gpsAltitude: $gpsAltitude",
////                "gpsAltitudeRef: $gpsAltitudeRef",
////                "gpsTimeStamp: $gpsTimeStamp",
////                "gpsDateStamp: $gpsDateStamp",
////                "gpsProcessingMethod: $gpsProcessingMethod",
////                "gpsAreaInformation: $gpsAreaInformation",
////                "gpsDifferential: $gpsDifferential",
////                "gpsSpeedRef: $gpsSpeedRef",
////                "gpsSpeed: $gpsSpeed",
////                "gpsTrackRef: $gpsTrackRef",
////                "gpsTrack: $gpsTrack",
////                "gpsImgDirectionRef: $gpsImgDirectionRef",
////                "gpsImgDirection: $gpsImgDirection",
////                "gpsDestLat: ${if (gpsDestLat.isNotEmpty()) gpsDestLat.hashCode().toString(16).take(8) else ""}",
////                "gpsDestLon: ${if (gpsDestLon.isNotEmpty()) gpsDestLon.hashCode().toString(16).take(8) else ""}"
////            )
////            session.log.d(TAG, "=== EXIF DEBUG [handler: ${objectInfo.handlerID}] ===\n" + logLines.joinToString("\n"))
//
//            // === BUILD UNIQUE KEY (prioritize high-entropy fields) ===
//            val primaryKey = imageUniqueId.ifEmpty {
//                "$dateTimeOriginal$subSecOriginal|$make|$model|$fNumber|$exposureTime|$iso|$focalLength"
//            }
//
//            session.log.d(TAG, "Generated unique key for ${objectInfo.handlerID}: $primaryKey or $imageUniqueId")
//            primaryKey
//        } catch (e: Exception) {
//            session.log.w(TAG, "EXIF parse failed for handler ${objectInfo.handlerID}", e)
//            return null
//        }
//    }

    private fun generateExifUniqueKeyFromBytes(partialBytes: ByteArray, objectInfo: ObjectInfo): String? {
        return try {
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
            val rawKey = "$dateTime|$subSec|$uniqueId|$make|$model|${objectInfo.objectCompressedSize}"
            rawKey.hashCode().toString(16)
        } catch (e: Exception) {
            session.log.w(TAG, "EXIF parse failed for handler ${objectInfo.handlerID}", e)
            null
        }
    }
    companion object {
        private const val TAG = "CanonCamera"
    }
}