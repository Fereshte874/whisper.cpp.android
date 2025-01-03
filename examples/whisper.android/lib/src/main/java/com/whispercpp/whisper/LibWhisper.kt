package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
//    private val scope: CoroutineScope = CoroutineScope(
//        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
//    )

    private val executorService = Executors.newSingleThreadExecutor()

    @Throws(ExecutionException::class, InterruptedException::class)
    fun transcribeData(
        data: FloatArray,
        language: String = "en",
        printTimestamp: Boolean = true
    ): String {
        return executorService.submit<String>(object : Callable<String> {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Throws(java.lang.Exception::class)
            override fun call(): String {
                check(ptr != 0L)
                val numThreads: Int = WhisperCpuConfig.preferredThreadCount
                Log.d(LOG_TAG, "Selecting $numThreads threads")

                val result = StringBuilder()
                synchronized(this) {
                    WhisperLib.fullTranscribe(ptr, numThreads, language, data)
                    val textCount = WhisperLib.getTextSegmentCount(ptr)
                    for (i in 0 until textCount) {
                        val sentence = WhisperLib.getTextSegment(ptr, i)
                        result.append(sentence)
                    }
                }
                return result.toString()
            }
        }).get()
    }

    @Throws(ExecutionException::class, InterruptedException::class)
    fun streamTranscribeData(data: FloatArray, language: String = "en"): String {
        return executorService.submit<String>(object : Callable<String> {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Throws(java.lang.Exception::class)
            override fun call(): String {
                check(ptr != 0L)
                val numThreads: Int = WhisperCpuConfig.preferredThreadCount
                Log.d(LOG_TAG, "Selecting $numThreads threads")

                val result = StringBuilder()
                synchronized(this) {
                    WhisperLib.fullStreamTranscribe(ptr, numThreads, language, data)
                    val textCount = WhisperLib.getTextSegmentCount(ptr)
                    for (i in 0 until textCount) {
                        val sentence = WhisperLib.getTextSegment(ptr, i)
                        result.append(sentence)
                    }
                }
                return result.toString()
            }
        }).get()
    }

    fun release() {
        executorService?.let {
            it.submit {
                if (ptr != 0L) {
                    WhisperLib.freeContext(ptr)
                    ptr = 0
                }
            }
            it.shutdownNow()
        }
    }

    protected fun finalize() {
        runBlocking {
            release()
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context with path $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            val ptr = WhisperLib.initContextFromInputStream(stream)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)

            if (ptr == 0L) {
                throw java.lang.RuntimeException("Couldn't create context from asset $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return WhisperLib.getSystemInfo()
        }
    }
}

private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadVfpv4 = false
            var loadV8fp16 = false
            if (isArmEabiV7a()) {
                // armeabi-v7a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("vfpv4")) {
                        Log.d(LOG_TAG, "CPU supports vfpv4")
                        loadVfpv4 = true
                    }
                }
            } else if (isArmEabiV8a()) {
                // ARMv8.2a needs runtime detection support
                val cpuInfo = cpuInfo()
                cpuInfo?.let {
                    Log.d(LOG_TAG, "CPU info: $cpuInfo")
                    if (cpuInfo.contains("fphp")) {
                        Log.d(LOG_TAG, "CPU supports fp16 arithmetic")
                        loadV8fp16 = true
                    }
                }
            }

            if (loadVfpv4) {
                Log.d(LOG_TAG, "Loading libwhisper_vfpv4.so")
                System.loadLibrary("whisper_vfpv4")
            } else if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        // JNI methods
        external fun initContextFromInputStream(inputStream: InputStream): Long
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long
        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            language: String,
            audioData: FloatArray
        )

        external fun fullStreamTranscribe(
            contextPtr: Long,
            numThreads: Int,
            language: String,
            audioData: FloatArray
        )

        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String
    }
}

//  500 -> 00:05.000
// 6000 -> 01:00.000
private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000

    val delimiter = if (comma) "," else "."
    return String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
}

private fun isArmEabiV7a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("armeabi-v7a")
}

private fun isArmEabiV8a(): Boolean {
    return Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
}

private fun cpuInfo(): String? {
    return try {
        File("/proc/cpuinfo").inputStream().bufferedReader().use {
            it.readText()
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
        null
    }
}