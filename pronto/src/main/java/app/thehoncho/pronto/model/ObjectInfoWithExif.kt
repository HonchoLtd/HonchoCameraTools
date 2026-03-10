package app.thehoncho.pronto.model

/**
 * Minimal wrapper for database deduplication check (matches iOS ObjectInfoWithExif)
 * Library passes this to app layer; app decides how to build unique key
 */
data class ObjectImageWithExif(
    val objectInfo: ObjectInfo,
    val exifKey: String?  // Nullable: null if EXIF extraction failed or camera doesn't support it
)