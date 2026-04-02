package app.thehoncho.pronto.camera

import androidx.exifinterface.media.ExifInterface
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.WorkerExecutor
import app.thehoncho.pronto.command.general.CloseSessionCommand
import app.thehoncho.pronto.command.general.GetDeviceInfoCommand
import app.thehoncho.pronto.command.general.GetObjectCommand
import app.thehoncho.pronto.command.general.GetObjectInfoCommand
import app.thehoncho.pronto.command.general.OpenSessionCommand
import app.thehoncho.pronto.command.sony.SonyEventCheckCommand
import app.thehoncho.pronto.command.sony.SonyGetSDIOGetExtDeviceInfo
import app.thehoncho.pronto.command.sony.SonyRequestPCModeFirst
import app.thehoncho.pronto.command.sony.SonyRequestPCModeSecond
import app.thehoncho.pronto.command.sony.SonyRequestPCModeThird
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectImageWithExif
import app.thehoncho.pronto.model.ObjectInfo
import app.thehoncho.pronto.model.sony.SonyDevicePropDesc
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.ByteOrder
import java.util.Locale

class SonyCamera(private val session: Session) : BaseCamera() {
    private var isPartialSupport = false
    private var devicePropsDescMap = mutableMapOf<Short, SonyDevicePropDesc>()
    private var globalHandlerID = -16383
    private var _deviceInfo: DeviceInfo? = null

    private val localExifDatabaseExist = mutableSetOf<String>()
    private var isSkipAutoUpload = true

    // Models that return negative values for pending image flag
    private val minus0List = listOf(
        "ILCE-7SM2",
        "ILCE-7M3",
        "ILCE-7SM3",
        "ILCE-7RM4",
        "ILCE-7M2",
        "ILCE-7RM3",
        "ILCE-7S",
        "ILCE-7C",
    )

    override fun execute(executor: WorkerExecutor) = runBlocking {
        val deviceInfo = onConnecting(executor)
        if (deviceInfo == null) {
            session.log.e(TAG, "execute: failed when connecting")
            listenerCamera?.onDeviceFailedToConnect(Throwable("failed when get device info, please check the cable or port"))
            listenerCamera?.onStop()
            return@runBlocking
        }
        listenerCamera?.onDeviceConnected(deviceInfo)
        _deviceInfo = deviceInfo

        deviceInfo.operationsSupported.forEach { opt ->
            if (opt.toShort() == PtpConstants.Operation.GetPartialObject) {
                isPartialSupport = true
                return@forEach
            }
        }

        requestPtpMode(executor)
        listenerCamera?.onReady()

        var isFirstLoop = true

        while (executor.isRunning()) {
            val eventCheckCommand = SonyEventCheckCommand(session)
            executor.handleCommand(eventCheckCommand)

            eventCheckCommand.getResult().onFailure {
                session.log.w(TAG, "execute: failed when get event check")
                listenerCamera?.onError(Throwable("failed when get event check, please restart the camera"))
                listenerCamera?.onStop()
                return@runBlocking
            }

            val eventCheckContent = eventCheckCommand.getResult().getOrNull() ?: listOf<SonyDevicePropDesc>()
            val hasNewImage = checkInMemoryImage(eventCheckContent, deviceInfo)

            if (hasNewImage) {
                val objectImage = onDownloadImage(executor, globalHandlerID)
                if (objectImage == null) {
                    session.log.e(TAG, "getCommand: failed when download image")
                    continue
                }

                session.log.d(TAG, "✅ Download success | filename: ${objectImage.objectInfo.filename}")

                // Skip non-JPEG images
                if (objectImage.objectInfo.objectFormat != PtpConstants.ObjectFormat.EXIF_JPEG) {
                    session.log.d(TAG, "⚠️ Not JPEG, skip | format=0x${objectImage.objectInfo.objectFormat.toString(16)}")
                    continue
                }

                // Extract EXIF signature
                val exif = extractExifSignaturePartial(objectImage)

                val checksum = objectImage.image.bytes.contentHashCode()
                val size = objectImage.objectInfo.objectCompressedSize

                val fallbackKey = "${objectImage.objectInfo.filename}_$size"
                val baseKey = if (!exif.isNullOrEmpty()) exif else fallbackKey
                val signature = "${baseKey}_$checksum"

                val objectInfoWithExif = ObjectImageWithExif(objectImage.objectInfo, exif)
                val isExist = listenerCamera?.onIsImageAlreadyInDatabase(
                    objectInfoWithExif,
                    isSkipAutoUpload
                ) ?: false

                if (isExist) {
                    session.log.d(TAG, "🚫 SKIP: already in DB")
                    localExifDatabaseExist.add(signature)
                    continue
                }

                if (localExifDatabaseExist.contains(signature)) {
                    session.log.d(TAG, "🚫 SKIP: already in local cache")
                    continue
                }

                // Insert to cache right before callback
                localExifDatabaseExist.add(signature)

                // Deliver to app
                val enrichedImage = objectImage.copy(exifKey = exif)
                listenerCamera?.onImageDownloaded(enrichedImage)
            } else {
                delay((3000))
            }

            if (isFirstLoop) {
                session.log.d(TAG, "⚙️ First loop finished → disable skipAutoUpload")
                isFirstLoop = false
                isSkipAutoUpload = false
            }
        }

        listenerCamera?.onStop()
    }

    private fun checkInMemoryImage(propDesc: List<SonyDevicePropDesc>, deviceInfo: DeviceInfo): Boolean {
        var isImagePending = false
        propDesc.forEach { desc ->
            if (devicePropsDescMap.containsKey(desc.propCode)) {
                val oldDesc = devicePropsDescMap[desc.propCode]
                if (oldDesc != null && oldDesc.currentValue != desc.currentValue) {
                    if (oldDesc.propCode == (0xd215).toShort()) {
                        session.log.d(TAG, "checkInMemoryImage: ${oldDesc.currentValue} -> ${desc.currentValue}")
                    }
                    devicePropsDescMap[desc.propCode] = desc
                }

                if (oldDesc != null && oldDesc.propCode == (0xd215).toShort()) {
                    val currentValue = desc.currentValue
                    if (deviceInfo.model == "ILCE-7M4") {
                        if (currentValue != 0L) {
                            isImagePending = true
                        }
                    } else if (minus0List.contains(deviceInfo.model)) {
                        if (currentValue < 0) {
                            isImagePending = true
                        }
                    } else {
                        if (currentValue < 0) {
                            isImagePending = true
                        }
                    }
                }
            } else {
                devicePropsDescMap[desc.propCode] = desc
            }
        }
        return isImagePending
    }

    private fun onConnecting(worker: WorkerExecutor): DeviceInfo? {
        val closeSessionCommand = CloseSessionCommand(session)
        worker.handleCommand(closeSessionCommand)

        val openSessionCommand = OpenSessionCommand(session)
        worker.handleCommand(openSessionCommand)

        val getDeviceInfoCommand = GetDeviceInfoCommand(session)
        worker.handleCommand(getDeviceInfoCommand)

        return getDeviceInfoCommand.getResult().getOrNull()
    }

    private fun requestPtpMode(worker: WorkerExecutor) {
        val requestPCMode = SonyRequestPCModeFirst(session)
        worker.handleCommand(requestPCMode)

        val requestPCModeSecond = SonyRequestPCModeSecond(session)
        worker.handleCommand(requestPCModeSecond)

        val getSDIOGetExtDeviceInfo = SonyGetSDIOGetExtDeviceInfo(session)
        worker.handleCommand(getSDIOGetExtDeviceInfo)

        val requestPCModeThird = SonyRequestPCModeThird(session)
        worker.handleCommand(requestPCModeThird)
    }

    private fun onDownloadImage(worker: WorkerExecutor, handler: Int): ObjectImage? {
        val getObjectInfoCommand = GetObjectInfoCommand(session, handler)
        worker.handleCommand(getObjectInfoCommand)
        val objectInfo = getObjectInfoCommand.getResult().getOrNull()

        if (objectInfo == null) {
            session.log.e(TAG, "onDownloadImage: failed when download info image")
            return null
        }

        if (objectInfo.objectFormat != PtpConstants.ObjectFormat.EXIF_JPEG) {
            session.log.e(TAG, "onDownloadImage: object format its not the JPEG but ${objectInfo.objectFormat}")
        }

        return downloadImage(worker, handler, objectInfo)
    }

    private fun downloadImage(worker: WorkerExecutor, handler: Int, objectInfo: ObjectInfo): ObjectImage? {
        session.log.i(TAG, "downloadImage: start download image")
        val getObjectCommand = GetObjectCommand(session, handler)
        worker.handleCommand(getObjectCommand)
        val imageObject = getObjectCommand.getResult().getOrNull()

        if (imageObject == null) {
            session.log.e(TAG, "downloadImage: failed when download image data")
            return null
        }

        session.log.i(TAG, "downloadImage: finish download image")
        return ObjectImage(objectInfo, handler, imageObject)
    }

    private fun extractExifSignaturePartial(objectImage: ObjectImage): String? {
        return try {
            val exifKey = generateExifUniqueKeyFromBytes(objectImage)
            return exifKey
        } catch (e: Exception) {
            null
        }
    }

    private fun generateExifUniqueKeyFromBytes(objectImage: ObjectImage): String? {
        return try {
            val imageBytes = objectImage.image.bytes
            if (imageBytes.size < 128) {
                session.log.d(TAG, "⚠️ EXIF: file too small (${imageBytes.size}B) | ${objectImage.objectInfo.filename}")
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
                session.log.w(TAG, "⚠️ EXIF: missing required fields | model=$model | date=$dateTimeOriginal | filename=${objectImage.objectInfo.filename}")
                return null
            }

            val actualSubSec = subSecTime.ifEmpty {
                val millis = System.currentTimeMillis() % 1000
                String.format(Locale.US, "%03d", millis)
            }

            val key = "${make}_${model}_${dateTimeOriginal}_${actualSubSec}_${uniqueId}"
            return key

        } catch (e: Exception) {
            session.log.e(TAG, "❌ EXIF: exception | filename=${objectImage.objectInfo.filename} | error=${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "SonyCamera"
    }
}