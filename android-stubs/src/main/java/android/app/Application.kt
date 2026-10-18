package android.app

import android.content.Context
import com.hikari.common.platform.PlatformPaths
import java.io.File

open class Application : Context {
    constructor() : super()

    constructor(baseDir: File) : super(baseDir)

    override fun getCacheDir(): File = PlatformPaths.cacheDir

    override fun getFilesDir(): File =
        File(PlatformPaths.appDataDir, "aniyomi-files").also { it.mkdirs() }
}
