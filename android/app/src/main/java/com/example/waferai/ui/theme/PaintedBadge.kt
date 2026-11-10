package com.example.waferai.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

// Coordinates follow the launcher badge so the crystalline notch, grid and orbit
// land in the same place even while the oil finishes tracing them.
internal class PaintedBadge {
    private val outline = PaintTrace(
        "M13.5,34 C13.5,23.8 21.8,15.5 32,15.5 C42.2,15.5 50.5,23.8 50.5,34 " +
            "C50.5,42.8 44.4,50.1 36.4,52 L34.3,49.4 L29.7,49.4 L27.6,52 C19.6,50.1 13.5,42.8 13.5,34 Z"
    )
    private val orbitBack = PaintTrace("M8.78,50.6 C6.87,46.49 16.07,38.15 29.33,31.97 C42.6,25.78 54.9,24.1 56.82,28.2")
    private val orbitFront = PaintTrace("M56.82,28.2 C58.73,32.31 49.53,40.65 36.27,46.83 C23,53.02 10.7,54.7 8.78,50.6")
    private val orbitGlint = PaintTrace("M52,36.3 C45,43.4 28.4,51 16,52.6")
    private val grid = listOf(
        PaintTrace("M25.5,20.2 L25.5,45.8"),
        PaintTrace("M38.5,20.2 L38.5,42.8"),
        PaintTrace("M18.1,28 L45.9,28"),
        PaintTrace("M17.5,37 L45.9,37"),
    )
    private val largeStar = parsePaintPath("M52.7,8.5 Q54,13.1 58.7,14.4 Q54,15.7 52.7,20.3 Q51.4,15.7 46.7,14.4 Q51.4,13.1 52.7,8.5 Z")
    private val smallStar = parsePaintPath("M12,15.8 Q12.8,18.2 15.2,19 Q12.8,19.8 12,22.2 Q11.2,19.8 8.8,19 Q11.2,18.2 12,15.8 Z")
    private val paintMask = Path()
    private val face = Brush.linearGradient(
        colors = listOf(Color(0xFF416EAC), Color(0xFF192E63), Color(0xFF102243)),
        start = Offset(15f, 16f), end = Offset(50f, 52f),
    )

    fun draw(scope: DrawScope, texture: ImageBitmap?, time: Float) = with(scope) {
        val extent = size.minDimension * 0.59f
        val unit = extent / 66f
        withTransform({
            translate(center.x - extent / 2f, center.y - extent / 2f)
            scale(unit, unit, Offset.Zero)
        }) {
            orbitBack.draw(this, phase(time, 760, 1180), Color(0xFFC4934E), 2.55f)
            paintFace(texture, time)
            val trace = phase(time, 0, 700)
            outline.draw(this, trace, Color(0xFF0B183A), 5.8f)
            outline.draw(this, trace, Color(0xFF326BB8), 4.4f)
            outline.draw(this, trace, Color(0xFF80AEE3), 1.8f)
            for ((index, stroke) in grid.withIndex()) {
                stroke.draw(this, phase(time, 840 + index * 112, 1130 + index * 112), Color(0xFFE7BB69), 1.56f)
            }
            val ring = phase(time, 1180, 1690)
            orbitFront.draw(this, ring, Color(0xFF644018), 5.1f)
            orbitFront.draw(this, ring, Color(0xFFF3B854), 3.5f)
            orbitGlint.draw(this, phase(time, 1420, 1730), Color(0xFFFFEBB7), 1.16f)
            drawPath(largeStar, Color(0xFFFFE296), alpha = easeOutCubic(phase(time, 1500, 1740)))
            drawPath(smallStar, Color(0xFFFFE296), alpha = easeOutCubic(phase(time, 1600, 1790)))
        }
    }

    private fun DrawScope.paintFace(texture: ImageBitmap?, time: Float) {
        if (time <= 330f) return
        paintMask.reset()
        // Adjacent palette-knife passes fill the face in alternating directions.
        for (pass in 0..6) {
            val laid = easeOutCubic(phase(time, 330 + pass * 74, 740 + pass * 74))
            if (laid <= 0f) continue
            val width = laid * 40f
            val left = if (pass % 2 == 0) 12f else 52f - width
            val top = 15f + pass * 5.5f
            paintMask.addRect(Rect(left, top, left + width, top + 5.65f))
        }
        clipPath(outline.path) {
            clipPath(paintMask) {
                drawRect(face, topLeft = Offset(13f, 15f), size = androidx.compose.ui.geometry.Size(38f, 38f))
                if (texture != null) drawImage(
                    texture,
                    srcOffset = IntOffset(texture.width * 2 / 7, texture.height * 2 / 5),
                    srcSize = IntSize(texture.width * 3 / 7, texture.height * 2 / 7),
                    dstOffset = IntOffset(13, 15),
                    dstSize = IntSize(38, 38),
                    alpha = 0.69f,
                )
            }
        }
    }
}

private fun parsePaintPath(data: String): Path = PathParser().parsePathString(data).toPath()

private class PaintTrace(data: String) {
    val path = parsePaintPath(data)
    private val segment = Path()
    private val measure = PathMeasure().apply { setPath(path, false) }

    fun draw(scope: DrawScope, progress: Float, color: Color, width: Float) = with(scope) {
        if (progress <= 0f) return@with
        val trace = if (progress >= 1f) path else {
            segment.reset()
            measure.getSegment(0f, measure.length * progress, segment, true)
            segment
        }
        translate(0.14f, 0.36f) {
            drawPath(trace, Color(0xFF090F28), alpha = 0.53f, style = Stroke(width + 0.55f, cap = StrokeCap.Round))
        }
        drawPath(trace, color, style = Stroke(width, cap = StrokeCap.Round))
    }
}
