package app.thehoncho.pronto.model

data class ObjectImage(
    val objectInfo: ObjectInfo,
    val handlerId: Int,
    val image: ImageObject,
    val exifKey: String? = null
)