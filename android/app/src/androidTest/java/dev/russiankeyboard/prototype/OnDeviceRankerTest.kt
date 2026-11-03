package dev.russiankeyboard.prototype

import android.content.Context
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
        val model = OnDeviceRanker.openIfBundled(ctx)
        assertNotNull("Int8 ONNX artifact must be present in smoke APK", model)
        model!!.use {
            val scores = it.score(json.getString("context"), json.getString("typed"), candidates)
            assertEquals(candidates.size, scores.size)
            for (i in scores.indices) {
                assertTrue("Score mismatch at candidate $i: ${scores[i]} vs ${reference.getDouble(i)}",
                    abs(scores[i] - reference.getDouble(i)) < 0.08)
            }
        }
    }
}
