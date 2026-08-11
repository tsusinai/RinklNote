package com.example.rinklnote.util

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Thin MediaRecorder wrapper that produces an AAC/M4A clip in the app cache dir.
 * Used by the in-app voice overlay (server ASR upload, then on-device fallback).
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null

    /** File being written by the current/last recording, if any. */
    var outputFile: File? = null
        private set

    val isRecording: Boolean
        get() = recorder != null

    /** Begin recording to a fresh cache file. Returns false on failure. */
    fun start(): Boolean {
        stopQuietly()
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            @Suppress("DEPRECATION")
            val r = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = r
            outputFile = file
            true
        } catch (e: Exception) {
            recorder = null
            outputFile = null
            file.delete()
            false
        }
    }

    /** Stop recording and finalize the file. Returns false if nothing was recorded. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        return try {
            r.stop()
            true
        } catch (e: Exception) {
            false
        } finally {
            r.release()
            recorder = null
        }
    }

    /** Stop and delete the partial clip. */
    fun cancel() {
        stopQuietly()
        outputFile?.delete()
        outputFile = null
    }

    private fun stopQuietly() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
    }
}
