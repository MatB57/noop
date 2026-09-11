package com.noop.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.MetricRangeStat
import com.noop.analytics.RangeReport
import com.noop.analytics.RangeReportEngine
import com.noop.analytics.ReportMetric
import com.noop.analytics.ReportTrend
import com.noop.data.DailyMetric
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Trends Report (#436)
//
// Kotlin parity for the macOS/iOS shareable offline trends report. Builds the five
// metric day→value maps from the merged DailyMetric history, calls the pure, unit-tested
// RangeReportEngine for a chosen range, renders a clean multi-page PDF with android.graphics
// (a deterministic native Canvas — no Compose-window dependency), saves it to the app's
// cache via the existing FileProvider, and fires an ACTION_SEND share intent. No network.
//
// Honesty: an empty range (no metric carried a reading) renders a friendly "not enough
// data in this range yet" page, never a blank or fabricated sheet.

// MARK: - Range options (mirror Swift ReportRange)

/** The export window choices: trailing N days, or all history. */
enum class ReportRange(val days: Int?, val label: String, val longName: String) {
    Days30(30, "30d", tr("Last 30 days")),
    Days90(90, "90d", tr("Last 90 days")),
    Days180(180, "6M", tr("Last 6 months")),
    Days365(365, "1Y", tr("Last year")),
    All(null, tr("All"), tr("All history")),
}

// MARK: - Data builder (pure glue over the engine)

object TrendsReportData {

    /**
     * The nine day→value maps the engine consumes, keyed by ReportMetric.
     *
     * [stressByDay] is the persisted daily stress series ("yyyy-MM-dd" → 0–3), the same
     * stored series the Stress screen prioritises (#457). It isn't carried on DailyMetric, so
     * the caller loads it (via metricSeries) and passes it in; absent days stay out.
     */
    fun metricMaps(
        days: List<DailyMetric>,
        stressByDay: Map<String, Double> = emptyMap(),
    ): Map<ReportMetric, Map<String, Double>> {
        val workouts = HashMap<String, Double>()
        val recovery = HashMap<String, Double>()
        val sleepHours = HashMap<String, Double>()
        val hrv = HashMap<String, Double>()
        val restingHr = HashMap<String, Double>()
        val strain = HashMap<String, Double>()
        val respRate = HashMap<String, Double>()
        val skinTempDev = HashMap<String, Double>()
        for (d in days) {
            // Workouts logged that day (#457). Present on a recorded day (0 on a rest day), so
            // the row reflects the full window's activity cadence.
            d.exerciseCount?.let { workouts[d.day] = it.toDouble() }
            d.recovery?.let { recovery[d.day] = it }
            // Sleep reported in HOURS (the metric's unit); totalSleepMin is minutes asleep.
            // Days with no in-bed sleep stay absent.
            d.totalSleepMin?.let { if (it > 0) sleepHours[d.day] = it / 60.0 }
            d.avgHrv?.let { hrv[d.day] = it }
            d.restingHr?.let { restingHr[d.day] = it.toDouble() }
            d.strain?.let { strain[d.day] = it }
            // In-sleep physiology (v7 columns). Absent on days the strap didn't measure them.
            d.respRateBpm?.let { respRate[d.day] = it }
            d.skinTempDevC?.let { skinTempDev[d.day] = it }
        }
        // Daily stress score (#457), clamped to its 0–3 scale. Stored-only — the report never
        // re-derives a stress value (unlike the live Stress screen).
        val stress = stressByDay.mapValues { it.value.coerceIn(0.0, 3.0) }
        return mapOf(
            ReportMetric.WORKOUTS to workouts,
            ReportMetric.STRESS to stress,
            ReportMetric.RECOVERY to recovery,
            ReportMetric.SLEEP_HOURS to sleepHours,
            ReportMetric.HRV to hrv,
            ReportMetric.RESTING_HR to restingHr,
            ReportMetric.STRAIN to strain,
            ReportMetric.RESP_RATE to respRate,
            ReportMetric.SKIN_TEMP_DEV to skinTempDev,
        )
    }

    /**
     * The inclusive [start, end] "yyyy-MM-dd" window for a range, anchored to today's local
     * day (so a 30-day export is the last 30 calendar days, not the last 30 rows — matching
     * TrendsScreen's window rule). For [ReportRange.All], start is the earliest day present.
     */
    fun window(range: ReportRange, days: List<DailyMetric>, today: String): Pair<String, String> {
        val n = range.days ?: return Pair(days.minOfOrNull { it.day } ?: today, today)
        // Trailing N calendar days ending today; ISO yyyy-MM-dd sorts chronologically.
        val start = LocalDate.parse(today).minusDays((n - 1).toLong()).toString()
        return Pair(start, today)
    }

    /** Build the full report for a range from a DailyMetric history (+ stored stress — #457). */
    fun report(
        range: ReportRange,
        days: List<DailyMetric>,
        today: String,
        stressByDay: Map<String, Double> = emptyMap(),
    ): RangeReport {
        val (start, end) = window(range, days, today)
        return RangeReportEngine.build(metricMaps(days, stressByDay), start, end)
    }

    /** The in-range sparkline series (chronological values) for one metric. */
    fun series(
        metric: ReportMetric,
        days: List<DailyMetric>,
        start: String,
        end: String,
        stressByDay: Map<String, Double> = emptyMap(),
    ): List<Double> {
        val map = metricMaps(days, stressByDay)[metric] ?: emptyMap()
        return map.filter { it.key in start..end }.toList().sortedBy { it.first }.map { it.second }
    }
}

// MARK: - Metric → colour (mirror Swift's per-metric hue; raw ARGB for the native Canvas)

private fun ReportMetric.accentArgb(): Int = when (this) {
    ReportMetric.WORKOUTS -> 0xFFD98A3D.toInt()       // activity → the Effort (amber) world
    ReportMetric.STRESS -> 0xFFF0A020.toInt()         // the Stress world hue (matches stressColor)
    ReportMetric.RECOVERY -> 0xFF03E095.toInt()       // charge — WHOOP green (matches chargeColor)
    ReportMetric.STRAIN -> 0xFF4090E0.toInt()         // effort — WHOOP blue (matches effortColor)
    ReportMetric.SLEEP_HOURS -> 0xFF83A0B8.toInt()    // rest — slate (matches restColor)
    ReportMetric.HRV -> 0xFF4A90E2.toInt()            // HRV shares the rest/blue world
    ReportMetric.RESTING_HR -> 0xFFE0662F.toInt()     // burnt-orange risk hue
    ReportMetric.RESP_RATE -> 0xFF3FA9C9.toInt()      // breath / air — teal (metricCyan)
    ReportMetric.SKIN_TEMP_DEV -> 0xFFE0662F.toInt()  // temperature — warm (shares RHR's hue)
}

// MARK: - PDF renderer (deterministic native Canvas → PdfDocument)
//
// REWORK (#trends-pdf-rework): the old renderer drew every section at a hardcoded Y offset on
// a single fixed-height page, with no notion of how tall the content actually was. Past ~5
// metric cards (a 90-day-or-wider export easily carries all nine) the cards ran off the bottom
// of the page and straight through the footer, which was itself pinned to a fixed
// PAGE_H-relative Y regardless of what had already been drawn above it — the "elements
// overlap" the PDF was shipping with. The trend chip also floated at a Y band that overlapped
// the sparkline it sat beside.
//
// The renderer is now a small cumulative layout: [PageCursor] tracks a running Y and starts a
// fresh page whenever the next block wouldn't fit before the bottom margin, so pagination is a
// side effect of real measurement rather than a page-count guess. Every block that can vary in
// length (headline bullets, the footer legend, the empty-state body) is WORD-WRAPPED first via
// [wrapPlain] to get its real line count, and the block's height is computed from that count —
// never assumed. The trend chip moved into the title row (left of the mean value) so it no
// longer competes with the sparkline for the same vertical band.
object TrendsReportRenderer {

    // Page geometry — A4-ish portrait at the same ~612pt width the Swift page uses, so the
    // two platforms produce visually matched sheets. PAGE_H is now just the per-page height;
    // the document itself grows to as many pages as the content needs.
    private const val PAGE_W = 612
    private const val PAGE_H = 850
    private const val MARGIN = 28f

    // Palette (raw ARGB — the same hex tokens as Theme.kt's Palette / StrandPalette dark, WHOOP-reset).
    private const val SURFACE_BASE = 0xFF121518.toInt()
    private const val CARD_TOP = 0xFF15243C.toInt()
    private const val CARD_BOTTOM = 0xFF0B1424.toInt()
    private const val HAIRLINE = 0xFF21304A.toInt()
    private const val TEXT_PRIMARY = 0xFFF4F6F8.toInt()
    private const val TEXT_SECONDARY = 0xFFC8CFD8.toInt()
    private const val TEXT_TERTIARY = 0xFF8A94A4.toInt()
    private const val ACCENT = 0xFF60A0E0.toInt()     // WHOOP blue accent (gold killed 2026-06-22)
    private const val POSITIVE = 0xFF03E095.toInt()   // WHOOP green (matches statusPositive)
    private const val NEGATIVE = 0xFFE0662F.toInt()

    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)
    private val sansBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /**
     * A growing PDF document plus "where am I on the current page" state. Every draw* function
     * below takes one of these instead of a raw Canvas + top Y, so a block that doesn't fit the
     * remaining space on the current page cleanly starts a new one instead of running off the
     * bottom or through whatever comes next.
     */
    private class PageCursor(private val doc: PdfDocument) {
        private var pageNumber = 0
        private var pageOpen = false
        lateinit var canvas: Canvas
            private set
        var y: Float = MARGIN
            private set

        fun newPage() {
            if (pageOpen) doc.finishPage(currentPage)
            pageNumber += 1
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNumber).create()
            currentPage = doc.startPage(info)
            canvas = currentPage.canvas
            canvas.drawColor(SURFACE_BASE)
            pageOpen = true
            y = MARGIN
        }

        private lateinit var currentPage: PdfDocument.Page

        /** Advance the cursor by [dy] (after drawing a block of that height). */
        fun advance(dy: Float) {
            y += dy
        }

        /** Move the cursor to an absolute Y (used after a block computed its own end position). */
        fun moveTo(newY: Float) {
            y = newY
        }

        /**
         * Start a new page FIRST when [height] of content wouldn't fit above the bottom margin.
         * Call this before drawing any block whose height is known up front, so a card or
         * paragraph is never split across a page boundary or drawn past the page edge.
         */
        fun ensureSpace(height: Float) {
            if (!pageOpen) { newPage(); return }
            if (y + height > PAGE_H - MARGIN) newPage()
        }

        fun finish() {
            if (pageOpen) { doc.finishPage(currentPage); pageOpen = false }
        }
    }

    /**
     * Render [report] (+ its sparkline [series]) to a PDF file in the cache and return it, or
     * null on failure. Pure drawing — no view tree, so it's safe off the main thread and never
     * depends on a window. Paginates automatically: a short range is one page; a long one with
     * every metric populated spills onto as many pages as it needs.
     */
    fun renderPdf(
        context: Context,
        report: RangeReport,
        range: ReportRange,
        series: Map<ReportMetric, List<Double>>,
        generatedOn: String,
    ): File? = runCatching {
        val doc = PdfDocument()
        val cursor = PageCursor(doc)
        cursor.newPage()

        drawHeader(cursor, report, range)
        cursor.advance(18f)
        if (report.isEmpty) {
            drawEmptyState(cursor, range)
        } else {
            drawHeadlines(cursor, report)
            cursor.advance(18f)
            drawMetrics(cursor, report, series)
        }
        drawFooter(cursor, generatedOn)
        cursor.finish()

        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "NOOP-trends-${report.start}_to_${report.end}.pdf")
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        file
    }.getOrNull()

    // --- Header ---

    private fun drawHeader(cursor: PageCursor, report: RangeReport, range: ReportRange) {
        val cardH = 92f
        cursor.ensureSpace(cardH)
        val top = cursor.y
        drawCard(cursor.canvas, MARGIN, top, PAGE_W - MARGIN, top + cardH, ACCENT)

        val left = MARGIN + 16f
        var ty = top + 26f
        text(cursor.canvas, "NOOP", left, ty, 11f, sansBold, ACCENT, letterSpacing = 0.12f)
        textRight(cursor.canvas, range.longName.uppercase(), PAGE_W - MARGIN - 16f, ty, 10f, sansBold, TEXT_TERTIARY)
        ty += 30f
        text(cursor.canvas, tr("Trends report"), left, ty, 26f, sansBold, TEXT_PRIMARY)
        ty += 22f
        val span = report.totalDays
        val dayWord = if (span == 1) tr("day") else tr("days")
        text(
            cursor.canvas,
            "${prettyDate(report.start)}-${prettyDate(report.end)}   ·   $span $dayWord",
            left, ty, 12f, sans, TEXT_SECONDARY,
        )
        cursor.moveTo(top + cardH)
    }

    // --- Headlines ---

    private fun drawHeadlines(cursor: PageCursor, report: RangeReport) {
        val left = MARGIN + 16f
        val right = PAGE_W - MARGIN - 16f
        val lineH = 16f
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 12f }

        // Wrap every bullet up front — real multi-line wrapping, not the old single-line
        // ellipsize — so the card is exactly as tall as what it's about to draw.
        val wrapped = report.headlines.map { wrapPlain(bodyPaint, "•  $it", right - left) }
        val bodyLineCount = wrapped.sumOf { it.size }
        val headH = 22f + 24f + 12f  // SUMMARY label, "What changed" title, gap to first bullet
        val cardH = headH + bodyLineCount * lineH + 14f
        cursor.ensureSpace(cardH)

        val top = cursor.y
        drawCard(cursor.canvas, MARGIN, top, PAGE_W - MARGIN, top + cardH, ACCENT)

        var ty = top + 22f
        text(cursor.canvas, tr("SUMMARY"), left, ty, 10f, sansBold, ACCENT, letterSpacing = 0.1f)
        ty += 24f
        text(cursor.canvas, tr("What changed"), left, ty, 16f, sansBold, TEXT_PRIMARY)
        ty += 24f
        for (lines in wrapped) {
            for (line in lines) {
                text(cursor.canvas, line, left, ty, 12f, sans, TEXT_PRIMARY)
                ty += lineH
            }
        }
        cursor.moveTo(top + cardH)
    }

    // --- Metric cards ---

    private fun drawMetrics(cursor: PageCursor, report: RangeReport, series: Map<ReportMetric, List<Double>>) {
        cursor.ensureSpace(36f)
        text(cursor.canvas, tr("BY THE NUMBERS"), MARGIN, cursor.y + 4f, 10f, sansBold, TEXT_TERTIARY, letterSpacing = 0.1f)
        text(cursor.canvas, tr("Metrics"), MARGIN, cursor.y + 24f, 16f, sansBold, TEXT_PRIMARY)
        cursor.advance(36f)

        val cardH = 96f
        for (stat in report.metrics) {
            // One card fits fully on a page or starts a fresh one — never split across a break,
            // and never drawn past the bottom margin.
            cursor.ensureSpace(cardH)
            drawMetricCard(cursor.canvas, stat, series[stat.metric] ?: emptyList(), cursor.y)
            cursor.advance(cardH + 10f)
        }
    }

    private fun drawMetricCard(canvas: Canvas, stat: MetricRangeStat, spark: List<Double>, top: Float) {
        val cardH = 96f
        val accent = stat.metric.accentArgb()
        drawCard(canvas, MARGIN, top, PAGE_W - MARGIN, top + cardH, accent)

        val left = MARGIN + 16f
        val right = PAGE_W - MARGIN - 16f
        val titleY = top + 24f

        // Title (left) + trend chip + mean value, right-aligned as one group on the SAME row —
        // the chip used to float over the sparkline band below; grouping it with the mean here
        // keeps every element on its own row, never crossing into a neighbour's space.
        // Capped to the card's left half so a longer translated label can never run into the
        // trend-chip/mean group on the right half of the same row.
        text(
            canvas, tr(stat.metric.label).uppercase(), left, titleY, 11f, sansBold, accent,
            letterSpacing = 0.08f, maxWidth = (right - left) * 0.5f,
        )
        val meanStr = meanText(stat)
        val meanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansMedium; textSize = 14f }
        val meanW = meanPaint.measureText(meanStr)
        drawTrendChip(canvas, stat, pillRight = right - meanW - 8f, baselineY = titleY)
        textRight(canvas, meanStr, right, titleY, 14f, sansMedium, TEXT_PRIMARY)

        // Sparkline (decorative) below the title row.
        val sparkTop = top + 34f
        val sparkBottom = top + 60f
        if (spark.size >= 2) {
            drawSparkline(canvas, spark, left, sparkTop, right - 8f, sparkBottom, accent)
        } else {
            text(canvas, tr("Single reading in range"), left, sparkTop + 16f, 11f, sans, TEXT_TERTIARY)
        }

        // Divider.
        line(canvas, left, top + 66f, right, top + 66f, HAIRLINE)

        // Footer stats: Avg / Min(day) / Max(day) / Days, evenly spaced. Each value is clipped
        // to its own column width (a long "12.3 ms · Sep 8" MIN/MAX string, especially in
        // French, could otherwise bleed into the next column) rather than drawn unbounded.
        val cols = listOf(
            tr("AVG") to valueText(stat.mean, stat.metric),
            tr("MIN") to "${valueText(stat.min.value, stat.metric)} · ${prettyDate(stat.min.day)}",
            tr("MAX") to "${valueText(stat.max.value, stat.metric)} · ${prettyDate(stat.max.day)}",
            tr("DAYS") to "${stat.n}",
        )
        val colW = (right - left) / cols.size
        cols.forEachIndexed { i, (label, value) ->
            val cx = left + i * colW
            val colMaxWidth = colW - 8f
            text(canvas, label, cx, top + 80f, 9f, sansBold, TEXT_TERTIARY, letterSpacing = 0.06f, maxWidth = colMaxWidth)
            text(canvas, value, cx, top + 91f, 11f, sansMedium, TEXT_SECONDARY, maxWidth = colMaxWidth)
        }
    }

    /** Draws a small tinted trend pill ending at [pillRight], baseline-aligned with [baselineY]. */
    private fun drawTrendChip(canvas: Canvas, stat: MetricRangeStat, pillRight: Float, baselineY: Float) {
        val d = stat.halfDelta
        val (label, color) = if (stat.trend == ReportTrend.FLAT || abs(d) < 0.05) {
            tr("steady") to TEXT_TERTIARY
        } else {
            val up = d > 0
            val sign = if (up) "+" else "−"
            // Signed-deviation metric (skin-temp Δ): show the move, no good/bad verdict.
            val c = if (stat.metric.framesGoodBad) {
                if (up == stat.metric.higherIsBetter) POSITIVE else NEGATIVE
            } else {
                TEXT_TERTIARY
            }
            "$sign${round1(abs(d))}" to c
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = sansMedium
            textSize = 10f
            this.color = color
        }
        val tw = paint.measureText(label)
        val padX = 7f
        val pillLeft = pillRight - tw - padX * 2
        val pillTop = baselineY - 11f
        val pillBottom = baselineY + 3f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color, 0.14f) }
        canvas.drawRoundRect(RectF(pillLeft, pillTop, pillRight, pillBottom), 7f, 7f, bg)
        canvas.drawText(label, pillLeft + padX, baselineY, paint)
    }

    // --- Empty state ---

    private fun drawEmptyState(cursor: PageCursor, range: ReportRange) {
        val left = MARGIN + 16f
        val right = PAGE_W - MARGIN - 16f
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 12f }
        val body = tr("No workout, stress, recovery, sleep, HRV, resting-HR, strain, respiratory-rate or ") +
            tr("skin-temp readings fell inside") + " ${range.longName.lowercase()}. " +
            tr("Wear your strap a few more days, or pick a wider range, then export again.")
        val lines = wrapPlain(bodyPaint, body, right - left)
        val cardH = 30f + 22f + lines.size * 16f + 16f
        cursor.ensureSpace(cardH)

        val top = cursor.y
        drawCard(cursor.canvas, MARGIN, top, PAGE_W - MARGIN, top + cardH, null)
        text(cursor.canvas, tr("Not enough data in this range yet"), left, top + 30f, 16f, sansBold, TEXT_PRIMARY)
        var ty = top + 52f
        for (line in lines) {
            text(cursor.canvas, line, left, ty, 12f, sans, TEXT_SECONDARY)
            ty += 16f
        }
        cursor.moveTo(top + cardH)
    }

    // --- Footer ---

    private fun drawFooter(cursor: PageCursor, generatedOn: String) {
        // Provenance legend (#457): make clear which numbers are measured vs. NOOP's own derived scores,
        // so a clinician reading the PDF isn't misled into treating Recovery/Strain as clinical measures.
        // Part of the normal content flow now (not pinned to a fixed bottom Y) — it lands right after
        // whatever content precedes it, on the current page if there's room or a fresh one if not.
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 9f }
        val legend = tr(
            "How to read this: HRV, Resting HR, Sleep duration, Respiratory rate and Skin " +
                "temperature are measured from the strap (skin temp is shown as the deviation from your own " +
                "baseline). Workouts is the count of activities you logged or that were detected. Recovery, " +
                "Strain and Stress are NOOP's own on-device scores, not clinical measures - Recovery is a daily " +
                "readiness composite (HRV, resting HR, sleep and skin-temp trend), Strain is cardiovascular load " +
                "derived from heart rate, and Stress is a 0-3 autonomic-load index from resting HR and HRV.",
        )
        val legendLines = wrapPlain(legendPaint, legend, PAGE_W - 2 * MARGIN)
        val legendLineH = 11f
        val blockH = legendLines.size * legendLineH + 10f + 14f + 12f + 12f
        cursor.ensureSpace(blockH)

        var ty = cursor.y
        for (line in legendLines) {
            text(cursor.canvas, line, MARGIN, ty, 9f, sans, TEXT_TERTIARY)
            ty += legendLineH
        }
        ty += 10f
        line(cursor.canvas, MARGIN, ty, PAGE_W - MARGIN, ty, HAIRLINE)
        ty += 14f
        text(
            cursor.canvas,
            "${tr("Generated by NOOP on")} $generatedOn · ${tr("all on-device, no account, no cloud.")}",
            MARGIN, ty, 10f, sans, TEXT_TERTIARY,
        )
        ty += 12f
        text(cursor.canvas, tr("Informational only - not medical advice."), MARGIN, ty, 10f, sans, TEXT_TERTIARY)
        cursor.moveTo(ty + 12f)
    }

    // --- Primitives ---

    private fun drawCard(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, tint: Int?) {
        val rect = RectF(l, t, r, b)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                l, t, r, b, CARD_TOP, CARD_BOTTOM, android.graphics.Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, 18f, 18f, fill)
        if (tint != null) {
            // Faint diagonal hue wash, mirroring the frosted-card surface.
            val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    l, t, r, b,
                    intArrayOf(withAlpha(tint, 0.10f), withAlpha(tint, 0.03f), AndroidColor.TRANSPARENT),
                    floatArrayOf(0f, 0.5f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(rect, 18f, 18f, wash)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = if (tint != null) withAlpha(tint, 0.22f) else HAIRLINE
        }
        canvas.drawRoundRect(rect, 18f, 18f, border)
    }

    private fun drawSparkline(canvas: Canvas, values: List<Double>, l: Float, t: Float, r: Float, b: Float, color: Int) {
        val minV = values.min()
        val maxV = values.max()
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
        val w = (r - l).coerceAtLeast(1f)
        val h = (b - t).coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = l + (i.toFloat() / (values.size - 1)) * w
            val norm = ((v - minV) / span).toFloat().coerceIn(0f, 1f)
            val y = b - norm * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
        }
        canvas.drawPath(path, paint)
        // Bright head dot at the latest sample (the "now" idiom).
        val lastX = r
        val lastNorm = ((values.last() - minV) / span).toFloat().coerceIn(0f, 1f)
        val lastY = b - lastNorm * h
        canvas.drawCircle(lastX, lastY, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun line(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        canvas.drawLine(x0, y0, x1, y1, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = 1f })
    }

    private fun text(
        canvas: Canvas, s: String, x: Float, y: Float, size: Float, tf: Typeface, color: Int,
        letterSpacing: Float = 0f, maxWidth: Float = 0f,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color; this.letterSpacing = letterSpacing
        }
        val str = if (maxWidth > 0f) ellipsize(paint, s, maxWidth) else s
        canvas.drawText(str, x, y, paint)
    }

    private fun textRight(canvas: Canvas, s: String, right: Float, y: Float, size: Float, tf: Typeface, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf; textSize = size; this.color = color; textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(s, right, y, paint)
    }

    /**
     * Real word-wrap of [s] into lines that each fit [maxWidth] under [paint] — greedy, breaking
     * on spaces. Shared by every block whose height depends on how much it wraps (headlines, the
     * footer legend, the empty-state body), so a block's computed height and its drawn height are
     * always the same number: this is measured ONCE and both used to size the card and to draw.
     */
    private fun wrapPlain(paint: Paint, s: String, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        val current = StringBuilder()
        for (word in s.split(" ")) {
            val trial = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(trial) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current.clear()
                current.append(word)
            } else {
                if (current.isNotEmpty()) current.append(" ")
                current.append(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        if (lines.isEmpty()) lines.add("")
        return lines
    }

    private fun ellipsize(paint: Paint, s: String, maxWidth: Float): String {
        if (paint.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && paint.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    // --- Formatting (mirror the Swift page) ---

    private fun valueText(v: Double, metric: ReportMetric): String {
        // One decimal for sleep hours, respiratory rate, skin-temp Δ and the 0–3 stress score
        // (metric.usesOneDecimal); whole numbers for the scores + bpm + ms + workout count.
        // Skin-temp is a signed deviation, so a positive reading gets an explicit "+" to keep
        // it from reading as an absolute temperature.
        val unit = metric.unit
        var num = if (metric.usesOneDecimal) round1(v) else "${v.roundToInt()}"
        if (metric == ReportMetric.SKIN_TEMP_DEV && v > 0) num = "+$num"
        return if (unit.isEmpty()) num else "$num $unit"
    }

    private fun meanText(stat: MetricRangeStat): String = valueText(stat.mean, stat.metric)

    private fun round1(x: Double): String = String.format(Locale.US, "%.1f", (x * 10).roundToInt() / 10.0)

    /** "Jun 15" from "2026-06-15" via the engine's pure parse (no Calendar/locale). */
    private fun prettyDate(ymd: String): String {
        val p = RangeReportEngine.parseYMD(ymd) ?: return ymd
        val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val name = if (p.second in 1..12) months[p.second - 1] else "${p.second}"
        return "$name ${p.third}"
    }
}

// MARK: - Share

object TrendsReportShare {

    /**
     * Build the report for [range] from [days], render the PDF, and fire a share intent via
     * the existing FileProvider. An empty range still produces a valid PDF — the renderer draws
     * an honest "not enough data in this range yet" page rather than a blank or fabricated sheet.
     */
    fun export(
        context: Context,
        days: List<DailyMetric>,
        range: ReportRange,
        stressByDay: Map<String, Double> = emptyMap(),
    ) {
        runCatching {
            val today = LocalDate.now().toString()
            val report = TrendsReportData.report(range, days, today, stressByDay)
            val series = ReportMetric.allCases.associateWith {
                TrendsReportData.series(it, days, report.start, report.end, stressByDay)
            }
            val generatedOn = LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
            val file = TrendsReportRenderer.renderPdf(context, report, range, series, generatedOn)
                ?: run {
                    Toast.makeText(context, tr("Couldn't build the report."), Toast.LENGTH_LONG).show()
                    return
                }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "NOOP trends report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, tr("Share trends report")))
        }.onFailure {
            Toast.makeText(context, "${tr("Couldn't share the report:")} ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}

// MARK: - Export section (embeddable Composable for Settings / Trends)

/**
 * The trends-report export entry: a short blurb, a range SegmentedPillControl, the resolved
 * range's day-count, and a gold "Export PDF" CTA. Built only from the locked component system.
 * Drop it into Settings (or Trends). Reads the merged history off [vm].
 */
@Composable
fun TrendsReportExportSection(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf(ReportRange.Days90) }

    // Stored daily stress series ("yyyy-MM-dd" → 0–3) for the Stress row (#457). Loaded once
    // from the same "my-whoop" series the Stress screen reads; empty until it arrives.
    var stressByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val rows = runCatching {
            vm.repo.metricSeries("my-whoop", "stress", "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList())
        stressByDay = rows.associate { it.day to it.value }
    }

    NoopCard(modifier = modifier, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Overline(tr("Export"))
            Text(tr("Trends report (PDF)"), style = NoopType.title2, color = Palette.textPrimary)
            Text(
                tr(
                    "A clean, shareable one-page PDF of your recovery, sleep, HRV, resting heart rate " +
                        "and strain over a date range. Built and saved on your phone - nothing leaves the device.",
                ),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )

            Overline(tr("Range"), color = Palette.textTertiary)
            SegmentedPillControl(
                items = ReportRange.entries.toList(),
                selection = range,
                label = { it.label },
                onSelect = { range = it },
            )
            Text(range.longName, style = NoopType.footnote, color = Palette.textTertiary)

            // Routed through the unified NoopButton (crisp filled accent, no gold) — the same button
            // system every other CTA uses, mirroring the iOS exportReportRow.
            NoopButton(
                text = tr("Export PDF"),
                leadingIcon = Icons.Filled.IosShare,
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                onClick = { TrendsReportShare.export(context, days, range, stressByDay) },
            )

            Text(
                tr("The share sheet can save the PDF to Files, or send it on."),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}
