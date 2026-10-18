package eu.kanade.tachiyomi

object AppInfo {
    fun getVersionCode(): Int =
        System.getProperty("hikari.versionCode")?.toIntOrNull() ?: 1

    fun getVersionName(): String =
        System.getProperty("hikari.version")?.takeIf { it.isNotBlank() } ?: "0.0.0"

    fun getSupportedImageMimeTypes(): List<String> = listOf(
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/avif",
    )
}
