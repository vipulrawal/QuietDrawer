package com.example.ui.util

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFormatter {

    fun formatCompactTimestamp(context: Context, timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = (now - timestampMs).coerceAtLeast(0L)
        val minutes = diffMs / 60_000L

        if (minutes < 1L) return "now"
        if (minutes < 60L) return "${minutes}m"

        val is24Hour = DateFormat.is24HourFormat(context)
        val timePattern = if (is24Hour) "HH:mm" else "h:mm a"
        val timeFormatter = SimpleDateFormat(timePattern, Locale.getDefault())

        val nowCal = Calendar.getInstance()
        val targetCal = Calendar.getInstance().apply { timeInMillis = timestampMs }

        return when {
            isSameDay(nowCal, targetCal) -> timeFormatter.format(Date(timestampMs))
            isYesterday(nowCal, targetCal) -> "Yesterday · ${timeFormatter.format(Date(timestampMs))}"
            nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) -> {
                val datePart = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
                "$datePart · ${timeFormatter.format(Date(timestampMs))}"
            }
            else -> {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
            }
        }
    }

    fun formatDateSectionHeader(timestampMs: Long): String {
        val nowCal = Calendar.getInstance()
        val targetCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return when {
            isSameDay(nowCal, targetCal) -> "Today"
            isYesterday(nowCal, targetCal) -> "Yesterday"
            nowCal.get(Calendar.YEAR) == targetCal.get(Calendar.YEAR) -> {
                SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(timestampMs))
            }
            else -> {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))
            }
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(now: Calendar, target: Calendar): Boolean {
        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return isSameDay(yesterday, target)
    }
}
