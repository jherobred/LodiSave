package io.github.jherobred.lodisave

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.max

enum class Pose { Idle, Grab, Full, Yay, Oops }

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

// The same drawing as extension/popup.html, in its 160 x 160 space.
private val BODY = path("M80 36C112 36 136 60 138 94C140 122 116 140 80 140C44 140 20 122 22 94C24 60 48 36 80 36Z")
private val BELLY = path("M50 112C50 94 63 86 80 86C97 86 110 94 110 112C110 128 97 138 80 138C63 138 50 128 50 112Z")
private val TUFT = path("M74 38Q79 27 87 34")
private val NOSE = path("M75.5 92.5Q80 90 84.5 92.5Q82.5 96.5 80 97Q77.5 96.5 75.5 92.5Z")
private val HAPPY_EYES = path("M54 82Q62 72.5 70 82M90 82Q98 72.5 106 82")
private val BROWS = path("M53 69L68 65M107 69L92 65")
private val SMILE = path("M72.5 100Q76.25 104 80 100.5Q83.75 104 87.5 100")
private val SMALL_SMILE = path("M74.5 100Q77.25 103 80 100.5Q82.75 103 85.5 100")
private val OPEN_MOUTH = path("M71 100.5Q80 99 89 100.5Q87 112 80 112Q73 112 71 100.5Z")
private val FROWN = path("M73 106Q80 99.5 87 106")
private val SWEAT = path("M136 44C136 44 129 53 129 57C129 61 132.5 63.5 136 63.5C139.5 63.5 143 61 143 57C143 53 136 44 136 44Z")
private val LEAF = path("M-8 8C-8 -2 -2 -8 8 -8C8 2 2 8 -8 8Z")

private val INK = Color(0xFF141414)
private val PINK = Color(0xFFFF9A7E)
private val LINE = Stroke(width = 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** The radial fur shading of the design, placed on a shape's bounding box. */
private fun shade(r: Rect, vararg stops: Pair<Float, Color>) =
    Brush.radialGradient(*stops, center = Offset(r.left + 0.38f * r.width, r.top + 0.3f * r.height), radius = 0.78f * max(r.width, r.height))

private fun fur(r: Rect) = shade(r, 0f to Color(0xFFF8DDB8), 0.55f to Color(0xFFE8B37A), 1f to Color(0xFFC98E52))
private fun cheek(r: Rect) = shade(r, 0f to Color(0xFFFFEFD8), 1f to Color(0xFFF3CFA3))
private fun paw(r: Rect) = shade(r, 0f to Color(0xFFFFEDE3), 1f to Color(0xFFF5C7AE))

private fun box(cx: Float, cy: Float, rx: Float, ry: Float = rx) = Rect(cx - rx, cy - ry, cx + rx, cy + ry)
private fun DrawScope.oval(r: Rect, brush: Brush) = drawOval(brush, r.topLeft, r.size)
private fun DrawScope.oval(r: Rect, color: Color, alpha: Float = 1f) = drawOval(color, r.topLeft, r.size, alpha)

@Composable
fun Lodi(pose: Pose, modifier: Modifier = Modifier, motion: Boolean = true) {
    val cheeks by animateFloatAsState(if (pose == Pose.Full) 1.48f else 1f, floaty(), label = "cheeks")
    val ears by animateFloatAsState(if (pose == Pose.Oops) 24f else 0f, floaty(), label = "ears")
    // A squash and hop each time the pose changes.
    val hop = remember { Animatable(1f) }
    LaunchedEffect(pose) {
        if (motion) {
            hop.snapTo(0.9f)
            hop.animateTo(1f, floaty())
        }
    }
    val loop = rememberInfiniteTransition(label = "lodi")
    val bob by loop.animateFloat(0f, if (motion) -4f else 0f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val blink by loop.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4400
        1f at 4050
        (if (motion) 0.1f else 1f) at 4180
        1f at 4300
    }), label = "blink")
    val drip by loop.animateFloat(0f, if (motion) 1f else 0f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "drip")
    val leaf by loop.animateFloat(0f, if (motion) 1f else 0.4f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "leaf")

    Canvas(modifier) {
        val s = size.minDimension / 160f
        val h = hop.value
        scale(s, s, pivot = Offset.Zero) {
            if (pose == Pose.Yay) leaves(leaf)
            translate(top = bob) {
                scale(2f - h, h, pivot = Offset(80f, 140f)) { body(pose, cheeks, ears, blink, drip) }
            }
        }
    }
}

private fun DrawScope.leaves(t: Float) {
    for ((i, at) in listOf(Offset(20f, 34f), Offset(140f, 28f), Offset(148f, 96f)).withIndex()) {
        val p = (t + i * 0.3f) % 1f
        val alpha = when {
            p < 0.2f -> 0f
            p < 0.4f -> (p - 0.2f) / 0.2f
            else -> 1f - (p - 0.4f) / 0.6f
        }
        translate(at.x, at.y + (p - 0.4f) * 30f) {
            rotate(-20f + p * 70f, pivot = Offset.Zero) {
                drawPath(LEAF, if (i == 1) Color(0xFFE8B37A) else Color(0xFFFF8A3D), alpha = alpha.coerceIn(0f, 1f))
            }
        }
    }
}

private fun DrawScope.body(pose: Pose, cheeks: Float, ears: Float, blink: Float, drip: Float) {
    // Ears, tilted back when something went wrong.
    rotate(-ears, pivot = Offset(50f, 54f)) {
        oval(box(46f, 44f, 14f), fur(box(46f, 44f, 14f)))
        oval(box(47f, 46f, 7f), PINK)
    }
    rotate(ears, pivot = Offset(110f, 54f)) {
        oval(box(114f, 44f, 14f), fur(box(114f, 44f, 14f)))
        oval(box(113f, 46f, 7f), PINK)
    }
    for (x in listOf(56f, 104f)) oval(box(x, 139f, 12f, 6f), paw(box(x, 139f, 12f, 6f)))
    if (pose == Pose.Yay) for (x in listOf(20f, 140f)) oval(box(x, 66f, 9.5f, 8f), paw(box(x, 66f, 9.5f, 8f)))
    drawPath(BODY, fur(BODY.getBounds()))
    rotate(-28f, pivot = Offset(58f, 56f)) { oval(box(58f, 56f, 17f, 8f), Color.White, 0.38f) }
    drawPath(TUFT, Color(0xFFC98E52), style = LINE)
    drawPath(BELLY, Brush.verticalGradient(listOf(Color(0xFFFFFAF2), Color(0xFFFBEBD6)), startY = 86f, endY = 138f))
    // Cheeks grow when Lodi stuffs them with files.
    for ((cx, pivot) in listOf(44f to 54.5f, 116f to 105.5f)) {
        scale(cheeks, cheeks, pivot = Offset(pivot, 98f)) {
            oval(box(cx, 98f, 15f, 13f), cheek(box(cx, 98f, 15f, 13f)))
            oval(box(cx, 95f, 7f, 4.5f), PINK)
        }
    }
    when (pose) {
        Pose.Idle -> scale(1f, blink, pivot = Offset(80f, 80f)) {
            for (x in listOf(62f, 98f)) oval(box(x, 80f, 8.5f), INK)
            for (x in listOf(65f, 101f)) oval(box(x, 76.5f, 3.2f), Color.White)
            for (x in listOf(59.5f, 95.5f)) oval(box(x, 83.5f, 1.3f), Color.White)
        }
        Pose.Grab -> {
            for (x in listOf(62f, 98f)) oval(box(x, 82.5f, 8.5f), INK)
            for (x in listOf(63.5f, 99.5f)) oval(box(x, 81.5f, 3.2f), Color.White)
        }
        Pose.Full, Pose.Yay -> drawPath(HAPPY_EYES, INK, style = LINE)
        Pose.Oops -> {
            for (x in listOf(62f, 98f)) oval(box(x, 81f, 8f), INK)
            for (x in listOf(64.8f, 100.8f)) oval(box(x, 78f, 3f), Color.White)
            drawPath(BROWS, INK, style = LINE)
        }
    }
    drawPath(NOSE, Color(0xFFEE7A6E))
    when (pose) {
        Pose.Idle -> drawPath(SMILE, INK, style = LINE)
        Pose.Grab -> {
            oval(box(80f, 103f, 5f, 4.5f), INK)
            oval(box(80f, 105.5f, 3f, 1.8f), PINK)
        }
        Pose.Full -> drawPath(SMALL_SMILE, INK, style = LINE)
        Pose.Yay -> {
            drawPath(OPEN_MOUTH, INK)
            oval(box(80f, 108f, 4.2f, 2.4f), PINK)
        }
        Pose.Oops -> drawPath(FROWN, INK, style = LINE)
    }
    for (x in listOf(67f, 93f)) oval(box(x, 122f, 9.5f, 7.5f), paw(box(x, 122f, 9.5f, 7.5f)))
    if (pose == Pose.Oops) {
        val alpha = if (drip < 0.2f) drip / 0.2f else 1f - (drip - 0.2f) / 0.8f
        translate(top = -4f + drip * 16f) { drawPath(SWEAT, Color(0xFF8FB6D6), alpha = alpha.coerceIn(0f, 1f)) }
    }
}

