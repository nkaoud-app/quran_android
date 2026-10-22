package com.quran.recitation.tracking

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * The distilled recitation acoustic model (tools/recitation-spike/export_onnx.py), run with ONNX
 * Runtime. Input `audio` is normalized 16 kHz audio shaped [1, samples]; output `logits` is
 * shaped [1, frames, classes].
 */
class OnnxFrameModel(modelFile: File, threads: Int = 2) : FrameModel, Closeable {
  private val environment = OrtEnvironment.getEnvironment()
  private val session: OrtSession = OrtSession.SessionOptions().use { options ->
    options.setIntraOpNumThreads(threads)
    options.setInterOpNumThreads(1)
    environment.createSession(modelFile.absolutePath, options)
  }

  override fun logits(window: FloatArray): Array<FloatArray> {
    val input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(window), longArrayOf(1, window.size.toLong()))
    input.use {
      session.run(mapOf("audio" to input)).use { result ->
        @Suppress("UNCHECKED_CAST")
        val logits = (result[0].value as Array<Array<FloatArray>>)[0]
        check(logits.isEmpty() || logits[0].size == RecitationSkeleton.CLASS_COUNT) {
          "Model has ${logits[0].size} classes, expected ${RecitationSkeleton.CLASS_COUNT}"
        }
        return logits
      }
    }
  }

  override fun close() {
    session.close()
  }
}
