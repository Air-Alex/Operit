package com.ai.assistance.operit.ui.floating.ui.fullscreen.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 消息显示组件
 * 显示用户消息和AI消息
 */
@Composable
fun MessageDisplay(
    userMessage: String,
    aiMessage: String,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom
    ) {
        // 用户消息
        if (userMessage.isNotEmpty()) {
            item {
                Text(
                    text = userMessage,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }

        // AI消息
        if (aiMessage.isNotBlank()) {
            item {
                Text(
                    text = aiMessage,
                    style = MaterialTheme.typography.bodyLarge, // Timon: 字太大了，调小一些
                    textAlign = TextAlign.Start,
                    color = Color.White,
                    modifier = Modifier.animateContentSize()
                )
            }
        }
    }
}

