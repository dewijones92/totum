@file:Suppress("MagicNumber")

package com.dewijones92.totum.exsurge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.dewijones92.totum.theme.Cyan40
import com.dewijones92.totum.theme.Cyan80
import com.dewijones92.totum.theme.Cyan90
import com.dewijones92.totum.theme.Lemon40
import com.dewijones92.totum.theme.Lemon80
import com.dewijones92.totum.theme.Tangerine10
import com.dewijones92.totum.theme.Tangerine20
import com.dewijones92.totum.theme.Tangerine40
import com.dewijones92.totum.theme.Tangerine60
import com.dewijones92.totum.theme.Tangerine80
import kotlin.math.cos
import kotlin.math.sin

private val face = Tangerine60.toArgb()
private val outline = Tangerine20.toArgb()
private val cheek = Tangerine80.toArgb()
private val brow = Tangerine10.toArgb()
private val laurel = Lemon80.toArgb()
private val laurelEdge = Lemon40.toArgb()

object SurgiusPainter {
    private const val HEAD_RADIUS = 50f
    const val EXTENT = 140f
    private val laurelAngles = listOf(-165f, -145f, -125f, -105f, -75f, -55f, -35f, -15f)

    fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float, mood: Mood) {
        val scale = size / EXTENT
        canvas.withTranslation(cx, cy) {
            scale(scale, scale)
            head(this)
            when (mood) {
                Mood.SUMMONING -> MoodFaces.summoning(this)
                Mood.CHEERING -> MoodFaces.cheering(this)
                Mood.COUNTING -> MoodFaces.counting(this)
                Mood.FREEING -> MoodFaces.freeing(this)
                Mood.WOUNDED -> MoodFaces.wounded(this)
                Mood.SLEEPING -> MoodFaces.sleeping(this)
                Mood.CONTENT -> MoodFaces.content(this)
            }
        }
    }

    fun faceBitmap(sizePx: Int, mood: Mood): Bitmap =
        createBitmap(sizePx, sizePx).also {
            draw(Canvas(it), sizePx / 2f, sizePx / 2f + sizePx * 0.04f, sizePx.toFloat(), mood)
        }

    fun logoBitmap(sizePx: Int): Bitmap = createBitmap(sizePx, sizePx).also { bitmap ->
        val canvas = Canvas(bitmap)
        val s = sizePx / 200f
        canvas.scale(s, s)
        val ring = stroke(Cyan90.toArgb(), 12f)
        canvas.drawCircle(100f, 100f, 86f, ring)
        val sweep = stroke(Cyan40.toArgb(), 12f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawArc(RectF(14f, 14f, 186f, 186f), -90f, 180f, false, sweep)
        canvas.drawCircle(100f, 100f, 68f, fill(Tangerine40.toArgb()))
        canvas.drawText(GLYPH, 100f, 126f, glyph(Color.WHITE, 76f))
        canvas.drawCircle(100f, 14f, 9f, fill(laurel))
    }

    fun glyphBitmap(sizePx: Int): Bitmap = createBitmap(sizePx, sizePx).also {
        val paint = glyph(Color.WHITE, sizePx * 0.86f)
        val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        Canvas(it).drawText(GLYPH, sizePx / 2f, y, paint)
    }

    private fun head(canvas: Canvas) {
        canvas.drawCircle(0f, 0f, HEAD_RADIUS, fill(face))
        canvas.drawCircle(0f, 0f, HEAD_RADIUS, stroke(outline, 2.5f))
        canvas.drawOval(RectF(-39f, 12f, -21f, 24f), fill(cheek))
        canvas.drawOval(RectF(21f, 12f, 39f, 24f), fill(cheek))
        val leafFill = fill(laurel)
        val leafEdge = stroke(laurelEdge, 1.5f)
        laurelAngles.forEach { degrees ->
            val radians = Math.toRadians(degrees.toDouble())
            val x = (58 * cos(radians)).toFloat()
            val y = (58 * sin(radians)).toFloat()
            canvas.withRotation(degrees + 90f, x, y) {
                val leaf = RectF(x - 12f, y - 5f, x + 12f, y + 5f)
                drawOval(leaf, leafFill)
                drawOval(leaf, leafEdge)
            }
        }
    }

    private const val GLYPH = "起"
}

private object MoodFaces {
    private fun eyes(canvas: Canvas, radius: Float = 5f) {
        listOf(-18f, 18f).forEach { x ->
            canvas.drawOval(RectF(x - 8f, -12f, x + 8f, 8f), fill(Color.WHITE))
            canvas.drawCircle(x + if (x < 0) 2f else -2f, 0f, radius, fill(brow))
        }
    }

    fun summoning(canvas: Canvas) {
        val line = stroke(brow, 4f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(-32f, -22f, -10f, -14f, line)
        canvas.drawLine(32f, -22f, 10f, -14f, line)
        eyes(canvas)
        canvas.drawOval(RectF(-12f, 16f, 12f, 36f), fill(outline))
    }

    fun cheering(canvas: Canvas) {
        val line = stroke(brow, 4f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawPath(
            Path().apply {
                moveTo(-26f, -2f)
                quadTo(-18f, -12f, -10f, -2f)
            },
            line
        )
        canvas.drawPath(
            Path().apply {
                moveTo(10f, -2f)
                quadTo(18f, -12f, 26f, -2f)
            },
            line
        )
        canvas.drawPath(
            Path().apply {
                moveTo(-20f, 16f)
                quadTo(0f, 40f, 20f, 16f)
                close()
            },
            fill(outline)
        )
    }

    fun counting(canvas: Canvas) {
        val line = stroke(brow, 4f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(-26f, -4f, -10f, -4f, line)
        canvas.drawOval(RectF(10f, -12f, 26f, 8f), fill(Color.WHITE))
        canvas.drawCircle(18f, 0f, 5f, fill(brow))
        canvas.drawPath(
            Path().apply {
                moveTo(-10f, 22f)
                quadTo(0f, 28f, 12f, 20f)
            },
            stroke(outline, 4f)
        )
        val glass = Path().apply {
            moveTo(30f, 18f)
            lineTo(46f, 18f)
            lineTo(30f, 42f)
            lineTo(46f, 42f)
            close()
        }
        canvas.drawPath(glass, fill(Cyan90.toArgb()))
        canvas.drawPath(glass, stroke(Cyan40.toArgb(), 2f))
    }

    fun freeing(canvas: Canvas) {
        listOf(-18f, 18f).forEach { star(canvas, it, -2f) }
        canvas.drawPath(
            Path().apply {
                moveTo(-26f, 14f)
                quadTo(0f, 46f, 26f, 14f)
                close()
            },
            fill(outline)
        )
    }

    fun wounded(canvas: Canvas) {
        val line = stroke(brow, 4f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawLine(-30f, -14f, -10f, -20f, line)
        canvas.drawLine(30f, -14f, 10f, -20f, line)
        eyes(canvas, radius = 4f)
        canvas.drawPath(
            Path().apply {
                moveTo(-14f, 30f)
                quadTo(0f, 18f, 14f, 30f)
            },
            stroke(outline, 4f)
        )
        canvas.drawPath(
            Path().apply {
                moveTo(26f, 8f)
                quadTo(30f, 16f, 26f, 20f)
                quadTo(22f, 16f, 26f, 8f)
            },
            fill(Cyan80.toArgb())
        )
    }

    fun sleeping(canvas: Canvas) {
        val line = stroke(brow, 4f).apply { strokeCap = Paint.Cap.ROUND }
        canvas.drawPath(
            Path().apply {
                moveTo(-26f, -4f)
                quadTo(-18f, 4f, -10f, -4f)
            },
            line
        )
        canvas.drawPath(
            Path().apply {
                moveTo(10f, -4f)
                quadTo(18f, 4f, 26f, -4f)
            },
            line
        )
        canvas.drawOval(RectF(-6f, 20f, 6f, 30f), fill(outline))
        canvas.drawText("z", 40f, -30f, glyph(Cyan40.toArgb(), 22f))
    }

    fun content(canvas: Canvas) {
        eyes(canvas)
        canvas.drawPath(
            Path().apply {
                moveTo(-12f, 22f)
                quadTo(0f, 30f, 12f, 22f)
            },
            stroke(outline, 4f)
        )
    }

    private fun star(canvas: Canvas, cx: Float, cy: Float) {
        val path = Path()
        repeat(10) { i ->
            val radius = if (i % 2 == 0) 10f else 4.5f
            val angle = Math.toRadians((i * 36 - 90).toDouble())
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill(laurel))
        canvas.drawPath(path, stroke(laurelEdge, 1.2f))
    }
}

private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.FILL
}

private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    style = Paint.Style.STROKE
    strokeWidth = width
}

private fun glyph(color: Int, textSize: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.textSize = textSize
    textAlign = Paint.Align.CENTER
    typeface = Typeface.DEFAULT_BOLD
}

@Composable
fun SurgiusFace(mood: Mood, description: String, modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = description }) {
        drawIntoCanvas { canvas ->
            SurgiusPainter.draw(
                canvas.nativeCanvas,
                center.x,
                center.y + size.minDimension * 0.04f,
                size.minDimension,
                mood
            )
        }
    }
}
