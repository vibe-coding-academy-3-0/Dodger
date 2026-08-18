package com.example

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import java.util.Random
import kotlin.math.*

// ==========================================
// GAME ENUMS & DATA MODELS
// ==========================================

enum class GameState {
    START,
    PLAYING,
    PAUSED,
    GAME_OVER
}

enum class PowerUpType {
    SHIELD,       // Protège le vaisseau contre 1 collision
    BONUS_SCORE,  // +50 Points instantanés
    EMP_BOMB      // Détruit tous les astéroïdes à l'écran
}

data class Star(
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float,
    val alpha: Float,
    val color: Color
)

data class Asteroid(
    val id: Long,
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float,
    var rotation: Float,
    val rotationSpeed: Float,
    val vertices: List<Offset>,
    val color: Color
)

data class PowerUp(
    val id: Long,
    var x: Float,
    var y: Float,
    val radius: Float,
    val speed: Float,
    val type: PowerUpType,
    var animPhase: Float = 0f
)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var radius: Float,
    var color: Color,
    var alpha: Float,
    var maxLife: Float,
    var currentLife: Float = 0f
)

data class ScorePopup(
    val id: Long,
    val text: String,
    var x: Float,
    var y: Float,
    val color: Color,
    var alpha: Float = 1.0f,
    var currentLife: Float = 0f,
    val maxLife: Float = 1.0f
)

// ==========================================
// MAIN COMPOSABLE
// ==========================================

@Composable
fun SpaceDodgerGame(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val random = remember { Random() }

    // Persistent High Score
    val sharedPrefs = remember {
        context.getSharedPreferences("space_dodger_prefs", Context.MODE_PRIVATE)
    }
    var highScore by remember {
        mutableStateOf(sharedPrefs.getInt("high_score", 0))
    }
    var totalGamesPlayed by remember {
        mutableStateOf(sharedPrefs.getInt("total_games", 0))
    }

    // Game Control States
    var gameState by remember { mutableStateOf(GameState.START) }
    var soundEnabled by remember { mutableStateOf(true) }
    var score by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf(1) }
    var hasShield by remember { mutableStateOf(false) }
    var empFlashAlpha by remember { mutableStateOf(0f) }

    // Game Statistics for GameOver screen
    var asteroidsDodged by remember { mutableStateOf(0) }
    var powerUpsCollected by remember { mutableStateOf(0) }
    var gameTimeSeconds by remember { mutableStateOf(0f) }
    var isNewHighScore by remember { mutableStateOf(false) }

    // Space Ship State
    var shipX by remember { mutableStateOf(0f) }
    var targetShipX by remember { mutableStateOf(0f) }
    var shipY by remember { mutableStateOf(0f) }
    val shipWidth = 48.dp
    val shipHeight = 56.dp
    var steeringDirection by remember { mutableStateOf(0) } // -1 left, 0 none, 1 right

    // Screen Dimensions in Pixels
    var canvasWidth by remember { mutableStateOf(0f) }
    var canvasHeight by remember { mutableStateOf(0f) }

    // Lists for Entities
    val stars = remember { mutableStateListOf<Star>() }
    val asteroids = remember { mutableStateListOf<Asteroid>() }
    val powerUps = remember { mutableStateListOf<PowerUp>() }
    val particles = remember { mutableStateListOf<Particle>() }
    val scorePopups = remember { mutableStateListOf<ScorePopup>() }

    // Timers & Spawners
    var nextAsteroidSpawnTime by remember { mutableStateOf(0f) }
    var nextPowerUpSpawnTime by remember { mutableStateOf(0f) }
    var entityIdCounter by remember { mutableStateOf(1L) }

    // Pulsing Animations for Menu & HUD
    val infiniteTransition = rememberInfiniteTransition(label = "menu_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Helper: Save High Score
    fun updateHighScoreIfNeeded(currentScore: Int) {
        if (currentScore > highScore) {
            highScore = currentScore
            isNewHighScore = true
            sharedPrefs.edit().putInt("high_score", highScore).apply()
        }
    }

    // Helper: Reset Game to Initial Playing State
    fun startNewGame() {
        score = 0
        level = 1
        hasShield = false
        asteroidsDodged = 0
        powerUpsCollected = 0
        gameTimeSeconds = 0f
        isNewHighScore = false
        empFlashAlpha = 0f

        asteroids.clear()
        powerUps.clear()
        particles.clear()
        scorePopups.clear()

        if (canvasWidth > 0f) {
            shipX = canvasWidth / 2f
            targetShipX = canvasWidth / 2f
            shipY = canvasHeight - 140f
        }

        nextAsteroidSpawnTime = 0.5f
        nextPowerUpSpawnTime = 8.0f

        totalGamesPlayed++
        sharedPrefs.edit().putInt("total_games", totalGamesPlayed).apply()

        gameState = GameState.PLAYING
    }

    // Helper: Spawn Explosions
    fun spawnExplosion(x: Float, y: Float, count: Int, primaryColor: Color, secondaryColor: Color) {
        for (i in 0 until count) {
            val angle = random.nextFloat() * 2 * PI.toFloat()
            val speed = random.nextFloat() * 250f + 50f
            val vx = cos(angle) * speed
            val vy = sin(angle) * speed
            val color = if (random.nextBoolean()) primaryColor else secondaryColor
            particles.add(
                Particle(
                    x = x,
                    y = y,
                    vx = vx,
                    vy = vy,
                    radius = random.nextFloat() * 6f + 3f,
                    color = color,
                    alpha = 1.0f,
                    maxLife = random.nextFloat() * 0.5f + 0.3f
                )
            )
        }
    }

    // Helper: Spawn Floating Score Text Popup
    fun addScorePopup(text: String, x: Float, y: Float, color: Color) {
        scorePopups.add(
            ScorePopup(
                id = entityIdCounter++,
                text = text,
                x = x,
                y = y,
                color = color
            )
        )
    }

    // ==========================================
    // GAME LOOP (withFrameNanos ~60 FPS)
    // ==========================================
    LaunchedEffect(gameState, canvasWidth, canvasHeight) {
        var lastTimeNanos = System.nanoTime()

        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                val dt = ((frameTimeNanos - lastTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                lastTimeNanos = frameTimeNanos

                if (canvasWidth <= 0f || canvasHeight <= 0f) return@withFrameNanos

                // Always update stars (gives scrolling background in all states)
                val starSpeedMultiplier = if (gameState == GameState.PLAYING) 1f + (level * 0.15f) else 0.5f
                for (star in stars) {
                    star.y += star.speed * starSpeedMultiplier * dt * 60f
                    if (star.y > canvasHeight) {
                        star.y = 0f
                        star.x = random.nextFloat() * canvasWidth
                    }
                }

                // EMP Screen Flash Fading
                if (empFlashAlpha > 0f) {
                    empFlashAlpha = (empFlashAlpha - dt * 2f).coerceAtLeast(0f)
                }

                // Active Gameplay Engine Logic
                if (gameState == GameState.PLAYING) {
                    gameTimeSeconds += dt

                    // Difficulty scaling: Increase Level every 150 points or 15 seconds
                    val calculatedLevel = (score / 150) + (gameTimeSeconds / 15f).toInt() + 1
                    level = calculatedLevel.coerceIn(1, 10)

                    // Steering & Ship Physics
                    val shipSpeed = 600f * dt
                    if (steeringDirection != 0) {
                        targetShipX = (targetShipX + steeringDirection * shipSpeed).coerceIn(40f, canvasWidth - 40f)
                    }
                    // Interpolate current shipX towards targetShipX
                    shipX += (targetShipX - shipX) * (12f * dt).coerceAtMost(1f)
                    shipX = shipX.coerceIn(40f, canvasWidth - 40f)

                    // Engine Exhaust Particles
                    if (random.nextFloat() < 0.7f) {
                        particles.add(
                            Particle(
                                x = shipX + (random.nextFloat() * 16f - 8f),
                                y = shipY + 24f,
                                vx = (random.nextFloat() * 40f - 20f),
                                vy = random.nextFloat() * 120f + 100f,
                                radius = random.nextFloat() * 4f + 2f,
                                color = if (random.nextBoolean()) Color(0xFFFF9100) else Color(0xFFFFEA00),
                                alpha = 0.8f,
                                maxLife = 0.25f
                            )
                        )
                    }

                    // --- SPAWN ASTEROIDS ---
                    nextAsteroidSpawnTime -= dt
                    if (nextAsteroidSpawnTime <= 0f) {
                        val baseSpeed = 180f + (level * 35f)
                        val radius = random.nextFloat() * 20f + 18f // 18dp to 38dp
                        val speed = baseSpeed + (random.nextFloat() * 80f - 40f)
                        val numVertices = random.nextInt(3) + 6
                        val vertexOffsets = List(numVertices) { i ->
                            val angle = (2 * PI / numVertices * i).toFloat()
                            val dist = radius * (0.75f + random.nextFloat() * 0.5f)
                            Offset(cos(angle) * dist, sin(angle) * dist)
                        }

                        asteroids.add(
                            Asteroid(
                                id = entityIdCounter++,
                                x = random.nextFloat() * (canvasWidth - 80f) + 40f,
                                y = -radius - 10f,
                                radius = radius,
                                speed = speed,
                                rotation = 0f,
                                rotationSpeed = (random.nextFloat() * 120f - 60f),
                                vertices = vertexOffsets,
                                color = if (random.nextBoolean()) Color(0xFF78909C) else Color(0xFF8D6E63)
                            )
                        )

                        // Spawn interval gets faster with higher levels
                        val minInterval = (1.2f - (level * 0.09f)).coerceAtLeast(0.35f)
                        nextAsteroidSpawnTime = minInterval * (0.7f + random.nextFloat() * 0.6f)
                    }

                    // --- SPAWN POWER-UPS ---
                    nextPowerUpSpawnTime -= dt
                    if (nextPowerUpSpawnTime <= 0f) {
                        val type = when (random.nextInt(10)) {
                            in 0..4 -> PowerUpType.BONUS_SCORE
                            in 5..7 -> PowerUpType.SHIELD
                            else -> PowerUpType.EMP_BOMB
                        }
                        powerUps.add(
                            PowerUp(
                                id = entityIdCounter++,
                                x = random.nextFloat() * (canvasWidth - 100f) + 50f,
                                y = -40f,
                                radius = 22f,
                                speed = 140f + (level * 10f),
                                type = type
                            )
                        )
                        nextPowerUpSpawnTime = random.nextFloat() * 8f + 7f
                    }

                    // --- UPDATE ASTEROIDS ---
                    val shipRadius = 24f
                    val asteroidIterator = asteroids.iterator()
                    while (asteroidIterator.hasNext()) {
                        val ast = asteroidIterator.next()
                        ast.y += ast.speed * dt
                        ast.rotation += ast.rotationSpeed * dt

                        // Check collision with ship
                        val dist = hypot(ast.x - shipX, ast.y - shipY)
                        if (dist < (ast.radius + shipRadius)) {
                            // Collision occurred!
                            if (hasShield) {
                                // Shield absorbs hit
                                hasShield = false
                                asteroidIterator.remove()
                                asteroidsDodged++
                                score += 20

                                if (soundEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }

                                spawnExplosion(ast.x, ast.y, 25, Color(0xFF00E5FF), Color(0xFF18FFFF))
                                addScorePopup("BOUCLIER DETRUIT!", shipX, shipY - 40f, Color(0xFF00E5FF))
                            } else {
                                // Ship destroyed -> Game Over!
                                spawnExplosion(shipX, shipY, 40, Color(0xFFFF3D00), Color(0xFFFFD600))
                                if (soundEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                updateHighScoreIfNeeded(score)
                                gameState = GameState.GAME_OVER
                                break
                            }
                        } else if (ast.y > canvasHeight + ast.radius + 20f) {
                            // Asteroid safely avoided off bottom
                            asteroidsDodged++
                            score += 5
                            asteroidIterator.remove()
                        }
                    }

                    // --- UPDATE POWER-UPS ---
                    val powerUpIterator = powerUps.iterator()
                    while (powerUpIterator.hasNext()) {
                        val p = powerUpIterator.next()
                        p.y += p.speed * dt
                        p.animPhase += dt * 4f

                        val dist = hypot(p.x - shipX, p.y - shipY)
                        if (dist < (p.radius + shipRadius)) {
                            // Collected Powerup!
                            powerUpsCollected++
                            if (soundEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }

                            when (p.type) {
                                PowerUpType.SHIELD -> {
                                    hasShield = true
                                    score += 25
                                    spawnExplosion(p.x, p.y, 18, Color(0xFF00E5FF), Color(0xFF80D8FF))
                                    addScorePopup("BOUCLIER ACTIF!", shipX, shipY - 40f, Color(0xFF00E5FF))
                                }
                                PowerUpType.BONUS_SCORE -> {
                                    score += 50
                                    spawnExplosion(p.x, p.y, 18, Color(0xFFFFD600), Color(0xFFFFEA00))
                                    addScorePopup("+50 PTS", p.x, p.y - 20f, Color(0xFFFFD600))
                                }
                                PowerUpType.EMP_BOMB -> {
                                    score += 100
                                    empFlashAlpha = 0.8f
                                    // Destroy all visible asteroids
                                    for (ast in asteroids) {
                                        spawnExplosion(ast.x, ast.y, 12, Color(0xFFD500F9), Color(0xFFE040FB))
                                        asteroidsDodged++
                                    }
                                    asteroids.clear()
                                    addScorePopup("BOMBE EMP! +100", shipX, shipY - 40f, Color(0xFFE040FB))
                                }
                            }
                            powerUpIterator.remove()
                        } else if (p.y > canvasHeight + p.radius + 20f) {
                            powerUpIterator.remove()
                        }
                    }

                    // --- UPDATE PARTICLES ---
                    val particleIterator = particles.iterator()
                    while (particleIterator.hasNext()) {
                        val pt = particleIterator.next()
                        pt.currentLife += dt
                        if (pt.currentLife >= pt.maxLife) {
                            particleIterator.remove()
                        } else {
                            pt.x += pt.vx * dt
                            pt.y += pt.vy * dt
                            pt.alpha = (1.0f - (pt.currentLife / pt.maxLife)).coerceIn(0f, 1f)
                        }
                    }

                    // --- UPDATE SCORE POPUPS ---
                    val popupIterator = scorePopups.iterator()
                    while (popupIterator.hasNext()) {
                        val pop = popupIterator.next()
                        pop.currentLife += dt
                        if (pop.currentLife >= pop.maxLife) {
                            popupIterator.remove()
                        } else {
                            pop.y -= 30f * dt
                            pop.alpha = (1.0f - (pop.currentLife / pop.maxLife)).coerceIn(0f, 1f)
                        }
                    }
                }
            }
        }
    }

    // Initialize Starfield on first size layout
    LaunchedEffect(canvasWidth, canvasHeight) {
        if (canvasWidth > 0f && canvasHeight > 0f && stars.isEmpty()) {
            stars.clear()
            for (i in 0..60) {
                stars.add(
                    Star(
                        x = random.nextFloat() * canvasWidth,
                        y = random.nextFloat() * canvasHeight,
                        radius = random.nextFloat() * 2.5f + 1f,
                        speed = random.nextFloat() * 3f + 1f,
                        alpha = random.nextFloat() * 0.7f + 0.3f,
                        color = when (random.nextInt(4)) {
                            0 -> Color(0xFF80D8FF)
                            1 -> Color(0xFFFFD180)
                            2 -> Color(0xFFEA80FC)
                            else -> Color.White
                        }
                    )
                )
            }
            if (shipX == 0f) {
                shipX = canvasWidth / 2f
                targetShipX = canvasWidth / 2f
                shipY = canvasHeight - 140f
            }
        }
    }

    // ==========================================
    // UI CONTAINERS & CANVAS LAYOUT
    // ==========================================

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF080B14))
    ) {
        // --- 1. MAIN GAME CANVAS ---
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(gameState) {
                    if (gameState != GameState.PLAYING) return@pointerInput
                    detectTapGestures(
                        onPress = { offset ->
                            // Touch Left half -> Steer Left, Touch Right half -> Steer Right
                            steeringDirection = if (offset.x < size.width / 2f) -1 else 1
                            tryAwaitRelease()
                            steeringDirection = 0
                        }
                    )
                }
                .pointerInput(gameState) {
                    if (gameState != GameState.PLAYING) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            targetShipX = offset.x.coerceIn(40f, size.width.toFloat() - 40f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            targetShipX = change.position.x.coerceIn(40f, size.width.toFloat() - 40f)
                        }
                    )
                }
        ) {
            canvasWidth = size.width
            canvasHeight = size.height

            // A. Draw Starfield
            for (star in stars) {
                drawCircle(
                    color = star.color,
                    radius = star.radius,
                    center = Offset(star.x, star.y),
                    alpha = star.alpha
                )
            }

            // B. Draw EMP Bomb Flash
            if (empFlashAlpha > 0f) {
                drawRect(
                    color = Color(0xFFE040FB),
                    size = size,
                    alpha = empFlashAlpha * 0.4f
                )
            }

            // C. Draw Asteroids
            for (ast in asteroids) {
                rotate(degrees = ast.rotation, pivot = Offset(ast.x, ast.y)) {
                    val path = Path()
                    if (ast.vertices.isNotEmpty()) {
                        path.moveTo(ast.x + ast.vertices[0].x, ast.y + ast.vertices[0].y)
                        for (i in 1 until ast.vertices.size) {
                            path.lineTo(ast.x + ast.vertices[i].x, ast.y + ast.vertices[i].y)
                        }
                        path.close()
                    }
                    drawPath(path = path, color = ast.color)
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.3f),
                        style = Stroke(width = 2f)
                    )
                }
            }

            // D. Draw Power-Ups
            for (p in powerUps) {
                val pulseRadius = p.radius + sin(p.animPhase) * 3f
                when (p.type) {
                    PowerUpType.SHIELD -> {
                        // Blue glowing aura orb
                        drawCircle(
                            color = Color(0xFF00E5FF).copy(alpha = 0.3f),
                            radius = pulseRadius + 6f,
                            center = Offset(p.x, p.y)
                        )
                        drawCircle(
                            color = Color(0xFF00B0FF),
                            radius = pulseRadius,
                            center = Offset(p.x, p.y)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = pulseRadius * 0.5f,
                            center = Offset(p.x, p.y)
                        )
                    }
                    PowerUpType.BONUS_SCORE -> {
                        // Yellow Star item
                        drawCircle(
                            color = Color(0xFFFFD600).copy(alpha = 0.35f),
                            radius = pulseRadius + 6f,
                            center = Offset(p.x, p.y)
                        )
                        drawCircle(
                            color = Color(0xFFFFAB00),
                            radius = pulseRadius,
                            center = Offset(p.x, p.y)
                        )
                        // Inner star accents
                        drawCircle(
                            color = Color.White,
                            radius = pulseRadius * 0.4f,
                            center = Offset(p.x, p.y)
                        )
                    }
                    PowerUpType.EMP_BOMB -> {
                        // Purple EMP orb
                        drawCircle(
                            color = Color(0xFFE040FB).copy(alpha = 0.4f),
                            radius = pulseRadius + 8f,
                            center = Offset(p.x, p.y)
                        )
                        drawCircle(
                            color = Color(0xFFAA00FF),
                            radius = pulseRadius,
                            center = Offset(p.x, p.y)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = pulseRadius * 0.45f,
                            center = Offset(p.x, p.y)
                        )
                    }
                }
            }

            // E. Draw Particles
            for (pt in particles) {
                drawCircle(
                    color = pt.color,
                    radius = pt.radius * (1f - (pt.currentLife / pt.maxLife) * 0.5f),
                    center = Offset(pt.x, pt.y),
                    alpha = pt.alpha
                )
            }

            // F. Draw Player Spaceship (if playing or paused)
            if (gameState == GameState.PLAYING || gameState == GameState.PAUSED) {
                // Shield Aura Ring
                if (hasShield) {
                    val shieldGlow = 38f + sin(System.currentTimeMillis() / 150f) * 4f
                    drawCircle(
                        color = Color(0xFF00E5FF).copy(alpha = 0.25f),
                        radius = shieldGlow + 8f,
                        center = Offset(shipX, shipY)
                    )
                    drawCircle(
                        color = Color(0xFF00E5FF),
                        radius = shieldGlow,
                        center = Offset(shipX, shipY),
                        style = Stroke(width = 4f)
                    )
                }

                // Spaceship Main Body (Polygonal Jet)
                val shipPath = Path().apply {
                    moveTo(shipX, shipY - 30f)                 // Nose tip
                    lineTo(shipX + 22f, shipY + 20f)           // Right wing tip
                    lineTo(shipX + 10f, shipY + 12f)           // Right wing inner notch
                    lineTo(shipX + 8f, shipY + 24f)            // Right thruster
                    lineTo(shipX - 8f, shipY + 24f)            // Left thruster
                    lineTo(shipX - 10f, shipY + 12f)           // Left wing inner notch
                    lineTo(shipX - 22f, shipY + 20f)           // Left wing tip
                    close()
                }

                // Main Hull Fill
                drawPath(path = shipPath, color = Color(0xFF00E676))
                // Wing Outlines
                drawPath(
                    path = shipPath,
                    color = Color(0xFFB9F6CA),
                    style = Stroke(width = 3f)
                )

                // Cockpit Glass
                val cockpitPath = Path().apply {
                    moveTo(shipX, shipY - 18f)
                    lineTo(shipX + 8f, shipY + 2f)
                    lineTo(shipX - 8f, shipY + 2f)
                    close()
                }
                drawPath(path = cockpitPath, color = Color(0xFF00E5FF))
            }
        }

        // --- 2. HUD OVERLAY (When PLAYING or PAUSED) ---
        if (gameState == GameState.PLAYING || gameState == GameState.PAUSED) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Score & Level Card
                    Surface(
                        color = Color(0xFF10182D).copy(alpha = 0.85f),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2942))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "SCORE",
                                    color = Color(0xFF90A4AE),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "$score",
                                    color = Color(0xFFFFD600),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = "NIVEAU $level",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "RECORD: $highScore",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Shield Status Indicator Icon
                        if (hasShield) {
                            Surface(
                                color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                shape = CircleShape,
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)),
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = "Bouclier Actif",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        // Pause Button
                        IconButton(
                            onClick = {
                                if (gameState == GameState.PLAYING) {
                                    gameState = GameState.PAUSED
                                } else if (gameState == GameState.PAUSED) {
                                    gameState = GameState.PLAYING
                                }
                            },
                            modifier = Modifier
                                .shadow(8.dp, CircleShape)
                                .background(Color(0xFF1E2942), CircleShape)
                                .size(44.dp)
                        ) {
                            Icon(
                                imageVector = if (gameState == GameState.PAUSED) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Pause",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Touch Steering Guidance Hint (Shows subtly at start of game)
                if (gameTimeSeconds < 4f && gameState == GameState.PLAYING) {
                    Spacer(modifier = Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 60.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "◀ TAP GAUCHE",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "TAP DROITE ▶",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- 3. START MENU OVERLAY ---
        AnimatedVisibility(
            visible = gameState == GameState.START,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF080B14).copy(alpha = 0.92f))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Logo Header
                    Surface(
                        color = Color(0xFF00E5FF).copy(alpha = 0.15f),
                        shape = CircleShape,
                        modifier = Modifier
                            .size(100.dp)
                            .shadow(16.dp, CircleShape, spotColor = Color(0xFF00E5FF))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Space Dodger Logo",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "SPACE DODGER",
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "ÉQUIVEE D'ASTÉROÏDES SPATIAUX",
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    // How To Play Card
                    Card(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF10182D)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2942))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "COMMENT JOUER",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = Color(0xFF00E676).copy(alpha = 0.2f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("👈👉", fontSize = 14.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Tapotetz ou glissez pour diriger le vaisseau.",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 13.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = null,
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Bouclier Bleu: Protège contre 1 choc d'astéroïde.",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 13.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = Color(0xFFFFD600).copy(alpha = 0.2f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = Color(0xFFFFD600),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Étoiles Jaunes: +50 Points instantanés.",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // High Score Record Display
                    if (highScore > 0) {
                        Surface(
                            color = Color(0xFFFFD600).copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD600).copy(alpha = 0.4f)),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            Text(
                                text = "🏆 MEILLEUR SCORE: $highScore",
                                color = Color(0xFFFFD600),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }

                    // START BUTTON ("JOUER")
                    Button(
                        onClick = { startNewGame() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .height(58.dp)
                            .shadow(12.dp, RoundedCornerShape(28.dp), spotColor = Color(0xFF00E676))
                    ) {
                        Text(
                            text = "JOUER",
                            color = Color(0xFF003314),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sound Toggle Button
                    IconButton(
                        onClick = { soundEnabled = !soundEnabled }
                    ) {
                        Icon(
                            imageVector = if (soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                            contentDescription = "Son",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // --- 4. PAUSE OVERLAY ---
        AnimatedVisibility(
            visible = gameState == GameState.PAUSED,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10182D)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E2942))
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "PAUSE",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = { gameState = GameState.PLAYING },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = "REPRENDRE",
                                color = Color.Black,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { startNewGame() },
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = "RECOMMENCER",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // --- 5. GAME OVER OVERLAY ---
        AnimatedVisibility(
            visible = gameState == GameState.GAME_OVER,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF080B14).copy(alpha = 0.94f))
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF10182D)),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFFF3D00).copy(alpha = 0.6f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "GAME OVER",
                            color = Color(0xFFFF3D00),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )

                        Text(
                            text = "VOTRE VAISSEAU A ÉTÉ DÉTRUIT",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Score Result Box
                        Surface(
                            color = Color(0xFF192238),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "SCORE FINAL",
                                    color = Color(0xFF90A4AE),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )

                                Text(
                                    text = "$score",
                                    color = Color(0xFFFFD600),
                                    fontSize = 44.sp,
                                    fontWeight = FontWeight.Black
                                )

                                if (isNewHighScore) {
                                    Surface(
                                        color = Color(0xFFFFD600).copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.padding(top = 6.dp)
                                    ) {
                                        Text(
                                            text = "🎉 NOUVEAU RECORD !",
                                            color = Color(0xFFFFD600),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "MEILLEUR SCORE: $highScore",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Game Stats Breakdown
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("ASTÉROÏDES", color = Color(0xFF90A4AE), fontSize = 10.sp)
                                Text("$asteroidsDodged", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("BONUS", color = Color(0xFF90A4AE), fontSize = 10.sp)
                                Text("$powerUpsCollected", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("TEMPS", color = Color(0xFF90A4AE), fontSize = 10.sp)
                                Text("${gameTimeSeconds.toInt()}s", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        // REPLAY BUTTON ("REJOUER")
                        Button(
                            onClick = { startNewGame() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .shadow(8.dp, RoundedCornerShape(24.dp), spotColor = Color(0xFF00E676))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    tint = Color(0xFF003314)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "REJOUER",
                                    color = Color(0xFF003314),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
