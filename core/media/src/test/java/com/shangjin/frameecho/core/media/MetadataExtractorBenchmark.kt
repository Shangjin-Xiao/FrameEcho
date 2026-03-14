package com.shangjin.frameecho.core.media

import android.media.MediaFormat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.system.measureNanoTime

class MetadataExtractorBenchmark {

    @Test
    fun benchmarkContainsException() {
        val format = mockk<MediaFormat>()
        // Mock a scenario where only the last key contains the value, or none do,
        // to maximize the time spent trying the wrong keys
        every { format.containsKey(any()) } returns false
        every { format.containsKey("missing3") } returns true
        every { format.getInteger("missing3") } throws IllegalArgumentException("Key not found")
        every { format.getString("missing3") } throws IllegalArgumentException("Key not found")

        val keys = arrayOf("missing1", "missing2", "missing3")

        val iterations = 1000

        // Warmup
        for (i in 0..100) {
            for (key in keys) {
                try {
                    if (format.containsKey(key)) {
                        format.getInteger(key)
                    }
                } catch (e: Exception) {
                    continue
                }
            }
        }

        for (i in 0..100) {
            for (key in keys) {
                if (!format.containsKey(key)) continue
                val res = runCatching { format.getInteger(key) }.getOrNull()
            }
        }

        val timeWithOldTryCatch = measureNanoTime {
            for (i in 0..iterations) {
                var res: Int? = null
                for (key in keys) {
                    try {
                        if (format.containsKey(key)) {
                            res = format.getInteger(key)
                            break
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }

        val timeWithNewGetOrNull = measureNanoTime {
            for (i in 0..iterations) {
                var res: Int? = null
                for (key in keys) {
                    if (!format.containsKey(key)) continue
                    res = runCatching { format.getInteger(key) }.getOrNull()
                    if (res != null) break
                }
            }
        }

        println("Time with old try catch: $timeWithOldTryCatch ns")
        println("Time with new getOrNull: $timeWithNewGetOrNull ns")
    }
}
