package com.github.libretube.helpers

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import com.google.android.material.color.MaterialColors
import kotlin.random.Random

/**
 * Colours taken from a video's artwork, so the players feel rich and different for every video
 * instead of one generic tone.
 */
object ArtworkColors {
    /** The most characteristic (saturated, not too dark or light) colour of [bitmap], if any. */
    fun dominantVibrant(bitmap: Bitmap): Int? {
        val small = bitmap.copy(Bitmap.Config.ARGB_8888, false).scale(32, 32)
        val bins = FloatArray(HUE_BINS)
        val sums = Array(HUE_BINS) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (x in 0 until small.width) for (y in 0 until small.height) {
            Color.colorToHSV(small.getPixel(x, y), hsv)
            val (h, sat, value) = Triple(hsv[0], hsv[1], hsv[2])
            if (sat < 0.2f || value < 0.2f || value > 0.98f) continue
            val weight = sat * value
            val bin = ((h / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            bins[bin] += weight
            sums[bin][0] += h * weight
            sums[bin][1] += sat * weight
            sums[bin][2] += value * weight
        }
        val best = bins.indices.maxByOrNull { bins[it] } ?: return null
        val total = bins[best]
        if (total < MIN_WEIGHT) return null
        return Color.HSVToColor(
            floatArrayOf(sums[best][0] / total, sums[best][1] / total, sums[best][2] / total)
        )
    }

    private fun isNight(context: Context) =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun withHsv(color: Int, saturation: (Float) -> Float, value: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = saturation(hsv[1]).coerceIn(0f, 1f)
        hsv[2] = value
        return Color.HSVToColor(hsv)
    }

    /**
     * A deep, saturated wash from [color] down to the background, drawn over the blurred artwork:
     * see-through at the top (the artwork glows through) and solid towards the controls.
     */
    fun playerWash(context: Context, color: Int): Drawable {
        val night = isNight(context)
        val colors = if (night) {
            intArrayOf(
                ColorUtils.setAlphaComponent(withHsv(color, { (it * 1.15f).coerceIn(0.5f, 0.9f) }, 0.42f), 0x66),
                ColorUtils.setAlphaComponent(withHsv(color, { (it * 1.05f).coerceIn(0.45f, 0.85f) }, 0.26f), 0xD9),
                withHsv(color, { (it * 0.8f).coerceIn(0.35f, 0.75f) }, 0.14f),
                withHsv(color, { (it * 0.6f).coerceIn(0.25f, 0.6f) }, 0.08f)
            )
        } else {
            intArrayOf(
                ColorUtils.setAlphaComponent(withHsv(color, { (it * 0.8f).coerceIn(0.25f, 0.6f) }, 0.95f), 0x55),
                ColorUtils.setAlphaComponent(withHsv(color, { (it * 0.45f).coerceIn(0.15f, 0.35f) }, 0.96f), 0xCC),
                withHsv(color, { (it * 0.25f).coerceIn(0.08f, 0.2f) }, 0.97f),
                withHsv(color, { (it * 0.15f).coerceIn(0.04f, 0.12f) }, 0.98f)
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
    }

    /**
     * Accent colours for controls on the artwork wash (play button, progress bar): bright in
     * dark mode (with a dark icon), deep in light mode (with a white icon).
     */
    data class Accent(val light: Int, val deep: Int, val onAccent: Int, val main: Int)

    fun accent(context: Context, color: Int): Accent {
        return if (isNight(context)) {
            val main = withHsv(color, { (it * 0.75f).coerceIn(0.35f, 0.6f) }, 0.88f)
            Accent(
                light = withHsv(color, { (it * 0.55f).coerceIn(0.25f, 0.45f) }, 0.97f),
                deep = withHsv(color, { (it * 0.95f).coerceIn(0.45f, 0.75f) }, 0.74f),
                onAccent = withHsv(color, { (it * 0.9f).coerceIn(0.4f, 0.8f) }, 0.16f),
                main = main
            )
        } else {
            val main = withHsv(color, { (it * 1.1f).coerceIn(0.55f, 0.85f) }, 0.52f)
            Accent(
                light = withHsv(color, { (it * 0.9f).coerceIn(0.45f, 0.75f) }, 0.66f),
                deep = withHsv(color, { (it * 1.2f).coerceIn(0.6f, 0.9f) }, 0.38f),
                onAccent = Color.WHITE,
                main = main
            )
        }
    }

    /** Linear blend between two accents ([fraction] 0 = [from], 1 = [to]), for colour fades. */
    fun blend(from: Accent, to: Accent, fraction: Float) = Accent(
        light = ColorUtils.blendARGB(from.light, to.light, fraction),
        deep = ColorUtils.blendARGB(from.deep, to.deep, fraction),
        onAccent = ColorUtils.blendARGB(from.onAccent, to.onAccent, fraction),
        main = ColorUtils.blendARGB(from.main, to.main, fraction)
    )

    /** The theme's own accent in the same shape (used when the video's colours are turned off). */
    fun themeAccent(context: Context): Accent {
        val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
        val tertiary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, primary)
        val onPrimary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
        return Accent(
            light = primary,
            deep = ColorUtils.blendARGB(primary, tertiary, 0.45f),
            onAccent = onPrimary,
            main = primary
        )
    }

    /** The play button's drawable and its colour layer, whose colours can be animated. */
    class PlayButton(val drawable: Drawable, val base: GradientDrawable)

    /** The play button: a lit oval from [Accent.light] to [Accent.deep] with a soft top light. */
    fun playButton(context: Context, accent: Accent): PlayButton {
        val base = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(accent.light, accent.deep)
        ).apply { shape = GradientDrawable.OVAL }
        val light = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(0x55, 255, 255, 255), Color.TRANSPARENT, Color.TRANSPARENT)
        ).apply { shape = GradientDrawable.OVAL }
        val rim = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((context.resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.argb(0x2E, 255, 255, 255))
        }
        val layers = android.graphics.drawable.LayerDrawable(arrayOf(base, light, rim))
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.argb(0x33, 255, 255, 255)), layers, null
        )
        return PlayButton(ripple, base)
    }

    /** A gentle tint of [color] for surfaces like the mini player card and the video page. */
    fun surfaceTint(context: Context, color: Int, strength: Float): Int {
        val night = isNight(context)
        val base = if (night) withHsv(color, { (it * 0.9f).coerceIn(0.35f, 0.8f) }, 0.30f)
        else withHsv(color, { (it * 0.7f).coerceIn(0.25f, 0.6f) }, 0.85f)
        return ColorUtils.setAlphaComponent(base, (strength * 255).toInt().coerceIn(0, 255))
    }

    /**
     * Very fine film grain, tiled. Laid over large gradients it breaks up the visible steps
     * (banding) that 8-bit colour produces on big, dark, smooth areas.
     */
    fun grain(context: Context): Drawable {
        val size = GRAIN_SIZE
        val pixels = IntArray(size * size) {
            val light = Random.nextBoolean()
            val alpha = Random.nextInt(GRAIN_MAX_ALPHA)
            if (light) Color.argb(alpha, 255, 255, 255) else Color.argb(alpha, 0, 0, 0)
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(context.resources, bitmap).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            isFilterBitmap = false
        }
    }

    private const val HUE_BINS = 12
    private const val MIN_WEIGHT = 8f
    private const val GRAIN_SIZE = 128
    private const val GRAIN_MAX_ALPHA = 14
}
