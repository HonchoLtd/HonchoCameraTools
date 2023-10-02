package app.thehoncho.cameratools

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.coroutineScope
import app.thehoncho.cameratools.ui.theme.HonchoCameraToolsTheme
import app.thehoncho.pronto.Logger
import app.thehoncho.pronto.PTPUsbConnection
import app.thehoncho.pronto.Session
import app.thehoncho.pronto.Worker
import app.thehoncho.pronto.camera.CanonCamera
import app.thehoncho.pronto.camera.NikonCamera
import app.thehoncho.pronto.camera.SonyCamera
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ImageObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var permissionIntent: PendingIntent

    companion object {
        private const val ACTION_USB_PERMISSION = "app.thehoncho.cameratools.USB_PERMISSION"
        private const val TAG = "MainActivity"
    }

    private val _usbDevice = MutableStateFlow<UsbDevice?>(null)
    private val usbDevice = _usbDevice.asStateFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context, p1: Intent) {
            Log.d(TAG, "broadcast receiver with action ${p1.action}")
            // this only call if device not permission and we trigger permission for that
            if (ACTION_USB_PERMISSION == p1.action) {
                synchronized(this) {
                    val device = p1.getParcelableExtraCompact(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return@synchronized
                    // if we call with this device it will be act like new device so don't use this device
                    if (p1.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        // this device its permission got
                        Log.d(TAG, "permission its accept so we need to call view model")
                        // we find the device use this getUsbDevice to get the device same as we request the permission
                        val usbDevice = getUsbDevice(this@MainActivity)
                        Log.d(TAG, "onReceive: usb device permission is granted call the service to start")
                        _usbDevice.tryEmit(usbDevice)
                    } else {
                        Log.d(TAG, "permission its not accept so we not do anything")
                        _usbDevice.tryEmit(null)
                        // show message if the permission its denied for device
                    }
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED == p1.action) {
                synchronized(this) {
                    val device = p1.getParcelableExtraCompact(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return@synchronized
                    Log.d(TAG, "onReceive: usb detracted call the service to shutdown")
                    _usbDevice.tryEmit(null)
                    // viewModel.listUsbDeviceManual(this@MainActivity)
                    // stopService()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (intent == null) return

        if (intent.hasExtra(UsbManager.EXTRA_DEVICE)) {
            Log.d(TAG, "onNewIntent: usb device intent is found")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE // Should be FLAG_MUTABLE to let usbManager to update the extra with usbDevice
        )

        val usbDeviceIntent = intent.getParcelableExtraCompact(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (usbDeviceIntent != null) {
            Log.d(TAG, "onCreate: usb device intent is found")
        }

        setContent {
            HonchoCameraToolsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val coroutineScope = rememberCoroutineScope()
                    var uiState by remember { mutableStateOf(State()) }
                    var sonyImage by remember { mutableStateOf(0) }
                    val currentUsbDevice by usbDevice.collectAsState()
                    var worker by remember { mutableStateOf<Worker?>(null) }

                    LaunchedEffect(currentUsbDevice) {
                        coroutineScope.launch {
                            if (currentUsbDevice == null) {
                                worker?.stop()
                                worker = null
                                return@launch
                            }

                            val ptpUsbConnection = createPTPConnection(currentUsbDevice!!)
                            val logger = createLogger()
                            val session = Session(logger)
                            worker = Worker(ptpUsbConnection, logger).apply {
                                start()
                            }

                            when (currentUsbDevice!!.vendorId) {
                                1193 -> {
                                    // Canon
                                    val engine = CanonCamera(session)
                                    engine.setOnDeviceInfo { deviceInfo ->
                                        logger.d(TAG, "onDeviceInfo: $deviceInfo")
                                        uiState = uiState.copy(deviceInfo = deviceInfo)
                                    }
                                    engine.setOnImageDownloaded { imageObject ->
                                        Log.d(TAG, "onImageDownloaded: $imageObject")
                                        uiState = uiState.copy(lastImage = imageObject.image)
                                    }
                                    engine.setOnHandlersFilter { handlers ->
                                        uiState = uiState.copy(totalImage = handlers.size)
                                        return@setOnHandlersFilter handlers
                                            .lastOrNull()?.let { listOf(it) } ?: listOf()
                                    }
                                    worker!!.offer(engine)
                                }
                                1356 -> {
                                    // Sony
                                    val engine = SonyCamera(session)
                                    sonyImage = 0
                                    engine.setOnDeviceInfo { deviceInfo ->
                                        logger.d(TAG, "onDeviceInfo: $deviceInfo")
                                        uiState = uiState.copy(deviceInfo = deviceInfo)
                                    }
                                    engine.setOnImageDownloaded { imageObject ->
                                        Log.d(TAG, "onImageDownloaded: $imageObject")
                                        sonyImage += 1
                                        uiState = uiState.copy(lastImage = imageObject.image, totalImage = sonyImage)
                                    }
                                    worker!!.offer(engine)
                                }
                                1200 -> {
                                    // Nikon
                                    val engine = NikonCamera(session)
                                    engine.setOnDeviceInfo { deviceInfo ->
                                        logger.d(TAG, "onDeviceInfo: $deviceInfo")
                                        uiState = uiState.copy(deviceInfo = deviceInfo)
                                    }
                                    engine.setOnImageDownloaded { imageObject ->
                                        Log.d(TAG, "onImageDownloaded: $imageObject")
                                        uiState = uiState.copy(lastImage = imageObject.image)
                                    }
                                    engine.setOnHandlersFilter { handlers ->
                                        uiState = uiState.copy(totalImage = handlers.size)
                                        return@setOnHandlersFilter handlers
                                            .lastOrNull()?.let { listOf(it) } ?: listOf()
                                    }
                                    worker!!.offer(engine)
                                }
                                1227 -> {
                                    // Fuji
                                    throw IllegalStateException("Fuji not support yet")
                                }
                                else -> {
                                    throw IllegalStateException("Unknown vendor id")
                                }
                            }
                        }
                    }
                    MainScreenContent(uiState)
                }
            }
        }

        launchReceiverUsbPermission()
        lifecycle.coroutineScope.launch(Dispatchers.IO) {
            val usbDevice = getUsbDevice(this@MainActivity)
            requestPermissionDevice(usbDevice)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: unregister receiver")
        unregisterReceiver(usbReceiver)
    }

    private fun requestPermissionDevice(usbDevice: UsbDevice) {
        Log.d(TAG, "requestPermissionDevice for $usbDevice")
        val usbManager = getSystemService<UsbManager>() ?: return
        if (!usbManager.hasPermission(usbDevice)) {
            Log.d(TAG, "device not rejected so we need ask permission")
            usbManager.requestPermission(usbDevice, permissionIntent)
        } else {
            Log.d(TAG, "device already get permission we just need skip it")
            _usbDevice.tryEmit(usbDevice)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun launchReceiverUsbPermission() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun createLogger(): Logger {
        return object : Logger {
            override fun d(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.d(tag, message, throwable)
            }

            override fun e(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.e(tag, message, throwable)
            }

            override fun w(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.w(tag, message, throwable)
            }

            override fun i(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.i(tag, message, throwable)
            }

            override fun v(
                tag: String,
                message: String,
                throwable: Throwable?
            ) {
                Log.v(tag, message, throwable)
            }

        }
    }

    private fun getUsbDevice(context: Context): UsbDevice {
        var usbDevice: UsbDevice? = null

        while (usbDevice == null) {
            val usbManager = context.getSystemService<UsbManager>() ?: throw IllegalStateException("UsbManager is null")
            val collectionUsbDevice = usbManager.deviceList.values
                .filter { it.vendorId == 1193 || it.vendorId == 1200 || it.vendorId == 1356 || it.vendorId == 1227}
            usbDevice = if (collectionUsbDevice.isNotEmpty()) {
                collectionUsbDevice.getOrNull(0)
                // setDetectedUsbDevice(usbDevice)
            } else {
                null
                // setDetectedUsbDevice(null)
                // onUsbDisconnected()
            }
        }

        return usbDevice
    }

    private fun createPTPConnection(usbDevice: UsbDevice): PTPUsbConnection {
        val ptpUsbConnection = kotlin.runCatching { PTPUsbConnection.fromUsbDevice(this, usbDevice) }.getOrNull()
        requireNotNull(ptpUsbConnection) { "PTPUsbConnection is null" }

        return ptpUsbConnection
    }
}

data class State(
    val deviceInfo: DeviceInfo? = null,
    val totalImage: Int = 0,
    val lastImage: ImageObject? = null,
    val errorList: List<String> = listOf()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(state: State = State()) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(text = "Honcho Camera Tools") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxWidth()
                .scrollable(
                    orientation = Orientation.Vertical,
                    state = rememberScrollState()
                ).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Please make sure take photo as JPEG", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(16.dp))
            if (state.deviceInfo != null) {
                Text(text = "Device: (${state.deviceInfo.manufacture}) ${state.deviceInfo.model}", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(text = "Device: Unknown", style = MaterialTheme.typography.bodyMedium)
            }
            Text(text = "Total Image: ${state.totalImage}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Last Image:", style = MaterialTheme.typography.bodyMedium)
            if (state.lastImage != null) {
                key(state.lastImage) {
                    Image(bitmap = state.lastImage.bitmap.asImageBitmap(), contentDescription = "", modifier = Modifier.fillMaxWidth())
                }
            } else {
                Text(text = "No Image", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Error List:",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
            state.errorList.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

fun <T> Intent.getParcelableExtraCompact(key: String, clazz: Class<T>): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    { this.getParcelableExtra(key, clazz) } else { this.getParcelableExtra(key) }
}


@Preview(showBackground = true)
@Composable
fun MainScreenContentPreview() {
    HonchoCameraToolsTheme {
        MainScreenContent()
    }
}