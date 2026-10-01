package com.svifi.vitalyspeak

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal RIFF/WAVE writer for 16-bit little-endian PCM. */
object Wav {
    private const val HEADER = 44

    fun fromPcm16(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val bytesPerSample = 2
        val buf = ByteBuffer.allocate(HEADER + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + pcm.size)                              // rest of the file after this field
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                                         // PCM fmt chunk size
        buf.putShort(1)                                        // format: PCM
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * channels * bytesPerSample)     // byte rate
        buf.putShort((channels * bytesPerSample).toShort())   // block align
        buf.putShort((bytesPerSample * 8).toShort())           // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcm.size)
        buf.put(pcm)
        return buf.array()
    }
}
