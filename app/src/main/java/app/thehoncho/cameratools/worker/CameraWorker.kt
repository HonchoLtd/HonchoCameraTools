package app.thehoncho.cameratools.worker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.thehoncho.cameratools.utils.createLoggerDefault
import app.thehoncho.pronto.PTPUsbConnection
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.Worker
import app.thehoncho.pronto.camera.BaseCamera
import app.thehoncho.pronto.camera.CanonCamera
import app.thehoncho.pronto.camera.NikonCamera
import app.thehoncho.pronto.camera.SonyCamera
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

class CameraWorker(
    appContext: Context, params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as
                NotificationManager

    private var isRunning = false

    private var logger = createLoggerDefault()
    private var worker: Worker? = null
    private var sonyImage = 0

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context, p1: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == p1.action) {
                synchronized(this) {
                    // val device = p1.getParcelableExtraCompact(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return@synchronized
                    worker?.stop()
                    WorkManager.getInstance(applicationContext).cancelWorkById(id)
                    // viewModel.listUsbDeviceManual(this@MainActivity)
                    // stopService()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun launchReceiverUsbPermission() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(usbReceiver, filter, ComponentActivity.RECEIVER_NOT_EXPORTED)
        } else {
            applicationContext.registerReceiver(usbReceiver, filter)
        }
    }

    override suspend fun doWork(): Result {
        try {
            isRunning = true
            setForeground(createForegroundInfo("Running Camera Service"))
            launchReceiverUsbPermission()

            var usbDevice: UsbDevice? = null
            val usbManager = applicationContext.getSystemService<UsbManager>() ?: return Result.failure()
            usbManager.deviceList
                .values
                .filter { it.vendorId == 1193 || it.vendorId == 1200 || it.vendorId == 1356 || it.vendorId == 1227}.forEach {
                    if (usbManager.hasPermission(it)) {
                        usbDevice = it
                        return@forEach
                    }
                }

            if (usbDevice == null) {
                logger.e(TAG, "No USB device has permission")
                return Result.failure()
            }

            val ptpUsbConnection = createPTPConnection(usbDevice!!)
            val session = Session(logger)
            worker = Worker(ptpUsbConnection, logger).apply {
                start()
            }

            when (usbDevice!!.vendorId) {
                1193 -> {
                    // Canon
                    val engine = CanonCamera(session)
                    engine.setListener(object : BaseCamera.ListenerCamera {
                        override suspend fun onDeviceConnected(deviceInfo: DeviceInfo) {
                            logger.d(TAG, "onDeviceInfo: ${deviceInfo}")
                        }

                        override suspend fun onDeviceFailedToConnect(exception: Throwable) {
                            stopProcess()
                            // no need to force close, cause it will be trigger onStop after this
                        }

                        override suspend fun onError(exception: Throwable) {
                            stopProcess()
                            // no need to force close, cause it will be trigger onStop after this
                        }

                        override suspend fun onHandlersFilter(handlers: List<ObjectInfo>): List<ObjectInfo> {
                            logger.d(TAG, "onHandlersFilter: ${handlers.size}")
                            return handlers.lastOrNull()?.let { listOf(it) } ?: listOf()
                        }

                        override suspend fun onImageDownloaded(objectImage: ObjectImage) {
                            logger.d(TAG, "onImageDownloaded: ${objectImage.objectInfo.filename}")
                        }

                        override suspend fun onReady() {

                        }

                        override suspend fun onStop() {
                            // to make worker force stop and catch by worker
                            // throw Throwable("Camera stop")
                        }

                    })
//                    engine.setOnDeviceInfo(this::onDeviceInfo)
//                    engine.setOnImageDownloaded(this::onImageDownloaded)
//                    engine.setOnHandlersFilter(this::onHandlerFilter)
                    worker!!.offer(engine)
                }
                1356 -> {
                    // Sony
                    val engine = SonyCamera(session)
                    engine.setListener(object: BaseCamera.ListenerCamera {
                        override suspend fun onDeviceConnected(deviceInfo: DeviceInfo) {
                            logger.d(TAG, "onDeviceInfo: ${deviceInfo}")
                        }

                        override suspend fun onDeviceFailedToConnect(exception: Throwable) {
                            stopProcess()
                            // no need to force close, cause it will be trigger onStop after this
                        }

                        override suspend fun onError(exception: Throwable) {
                            stopProcess()
                            // no need to force close, cause it will be trigger onStop after this
                        }

                        override suspend fun onHandlersFilter(handlers: List<ObjectInfo>): List<ObjectInfo> {
                            // Sony never use this
                            return emptyList()
                        }

                        override suspend fun onImageDownloaded(objectImage: ObjectImage) {
                            logger.d(TAG, "onImageDownloaded: ${objectImage.objectInfo.filename}")
                        }

                        override suspend fun onReady() {

                        }

                        override suspend fun onStop() {
                            // to make worker force stop and catch by worker
                            // throw Throwable("Camera stop")
                        }

                    })
//                    engine.setOnDeviceInfo(this::onDeviceInfo)
//                    engine.setOnImageDownloaded(this::onImageDownloaded)
                    worker!!.offer(engine)
                }
                1200 -> {
                    // Nikon
                    val engine = NikonCamera(session)
                    engine.setListener(object : BaseCamera.ListenerCamera {
                        override suspend fun onDeviceConnected(deviceInfo: DeviceInfo) {
                            logger.d(TAG, "onDeviceInfo: ${deviceInfo}")
                        }

                        override suspend fun onDeviceFailedToConnect(exception: Throwable) {
                            stopProcess()
                            // no need to force close, cause it will be trigger onStop after this
                        }

                        override suspend fun onError(exception: Throwable) {
                            stopProcess()
                            // no need to force close, cause it will be trigger onStop after this
                        }

                        override suspend fun onHandlersFilter(handlers: List<ObjectInfo>): List<ObjectInfo> {
                            logger.d(TAG, "onHandlersFilter: ${handlers.size}")
                            return handlers.lastOrNull()?.let { listOf(it) } ?: listOf()
                        }

                        override suspend fun onImageDownloaded(objectImage: ObjectImage) {
                            logger.d(TAG, "onImageDownloaded: ${objectImage.objectInfo.filename}")
                        }

                        override suspend fun onReady() {

                        }

                        override suspend fun onStop() {
                            // to make worker force stop and catch by worker
                            // throw Throwable("Camera stop")
                        }

                    })
//                    engine.setOnDeviceInfo(this::onDeviceInfo)
//                    engine.setOnImageDownloaded(this::onImageDownloaded)
//                    engine.setOnHandlersFilter(this::onHandlerFilter)
                    worker!!.offer(engine)
                }
                1227 -> {
                    // Fuji
                    logger.e(TAG, "Fuji not support yet")
                    worker?.stop()
                    return Result.failure()
                }
                else -> {
                    logger.e(TAG, "Unknown vendor id")
                    worker?.stop()
                    return Result.failure()
                }
            }

            while (worker!!.isRunningProcess) {
                delay(1000)
            }

            return Result.success()
        } catch (ex: CancellationException) {
            logger.e(TAG, "doWork: ${ex.message}", ex)
            worker?.stop()
            worker = null
            return Result.failure()
        } catch (ex: Throwable) {
            logger.e(TAG, "doWork: ${ex.message}", ex)
            worker?.stop()
            worker = null
            return Result.failure()
        }finally {
            isRunning = false
        }
    }

    private fun createPTPConnection(usbDevice: UsbDevice): PTPUsbConnection {
        val ptpUsbConnection = kotlin.runCatching { PTPUsbConnection.fromUsbDevice(applicationContext, usbDevice) }.getOrNull()
        requireNotNull(ptpUsbConnection) { "PTPUsbConnection is null" }

        return ptpUsbConnection
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        val name = "Camera Service"
        val descriptionText = "This channel will show the notification for the camera service"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
        mChannel.description = descriptionText
        // Register the channel with the system. You can't change the importance
        // or other notification behaviors after this.
        notificationManager.createNotificationChannel(mChannel)
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        val title = "Running Camera Service"
        val cancel = "Stop"
        // This PendingIntent can be used to cancel the worker
        val intent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        // Create a Notification channel if necessary
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createChannel()
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Running Camera Service")
            .setTicker(title)
            .setContentText(progress)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, cancel, intent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(250295, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            logger.w(TAG, "ForegroundInfo: launch without service type")
            ForegroundInfo(250295, notification)
        }
    }
    
    private fun stopProcess() {
        runCatching {
            //jobDeviceInfo.cancel()
            //jobEventID.cancel()
            //jobUploadImage.cancel()
            worker?.stop()
            // worker = null
        }
        // createNormalNotificationDisconnected()
        // setForegroundAsync(deviceDisconnectedForegroundInfo())
    }

    companion object {
        const val DEVICE = "USB_DEVICE"
        private const val CHANNEL_ID = "Camera_Service"
        private const val ACTION_USB_PERMISSION = "app.thehoncho.cameratools.USB_PERMISSION"
        const val TAG = "CameraWorker"
    }
}