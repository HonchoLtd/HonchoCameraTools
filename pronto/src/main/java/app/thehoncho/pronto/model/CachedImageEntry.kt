package app.thehoncho.pronto.model

data class CachedImageEntry(
    val objectInfo: ObjectInfo,
    val compositeKey: String,
    val handlerId: Int
)