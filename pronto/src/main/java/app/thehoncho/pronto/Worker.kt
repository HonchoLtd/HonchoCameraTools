package app.thehoncho.pronto

import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import app.thehoncho.pronto.command.Command
import app.thehoncho.pronto.command.MultipleCommand
import app.thehoncho.pronto.command.PTPAction
import app.thehoncho.pronto.utils.PacketUtil
import app.thehoncho.pronto.utils.PtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

class Worker(
    private val connection: PTPUsbConnection,
    private val logger: Logger
): CoroutineScope, WorkerExecutor {
    override val coroutineContext: CoroutineContext
        get() = SupervisorJob() + Dispatchers.IO

    // private val isRunning = false
    private var usbRequest1: UsbRequest? = null
    private var usbRequest2: UsbRequest? = null
    private var usbRequest3: UsbRequest? = null
    private val bigInSize = 0x4000

    // buffers for async data io, size bigInSize
    private var bigIn1: ByteBuffer? = null
    private var bigIn2: ByteBuffer? = null
    private var bigIn3: ByteBuffer? = null

    // buffer for small packets like command and response
    private var smallIn: ByteBuffer? = null

    // buffer containing full data out packet for processing
    private val fullInSize = 0x4000
    private var fullIn: ByteBuffer? = null

    var isRunningProcess: Boolean = false
        private set

    private val queue: LinkedBlockingQueue<PTPAction> = LinkedBlockingQueue<PTPAction>()

    fun offer(action: PTPAction) {
        queue.offer(action)
    }

    fun start() {
        launch {
            isRunningProcess = true
            usbRequest1?.close()
            usbRequest2?.close()
            usbRequest3?.close()

            try {
                require(connection.maxPacketInSize > 0) { "maxPacketInSize is invalid" }
                require(connection.maxPacketOutSize > 0) { "maxPacketOutSize is invalid" }

                smallIn = ByteBuffer
                    .allocate(connection.maxPacketInSize.coerceAtLeast(connection.maxPacketOutSize)).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                    }

                bigIn1 = ByteBuffer.allocate(bigInSize).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                bigIn2 = ByteBuffer.allocate(bigInSize).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }
                bigIn3 = ByteBuffer.allocate(bigInSize).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }

                fullIn = ByteBuffer.allocate(fullInSize).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                }

                usbRequest1 = connection.generatedUsbRequest()
                usbRequest2 = connection.generatedUsbRequest()
                usbRequest3 = connection.generatedUsbRequest()

                while (isActive) {
                    synchronized(this) {
                        if (!isActive) return@launch
                    }

                    val action = queue.poll() ?: continue
                    if (action is Command) handleCommand(action)
                    else if (action is MultipleCommand) action.execute(this@Worker)
                }
            } catch (e: Exception) {
                logger.e(TAG, e.message ?: "Unknown error")
            } finally {
                isRunningProcess = false
                usbRequest1?.close()
                usbRequest2?.close()
                usbRequest3?.close()
            }
        }
    }

    override fun handleCommand(command: Command) {
        val b = ByteBuffer.allocate(connection.maxPacketOutSize)
        b.order(ByteOrder.LITTLE_ENDIAN)
        b.position(0)

        command.encodeCommand(b)
        val outLen = b.position()

        // Send command to device
        logger.d(TAG, "Sending packet")

        val res = connection.transferOut(b.array(), outLen, TIMEOUT)
        if (res < outLen) {
            logger.e(TAG, "handleCommand: ", Throwable("res and outLen not in same size"))

            command.onError("res and outLen not in same size")
            // This make the camera stop request
            // this prob that the usb connection is cut loss so we just need to force stop worker
            stop()
            return
        }
        logger.d(TAG, "Sending packet done with data size $res")
        Log.d(TAG, PacketUtil.hexDumpToString(b.array(), 0, res))

        logger.d(TAG, "Reading packet")
        while (!command.hasResponseReceived) {
            val maxPacketSize: Int = connection.maxPacketInSize
            val inputBytes = requireNotNull(smallIn) { "smallIn is null" }
            inputBytes.position(0)

            // if the data come its events type we save to events and request again
            // if the data come its data type we start get length to get other data
            // id the data come its response type its end of the data
            val read = connection.transferIn(inputBytes.array(), maxPacketSize, 30000)
            Log.d(TAG, String.format("Packet found with size %d", read))
            Log.d(TAG, PacketUtil.hexDumpToString(inputBytes.duplicate().array(), 0, read))
            if (read < 12) {
                val errorMessage = String.format(
                    Locale.ENGLISH,
                    "Couldn't read header packet, only %d bytes available!",
                    read
                )
                logger.e(TAG, errorMessage)

                command.onError(errorMessage)
                continue
            }
            val length = inputBytes.int
            val type = inputBytes.short
            logger.d(TAG,
                String.format(
                    "Fist income packet found with read %d length %d and type %s",
                    read,
                    length,
                    PtpConstants.typeToString(type)
                )
            )
            if (type == PtpConstants.Type.Event && length == 16) {
                // We should send to events and start request again this usually not to much data
                // just need read one no need loop but need to fix it later to handle huge
                logger.d(TAG, "[EVENT] Try to send byte to command for parse")

                if (read == length) {
                    // event capture send to command
                    val sender = ByteBuffer.allocate(length)
                    inputBytes.position(0)
                    inputBytes[sender.array(), 0, length]

                    command.receivedRead(sender)
                } else {
                    logger.e(TAG, "[EVENT] message not same as read length, so we skip send byte to command")
                }
            } else if (type == PtpConstants.Type.Data) {
                // This start data so we need to request in looping
                // we need to use loop in here to request with multiple
                logger.d(TAG, "[DATA] Try to send byte to read parallel, this is data type")

                val sender = ByteBuffer.allocate(read)
                inputBytes.position(0)
                inputBytes[sender.array(), 0, read]

                logger.d(TAG, "[DATA] Start read data parallel")

                val destination = ByteBuffer.allocate(length)
                readDataParallel(sender, read, destination)

                logger.d(TAG, "[DATA] Success read data parallel, send to command")

                command.receivedRead(destination)
            } else if (type == PtpConstants.Type.Response && (length == 12 || length == 16 || length == 20)) {
                // This is end of data so we need to stop
                // length = 16 its for Nikon
                // length = 20 its for Canon Latest device
                logger.d(TAG, "[RESPONSE] $length Try to send byte to command for parse")

                if (read == length) {
                    val sender = ByteBuffer.allocate(length)
                    inputBytes.position(0)
                    inputBytes[sender.array(), 0, length]

                    command.receivedRead(sender)

                    logger.d(TAG, "[RESPONSE] $length Success send byte to command for parse, command success")
                } else {
                    logger.e(TAG, "[RESPONSE] $length message not same as read length")
                }
            } else {
                // check this maybe its response from partial
                // TODO this only to other scenario for partial objects
                logger.w(TAG, "[W] Whats this data come from?, try parse act response")

                if (type == PtpConstants.Type.Response) {
                    // This will get response for partial objects for Canon
                    logger.w(TAG, "[W] This is partial objects response, please check why come in here")
                    logger.w(TAG, "[W] Force to send byte to command for parse")

                    if (read == length) {
                        val sender = ByteBuffer.allocate(length)
                        inputBytes.position(0)
                        inputBytes[sender.array(), 0, length]

                        command.receivedRead(sender)

                        logger.w(TAG, "[W] Success send byte to command for parse, command success")
                    } else {
                        logger.e(TAG, "[W] Response message not same as read length")
                    }
                } else {
                    logger.e(TAG, "[W] This is unknown bytes, please check why come in here")
                }
            }
        }
    }

    override fun isRunning(): Boolean {
        return isActive && isRunningProcess
    }

    override fun getConnection(): PTPUsbConnection {
        return connection
    }

    private fun readDataParallel(byteBuffer: ByteBuffer, read: Int, destination: ByteBuffer) {
        // byte buffer its size of the item that need to read
        val length = destination.array().size
        byteBuffer.position(0)
        destination.order(ByteOrder.LITTLE_ENDIAN)
        destination.position(0)

        logger.d(TAG, String.format("readDataParallel with destination size %d", length))

        // we put first byte from usb
        destination.put(byteBuffer)
        var totalBytes: Int = read
        val maxPacketSize = 0x4000

        while (totalBytes < length) {
            // Read its not enough so the data will be partial
            // let say A need 10000 data but we just read 512 that max the data can hold it
            // 0x4000 that max the data can read by queue with UsbRequest
            // and B just need 1000 - 512 = 488 if the 488 the sizeB need to read
            val sizeA = maxPacketSize.coerceAtMost(length - totalBytes)
            val sizeB = 0.coerceAtLeast(maxPacketSize.coerceAtMost(length - totalBytes - sizeA))
            val sizeC = 0.coerceAtLeast(maxPacketSize.coerceAtMost(length - totalBytes - sizeA - sizeB))

            val byteA = ByteBuffer.allocate(sizeA)
            byteA.order(ByteOrder.LITTLE_ENDIAN)
            val byteB = ByteBuffer.allocate(sizeB)
            byteB.order(ByteOrder.LITTLE_ENDIAN)
            val byteC = ByteBuffer.allocate(sizeC)
            byteC.order(ByteOrder.LITTLE_ENDIAN)

            logger.d(
                TAG,
                String.format("readDataParallel Current size A %d, B %d, C %d", sizeA, sizeB, sizeC)
            )

            val usbReq1 = requireNotNull(usbRequest1) { "usbRequest1 is null" }
            val usbReq2 = requireNotNull(usbRequest2) { "usbRequest2 is null" }
            val usbReq3 = requireNotNull(usbRequest3) { "usbRequest3 is null" }

            if (sizeA > 0) {
                byteA.position(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    usbReq1.queue(byteA)
                } else {
                    usbReq1.queue(byteA, sizeA)
                }
            }
            if (sizeB > 0) {
                byteB.position(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    usbReq2.queue(byteB)
                } else {
                    usbReq2.queue(byteB, sizeB)
                }
            }
            if (sizeC > 0) {
                byteC.position(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    usbReq3.queue(byteC)
                } else {
                    usbReq3.queue(byteC, sizeC)
                }
            }
            if (sizeA > 0) {
                connection.requestWait()
                val sender = ByteBuffer.allocate(sizeA)
                byteA.position(0)
                byteA[sender.array(), 0, sizeA]
                destination.put(sender)
                totalBytes += sizeA

                logger.d(
                    TAG,
                    String.format(
                        "readDataParallel Total bytes read from A %d and destination size %d with totalBytes %d position %d",
                        sizeA,
                        destination.array().size,
                        totalBytes,
                        destination.position()
                    )
                )
            }
            if (sizeB > 0) {
                connection.requestWait()
                val sender = ByteBuffer.allocate(sizeB)
                byteB.position(0)
                byteB[sender.array(), 0, sizeB]
                destination.put(sender)
                totalBytes += sizeB

                logger.d(
                    TAG,
                    String.format(
                        "readDataParallel Total bytes read from B %d and destination size %d with totalBytes %d position %d",
                        sizeB,
                        destination.array().size,
                        totalBytes,
                        destination.position()
                    )
                )
            }
            if (sizeC > 0) {
                connection.requestWait()
                val sender = ByteBuffer.allocate(sizeC)
                byteC.position(0)
                byteC[sender.array(), 0, sizeC]
                destination.put(sender)
                totalBytes += sizeC

                logger.d(
                    TAG,
                    String.format(
                        "readDataParallel Total bytes read from C %d and destination size %d with totalBytes %d position %d",
                        sizeC,
                        destination.array().size,
                        totalBytes,
                        destination.position()
                    )
                )
            }
        }
        destination.position(0)
        if (totalBytes > 5 * read) {
            Log.d(
                TAG,
                PacketUtil.hexDumpToString(destination.duplicate().array(), 0, 5 * read)
            )
        }

        logger.d(
            TAG,
            String.format(
                "readDataParallel Success read data parallel with totalBytes %d and destination size %d",
                totalBytes,
                destination.array().size
            )
        )
    }

    fun stop() {
        isRunningProcess = false
        cancel()
    }

    companion object {
        private const val TAG = "Worker"
        private const val TIMEOUT = 30000
    }
}