package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.VoiceResult

interface NLUService {
    suspend fun parse(text: String, userId: Long): VoiceResult
}
