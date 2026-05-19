package com.sfpahsdev.mydream.inference

import android.content.Context
import java.io.Closeable
import org.tensorflow.lite.Interpreter

class TfliteSequenceModelRunner(
    context: Context,
    modelAssetPath: String = SequenceModelContract.VALIDATION_MODEL_ASSET,
) : Closeable {
    private val interpreter = Interpreter(
        AndroidSequenceModelAssets.loadModelBuffer(context, modelAssetPath),
        Interpreter.Options().setNumThreads(1),
    )

    fun predict(input: SequenceModelInput): Float {
        val stageBatch = arrayOf(input.stageSequence60m)
        val contextBatch = arrayOf(input.contextScaled22)
        val inputs = arrayOfNulls<Any>(interpreter.inputTensorCount)

        for (index in 0 until interpreter.inputTensorCount) {
            val tensorName = interpreter.getInputTensor(index).name()
            inputs[index] = when {
                tensorName.contains("context", ignoreCase = true) -> contextBatch
                tensorName.contains("stage", ignoreCase = true) -> stageBatch
                else -> error("Unknown TFLite input tensor: $tensorName")
            }
        }

        val output = Array(1) { FloatArray(1) }
        interpreter.runForMultipleInputsOutputs(inputs.requireNoNulls(), mapOf(0 to output))
        return output[0][0]
    }

    override fun close() {
        interpreter.close()
    }
}
