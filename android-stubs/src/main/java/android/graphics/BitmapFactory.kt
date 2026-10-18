package android.graphics

object BitmapFactory {
    fun decodeStream(stream: java.io.InputStream?): Bitmap? = null

    fun decodeByteArray(data: ByteArray?, offset: Int, length: Int): Bitmap? = null

    fun decodeFile(path: String?): Bitmap? = null
}
