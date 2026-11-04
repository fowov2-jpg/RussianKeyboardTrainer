package dev.russiankeyboard.prototype

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Actual pinned RUPunct Small INT8. Called only by the single analysis worker. */
class OnDevicePunctuator private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: PunctuationTokenizer,
    @Suppress("unused") private val mapping: ByteBuffer
) : Closeable {
    fun predict(text: String, context: String = "", mode: PunctuationPlanner.Mode,
                sentenceStart: Boolean = true): PunctuationPlanner.Result? {
        val encoding = tokenizer.encode(text, context, sentenceStart = sentenceStart)
        if (encoding.words.isEmpty()) return null
        val count = encoding.ids.size
        fun tensor(values: LongArray): OnnxTensor {
            val buffer = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder()).asLongBuffer()
            buffer.put(values); buffer.flip()
            return OnnxTensor.createTensor(environment, buffer, longArrayOf(1, count.toLong()))
        }
        tensor(encoding.ids).use { ids ->
            tensor(LongArray(count) { 1 }).use { mask ->
                tensor(LongArray(count)).use { types ->
                    session.run(mapOf("input_ids" to ids, "attention_mask" to mask, "token_type_ids" to types)).use { result ->
                        val batch = result[0].value as? Array<*> ?: error("Invalid punctuation batch")
                        val tokens = batch[0] as? Array<*> ?: error("Invalid punctuation sequence")
                        val scores = encoding.words.map { word ->
                            PunctuationPlanner.prediction(tokens[word.firstToken] as? FloatArray ?: error("Invalid punctuation logits"))
                        }
                        val planned = PunctuationPlanner.plan(text, encoding, scores, mode)
                        if (mode != PunctuationPlanner.Mode.STREAMING) return planned
                        val finalized = PunctuationPlanner.plan(text, encoding, scores, PunctuationPlanner.Mode.FINALIZE)?.text ?: text
                        return if (planned != null) planned.copy(finalizedText = finalized)
                            else if (finalized != text) PunctuationPlanner.Result(text, null, finalized) else null
                    }
                }
            }
        }
    }
    override fun close() { session.close() }

    companion object {
        fun open(context: Context): OnDevicePunctuator {
            val vocabulary = context.assets.open("punctuation/vocab.txt").bufferedReader().use { it.readLines() }
            val mapping = context.assets.openFd("punctuation/model.bin").use { asset ->
                require(asset.declaredLength in 1..40L * 1024 * 1024)
                asset.createInputStream().use { input -> input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength) }
            }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
                return OnDevicePunctuator(environment, environment.createSession(mapping, options), PunctuationTokenizer(vocabulary), mapping)
            } finally { options.close() }
        }
    }
}
