package edu.iitgoa.attendance.ui

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val displayDate = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault())
private val displayTime = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

/** "2026-03-10" -> "Tue 10 Mar 2026". Falls back to the raw value. */
fun formatDate(iso: String): String = runCatching {
    LocalDate.parse(iso).format(displayDate)
}.getOrDefault(iso)

/** "10:00:00" or "10:00" -> "10:00 AM". */
fun formatTime(value: String): String = runCatching {
    LocalTime.parse(if (value.count { it == ':' } == 1) "$value:00" else value)
        .format(displayTime)
}.getOrDefault(value)

fun formatSlot(start: String, end: String): String =
    "${formatTime(start)} – ${formatTime(end)}"

/** The server wants "HH:MM:SS"; a picker gives hour and minute. */
fun wireTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d:00", hour, minute)

fun wireDate(date: LocalDate): String = date.toString()
