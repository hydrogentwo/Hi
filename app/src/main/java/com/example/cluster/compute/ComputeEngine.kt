package com.example.cluster.compute

import com.example.cluster.model.ShardExecuteRequest
import com.example.cluster.model.ShardExecuteResponse
import com.example.cluster.model.WorkloadType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.security.MessageDigest
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis

class ComputeEngine {

    suspend fun executeShard(request: ShardExecuteRequest): ShardExecuteResponse =
        coroutineScope {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
            when (request.workloadId) {
                WorkloadType.PRIME_SIEVE.id -> executePrimeSieve(request, threads)
                WorkloadType.MONTE_CARLO_PI.id -> executeMonteCarloPi(request, threads)
                WorkloadType.SHA256_BENCHMARK.id -> executeSha256(request, threads)
                WorkloadType.MATRIX_MATMUL.id -> executeMatrixMatmul(request, threads)
                else -> ShardExecuteResponse(
                    podId = request.podId,
                    success = false,
                    elapsedMs = 0L,
                    itemsProcessed = 0L,
                    primaryValue = 0.0,
                    secondaryText = "",
                    threadsUsed = threads,
                    errorMessage = "Unknown workload kernel: ${request.workloadId}"
                )
            }
        }

    private suspend fun executePrimeSieve(
        request: ShardExecuteRequest,
        threads: Int
    ): ShardExecuteResponse = coroutineScope {
        val startN = request.rangeStart.coerceAtLeast(2L)
        val endN = request.rangeEnd.coerceAtLeast(startN)
        val span = (endN - startN) + 1L
        val chunkSize = ((span + threads - 1) / threads).coerceAtLeast(1L)

        var totalPrimes = 0L
        var maxPrime = 0L

        val elapsed = measureTimeMillis {
            val results = (0 until threads).mapNotNull { t ->
                val chunkStart = startN + t * chunkSize
                if (chunkStart > endN) return@mapNotNull null
                val chunkEnd = minOf(endN, chunkStart + chunkSize - 1L)
                async(Dispatchers.Default) {
                    var count = 0L
                    var largest = 0L
                    var n = if (chunkStart <= 2L) 2L else if (chunkStart % 2L == 0L) chunkStart + 1L else chunkStart
                    if (chunkStart <= 2L && chunkEnd >= 2L) {
                        count++
                        largest = 2L
                        n = 3L
                    }
                    while (n <= chunkEnd) {
                        if (isPrime(n)) {
                            count++
                            largest = n
                        }
                        n += 2L
                    }
                    count to largest
                }
            }.awaitAll()

            results.forEach { (c, maxP) ->
                totalPrimes += c
                if (maxP > maxPrime) maxPrime = maxP
            }
        }

        ShardExecuteResponse(
            podId = request.podId,
            success = true,
            elapsedMs = elapsed.coerceAtLeast(1L),
            itemsProcessed = span,
            primaryValue = totalPrimes.toDouble(),
            secondaryText = "maxPrime=$maxPrime",
            threadsUsed = threads
        )
    }

    private fun isPrime(n: Long): Boolean {
        if (n < 2L) return false
        if (n == 2L || n == 3L) return true
        if (n % 2L == 0L || n % 3L == 0L) return false
        val limit = sqrt(n.toDouble()).toLong()
        var i = 5L
        while (i <= limit) {
            if (n % i == 0L || n % (i + 2L) == 0L) return false
            i += 6L
        }
        return true
    }

    private suspend fun executeMonteCarloPi(
        request: ShardExecuteRequest,
        threads: Int
    ): ShardExecuteResponse = coroutineScope {
        val totalSamples = request.iterations.coerceAtLeast(10_000L)
        val perThread = (totalSamples + threads - 1) / threads
        var totalHits = 0L
        var actualSamples = 0L

        val elapsed = measureTimeMillis {
            val jobs = (0 until threads).map { threadIdx ->
                val count = if (threadIdx == threads - 1) {
                    (totalSamples - perThread * (threads - 1)).coerceAtLeast(0L)
                } else {
                    perThread
                }
                async(Dispatchers.Default) {
                    var seed = System.nanoTime() xor ((threadIdx + 1L) * 0x9E3779B97F4A7C15UL.toLong())
                    var hits = 0L
                    val invMax = 1.0 / 9007199254740991.0 // 2^53 - 1
                    for (i in 0L until count) {
                        seed = seed xor (seed ushr 12)
                        seed = seed xor (seed shl 25)
                        seed = seed xor (seed ushr 27)
                        val r1 = (seed * 0x2545F4914F6CDD1DUL.toLong()) ushr 11
                        val x = (r1 and 0x1FFFFFFFFFFFFFL) * invMax

                        seed = seed xor (seed ushr 12)
                        seed = seed xor (seed shl 25)
                        seed = seed xor (seed ushr 27)
                        val r2 = (seed * 0x2545F4914F6CDD1DUL.toLong()) ushr 11
                        val y = (r2 and 0x1FFFFFFFFFFFFFL) * invMax

                        if (x * x + y * y <= 1.0) hits++
                    }
                    hits to count
                }
            }.awaitAll()

            jobs.forEach { (hits, cnt) ->
                totalHits += hits
                actualSamples += cnt
            }
        }

        val piEstimate = 4.0 * totalHits.toDouble() / actualSamples.coerceAtLeast(1L).toDouble()
        ShardExecuteResponse(
            podId = request.podId,
            success = true,
            elapsedMs = elapsed.coerceAtLeast(1L),
            itemsProcessed = actualSamples,
            primaryValue = totalHits.toDouble(),
            secondaryText = "pi=${"%.6f".format(piEstimate)}",
            threadsUsed = threads
        )
    }

    private suspend fun executeSha256(
        request: ShardExecuteRequest,
        threads: Int
    ): ShardExecuteResponse = coroutineScope {
        val startNonce = request.rangeStart
        val count = request.iterations.coerceAtLeast(1_000L)
        val perThread = (count + threads - 1) / threads
        var bestPrefixHex = "ffffffff"
        var bestZeroBits = -1

        val elapsed = measureTimeMillis {
            val results = (0 until threads).map { t ->
                val tStart = startNonce + t * perThread
                val tCount = if (t == threads - 1) (count - perThread * (threads - 1)).coerceAtLeast(0L) else perThread
                async(Dispatchers.Default) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val prefixBytes = "kubemesh:${request.podId}:".toByteArray()
                    val buffer = ByteArray(prefixBytes.size + 16)
                    System.arraycopy(prefixBytes, 0, buffer, 0, prefixBytes.size)
                    var localBestZeros = -1
                    var localBestHex = ""

                    for (i in 0L until tCount) {
                        val nonce = tStart + i
                        for (b in 0 until 8) {
                            buffer[prefixBytes.size + b] = ((nonce ushr (b * 8)) and 0xFFL).toByte()
                        }
                        val hash = digest.digest(buffer)
                        val zeros = countLeadingZeroBits(hash)
                        if (zeros > localBestZeros) {
                            localBestZeros = zeros
                            localBestHex = hash.take(4).joinToString("") { "%02x".format(it) }
                        }
                    }
                    localBestZeros to localBestHex
                }
            }.awaitAll()

            results.forEach { (z, hex) ->
                if (z > bestZeroBits) {
                    bestZeroBits = z
                    bestPrefixHex = hex
                }
            }
        }

        ShardExecuteResponse(
            podId = request.podId,
            success = true,
            elapsedMs = elapsed.coerceAtLeast(1L),
            itemsProcessed = count,
            primaryValue = bestZeroBits.toDouble(),
            secondaryText = "best=0x$bestPrefixHex... ($bestZeroBits zero-bits)",
            threadsUsed = threads
        )
    }

    private fun countLeadingZeroBits(bytes: ByteArray): Int {
        var zeros = 0
        for (byte in bytes) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned == 0) {
                zeros += 8
            } else {
                zeros += Integer.numberOfLeadingZeros(unsigned) - 24
                break
            }
        }
        return zeros
    }

    private suspend fun executeMatrixMatmul(
        request: ShardExecuteRequest,
        threads: Int
    ): ShardExecuteResponse = coroutineScope {
        val dim = request.matrixDim.coerceIn(64, 512)
        val rowStart = request.rangeStart.toInt().coerceIn(0, dim)
        val rowEnd = request.rangeEnd.toInt().coerceIn(rowStart, dim)
        val totalRows = (rowEnd - rowStart).coerceAtLeast(1)

        // Deterministic matrix B so both nodes multiply against identical tensor weights
        val matB = FloatArray(dim * dim) { idx ->
            val r = idx / dim
            val c = idx % dim
            (((r * 31 + c * 17) % 97) - 48) * 0.01f
        }

        var checksum = 0.0
        val elapsed = measureTimeMillis {
            val rowsPerThread = ((totalRows + threads - 1) / threads).coerceAtLeast(1)
            val partials = (0 until threads).mapNotNull { t ->
                val s = rowStart + t * rowsPerThread
                if (s >= rowEnd) return@mapNotNull null
                val e = minOf(rowEnd, s + rowsPerThread)
                async(Dispatchers.Default) {
                    var localSum = 0.0
                    val rowA = FloatArray(dim)
                    for (r in s until e) {
                        for (k in 0 until dim) {
                            rowA[k] = (((r * 13 + k * 7) % 53) - 26) * 0.02f
                        }
                        for (c in 0 until dim) {
                            var acc = 0f
                            var idxB = c
                            for (k in 0 until dim) {
                                acc += rowA[k] * matB[idxB]
                                idxB += dim
                            }
                            localSum += acc.toDouble()
                        }
                    }
                    localSum
                }
            }.awaitAll()
            checksum = partials.sum()
        }

        val flops = 2.0 * totalRows * dim * dim
        ShardExecuteResponse(
            podId = request.podId,
            success = true,
            elapsedMs = elapsed.coerceAtLeast(1L),
            itemsProcessed = totalRows.toLong(),
            primaryValue = checksum,
            secondaryText = "${dim}x${dim} rows=$totalRows ops=${"%.1f".format(flops / 1_000_000.0)}M",
            threadsUsed = threads
        )
    }
}
