package app.thehoncho.pronto

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Parcelable
import android.util.Log
import java.util.Locale
import java.util.Objects

class PTPConnection {
    var usbManager: UsbManager? = null
        private set
    private val context: Context?
    val devicesList = ArrayList<UsbDevice?>()

    constructor(context: Context) {
        this.context = context
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList: Collection<UsbDevice> = usbManager!!.deviceList.values
        Log.d(
            TAG,
            String.format(
                Locale.ENGLISH,
                "PTPConnection: device found with size %d",
                deviceList.size
            )
        )
        devicesList.clear()
        for (device in deviceList) {
            Log.d(
                TAG, String.format(
                    Locale.ENGLISH,
                    "PTPConnection: device id: %d, name: %s, manufactureName: %s, productID: %s, productName: %s, serialNumber: %s, vendorID: %s",
                    device.deviceId,
                    device.deviceName,
                    device.manufacturerName,
                    device.productId,
                    device.productName,
                    device.serialNumber,
                    device.vendorId
                )
            )
            devicesList.add(device)
        }
    }

    constructor(context: Context?, intent: Intent) {
        this.context = context
        devicesList.clear()
        val device = intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_DEVICE) as UsbDevice?
        Log.d(
            TAG, String.format(
                Locale.ENGLISH,
                "PTPConnection: device id: %d, name: %s, manufactureName: %s, productID: %s, productName: %s, serialNumber: %s, vendorID: %s",
                device!!.deviceId,
                device.deviceName,
                device.manufacturerName,
                device.productId,
                device.productName,
                device.serialNumber,
                device.vendorId
            )
        )
        devicesList.add(device)
    }

    fun toUsbConnection(usbDevice: UsbDevice): PTPUsbConnection {
        if (usbManager == null && context != null) {
            usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        }
        Objects.requireNonNull(usbManager)
        val usbInterface = usbDevice.getInterface(0)
        val deviceConnection = usbManager!!.openDevice(usbDevice)
        deviceConnection.claimInterface(usbInterface, true)
        return PTPUsbConnection(deviceConnection, usbInterface)
    }

    companion object {
        private const val TAG = "PTPConnection"
    }
}
