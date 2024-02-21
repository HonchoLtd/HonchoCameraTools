package app.thehoncho.pronto.utils

import java.lang.reflect.Modifier

object PtpConstants {
    const val CanonVendorId = 0x04a9
    const val NikonVendorId = 0x04b0
    const val FujiFilmVendorId = 0x04CB
    const val SonyVendorId = 0x054C
    fun isCompatibleVendor(vendorId: Int): Boolean {
        return vendorId == CanonVendorId || vendorId == NikonVendorId
    }

    fun typeToString(type: Short): String {
        return constantToString(Type::class.java, type.toInt())
    }

    // sony C203 == Property,
    // PC Mode 1, Pc Mode 2, GetSDIO, PC Mode 3, GetSDIO
    // 1004, 9201-01, 9201-02, 9202-C8, 9201-03, 9202-C8, 9209 loop
    //#define PTP_EC_Sony_ObjectAdded			0xC201
    //#define PTP_EC_Sony_ObjectRemoved		0xC202
    //#define PTP_EC_Sony_PropertyChanged		0xC203
    // 02 C0 FF FF == Live View
    // 01 C0 FF FF
    fun operationToString(operation: Int): String {
        return constantToString(Operation::class.java, operation)
    }

    fun eventToString(event: Int): String {
        return constantToString(Event::class.java, event)
    }

    fun responseToString(response: Int): String {
        return constantToString(Response::class.java, response)
    }

    fun objectFormatToString(objectFormat: Int): String {
        return constantToString(ObjectFormat::class.java, objectFormat)
    }

    fun propertyToString(property: Int): String {
        return constantToString(Property::class.java, property)
    }

    fun datatypetoString(datatype: Int): String {
        return constantToString(Datatype::class.java, datatype)
    }

    fun getDatatypeSize(datatype: Int): Int {
        return when (datatype) {
            Datatype.int8, Datatype.uint8 -> 1
            Datatype.int16, Datatype.uint16 -> 2
            Datatype.int32, Datatype.uint32 -> 4
            Datatype.int64, Datatype.uint64 -> 8
            else -> throw UnsupportedOperationException()
        }
    }

    /**
     * Returns a string representation of the code field an PTP packet.
     */
    fun codeToString(type: Short, code: Short): String {
        return when (type) {
            Type.Command, Type.Data -> operationToString(code.toInt())
            Type.Response -> responseToString(code.toInt())
            Type.Event -> eventToString(code.toInt())
            else -> String.format("0x%04x", code)
        }
    }

    /**
     * Returns the name of the constant that has the specified `constant`
     * in the specified `clazz`.
     */
    fun constantToString(clazz: Class<*>, constant: Int): String {
        val hexString = String.format("0x%04x", constant)
        for (f in clazz.declaredFields) {
            if (f.type != Int::class.javaPrimitiveType || !Modifier.isStatic(f.modifiers) || !Modifier.isFinal(
                    f.modifiers
                )
            ) {
                continue
            }
            try {
                if (f.getInt(null) == constant) {
                    return f.name + "(" + hexString + ")"
                }
            } catch (e: Throwable) {
                // nop
                e.printStackTrace()
            }
        }
        return hexString
    }

    object Product {
        // TODO D60 seems not to have a unique id
        //public static final int NikonD700 = 0x041A; // Same as D300?
        const val NikonD300 = 0x041A
        const val NikonD300S = 0x0425
        const val NikonD5000 = 0x0423
        const val NikonD5100 = 0x0429
        const val NikonD7000 = 0x0428
        const val NikonD7100 = 0x0430
        const val NikonD80 = 0x0412
        const val NikonD200 = 0x0410
        const val NikonD3 = 0x041C
        const val NikonD3S = 0x0426
        const val NikonD3X = 0x0420
        const val NikonD40 = 0x0414
        const val NikonD90 = 0x0421
        const val NikonD700 = 0x0422
    }

    object Type {
        const val Undefined: Short= 0
        const val Command: Short = 1
        const val Data: Short = 2
        const val Response: Short = 3
        const val Event: Short = 4
    }

    object Operation {
        const val UndefinedOperationCode: Short = 0x1000
        const val GetDeviceInfo: Short = 0x1001
        const val OpenSession: Short = 0x1002
        const val CloseSession: Short = 0x1003
        const val GetStorageIDs: Short = 0x1004
        const val GetStorageInfo: Short = 0x1005
        const val GetNumObjects: Short = 0x1006
        const val GetObjectHandles: Short = 0x1007
        const val GetObjectInfo: Short = 0x1008
        const val GetObject: Short = 0x1009
        const val GetThumb: Short = 0x100A
        const val DeleteObject: Short = 0x100B
        const val SendObjectInfo: Short = 0x100C
        const val SendObject: Short = 0x100D
        const val InitiateCapture: Short = 0x100E
        const val FormatStore: Short = 0x100F
        const val ResetDevice: Short = 0x1010
        const val SelfTest: Short = 0x1011
        const val SetObjectProtection: Short = 0x1012
        const val PowerDown: Short = 0x1013
        const val GetDevicePropDesc: Short = 0x1014
        const val GetDevicePropValue: Short = 0x1015
        const val SetDevicePropValue: Short = 0x1016
        const val ResetDevicePropValue: Short = 0x1017
        const val TerminateOpenCapture: Short = 0x1018
        const val MoveObject: Short = 0x1019
        const val CopyObject: Short = 0x101A
        const val GetPartialObject: Short = 0x101B
        const val InitiateOpenCapture: Short = 0x101C
        const val NikonInitiateCaptureRecInSdram = 0x90C0
        const val NikonAfDrive = 0x90C1
        const val NikonChangeCameraMode = 0x90C2
        const val NikonDeleteImagesInSdram = 0x90C3
        const val NikonGetLargeThumb = 0x90C4
        const val NikonGetEvent: Int = 0x90C7
        const val NikonDeviceReady = 0x90C8
        const val NikonSetPreWbData = 0x90C9
        const val NikonGetVendorPropCodes: Int = 0x90CA
        const val NikonAfAndCaptureInSdram = 0x90CB
        const val NikonGetPicCtrlData = 0x90CC
        const val NikonSetPicCtrlData = 0x90CD
        const val NikonDeleteCustomPicCtrl = 0x90CE
        const val NikonGetPicCtrlCapability = 0x90CF
        const val NikonGetPreviewImage = 0x9200
        const val NikonStartLiveView = 0x9201
        const val NikonEndLiveView = 0x9202
        const val NikonGetLiveViewImage = 0x9203
        const val NikonMfDrive = 0x9204
        const val NikonChangeAfArea = 0x9205
        const val NikonAfDriveCancel = 0x9206
        const val NikonInitiateCaptureRecInMedia = 0x9207
        const val NikonGetObjectPropsSupported = 0x9801
        const val NikonGetObjectPropDesc = 0x9802
        const val NikonGetObjectPropValue = 0x9803
        const val NikonGetObjectPropList = 0x9805

        // Canon EOS
        const val EosTakePicture = 0x910F
        const val EosSetDevicePropValue = 0x9110
        const val EosSetPCConnectMode =
            0x9114 // Set Remote mode to make sure the Camera Connect as PTP not MTP
        const val EosSetEventMode = 0x9115
        const val EosEventCheck = 0x9116
        const val EosTransferComplete = 0x9117
        const val EosResetTransfer = 0x9119
        const val EosBulbStart = 0x9125
        const val EosBulbEnd = 0x9126
        const val EosGetDevicePropValue = 0x9127
        const val EosRemoteReleaseOn = 0x9128
        const val EosRemoteReleaseOff = 0x9129
        const val EosGetLiveViewPicture = 0x9153
        const val EosDriveLens = 0x9155
        const val EosGetObjectInfoEx = 0x9109

        // this get from the libPhoto2
        const val PTP_OC_CANON_EOS_GetStorageInfo = 0x9102
        const val PTP_OC_CANON_EOS_GetObjectInfo = 0x9103
        const val PTP_OC_CANON_EOS_GetObject = 0x9104
        const val PTP_OC_CANON_EOS_DeleteObject = 0x9105
        const val PTP_OC_CANON_EOS_FormatStore = 0x9106
        const val PTP_OC_CANON_EOS_GetPartialObject = 0x9107
        const val PTP_OC_CANON_EOS_GetDeviceInfoEx = 0x9108

        // Sony
        const val PTP_OC_SONY_SetControlDeviceA = 0x9205
        const val PTP_OC_SONY_SetControlDeviceB = 0x9207
        const val PTP_OC_SONY_SDIOConnect = 0x9201
        const val PTP_OC_SONY_GetAllDevicePropData =
            0x9209 // This will be get data and event if there is event included
        const val PTP_OC_SONY_GUESS = 0x9280
        const val PTP_OC_SONY = 0x9281
        const val PTP_OC_SONY_GetSDIOGetExtDeviceInfo = 0x9202
    }

    object Event {
        const val CancelTransaction = 0x4001
        const val ObjectAdded = 0x4002
        const val ObjectRemoved = 0x4003
        const val StoreAdded = 0x4004
        const val StoreRemoved = 0x4005
        const val DevicePropChanged = 0x4006
        const val ObjectInfoChanged = 0x4007
        const val DeviceInfoChanged = 0x4008
        const val RequestObjectTransfer = 0x4009
        const val StoreFull = 0x400A
        const val StorageInfoChanged = 0x400C
        const val CaptureComplete = 0x400D

        // Nikon
        const val NikonObjectAddedInSdram = 0xC101
        const val NikonCaptureCompleteRecInSdram = 0xC102
        const val NikonPreviewImageAdded = 0xC104

        // Canon EOS
        const val EosObjectAdded = 0xC181 // ? dir item request transfer or dir item created
        const val EosDevicePropChanged = 0xC189
        const val EosDevicePropDescChanged = 0xC18A
        const val EosCameraStatus = 0xC18B
        const val EosWillSoonShutdown = 0xC18D
        const val EosBulbExposureTime = 0xc194

        // Sony
        const val SonyObjectAdded = 0xC201 // 0xC201
    }

    object Response {
        const val Ok: Short = 0x2001
        const val GeneralError: Short = 0x2002
        const val SessionNotOpen: Short = 0x2003
        const val InvalidTransactionID: Short = 0x2004
        const val OperationNotSupported: Short = 0x2005
        const val ParameterNotSupported: Short = 0x2006
        const val IncompleteTransfer: Short = 0x2007
        const val InvalidStorageID: Short = 0x2008
        const val InvalidObjectHandle: Short = 0x2009
        const val DevicePropNotSupported: Short = 0x200A
        const val InvalidObjectFormatCode: Short = 0x200B
        const val StoreIsFull: Short = 0x200C
        const val ObjectWriteProtect: Short = 0x200D
        const val StoreReadOnly: Short = 0x200E
        const val AccessDenied: Short = 0x200F
        const val NoThumbnailPresent: Short = 0x2010
        const val PartialDeletion: Short = 0x2012
        const val StoreNotAvailable: Short = 0x2013
        const val SpecificationByFormatUnsupported: Short = 0x2014
        const val NoValidObjectInfo: Short = 0x2015
        const val DeviceBusy: Short = 0x2019
        const val InvalidParentObject: Short = 0x201A
        const val InvalidDevicePropFormat: Short = 0x201B
        const val InvalidDevicePropValue: Short = 0x201C
        const val InvalidParameter: Short = 0x201D
        const val SessionAlreadyOpen: Short = 0x201E
        const val TransferCancelled: Short = 0x201F
        const val SpecificationOfDestinationUnsupported: Short = 0x2020

        // Nikon ?
        const val HardwareError = 0xA001
        const val OutOfFocus = 0xA002
        const val ChangeCameraModeFailed = 0xA003
        const val InvalidStatus = 0xA004
        const val SetPropertyNotSupport = 0xA005
        const val WbPresetError = 0xA006
        const val DustReferenceError = 0xA007
        const val ShutterSpeedBulb = 0xA008
        const val MirrorUpSequence = 0xA009
        const val CameraModeNotAdjustFnumber = 0xA00A
        const val NotLiveView = 0xA00B
        const val MfDriveStepEnd = 0xA00C
        const val MfDriveStepInsufficiency = 0xA00E
        const val InvalidObjectPropCode = 0xA801
        const val InvalidObjectPropFormat = 0xA802
        const val ObjectPropNotSupported = 0xA80A

        // Canon EOS
        const val EosUnknown_MirrorUp = 0xA102 // ?
    }

    object ObjectFormat {
        const val UnknownNonImageObject = 0x3000
        const val Association = 0x3001
        const val Script = 0x3002
        const val Executable = 0x3003
        const val Text = 0x3004
        const val HTML = 0x3005
        const val DPOF = 0x3006
        const val AIFF = 0x3007
        const val WAV = 0x3008
        const val MP3 = 0x3009
        const val AVI = 0x300A
        const val MPEG = 0x300B
        const val ASF = 0x300C
        const val UnknownImageObject = 0x3800
        const val EXIF_JPEG = 0x3801
        const val TIFF_EP = 0x3802
        const val FlashPix = 0x3803
        const val BMP = 0x3804
        const val CIFF = 0x3805
        const val Undefined_Reserved1 = 0x3806
        const val GIF = 0x3807
        const val JFIF = 0x3808
        const val PCD = 0x3809
        const val PICT = 0x380A
        const val PNG = 0x380B
        const val Undefined_Reserved2 = 0x380C
        const val TIFF = 0x380D
        const val TIFF_IT = 0x380E
        const val JP2 = 0x380F
        const val JPX = 0x3810

        // Canon
        const val EosCRW = 0xb101
        const val EosCRW3 = 0xb103
        const val EosMOV = 0xb104
    }

    object Property {
        // PTP
        const val UndefinedProperty = 0x5000
        const val BatteryLevel = 0x5001
        const val FunctionalMode = 0x5002
        const val ImageSize = 0x5003
        const val CompressionSetting = 0x5004
        const val WhiteBalance = 0x5005
        const val RGBGain = 0x5006
        const val FNumber = 0x5007 // Aperture Value
        const val FocalLength = 0x5008
        const val FocusDistance = 0x5009
        const val FocusMode = 0x500A
        const val ExposureMeteringMode = 0x500B
        const val FlashMode = 0x500C
        const val ExposureTime = 0x500D // Shutter Speed
        const val ExposureProgramMode = 0x500E
        const val ExposureIndex = 0x500F // ISO Speed
        const val ExposureBiasCompensation = 0x5010
        const val DateTime = 0x5011
        const val CaptureDelay = 0x5012
        const val StillCaptureMode = 0x5013
        const val Contrast = 0x5014
        const val Sharpness = 0x5015
        const val DigitalZoom = 0x5016
        const val EffectMode = 0x5017
        const val BurstNumber = 0x5018
        const val BurstInterval = 0x5019
        const val TimelapseNumber = 0x501A
        const val TimelapseInterval = 0x501B
        const val FocusMeteringMode = 0x501C
        const val UploadURL = 0x501D
        const val Artist = 0x501E
        const val CopyrightInfo = 0x501F

        // MTP/Microsoft
        const val MtpDeviceFriendlyName = 0xD402
        const val MtpSessionInitiatorInfo = 0xD406
        const val MtpPerceivedDeviceType = 0xD407

        // Canon EOS
        const val EosApertureValue = 0xD101
        const val EosShutterSpeed = 0xD102
        const val EosIsoSpeed = 0xD103
        const val EosExposureCompensation = 0xD104
        const val EosShootingMode = 0xD105
        const val EosDriveMode = 0xD106
        const val EosMeteringMode = 0xD107
        const val EosAfMode = 0xD108
        const val EosWhitebalance = 0xD109
        const val EosColorTemperature = 0xD10A
        const val EosPictureStyle = 0xD110
        const val EosAvailableShots = 0xD11B
        const val EosEvfOutputDevice = 0xD1B0
        const val EosEvfMode = 0xD1B3
        const val EosEvfWhitebalance = 0xD1B4
        const val EosEvfColorTemperature = 0xD1B6

        // Nikon
        const val NikonShutterSpeed = 0xD100
        const val NikonFocusArea = 0xD108
        const val NikonWbColorTemp = 0xD01E
        const val NikonRecordingMedia = 0xD10B
        const val NikonExposureIndicateStatus = 0xD1B1
        const val NikonActivePicCtrlItem = 0xD200
        const val NikonEnableAfAreaPoint = 0xD08D
    }

    object Datatype {
        const val int8 = 0x0001
        const val uint8 = 0x002
        const val int16 = 0x003
        const val uint16 = 0x004
        const val int32 = 0x005
        const val uint32 = 0x006
        const val int64 = 0x007
        const val uint64 = 0x008
        const val int128 = 0x009
        const val uint128 = 0x00A
        const val aint8 = 0x4001
        const val auint8 = 0x4002
        const val aint16 = 0x4003
        const val auInt16 = 0x4004
        const val aint32 = 0x4005
        const val auint32 = 0x4006
        const val aint64 = 0x4007
        const val auint64 = 0x4008
        const val aint128 = 0x4009
        const val auint128 = 0x400A
        const val string = 0x00
    }
}
