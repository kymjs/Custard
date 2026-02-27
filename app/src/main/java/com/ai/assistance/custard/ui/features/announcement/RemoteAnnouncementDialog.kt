package com.ai.assistance.custard.ui.features.announcement

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
fun RemoteAnnouncementDialog(
    title: String,
    body: String,
    acknowledgeText: String,
    onAcknowledge: () -> Unit,
    countdownSeconds: Int = 5
) {
    var remainingSeconds by remember(countdownSeconds) { mutableStateOf(countdownSeconds.coerceAtLeast(0)) }

    LaunchedEffect(countdownSeconds) {
        while (remainingSeconds > 0) {
            delay(1000)
            remainingSeconds--
        }
    }

    val acknowledgeEnabled = remainingSeconds == 0
    val label = if (acknowledgeEnabled) {
        acknowledgeText
    } else {
        "$acknowledgeText (${remainingSeconds}s)"
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = title) },
        text = { Text(text = body) },
        confirmButton = {
            TextButton(onClick = onAcknowledge, enabled = acknowledgeEnabled) {
                Text(text = label)
            }
        }
    )
}
