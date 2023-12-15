package app.thehoncho.pronto.camera

import app.thehoncho.pronto.command.MultipleCommand
import app.thehoncho.pronto.model.DeviceInfo
import app.thehoncho.pronto.model.ObjectImage
import app.thehoncho.pronto.model.ObjectInfo

abstract class BaseCamera: MultipleCommand() {
    // This only called with Canon and Nikon so make sure filter with correctly
    // the list that return from this function will be download the image and call onImageDownloaded
    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "setListener(listener)",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    protected var onHandlersFilterCallback: (suspend (handlers: List<ObjectInfo>)->List<ObjectInfo>)? = null
    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "clearListener()",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    protected var onImageDownloadedCallback: (suspend (objectImage: ObjectImage)->Unit)? = null
    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "setListener(listener)",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    protected var onDeviceInfoCallback: (suspend (deviceInfo: DeviceInfo)->Unit)? = null
    protected var listenerCamera: ListenerCamera? = null

    interface ListenerCamera {
        // This call when the camera info already get it,
        // so its mean the command already send to the camera
        suspend fun onDeviceConnected(deviceInfo: DeviceInfo)
        // call when the command to get device info failed
        // its mean something wrong with USB connection, or the port/cable not work correctly
        suspend fun onDeviceFailedToConnect(exception: Throwable)
        // call when the camera to filter the handler, handler return from this function will use
        // to download the image
        suspend fun onHandlersFilter(handlers: List<ObjectInfo>): List<ObjectInfo>
        // call when the image already downloaded from camera to the byte array
        // this will be called multiple time base on the return from onHandlersFilter
        suspend fun onImageDownloaded(objectImage: ObjectImage)
        // call when the camera ready to use, like the looping already start
        // so you can take picture from this
        suspend fun onReady()
        // call when the camera stop the loop, can be error or user stop the loop
        // if the error found, that will be call onError first and onStop after
        suspend fun onStop()
        // call when the error found, this can we continue loop and force to stop
        // depend on the error, if force stop happened onStop will be called after this
        suspend fun onError(exception: Throwable)
    }

    fun setListener(listenerCamera: ListenerCamera) {
        this.listenerCamera = listenerCamera
    }

    fun clearListener() {
        this.listenerCamera = null
    }

    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "setListener(listener)",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    fun setOnHandlersFilter(callback: suspend (handlers: List<ObjectInfo>)->List<ObjectInfo>) {
        this.onHandlersFilterCallback = callback
    }

    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "clearListener()",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    fun clearHandlersFilter() {
        this.onHandlersFilterCallback = null
    }

    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "setListener(listener)",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    fun setOnImageDownloaded(callback: suspend (objectImage: ObjectImage)->Unit) {
        this.onImageDownloadedCallback = callback
    }

    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "clearListener()",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    fun clearOnImageDownloaded() {
        this.onImageDownloadedCallback = null
    }

    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "setListener(listener)",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    fun setOnDeviceInfo(callback: suspend (deviceInfo: DeviceInfo)->Unit) {
        this.onDeviceInfoCallback = callback
    }

    @Deprecated(
        "Use setListener instead",
        ReplaceWith(
            "clearListener()",
            "app.thehoncho.pronto.camera.BaseCamera"
        )
    )
    fun clearOnDeviceInfo() {
        this.onDeviceInfoCallback = null
    }
}