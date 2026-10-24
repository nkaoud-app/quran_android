package com.quran.mobile.recitation.natlu

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Natlu's recitation model (Quran-Lab's zipformer_p-arabic-v3, under its no-profit license; see
 * NATLU_NOTICE.md), downloaded on first use from the release Natlu downloads it from.
 */
object NatluModel {
  private const val NAME = "zipformer_p_arabic_v3.int8.onnx"
  private const val URL_STRING = "https://github.com/Iam-Muslim/Natlu/releases/download/models-latest/$NAME"
  private const val MIN_SIZE = 10L * 1024 * 1024

  const val DOWNLOAD_SIZE = "73 MB"

  private fun directory(context: Context) = File(context.filesDir, "natlu")

  fun file(context: Context): File = File(directory(context), NAME)

  fun isDownloaded(context: Context): Boolean = file(context).let { it.exists() && it.length() > MIN_SIZE }

  /** The model's phoneme units, copied out of the app's assets for the recognizer. */
  suspend fun tokens(context: Context): File = withContext(Dispatchers.IO) {
    val file = File(directory(context), "tokens.txt")
    if (!file.exists()) {
      file.parentFile?.mkdirs()
      val partial = File(file.path + ".part")
      context.assets.open("natlu/tokens.txt").use { input -> partial.outputStream().use { input.copyTo(it) } }
      check(partial.renameTo(file)) { "Could not save the model's tokens" }
    }
    file
  }

  /**
   * Downloads the model. [onProgress] gets the percentage done (or -1 when the size is unknown)
   * and the bytes so far, on the main thread, each time the percentage changes.
   */
  suspend fun download(context: Context, onProgress: (percent: Long, bytes: Long) -> Unit) =
    withContext(Dispatchers.IO) {
      val modelFile = file(context)
      val partial = File(modelFile.path + ".part")
      partial.parentFile?.mkdirs()
      val connection = URL(URL_STRING).openConnection() as HttpURLConnection
      try {
        connection.instanceFollowRedirects = true
        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
          partial.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            var lastShown = -2L
            while (true) {
              ensureActive()
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              done += read
              val percent = if (total > 0) done * 100 / total else -1
              if (percent != lastShown || total <= 0) {
                lastShown = percent
                withContext(Dispatchers.Main) { onProgress(percent, done) }
              }
            }
          }
        }
      } finally {
        connection.disconnect()
      }
      check(partial.length() > MIN_SIZE) { "incomplete download" }
      check(partial.renameTo(modelFile)) { "Could not save the model" }
    }
}
