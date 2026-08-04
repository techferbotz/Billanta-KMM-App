package com.ferbotz.billanta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Line-drawn icons on a 24×24 grid, rendered with [Canvas]. Hand-built so the app needs no
 * material-icons-extended dependency and stays fully KMM-safe. Stroke style matches the mock:
 * rounded caps/joins, ~1.8px weight.
 */
enum class AppIcon {
    Search, Bell, Tune, Plus, Receipt, People, Grid, Person,
    ChevronRight, ChevronDown, ArrowLeft, Check, Share, Download,
    Pencil, Trash, Close, Lock, CloudOff, Star, Camera, Qr, Info, Plane, Copy, Dot, Google, Sun, Moon, Search2,
}

@Composable
fun BillantaIcon(
    icon: AppIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    strokeWidth: Float = 1.9f,
) {
    Canvas(modifier = modifier.size(size)) {
        val k = this.size.width / 24f
        val w = strokeWidth * k
        val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(builder: Path.() -> Unit) = drawPath(Path().apply(builder), tint, style = stroke)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * k, y1 * k), Offset(x2 * k, y2 * k), w, StrokeCap.Round)

        when (icon) {
            AppIcon.Search, AppIcon.Search2 -> {
                drawCircle(tint, 6f * k, Offset(10f * k, 10f * k), style = stroke)
                line(14.5f, 14.5f, 20f, 20f)
            }
            AppIcon.Bell -> {
                p {
                    moveTo(6f * k, 16f * k)
                    cubicTo(6f * k, 12f * k, 6.5f * k, 8f * k, 12f * k, 8f * k)
                    cubicTo(17.5f * k, 8f * k, 18f * k, 12f * k, 18f * k, 16f * k)
                    lineTo(6f * k, 16f * k)
                }
                line(4.5f, 16.5f, 19.5f, 16.5f)
                line(12f, 5.5f, 12f, 8f)
                p {
                    moveTo(10.3f * k, 19f * k)
                    cubicTo(10.8f * k, 20.2f * k, 13.2f * k, 20.2f * k, 13.7f * k, 19f * k)
                }
            }
            AppIcon.Tune -> {
                line(4f, 8f, 20f, 8f); drawCircle(tint, 2.4f * k, Offset(9f * k, 8f * k), style = stroke)
                line(4f, 16f, 20f, 16f); drawCircle(tint, 2.4f * k, Offset(15f * k, 16f * k), style = stroke)
            }
            AppIcon.Plus -> { line(12f, 5.5f, 12f, 18.5f); line(5.5f, 12f, 18.5f, 12f) }
            AppIcon.Receipt -> {
                p {
                    moveTo(6f * k, 4.5f * k); lineTo(18f * k, 4.5f * k); lineTo(18f * k, 20f * k)
                    lineTo(15.5f * k, 18.3f * k); lineTo(13f * k, 20f * k); lineTo(10.5f * k, 18.3f * k)
                    lineTo(8f * k, 20f * k); lineTo(6f * k, 18.3f * k); close()
                }
                line(9f, 9f, 15f, 9f); line(9f, 12.5f, 15f, 12.5f)
            }
            AppIcon.People -> {
                drawCircle(tint, 2.6f * k, Offset(9f * k, 8.5f * k), style = stroke)
                p { moveTo(4.5f * k, 18.5f * k); cubicTo(4.5f * k, 14f * k, 13.5f * k, 14f * k, 13.5f * k, 18.5f * k) }
                drawCircle(tint, 2.2f * k, Offset(16f * k, 9f * k), style = stroke)
                p { moveTo(15f * k, 14.2f * k); cubicTo(19.5f * k, 14.5f * k, 19.8f * k, 16.5f * k, 19.8f * k, 18.5f * k) }
            }
            AppIcon.Grid -> {
                fun sq(x: Float, y: Float) = drawRoundRectStroke(x, y, 7.5f, 7.5f, 1.6f, k, tint, stroke)
                sq(4f, 4f); sq(12.5f, 4f); sq(4f, 12.5f); sq(12.5f, 12.5f)
            }
            AppIcon.Person -> {
                drawCircle(tint, 3.2f * k, Offset(12f * k, 8f * k), style = stroke)
                p { moveTo(5.5f * k, 19.5f * k); cubicTo(5.5f * k, 13.5f * k, 18.5f * k, 13.5f * k, 18.5f * k, 19.5f * k) }
            }
            AppIcon.ChevronRight -> p { moveTo(10f * k, 6f * k); lineTo(16f * k, 12f * k); lineTo(10f * k, 18f * k) }
            AppIcon.ChevronDown -> p { moveTo(6f * k, 10f * k); lineTo(12f * k, 16f * k); lineTo(18f * k, 10f * k) }
            AppIcon.ArrowLeft -> { line(19f, 12f, 6f, 12f); p { moveTo(11f * k, 7f * k); lineTo(6f * k, 12f * k); lineTo(11f * k, 17f * k) } }
            AppIcon.Check -> p { moveTo(5f * k, 12.5f * k); lineTo(10f * k, 17.5f * k); lineTo(19f * k, 6.5f * k) }
            AppIcon.Share -> {
                line(12f, 4.5f, 12f, 15f)
                p { moveTo(8.5f * k, 8f * k); lineTo(12f * k, 4.5f * k); lineTo(15.5f * k, 8f * k) }
                p {
                    moveTo(7.5f * k, 11f * k); lineTo(5.5f * k, 11f * k); lineTo(5.5f * k, 20f * k)
                    lineTo(18.5f * k, 20f * k); lineTo(18.5f * k, 11f * k); lineTo(16.5f * k, 11f * k)
                }
            }
            AppIcon.Download -> {
                line(12f, 4.5f, 12f, 15f)
                p { moveTo(8.5f * k, 11.5f * k); lineTo(12f * k, 15f * k); lineTo(15.5f * k, 11.5f * k) }
                line(5.5f, 19.5f, 18.5f, 19.5f)
            }
            AppIcon.Pencil -> {
                p { moveTo(5f * k, 19f * k); lineTo(5f * k, 15.5f * k); lineTo(15.5f * k, 5f * k); lineTo(19f * k, 8.5f * k); lineTo(8.5f * k, 19f * k); close() }
                line(13f, 7.5f, 16.5f, 11f)
            }
            AppIcon.Trash -> {
                line(5f, 7f, 19f, 7f)
                p { moveTo(6.5f * k, 7f * k); lineTo(7.3f * k, 19.5f * k); lineTo(16.7f * k, 19.5f * k); lineTo(17.5f * k, 7f * k) }
                p { moveTo(9.5f * k, 7f * k); lineTo(9.8f * k, 4.5f * k); lineTo(14.2f * k, 4.5f * k); lineTo(14.5f * k, 7f * k) }
                line(10f, 10.5f, 10.3f, 16f); line(14f, 10.5f, 13.7f, 16f)
            }
            AppIcon.Close -> { line(6.5f, 6.5f, 17.5f, 17.5f); line(17.5f, 6.5f, 6.5f, 17.5f) }
            AppIcon.Lock -> {
                drawRoundRectStroke(5.5f, 10.5f, 13f, 9f, 2f, k, tint, stroke)
                p { moveTo(8f * k, 10.5f * k); lineTo(8f * k, 8f * k); cubicTo(8f * k, 4.5f * k, 16f * k, 4.5f * k, 16f * k, 8f * k); lineTo(16f * k, 10.5f * k) }
            }
            AppIcon.CloudOff -> {
                p {
                    moveTo(7f * k, 18f * k); cubicTo(3.5f * k, 18f * k, 3.5f * k, 12.5f * k, 7.5f * k, 12.5f * k)
                    cubicTo(7.5f * k, 7f * k, 15f * k, 7f * k, 16f * k, 11.5f * k)
                    cubicTo(20f * k, 11f * k, 20.5f * k, 17.2f * k, 17f * k, 18f * k)
                }
                line(5f, 5f, 19.5f, 20f)
            }
            AppIcon.Star -> {
                p {
                    moveTo(12f * k, 4f * k); lineTo(14.4f * k, 9f * k); lineTo(19.8f * k, 9.7f * k)
                    lineTo(15.9f * k, 13.5f * k); lineTo(16.9f * k, 19f * k); lineTo(12f * k, 16.3f * k)
                    lineTo(7.1f * k, 19f * k); lineTo(8.1f * k, 13.5f * k); lineTo(4.2f * k, 9.7f * k)
                    lineTo(9.6f * k, 9f * k); close()
                }
            }
            AppIcon.Camera -> {
                drawRoundRectStroke(4f, 7.5f, 16f, 12f, 2.4f, k, tint, stroke)
                drawCircle(tint, 3f * k, Offset(12f * k, 13.5f * k), style = stroke)
                p { moveTo(8.5f * k, 7.5f * k); lineTo(9.7f * k, 5.5f * k); lineTo(14.3f * k, 5.5f * k); lineTo(15.5f * k, 7.5f * k) }
            }
            AppIcon.Qr -> {
                drawRoundRectStroke(4f, 4f, 6.5f, 6.5f, 1.4f, k, tint, stroke)
                drawRoundRectStroke(13.5f, 4f, 6.5f, 6.5f, 1.4f, k, tint, stroke)
                drawRoundRectStroke(4f, 13.5f, 6.5f, 6.5f, 1.4f, k, tint, stroke)
                line(13.5f, 13.5f, 16.5f, 13.5f); line(13.5f, 13.5f, 13.5f, 16.5f)
                line(20f, 16.5f, 20f, 20f); line(16.5f, 20f, 20f, 20f); line(17f, 16.5f, 17f, 17f)
            }
            AppIcon.Info -> {
                drawCircle(tint, 8f * k, Offset(12f * k, 12f * k), style = stroke)
                line(12f, 11f, 12f, 16.5f); drawCircle(tint, 0.4f * k, Offset(12f * k, 8f * k), style = Stroke(1.4f * w))
            }
            AppIcon.Plane -> p { moveTo(4f * k, 12f * k); lineTo(20f * k, 5f * k); lineTo(13f * k, 20f * k); lineTo(11f * k, 13f * k); close() }
            AppIcon.Copy -> {
                drawRoundRectStroke(8f, 8f, 11f, 11f, 2f, k, tint, stroke)
                p { moveTo(5f * k, 15.5f * k); lineTo(5f * k, 5f * k); lineTo(15.5f * k, 5f * k) }
            }
            AppIcon.Dot -> drawCircle(tint, 3f * k, Offset(12f * k, 12f * k))
            AppIcon.Google -> {
                // Simplified multi-arc "G" placeholder.
                drawCircle(tint, 7f * k, Offset(12f * k, 12f * k), style = stroke)
                line(12f, 12f, 19.5f, 12f)
            }
            AppIcon.Sun -> {
                drawCircle(tint, 3.6f * k, Offset(12f * k, 12f * k), style = stroke)
                for (a in 0 until 8) {
                    val ang = a * 45f * (3.1415927f / 180f)
                    val cx = 12f * k; val cy = 12f * k
                    val r1 = 6.4f * k; val r2 = 8.4f * k
                    drawLine(tint, Offset(cx + r1 * cos(ang), cy + r1 * sin(ang)), Offset(cx + r2 * cos(ang), cy + r2 * sin(ang)), w, StrokeCap.Round)
                }
            }
            AppIcon.Moon -> p {
                moveTo(17f * k, 15f * k)
                cubicTo(11f * k, 17f * k, 7f * k, 13f * k, 9f * k, 7f * k)
                cubicTo(5f * k, 8.5f * k, 4.5f * k, 15f * k, 9.5f * k, 18f * k)
                cubicTo(13f * k, 20f * k, 16.5f * k, 18.5f * k, 17f * k, 15f * k)
            }
        }
    }
}

private fun DrawScope.drawRoundRectStroke(
    x: Float, y: Float, w: Float, h: Float, r: Float, k: Float, color: Color, stroke: Stroke,
) {
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                Rect(Offset(x * k, y * k), Size(w * k, h * k)),
                androidx.compose.ui.geometry.CornerRadius(r * k, r * k),
            )
        )
    }
    drawPath(path, color, style = stroke)
}

private fun cos(a: Float) = kotlin.math.cos(a)
private fun sin(a: Float) = kotlin.math.sin(a)
