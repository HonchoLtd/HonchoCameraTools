package app.thehoncho.pronto

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.Objects

class PTPUsbConnection(
    private val usbDeviceConnection: UsbDeviceConnection,
    usbInterface: UsbInterface
) {
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var endPointEv: UsbEndpoint? = null

    init {
        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            Log.i(TAG, String.format("Address: %d", endpoint.address))
            Log.i(TAG, String.format("Attribute: %d", endpoint.attributes))
            Log.i(TAG, String.format("Direction: %d", endpoint.direction))
            Log.i(TAG, String.format("Number: %d", endpoint.endpointNumber))
            Log.i(TAG, String.format("Type: %d", endpoint.type))
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    endpointIn = endpoint
                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    endpointOut = endpoint
                }
            }
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                endPointEv = endpoint
            }
        }
        Objects.requireNonNull(endpointIn, "input endpoint is null")
        Objects.requireNonNull(endpointOut, "output endpoint is null")
        Objects.requireNonNull(usbDeviceConnection, "device connection is null")
    }

    val maxPacketInSize: Int
        get() = endpointIn!!.maxPacketSize
    val maxPacketOutSize: Int
        get() = endpointOut!!.maxPacketSize

    fun close() {
        usbDeviceConnection.close()
    }

    fun generatedUsbRequest(): UsbRequest {
        val usbRequest = UsbRequest()
        return if (usbRequest.initialize(usbDeviceConnection, endpointIn)) {
            usbRequest
        } else {
            throw Exception("initialize usb request failed")
        }
    }

    fun requestWait(): UsbRequest {
        return usbDeviceConnection.requestWait()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun requestWait(timeout: Long): UsbRequest {
        return usbDeviceConnection.requestWait(timeout)
    }

    fun transferOut(buffer: ByteArray?, length: Int, timeout: Int): Int {
        return usbDeviceConnection.bulkTransfer(endpointOut, buffer, length, timeout)
    }

    fun transferIn(buffer: ByteArray?, maxLength: Int, timeout: Int): Int {
        return usbDeviceConnection.bulkTransfer(endpointIn, buffer, maxLength, timeout)
    }

    fun transferInEvent(buffer: ByteArray?, maxLength: Int, timeout: Int): Int {
        return usbDeviceConnection.bulkTransfer(endPointEv, buffer, maxLength, timeout)
    }

    companion object {
        fun fromUsbDevice(context: Context, usbDevice: UsbDevice): PTPUsbConnection {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val usbInterface = usbDevice.getInterface(0)
            val deviceConnection = usbManager.openDevice(usbDevice)
            deviceConnection.claimInterface(usbInterface, true)
            return PTPUsbConnection(deviceConnection, usbInterface)
        }

        const val TAG = "PTPUsbConnection"
    }
}
