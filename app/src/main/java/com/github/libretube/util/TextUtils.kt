package com.github.libretube.util

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import android.icu.text.RelativeDateTimeFormatter
import android.net.Uri
import android.text.format.DateUtils
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.extensions.formatShort
import com.github.libretube.extensions.toLocalDate
import com.google.common.math.IntMath.pow
import kotlinx.datetime.toJavaLocalDate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlinx.datetime.LocalDate as KotlinLocalDate

object TextUtils {
    /**
     * Separator used for descriptions
     */
    const val SEPARATOR = " • "

    /**
     * Date time formatter which uses the [FormatStyle.MEDIUM] format style.
     */
    private val MEDIUM_DATE_FORMATTER = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    /**
     * Date time formatter which doesn't use any forbidden characters for file names like ':'
     */
    private val SAFE_FILENAME_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH_mm_ss")

    /**
     * Localize the date from a date string, using the medium format.
     * @param date The date to parse
     * @return localized date string
     */
    fun localizeDate(date: KotlinLocalDate): String {
        return date.toJavaLocalDate().format(MEDIUM_DATE_FORMATTER)
    }

    fun localizeInstant(instant: kotlinx.datetime.Instant): String {
        return localizeDate(instant.toLocalDate())
    }

    /**
     * Get time in seconds from a YouTube video link.
     * @return Time in seconds
     */
    fun String.toTimeInSeconds(): Long? = parseTimeString(this)?.toLong()

    fun String.parseDurationString(): Float? = parseTimeString(this)

    private fun parseTimeString(timeString: String): Float? {
        if (timeString.all { it.isDigit() }) return timeString.toLongOrNull()?.toFloat()

        if (timeString.all { it.isDigit() || ",.:".contains(it) }) {
            var secondsTotal = 0
            var secondsScoped = 0

            var milliseconds = 0
            var inMillis = false
            var millisLength = 0

            for (char in timeString) {
                if (inMillis) {
                    if (!char.isDigit()) break

                    milliseconds *= 10
                    milliseconds += char.digitToInt()
                    millisLength++
                } else if (char.isDigit()) {
                    secondsScoped *= 10
                    secondsScoped += char.digitToInt()
                } else if (char == ':') {
                    secondsTotal += secondsScoped
                    secondsTotal *= 60
                    secondsScoped = 0
                } else if (",.".contains(char)) {
                    secondsTotal += secondsScoped
                    secondsScoped = 0
                    inMillis = true
                }
            }
            secondsTotal += secondsScoped

            val millisDecimal = milliseconds.toFloat() / pow(10, millisLength)
            return secondsTotal.toFloat() + millisDecimal
        }

        return Duration.parseOrNull(timeString)?.inWholeMilliseconds?.toFloat()?.div(1000)
    }

    /**
     * Get video id if the link is a valid youtube video link
     */
    fun getVideoIdFromUri(uri: Uri) = when (uri.host) {
        "www.youtube.com", "m.youtube.com", "piped.video" -> uri.getQueryParameter("v")
        "youtu.be" -> uri.lastPathSegment
        else -> null
    }

    fun formatRelativeDate(unixTime: Long): CharSequence {
        val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(unixTime), ZoneId.systemDefault())
        val now = LocalDateTime.now()
        val months = date.until(now, ChronoUnit.MONTHS)
        val weeks = date.until(now, ChronoUnit.WEEKS)

        val (timeFormat, time) = when {
            months >= 12 -> RelativeDateTimeFormatter.RelativeUnit.YEARS to months / 12
            months > 0 -> RelativeDateTimeFormatter.RelativeUnit.MONTHS to months
            weeks > 0 -> RelativeDateTimeFormatter.RelativeUnit.WEEKS to weeks
            else -> return  DateUtils.getRelativeTimeSpanString(unixTime, System.currentTimeMillis(), 0)
        }

        return RelativeDateTimeFormatter.getInstance()
            .format(time.toDouble(), RelativeDateTimeFormatter.Direction.LAST, timeFormat)
    }

    fun formatBitrate(bitrate: Int?): String {
        bitrate ?: return ""
        return "${bitrate / 1024}kbps"
    }

    fun limitTextToLength(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.take(maxLength) + "…"
    }

    fun getUserAgent(context: Context): String {
        return "${context.packageName}/${BuildConfig.VERSION_NAME}"
    }

    /**
     * Channel, views and age for video lists, e.g. "Channel · 30K views · 7h", with a small,
     * muted separator. Ages are abbreviated so they aren't cut off (see [formatRelativeDateShort]).
     */
    fun formatViewsString(context: Context, views: Long, uploaded: Long, uploader: String? = null): CharSequence {
        val viewsString = views.takeIf { it != -1L }?.formatShort()?.let {
            context.getString(R.string.view_count, it)
        }
        val uploadDate = uploaded.takeIf { it > 0 }?.let {
            formatRelativeDateShort(it)
        }
        return joinWithQuietDots(context, listOfNotNull(uploader, viewsString, uploadDate))
    }

    /**
     * Short age of a past date: now, 5m, 7h, 3d, 2w, 4mo, 1y. Future dates (upcoming videos)
     * keep the full wording, e.g. "in 2 days".
     */
    fun formatRelativeDateShort(unixTime: Long): CharSequence {
        val elapsedMs = System.currentTimeMillis() - unixTime
        if (elapsedMs < 0) return formatRelativeDate(unixTime)

        val minutes = elapsedMs / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "now"
            hours < 1 -> "${minutes}m"
            days < 1 -> "${hours}h"
            days < 7 -> "${days}d"
            days < 30 -> "${days / 7}w"
            days < 365 -> "${(days / 30).coerceAtLeast(1)}mo"
            else -> "${days / 365}y"
        }
    }

    /** Joins [parts] with a small, muted middle dot. */
    fun joinWithQuietDots(context: Context, parts: List<CharSequence>): CharSequence {
        val dotColor = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                Color.GRAY
            ),
            QUIET_DOT_ALPHA
        )
        val builder = SpannableStringBuilder()
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                val start = builder.length
                builder.append(QUIET_DOT)
                builder.setSpan(RelativeSizeSpan(0.8f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(dotColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            builder.append(part)
        }
        return builder
    }

    private const val QUIET_DOT = " · "
    private const val QUIET_DOT_ALPHA = 0x99

    /**
     * Timestamp of the current time which doesn't use any forbidden characters for file names like ':'
     */
    fun getFileSafeTimeStampNow(): String {
        return SAFE_FILENAME_DATETIME_FORMATTER.format(LocalDateTime.now())
    }
}
