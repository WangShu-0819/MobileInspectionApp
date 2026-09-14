package com.wearable.inspection.mobile.detection

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

object NanoDetOutputDecoder {
    private val strides = intArrayOf(8, 16, 32, 64)
    private val classNames = arrayOf("nut", "thread")
    private const val REG_MAX = 7
    private const val NMS_THRESHOLD = 0.6
    private const val MAX_DETECTIONS_PER_CLASS = 100

    fun decode(
        output: FloatArray,
        transform: NanoDetInputTransform,
        scoreThreshold: Float = NanoDetModelContract.CANDIDATE_THRESHOLD
    ): List<NanoDetCandidate> {
        require(output.size == NanoDetModelContract.OUTPUT_WIDTH * NanoDetModelContract.OUTPUT_HEIGHT) {
            "Unexpected NCNN output element count: ${output.size}"
        }
        require(output.all(Float::isFinite)) { "NCNN output contains non-finite values" }
        require(scoreThreshold.isFinite() && scoreThreshold in 0f..1f)

        val byClass = Array(2) { mutableListOf<NanoDetCandidate>() }
        var offset = 0
        for (stride in strides) {
            val featureSize = ceil(NanoDetModelContract.INPUT_SIZE.toDouble() / stride).toInt()
            for (y in 0 until featureSize) {
                for (x in 0 until featureSize) {
                    val point = offset + y * featureSize + x
                    val centerX = x * stride
                    val centerY = y * stride
                    if (centerX >= transform.resizedWidth || centerY >= transform.resizedHeight) continue

                    val row = point * NanoDetModelContract.OUTPUT_WIDTH
                    val classIndex = if (output[row + 1] > output[row]) 1 else 0
                    val score = output[row + classIndex]
                    if (score < scoreThreshold) continue

                    val distances = DoubleArray(4)
                    for (side in 0 until 4) {
                        val base = row + 2 + side * (REG_MAX + 1)
                        var maxLogit = Float.NEGATIVE_INFINITY
                        for (bin in 0..REG_MAX) maxLogit = max(maxLogit, output[base + bin])
                        var sum = 0.0
                        val probabilities = DoubleArray(REG_MAX + 1) { bin ->
                            exp((output[base + bin] - maxLogit).toDouble()).also { sum += it }
                        }
                        val expectation = probabilities.indices.sumOf { bin ->
                            probabilities[bin] / sum * bin
                        }
                        distances[side] = expectation * stride
                    }

                    byClass[classIndex].add(
                        NanoDetCandidate(
                            classIndex = classIndex,
                            className = classNames[classIndex],
                            score = score,
                            point = point,
                            box = NanoDetBox(
                                left = clamp((centerX - distances[0]) / transform.scaleX, transform.width.toDouble()),
                                top = clamp((centerY - distances[1]) / transform.scaleY, transform.height.toDouble()),
                                right = clamp((centerX + distances[2]) / transform.scaleX, transform.width.toDouble()),
                                bottom = clamp((centerY + distances[3]) / transform.scaleY, transform.height.toDouble())
                            )
                        )
                    )
                }
            }
            offset += featureSize * featureSize
        }
        return byClass.flatMap(::nms)
    }

    private fun nms(boxes: List<NanoDetCandidate>): List<NanoDetCandidate> {
        val kept = mutableListOf<NanoDetCandidate>()
        for (box in boxes.sortedByDescending { it.score }) {
            if (kept.none { previous -> iouInclusive(box.box, previous.box) > NMS_THRESHOLD }) {
                kept.add(box)
            }
            if (kept.size == MAX_DETECTIONS_PER_CLASS) break
        }
        return kept
    }

    private fun iouInclusive(a: NanoDetBox, b: NanoDetBox): Double {
        val x1 = max(a.left, b.left)
        val y1 = max(a.top, b.top)
        val x2 = min(a.right, b.right)
        val y2 = min(a.bottom, b.bottom)
        val intersection = max(0.0, x2 - x1 + 1.0) * max(0.0, y2 - y1 + 1.0)
        val areaA = (a.right - a.left + 1.0) * (a.bottom - a.top + 1.0)
        val areaB = (b.right - b.left + 1.0) * (b.bottom - b.top + 1.0)
        return intersection / max(areaA + areaB - intersection, 1e-12)
    }

    private fun clamp(value: Double, limit: Double) = max(0.0, min(value, limit))
}
