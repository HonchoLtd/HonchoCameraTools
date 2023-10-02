package app.thehoncho.pronto.camera

import app.thehoncho.pronto.command.MultipleCommand
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectInfo

abstract class BaseCamera: MultipleCommand() {
    // This only called with Canon and Nikon so make sure filter with correctly
    // the list that return from this function will be download the image and call onImageDownloaded
    protected var onHandlersFilterCallback: (suspend (handlers: List<ObjectInfo>)->List<ObjectInfo>)? = null
    protected var onImageDownloadedCallback: (suspend (objectImage: ObjectImage)->Unit)? = null
    protected var onDeviceInfoCallback: (suspend (deviceInfo: DeviceInfo)->Unit)? = null

    fun setOnHandlersFilter(callback: suspend (handlers: List<ObjectInfo>)->List<ObjectInfo>) {
        this.onHandlersFilterCallback = callback
    }

    fun clearHandlersFilter() {
        this.onHandlersFilterCallback = null
    }

    fun setOnImageDownloaded(callback: suspend (objectImage: ObjectImage)->Unit) {
        this.onImageDownloadedCallback = callback
    }

    fun clearOnImageDownloaded() {
        this.onImageDownloadedCallback = null
    }

    fun setOnDeviceInfo(callback: suspend (deviceInfo: DeviceInfo)->Unit) {
        this.onDeviceInfoCallback = callback
    }

    fun clearOnDeviceInfo() {
        this.onDeviceInfoCallback = null
    }
}