package com.alessandrognola.superpong

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min

private data class Block(val rect: RectF, val color: Int, val points: Int)

private data class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val totalLife: Float, val color: Int, val radius: Float
)

private enum class GameState { MENU, TOP_SCORE, BACKGROUND_SELECT, PLAYING, LEVEL_CLEARED, GAME_OVER }
private enum class PendingAction { NONE, NEXT_LEVEL, RESTART_GAME }
private enum class BgTheme { GRID, SPACE, SKY }

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false

    private var screenW = 0
    private var screenH = 0

    private val prefs = context.getSharedPreferences("superpong_prefs", Context.MODE_PRIVATE)
    private var topScore = prefs.getInt("top_score", 0)
    @Volatile private var bgTheme = BgTheme.values()[prefs.getInt("bg_theme", BgTheme.SPACE.ordinal)]

    // --- world objects ---
    private var paddleX = 0f
    private var paddleY = 0f
    private var paddleW = 0f
    private val paddleH = dp(18f)

    private var ballX = 0f
    private var ballY = 0f
    private var ballR = dp(9f)
    private var ballDx = 0f
    private var ballDy = 0f
    private val baseBallSpeed = dp(260f)
    private val ballColor = Color.parseColor("#FFEB3B")

    private val blocks = mutableListOf<Block>()
    private val blockCols = 6
    private var blockW = 0f
    private val blockH = dp(34f)
    private val rowGap = dp(4f)

    // --- levels: 3 macro groups (constant speed) x 6 sub-levels (number of rows) ---
    // group 1 (levels 1-6): blocks stay still, only the row count grows
    // group 2 (levels 7-12) and 3 (levels 13-18): blocks fall at an increasing constant speed
    private val rowsPerSubLevel = intArrayOf(5, 6, 7, 8, 9, 10)
    private val macroFallSpeeds = floatArrayOf(0f, dp(20f), dp(42f))
    private val totalLevels = macroFallSpeeds.size * rowsPerSubLevel.size
    private var levelIndex = 0 // 0-based

    // --- bottom layout: control-zone hint sits above this bottom margin ---
    private val bottomBarH = dp(56f)
    private val controlZoneH = dp(64f)

    private var score = 0
    private var lives = 3
    @Volatile private var state = GameState.MENU
    @Volatile private var pendingAction = PendingAction.NONE

    private val particles = mutableListOf<Particle>()

    private val palette = intArrayOf(
        Color.parseColor("#EC407A"), // pink - 50
        Color.parseColor("#AB47BC"), // purple - 45
        Color.parseColor("#5C6BC0"), // indigo - 40
        Color.parseColor("#29B6F6"), // blue - 35
        Color.parseColor("#26C6DA"), // cyan - 30
        Color.parseColor("#66BB6A"), // green - 20
        Color.parseColor("#FFEE58"), // yellow - 15
        Color.parseColor("#FFA726"), // orange - 10
        Color.parseColor("#EF5350")  // red - 5
    )
    private val palettePoints = intArrayOf(50, 45, 40, 35, 30, 20, 15, 10, 5)
    private var nextRowColorIndex = 0

    private var lastFrameNanos = 0L

    private val gameFont: Typeface? = try {
        ResourcesCompat.getFont(context, R.font.bubblegum_sans)
    } catch (e: Exception) { null }

    // --- candy-game palette ---
    private val colorSkyBlue = Color.parseColor("#2FA8F0")
    private val colorLeafGreen = Color.parseColor("#7CC13B")
    private val colorGold = Color.parseColor("#FFB300")
    private val colorOutline = Color.parseColor("#123C6B")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(20f)
        isFakeBoldText = true
        typeface = gameFont
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(16f)
        alpha = 200
        typeface = gameFont
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(32f)
        isFakeBoldText = true
        typeface = gameFont
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(40f)
        isFakeBoldText = true
        typeface = gameFont
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(22f)
        isFakeBoldText = true
        typeface = gameFont
    }

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun lighten(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
    )

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * (1 - factor)).toInt().coerceIn(0, 255),
        (Color.green(color) * (1 - factor)).toInt().coerceIn(0, 255),
        (Color.blue(color) * (1 - factor)).toInt().coerceIn(0, 255)
    )

    private fun drawOutlinedText(canvas: Canvas, text: String, x: Float, y: Float, fillPaint: Paint) {
        val outline = Paint(fillPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            color = colorOutline
        }
        canvas.drawText(text, x, y, outline)
        canvas.drawText(text, x, y, fillPaint)
    }

    private fun drawBeveledButton(canvas: Canvas, rect: RectF, baseColor: Int) {
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            lighten(baseColor, 0.25f), darken(baseColor, 0.15f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, dp(14f), dp(14f), paint)
        paint.shader = null

        paint.color = darken(baseColor, 0.45f)
        paint.alpha = 200
        canvas.drawRoundRect(RectF(rect.left, rect.bottom - dp(7f), rect.right, rect.bottom), dp(10f), dp(10f), paint)
        paint.alpha = 255
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenW = width
        screenH = height
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        screenW = w
        screenH = h
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

    private fun isLastLevel() = levelIndex >= totalLevels - 1

    private fun currentFallSpeed() = macroFallSpeeds[levelIndex / rowsPerSubLevel.size]

    private fun currentRows() = rowsPerSubLevel[levelIndex % rowsPerSubLevel.size]

    private fun setupGame() {
        score = 0
        levelIndex = 0
        setupLevel()
    }

    private fun setupLevel() {
        paddleW = screenW / 5f
        paddleX = screenW / 2f
        val controlZoneBottom = screenH - bottomBarH - dp(8f)
        val controlZoneTop = controlZoneBottom - controlZoneH
        paddleY = controlZoneTop - dp(16f) - paddleH

        blockW = screenW / blockCols.toFloat()

        resetBall()
        blocks.clear()
        particles.clear()
        lives = 3
        state = GameState.PLAYING
        nextRowColorIndex = 0

        val topStart = dp(72f)
        for (row in 0 until currentRows()) {
            spawnRow(topStart + row * (blockH + rowGap))
        }
    }

    private fun resetBall() {
        ballX = screenW / 2f
        ballY = paddleY - dp(40f)
        ballDx = baseBallSpeed * (if (Math.random() < 0.5) -1 else 1)
        ballDy = -baseBallSpeed
    }

    private fun spawnRow(y: Float) {
        val color = palette[nextRowColorIndex % palette.size]
        val points = palettePoints[nextRowColorIndex % palettePoints.size]
        nextRowColorIndex++
        for (col in 0 until blockCols) {
            val left = col * blockW + dp(2f)
            val right = (col + 1) * blockW - dp(2f)
            blocks.add(Block(RectF(left, y, right, y + blockH), color, points))
        }
    }

    private fun spawnBurst(x: Float, y: Float, color: Int, count: Int) {
        repeat(count) {
            val angle = (Math.random() * Math.PI * 2).toFloat()
            val speed = dp(70f) + (Math.random() * dp(170f)).toFloat()
            val life = 0.5f + (Math.random() * 0.4f).toFloat()
            particles.add(
                Particle(
                    x, y,
                    kotlin.math.cos(angle) * speed, kotlin.math.sin(angle) * speed,
                    life, life, color, dp(2f) + (Math.random() * dp(3f)).toFloat()
                )
            )
        }
    }

    private fun spawnFireworks() {
        repeat(5) {
            val fx = (Math.random() * screenW).toFloat()
            val fy = (Math.random() * screenH * 0.5f + screenH * 0.08f).toFloat()
            spawnBurst(fx, fy, palette[(Math.random() * palette.size).toInt()], 26)
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += dp(200f) * dt
        }
    }

    private fun selectBackground(b: BgTheme) {
        bgTheme = b
        prefs.edit().putInt("bg_theme", b.ordinal).apply()
    }

    private fun updateTopScore() {
        if (score > topScore) {
            topScore = score
            prefs.edit().putInt("top_score", topScore).apply()
        }
    }

    private fun onGameOver() {
        state = GameState.GAME_OVER
        (context as? Activity)?.let { LeaderboardManager.submitScore(it, score.toLong()) }
    }

    fun pause() {
        running = false
        val t = thread
        thread = null
        t?.let {
            try { it.join() } catch (e: InterruptedException) { /* ignore */ }
        }
    }

    fun resume() {
        if (thread != null) return
        running = true
        lastFrameNanos = 0L
        thread = Thread(this).also { it.start() }
    }

    override fun run() {
        while (running) {
            val now = System.nanoTime()
            if (lastFrameNanos == 0L) lastFrameNanos = now
            var dt = (now - lastFrameNanos) / 1_000_000_000f
            lastFrameNanos = now
            dt = min(dt, 0.05f) // clamp huge jumps (e.g. after backgrounding)

            when (pendingAction) {
                PendingAction.NEXT_LEVEL -> { levelIndex++; setupLevel() }
                PendingAction.RESTART_GAME -> setupGame()
                PendingAction.NONE -> {}
            }
            pendingAction = PendingAction.NONE

            updateParticles(dt)
            if (state == GameState.PLAYING) update(dt)
            draw()

            val frameMillis = 1000L / 60L
            val elapsedMs = (System.nanoTime() - now) / 1_000_000L
            val sleepMs = frameMillis - elapsedMs
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs) } catch (e: InterruptedException) { /* ignore */ }
            }
        }
    }

    private fun update(dt: Float) {
        // move ball
        ballX += ballDx * dt
        ballY += ballDy * dt

        if (ballX - ballR < 0) { ballX = ballR; ballDx = -ballDx }
        if (ballX + ballR > screenW) { ballX = screenW - ballR; ballDx = -ballDx }
        if (ballY - ballR < 0) { ballY = ballR; ballDy = -ballDy }

        // paddle collision
        val paddleTop = paddleY
        val paddleLeft = paddleX - paddleW / 2f
        val paddleRight = paddleX + paddleW / 2f
        if (ballDy > 0 &&
            ballY + ballR >= paddleTop && ballY + ballR <= paddleTop + paddleH &&
            ballX >= paddleLeft && ballX <= paddleRight
        ) {
            ballY = paddleTop - ballR
            val hitPos = (ballX - paddleX) / (paddleW / 2f) // -1..1
            val speed = kotlin.math.hypot(ballDx.toDouble(), ballDy.toDouble()).toFloat()
            val newDx = hitPos * speed * 0.9f
            val newDy = -kotlin.math.sqrt(max(speed * speed - newDx * newDx, speed * 0.2f * speed * 0.2f))
            ballDx = newDx
            ballDy = newDy
        }

        // block collisions
        val it = blocks.iterator()
        var bounced = false
        while (it.hasNext()) {
            val b = it.next()
            if (!bounced &&
                ballX + ballR > b.rect.left && ballX - ballR < b.rect.right &&
                ballY + ballR > b.rect.top && ballY - ballR < b.rect.bottom
            ) {
                it.remove()
                score += b.points
                updateTopScore()
                val overlapX = min(ballX + ballR - b.rect.left, b.rect.right - (ballX - ballR))
                val overlapY = min(ballY + ballR - b.rect.top, b.rect.bottom - (ballY - ballR))
                if (overlapX < overlapY) ballDx = -ballDx else ballDy = -ballDy
                bounced = true
            }
        }

        // blocks fall at the level's constant speed
        val fallSpeed = currentFallSpeed()
        for (b in blocks) {
            b.rect.top += fallSpeed * dt
            b.rect.bottom += fallSpeed * dt
        }

        // a block reaching the paddle overwhelms it -> level failed
        for (b in blocks) {
            if (b.rect.bottom >= paddleY) {
                onGameOver()
                return
            }
        }

        // ball missed -> explode the ball and lose a life
        if (ballY - ballR > screenH) {
            spawnBurst(ballX, screenH - dp(30f), ballColor, 22)
            lives--
            if (lives <= 0) {
                onGameOver()
            } else {
                resetBall()
            }
            return
        }

        // all blocks cleared -> level complete
        if (blocks.isEmpty()) {
            spawnFireworks()
            state = GameState.LEVEL_CLEARED
        }
    }

    private fun draw() {
        if (!holder.surface.isValid) return
        val canvas: Canvas = holder.lockCanvas() ?: return
        try {
            when (state) {
                GameState.MENU -> drawMenu(canvas)
                GameState.TOP_SCORE -> drawTopScoreScreen(canvas)
                GameState.BACKGROUND_SELECT -> drawBackgroundSelectScreen(canvas)
                else -> drawGameplay(canvas)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawGameplay(canvas: Canvas) {
        drawBackground(canvas)

        // blocks (3D relief: gradient + top highlight + bottom shadow)
        for (b in blocks) {
            paint.shader = LinearGradient(
                b.rect.left, b.rect.top, b.rect.left, b.rect.bottom,
                lighten(b.color, 0.35f), darken(b.color, 0.35f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(b.rect, dp(4f), dp(4f), paint)
            paint.shader = null

            paint.color = Color.WHITE
            paint.alpha = 80
            canvas.drawRoundRect(
                RectF(b.rect.left + dp(2f), b.rect.top + dp(2f), b.rect.right - dp(2f), b.rect.top + (b.rect.height() * 0.4f)),
                dp(3f), dp(3f), paint
            )

            paint.color = Color.BLACK
            paint.alpha = 70
            canvas.drawRect(b.rect.left, b.rect.bottom - dp(3f), b.rect.right, b.rect.bottom, paint)
            paint.alpha = 255
        }

        // paddle
        val paddleRect = RectF(paddleX - paddleW / 2f, paddleY, paddleX + paddleW / 2f, paddleY + paddleH)
        drawBeveledButton(canvas, paddleRect, colorSkyBlue)

        // ball (naturally off-screen once missed, so no extra visibility check needed)
        paint.color = ballColor
        canvas.drawCircle(ballX, ballY, ballR, paint)

        drawControlZoneHint(canvas)
        drawTopBar(canvas)

        when (state) {
            GameState.LEVEL_CLEARED -> {
                canvas.drawColor(Color.argb(160, 0, 0, 0))
                drawParticles(canvas)
                if (isLastLevel()) {
                    drawOutlinedText(canvas, "CONGRATULATIONS!", screenW / 2f, screenH / 2f - dp(20f), bigPaint)
                    val sub = Paint(bigPaint).apply { textSize = dp(20f) }
                    canvas.drawText("You completed all levels", screenW / 2f, screenH / 2f + dp(20f), sub)
                    canvas.drawText("Score: $score", screenW / 2f, screenH / 2f + dp(50f), sub)
                    canvas.drawText("Tap to restart", screenW / 2f, screenH / 2f + dp(86f), sub)
                } else {
                    drawOutlinedText(canvas, "LEVEL COMPLETE", screenW / 2f, screenH / 2f - dp(20f), bigPaint)
                    val sub = Paint(bigPaint).apply { textSize = dp(20f) }
                    canvas.drawText("Score: $score", screenW / 2f, screenH / 2f + dp(20f), sub)
                    canvas.drawText("Tap to continue", screenW / 2f, screenH / 2f + dp(56f), sub)
                }
            }
            GameState.GAME_OVER -> {
                canvas.drawColor(Color.argb(160, 0, 0, 0))
                drawParticles(canvas)
                drawOutlinedText(canvas, "GAME OVER", screenW / 2f, screenH / 2f - dp(20f), bigPaint)
                val sub = Paint(bigPaint).apply { textSize = dp(20f) }
                canvas.drawText("Score: $score", screenW / 2f, screenH / 2f + dp(20f), sub)
                canvas.drawText("Best: $topScore", screenW / 2f, screenH / 2f + dp(50f), sub)
                canvas.drawText("Tap for menu", screenW / 2f, screenH / 2f + dp(86f), sub)
            }
            else -> drawParticles(canvas)
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            val t = (p.life / p.totalLife).coerceIn(0f, 1f)
            paint.color = p.color
            paint.alpha = (t * 255).toInt()
            canvas.drawCircle(p.x, p.y, p.radius * t, paint)
        }
        paint.alpha = 255
    }

    // --- backgrounds ---

    private var decorW = -1
    private var decorH = -1
    private val stars = mutableListOf<FloatArray>() // x, y, radius, alpha
    private val clouds = mutableListOf<FloatArray>() // cx, cy, rw, rh

    private fun ensureDecorGenerated() {
        if (decorW == screenW && decorH == screenH) return
        decorW = screenW
        decorH = screenH
        val rnd = java.util.Random(42)
        stars.clear()
        repeat(60) {
            stars.add(
                floatArrayOf(
                    rnd.nextFloat() * screenW,
                    rnd.nextFloat() * screenH,
                    dp(1f + rnd.nextFloat() * 1.4f),
                    35f + rnd.nextFloat() * 55f
                )
            )
        }
        clouds.clear()
        repeat(6) {
            clouds.add(
                floatArrayOf(
                    rnd.nextFloat() * screenW,
                    rnd.nextFloat() * screenH * 0.55f + screenH * 0.05f,
                    dp(46f + rnd.nextFloat() * 36f),
                    dp(20f + rnd.nextFloat() * 12f)
                )
            )
        }
    }

    private fun drawBackground(canvas: Canvas) {
        ensureDecorGenerated()
        when (bgTheme) {
            BgTheme.GRID -> drawGridBackground(canvas)
            BgTheme.SPACE -> drawSpaceBackground(canvas)
            BgTheme.SKY -> drawSkyBackground(canvas)
        }
    }

    private fun drawGridBackground(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B1A33"))
        paint.color = Color.WHITE
        paint.alpha = 18
        paint.strokeWidth = dp(1f)
        val step = dp(40f)
        var x = 0f
        while (x < screenW) { canvas.drawLine(x, 0f, x, screenH.toFloat(), paint); x += step }
        var y = 0f
        while (y < screenH) { canvas.drawLine(0f, y, screenW.toFloat(), y, paint); y += step }
        paint.alpha = 255
    }

    private fun drawSpaceBackground(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B1A33"))
        paint.color = Color.WHITE
        for (s in stars) {
            paint.alpha = s[3].toInt()
            canvas.drawCircle(s[0], s[1], s[2], paint)
        }
        paint.alpha = 255
    }

    private fun drawSkyBackground(canvas: Canvas) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, screenH.toFloat(),
            Color.parseColor("#64B5F6"), Color.parseColor("#E1F5FE"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)
        paint.shader = null

        paint.color = Color.WHITE
        paint.alpha = 210
        for (c in clouds) {
            canvas.drawOval(c[0] - c[2], c[1] - c[3], c[0] + c[2], c[1] + c[3], paint)
            canvas.drawOval(c[0] - c[2] * 0.6f, c[1] - c[3] * 1.3f, c[0] + c[2] * 0.5f, c[1] + c[3] * 0.5f, paint)
            canvas.drawOval(c[0] - c[2] * 0.1f, c[1] - c[3] * 1.1f, c[0] + c[2], c[1] + c[3] * 0.6f, paint)
        }
        paint.alpha = 255
    }

    // --- HUD ---

    private fun drawTopBar(canvas: Canvas) {
        paint.color = Color.BLACK
        paint.alpha = 90
        canvas.drawRect(0f, 0f, screenW.toFloat(), dp(60f), paint)
        paint.alpha = 255

        hudPaint.textAlign = Paint.Align.LEFT
        hudPaint.color = colorGold
        canvas.drawText("Score: $score", dp(16f), dp(30f), hudPaint)
        hudPaint.textAlign = Paint.Align.RIGHT
        hudPaint.color = colorLeafGreen
        canvas.drawText("Lives: $lives", screenW - dp(16f), dp(30f), hudPaint)
        hudPaint.textAlign = Paint.Align.LEFT
        hudPaint.color = Color.WHITE

        canvas.drawText(
            "Level ${levelIndex + 1}/$totalLevels  ·  ${currentRows()} rows",
            screenW / 2f, dp(52f), levelPaint
        )
    }

    private fun drawControlZoneHint(canvas: Canvas) {
        val bottom = screenH - bottomBarH - dp(8f)
        val top = bottom - controlZoneH
        val margin = dp(24f)
        val track = RectF(margin, top, screenW - margin, bottom)

        paint.color = Color.WHITE
        paint.alpha = 22
        canvas.drawRoundRect(track, controlZoneH / 2f, controlZoneH / 2f, paint)

        val cy = (top + bottom) / 2f
        val cx = paddleX.coerceIn(track.left + controlZoneH / 2f, track.right - controlZoneH / 2f)

        paint.alpha = 60
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        canvas.drawCircle(cx, cy, dp(18f), paint)

        paint.style = Paint.Style.FILL
        paint.alpha = 110
        canvas.drawCircle(cx, cy, dp(9f), paint)
        paint.alpha = 255
    }

    // --- menu & sub-screens ---

    private fun menuButtonRects(): List<RectF> {
        val bw = screenW * 0.72f
        val bh = dp(58f)
        val gap = dp(22f)
        val left = (screenW - bw) / 2f
        val startY = screenH * 0.42f
        return (0 until 3).map { i ->
            val top = startY + i * (bh + gap)
            RectF(left, top, left + bw, top + bh)
        }
    }

    private fun backButtonRect(): RectF {
        val w = dp(130f)
        val h = dp(50f)
        return RectF(dp(20f), screenH - dp(30f) - h, dp(20f) + w, screenH - dp(30f))
    }

    private fun bgOptionRects(): List<RectF> {
        val w = screenW * 0.8f
        val h = dp(110f)
        val gap = dp(20f)
        val left = (screenW - w) / 2f
        val startY = screenH * 0.24f
        return (0 until 3).map { i ->
            val top = startY + i * (h + gap)
            RectF(left, top, left + w, top + h)
        }
    }

    private fun drawMenu(canvas: Canvas) {
        drawBackground(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.14f, screenW.toFloat(), screenH * 0.14f + dp(56f), paint)
        paint.alpha = 255
        drawOutlinedText(canvas, "SUPER PONG", screenW / 2f, screenH * 0.14f + dp(40f), titlePaint)

        val rects = menuButtonRects()
        val labels = listOf("New Game", "Top Score", "Change Background")
        val buttonColors = listOf(colorSkyBlue, colorGold, colorLeafGreen)
        for (i in rects.indices) {
            drawBeveledButton(canvas, rects[i], buttonColors[i])
            drawOutlinedText(canvas, labels[i], rects[i].centerX(), rects[i].centerY() + dp(7f), buttonTextPaint)
        }
    }

    private fun drawTopScoreScreen(canvas: Canvas) {
        drawBackground(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.14f, screenW.toFloat(), screenH * 0.5f, paint)
        paint.alpha = 255

        drawOutlinedText(canvas, "TOP SCORE", screenW / 2f, screenH * 0.22f, titlePaint)
        val scorePaint = Paint(bigPaint).apply { textSize = dp(44f); color = colorGold }
        drawOutlinedText(canvas, "$topScore", screenW / 2f, screenH * 0.34f, scorePaint)
        canvas.drawText("(best score on this device)", screenW / 2f, screenH * 0.34f + dp(34f), levelPaint)

        drawBackButton(canvas)
    }

    private fun drawBackgroundSelectScreen(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B1A33"))

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.08f, screenW.toFloat(), screenH * 0.08f + dp(50f), paint)
        paint.alpha = 255
        drawOutlinedText(
            canvas, "CHOOSE BACKGROUND", screenW / 2f, screenH * 0.08f + dp(36f),
            Paint(titlePaint).apply { textSize = dp(24f) }
        )

        val rects = bgOptionRects()
        val names = listOf("Grid", "Space", "Sky")
        val options = BgTheme.values()
        for (i in rects.indices) {
            canvas.save()
            canvas.clipRect(rects[i])
            when (options[i]) {
                BgTheme.GRID -> drawGridBackground(canvas)
                BgTheme.SPACE -> drawSpaceBackground(canvas)
                BgTheme.SKY -> drawSkyBackground(canvas)
            }
            canvas.restore()

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            val selected = bgTheme == options[i]
            paint.color = if (selected) Color.parseColor("#FFEB3B") else Color.WHITE
            paint.alpha = if (selected) 255 else 90
            canvas.drawRoundRect(rects[i], dp(10f), dp(10f), paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255

            paint.color = Color.BLACK
            paint.alpha = 120
            canvas.drawRect(rects[i].left, rects[i].bottom - dp(26f), rects[i].right, rects[i].bottom, paint)
            paint.alpha = 255
            drawOutlinedText(canvas, names[i], rects[i].centerX(), rects[i].bottom - dp(8f), buttonTextPaint)
        }

        drawBackButton(canvas)
    }

    private fun drawBackButton(canvas: Canvas) {
        val r = backButtonRect()
        drawBeveledButton(canvas, r, Color.parseColor("#78909C"))
        drawOutlinedText(canvas, "< Back", r.centerX(), r.centerY() + dp(7f), buttonTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) {
            return true
        }
        when (state) {
            GameState.MENU -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val rects = menuButtonRects()
                    when {
                        rects[0].contains(event.x, event.y) -> pendingAction = PendingAction.RESTART_GAME
                        rects[1].contains(event.x, event.y) -> {
                            state = GameState.TOP_SCORE
                            (context as? Activity)?.let { LeaderboardManager.tryOpenLeaderboardUi(it) }
                        }
                        rects[2].contains(event.x, event.y) -> state = GameState.BACKGROUND_SELECT
                    }
                }
            }
            GameState.TOP_SCORE -> {
                if (event.action == MotionEvent.ACTION_DOWN && backButtonRect().contains(event.x, event.y)) {
                    state = GameState.MENU
                }
            }
            GameState.BACKGROUND_SELECT -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (backButtonRect().contains(event.x, event.y)) {
                        state = GameState.MENU
                    } else {
                        val rects = bgOptionRects()
                        val options = BgTheme.values()
                        for (i in rects.indices) {
                            if (rects[i].contains(event.x, event.y)) {
                                selectBackground(options[i])
                            }
                        }
                    }
                }
            }
            GameState.PLAYING -> {
                paddleX = event.x.coerceIn(paddleW / 2f, screenW - paddleW / 2f)
            }
            GameState.LEVEL_CLEARED -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    pendingAction = if (isLastLevel()) PendingAction.RESTART_GAME else PendingAction.NEXT_LEVEL
                }
            }
            GameState.GAME_OVER -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    state = GameState.MENU
                }
            }
        }
        return true
    }
}
