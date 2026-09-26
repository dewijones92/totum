package com.dewijones92.totum.playback

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal class SpeechWeights private constructor(val floats: FloatArray) {

    val basis = Slice(0, BASIS_ROWS * FILTER)
    val encoder = listOf(
        ConvLayer(129, 128, 1),
        ConvLayer(128, 64, 2),
        ConvLayer(64, 64, 2),
        ConvLayer(64, 128, 1),
    ).let { layers ->
        var at = basis.end
        layers.map { layer ->
            val weight = Slice(at, layer.out * layer.into * KERNEL)
            val bias = Slice(weight.end, layer.out)
            at = bias.end
            layer.copy(weight = weight, bias = bias)
        }
    }
    val inputWeights = Slice(encoder.last().bias.end, GATES * HIDDEN)
    val hiddenWeights = Slice(inputWeights.end, GATES * HIDDEN)
    val inputBias = Slice(hiddenWeights.end, GATES)
    val hiddenBias = Slice(inputBias.end, GATES)
    val outputWeights = Slice(hiddenBias.end, HIDDEN)
    val outputBias = Slice(outputWeights.end, 1)

    operator fun get(index: Int): Float = floats[index]

    init {
        require(
            outputBias.end == floats.size
        ) { "speech model holds ${floats.size} values, expected ${outputBias.end}" }
    }

    data class Slice(val start: Int, val size: Int) {
        val end: Int get() = start + size
    }

    data class ConvLayer(
        val into: Int,
        val out: Int,
        val stride: Int,
        val weight: Slice = Slice(0, 0),
        val bias: Slice = Slice(0, 0),
    )

    internal companion object {
        const val FILTER = 256
        const val HOP = 128
        const val BASIS_ROWS = 258
        const val BINS = 129
        const val KERNEL = 3
        const val HIDDEN = 128
        const val GATES = 4 * HIDDEN
        private const val MAGIC = 0x53564144
        private const val VERSION = 1
        private const val BYTES_PER_FLOAT = 4

        fun read(input: InputStream): SpeechWeights {
            val data = DataInputStream(input.buffered())
            val header = ByteArray(HEADER_BYTES)
            data.readFully(header)
            val fields = ByteBuffer.wrap(header)
            require(fields.int == MAGIC) { "not a speech model" }
            fields.order(ByteOrder.LITTLE_ENDIAN)
            require(fields.int == VERSION) { "unknown speech model version" }
            val count = fields.int
            val body = ByteArray(count * BYTES_PER_FLOAT)
            data.readFully(body)
            val floats = FloatArray(count)
            ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            return SpeechWeights(floats)
        }

        private const val HEADER_BYTES = 12
    }
}

internal class SpeechModel(private val weights: SpeechWeights) {

    private val context = FloatArray(CONTEXT)
    private val padded = FloatArray(CONTEXT + CHUNK + REFLECT)
    private val spectrum = FloatArray(SpeechWeights.BINS * (FRAMES + 2))
    private val layers = weights.encoder.fold(listOf(FRAMES)) { lengths, layer ->
        lengths + ((lengths.last() + 2 - SpeechWeights.KERNEL) / layer.stride + 1)
    }
    private val activations = weights.encoder.mapIndexed { index, layer ->
        FloatArray(
            layer.out * (layers[index + 1] + 2)
        )
    }
    private val hidden = FloatArray(SpeechWeights.HIDDEN)
    private val cell = FloatArray(SpeechWeights.HIDDEN)
    private val gates = FloatArray(SpeechWeights.GATES)
    private val real = FloatArray(SpeechWeights.FILTER)
    private val imaginary = FloatArray(SpeechWeights.FILTER)
    private val window = FloatArray(SpeechWeights.FILTER) { n -> hann(n) }
    private val cosines = FloatArray(SpeechWeights.FILTER / 2) { k -> cos(2 * PI * k / SpeechWeights.FILTER).toFloat() }
    private val sines = FloatArray(SpeechWeights.FILTER / 2) { k -> sin(2 * PI * k / SpeechWeights.FILTER).toFloat() }
    private val bitReversed = IntArray(SpeechWeights.FILTER) { n -> reversed(n) }

    fun reset() {
        context.fill(0f)
        hidden.fill(0f)
        cell.fill(0f)
    }

    fun probability(chunk: FloatArray): Float {
        require(chunk.size == CHUNK) { "the speech model takes $CHUNK samples at a time, not ${chunk.size}" }
        context.copyInto(padded, 0)
        chunk.copyInto(padded, CONTEXT)
        val last = CONTEXT + CHUNK - 1
        for (j in 0 until REFLECT) padded[CONTEXT + CHUNK + j] = padded[last - 1 - j]
        chunk.copyInto(context, 0, CHUNK - CONTEXT, CHUNK)
        magnitudes()
        var input = spectrum
        var length = FRAMES
        weights.encoder.forEachIndexed { index, layer ->
            val output = activations[index]
            convolve(input, length, layer, output)
            input = output
            length = layers[index + 1]
        }
        remember(input)
        var sum = weights[weights.outputBias.start]
        for (unit in 0 until SpeechWeights.HIDDEN) {
            sum += weights[weights.outputWeights.start + unit] * maxOf(hidden[unit], 0f)
        }
        return sigmoid(sum)
    }

    private fun magnitudes() {
        for (frame in 0 until FRAMES) {
            val at = frame * SpeechWeights.HOP
            for (n in 0 until SpeechWeights.FILTER) {
                val slot = bitReversed[n]
                real[slot] = padded[at + n] * window[n]
                imaginary[slot] = 0f
            }
            transform()
            for (bin in 0 until SpeechWeights.BINS) {
                spectrum[bin * (FRAMES + 2) + frame + 1] = sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
            }
        }
    }

    private fun transform() {
        var size = 2
        while (size <= SpeechWeights.FILTER) {
            val half = size / 2
            val stride = SpeechWeights.FILTER / size
            for (start in 0 until SpeechWeights.FILTER step size) {
                for (k in 0 until half) {
                    val cos = cosines[k * stride]
                    val sin = sines[k * stride]
                    val a = start + k
                    val b = a + half
                    val tr = real[b] * cos + imaginary[b] * sin
                    val ti = imaginary[b] * cos - real[b] * sin
                    real[b] = real[a] - tr
                    imaginary[b] = imaginary[a] - ti
                    real[a] += tr
                    imaginary[a] += ti
                }
            }
            size *= 2
        }
    }

    private fun convolve(input: FloatArray, length: Int, layer: SpeechWeights.ConvLayer, output: FloatArray) {
        val w = weights.floats
        val kernel = SpeechWeights.KERNEL
        val inRow = length + 2
        val outLength = (length + 2 - kernel) / layer.stride + 1
        val outRow = outLength + 2
        for (channel in 0 until layer.out) {
            val bias = w[layer.bias.start + channel]
            val rows = layer.weight.start + channel * layer.into * kernel
            for (step in 0 until outLength) {
                var sum = bias
                var weight = rows
                var at = step * layer.stride
                repeat(layer.into) {
                    sum += w[weight] * input[at] + w[weight + 1] * input[at + 1] + w[weight + 2] * input[at + 2]
                    weight += kernel
                    at += inRow
                }
                output[channel * outRow + step + 1] = maxOf(sum, 0f)
            }
        }
    }

    private fun remember(features: FloatArray) {
        val size = SpeechWeights.HIDDEN
        val w = weights.floats
        for (gate in 0 until SpeechWeights.GATES) {
            var sum = w[weights.inputBias.start + gate] + w[weights.hiddenBias.start + gate]
            val inputRow = weights.inputWeights.start + gate * size
            val hiddenRow = weights.hiddenWeights.start + gate * size
            for (k in 0 until size) {
                sum += w[inputRow + k] * features[k * FEATURE_ROW + 1] + w[hiddenRow + k] * hidden[k]
            }
            gates[gate] = sum
        }
        for (unit in 0 until size) {
            val input = sigmoid(gates[unit])
            val forget = sigmoid(gates[size + unit])
            val candidate = tanh(gates[2 * size + unit])
            val output = sigmoid(gates[OUTPUT_GATE * size + unit])
            cell[unit] = forget * cell[unit] + input * candidate
            hidden[unit] = output * tanh(cell[unit])
        }
    }

    private fun sigmoid(value: Float): Float = 1f / (1f + exp(-value))

    private fun reversed(n: Int): Int {
        var value = n
        var result = 0
        repeat(BITS) {
            result = (result shl 1) or (value and 1)
            value = value shr 1
        }
        return result
    }

    internal companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 512
        const val CONTEXT = 64
        private const val OUTPUT_GATE = 3
        private const val BITS = 8
        private const val FEATURE_ROW = 3

        fun hann(n: Int): Float = (HALF - HALF * cos(2 * PI * n / SpeechWeights.FILTER)).toFloat()

        private const val HALF = 0.5
        private const val REFLECT = 64
        private const val FRAMES = (CONTEXT + CHUNK + REFLECT - SpeechWeights.FILTER) / SpeechWeights.HOP + 1
    }
}
