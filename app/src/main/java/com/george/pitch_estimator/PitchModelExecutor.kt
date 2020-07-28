package com.george.pitch_estimator

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

class PitchModelExecutor(
    context: Context,
    private var useGPU: Boolean
) {
    private var gpuDelegate: GpuDelegate = GpuDelegate()
    private var numberThreads = 4

    private val interpreter: Interpreter
    private var predictTime = 0L

    init {
        if (useGPU) {
            interpreter = getInterpreter(context, PITCH_MODEL, true)
            Log.i("GPU_TRUE", "TRUE")
        } else {
            interpreter = getInterpreter(context, PITCH_MODEL, false)
            Log.i("GPU_FALSE", "FALSE")
        }
    }

    companion object {
        private const val PITCH_MODEL = "lite-model_spice_1.tflite"
        private const val PT_OFFSET = 25.58
        private const val PT_SLOPE = 63.07
        private const val FMIN = 10.0
        private const val BINS_PER_OCTAVE = 12.0
        private const val C0 = 16.351597831287414
    }

    fun execute(floatsInput: FloatArray): DoubleArray {

        predictTime = System.currentTimeMillis()
        val inputSize = floatsInput.size // ~10 seconds of sound
        var outputSize = 0
        when (inputSize) {
            32000 -> outputSize = ceil(inputSize / 512.0).toInt()
            else -> outputSize = (ceil(inputSize / 512.0) + 1).toInt()
        }
        val inputValues = floatsInput//FloatArray(inputSize)

        val inputs = arrayOf<Any>(inputValues)
        val outputs = HashMap<Int, Any>()

        val pitches = FloatArray(outputSize)
        val uncertainties = FloatArray(outputSize)

        outputs[0] = pitches
        outputs[1] = uncertainties
        //Log.e("INPUTS_SIZE", floatsInput.size.toString())
        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
        } catch (e: Exception) {
            Log.e("EXCEPTION", e.toString())
        }

        predictTime = System.currentTimeMillis() - predictTime

        Log.i("PITCHES", pitches.contentToString())
        Log.i("PITCHES_SIZE", pitches.size.toString())
        Log.i("UNCERTAIN", uncertainties.contentToString())
        Log.i("UNCERTAIN_SIZE", uncertainties.size.toString())
        Log.i("PITCHES_TIME", predictTime.toString())

        // Calculate confidence over 90%
        // and store values inside an array list of floats
        // if confidence is lower than 90% then add 0F
        val arrayForConfidence = arrayListOf<Float>()
        for (i in uncertainties.indices) {
            if (1 - uncertainties[i] >= 0.9) {
                arrayForConfidence.add(pitches[i])
            } else {
                arrayForConfidence.add(0F)
            }
        }

        Log.e("PITCHES_OVER_0.9", arrayForConfidence.size.toString())
        for (k in 0 until arrayForConfidence.size) {
            Log.e("PITCHES_OVER_0.9", arrayForConfidence[k].toString())
        }

        // The pitch values returned by SPICE are in the range from 0 to 1.
        // Let's convert them to absolute pitch values in Hz.
        val hertzValues = DoubleArray(arrayForConfidence.size)
        for (i in 0 until arrayForConfidence.size) {
            hertzValues[i] = convertToAbsolutePitchValuesInHz(arrayForConfidence[i])
        }

        Log.e("HERTZ_VALUES", hertzValues.contentToString())

        // Calculate the offset during singing
        // When a person sings freely, the melody may have an offset to the absolute pitch values that notes can represent.
        // Hence, to convert predictions to notes, one needs to correct for this possible offset.
        val arrayForOffset = arrayListOf<Float>()
        for (i in hertzValues.indices) {
            if (hertzValues[i] > 0 )
                arrayForOffset.add(hzToOffset(hertzValues[i].toFloat()))
        }

        /*Log.e("OFFSETS", arrayForOffset.size.toString())
        for (k in 0 until arrayForOffset.size) {
            Log.e("OFFSETS", arrayForOffset[k].toString())
        }*/

        val idealOffset = arrayForOffset.average()

        Log.e("OFFSETS_AVERAGE", idealOffset.toString())

        return hertzValues

    }

    private fun convertToAbsolutePitchValuesInHz(value: Float): Double {
        if (value != 0F) {
            val cqt_bin = value * PT_SLOPE + PT_OFFSET
            return FMIN * (2.0.pow(cqt_bin / BINS_PER_OCTAVE))
        } else {
            return 0.toDouble()
        }
    }

    private fun hzToOffset(hertzFloat: Float): Float {
        val h = (12 * kotlin.math.log2(hertzFloat / C0)).roundToInt().toFloat()
        Log.e("ROUND", h.toString())
        return (12 * kotlin.math.log2(hertzFloat / C0) - h).toFloat()
    }

    @Throws(IOException::class)
    private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFile)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val retFile = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        fileDescriptor.close()
        return retFile
    }

    @Throws(IOException::class)
    private fun getInterpreter(
        context: Context,
        modelName: String,
        useGpu: Boolean
    ): Interpreter {
        val tfliteOptions = Interpreter.Options()
        if (useGpu) {
            gpuDelegate = GpuDelegate()
            tfliteOptions.addDelegate(gpuDelegate)

            //val delegate =
            //GpuDelegate(GpuDelegate.Options().setQuantizedModelsAllowed(true))
        }

        tfliteOptions.setNumThreads(numberThreads)
        return Interpreter(loadModelFile(context, modelName), tfliteOptions)
    }

    fun close() {
        interpreter.close()
    }
}