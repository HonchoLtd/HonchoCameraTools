package app.thehoncho.pronto.model

import app.thehoncho.pronto.utils.PacketUtil
import app.thehoncho.pronto.utils.PtpConstants
import java.nio.ByteBuffer
import java.util.Arrays

class DeviceInfo {
    var standardVersion: Short = 0
    var vendorExtensionId = 0
    var vendorExtensionVersion: Short = 0
    var vendorExtensionDesc: String? = null
    var functionalMode: Short = 0
    lateinit var operationsSupported: IntArray
    lateinit var eventsSupported: IntArray
    lateinit var devicePropertiesSupported: IntArray
    lateinit var captureFormats: IntArray
    lateinit var imageFormats: IntArray
    var manufacture: String? = null
    var model: String? = null
    var deviceVersion: String? = null
    var serialNumber: String? = null

    constructor(b: ByteBuffer, length: Int) {
        decode(b, length)
    }

    constructor()

    fun decode(b: ByteBuffer, length: Int) {
        standardVersion = b.short
        vendorExtensionId = b.int
        vendorExtensionVersion = b.short
        vendorExtensionDesc = PacketUtil.readString(b)
        functionalMode = b.short
        operationsSupported = PacketUtil.readU16Array(b)
        eventsSupported = PacketUtil.readU16Array(b)
        devicePropertiesSupported = PacketUtil.readU16Array(b)
        captureFormats = PacketUtil.readU16Array(b)
        imageFormats = PacketUtil.readU16Array(b)
        manufacture = PacketUtil.readString(b)
        model = PacketUtil.readString(b)
        deviceVersion = PacketUtil.readString(b)
        serialNumber = PacketUtil.readString(b)
    }

    fun encode(b: ByteBuffer) {
        b.putShort(standardVersion)
        b.putInt(vendorExtensionId)
        b.putInt(vendorExtensionVersion.toInt())
        PacketUtil.writeString(b, "")
        b.putShort(functionalMode)
        PacketUtil.writeU16Array(b, IntArray(0))
        PacketUtil.writeU16Array(b, IntArray(0))
        PacketUtil.writeU16Array(b, IntArray(0))
        PacketUtil.writeU16Array(b, IntArray(0))
        PacketUtil.writeU16Array(b, IntArray(0))
        PacketUtil.writeString(b, "")
        PacketUtil.writeString(b, "")
        PacketUtil.writeString(b, "")
    }

    override fun toString(): String {
        // Changes here have to reflect changes in PtpConstants.main()
        val b = StringBuilder()
        b.append("DeviceInfo\n")
        b.append("StandardVersion: ").append(standardVersion.toInt()).append('\n')
        b.append("VendorExtensionId: ").append(vendorExtensionId).append('\n')
        b.append("VendorExtensionVersion: ").append(vendorExtensionVersion.toInt()).append('\n')
        b.append("VendorExtensionDesc: ").append(vendorExtensionDesc).append('\n')
        b.append("FunctionalMode: ").append(functionalMode.toInt()).append('\n')
        appendU16Array(
            b, "OperationsSupported",
            PtpConstants.Operation::class.java, operationsSupported
        )
        appendU16Array(
            b, "EventsSupported",
            PtpConstants.Event::class.java, eventsSupported
        )
        appendU16Array(
            b, "DevicePropertiesSupported",
            PtpConstants.Property::class.java, devicePropertiesSupported
        )
        appendU16Array(
            b, "CaptureFormats",
            PtpConstants.ObjectFormat::class.java, captureFormats
        )
        appendU16Array(
            b, "ImageFormats",
            PtpConstants.ObjectFormat::class.java, imageFormats
        )
        b.append("Manufacture: ").append(manufacture).append('\n')
        b.append("Model: ").append(model).append('\n')
        b.append("DeviceVersion: ").append(deviceVersion).append('\n')
        b.append("SerialNumber: ").append(serialNumber).append('\n')
        return b.toString()
    }

    companion object {
        private fun appendU16Array(b: StringBuilder, name: String, cl: Class<*>, a: IntArray) {
            Arrays.sort(a)
            b.append(name).append(":\n")
            for (i in a.indices) {
                b.append("    ").append(PtpConstants.constantToString(cl, a[i])).append('\n')
            }
        }
    }
}
