package com.sfpahsdev.mydream.inference

import android.content.Context
import java.io.Closeable
import org.tensorflow.lite.Interpreter

class TfliteTabularModelRunner(
    context: Context,
    modelAssetPath: String = TabularModelContract.VALIDATION_MODEL_ASSET,
) : Closeable {
    private val interpreter = Interpreter(
        AndroidSequenceModelAssets.loadModelBuffer(context, modelAssetPath),
        Interpreter.Options().setNumThreads(1),
    )

    fun predict(input: TabularModelInput): Float {
        val inputBatch = arrayOf(input.scaledFeatures28)
        val output = Array(1) { FloatArray(1) }
        interpreter.run(inputBatch, output)
        return output[0][0]
    }

    override fun close() {
        interpreter.close()
    }
}
