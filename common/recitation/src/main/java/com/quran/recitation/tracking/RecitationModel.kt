package com.quran.recitation.tracking

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** The on-device recitation model, downloaded on first use (see tools/recitation-spike). */
object RecitationModel {
  private const val NAME = "recitation-s8-int8.onnx"
  private const val URL =
    "https://github.com/nkaoud-app/quran_android/releases/download/recitation-model-v1/$NAME"

  const val DOWNLOAD_SIZE = "93 MB"

  fun file(context: Context): File = File(context.filesDir, "recitation/$NAME")

  fun isDownloaded(context: Context): Boolean = file(context).exists()

  /**
   * Downloads the model. [onProgress] gets the percentage done (or -1 when the size is unknown)
   * and the bytes so far, on the main thread, each time the percentage changes.
   */
  suspend fun download(context: Context, onProgress: (percent: Long, bytes: Long) -> Unit) =
    withContext(Dispatchers.IO) {
      val modelFile = file(context)
      val partial = File(modelFile.path + ".part")
      partial.parentFile?.mkdirs()
      OkHttpClient().newCall(Request.Builder().url(URL).build()).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        val body = response.body
        val total = body.contentLength()
        body.byteStream().use { input ->
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
      }
      check(partial.renameTo(modelFile)) { "Could not save the model" }
    }
}
