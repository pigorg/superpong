package com.alessandrognola.superpong

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.res.ResourcesCompat
import java.io.File
import kotlin.math.max
import kotlin.math.min

private data class Block(val rect: RectF, val color: Int, val points: Int, val isGemBlock: Boolean = false)

private data class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val totalLife: Float, val color: Int, val radius: Float
)

private enum class GameState { MENU, TOP_SCORE, OPTIONS, SHOP, ACCOUNT, PLAYING, LEVEL_CLEARED, GAME_OVER }
private enum class PendingAction { NONE, NEXT_LEVEL, RESTART_GAME }
private enum class ThemePack { NONE, SOCCER, BASKETBALL, HOCKEY, SPACE }

private enum class DecorShape { GRIDLINES, DOTS, RINGS, DIAMONDS, TRIANGLES, STRIPES, MOON_CLOUDS }
private data class BgStyle(val name: String, val top: Int, val bottom: Int, val accent: Int, val shape: DecorShape, val density: Int = 22)

private enum class BallPattern { SOLID, STRIPES, DOTS, CHECKER, RING, METALLIC }
private data class BallStyle(val name: String, val base: Int, val accent: Int, val pattern: BallPattern)

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false

    private var screenW = 0
    private var screenH = 0

    private val prefs = context.getSharedPreferences("superpong_prefs", Context.MODE_PRIVATE)
    private var topScore = prefs.getInt("top_score", 0)
    private var gems = prefs.getInt("gems", 0)
    @Volatile private var pendingGemGrant = 0
    @Volatile private var bgIndex = prefs.getInt("bg_index", 0)
    @Volatile private var ballIndex = prefs.getInt("ball_index", 0)
    @Volatile private var optionsTab = 0 // 0 = background grid, 1 = ball grid

    private val gemBlockColor = Color.parseColor("#8E24AA")

    // --- Shop: 4 real-money theme packs (bg + ball + block skin bundle) ---
    private var themeSoccerOwned = prefs.getBoolean("theme_soccer_owned", false)
    private var themeBasketballOwned = prefs.getBoolean("theme_basketball_owned", false)
    private var themeHockeyOwned = prefs.getBoolean("theme_hockey_owned", false)
    private var themeSpaceOwned = prefs.getBoolean("theme_space_owned", false)
    @Volatile private var activeThemePack = ThemePack.NONE
    @Volatile private var pendingThemeUnlock: String? = null
    @Volatile private var pendingShopPurchase = -1
    private var storeMessageText = ""
    private var storeMessageTimer = 0f

    // --- reward crates: pop up when total gems first cross 100/200/300 ---
    private var crate100Shown = prefs.getBoolean("crate100_shown", false)
    private var crate200Shown = prefs.getBoolean("crate200_shown", false)
    private var crate300Shown = prefs.getBoolean("crate300_shown", false)
    private val crateQueue = mutableListOf<Int>()
    private var activeCrateTier = -1
    @Volatile private var pendingCrateOpen = false

    // --- lightweight local "account" (demo registration, no real backend yet) ---
    private var isRegistered = prefs.getBoolean("is_registered", false)
    private var username = prefs.getString("username", "") ?: ""
    @Volatile private var pendingRegister = false
    @Volatile private var pendingUsername: String? = null

    private var avatarIsPhoto = prefs.getBoolean("avatar_is_photo", false)
    private var avatarPreset = prefs.getInt("avatar_preset", 0)
    private var avatarBitmap: Bitmap? = null
    @Volatile private var pendingAvatarPreset = -1
    @Volatile private var pendingAvatarBitmap: Bitmap? = null

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
    private val colorGemPurple = Color.parseColor("#CE93D8")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(23f)
        isFakeBoldText = true
        typeface = gameFont
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(19f)
        alpha = 200
        typeface = gameFont
    }
    private val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(36f)
        isFakeBoldText = true
        typeface = gameFont
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(44f)
        isFakeBoldText = true
        typeface = gameFont
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(25f)
        isFakeBoldText = true
        typeface = gameFont
    }

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    init {
        if (avatarIsPhoto) {
            avatarBitmap = try {
                BitmapFactory.decodeFile(File(context.filesDir, "avatar.png").absolutePath)
            } catch (e: Exception) { null }
            if (avatarBitmap == null) avatarIsPhoto = false
        }
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

    private fun trianglePath(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Path =
        Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }

    private fun diamondPath(cx: Float, cy: Float, r: Float): Path =
        Path().apply { moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close() }

    private fun pentagonPath(cx: Float, cy: Float, r: Float): Path {
        val path = Path()
        for (i in 0 until 5) {
            val angle = (Math.PI * 2 / 5 * i - Math.PI / 2).toFloat()
            val x = cx + r * kotlin.math.cos(angle)
            val y = cy + r * kotlin.math.sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun starPath(cx: Float, cy: Float, r: Float): Path {
        val path = Path()
        val innerR = r * 0.45f
        for (i in 0 until 10) {
            val angle = (Math.PI / 5 * i - Math.PI / 2).toFloat()
            val rad = if (i % 2 == 0) r else innerR
            val x = cx + rad * kotlin.math.cos(angle)
            val y = cy + rad * kotlin.math.sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun drawGemIcon(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // faceted body
        paint.color = Color.parseColor("#E1BEE7")
        canvas.drawPath(diamondPath(cx, cy, r), paint)
        paint.color = Color.parseColor("#BA68C8")
        canvas.drawPath(trianglePath(cx, cy + r * 0.15f, cx + r * 0.65f, cy, cx, cy + r), paint)
        canvas.drawPath(trianglePath(cx, cy + r * 0.15f, cx - r * 0.65f, cy, cx, cy + r), paint)
        // top highlight facet
        paint.color = Color.WHITE
        paint.alpha = 190
        canvas.drawPath(trianglePath(cx, cy - r, cx + r * 0.55f, cy - r * 0.05f, cx - r * 0.55f, cy - r * 0.05f), paint)
        paint.alpha = 255
        // sparkle
        paint.color = Color.WHITE
        canvas.drawPath(starPath(cx + r * 0.55f, cy - r * 0.55f, r * 0.3f), paint)
    }

    private val avatarColors = listOf(
        Color.parseColor("#90A4AE"), // robot
        Color.parseColor("#FFB74D"), // cat
        Color.parseColor("#81C784"), // alien
        Color.parseColor("#ECEFF1"), // ghost
        Color.parseColor("#4FC3F7")  // astronaut
    )

    private fun drawAvatar(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val bmp = avatarBitmap
        if (avatarIsPhoto && bmp != null) {
            canvas.save()
            canvas.clipPath(Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) })
            canvas.drawBitmap(bmp, null, RectF(cx - radius, cy - radius, cx + radius, cy + radius), paint)
            canvas.restore()
        } else {
            drawAvatarPreset(canvas, avatarPreset, cx, cy, radius)
        }
    }

    private val avatarHairColors = listOf(
        Color.parseColor("#3E2723"), // brown
        Color.parseColor("#212121"), // black
        Color.parseColor("#F9A825"), // blonde
        Color.parseColor("#D84315"), // red
        Color.parseColor("#9E9E9E")  // grey
    )

    private val avatarShirtColors = listOf(
        Color.parseColor("#EF5350"),
        Color.parseColor("#42A5F5"),
        Color.parseColor("#66BB6A"),
        Color.parseColor("#AB47BC"),
        Color.parseColor("#FFA726")
    )

    private fun drawAvatarPreset(canvas: Canvas, index: Int, cx: Float, cy: Float, radius: Float) {
        val badgeColor = avatarColors[index % avatarColors.size]
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - radius * 0.3f, cy - radius * 0.3f, radius * 1.5f,
            lighten(badgeColor, 0.35f), darken(badgeColor, 0.2f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null
        drawPersonIcon(
            canvas, cx, cy, radius,
            avatarHairColors[index % avatarHairColors.size],
            avatarShirtColors[index % avatarShirtColors.size]
        )
    }

    private fun drawPersonIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, hairColor: Int, shirtColor: Int) {
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        paint.style = Paint.Style.FILL

        // neck
        paint.color = Color.parseColor("#E8A876")
        canvas.drawRect(RectF(cx - r * 0.12f, cy + r * 0.02f, cx + r * 0.12f, cy + r * 0.3f), paint)

        // shoulders / shirt, shaded top-to-bottom
        paint.shader = LinearGradient(
            cx, cy + r * 0.35f, cx, cy + r * 1.5f,
            lighten(shirtColor, 0.2f), darken(shirtColor, 0.25f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + r * 0.95f, r * 0.64f, paint)
        paint.shader = null

        // collar
        paint.color = darken(shirtColor, 0.35f)
        canvas.drawArc(RectF(cx - r * 0.16f, cy + r * 0.16f, cx + r * 0.16f, cy + r * 0.42f), 0f, 180f, false, paint)

        // head, shaded for roundness
        paint.shader = RadialGradient(
            cx - r * 0.12f, cy - r * 0.28f, r * 0.55f,
            Color.parseColor("#FFE0B2"), Color.parseColor("#E0A579"), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy - r * 0.16f, r * 0.33f, paint)
        paint.shader = null

        // hair: cap plus short side strands
        paint.color = hairColor
        canvas.drawArc(RectF(cx - r * 0.35f, cy - r * 0.5f, cx + r * 0.35f, cy + r * 0.02f), 180f, 180f, false, paint)
        canvas.drawRect(RectF(cx - r * 0.35f, cy - r * 0.28f, cx - r * 0.27f, cy - r * 0.02f), paint)
        canvas.drawRect(RectF(cx + r * 0.27f, cy - r * 0.28f, cx + r * 0.35f, cy - r * 0.02f), paint)

        // face: eyes + smile
        paint.color = Color.parseColor("#3E2723")
        canvas.drawCircle(cx - r * 0.11f, cy - r * 0.17f, max(1f, r * 0.035f), paint)
        canvas.drawCircle(cx + r * 0.11f, cy - r * 0.17f, max(1f, r * 0.035f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, r * 0.05f)
        canvas.drawArc(RectF(cx - r * 0.1f, cy - r * 0.09f, cx + r * 0.1f, cy + r * 0.04f), 10f, 160f, false, paint)
        paint.style = Paint.Style.FILL

        canvas.restore()
    }

    /** A falling "block" reskinned as a tiny player (Soccer/Basketball/Hockey) or alien ship (Space),
     *  tinted with the block's row color so the score-band color coding stays readable. */
    private fun drawThemedBlock(canvas: Canvas, rect: RectF, tint: Int, pack: ThemePack) {
        when (pack) {
            ThemePack.SOCCER -> drawSportPlayerBlock(canvas, rect, tint, Color.parseColor("#1B1B1B"))
            ThemePack.BASKETBALL -> drawSportPlayerBlock(canvas, rect, tint, Color.parseColor("#5D4037"))
            ThemePack.HOCKEY -> drawSportPlayerBlock(canvas, rect, tint, Color.parseColor("#37474F"))
            ThemePack.SPACE -> drawAlienShipBlock(canvas, rect, tint)
            ThemePack.NONE -> {}
        }
    }

    private fun drawSportPlayerBlock(canvas: Canvas, rect: RectF, jerseyColor: Int, shortsColor: Int) {
        val cx = rect.centerX()
        val w = rect.width()
        val h = rect.height()
        val headR = h * 0.16f
        val headCy = rect.top + h * 0.24f
        val bodyTop = headCy + headR * 0.85f
        val bodyBottom = rect.bottom - h * 0.1f
        val bodyH = bodyBottom - bodyTop
        val bodyW = w * 0.36f

        paint.style = Paint.Style.FILL
        paint.color = shortsColor
        canvas.drawRect(cx - bodyW * 0.5f, bodyTop + bodyH * 0.55f, cx - dp(1f), bodyBottom, paint)
        canvas.drawRect(cx + dp(1f), bodyTop + bodyH * 0.55f, cx + bodyW * 0.5f, bodyBottom, paint)

        paint.shader = LinearGradient(
            cx, bodyTop, cx, bodyTop + bodyH * 0.62f,
            lighten(jerseyColor, 0.25f), darken(jerseyColor, 0.2f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH * 0.62f), dp(3f), dp(3f), paint)
        paint.shader = null

        paint.color = Color.parseColor("#FFD9B3")
        canvas.drawCircle(cx, headCy, headR, paint)
    }

    private fun drawAlienShipBlock(canvas: Canvas, rect: RectF, hullColor: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val w = rect.width() * 0.8f
        val h = rect.height() * 0.5f

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx, cy - h / 2f, cx, cy + h / 2f,
            lighten(hullColor, 0.3f), darken(hullColor, 0.25f), Shader.TileMode.CLAMP
        )
        canvas.drawOval(RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), paint)
        paint.shader = null

        paint.color = Color.parseColor("#B3E5FC")
        paint.alpha = 200
        canvas.drawOval(RectF(cx - w * 0.22f, cy - h * 0.85f, cx + w * 0.22f, cy + h * 0.05f), paint)
        paint.alpha = 255

        paint.color = Color.WHITE
        canvas.drawCircle(cx - w * 0.28f, cy + h * 0.15f, dp(2f), paint)
        canvas.drawCircle(cx, cy + h * 0.25f, dp(2f), paint)
        canvas.drawCircle(cx + w * 0.28f, cy + h * 0.15f, dp(2f), paint)
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

    // levels 1-18 are the hand-tuned curriculum; from level 19 on it's endless and keeps
    // accelerating so the game never just "ends".
    private fun isLastLevel() = false

    private fun currentFallSpeed(): Float {
        if (levelIndex < totalLevels) return macroFallSpeeds[levelIndex / rowsPerSubLevel.size]
        val extraLevels = levelIndex - totalLevels + 1
        return macroFallSpeeds.last() + dp(3f) * extraLevels
    }

    private fun currentRows(): Int {
        if (levelIndex < totalLevels) return rowsPerSubLevel[levelIndex % rowsPerSubLevel.size]
        return rowsPerSubLevel.last()
    }

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

        val topStart = dp(84f)
        for (row in 0 until currentRows()) {
            spawnRow(topStart + row * (blockH + rowGap))
        }

        if (blocks.isNotEmpty()) {
            val idx = (Math.random() * blocks.size).toInt()
            val b = blocks[idx]
            blocks[idx] = Block(b.rect, gemBlockColor, b.points, isGemBlock = true)
        }
    }

    private fun addGems(amount: Int) {
        gems += amount
        prefs.edit().putInt("gems", gems).apply()
        if (amount > 0) checkCrateMilestones()
    }

    private fun checkCrateMilestones() {
        if (!crate100Shown && gems >= 100) {
            crate100Shown = true
            prefs.edit().putBoolean("crate100_shown", true).apply()
            crateQueue.add(0)
        }
        if (!crate200Shown && gems >= 200) {
            crate200Shown = true
            prefs.edit().putBoolean("crate200_shown", true).apply()
            crateQueue.add(1)
        }
        if (!crate300Shown && gems >= 300) {
            crate300Shown = true
            prefs.edit().putBoolean("crate300_shown", true).apply()
            crateQueue.add(2)
        }
    }

    private val crateBonusGems = intArrayOf(50, 75, 100)

    private fun openCrate(tier: Int) {
        gems += crateBonusGems.getOrElse(tier) { 0 }
        prefs.edit().putInt("gems", gems).apply()
        spawnFireworks()
    }

    fun grantGems(amount: Int) {
        pendingGemGrant += amount
    }

    fun grantTheme(themeId: String) {
        pendingThemeUnlock = themeId
    }

    fun setUsername(name: String) {
        val trimmed = name.trim().take(16)
        if (trimmed.isNotEmpty()) pendingUsername = trimmed
    }

    fun setAvatarPhoto(bitmap: Bitmap) {
        pendingAvatarBitmap = bitmap
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

    private fun registerDemoAccount() {
        username = "Player" + (1000 + (Math.random() * 9000).toInt())
        isRegistered = true
        prefs.edit().putBoolean("is_registered", true).putString("username", username).apply()
    }

    private fun applyUsername(name: String) {
        username = name
        prefs.edit().putString("username", username).apply()
    }

    private fun applyAvatarPreset(index: Int) {
        avatarPreset = index
        avatarIsPhoto = false
        prefs.edit().putInt("avatar_preset", index).putBoolean("avatar_is_photo", false).apply()
    }

    private fun applyAvatarPhoto(bitmap: Bitmap) {
        avatarBitmap = bitmap
        avatarIsPhoto = true
        prefs.edit().putBoolean("avatar_is_photo", true).apply()
        try {
            context.openFileOutput("avatar.png", Context.MODE_PRIVATE).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) { /* best effort persistence */ }
    }

    private val shopThemeProductIds = listOf("theme_soccer", "theme_basketball", "theme_hockey", "theme_space")
    private val shopThemeIds = listOf("soccer", "basketball", "hockey", "space")
    private val shopDemoGemCost = 300

    private fun shopThemeOwned(index: Int): Boolean = when (index) {
        0 -> themeSoccerOwned
        1 -> themeBasketballOwned
        2 -> themeHockeyOwned
        3 -> themeSpaceOwned
        else -> false
    }

    private fun handleShopPurchase(index: Int) {
        if (shopThemeOwned(index)) {
            activeThemePack = ThemePack.values()[index + 1]
            pendingAction = PendingAction.RESTART_GAME
            return
        }
        val activity = context as? Activity
        val price = BillingManager.priceFor(shopThemeProductIds[index])
        if (price != null && activity != null) {
            BillingManager.purchase(activity, shopThemeProductIds[index])
            return
        }
        // Demo fallback: real Play Billing isn't configured yet (needs Play Console setup), so let
        // the theme be unlocked with in-game gems for now — keeps the Shop testable in this demo build.
        if (gems < shopDemoGemCost) {
            storeMessageText = "Not enough gems (need $shopDemoGemCost)"
            storeMessageTimer = 1.6f
            return
        }
        gems -= shopDemoGemCost
        prefs.edit().putInt("gems", gems).apply()
        unlockTheme(shopThemeIds[index])
    }

    private fun unlockTheme(themeId: String) {
        when (themeId) {
            "soccer" -> { themeSoccerOwned = true; prefs.edit().putBoolean("theme_soccer_owned", true).apply() }
            "basketball" -> { themeBasketballOwned = true; prefs.edit().putBoolean("theme_basketball_owned", true).apply() }
            "hockey" -> { themeHockeyOwned = true; prefs.edit().putBoolean("theme_hockey_owned", true).apply() }
            "space" -> { themeSpaceOwned = true; prefs.edit().putBoolean("theme_space_owned", true).apply() }
        }
        storeMessageText = "Purchased!"
        storeMessageTimer = 1.2f
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

    private fun drawSphereBase(canvas: Canvas, cx: Float, cy: Float, r: Float, base: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.7f,
            intArrayOf(lighten(base, 0.6f), base, darken(base, 0.3f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    private fun drawSphereGloss(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = 100
        canvas.drawOval(RectF(cx - r * 0.55f, cy - r * 0.72f, cx - r * 0.05f, cy - r * 0.26f), paint)
        paint.alpha = 255
    }

    /** Ball used by a purchased Shop theme (Free ball styles use [drawFreeBallStyle] instead). */
    private fun drawThemeBall(canvas: Canvas, cx: Float, cy: Float, r: Float, pack: ThemePack) {
        when (pack) {
            ThemePack.SOCCER -> {
                drawSphereBase(canvas, cx, cy, r, Color.WHITE)
                paint.color = Color.parseColor("#1B1B1B")
                canvas.drawPath(pentagonPath(cx, cy, r * 0.4f), paint)
                for (i in 0 until 5) {
                    val angle = (Math.PI * 2 / 5 * i - Math.PI / 2).toFloat()
                    val px = cx + r * 0.74f * kotlin.math.cos(angle)
                    val py = cy + r * 0.74f * kotlin.math.sin(angle)
                    canvas.drawPath(pentagonPath(px, py, r * 0.23f), paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, r * 0.05f)
                paint.color = Color.parseColor("#455A64")
                canvas.drawCircle(cx, cy, r, paint)
                paint.style = Paint.Style.FILL
                drawSphereGloss(canvas, cx, cy, r)
            }
            ThemePack.BASKETBALL -> {
                drawSphereBase(canvas, cx, cy, r, Color.parseColor("#E65100"))
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.1f, r * 0.09f)
                paint.color = Color.parseColor("#3E1F00")
                val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawLine(cx - r, cy, cx + r, cy, paint)
                canvas.drawLine(cx, cy - r, cx, cy + r, paint)
                canvas.drawArc(oval, 205f, 130f, false, paint)
                canvas.drawArc(oval, 25f, 130f, false, paint)
                paint.style = Paint.Style.FILL
                drawSphereGloss(canvas, cx, cy, r)
            }
            ThemePack.HOCKEY -> {
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#111111")
                canvas.drawOval(RectF(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, r * 0.08f)
                paint.color = Color.parseColor("#424242")
                canvas.drawOval(RectF(cx - r, cy - r * 0.62f, cx + r, cy + r * 0.62f), paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.alpha = 60
                canvas.drawOval(RectF(cx - r * 0.5f, cy - r * 0.45f, cx + r * 0.1f, cy - r * 0.15f), paint)
                paint.alpha = 255
            }
            ThemePack.SPACE -> {
                paint.color = Color.parseColor("#7C4DFF")
                paint.alpha = 60
                canvas.drawCircle(cx, cy, r * 1.7f, paint)
                paint.alpha = 255
                paint.shader = RadialGradient(
                    cx - r * 0.3f, cy - r * 0.3f, r * 1.6f,
                    intArrayOf(Color.parseColor("#E1F5FE"), Color.parseColor("#26C6DA"), Color.parseColor("#4527A0")),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, r, paint)
                paint.shader = null
                drawSphereGloss(canvas, cx, cy, r)
            }
            ThemePack.NONE -> {}
        }
    }

    /** Free ball style chosen in Options (20 variants over a shared sphere renderer). */
    private fun drawFreeBallStyle(canvas: Canvas, cx: Float, cy: Float, r: Float, style: BallStyle) {
        drawSphereBase(canvas, cx, cy, r, style.base)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) })
        paint.style = Paint.Style.FILL
        when (style.pattern) {
            BallPattern.SOLID -> {}
            BallPattern.STRIPES -> {
                paint.color = style.accent
                paint.alpha = 200
                var sx = cx - r * 1.4f
                while (sx < cx + r * 1.4f) {
                    canvas.drawRect(sx, cy - r, sx + r * 0.4f, cy + r, paint)
                    sx += r * 0.85f
                }
                paint.alpha = 255
            }
            BallPattern.DOTS -> {
                paint.color = style.accent
                for (pt in decorPoints.take(9)) {
                    val px = cx - r + pt[0] * r * 2f
                    val py = cy - r + pt[1] * r * 2f
                    canvas.drawCircle(px, py, r * 0.14f, paint)
                }
            }
            BallPattern.CHECKER -> {
                paint.color = style.accent
                val cell = r * 0.7f
                var gy = cy - r
                var row = 0
                while (gy < cy + r) {
                    var gx = cx - r + if (row % 2 == 0) 0f else cell
                    while (gx < cx + r) {
                        canvas.drawRect(gx, gy, gx + cell, gy + cell, paint)
                        gx += cell * 2f
                    }
                    gy += cell
                    row++
                }
            }
            BallPattern.RING -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1.5f, r * 0.16f)
                paint.color = style.accent
                canvas.drawCircle(cx, cy, r * 0.62f, paint)
                paint.style = Paint.Style.FILL
            }
            BallPattern.METALLIC -> {
                paint.color = Color.WHITE
                paint.alpha = 70
                canvas.drawRect(cx - r, cy - r * 0.15f, cx + r, cy + r * 0.05f, paint)
                paint.alpha = 255
            }
        }
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, r * 0.07f)
        paint.color = darken(style.base, 0.4f)
        paint.alpha = 110
        canvas.drawCircle(cx, cy, r, paint)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        drawSphereGloss(canvas, cx, cy, r)
    }

    private fun drawGameplayBall(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (activeThemePack != ThemePack.NONE) {
            drawThemeBall(canvas, cx, cy, r, activeThemePack)
        } else {
            drawFreeBallStyle(canvas, cx, cy, r, freeBalls[ballIndex.coerceIn(0, freeBalls.size - 1)])
        }
    }

    private fun currentBallColor(): Int = when (activeThemePack) {
        ThemePack.SOCCER -> Color.WHITE
        ThemePack.BASKETBALL -> Color.parseColor("#E65100")
        ThemePack.HOCKEY -> Color.parseColor("#111111")
        ThemePack.SPACE -> Color.parseColor("#26C6DA")
        ThemePack.NONE -> freeBalls[ballIndex.coerceIn(0, freeBalls.size - 1)].base
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

            if (pendingGemGrant != 0) {
                val grant = pendingGemGrant
                pendingGemGrant -= grant
                addGems(grant)
            }

            if (pendingShopPurchase >= 0) {
                val idx = pendingShopPurchase
                pendingShopPurchase = -1
                handleShopPurchase(idx)
            }

            pendingThemeUnlock?.let {
                pendingThemeUnlock = null
                unlockTheme(it)
            }

            if (pendingRegister) {
                pendingRegister = false
                if (!isRegistered) registerDemoAccount()
            }

            pendingUsername?.let {
                pendingUsername = null
                applyUsername(it)
            }
            if (pendingAvatarPreset >= 0) {
                val idx = pendingAvatarPreset
                pendingAvatarPreset = -1
                applyAvatarPreset(idx)
            }
            pendingAvatarBitmap?.let {
                pendingAvatarBitmap = null
                applyAvatarPhoto(it)
            }

            if (activeCrateTier < 0 && crateQueue.isNotEmpty()) {
                activeCrateTier = crateQueue.removeAt(0)
            }
            if (pendingCrateOpen) {
                pendingCrateOpen = false
                if (activeCrateTier >= 0) {
                    openCrate(activeCrateTier)
                    activeCrateTier = -1
                }
            }
            if (storeMessageTimer > 0f) storeMessageTimer -= dt

            updateParticles(dt)
            if (state == GameState.PLAYING && activeCrateTier < 0) update(dt)
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
                addGems(if (b.isGemBlock) 100 else 1)
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
            spawnBurst(ballX, screenH - dp(30f), currentBallColor(), 22)
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
                GameState.OPTIONS -> drawOptionsScreen(canvas)
                GameState.SHOP -> drawShopScreen(canvas)
                GameState.ACCOUNT -> drawAccountScreen(canvas)
                else -> drawGameplay(canvas)
            }
            if (activeCrateTier >= 0) drawCrateOverlay(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawGameplay(canvas: Canvas) {
        drawBackground(canvas)

        // blocks (3D relief: gradient + top highlight + bottom shadow; themed games draw little
        // players/ships instead, tinted by the block's row color so the score bands stay readable)
        for (b in blocks) {
            if (activeThemePack != ThemePack.NONE) {
                drawThemedBlock(canvas, b.rect, b.color, activeThemePack)
            } else {
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

            if (b.isGemBlock) {
                drawGemIcon(canvas, b.rect.centerX(), b.rect.centerY(), dp(10f))
            }
        }

        // paddle
        val paddleRect = RectF(paddleX - paddleW / 2f, paddleY, paddleX + paddleW / 2f, paddleY + paddleH)
        drawBeveledButton(canvas, paddleRect, colorSkyBlue)

        // ball (naturally off-screen once missed, so no extra visibility check needed)
        drawGameplayBall(canvas, ballX, ballY, ballR)

        drawControlZoneHint(canvas)
        drawTopBar(canvas)

        when (state) {
            GameState.LEVEL_CLEARED -> {
                canvas.drawColor(Color.argb(160, 0, 0, 0))
                drawParticles(canvas)
                if (isLastLevel()) {
                    drawOutlinedText(canvas, "CONGRATULATIONS!", screenW / 2f, screenH / 2f - dp(20f), bigPaint)
                    val sub = Paint(bigPaint).apply { textSize = dp(23f) }
                    canvas.drawText("You completed all levels", screenW / 2f, screenH / 2f + dp(20f), sub)
                    canvas.drawText("Score: $score", screenW / 2f, screenH / 2f + dp(50f), sub)
                    canvas.drawText("Tap to restart", screenW / 2f, screenH / 2f + dp(86f), sub)
                } else {
                    drawOutlinedText(canvas, "LEVEL COMPLETE", screenW / 2f, screenH / 2f - dp(20f), bigPaint)
                    val sub = Paint(bigPaint).apply { textSize = dp(23f) }
                    canvas.drawText("Score: $score", screenW / 2f, screenH / 2f + dp(20f), sub)
                    canvas.drawText("Tap to continue", screenW / 2f, screenH / 2f + dp(56f), sub)
                }
            }
            GameState.GAME_OVER -> {
                canvas.drawColor(Color.argb(160, 0, 0, 0))
                drawParticles(canvas)
                drawOutlinedText(canvas, "GAME OVER", screenW / 2f, screenH / 2f - dp(20f), bigPaint)
                val sub = Paint(bigPaint).apply { textSize = dp(23f) }
                canvas.drawText("Score: $score", screenW / 2f, screenH / 2f + dp(20f), sub)
                canvas.drawText("Best: $topScore", screenW / 2f, screenH / 2f + dp(50f), sub)
                canvas.drawText("Tap for menu", screenW / 2f, screenH / 2f + dp(86f), sub)
            }
            else -> drawParticles(canvas)
        }
    }

    private fun drawCrateOverlay(canvas: Canvas) {
        canvas.drawColor(Color.argb(190, 0, 0, 0))
        val cx = screenW / 2f
        val cy = screenH / 2f
        val half = dp(50f)
        val lidH = half * 0.6f

        paint.shader = LinearGradient(
            cx - half, cy - lidH, cx - half, cy + half * 0.8f,
            lighten(colorGold, 0.25f), darken(colorGold, 0.2f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(RectF(cx - half, cy - lidH, cx + half, cy + half * 0.8f), dp(10f), dp(10f), paint)
        paint.shader = null

        paint.color = Color.parseColor("#D32F2F")
        canvas.drawRect(cx - dp(8f), cy - lidH, cx + dp(8f), cy + half * 0.8f, paint)
        canvas.drawRect(cx - half, cy - dp(6f), cx + half, cy + dp(6f), paint)
        canvas.drawPath(trianglePath(cx, cy - lidH, cx - dp(16f), cy - lidH - dp(16f), cx - dp(2f), cy - lidH - dp(2f)), paint)
        canvas.drawPath(trianglePath(cx, cy - lidH, cx + dp(16f), cy - lidH - dp(16f), cx + dp(2f), cy - lidH - dp(2f)), paint)

        drawOutlinedText(canvas, "GIFT UNLOCKED!", cx, cy - half - dp(30f), bigPaint)
        val label = "+${crateBonusGems.getOrElse(activeCrateTier) { 0 }} Gems"
        val sub = Paint(buttonTextPaint).apply { textSize = dp(21f) }
        canvas.drawText("You got: $label", cx, cy + half + dp(34f), sub)
        canvas.drawText("Tap to claim", cx, cy + half + dp(60f), sub)
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
    private val decorPoints = mutableListOf<FloatArray>() // normalized x, y (0..1), sizeSeed, alphaSeed - shared by generated free backgrounds/balls

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
        decorPoints.clear()
        repeat(28) {
            decorPoints.add(floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat()))
        }
    }

    private val freeBackgrounds = listOf(
        BgStyle("Grid", Color.parseColor("#0B1A33"), Color.parseColor("#0B1A33"), Color.WHITE, DecorShape.GRIDLINES),
        BgStyle("Night Sky", Color.parseColor("#0D1B4C"), Color.parseColor("#2B3A73"), Color.WHITE, DecorShape.MOON_CLOUDS),
        BgStyle("Diamonds", Color.BLACK, Color.BLACK, Color.parseColor("#5C6BC0"), DecorShape.DIAMONDS),
        BgStyle("Sunset", Color.parseColor("#FF7043"), Color.parseColor("#4A148C"), Color.parseColor("#FFD54F"), DecorShape.DOTS),
        BgStyle("Ocean", Color.parseColor("#01579B"), Color.parseColor("#00838F"), Color.parseColor("#80DEEA"), DecorShape.RINGS),
        BgStyle("Forest", Color.parseColor("#1B5E20"), Color.parseColor("#33691E"), Color.parseColor("#AED581"), DecorShape.TRIANGLES),
        BgStyle("Candy", Color.parseColor("#F48FB1"), Color.parseColor("#CE93D8"), Color.WHITE, DecorShape.DOTS),
        BgStyle("Lava", Color.parseColor("#3E2723"), Color.parseColor("#BF360C"), Color.parseColor("#FFAB40"), DecorShape.TRIANGLES),
        BgStyle("Aurora", Color.parseColor("#0D1B2A"), Color.parseColor("#1B4332"), Color.parseColor("#64FFDA"), DecorShape.STRIPES),
        BgStyle("Synthwave", Color.parseColor("#2B0B3F"), Color.parseColor("#7A0C4B"), Color.parseColor("#00E5FF"), DecorShape.GRIDLINES),
        BgStyle("Bubblegum", Color.parseColor("#F8BBD0"), Color.parseColor("#F48FB1"), Color.WHITE, DecorShape.RINGS),
        BgStyle("Desert", Color.parseColor("#FFD54F"), Color.parseColor("#EF6C00"), Color.parseColor("#FFF3E0"), DecorShape.DOTS),
        BgStyle("Coral", Color.parseColor("#00695C"), Color.parseColor("#26A69A"), Color.parseColor("#FF8A65"), DecorShape.RINGS),
        BgStyle("Mint", Color.parseColor("#004D40"), Color.parseColor("#00796B"), Color.parseColor("#B2FF59"), DecorShape.TRIANGLES),
        BgStyle("Berry", Color.parseColor("#4A148C"), Color.parseColor("#880E4F"), Color.parseColor("#F06292"), DecorShape.DOTS),
        BgStyle("Citrus", Color.parseColor("#F9A825"), Color.parseColor("#FBC02D"), Color.parseColor("#33691E"), DecorShape.STRIPES),
        BgStyle("Slate Tech", Color.parseColor("#101820"), Color.parseColor("#1C2833"), Color.parseColor("#00E5FF"), DecorShape.GRIDLINES),
        BgStyle("Rain", Color.parseColor("#37474F"), Color.parseColor("#263238"), Color.parseColor("#90A4AE"), DecorShape.STRIPES),
        BgStyle("Confetti", Color.parseColor("#212121"), Color.parseColor("#424242"), Color.parseColor("#FF4081"), DecorShape.DOTS),
        BgStyle("Golden Hour", Color.parseColor("#FFB300"), Color.parseColor("#6A1B9A"), Color.parseColor("#FFF176"), DecorShape.RINGS)
    )

    private val freeBalls = listOf(
        BallStyle("Yellow", Color.parseColor("#FFEB3B"), Color.parseColor("#FBC02D"), BallPattern.SOLID),
        BallStyle("White", Color.WHITE, Color.parseColor("#B0BEC5"), BallPattern.SOLID),
        BallStyle("Red", Color.parseColor("#E53935"), Color.parseColor("#FFCDD2"), BallPattern.SOLID),
        BallStyle("Blue", Color.parseColor("#1E88E5"), Color.parseColor("#BBDEFB"), BallPattern.SOLID),
        BallStyle("Green", Color.parseColor("#43A047"), Color.parseColor("#C8E6C9"), BallPattern.SOLID),
        BallStyle("Purple", Color.parseColor("#8E24AA"), Color.parseColor("#E1BEE7"), BallPattern.SOLID),
        BallStyle("Orange", Color.parseColor("#FB8C00"), Color.parseColor("#FFE0B2"), BallPattern.SOLID),
        BallStyle("Pink", Color.parseColor("#EC407A"), Color.parseColor("#F8BBD0"), BallPattern.SOLID),
        BallStyle("Teal", Color.parseColor("#00897B"), Color.parseColor("#B2DFDB"), BallPattern.SOLID),
        BallStyle("Lime", Color.parseColor("#C0CA33"), Color.parseColor("#F0F4C3"), BallPattern.SOLID),
        BallStyle("Cyan Ring", Color.parseColor("#00BCD4"), Color.WHITE, BallPattern.RING),
        BallStyle("Magenta Ring", Color.parseColor("#D81B60"), Color.WHITE, BallPattern.RING),
        BallStyle("Candy Stripes", Color.parseColor("#E53935"), Color.WHITE, BallPattern.STRIPES),
        BallStyle("Blueberry Stripes", Color.parseColor("#1565C0"), Color.WHITE, BallPattern.STRIPES),
        BallStyle("Checker", Color.parseColor("#212121"), Color.WHITE, BallPattern.CHECKER),
        BallStyle("Grape Stripes", Color.parseColor("#5E35B1"), Color.parseColor("#FFEB3B"), BallPattern.STRIPES),
        BallStyle("Gold", Color.parseColor("#FFC107"), Color.parseColor("#FFF8E1"), BallPattern.METALLIC),
        BallStyle("Silver", Color.parseColor("#B0BEC5"), Color.parseColor("#ECEFF1"), BallPattern.METALLIC),
        BallStyle("Neon Glow", Color.parseColor("#00E676"), Color.WHITE, BallPattern.RING),
        BallStyle("Polka Dot", Color.parseColor("#FF80AB"), Color.WHITE, BallPattern.DOTS)
    )

    private fun selectBackground(index: Int) {
        bgIndex = index
        prefs.edit().putInt("bg_index", index).apply()
    }

    private fun selectBallSkin(index: Int) {
        ballIndex = index
        prefs.edit().putInt("ball_index", index).apply()
    }

    private fun drawBackground(canvas: Canvas) {
        ensureDecorGenerated()
        if (activeThemePack != ThemePack.NONE) {
            drawThemeBackground(canvas, activeThemePack)
        } else {
            drawFreeBackground(canvas, freeBackgrounds[bgIndex.coerceIn(0, freeBackgrounds.size - 1)])
        }
    }

    private fun drawFreeBackground(
        canvas: Canvas,
        style: BgStyle,
        area: RectF = RectF(0f, 0f, screenW.toFloat(), screenH.toFloat())
    ) {
        when (style.shape) {
            DecorShape.GRIDLINES -> {
                paint.color = style.top
                canvas.drawRect(area, paint)
                paint.color = style.accent
                paint.alpha = 26
                paint.strokeWidth = dp(1f)
                val step = min(area.width(), area.height()) / 7f
                var x = area.left
                while (x < area.right) { canvas.drawLine(x, area.top, x, area.bottom, paint); x += step }
                var y = area.top
                while (y < area.bottom) { canvas.drawLine(area.left, y, area.right, y, paint); y += step }
                paint.alpha = 255
            }
            DecorShape.MOON_CLOUDS -> drawSkyBackground(canvas)
            else -> {
                if (style.top == style.bottom) {
                    paint.color = style.top
                    canvas.drawRect(area, paint)
                } else {
                    paint.shader = LinearGradient(
                        area.left, area.top, area.left, area.bottom, style.top, style.bottom, Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(area, paint)
                    paint.shader = null
                }
                paint.color = style.accent
                val n = style.density.coerceAtMost(decorPoints.size)
                val unit = min(area.width(), area.height())
                when (style.shape) {
                    DecorShape.DOTS -> for (i in 0 until n) {
                        val pt = decorPoints[i]
                        paint.alpha = (40 + pt[3] * 70).toInt()
                        canvas.drawCircle(area.left + pt[0] * area.width(), area.top + pt[1] * area.height(), unit * (0.02f + pt[2] * 0.05f), paint)
                    }
                    DecorShape.RINGS -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = max(1f, unit * 0.012f)
                        for (i in 0 until n) {
                            val pt = decorPoints[i]
                            paint.alpha = (30 + pt[3] * 60).toInt()
                            canvas.drawCircle(area.left + pt[0] * area.width(), area.top + pt[1] * area.height(), unit * (0.05f + pt[2] * 0.14f), paint)
                        }
                        paint.style = Paint.Style.FILL
                    }
                    DecorShape.TRIANGLES -> for (i in 0 until n) {
                        val pt = decorPoints[i]
                        paint.alpha = (30 + pt[3] * 60).toInt()
                        val cx = area.left + pt[0] * area.width()
                        val cy = area.top + pt[1] * area.height()
                        val s = unit * (0.06f + pt[2] * 0.12f)
                        canvas.drawPath(trianglePath(cx, cy - s, cx + s, cy + s, cx - s, cy + s), paint)
                    }
                    DecorShape.DIAMONDS -> for (i in 0 until n) {
                        val pt = decorPoints[i]
                        paint.alpha = (30 + pt[3] * 60).toInt()
                        val cx = area.left + pt[0] * area.width()
                        val cy = area.top + pt[1] * area.height()
                        val s = unit * (0.05f + pt[2] * 0.14f)
                        canvas.drawPath(diamondPath(cx, cy, s), paint)
                    }
                    DecorShape.STRIPES -> {
                        canvas.save()
                        canvas.clipRect(area)
                        paint.alpha = 30
                        val stripeW = unit * 0.18f
                        canvas.rotate(-20f, area.centerX(), area.centerY())
                        val diag = area.width() + area.height()
                        var sx = area.centerX() - diag
                        while (sx < area.centerX() + diag) {
                            canvas.drawRect(sx, area.centerY() - diag, sx + stripeW, area.centerY() + diag, paint)
                            sx += stripeW * 2.4f
                        }
                        canvas.restore()
                    }
                    else -> {}
                }
                paint.alpha = 255
            }
        }
    }

    private fun drawSkyBackground(canvas: Canvas) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, screenH.toFloat(),
            Color.parseColor("#0D1B4C"), Color.parseColor("#2B3A73"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)
        paint.shader = null

        // moon
        paint.color = Color.parseColor("#FFF9C4")
        paint.alpha = 220
        canvas.drawCircle(screenW * 0.78f, screenH * 0.12f, dp(30f), paint)
        paint.alpha = 255

        // faint stars behind the clouds for a night feel
        paint.color = Color.WHITE
        for (s in stars) {
            paint.alpha = (s[3] * 0.6f).toInt()
            canvas.drawCircle(s[0], s[1] * 0.6f, s[2] * 0.7f, paint)
        }
        paint.alpha = 255

        paint.color = Color.parseColor("#C5CAE9")
        paint.alpha = 200
        for (c in clouds) {
            canvas.drawOval(c[0] - c[2], c[1] - c[3], c[0] + c[2], c[1] + c[3], paint)
            canvas.drawOval(c[0] - c[2] * 0.6f, c[1] - c[3] * 1.3f, c[0] + c[2] * 0.5f, c[1] + c[3] * 0.5f, paint)
            canvas.drawOval(c[0] - c[2] * 0.1f, c[1] - c[3] * 1.1f, c[0] + c[2], c[1] + c[3] * 0.6f, paint)
        }
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

        // premium touch: a distant glowing planet so the paid Space theme reads as special
        paint.shader = RadialGradient(
            screenW * 0.78f, screenH * 0.16f, dp(46f),
            Color.parseColor("#B39DDB"), Color.parseColor("#4527A0"), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(screenW * 0.78f, screenH * 0.16f, dp(34f), paint)
        paint.shader = null
    }

    private fun drawThemeBackground(canvas: Canvas, pack: ThemePack) {
        when (pack) {
            ThemePack.SOCCER -> drawSoccerFieldBackground(canvas)
            ThemePack.BASKETBALL -> drawBasketballCourtBackground(canvas)
            ThemePack.HOCKEY -> drawHockeyRinkBackground(canvas)
            ThemePack.SPACE -> drawSpaceBackground(canvas)
            ThemePack.NONE -> {}
        }
    }

    private fun drawSoccerFieldBackground(canvas: Canvas) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, screenH.toFloat(),
            Color.parseColor("#2E7D32"), Color.parseColor("#1B5E20"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)
        paint.shader = null

        // mowed-grass stripes
        paint.color = Color.WHITE
        paint.alpha = 12
        val stripeH = screenH / 10f
        for (i in 0 until 10 step 2) canvas.drawRect(0f, i * stripeH, screenW.toFloat(), (i + 1) * stripeH, paint)
        paint.alpha = 255

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = Color.WHITE
        paint.alpha = 200
        val margin = dp(14f)
        canvas.drawRect(margin, margin, screenW - margin, screenH - margin, paint)
        canvas.drawLine(margin, screenH / 2f, screenW - margin, screenH / 2f, paint)
        canvas.drawCircle(screenW / 2f, screenH / 2f, dp(46f), paint)
        val boxW = screenW * 0.5f
        canvas.drawRect((screenW - boxW) / 2f, margin, (screenW + boxW) / 2f, margin + dp(60f), paint)
        canvas.drawRect((screenW - boxW) / 2f, screenH - margin - dp(60f), (screenW + boxW) / 2f, screenH - margin, paint)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun drawBasketballCourtBackground(canvas: Canvas) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, screenH.toFloat(),
            Color.parseColor("#C87F3E"), Color.parseColor("#A0602A"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)
        paint.shader = null

        // plank lines for a wood-court feel
        paint.color = Color.BLACK
        paint.alpha = 18
        paint.strokeWidth = dp(1f)
        var x = 0f
        while (x < screenW) { canvas.drawLine(x, 0f, x, screenH.toFloat(), paint); x += dp(18f) }
        paint.alpha = 255

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = Color.WHITE
        paint.alpha = 210
        val margin = dp(14f)
        canvas.drawRect(margin, margin, screenW - margin, screenH - margin, paint)
        canvas.drawLine(margin, screenH / 2f, screenW - margin, screenH / 2f, paint)
        canvas.drawCircle(screenW / 2f, screenH / 2f, dp(40f), paint)
        val keyW = screenW * 0.4f
        canvas.drawRect((screenW - keyW) / 2f, margin, (screenW + keyW) / 2f, margin + dp(90f), paint)
        canvas.drawRect((screenW - keyW) / 2f, screenH - margin - dp(90f), (screenW + keyW) / 2f, screenH - margin, paint)
        canvas.drawArc(RectF(screenW / 2f - dp(70f), margin - dp(70f), screenW / 2f + dp(70f), margin + dp(70f)), 0f, 180f, false, paint)
        canvas.drawArc(RectF(screenW / 2f - dp(70f), screenH - margin - dp(70f), screenW / 2f + dp(70f), screenH - margin + dp(70f)), 180f, 180f, false, paint)
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun drawHockeyRinkBackground(canvas: Canvas) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, screenH.toFloat(),
            Color.parseColor("#E1F5FE"), Color.parseColor("#B3E5FC"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW.toFloat(), screenH.toFloat(), paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.parseColor("#C62828")
        val margin = dp(14f)
        canvas.drawRoundRect(RectF(margin, margin, screenW - margin, screenH - margin), dp(30f), dp(30f), paint)

        paint.strokeWidth = dp(4f)
        canvas.drawLine(margin, screenH / 2f, screenW - margin, screenH / 2f, paint)

        paint.color = Color.parseColor("#1565C0")
        canvas.drawLine(margin, screenH * 0.28f, screenW - margin, screenH * 0.28f, paint)
        canvas.drawLine(margin, screenH * 0.72f, screenW - margin, screenH * 0.72f, paint)

        paint.color = Color.parseColor("#C62828")
        paint.strokeWidth = dp(2f)
        canvas.drawCircle(screenW / 2f, screenH / 2f, dp(36f), paint)
        canvas.drawCircle(screenW * 0.28f, screenH * 0.18f, dp(26f), paint)
        canvas.drawCircle(screenW * 0.72f, screenH * 0.18f, dp(26f), paint)
        canvas.drawCircle(screenW * 0.28f, screenH * 0.82f, dp(26f), paint)
        canvas.drawCircle(screenW * 0.72f, screenH * 0.82f, dp(26f), paint)
        paint.style = Paint.Style.FILL
    }

    // --- HUD ---

    private fun exitButtonRect(): RectF {
        val d = dp(36f)
        val cx = screenW / 2f
        return RectF(cx - d / 2f, dp(6f), cx + d / 2f, dp(6f) + d)
    }

    private fun drawExitButton(canvas: Canvas) {
        val r = exitButtonRect()
        val cx = r.centerX()
        val cy = r.centerY()
        val half = r.width() / 2f

        paint.color = Color.parseColor("#76FF03") // acid green
        canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.parseColor("#33691E")
        canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.WHITE
        paint.strokeWidth = dp(3f)
        val armLen = half * 0.45f
        canvas.drawLine(cx - armLen, cy - armLen, cx + armLen, cy + armLen, paint)
        canvas.drawLine(cx + armLen, cy - armLen, cx - armLen, cy + armLen, paint)
    }

    private fun drawTopBar(canvas: Canvas) {
        paint.color = Color.BLACK
        paint.alpha = 90
        canvas.drawRect(0f, 0f, screenW.toFloat(), dp(76f), paint)
        paint.alpha = 255

        drawExitButton(canvas)

        // score is the primary stat: bigger + outlined so it stands out
        val scorePaint = Paint(hudPaint).apply { textAlign = Paint.Align.LEFT; color = colorGold; textSize = dp(30f) }
        drawOutlinedText(canvas, "Score: $score", dp(16f), dp(32f), scorePaint)

        hudPaint.textAlign = Paint.Align.RIGHT
        hudPaint.color = colorLeafGreen
        canvas.drawText("Lives: $lives", screenW - dp(16f), dp(28f), hudPaint)
        hudPaint.textAlign = Paint.Align.LEFT
        hudPaint.color = Color.WHITE

        val gemsPaint = Paint(hudPaint).apply { textAlign = Paint.Align.LEFT; color = colorGemPurple; textSize = dp(20f) }
        canvas.drawText("Gems: $gems", dp(16f), dp(58f), gemsPaint)

        val levelLabel = if (levelIndex < totalLevels) {
            "Level ${levelIndex + 1}/$totalLevels  ·  ${currentRows()} rows"
        } else {
            "Level ${levelIndex + 1} (endless)  ·  ${currentRows()} rows"
        }
        canvas.drawText(
            levelLabel,
            screenW / 2f, dp(58f), levelPaint
        )

    }

    private fun drawGemsBadge(canvas: Canvas) {
        val text = "Gems: $gems"
        val p = Paint(hudPaint).apply { textAlign = Paint.Align.RIGHT; color = colorGemPurple; textSize = dp(20f) }
        val tw = p.measureText(text)
        paint.color = Color.BLACK
        paint.alpha = 90
        canvas.drawRoundRect(RectF(screenW - tw - dp(28f), dp(14f), screenW - dp(8f), dp(40f)), dp(10f), dp(10f), paint)
        paint.alpha = 255
        canvas.drawText(text, screenW - dp(16f), dp(34f), p)
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
        val bw = screenW * 0.74f
        val bh = dp(46f)
        val gap = dp(10f)
        val left = (screenW - bw) / 2f
        val startY = screenH * 0.27f
        return (0 until 5).map { i ->
            val top = startY + i * (bh + gap)
            RectF(left, top, left + bw, top + bh)
        }
    }

    private fun shopCardRects(): List<RectF> {
        val w = screenW * 0.8f
        val h = dp(90f)
        val gap = dp(16f)
        val left = (screenW - w) / 2f
        val startY = screenH * 0.2f
        return (0 until 4).map { i ->
            val top = startY + i * (h + gap)
            RectF(left, top, left + w, top + h)
        }
    }

    private fun backButtonRect(): RectF {
        val w = dp(130f)
        val h = dp(50f)
        return RectF(dp(20f), screenH - dp(30f) - h, dp(20f) + w, screenH - dp(30f))
    }

    private fun optionsHeaderBottom(): Float = screenH * 0.08f + dp(50f)

    private fun optionsTabTop(): Float = optionsHeaderBottom() + dp(10f)

    private val optionsTabH = dp(34f)

    private fun optionsNameY(): Float = optionsTabTop() + optionsTabH + dp(22f)

    private fun optionsGridTop(): Float = optionsNameY() + dp(12f)

    private fun optionsTabRects(): List<RectF> {
        val w = dp(140f)
        val h = optionsTabH
        val gap = dp(10f)
        val totalW = w * 2 + gap
        val left = (screenW - totalW) / 2f
        val top = optionsTabTop()
        return listOf(
            RectF(left, top, left + w, top + h),
            RectF(left + w + gap, top, left + w + gap + w, top + h)
        )
    }

    private fun optionsGridRects(): List<RectF> {
        val cols = 4
        val rows = 5
        val totalW = screenW * 0.86f
        val gapX = dp(8f)
        val gapY = dp(10f)
        val cellW = (totalW - gapX * (cols - 1)) / cols
        val cellH = cellW * 0.82f
        val left = (screenW - totalW) / 2f
        val top = optionsGridTop()
        val list = mutableListOf<RectF>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val l = left + col * (cellW + gapX)
                val t = top + row * (cellH + gapY)
                list.add(RectF(l, t, l + cellW, t + cellH))
            }
        }
        return list
    }

    private fun drawMenu(canvas: Canvas) {
        drawBackground(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.14f, screenW.toFloat(), screenH * 0.14f + dp(56f), paint)
        paint.alpha = 255
        drawOutlinedText(canvas, "SUPER PONG", screenW / 2f, screenH * 0.14f + dp(40f), titlePaint)

        val rects = menuButtonRects()
        val labels = listOf("New Game Normal", "Top Score", "Options", "Shop", "My Account")
        val buttonColors = listOf(
            colorSkyBlue, colorGold, colorLeafGreen,
            Color.parseColor("#AB47BC"), Color.parseColor("#546E7A")
        )
        val labelPaint = Paint(buttonTextPaint).apply { textSize = dp(20f) }
        for (i in rects.indices) {
            drawBeveledButton(canvas, rects[i], buttonColors[i])
            drawOutlinedText(canvas, labels[i], rects[i].centerX(), rects[i].centerY() + dp(6f), labelPaint)
        }

        drawSpaceCharacter(canvas, screenW * 0.5f, screenH * 0.80f, 1.4f)

        drawUserBadge(canvas)
        drawGemsBadge(canvas)
        drawFooter(canvas)
    }

    private fun drawSpaceCharacter(canvas: Canvas, cx: Float, cy: Float, scale: Float) {
        fun s(v: Float) = dp(v * scale)

        // legs
        paint.color = Color.parseColor("#78909C")
        canvas.drawRoundRect(RectF(cx - s(22f), cy + s(28f), cx - s(8f), cy + s(50f)), s(6f), s(6f), paint)
        canvas.drawRoundRect(RectF(cx + s(8f), cy + s(28f), cx + s(22f), cy + s(50f)), s(6f), s(6f), paint)

        // body (spacesuit)
        paint.color = Color.parseColor("#ECEFF1")
        canvas.drawRoundRect(RectF(cx - s(26f), cy - s(10f), cx + s(26f), cy + s(34f)), s(14f), s(14f), paint)

        // chest panel
        paint.color = colorSkyBlue
        canvas.drawRoundRect(RectF(cx - s(10f), cy + s(2f), cx + s(10f), cy + s(18f)), s(4f), s(4f), paint)

        // arms
        paint.color = Color.parseColor("#ECEFF1")
        canvas.drawRoundRect(RectF(cx - s(46f), cy - s(2f), cx - s(18f), cy + s(12f)), s(8f), s(8f), paint)
        canvas.drawRoundRect(RectF(cx + s(18f), cy - s(2f), cx + s(46f), cy + s(12f)), s(8f), s(8f), paint)

        // ray gun
        paint.color = Color.parseColor("#546E7A")
        canvas.drawRoundRect(RectF(cx + s(40f), cy - s(6f), cx + s(64f), cy + s(4f)), s(3f), s(3f), paint)
        paint.color = Color.parseColor("#EF5350")
        canvas.drawRect(cx + s(60f), cy - s(4f), cx + s(68f), cy + s(2f), paint)

        // helmet
        paint.color = Color.parseColor("#ECEFF1")
        canvas.drawCircle(cx, cy - s(22f), s(24f), paint)
        paint.color = Color.parseColor("#4FC3F7")
        canvas.drawCircle(cx + s(4f), cy - s(22f), s(17f), paint)
        paint.color = Color.WHITE
        paint.alpha = 150
        canvas.drawCircle(cx - s(2f), cy - s(30f), s(5f), paint)
        paint.alpha = 255

        // antenna
        paint.color = colorGold
        canvas.drawRect(cx - s(1.5f), cy - s(46f), cx + s(1.5f), cy - s(38f), paint)
        canvas.drawCircle(cx, cy - s(48f), s(4f), paint)
    }

    private fun drawUserBadge(canvas: Canvas) {
        val text = if (isRegistered) username else "Guest"
        val p = Paint(hudPaint).apply { textAlign = Paint.Align.LEFT; color = Color.WHITE; textSize = dp(20f) }
        val tw = p.measureText(text)
        paint.color = Color.BLACK
        paint.alpha = 90
        canvas.drawRoundRect(RectF(dp(8f), dp(14f), dp(24f) + tw, dp(40f)), dp(10f), dp(10f), paint)
        paint.alpha = 255
        canvas.drawText(text, dp(16f), dp(34f), p)
    }

    private fun drawFooter(canvas: Canvas) {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val p = Paint(levelPaint).apply { textSize = dp(16f); alpha = 190 }
        canvas.drawText("© $year IstoreLab", screenW / 2f, screenH - dp(16f), p)
    }

    private fun drawShopScreen(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B1A33"))

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.08f, screenW.toFloat(), screenH * 0.08f + dp(50f), paint)
        paint.alpha = 255
        drawOutlinedText(
            canvas, "SHOP", screenW / 2f, screenH * 0.08f + dp(36f),
            Paint(titlePaint).apply { textSize = dp(28f) }
        )
        val subPaint = Paint(levelPaint).apply { textSize = dp(13f) }
        canvas.drawText("Full theme packs: field, ball & players", screenW / 2f, screenH * 0.08f + dp(64f), subPaint)

        val rects = shopCardRects()
        val names = listOf("Soccer", "Basketball", "Hockey", "Space")
        val owned = booleanArrayOf(themeSoccerOwned, themeBasketballOwned, themeHockeyOwned, themeSpaceOwned)

        for (i in rects.indices) {
            val r = rects[i]
            canvas.save()
            canvas.clipRect(r)
            drawThemeBackground(canvas, ThemePack.values()[i + 1])
            canvas.restore()

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = if (owned[i]) Color.parseColor("#76FF03") else Color.WHITE
            paint.alpha = if (owned[i]) 255 else 110
            canvas.drawRoundRect(r, dp(10f), dp(10f), paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 255

            drawThemeBall(canvas, r.left + dp(28f), r.centerY(), dp(16f), ThemePack.values()[i + 1])

            paint.color = Color.BLACK
            paint.alpha = 130
            canvas.drawRect(r.left + dp(48f), r.top, r.right, r.bottom, paint)
            paint.alpha = 255
            drawOutlinedText(canvas, names[i], (r.left + dp(48f) + r.right) / 2f, r.top + dp(30f), buttonTextPaint)

            val priceLabel = if (owned[i]) {
                "Owned · tap to play"
            } else {
                BillingManager.priceFor(shopThemeProductIds[i]) ?: "$shopDemoGemCost gems (demo)"
            }
            val pricePaint = Paint(buttonTextPaint).apply { textSize = dp(16f) }
            drawOutlinedText(canvas, priceLabel, (r.left + dp(48f) + r.right) / 2f, r.bottom - dp(14f), pricePaint)
        }

        if (storeMessageTimer > 0f) {
            val msgPaint = Paint(buttonTextPaint).apply { textSize = dp(18f); color = colorGold }
            drawOutlinedText(canvas, storeMessageText, screenW / 2f, screenH * 0.9f, msgPaint)
        }

        drawGemsBadge(canvas)
        drawBackButton(canvas)
    }

    private fun drawTopScoreScreen(canvas: Canvas) {
        drawBackground(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.14f, screenW.toFloat(), screenH * 0.5f, paint)
        paint.alpha = 255

        drawOutlinedText(canvas, "TOP SCORE", screenW / 2f, screenH * 0.22f, titlePaint)
        val scorePaint = Paint(bigPaint).apply { textSize = dp(46f); color = colorGold }
        drawOutlinedText(canvas, "$topScore", screenW / 2f, screenH * 0.34f, scorePaint)
        canvas.drawText("(best score on this device)", screenW / 2f, screenH * 0.34f + dp(34f), levelPaint)

        drawGemsBadge(canvas)
        drawBackButton(canvas)
    }

    private fun drawOptionsScreen(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B1A33"))
        drawGemsBadge(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.08f, screenW.toFloat(), screenH * 0.08f + dp(50f), paint)
        paint.alpha = 255
        drawOutlinedText(
            canvas, "OPTIONS", screenW / 2f, screenH * 0.08f + dp(36f),
            Paint(titlePaint).apply { textSize = dp(26f) }
        )

        val tabRects = optionsTabRects()
        val tabLabels = listOf("Background", "Ball")
        for (i in tabRects.indices) {
            val active = optionsTab == i
            drawBeveledButton(canvas, tabRects[i], if (active) colorLeafGreen else Color.parseColor("#546E7A"))
            drawOutlinedText(
                canvas, tabLabels[i], tabRects[i].centerX(), tabRects[i].centerY() + dp(6f),
                Paint(buttonTextPaint).apply { textSize = dp(16f) }
            )
        }

        val selectedName = if (optionsTab == 0) {
            freeBackgrounds[bgIndex.coerceIn(0, freeBackgrounds.size - 1)].name
        } else {
            freeBalls[ballIndex.coerceIn(0, freeBalls.size - 1)].name
        }
        val namePaint = Paint(levelPaint).apply { textSize = dp(15f); color = colorGold }
        canvas.drawText(selectedName, screenW / 2f, optionsNameY(), namePaint)

        val cellRects = optionsGridRects()
        if (optionsTab == 0) {
            for (i in cellRects.indices) {
                if (i >= freeBackgrounds.size) break
                val r = cellRects[i]
                canvas.save()
                canvas.clipRect(r)
                drawFreeBackground(canvas, freeBackgrounds[i], r)
                canvas.restore()

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                val selected = bgIndex == i
                paint.color = if (selected) Color.parseColor("#FFEB3B") else Color.WHITE
                paint.alpha = if (selected) 255 else 80
                canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
                paint.style = Paint.Style.FILL
                paint.alpha = 255
            }
        } else {
            for (i in cellRects.indices) {
                if (i >= freeBalls.size) break
                val r = cellRects[i]
                paint.color = Color.parseColor("#13294F")
                canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
                drawFreeBallStyle(canvas, r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.36f, freeBalls[i])

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                val selected = ballIndex == i
                paint.color = if (selected) Color.parseColor("#FFEB3B") else Color.WHITE
                paint.alpha = if (selected) 255 else 80
                canvas.drawRoundRect(r, dp(6f), dp(6f), paint)
                paint.style = Paint.Style.FILL
                paint.alpha = 255
            }
        }

        drawBackButton(canvas)
    }

    private fun registerButtonRect(): RectF {
        val w = screenW * 0.7f
        val h = dp(52f)
        return RectF((screenW - w) / 2f, screenH * 0.5f, (screenW + w) / 2f, screenH * 0.5f + h)
    }

    private fun editNameButtonRect(): RectF {
        val w = dp(110f)
        val h = dp(34f)
        return RectF((screenW - w) / 2f, screenH * 0.30f, (screenW + w) / 2f, screenH * 0.30f + h)
    }

    private fun avatarPresetRects(): List<RectF> {
        val count = 5
        val d = dp(40f)
        val gap = dp(10f)
        val totalW = count * d + (count - 1) * gap
        val left = (screenW - totalW) / 2f
        val y = screenH * 0.66f
        return (0 until count).map { i ->
            val l = left + i * (d + gap)
            RectF(l, y, l + d, y + d)
        }
    }

    private fun themeIconRects(): List<RectF> {
        val count = 4
        val d = dp(46f)
        val gap = dp(14f)
        val totalW = count * d + (count - 1) * gap
        val left = (screenW - totalW) / 2f
        val y = screenH * 0.5f
        return (0 until count).map { i ->
            val l = left + i * (d + gap)
            RectF(l, y, l + d, y + d)
        }
    }

    private fun uploadPhotoButtonRect(): RectF {
        val w = dp(170f)
        val h = dp(42f)
        return RectF((screenW - w) / 2f, screenH * 0.73f, (screenW + w) / 2f, screenH * 0.73f + h)
    }

    private fun drawAccountScreen(canvas: Canvas) {
        drawBackground(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.08f, screenW.toFloat(), screenH * 0.08f + dp(50f), paint)
        paint.alpha = 255
        drawOutlinedText(
            canvas, "MY ACCOUNT", screenW / 2f, screenH * 0.08f + dp(36f),
            Paint(titlePaint).apply { textSize = dp(28f) }
        )

        if (!isRegistered) {
            val infoPaint = Paint(buttonTextPaint).apply { textSize = dp(18f) }
            canvas.drawText("Register to save your gems, scores", screenW / 2f, screenH * 0.36f, infoPaint)
            canvas.drawText("and purchased accessories.", screenW / 2f, screenH * 0.36f + dp(24f), infoPaint)

            drawBeveledButton(canvas, registerButtonRect(), colorSkyBlue)
            drawOutlinedText(
                canvas, "Register (Demo Account)",
                registerButtonRect().centerX(), registerButtonRect().centerY() + dp(6f), buttonTextPaint
            )
        } else {
            drawAvatar(canvas, screenW / 2f, screenH * 0.20f, dp(42f))
            drawOutlinedText(canvas, username, screenW / 2f, screenH * 0.20f + dp(64f), Paint(bigPaint).apply { textSize = dp(24f) })

            drawBeveledButton(canvas, editNameButtonRect(), Color.parseColor("#546E7A"))
            drawOutlinedText(
                canvas, "Edit Name", editNameButtonRect().centerX(), editNameButtonRect().centerY() + dp(5f),
                Paint(buttonTextPaint).apply { textSize = dp(16f) }
            )

            val infoPaint = Paint(buttonTextPaint).apply { textAlign = Paint.Align.LEFT; textSize = dp(18f) }
            val left = screenW * 0.14f
            var y = screenH * 0.40f
            val lineGap = dp(30f)

            canvas.drawText("Gems: $gems", left, y, infoPaint); y += lineGap
            canvas.drawText("Top score: $topScore", left, y, infoPaint)

            val themeLabelPaint = Paint(levelPaint).apply { textSize = dp(16f); color = colorGold }
            canvas.drawText("Shop themes:", screenW / 2f, screenH * 0.465f, themeLabelPaint)

            val themeRects = themeIconRects()
            val themeNames = listOf("Soccer", "Basketball", "Hockey", "Space")
            val themeOwned = booleanArrayOf(themeSoccerOwned, themeBasketballOwned, themeHockeyOwned, themeSpaceOwned)
            for (i in themeRects.indices) {
                val r = themeRects[i]
                val pack = ThemePack.values()[i + 1]
                paint.color = Color.parseColor("#13294F")
                canvas.drawRoundRect(r, dp(8f), dp(8f), paint)
                if (themeOwned[i]) {
                    drawThemeBall(canvas, r.centerX(), r.centerY(), r.width() * 0.34f, pack)
                } else {
                    paint.color = Color.parseColor("#455A64")
                    canvas.drawCircle(r.centerX(), r.centerY(), r.width() * 0.3f, paint)
                    paint.color = Color.parseColor("#CFD8DC")
                    canvas.drawRoundRect(
                        RectF(r.centerX() - dp(6f), r.centerY() - dp(1f), r.centerX() + dp(6f), r.centerY() + dp(8f)),
                        dp(2f), dp(2f), paint
                    )
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2f)
                    canvas.drawArc(
                        RectF(r.centerX() - dp(5f), r.centerY() - dp(9f), r.centerX() + dp(5f), r.centerY() + dp(1f)),
                        180f, 180f, false, paint
                    )
                    paint.style = Paint.Style.FILL
                }

                val active = themeOwned[i] && activeThemePack == pack
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                paint.color = if (active) Color.parseColor("#FFEB3B") else Color.WHITE
                paint.alpha = if (active) 255 else if (themeOwned[i]) 90 else 40
                canvas.drawRoundRect(r, dp(8f), dp(8f), paint)
                paint.style = Paint.Style.FILL
                paint.alpha = 255

                val themeNamePaint = Paint(levelPaint).apply { textSize = dp(10f) }
                canvas.drawText(themeNames[i], r.centerX(), r.bottom + dp(13f), themeNamePaint)
            }

            val choosePaint = Paint(levelPaint).apply { textSize = dp(16f) }
            canvas.drawText("Choose avatar:", screenW / 2f, screenH * 0.63f, choosePaint)

            val rects = avatarPresetRects()
            for (i in rects.indices) {
                val r = rects[i]
                drawAvatarPreset(canvas, i, r.centerX(), r.centerY(), r.width() / 2f)
                val selected = !avatarIsPhoto && avatarPreset == i
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                paint.color = if (selected) Color.parseColor("#FFEB3B") else Color.WHITE
                paint.alpha = if (selected) 255 else 60
                canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2f + dp(2f), paint)
                paint.style = Paint.Style.FILL
                paint.alpha = 255
            }

            drawBeveledButton(canvas, uploadPhotoButtonRect(), colorSkyBlue)
            drawOutlinedText(
                canvas, "Upload Photo", uploadPhotoButtonRect().centerX(), uploadPhotoButtonRect().centerY() + dp(5f),
                Paint(buttonTextPaint).apply { textSize = dp(17f) }
            )
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
        if (activeCrateTier >= 0) {
            if (event.action == MotionEvent.ACTION_DOWN) pendingCrateOpen = true
            return true
        }
        when (state) {
            GameState.MENU -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val rects = menuButtonRects()
                    when {
                        rects[0].contains(event.x, event.y) -> {
                            activeThemePack = ThemePack.NONE
                            pendingAction = PendingAction.RESTART_GAME
                        }
                        rects[1].contains(event.x, event.y) -> {
                            state = GameState.TOP_SCORE
                            (context as? Activity)?.let { LeaderboardManager.tryOpenLeaderboardUi(it) }
                        }
                        rects[2].contains(event.x, event.y) -> state = GameState.OPTIONS
                        rects[3].contains(event.x, event.y) -> state = GameState.SHOP
                        rects[4].contains(event.x, event.y) -> state = GameState.ACCOUNT
                    }
                }
            }
            GameState.TOP_SCORE -> {
                if (event.action == MotionEvent.ACTION_DOWN && backButtonRect().contains(event.x, event.y)) {
                    state = GameState.MENU
                }
            }
            GameState.OPTIONS -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (backButtonRect().contains(event.x, event.y)) {
                        state = GameState.MENU
                    } else {
                        val tabRects = optionsTabRects()
                        if (tabRects[0].contains(event.x, event.y)) {
                            optionsTab = 0
                        } else if (tabRects[1].contains(event.x, event.y)) {
                            optionsTab = 1
                        } else {
                            val cellRects = optionsGridRects()
                            for (i in cellRects.indices) {
                                if (cellRects[i].contains(event.x, event.y)) {
                                    if (optionsTab == 0 && i < freeBackgrounds.size) selectBackground(i)
                                    if (optionsTab == 1 && i < freeBalls.size) selectBallSkin(i)
                                }
                            }
                        }
                    }
                }
            }
            GameState.SHOP -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (backButtonRect().contains(event.x, event.y)) {
                        state = GameState.MENU
                    } else {
                        val rects = shopCardRects()
                        for (i in rects.indices) {
                            if (rects[i].contains(event.x, event.y)) {
                                pendingShopPurchase = i
                            }
                        }
                    }
                }
            }
            GameState.ACCOUNT -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (backButtonRect().contains(event.x, event.y)) {
                        state = GameState.MENU
                    } else if (!isRegistered && registerButtonRect().contains(event.x, event.y)) {
                        pendingRegister = true
                    } else if (isRegistered && editNameButtonRect().contains(event.x, event.y)) {
                        (context as? MainActivity)?.showRenameDialog(username)
                    } else if (isRegistered && uploadPhotoButtonRect().contains(event.x, event.y)) {
                        (context as? MainActivity)?.pickAvatarPhoto()
                    } else if (isRegistered && themeIconRects().any { it.contains(event.x, event.y) }) {
                        val themeRects = themeIconRects()
                        val i = themeRects.indexOfFirst { it.contains(event.x, event.y) }
                        val owned = booleanArrayOf(themeSoccerOwned, themeBasketballOwned, themeHockeyOwned, themeSpaceOwned)
                        if (owned[i]) {
                            activeThemePack = ThemePack.values()[i + 1]
                            pendingAction = PendingAction.RESTART_GAME
                        } else {
                            state = GameState.SHOP
                        }
                    } else if (isRegistered) {
                        val rects = avatarPresetRects()
                        for (i in rects.indices) {
                            if (rects[i].contains(event.x, event.y)) {
                                pendingAvatarPreset = i
                            }
                        }
                    }
                }
            }
            GameState.PLAYING -> {
                if (event.action == MotionEvent.ACTION_DOWN && exitButtonRect().contains(event.x, event.y)) {
                    state = GameState.MENU
                } else {
                    paddleX = event.x.coerceIn(paddleW / 2f, screenW - paddleW / 2f)
                }
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
