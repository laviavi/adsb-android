package com.laviavi.adsbandroid.demod

import com.laviavi.adsbandroid.crc.CrcChecker
import com.laviavi.adsbandroid.crc.IcaoCache
import com.laviavi.adsbandroid.decoder.MessageDecoder
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Phase 3.5 throughput benchmark.
 *
 * Not a correctness test — prints results to stdout and asserts a loose
 * sanity bound. Run via `./gradlew :core:receiver:test --tests *Benchmark*`
 * to see the numbers without the full suite noise.
 *
 * Fixture: 256 K synthetic IQ bytes (seeded PRNG), mirroring what
 * PipelineService feeds the demodulator each tick. Random bytes rarely
 * pass preambleOk, so this exercises the hot path that spends most of
 * its time in computeMagnitude and the magnitude-scan loop.
 */
class DemodulatorBenchmarkTest {

    private val rng = Random(42)
    private val iqBlock = ByteArray(Demodulator.BLOCK_SIZE) { rng.nextInt(256).toByte() }

    @Test fun `demodulator throughput`() {
        val demod = Demodulator()

        // Warm up JIT
        repeat(20) { demod.demodulate(iqBlock) }

        val iterations = 300
        val t0 = System.nanoTime()
        var totalFrames = 0
        repeat(iterations) { totalFrames += demod.demodulate(iqBlock).size }
        val elapsedNs = System.nanoTime() - t0

        val msPerCall = elapsedNs / 1_000_000.0 / iterations
        val mbPerSec  = (iqBlock.size.toLong() * iterations) / (elapsedNs / 1e9) / 1_000_000.0

        println("=== Demodulator benchmark ===")
        println("  ${msPerCall.format(2)} ms/call   |   ${mbPerSec.format(0)} MB/s   |   $totalFrames frames total")

        assert(msPerCall < 200.0) { "Demodulator regressed: ${msPerCall.format(2)} ms/call > 200 ms threshold" }
    }

    @Test fun `full pipeline throughput — demod to decoded message`() {
        val demod   = Demodulator()
        val cache   = IcaoCache()
        val decoder = MessageDecoder()

        repeat(20) {
            demod.demodulate(iqBlock)
                .map { CrcChecker.check(it, icaoCache = cache) }
                .mapNotNull { decoder.decode(it) }
        }

        val iterations = 300
        val t0 = System.nanoTime()
        var msgs = 0
        repeat(iterations) {
            demod.demodulate(iqBlock)
                .map { CrcChecker.check(it, icaoCache = cache) }
                .forEach { f -> decoder.decode(f)?.let { msgs++ } }
        }
        val elapsedNs = System.nanoTime() - t0

        val msPerCall = elapsedNs / 1_000_000.0 / iterations
        val mbPerSec  = (iqBlock.size.toLong() * iterations) / (elapsedNs / 1e9) / 1_000_000.0

        println("=== Full pipeline benchmark (demod+CRC+decode) ===")
        println("  ${msPerCall.format(2)} ms/call   |   ${mbPerSec.format(0)} MB/s   |   $msgs messages decoded")
    }

    private fun Double.format(decimals: Int) = "%.${decimals}f".format(this)
}
