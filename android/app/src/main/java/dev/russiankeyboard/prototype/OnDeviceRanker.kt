package dev.russiankeyboard.prototype

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Experimental, strictly offline model runner.
 * Not invoked by InputMethodService: low-quality demo checkpoints must never
 * silently modify user text. Keep sessions short on 3-4 GB devices.
 */
class OnDeviceRanker private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: RankerCharTokenizer,
    val keepMargin: Float
) : Closeable {
    fun score(context: String, typed: String, candidates: List<String>): FloatArray {
        require(candidates.isNotEmpty() && candidates.size <= 16)
        val maxChars = tokenizer.maxChars
        val ids = ByteBuffer.allocateDirect(candidates.size * maxChars * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        val masks = ByteBuffer.allocateDirect(candidates.size * maxChars * 8)
            .order(ByteOrder.nativeOrder()).asLongBuffer()
        for (candidate in candidates) {
            val item = tokenizer.encodePair(context, typed, candidate)
            ids.put(item.ids)
            masks.put(item.mask)
        }
        ids.flip()
        masks.flip()
        val shape = longArrayOf(candidates.size.toLong(), maxChars.toLong())
        OnnxTensor.createTensor(environment, ids, shape).use { inputs ->
            OnnxTensor.createTensor(environment, masks, shape).use { attention ->
                session.run(mapOf("input_ids" to inputs, "attention_mask" to attention)).use { output ->
                    val scores = output[0].value as? FloatArray
                        ?: error("Unexpected ONNX score tensor shape")
                    require(scores.size == candidates.size && scores.all { it.isFinite() })
                    return scores.copyOf()
                }
            }
        }
    }

    override fun close() {
        session.close()
        // OrtEnvironment is a process-global singleton and is not closed here.
    }

    companion object {
        private const val MODEL_NAME = "ranker_int8.onnx"
        private const val LIMIT = 16 * 1024 * 1024

        private fun readBounded(context: Context): ByteArray =
            context.assets.open(MODEL_NAME).use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count: Int
                while (stream.read(buffer).also { count = it } != -1) {
                    if (out.size() + count > LIMIT) {
                        throw IllegalStateException("Ranker model exceeds the 16 MB mobile budget")
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }

        /** null means model is not bundled; absence is normal in production MVP. */
        fun openIfBundled(context: Context): OnDeviceRanker? {
            val modelBytes = try {
                readBounded(context)
            } catch (_: FileNotFoundException) {
                return null
            }
            val metadata = JSONObject(context.assets.open("ranker_metadata.json")
                .bufferedReader().use { it.readText() })
            val vocab = JSONObject(context.assets.open("tokenizer.json")
                .bufferedReader().use { it.readText() }).getJSONArray("itos")
            val tokens = (0 until vocab.length()).map { vocab.getString(it) }
            val maxChars = metadata.getInt("max_chars")
            val margin = metadata.getDouble("keep_margin").toFloat()
            require(margin.isFinite() && margin >= 0f)
            val tok = RankerCharTokenizer(tokens, maxChars)
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                val session = environment.createSession(modelBytes, options)
                return OnDeviceRanker(environment, session, tok, margin)
            } finally {
                options.close()
            }
        }
    }
}
