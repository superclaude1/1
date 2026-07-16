package com.storybrain.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val isUser: Boolean,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
