package com.shangjin.frameecho.core.media.utils

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Thread-safe utilities for date and time parsing and formatting.
 * Replaces repeated instantiation of SimpleDateFormat.
 */
object DateTimeUtils {
    private val systemZone: ZoneId
        get() = ZoneId.systemDefault()

    private val ISO_LOCAL_OUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val ISO_OFFSET_OUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val EXIF_OUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    // Offset/Z-aware input formats
    private val FMT_COMPACT_MILLIS_OFFSET = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX", Locale.US)
    private val FMT_ISO_SECONDS_X = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
    private val FMT_ISO_OFFSET_NO_COLON = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
    private val FMT_ISO_MILLIS_OFFSET_NO_COLON = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    // Local (no offset) input formats
    private val FMT_COMPACT_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)
    private val FMT_ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val FMT_ISO_MILLIS_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val FMT_SPACE_SEP_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val FMT_COMPACT_NO_T = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US)
    private val FMT_SPACE_SEP_LOCAL = DateTimeFormatter.ofPattern("yyyy MM dd", Locale.US)

    private val OFFSET_INPUT_FORMATTERS = listOf(
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        FMT_ISO_SECONDS_X,
        FMT_ISO_OFFSET_NO_COLON,
        FMT_ISO_MILLIS_OFFSET_NO_COLON,
        FMT_COMPACT_MILLIS_OFFSET
    )

    private val LOCAL_DATE_TIME_INPUT_FORMATTERS = listOf(
        FMT_ISO_LOCAL,
        FMT_ISO_MILLIS_LOCAL,
        FMT_COMPACT_LOCAL,
        FMT_SPACE_SEP_DATE_TIME,
        FMT_COMPACT_NO_T
    )

    /**
     * Normalizes a date string to ISO 8601 format.
     * Offset-bearing inputs preserve their original offset.
     * Local inputs remain local (no forced UTC conversion).
     * Returns the original string if parsing fails.
     */
    fun normalizeToIso(dateStr: String): String {
        if (dateStr.isBlank()) return dateStr
        return when (val parsed = parseDateTime(dateStr)) {
            is ParsedDateTime.Offset -> formatOffsetIso(parsed.value)
            is ParsedDateTime.Local -> ISO_LOCAL_OUT_FORMATTER.format(parsed.value)
            is ParsedDateTime.DateOnly -> ISO_LOCAL_OUT_FORMATTER.format(parsed.value.atStartOfDay())
            null -> dateStr
        }
    }

    /**
     * Converts an ISO date string to EXIF format (yyyy:MM:dd HH:mm:ss).
     * Returns null if parsing fails.
     */
    fun convertToExif(isoDate: String): String? {
        if (isoDate.isBlank()) return null
        return when (val parsed = parseDateTime(isoDate)) {
            is ParsedDateTime.Offset -> EXIF_OUT_FORMATTER.format(
                parsed.value.atZoneSameInstant(systemZone).toLocalDateTime()
            )
            is ParsedDateTime.Local -> EXIF_OUT_FORMATTER.format(parsed.value)
            is ParsedDateTime.DateOnly -> EXIF_OUT_FORMATTER.format(parsed.value.atStartOfDay())
            null -> null
        }
    }

    /**
     * Parses a date string to milliseconds since epoch.
     * Returns null if parsing fails.
     */
    fun parseToMillis(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        return when (val parsed = parseDateTime(dateStr)) {
            is ParsedDateTime.Offset -> parsed.value.toInstant().toEpochMilli()
            is ParsedDateTime.Local -> parsed.value.atZone(systemZone).toInstant().toEpochMilli()
            is ParsedDateTime.DateOnly -> parsed.value.atStartOfDay(systemZone).toInstant().toEpochMilli()
            null -> null
        }
    }

    private sealed class ParsedDateTime {
        data class Offset(val value: OffsetDateTime) : ParsedDateTime()
        data class Local(val value: LocalDateTime) : ParsedDateTime()
        data class DateOnly(val value: LocalDate) : ParsedDateTime()
    }

    private fun parseDateTime(dateStr: String): ParsedDateTime? {
        val trimmed = dateStr.trim()
        if (trimmed.isEmpty()) return null

        parseOffsetDateTime(trimmed)?.let { return ParsedDateTime.Offset(it) }
        parseLocalDateTime(trimmed)?.let { return ParsedDateTime.Local(it) }
        parseLocalDate(trimmed)?.let { return ParsedDateTime.DateOnly(it) }
        return null
    }

    private fun parseOffsetDateTime(dateStr: String): OffsetDateTime? {
        // Fast path: if there is no timezone/offset indicator, it's not an OffsetDateTime
        val hasZ = dateStr.contains('Z') || dateStr.contains('+') || dateStr.contains('-')
        if (!hasZ) return null

        for (formatter in OFFSET_INPUT_FORMATTERS) {
            val pos = java.text.ParsePosition(0)
            val parsed = formatter.parseUnresolved(dateStr, pos)
            if (parsed != null && pos.errorIndex < 0 && pos.index == dateStr.length) {
                try {
                    return OffsetDateTime.parse(dateStr, formatter)
                } catch (_: DateTimeParseException) {
                    continue
                }
            }
        }
        return null
    }

    private fun parseLocalDateTime(dateStr: String): LocalDateTime? {
        val hasT = dateStr.contains('T')
        val hasSpace = dateStr.contains(' ')

        // Use heuristics to select a subset of formatters, preventing performance degradation
        // caused by exception handling in sequential parsing loops for invalid/mismatched formats.
        val formatters = if (hasT) {
            listOf(FMT_ISO_LOCAL, FMT_ISO_MILLIS_LOCAL, FMT_COMPACT_LOCAL)
        } else if (hasSpace) {
            listOf(FMT_SPACE_SEP_DATE_TIME)
        } else {
            listOf(FMT_COMPACT_NO_T)
        }

        for (formatter in formatters) {
            val pos = java.text.ParsePosition(0)
            val parsed = formatter.parseUnresolved(dateStr, pos)
            if (parsed != null && pos.errorIndex < 0 && pos.index == dateStr.length) {
                try {
                    return LocalDateTime.parse(dateStr, formatter)
                } catch (_: DateTimeParseException) {
                    continue
                }
            }
        }
        return null
    }

    private fun parseLocalDate(dateStr: String): LocalDate? {
        val pos = java.text.ParsePosition(0)
        val parsed = FMT_SPACE_SEP_LOCAL.parseUnresolved(dateStr, pos)
        if (parsed != null && pos.errorIndex < 0 && pos.index == dateStr.length) {
            try {
                return LocalDate.parse(dateStr, FMT_SPACE_SEP_LOCAL)
            } catch (_: DateTimeParseException) {
                return null
            }
        }
        return null
    }

    private fun formatOffsetIso(offsetDateTime: OffsetDateTime): String {
        val formatted = ISO_OFFSET_OUT_FORMATTER.format(offsetDateTime)
        return if (formatted.endsWith("+00:00")) {
            formatted.removeSuffix("+00:00") + "Z"
        } else {
            formatted
        }
    }
}
