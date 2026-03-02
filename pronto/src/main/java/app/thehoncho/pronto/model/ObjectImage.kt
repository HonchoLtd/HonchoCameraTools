package app.thehoncho.pronto.model

data class ObjectImage(
    val objectInfo: ObjectInfo,
    val handlerId: Int,
    val image: ImageObject,
    val cachedEntry: CachedImageEntry? = null
) {
    val compositeKey: String? get() = cachedEntry?.compositeKey
}