package dev.russiankeyboard.prototype

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OnDeviceRankerTest {
    @Test fun quantizedOnnxScoresMatchPythonOnTheEmulator() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Normal APK does not contain the synthetic test weights.
        assumeTrue(ctx.assets.list("")?.contains("ranker_reference.json") == true)
        val json = JSONObject(ctx.assets.open("ranker_reference.json")
            .bufferedReader().use { it.readText() })
        val choices = json.getJSONArray("candidates")
        val candidates = (0 until choices.length()).map { choices.getString(it) }
        val reference = json.getJSONArray("scores")
        val vocab = JSONObject(ctx.assets.open("tokenizer.json").bufferedReader()
            .use { it.readText() }).getJSONArray("itos")
        val tokens = (0 until vocab.length()).map { vocab.getString(it) }
        val metadata = JSONObject(ctx.assets.open("ranker_metadata.json")
            .bufferedReader().use { it.readText() })
        val tok = RankerCharTokenizer(tokens, metadata.getInt("max_chars"))
        val expectedIds = json.getJSONArray("ids")
        val expectedMask = json.getJSONArray("mask")
        for (i in candidates.indices) {
            val encoded = tok.encodePair(json.getString("context"), json.getString("typed"), candidates[i])
            val ids = expectedIds.getJSONArray(i)
            val mask = expectedMask.getJSONArray(i)
            assertEquals(ids.length(), encoded.ids.size)
            for (j in encoded.ids.indices) {
                assertEquals("token id candidate $i index $j", ids.getLong(j), encoded.ids[j])
                assertEquals("attention mask candidate $i index $j", mask.getLong(j), encoded.mask[j])
            }
        }
        // Synthetic Android performance sample, not a real-device benchmark.
        // PSS is process-wide and sampled; it is not a model-only memory figure.
        fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        val pssBeforeKb = pssKb()
        val openStarted = SystemClock.elapsedRealtimeNanos()
        val model = OnDeviceRanker.openIfBundled(ctx)
        val openMs = (SystemClock.elapsedRealtimeNanos() - openStarted) / 1_000_000.0
        assertNotNull("Int8 ONNX artifact must be present in smoke APK", model)
        val pssWithSessionKb = pssKb()
        val samples = ArrayList<Double>(40)
        var pssAfterCallsKb = 0
        model!!.use {
            val context = json.getString("context")
            val typed = json.getString("typed")
            val scores = it.score(context, typed, candidates)
            assertEquals(candidates.size, scores.size)
            for (i in scores.indices) {
                assertTrue("Score mismatch at candidate $i: ${scores[i]} vs ${reference.getDouble(i)}",
                    abs(scores[i] - reference.getDouble(i)) < 0.08)
            }
            repeat(5) { _ -> it.score(context, typed, candidates) } // warm up
            repeat(40) {
                val started = SystemClock.elapsedRealtimeNanos()
                val result = it.score(context, typed, candidates)
                samples.add((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                assertEquals(candidates.size, result.size)
            }
            pssAfterCallsKb = pssKb()
        }
        val pssAfterCloseKb = pssKb()
        val sorted = samples.sorted()
        val p50 = sorted[(sorted.size * 50 + 99) / 100 - 1]
        val p95 = sorted[(sorted.size * 95 + 99) / 100 - 1]
        val report = JSONObject().apply {
            put("scenario", "synthetic-ranker-int8")
            put("device_type", "github-actions-android-emulator")
            put("model_quality_validated", false)
            put("model_used_for_ime_autocorrection", false)
            put("threads", 1)
            put("candidate_count", candidates.size)
            put("warmup_iterations", 5)
            put("measured_iterations", sorted.size)
            put("model_open_ms", openMs)
            put("inference_p50_ms", p50)
            put("inference_p95_ms", p95)
            put("inference_max_ms", sorted.last())
            put("process_pss_before_kb", pssBeforeKb)
            put("process_pss_with_session_kb", pssWithSessionKb)
            put("process_pss_after_inference_kb", pssAfterCallsKb)
            put("process_pss_after_close_kb", pssAfterCloseKb)
        }
        Log.i("RSK_ONNX_BENCH", "ONNX_BENCH_JSON=$report")
    }
}
