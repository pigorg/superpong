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
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.SoundPool
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

private enum class ObstacleType { ROCKET, PIG, EAGLE, GOLDFISH }
private data class Obstacle(var x: Float, var y: Float, val vx: Float, val type: ObstacleType, var bounceCooldown: Float = 0f)

private data class Projectile(var x: Float, var y: Float, val vy: Float, val isBomb: Boolean = false)

private data class DiamondDecor(val x: Float, val y: Float, val r: Float, val color: Int)

private enum class GameState { MENU, TOP_SCORE, BACKGROUND_SELECT, BUY_GEMS, ACCOUNT, PLAYING, LEVEL_CLEARED, GAME_OVER }
private enum class PendingAction { NONE, NEXT_LEVEL, RESTART_GAME }
private enum class BgTheme { GRID, SPACE, SKY, DIAMONDS }

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private var thread: Thread? = null
    @Volatile private var running = false

    private var screenW = 0
    private var screenH = 0

    private val prefs = context.getSharedPreferences("superpong_prefs", Context.MODE_PRIVATE)
    private var topScore = prefs.getInt("top_score", 0)
    private var gems = prefs.getInt("gems", 0)
    @Volatile private var pendingGemGrant = 0
    @Volatile private var bgTheme = BgTheme.values()[prefs.getInt("bg_theme", BgTheme.SPACE.ordinal)]
    @Volatile private var hardMode = false

    private val obstacles = mutableListOf<Obstacle>()
    private var obstacleSpawnTimer = 4f
    private val obstacleHalfW = dp(22f)
    private val obstacleHalfH = dp(14f)
    private val gemBlockColor = Color.parseColor("#8E24AA")

    // --- store power-ups: 0=cannon, 1=double cannon (upgrades cannon), 2=bombs ---
    private var cannonLevel = prefs.getInt("cannon_level", 0)
    private var bombsUnlocked = prefs.getBoolean("bombs_unlocked", false)
    private var cannonFireTimer = 0f
    private val cannonInterval = 1.4f
    private var bombCooldown = 0f
    private val bombCooldownDuration = 5f
    private val projectiles = mutableListOf<Projectile>()
    @Volatile private var pendingStorePurchase = -1
    @Volatile private var pendingBombLaunch = false
    @Volatile private var pendingRemoveAccessory = -1
    private var storeMessageText = ""
    private var storeMessageTimer = 0f

    private var soundPool: SoundPool? = null
    private var laserSoundId = 0
    private var bombLaunchSoundId = 0
    private var explosionSoundId = 0

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

    init {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
            soundPool = pool
            laserSoundId = pool.load(context, R.raw.laser, 1)
            bombLaunchSoundId = pool.load(context, R.raw.bomb_launch, 1)
            explosionSoundId = pool.load(context, R.raw.explosion, 1)
        } catch (e: Exception) {
            soundPool = null
        }
    }

    private fun playSound(soundId: Int) {
        soundPool?.play(soundId, 1f, 1f, 1, 0, 1f)
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

    private fun drawAvatarPreset(canvas: Canvas, index: Int, cx: Float, cy: Float, radius: Float) {
        paint.color = avatarColors[index % avatarColors.size]
        canvas.drawCircle(cx, cy, radius, paint)
        when (index % 5) {
            0 -> drawRobotFace(canvas, cx, cy, radius)
            1 -> drawCatFace(canvas, cx, cy, radius)
            2 -> drawAlienFace(canvas, cx, cy, radius)
            3 -> drawGhostFace(canvas, cx, cy, radius)
            4 -> drawAstronautFace(canvas, cx, cy, radius)
        }
    }

    private fun drawBlush(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = Color.parseColor("#FF8A80")
        paint.alpha = 120
        canvas.drawCircle(cx - r * 0.34f, cy + r * 0.12f, r * 0.12f, paint)
        canvas.drawCircle(cx + r * 0.34f, cy + r * 0.12f, r * 0.12f, paint)
        paint.alpha = 255
    }

    private fun drawSmile(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int = Color.parseColor("#3E2723")) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = color
        canvas.drawArc(RectF(cx - r * 0.22f, cy, cx + r * 0.22f, cy + r * 0.3f), 15f, 150f, false, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawEyeShine(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = Color.WHITE
        paint.alpha = 220
        canvas.drawCircle(cx - r * 0.06f, cy - r * 0.06f, r * 0.35f, paint)
        paint.alpha = 255
    }

    private fun drawRobotFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = Color.parseColor("#37474F")
        canvas.drawRect(RectF(cx - r * 0.35f, cy - r * 0.15f, cx - r * 0.1f, cy + r * 0.1f), paint)
        canvas.drawRect(RectF(cx + r * 0.1f, cy - r * 0.15f, cx + r * 0.35f, cy + r * 0.1f), paint)
        paint.color = Color.parseColor("#4FC3F7")
        drawEyeShine(canvas, cx - r * 0.22f, cy - r * 0.02f, r * 0.13f)
        drawEyeShine(canvas, cx + r * 0.22f, cy - r * 0.02f, r * 0.13f)
        drawSmile(canvas, cx, cy + r * 0.14f, r, Color.parseColor("#4FC3F7"))
        paint.color = Color.parseColor("#FFEE58")
        canvas.drawRect(RectF(cx - dp(1.5f), cy - r * 0.75f, cx + dp(1.5f), cy - r * 0.5f), paint)
        canvas.drawCircle(cx, cy - r * 0.8f, dp(3f), paint)
    }

    private fun drawCatFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = avatarColors[1]
        canvas.drawPath(trianglePath(cx - r * 0.55f, cy - r * 0.35f, cx - r * 0.15f, cy - r * 0.75f, cx - r * 0.1f, cy - r * 0.2f), paint)
        canvas.drawPath(trianglePath(cx + r * 0.55f, cy - r * 0.35f, cx + r * 0.15f, cy - r * 0.75f, cx + r * 0.1f, cy - r * 0.2f), paint)
        paint.color = Color.parseColor("#3E2723")
        canvas.drawCircle(cx - r * 0.22f, cy - r * 0.05f, dp(3.5f), paint)
        canvas.drawCircle(cx + r * 0.22f, cy - r * 0.05f, dp(3.5f), paint)
        drawEyeShine(canvas, cx - r * 0.22f, cy - r * 0.05f, dp(3.5f))
        drawEyeShine(canvas, cx + r * 0.22f, cy - r * 0.05f, dp(3.5f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = Color.parseColor("#5D4037")
        for (dy in intArrayOf(-3, 0, 3)) {
            canvas.drawLine(cx - r * 0.35f, cy + r * 0.1f + dp(dy.toFloat()), cx - r * 0.6f, cy + r * 0.05f + dp(dy.toFloat()), paint)
            canvas.drawLine(cx + r * 0.35f, cy + r * 0.1f + dp(dy.toFloat()), cx + r * 0.6f, cy + r * 0.05f + dp(dy.toFloat()), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#EC407A")
        canvas.drawPath(trianglePath(cx, cy + r * 0.12f, cx - dp(3f), cy + r * 0.04f, cx + dp(3f), cy + r * 0.04f), paint)
        drawBlush(canvas, cx, cy, r)
        drawSmile(canvas, cx, cy + r * 0.1f, r)
    }

    private fun drawAlienFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = Color.parseColor("#1B5E20")
        canvas.drawOval(RectF(cx - r * 0.32f, cy - r * 0.25f, cx - r * 0.05f, cy + r * 0.12f), paint)
        canvas.drawOval(RectF(cx + r * 0.05f, cy - r * 0.25f, cx + r * 0.32f, cy + r * 0.12f), paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx - r * 0.15f, cy - r * 0.1f, dp(3f), paint)
        canvas.drawCircle(cx + r * 0.15f, cy - r * 0.1f, dp(3f), paint)
        paint.color = Color.parseColor("#1B5E20")
        drawEyeShine(canvas, cx - r * 0.15f, cy - r * 0.1f, dp(2f))
        drawEyeShine(canvas, cx + r * 0.15f, cy - r * 0.1f, dp(2f))
        drawSmile(canvas, cx, cy + r * 0.18f, r)
    }

    private fun drawGhostFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = Color.parseColor("#212121")
        canvas.drawCircle(cx - r * 0.2f, cy - r * 0.05f, dp(4f), paint)
        canvas.drawCircle(cx + r * 0.2f, cy - r * 0.05f, dp(4f), paint)
        drawEyeShine(canvas, cx - r * 0.2f, cy - r * 0.05f, dp(4f))
        drawEyeShine(canvas, cx + r * 0.2f, cy - r * 0.05f, dp(4f))
        drawBlush(canvas, cx, cy, r)
        paint.color = Color.parseColor("#EC407A")
        canvas.drawCircle(cx, cy + r * 0.22f, dp(3.5f), paint)
    }

    private fun drawAstronautFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = Color.parseColor("#B3E5FC")
        canvas.drawCircle(cx, cy, r * 0.65f, paint)
        paint.color = Color.WHITE
        paint.alpha = 150
        canvas.drawCircle(cx - r * 0.2f, cy - r * 0.25f, r * 0.15f, paint)
        paint.alpha = 255
        paint.color = Color.parseColor("#37474F")
        canvas.drawCircle(cx - r * 0.12f, cy + r * 0.05f, dp(3f), paint)
        canvas.drawCircle(cx + r * 0.12f, cy + r * 0.05f, dp(3f), paint)
        drawEyeShine(canvas, cx - r * 0.12f, cy + r * 0.05f, dp(2f))
        drawEyeShine(canvas, cx + r * 0.12f, cy + r * 0.05f, dp(2f))
        drawSmile(canvas, cx, cy + r * 0.2f, r)
        drawBlush(canvas, cx, cy + r * 0.1f, r * 0.8f)
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
        obstacles.clear()
        obstacleSpawnTimer = 1.5f + (Math.random() * 1.5f).toFloat()
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

    private fun openCrate(tier: Int) {
        when (tier) {
            0 -> setCannonLevel(max(cannonLevel, 1))
            1 -> setCannonLevel(max(cannonLevel, 2))
            2 -> {
                bombsUnlocked = true
                prefs.edit().putBoolean("bombs_unlocked", true).apply()
            }
        }
        spawnFireworks()
    }

    fun grantGems(amount: Int) {
        pendingGemGrant += amount
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

    private fun spawnObstacle() {
        val fromLeft = Math.random() < 0.5
        val type = ObstacleType.values()[(Math.random() * ObstacleType.values().size).toInt()]
        val topBound = dp(94f)
        val bottomBound = paddleY - dp(20f)
        val y = if (bottomBound > topBound) {
            topBound + (Math.random().toFloat() * (bottomBound - topBound))
        } else topBound
        val speed = dp(90f) + (Math.random() * dp(60f)).toFloat()
        val x = if (fromLeft) -obstacleHalfW else screenW + obstacleHalfW
        val vx = if (fromLeft) speed else -speed
        obstacles.add(Obstacle(x, y, vx, type))
    }

    private fun updateObstacles(dt: Float) {
        val it = obstacles.iterator()
        while (it.hasNext()) {
            val o = it.next()
            o.x += o.vx * dt
            if (o.bounceCooldown > 0f) o.bounceCooldown -= dt
            if ((o.vx > 0 && o.x - obstacleHalfW > screenW) || (o.vx < 0 && o.x + obstacleHalfW < 0)) {
                it.remove()
            }
        }
    }

    private fun checkObstacleCollisions() {
        for (o in obstacles) {
            if (o.bounceCooldown > 0f) continue
            val left = o.x - obstacleHalfW
            val right = o.x + obstacleHalfW
            val top = o.y - obstacleHalfH
            val bottom = o.y + obstacleHalfH
            if (ballX + ballR > left && ballX - ballR < right && ballY + ballR > top && ballY - ballR < bottom) {
                val overlapX = min(ballX + ballR - left, right - (ballX - ballR))
                val overlapY = min(ballY + ballR - top, bottom - (ballY - ballR))
                if (overlapX < overlapY) ballDx = -ballDx else ballDy = -ballDy
                o.bounceCooldown = 0.3f
            }
        }
    }

    private fun fireCannons() {
        val by = paddleY - dp(6f)
        if (cannonLevel == 1) {
            projectiles.add(Projectile(paddleX, by, -dp(240f)))
        } else if (cannonLevel >= 2) {
            projectiles.add(Projectile(paddleX - paddleW / 2f + dp(8f), by, -dp(240f)))
            projectiles.add(Projectile(paddleX + paddleW / 2f - dp(8f), by, -dp(240f)))
        }
        playSound(laserSoundId)
    }

    private fun launchBomb() {
        projectiles.add(Projectile(paddleX, paddleY - dp(6f), -dp(260f), isBomb = true))
        bombCooldown = bombCooldownDuration
        playSound(bombLaunchSoundId)
    }

    private fun updateProjectiles(dt: Float) {
        val it = projectiles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.y += p.vy * dt
            if (p.y < -dp(20f)) it.remove()
        }
    }

    private fun distanceSq(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return dx * dx + dy * dy
    }

    private fun checkProjectileCollisions() {
        val projIt = projectiles.iterator()
        while (projIt.hasNext()) {
            val p = projIt.next()
            val r = if (p.isBomb) dp(11f) else dp(4f)
            val hit = blocks.firstOrNull { b ->
                p.x + r > b.rect.left && p.x - r < b.rect.right && p.y + r > b.rect.top && p.y - r < b.rect.bottom
            } ?: continue

            val toRemove = if (p.isBomb) {
                blocks.sortedBy { distanceSq(it.rect, hit.rect) }.take(3)
            } else {
                listOf(hit)
            }
            for (b in toRemove) {
                blocks.remove(b)
                score += b.points * (if (hardMode) 2 else 1)
                addGems(if (b.isGemBlock) 100 else 1)
                spawnBurst(b.rect.centerX(), b.rect.centerY(), b.color, 10)
            }
            updateTopScore()
            if (p.isBomb) playSound(explosionSoundId)
            projIt.remove()
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

    private fun handleStorePurchase(index: Int) {
        if (!isRegistered) {
            state = GameState.ACCOUNT
            return
        }
        // Intentionally NOT gated on "already owned": buying again is allowed so the demo
        // flow can be re-tested freely for every tier, as requested.
        val activity = context as? Activity
        val price = BillingManager.priceFor(BillingManager.GEM_PACKS[index].productId)
        if (price != null && activity != null) {
            BillingManager.purchase(activity, BillingManager.GEM_PACKS[index].productId)
            return
        }
        val cost = intArrayOf(100, 200, 300)[index]
        if (gems < cost) {
            storeMessageText = "Not enough gems (need $cost)"
            storeMessageTimer = 1.6f
            return
        }
        addGems(-cost)
        when (index) {
            0 -> setCannonLevel(1)
            1 -> setCannonLevel(2)
            2 -> {
                bombsUnlocked = true
                prefs.edit().putBoolean("bombs_unlocked", true).apply()
            }
        }
        storeMessageText = "Purchased!"
        storeMessageTimer = 1.2f
    }

    private fun setCannonLevel(level: Int) {
        if (level > cannonLevel) cannonLevel = level
        prefs.edit().putInt("cannon_level", cannonLevel).apply()
    }

    private fun removeAccessory(index: Int) {
        when (index) {
            0 -> if (cannonLevel == 1) {
                cannonLevel = 0
                prefs.edit().putInt("cannon_level", 0).apply()
                addGems(100)
            }
            1 -> if (cannonLevel >= 2) {
                cannonLevel = 0
                prefs.edit().putInt("cannon_level", 0).apply()
                addGems(200)
            }
            2 -> if (bombsUnlocked) {
                bombsUnlocked = false
                prefs.edit().putBoolean("bombs_unlocked", false).apply()
                addGems(300)
            }
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

            if (pendingGemGrant != 0) {
                val grant = pendingGemGrant
                pendingGemGrant -= grant
                addGems(grant)
            }

            if (pendingStorePurchase >= 0) {
                val idx = pendingStorePurchase
                pendingStorePurchase = -1
                handleStorePurchase(idx)
            }

            if (pendingBombLaunch) {
                pendingBombLaunch = false
                if (bombsUnlocked && bombCooldown <= 0f) launchBomb()
            }

            if (pendingRemoveAccessory >= 0) {
                val idx = pendingRemoveAccessory
                pendingRemoveAccessory = -1
                removeAccessory(idx)
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

        if (hardMode) {
            obstacleSpawnTimer -= dt
            if (obstacleSpawnTimer <= 0f) {
                spawnObstacle()
                obstacleSpawnTimer = 1.5f + (Math.random() * 1.5f).toFloat()
            }
            updateObstacles(dt)
            checkObstacleCollisions()
        }

        if (cannonLevel > 0) {
            cannonFireTimer -= dt
            if (cannonFireTimer <= 0f) {
                fireCannons()
                cannonFireTimer = cannonInterval
            }
        }
        if (bombCooldown > 0f) bombCooldown -= dt
        updateProjectiles(dt)
        checkProjectileCollisions()

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
                score += b.points * (if (hardMode) 2 else 1)
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
                GameState.BUY_GEMS -> drawBuyGemsScreen(canvas)
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

            if (b.isGemBlock) {
                drawGemIcon(canvas, b.rect.centerX(), b.rect.centerY(), dp(10f))
            }
        }

        if (hardMode) drawObstacles(canvas)

        // paddle
        val paddleRect = RectF(paddleX - paddleW / 2f, paddleY, paddleX + paddleW / 2f, paddleY + paddleH)
        drawBeveledButton(canvas, paddleRect, colorSkyBlue)
        drawCannons(canvas)
        drawProjectiles(canvas)

        // ball (naturally off-screen once missed, so no extra visibility check needed)
        paint.color = ballColor
        canvas.drawCircle(ballX, ballY, ballR, paint)

        drawControlZoneHint(canvas)
        if (bombsUnlocked) drawBombHint(canvas)
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
        val label = listOf("Cannon", "Double Cannon", "Bombs").getOrElse(activeCrateTier) { "Reward" }
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

    private fun drawObstacles(canvas: Canvas) {
        for (o in obstacles) {
            val facingRight = o.vx > 0
            when (o.type) {
                ObstacleType.ROCKET -> drawRocket(canvas, o.x, o.y, facingRight)
                ObstacleType.PIG -> drawPig(canvas, o.x, o.y, facingRight)
                ObstacleType.EAGLE -> drawEagle(canvas, o.x, o.y, facingRight)
                ObstacleType.GOLDFISH -> drawGoldfish(canvas, o.x, o.y, facingRight)
            }
        }
    }

    private fun drawRocket(canvas: Canvas, cx: Float, cy: Float, right: Boolean) {
        val d = if (right) 1f else -1f
        val bodyHalf = obstacleHalfW - dp(10f)
        paint.color = Color.parseColor("#ECEFF1")
        canvas.drawRoundRect(RectF(cx - bodyHalf, cy - dp(7f), cx + bodyHalf, cy + dp(7f)), dp(6f), dp(6f), paint)
        paint.color = Color.parseColor("#E53935")
        canvas.drawPath(trianglePath(cx + bodyHalf * d, cy - dp(7f), cx + bodyHalf * d, cy + dp(7f), cx + (bodyHalf + dp(12f)) * d, cy), paint)
        paint.color = Color.parseColor("#FFB300")
        canvas.drawPath(trianglePath(cx - bodyHalf * d, cy - dp(4f), cx - bodyHalf * d, cy + dp(4f), cx - (bodyHalf + dp(10f)) * d, cy), paint)
    }

    private fun drawPig(canvas: Canvas, cx: Float, cy: Float, right: Boolean) {
        val d = if (right) 1f else -1f
        paint.color = Color.WHITE
        paint.alpha = 200
        canvas.drawOval(RectF(cx - dp(6f), cy - dp(16f), cx + dp(10f), cy - dp(4f)), paint)
        paint.alpha = 255
        paint.color = Color.parseColor("#F48FB1")
        canvas.drawOval(RectF(cx - dp(18f), cy - dp(11f), cx + dp(18f), cy + dp(11f)), paint)
        canvas.drawPath(trianglePath(cx - dp(10f) * d, cy - dp(10f), cx - dp(4f) * d, cy - dp(18f), cx + dp(2f) * d, cy - dp(9f)), paint)
        paint.color = Color.parseColor("#EC7FA5")
        val snoutCx = cx + dp(10f) * d
        canvas.drawOval(RectF(snoutCx - dp(6f), cy - dp(5f), snoutCx + dp(6f), cy + dp(5f)), paint)
    }

    private fun drawEagle(canvas: Canvas, cx: Float, cy: Float, right: Boolean) {
        val d = if (right) 1f else -1f
        paint.color = Color.parseColor("#795548")
        canvas.drawPath(trianglePath(cx - dp(4f) * d, cy, cx - dp(22f) * d, cy - dp(14f), cx - dp(22f) * d, cy + dp(14f)), paint)
        canvas.drawOval(RectF(cx - dp(12f), cy - dp(7f), cx + dp(12f), cy + dp(7f)), paint)
        paint.color = Color.parseColor("#FFCA28")
        val beakCx = cx + dp(14f) * d
        canvas.drawPath(trianglePath(beakCx - dp(2f) * d, cy - dp(3f), beakCx - dp(2f) * d, cy + dp(3f), beakCx + dp(6f) * d, cy), paint)
    }

    private fun drawGoldfish(canvas: Canvas, cx: Float, cy: Float, right: Boolean) {
        val d = if (right) 1f else -1f
        paint.color = Color.parseColor("#FF7043")
        canvas.drawOval(RectF(cx - dp(14f), cy - dp(9f), cx + dp(14f), cy + dp(9f)), paint)
        canvas.drawPath(trianglePath(cx - dp(14f) * d, cy, cx - dp(24f) * d, cy - dp(10f), cx - dp(24f) * d, cy + dp(10f)), paint)
        paint.color = Color.parseColor("#FFAB91")
        canvas.drawPath(trianglePath(cx, cy - dp(9f), cx + dp(6f) * d, cy - dp(16f), cx + dp(4f) * d, cy - dp(6f)), paint)
    }

    // --- backgrounds ---

    private var decorW = -1
    private var decorH = -1
    private val stars = mutableListOf<FloatArray>() // x, y, radius, alpha
    private val clouds = mutableListOf<FloatArray>() // cx, cy, rw, rh
    private val diamonds = mutableListOf<DiamondDecor>()

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
        diamonds.clear()
        val diamondColors = intArrayOf(Color.parseColor("#5C6BC0"), Color.parseColor("#26C6DA"), Color.parseColor("#AB47BC"))
        repeat(18) {
            diamonds.add(
                DiamondDecor(
                    rnd.nextFloat() * screenW,
                    rnd.nextFloat() * screenH,
                    dp(20f + rnd.nextFloat() * 40f),
                    diamondColors[rnd.nextInt(diamondColors.size)]
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
            BgTheme.DIAMONDS -> drawDiamondsBackground(canvas)
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

    private fun drawDiamondsBackground(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        for (d in diamonds) {
            paint.color = d.color
            paint.alpha = 30
            canvas.drawPath(diamondPath(d.x, d.y, d.r), paint)
        }
        paint.alpha = 255
    }

    // --- HUD ---

    private fun exitButtonRect(): RectF = RectF(dp(6f), dp(6f), dp(52f), dp(52f))

    private fun drawExitButton(canvas: Canvas) {
        val r = exitButtonRect()
        val cx = r.centerX()
        val cy = r.centerY()
        val radius = r.width() / 2f

        paint.color = Color.parseColor("#76FF03") // acid green
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = Color.parseColor("#33691E")
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL

        paint.color = Color.WHITE
        paint.strokeWidth = dp(4f)
        val armLen = radius * 0.5f
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
        drawOutlinedText(canvas, "Score: $score", dp(62f), dp(32f), scorePaint)

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

        if (hardMode) {
            val hardPaint = Paint(hudPaint).apply {
                textAlign = Paint.Align.RIGHT
                color = Color.parseColor("#FF5252")
                textSize = dp(20f)
            }
            canvas.drawText("HARD", screenW - dp(16f), dp(58f), hardPaint)
        }
    }

    private fun drawCannons(canvas: Canvas) {
        if (cannonLevel <= 0) return
        paint.color = Color.parseColor("#546E7A")
        val positions = if (cannonLevel == 1) {
            listOf(paddleX)
        } else {
            listOf(paddleX - paddleW / 2f + dp(8f), paddleX + paddleW / 2f - dp(8f))
        }
        for (px in positions) {
            canvas.drawRoundRect(RectF(px - dp(5f), paddleY - dp(14f), px + dp(5f), paddleY + dp(2f)), dp(3f), dp(3f), paint)
        }
    }

    private fun drawProjectiles(canvas: Canvas) {
        for (p in projectiles) {
            if (p.isBomb) {
                // dark bomb body with a bright outline so it reads clearly on any background
                paint.color = Color.parseColor("#212121")
                canvas.drawCircle(p.x, p.y, dp(11f), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                paint.color = Color.WHITE
                canvas.drawCircle(p.x, p.y, dp(11f), paint)
                paint.style = Paint.Style.FILL

                paint.color = Color.WHITE
                paint.alpha = 130
                canvas.drawCircle(p.x - dp(3.5f), p.y - dp(3.5f), dp(3.5f), paint)
                paint.alpha = 255

                // lit fuse
                paint.color = Color.parseColor("#8D6E63")
                canvas.drawRect(p.x - dp(1.5f), p.y - dp(18f), p.x + dp(1.5f), p.y - dp(10f), paint)
                paint.color = Color.parseColor("#FF7043")
                canvas.drawCircle(p.x, p.y - dp(19f), dp(5f), paint)
                paint.color = Color.parseColor("#FFEE58")
                canvas.drawCircle(p.x, p.y - dp(19f), dp(2.5f), paint)
            } else {
                paint.color = Color.parseColor("#80DEEA")
                canvas.drawRoundRect(RectF(p.x - dp(2.5f), p.y - dp(8f), p.x + dp(2.5f), p.y + dp(8f)), dp(2f), dp(2f), paint)
            }
        }
    }

    private fun drawBombHint(canvas: Canvas) {
        val label = if (bombCooldown <= 0f) "Bomb ready – tap center" else "Bomb: ${(bombCooldown).toInt() + 1}s"
        val bombPaint = Paint(levelPaint).apply { textSize = dp(15f) }
        canvas.drawText(label, screenW / 2f, screenH - bottomBarH - controlZoneH - dp(10f), bombPaint)
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
        return (0 until 6).map { i ->
            val top = startY + i * (bh + gap)
            RectF(left, top, left + bw, top + bh)
        }
    }

    private fun gemPackRects(): List<RectF> {
        val w = screenW * 0.8f
        val h = dp(90f)
        val gap = dp(20f)
        val left = (screenW - w) / 2f
        val startY = screenH * 0.28f
        return (0 until 3).map { i ->
            val top = startY + i * (h + gap)
            RectF(left, top, left + w, top + h)
        }
    }

    private fun backButtonRect(): RectF {
        val w = dp(130f)
        val h = dp(50f)
        return RectF(dp(20f), screenH - dp(30f) - h, dp(20f) + w, screenH - dp(30f))
    }

    private fun bgOptionRects(): List<RectF> {
        val w = screenW * 0.8f
        val h = dp(90f)
        val gap = dp(14f)
        val left = (screenW - w) / 2f
        val startY = screenH * 0.16f
        return (0 until 4).map { i ->
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
        val labels = listOf("New Game Normal", "New Game Hard", "Top Score", "Change Background", "Store", "My Account")
        val buttonColors = listOf(
            colorSkyBlue, Color.parseColor("#EF5350"), colorGold, colorLeafGreen,
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

    private fun drawBuyGemsScreen(canvas: Canvas) {
        drawBackground(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.08f, screenW.toFloat(), screenH * 0.08f + dp(50f), paint)
        paint.alpha = 255
        drawOutlinedText(
            canvas, "STORE", screenW / 2f, screenH * 0.08f + dp(36f),
            Paint(titlePaint).apply { textSize = dp(28f) }
        )

        if (!isRegistered) {
            val warn = Paint(levelPaint).apply { color = Color.parseColor("#FF5252"); textSize = dp(15f) }
            canvas.drawText("Register in My Account to make purchases", screenW / 2f, screenH * 0.08f + dp(66f), warn)
        }

        val rects = gemPackRects()
        val titles = listOf("Cannon", "Double Cannon", "Bombs")
        val descriptions = listOf("Auto-fires upward", "Two side cannons", "Tap center every 5s: destroys 3 blocks")
        val gemCosts = intArrayOf(100, 200, 300)
        val euroPrices = listOf("€1", "€2", "€3")
        val owned = booleanArrayOf(cannonLevel >= 1, cannonLevel >= 2, bombsUnlocked)

        for (i in rects.indices) {
            drawBeveledButton(canvas, rects[i], if (owned[i]) colorLeafGreen else Color.parseColor("#AB47BC"))
            drawOutlinedText(canvas, titles[i], rects[i].centerX(), rects[i].top + dp(30f), buttonTextPaint)
            val descPaint = Paint(buttonTextPaint).apply { textSize = dp(15f) }
            canvas.drawText(descriptions[i], rects[i].centerX(), rects[i].top + dp(52f), descPaint)

            val priceLabel = if (owned[i]) {
                "Owned · tap to buy again"
            } else {
                val realPrice = BillingManager.priceFor(BillingManager.GEM_PACKS[i].productId)
                if (realPrice != null) "$realPrice  ·  or ${gemCosts[i]} gems (demo)"
                else "${gemCosts[i]} gems · ${euroPrices[i]} (tap: unlock with your gems)"
            }
            val pricePaint = Paint(buttonTextPaint).apply { textSize = dp(15f) }
            drawOutlinedText(canvas, priceLabel, rects[i].centerX(), rects[i].bottom - dp(12f), pricePaint)
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

    private fun drawBackgroundSelectScreen(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0B1A33"))
        drawGemsBadge(canvas)

        paint.color = Color.BLACK
        paint.alpha = 70
        canvas.drawRect(0f, screenH * 0.08f, screenW.toFloat(), screenH * 0.08f + dp(50f), paint)
        paint.alpha = 255
        drawOutlinedText(
            canvas, "CHOOSE BACKGROUND", screenW / 2f, screenH * 0.08f + dp(36f),
            Paint(titlePaint).apply { textSize = dp(26f) }
        )

        val rects = bgOptionRects()
        val names = listOf("Grid", "Space", "Night Sky", "Diamonds")
        val options = BgTheme.values()
        for (i in rects.indices) {
            canvas.save()
            canvas.clipRect(rects[i])
            when (options[i]) {
                BgTheme.GRID -> drawGridBackground(canvas)
                BgTheme.SPACE -> drawSpaceBackground(canvas)
                BgTheme.SKY -> drawSkyBackground(canvas)
                BgTheme.DIAMONDS -> drawDiamondsBackground(canvas)
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

    private fun accessoryRowY(index: Int): Float {
        val lineGap = dp(30f)
        val y0 = screenH * 0.40f
        return y0 + lineGap * (3.2f + index)
    }

    private fun accessoryRemoveRect(index: Int): RectF {
        val w = dp(74f)
        val h = dp(24f)
        val y = accessoryRowY(index) - dp(18f)
        return RectF(screenW * 0.66f, y, screenW * 0.66f + w, y + h)
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
            canvas.drawText("Top score: $topScore", left, y, infoPaint); y += lineGap * 1.2f

            canvas.drawText("Accessories:", left, y, Paint(infoPaint).apply { color = colorGold }); y += lineGap

            val accessoryLabels = listOf("Cannon", "Double Cannon", "Bombs")
            val accessoryOwned = booleanArrayOf(cannonLevel >= 1, cannonLevel >= 2, bombsUnlocked)
            for (i in accessoryLabels.indices) {
                val rowY = accessoryRowY(i)
                canvas.drawText("• ${accessoryLabels[i]}: ${if (accessoryOwned[i]) "Owned" else "-"}", left, rowY, infoPaint)
                if (accessoryOwned[i]) {
                    val r = accessoryRemoveRect(i)
                    drawBeveledButton(canvas, r, Color.parseColor("#EF5350"))
                    drawOutlinedText(canvas, "Remove", r.centerX(), r.centerY() + dp(5f), Paint(buttonTextPaint).apply { textSize = dp(12f) })
                }
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
                            hardMode = false
                            pendingAction = PendingAction.RESTART_GAME
                        }
                        rects[1].contains(event.x, event.y) -> {
                            hardMode = true
                            pendingAction = PendingAction.RESTART_GAME
                        }
                        rects[2].contains(event.x, event.y) -> {
                            state = GameState.TOP_SCORE
                            (context as? Activity)?.let { LeaderboardManager.tryOpenLeaderboardUi(it) }
                        }
                        rects[3].contains(event.x, event.y) -> state = GameState.BACKGROUND_SELECT
                        rects[4].contains(event.x, event.y) -> state = GameState.BUY_GEMS
                        rects[5].contains(event.x, event.y) -> state = GameState.ACCOUNT
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
            GameState.BUY_GEMS -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (backButtonRect().contains(event.x, event.y)) {
                        state = GameState.MENU
                    } else {
                        val rects = gemPackRects()
                        for (i in rects.indices) {
                            if (rects[i].contains(event.x, event.y)) {
                                pendingStorePurchase = i
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
                    } else if (isRegistered && (0..2).any { accessoryRemoveRect(it).contains(event.x, event.y) }) {
                        pendingRemoveAccessory = (0..2).first { accessoryRemoveRect(it).contains(event.x, event.y) }
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
                    if (event.action == MotionEvent.ACTION_DOWN &&
                        kotlin.math.abs(event.x - screenW / 2f) < screenW * 0.15f
                    ) {
                        pendingBombLaunch = true
                    }
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
