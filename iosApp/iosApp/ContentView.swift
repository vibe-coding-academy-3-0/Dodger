import SwiftUI
import Combine

// ==========================================
// GAME ENUMS & DATA MODELS
// ==========================================

enum GameState {
    case start
    case playing
    case paused
    case gameOver
}

enum PowerUpType {
    case shield      // Protège le vaisseau contre 1 collision
    case bonusScore  // +50 Points instantanés
    case empBomb     // Détruit tous les astéroïdes à l'écran
}

struct Star: Identifiable {
    let id = UUID()
    var x: CGFloat
    var y: CGFloat
    let radius: CGFloat
    let speed: CGFloat
    let alpha: Double
    let color: Color
}

struct AsteroidVertex {
    let x: CGFloat
    let y: CGFloat
}

struct Asteroid: Identifiable {
    let id: Int64
    var x: CGFloat
    var y: CGFloat
    let radius: CGFloat
    let speed: CGFloat
    var rotation: Double
    let rotationSpeed: Double
    let vertices: [AsteroidVertex]
    let color: Color
}

struct PowerUp: Identifiable {
    let id: Int64
    var x: CGFloat
    var y: CGFloat
    let radius: CGFloat
    let speed: CGFloat
    let type: PowerUpType
    var animPhase: Double = 0
}

struct GameParticle: Identifiable {
    let id = UUID()
    var x: CGFloat
    var y: CGFloat
    var vx: CGFloat
    var vy: CGFloat
    var radius: CGFloat
    var color: Color
    var alpha: Double
    var maxLife: Double
    var currentLife: Double = 0
}

struct ScorePopup: Identifiable {
    let id: Int64
    let text: String
    var x: CGFloat
    var y: CGFloat
    let color: Color
    var alpha: Double = 1.0
    var currentLife: Double = 0
    let maxLife: Double = 1.0
}

// ==========================================
// GAME STATE MANAGER (SWIFTUI / COMBINE)
// ==========================================

class SpaceDodgerEngine: ObservableObject {
    @Published var gameState: GameState = .start
    @Published var score: Int = 0
    @Published var highScore: Int = UserDefaults.standard.integer(forKey: "space_dodger_high_score")
    @Published var totalGamesPlayed: Int = UserDefaults.standard.integer(forKey: "space_dodger_total_games")
    @Published var level: Int = 1
    @Published var hasShield: Bool = false
    @Published var empFlashAlpha: Double = 0.0
    @Published var soundEnabled: Bool = true
    
    // Statistics for GameOver screen
    @Published var asteroidsDodged: Int = 0
    @Published var powerUpsCollected: Int = 0
    @Published var gameTimeSeconds: Double = 0
    @Published var isNewHighScore: Bool = false
    
    // Ship State
    @Published var shipX: CGFloat = 200
    @Published var targetShipX: CGFloat = 200
    @Published var shipY: CGFloat = 600
    @Published var steeringDirection: Int = 0 // -1 left, 0 none, 1 right
    
    // Entities
    @Published var stars: [Star] = []
    @Published var asteroids: [Asteroid] = []
    @Published var powerUps: [PowerUp] = []
    @Published var particles: [GameParticle] = []
    @Published var scorePopups: [ScorePopup] = []
    
    // Spawners & Internal Timers
    private var nextAsteroidSpawnTime: Double = 0.5
    private var nextPowerUpSpawnTime: Double = 8.0
    private var entityIdCounter: Int64 = 1
    private var canvasSize: CGSize = .zero
    
    // Haptics
    private let impactFeedback = UIImpactFeedbackGenerator(style: .medium)
    private let heavyFeedback = UIImpactFeedbackGenerator(style: .heavy)
    private let notificationFeedback = UINotificationFeedbackGenerator()
    
    func setCanvasSize(_ size: CGSize) {
        guard size.width > 0 && size.height > 0 else { return }
        let sizeChanged = self.canvasSize != size
        self.canvasSize = size
        
        if sizeChanged {
            initStars(count: 65)
            if gameState == .start || gameState == .gameOver {
                shipX = size.width / 2
                targetShipX = size.width / 2
                shipY = size.height - 120
            }
        }
    }
    
    func initStars(count: Int) {
        guard canvasSize.width > 0 else { return }
        var newStars: [Star] = []
        let colors: [Color] = [
            Color.white,
            Color(red: 0.8, green: 0.9, blue: 1.0),
            Color(red: 1.0, green: 0.95, blue: 0.8),
            Color(red: 0.6, green: 0.8, blue: 1.0)
        ]
        for _ in 0..<count {
            newStars.append(
                Star(
                    x: CGFloat.random(in: 0...canvasSize.width),
                    y: CGFloat.random(in: 0...canvasSize.height),
                    radius: CGFloat.random(in: 1.0...2.5),
                    speed: CGFloat.random(in: 0.4...2.2),
                    alpha: Double.random(in: 0.3...0.9),
                    color: colors.randomElement() ?? .white
                )
            )
        }
        self.stars = newStars
    }
    
    func startNewGame() {
        score = 0
        level = 1
        hasShield = false
        asteroidsDodged = 0
        powerUpsCollected = 0
        gameTimeSeconds = 0
        isNewHighScore = false
        empFlashAlpha = 0
        steeringDirection = 0
        
        asteroids.removeAll()
        powerUps.removeAll()
        particles.removeAll()
        scorePopups.removeAll()
        
        if canvasSize.width > 0 {
            shipX = canvasSize.width / 2
            targetShipX = canvasSize.width / 2
            shipY = canvasSize.height - 120
        }
        
        nextAsteroidSpawnTime = 0.5
        nextPowerUpSpawnTime = 8.0
        
        totalGamesPlayed += 1
        UserDefaults.standard.set(totalGamesPlayed, forKey: "space_dodger_total_games")
        
        gameState = .playing
        triggerHaptic(.medium)
    }
    
    func triggerHaptic(_ type: UIImpactFeedbackGenerator.FeedbackStyle) {
        guard soundEnabled else { return }
        let generator = UIImpactFeedbackGenerator(style: type)
        generator.impactOccurred()
    }
    
    func spawnExplosion(x: CGFloat, y: CGFloat, count: Int, primaryColor: Color, secondaryColor: Color) {
        for _ in 0..<count {
            let angle = Double.random(in: 0...(2 * .pi))
            let speed = CGFloat.random(in: 60...280)
            let vx = cos(angle) * speed
            let vy = sin(angle) * speed
            let color = Bool.random() ? primaryColor : secondaryColor
            
            particles.append(
                GameParticle(
                    x: x,
                    y: y,
                    vx: vx,
                    vy: vy,
                    radius: CGFloat.random(in: 3...7),
                    color: color,
                    alpha: 1.0,
                    maxLife: Double.random(in: 0.35...0.65)
                )
            )
        }
    }
    
    func addScorePopup(text: String, x: CGFloat, y: CGFloat, color: Color) {
        scorePopups.append(
            ScorePopup(
                id: entityIdCounter,
                text: text,
                x: x,
                y: y,
                color: color
            )
        )
        entityIdCounter += 1
    }
    
    func update(dt: Double) {
        guard canvasSize.width > 0 && canvasSize.height > 0 else { return }
        
        // Update background stars
        let starSpeedMultiplier = gameState == .playing ? (1.0 + Double(level) * 0.15) : 0.5
        for i in stars.indices {
            stars[i].y += stars[i].speed * CGFloat(starSpeedMultiplier * dt * 60.0)
            if stars[i].y > canvasSize.height {
                stars[i].y = 0
                stars[i].x = CGFloat.random(in: 0...canvasSize.width)
            }
        }
        
        // EMP Screen Flash
        if empFlashAlpha > 0 {
            empFlashAlpha = max(0, empFlashAlpha - dt * 2.0)
        }
        
        // Gameplay Loop
        if gameState == .playing {
            gameTimeSeconds += dt
            
            // Difficulty scaling
            let calculatedLevel = (score / 150) + Int(gameTimeSeconds / 15.0) + 1
            level = min(10, max(1, calculatedLevel))
            
            // Ship Movement & Inertia
            let shipSpeed: CGFloat = 600.0 * CGFloat(dt)
            if steeringDirection != 0 {
                targetShipX = min(canvasSize.width - 35, max(35, targetShipX + CGFloat(steeringDirection) * shipSpeed))
            }
            shipX += (targetShipX - shipX) * min(1.0, CGFloat(12.0 * dt))
            shipX = min(canvasSize.width - 35, max(35, shipX))
            
            // Thruster engine exhaust
            if Double.random(in: 0...1) < 0.75 {
                particles.append(
                    GameParticle(
                        x: shipX + CGFloat.random(in: -7...7),
                        y: shipY + 22,
                        vx: CGFloat.random(in: -25...25),
                        vy: CGFloat.random(in: 100...220),
                        radius: CGFloat.random(in: 2...4.5),
                        color: Bool.random() ? Color(red: 1.0, green: 0.55, blue: 0.0) : Color(red: 1.0, green: 0.9, blue: 0.0),
                        alpha: 0.85,
                        maxLife: 0.28
                    )
                )
            }
            
            // Spawn Asteroids
            nextAsteroidSpawnTime -= dt
            if nextAsteroidSpawnTime <= 0 {
                let baseSpeed = 180.0 + Double(level) * 35.0
                let radius = CGFloat.random(in: 18...38)
                let speed = CGFloat(baseSpeed + Double.random(in: -40...40))
                let numVertices = Int.random(in: 6...8)
                
                var vertices: [AsteroidVertex] = []
                for i in 0..<numVertices {
                    let angle = (2.0 * .pi / Double(numVertices)) * Double(i)
                    let dist = radius * CGFloat.random(in: 0.75...1.25)
                    vertices.append(AsteroidVertex(x: cos(angle) * dist, y: sin(angle) * dist))
                }
                
                let asteroidColor = Bool.random() ? Color(red: 0.45, green: 0.55, blue: 0.6) : Color(red: 0.55, green: 0.45, blue: 0.4)
                
                asteroids.append(
                    Asteroid(
                        id: entityIdCounter,
                        x: CGFloat.random(in: 40...(canvasSize.width - 40)),
                        y: -radius - 10,
                        radius: radius,
                        speed: speed,
                        rotation: 0,
                        rotationSpeed: Double.random(in: -60...60),
                        vertices: vertices,
                        color: asteroidColor
                    )
                )
                entityIdCounter += 1
                
                let minInterval = max(0.35, 1.2 - Double(level) * 0.09)
                nextAsteroidSpawnTime = minInterval * Double.random(in: 0.7...1.3)
            }
            
            // Spawn PowerUps
            nextPowerUpSpawnTime -= dt
            if nextPowerUpSpawnTime <= 0 {
                let randType = Int.random(in: 0...9)
                let type: PowerUpType
                if randType < 5 {
                    type = .bonusScore
                } else if randType < 8 {
                    type = .shield
                } else {
                    type = .empBomb
                }
                
                powerUps.append(
                    PowerUp(
                        id: entityIdCounter,
                        x: CGFloat.random(in: 45...(canvasSize.width - 45)),
                        y: -30,
                        radius: 20,
                        speed: CGFloat(140.0 + Double(level) * 10.0),
                        type: type
                    )
                )
                entityIdCounter += 1
                nextPowerUpSpawnTime = Double.random(in: 7.0...14.0)
            }
            
            // Asteroids Collision & Movement
            let shipRadius: CGFloat = 24.0
            var i = asteroids.count - 1
            while i >= 0 {
                asteroids[i].y += asteroids[i].speed * CGFloat(dt)
                asteroids[i].rotation += asteroids[i].rotationSpeed * dt
                
                let dx = asteroids[i].x - shipX
                let dy = asteroids[i].y - shipY
                let dist = sqrt(dx * dx + dy * dy)
                
                if dist < (asteroids[i].radius + shipRadius) {
                    // Hit!
                    if hasShield {
                        hasShield = false
                        let ast = asteroids.remove(at: i)
                        asteroidsDodged += 1
                        score += 20
                        triggerHaptic(.medium)
                        spawnExplosion(x: ast.x, y: ast.y, count: 25, primaryColor: Color.cyan, secondaryColor: Color.white)
                        addScorePopup(text: "BOUCLIER DÉTRUIT!", x: shipX, y: shipY - 40, color: .cyan)
                    } else {
                        // Game Over
                        spawnExplosion(x: shipX, y: shipY, count: 40, primaryColor: Color.orange, secondaryColor: Color.red)
                        triggerHaptic(.heavy)
                        if score > highScore {
                            highScore = score
                            isNewHighScore = true
                            UserDefaults.standard.set(highScore, forKey: "space_dodger_high_score")
                        }
                        gameState = .gameOver
                        break
                    }
                } else if asteroids[i].y > canvasSize.height + asteroids[i].radius + 20 {
                    asteroidsDodged += 1
                    score += 5
                    asteroids.remove(at: i)
                }
                i -= 1
            }
            
            // PowerUps Collision & Movement
            var pIdx = powerUps.count - 1
            while pIdx >= 0 {
                powerUps[pIdx].y += powerUps[pIdx].speed * CGFloat(dt)
                powerUps[pIdx].animPhase += dt * 4.0
                
                let dx = powerUps[pIdx].x - shipX
                let dy = powerUps[pIdx].y - shipY
                let dist = sqrt(dx * dx + dy * dy)
                
                if dist < (powerUps[pIdx].radius + shipRadius) {
                    let collected = powerUps.remove(at: pIdx)
                    powerUpsCollected += 1
                    triggerHaptic(.light)
                    
                    switch collected.type {
                    case .shield:
                        hasShield = true
                        score += 25
                        spawnExplosion(x: collected.x, y: collected.y, count: 18, primaryColor: Color.cyan, secondaryColor: Color.blue)
                        addScorePopup(text: "BOUCLIER ACTIF!", x: shipX, y: shipY - 40, color: .cyan)
                    case .bonusScore:
                        score += 50
                        spawnExplosion(x: collected.x, y: collected.y, count: 18, primaryColor: Color.yellow, secondaryColor: Color.orange)
                        addScorePopup(text: "+50 PTS", x: collected.x, y: collected.y - 20, color: .yellow)
                    case .empBomb:
                        score += 100
                        empFlashAlpha = 0.8
                        for ast in asteroids {
                            spawnExplosion(x: ast.x, y: ast.y, count: 14, primaryColor: Color.purple, secondaryColor: Color.pink)
                            asteroidsDodged += 1
                        }
                        asteroids.removeAll()
                        addScorePopup(text: "BOMBE EMP! +100", x: shipX, y: shipY - 40, color: Color(red: 0.9, green: 0.3, blue: 1.0))
                    }
                } else if powerUps[pIdx].y > canvasSize.height + powerUps[pIdx].radius + 20 {
                    powerUps.remove(at: pIdx)
                }
                pIdx -= 1
            }
            
            // Update Particles
            var ptIdx = particles.count - 1
            while ptIdx >= 0 {
                particles[ptIdx].currentLife += dt
                if particles[ptIdx].currentLife >= particles[ptIdx].maxLife {
                    particles.remove(at: ptIdx)
                } else {
                    particles[ptIdx].x += particles[ptIdx].vx * CGFloat(dt)
                    particles[ptIdx].y += particles[ptIdx].vy * CGFloat(dt)
                    particles[ptIdx].alpha = max(0, 1.0 - (particles[ptIdx].currentLife / particles[ptIdx].maxLife))
                }
                ptIdx -= 1
            }
            
            // Update Popups
            var popIdx = scorePopups.count - 1
            while popIdx >= 0 {
                scorePopups[popIdx].currentLife += dt
                if scorePopups[popIdx].currentLife >= scorePopups[popIdx].maxLife {
                    scorePopups.remove(at: popIdx)
                } else {
                    scorePopups[popIdx].y -= CGFloat(40.0 * dt)
                    scorePopups[popIdx].alpha = max(0, 1.0 - (scorePopups[popIdx].currentLife / scorePopups[popIdx].maxLife))
                }
                popIdx -= 1
            }
        }
    }
}

// ==========================================
// MAIN CONTENT VIEW (SWIFTUI)
// ==========================================

struct ContentView: View {
    @StateObject private var engine = SpaceDodgerEngine()
    @State private var dragOffset: CGFloat = 0
    @State private var lastDragLocation: CGFloat = 0
    
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                // Background
                Color(red: 0.03, green: 0.04, blue: 0.09)
                    .ignoresSafeArea()
                
                // 60 FPS Game Render Canvas
                TimelineView(.animation) { timeline in
                    Canvas { context, size in
                        // Background Stars
                        for star in engine.stars {
                            let rect = CGRect(x: star.x - star.radius, y: star.y - star.radius, width: star.radius * 2, height: star.radius * 2)
                            context.fill(Path(ellipseIn: rect), with: .color(star.color.opacity(star.alpha)))
                        }
                        
                        // EMP Flash Effect
                        if engine.empFlashAlpha > 0 {
                            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color.purple.opacity(engine.empFlashAlpha * 0.4)))
                        }
                        
                        // Asteroids
                        for ast in engine.asteroids {
                            var astContext = context
                            astContext.translateBy(x: ast.x, y: ast.y)
                            astContext.rotate(by: Angle(degrees: ast.rotation))
                            
                            var path = Path()
                            if let first = ast.vertices.first {
                                path.move(to: CGPoint(x: first.x, y: first.y))
                                for v in ast.vertices.dropFirst() {
                                    path.addLine(to: CGPoint(x: v.x, y: v.y))
                                }
                                path.closeSubpath()
                            }
                            
                            // Fill & Stroke
                            astContext.fill(path, with: .color(ast.color))
                            astContext.stroke(path, with: .color(ast.color.opacity(0.8)), lineWidth: 2)
                            
                            // Asteroid Crater detail
                            let craterRect = CGRect(x: -ast.radius * 0.3, y: -ast.radius * 0.2, width: ast.radius * 0.4, height: ast.radius * 0.4)
                            astContext.fill(Path(ellipseIn: craterRect), with: .color(Color.black.opacity(0.25)))
                        }
                        
                        // PowerUps
                        for p in engine.powerUps {
                            let rect = CGRect(x: p.x - p.radius, y: p.y - p.radius, width: p.radius * 2, height: p.radius * 2)
                            let pulse = 1.0 + 0.15 * sin(p.animPhase * 3.0)
                            
                            switch p.type {
                            case .shield:
                                context.fill(Path(ellipseIn: rect), with: .color(Color.cyan.opacity(0.4)))
                                context.stroke(Path(ellipseIn: rect.insetBy(dx: -2 * pulse, dy: -2 * pulse)), with: .color(Color.cyan), lineWidth: 2.5)
                            case .bonusScore:
                                context.fill(Path(ellipseIn: rect), with: .color(Color.yellow.opacity(0.4)))
                                context.stroke(Path(ellipseIn: rect.insetBy(dx: -2 * pulse, dy: -2 * pulse)), with: .color(Color.yellow), lineWidth: 2.5)
                            case .empBomb:
                                context.fill(Path(ellipseIn: rect), with: .color(Color.purple.opacity(0.4)))
                                context.stroke(Path(ellipseIn: rect.insetBy(dx: -2 * pulse, dy: -2 * pulse)), with: .color(Color.purple), lineWidth: 2.5)
                            }
                        }
                        
                        // Particles
                        for pt in engine.particles {
                            let rect = CGRect(x: pt.x - pt.radius, y: pt.y - pt.radius, width: pt.radius * 2, height: pt.radius * 2)
                            context.fill(Path(ellipseIn: rect), with: .color(pt.color.opacity(pt.alpha)))
                        }
                        
                        // Spaceship (if playing or paused)
                        if engine.gameState == .playing || engine.gameState == .paused || engine.gameState == .start {
                            var shipContext = context
                            shipContext.translateBy(x: engine.shipX, y: engine.shipY)
                            
                            // Hull Path
                            var shipPath = Path()
                            shipPath.move(to: CGPoint(x: 0, y: -26))
                            shipPath.addLine(to: CGPoint(x: 20, y: 22))
                            shipPath.addLine(to: CGPoint(x: 8, y: 16))
                            shipPath.addLine(to: CGPoint(x: 0, y: 20))
                            shipPath.addLine(to: CGPoint(x: -8, y: 16))
                            shipPath.addLine(to: CGPoint(x: -20, y: 22))
                            shipPath.closeSubpath()
                            
                            // Draw Spaceship Hull
                            shipContext.fill(shipPath, with: .color(Color(red: 0.15, green: 0.65, blue: 1.0)))
                            shipContext.stroke(shipPath, with: .color(Color.white.opacity(0.9)), lineWidth: 2)
                            
                            // Cockpit
                            let cockpitRect = CGRect(x: -5, y: -12, width: 10, height: 16)
                            shipContext.fill(Path(ellipseIn: cockpitRect), with: .color(Color.cyan))
                            
                            // Wings cannons
                            let leftCannon = CGRect(x: -18, y: 6, width: 4, height: 12)
                            let rightCannon = CGRect(x: 14, y: 6, width: 4, height: 12)
                            shipContext.fill(Path(roundedRect: leftCannon, cornerRadius: 2), with: .color(Color.gray))
                            shipContext.fill(Path(roundedRect: rightCannon, cornerRadius: 2), with: .color(Color.gray))
                            
                            // Shield Glow
                            if engine.hasShield {
                                let shieldRect = CGRect(x: -34, y: -34, width: 68, height: 68)
                                shipContext.stroke(Path(ellipseIn: shieldRect), with: .color(Color.cyan.opacity(0.85)), lineWidth: 3)
                                shipContext.fill(Path(ellipseIn: shieldRect), with: .color(Color.cyan.opacity(0.18)))
                            }
                        }
                    }
                    .onChange(of: timeline.date) { _ in
                        engine.update(dt: 1.0 / 60.0)
                    }
                }
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            if engine.gameState == .playing {
                                if lastDragLocation == 0 {
                                    lastDragLocation = value.location.x
                                }
                                let delta = value.location.x - lastDragLocation
                                engine.targetShipX += delta * 1.3
                                lastDragLocation = value.location.x
                            }
                        }
                        .onEnded { _ in
                            lastDragLocation = 0
                        }
                )
                
                // Floating Score Popups
                ForEach(engine.scorePopups) { popup in
                    Text(popup.text)
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundColor(popup.color)
                        .position(x: popup.x, y: popup.y)
                        .opacity(popup.alpha)
                }
                
                // HUD Layer (Playing & Paused)
                if engine.gameState == .playing || engine.gameState == .paused {
                    VStack {
                        // Top HUD Bar
                        HStack(alignment: .center, spacing: 16) {
                            // Score & High Score
                            VStack(alignment: .leading, spacing: 2) {
                                Text("SCORE")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.gray)
                                Text("\(engine.score)")
                                    .font(.system(size: 26, weight: .black, design: .rounded))
                                    .foregroundColor(.white)
                            }
                            
                            Spacer()
                            
                            // Level Badge
                            HStack(spacing: 4) {
                                Image(systemName: "bolt.fill")
                                    .foregroundColor(.yellow)
                                Text("LVL \(engine.level)")
                                    .font(.system(size: 14, weight: .heavy))
                                    .foregroundColor(.yellow)
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.yellow.opacity(0.15))
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(Color.yellow.opacity(0.4), lineWidth: 1))
                            
                            Spacer()
                            
                            // Shield status indicator
                            if engine.hasShield {
                                Image(systemName: "shield.fill")
                                    .foregroundColor(.cyan)
                                    .font(.system(size: 20))
                                    .padding(6)
                                    .background(Color.cyan.opacity(0.2))
                                    .clipShape(Circle())
                            }
                            
                            // Pause Button
                            Button(action: {
                                engine.gameState = engine.gameState == .paused ? .playing : .paused
                                engine.triggerHaptic(.medium)
                            }) {
                                Image(systemName: engine.gameState == .paused ? "play.fill" : "pause.fill")
                                    .font(.system(size: 18, weight: .bold))
                                    .foregroundColor(.white)
                                    .frame(width: 40, height: 40)
                                    .background(Color.white.opacity(0.15))
                                    .clipShape(Circle())
                            }
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 50)
                        
                        Spacer()
                        
                        // Bottom Touch Steering Controls (Alternative to Dragging)
                        HStack(spacing: 40) {
                            Button(action: {}) {
                                Image(systemName: "chevron.left")
                                    .font(.system(size: 28, weight: .bold))
                                    .foregroundColor(.white)
                                    .frame(width: 70, height: 70)
                                    .background(Color.white.opacity(0.12))
                                    .clipShape(Circle())
                            }
                            .simultaneousGesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { _ in engine.steeringDirection = -1 }
                                    .onEnded { _ in engine.steeringDirection = 0 }
                            )
                            
                            Spacer()
                            
                            Button(action: {}) {
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 28, weight: .bold))
                                    .foregroundColor(.white)
                                    .frame(width: 70, height: 70)
                                    .background(Color.white.opacity(0.12))
                                    .clipShape(Circle())
                            }
                            .simultaneousGesture(
                                DragGesture(minimumDistance: 0)
                                    .onChanged { _ in engine.steeringDirection = 1 }
                                    .onEnded { _ in engine.steeringDirection = 0 }
                            )
                        }
                        .padding(.horizontal, 30)
                        .padding(.bottom, 25)
                    }
                }
                
                // START SCREEN OVERLAY
                if engine.gameState == .start {
                    VStack(spacing: 24) {
                        Spacer()
                        
                        // Game Title
                        VStack(spacing: 8) {
                            Image(systemName: "airplane")
                                .font(.system(size: 54))
                                .foregroundColor(.cyan)
                                .rotationEffect(.degrees(-45))
                                .padding(.bottom, 8)
                            
                            Text("SPACE DODGER")
                                .font(.system(size: 36, weight: .black, design: .rounded))
                                .foregroundStyle(
                                    LinearGradient(
                                        colors: [Color.cyan, Color.blue, Color.purple],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .shadow(color: .cyan.opacity(0.5), radius: 10)
                            
                            Text("ESQUIVEZ. SURVIVEZ. GAGNEZ.")
                                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                                .foregroundColor(.gray)
                                .tracking(2)
                        }
                        
                        // High Score Card
                        HStack(spacing: 16) {
                            Image(systemName: "trophy.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.yellow)
                            
                            VStack(alignment: .leading, spacing: 2) {
                                Text("MEILLEUR SCORE")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(.gray)
                                Text("\(engine.highScore)")
                                    .font(.system(size: 24, weight: .heavy, design: .rounded))
                                    .foregroundColor(.white)
                            }
                        }
                        .padding(.horizontal, 28)
                        .padding(.vertical, 14)
                        .background(Color.white.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(Color.yellow.opacity(0.3), lineWidth: 1)
                        )
                        
                        Spacer()
                        
                        // Play Button
                        Button(action: {
                            engine.startNewGame()
                        }) {
                            HStack(spacing: 12) {
                                Image(systemName: "play.fill")
                                    .font(.system(size: 20, weight: .bold))
                                Text("JOUER")
                                    .font(.system(size: 20, weight: .bold, design: .rounded))
                            }
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 18)
                            .background(
                                LinearGradient(
                                    colors: [Color.cyan, Color(red: 0.2, green: 0.8, blue: 1.0)],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 18))
                            .shadow(color: .cyan.opacity(0.4), radius: 12, y: 4)
                        }
                        .padding(.horizontal, 36)
                        .padding(.bottom, 60)
                    }
                    .background(Color.black.opacity(0.55).ignoresSafeArea())
                }
                
                // PAUSE OVERLAY
                if engine.gameState == .paused {
                    VStack(spacing: 20) {
                        Text("PAUSE")
                            .font(.system(size: 32, weight: .black, design: .rounded))
                            .foregroundColor(.white)
                        
                        Button(action: {
                            engine.gameState = .playing
                            engine.triggerHaptic(.medium)
                        }) {
                            Text("REPRENDRE")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.black)
                                .frame(width: 200, height: 48)
                                .background(Color.cyan)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                        
                        Button(action: {
                            engine.startNewGame()
                        }) {
                            Text("RECOMMENCER")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                                .frame(width: 200, height: 48)
                                .background(Color.white.opacity(0.15))
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    .padding(32)
                    .background(Color.black.opacity(0.85))
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.2), lineWidth: 1)
                    )
                }
                
                // GAME OVER OVERLAY
                if engine.gameState == .gameOver {
                    VStack(spacing: 20) {
                        Text("GAME OVER")
                            .font(.system(size: 34, weight: .black, design: .rounded))
                            .foregroundColor(.red)
                            .shadow(color: .red.opacity(0.6), radius: 10)
                        
                        if engine.isNewHighScore {
                            HStack(spacing: 6) {
                                Image(systemName: "sparkles")
                                    .foregroundColor(.yellow)
                                Text("NOUVEAU RECORD !")
                                    .font(.system(size: 13, weight: .heavy))
                                    .foregroundColor(.yellow)
                            }
                            .padding(.horizontal, 14)
                            .padding(.vertical, 6)
                            .background(Color.yellow.opacity(0.15))
                            .clipShape(Capsule())
                        }
                        
                        // Score Summary Card
                        VStack(spacing: 14) {
                            HStack {
                                Text("Score Final")
                                    .foregroundColor(.gray)
                                Spacer()
                                Text("\(engine.score)")
                                    .font(.system(size: 22, weight: .bold, design: .rounded))
                                    .foregroundColor(.white)
                            }
                            
                            Divider().background(Color.white.opacity(0.1))
                            
                            HStack {
                                Text("Meilleur Score")
                                    .foregroundColor(.gray)
                                Spacer()
                                Text("\(engine.highScore)")
                                    .font(.system(size: 18, weight: .bold))
                                    .foregroundColor(.yellow)
                            }
                            
                            HStack {
                                Text("Astéroïdes Évités")
                                    .foregroundColor(.gray)
                                Spacer()
                                Text("\(engine.asteroidsDodged)")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(.white)
                            }
                            
                            HStack {
                                Text("Bonus Collectés")
                                    .foregroundColor(.gray)
                                Spacer()
                                Text("\(engine.powerUpsCollected)")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(.cyan)
                            }
                        }
                        .padding(20)
                        .background(Color.white.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                        
                        // Action Buttons
                        VStack(spacing: 12) {
                            Button(action: {
                                engine.startNewGame()
                            }) {
                                HStack(spacing: 10) {
                                    Image(systemName: "arrow.clockwise")
                                    Text("REJOUER")
                                }
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.black)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 16)
                                .background(Color.cyan)
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                            }
                            
                            Button(action: {
                                engine.gameState = .start
                            }) {
                                Text("MENU PRINCIPAL")
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(.gray)
                                    .padding(.vertical, 10)
                            }
                        }
                    }
                    .padding(28)
                    .background(Color(red: 0.08, green: 0.08, blue: 0.14).opacity(0.95))
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.15), lineWidth: 1)
                    )
                    .padding(.horizontal, 30)
                }
            }
            .onAppear {
                engine.setCanvasSize(geometry.size)
            }
            .onChange(of: geometry.size) { newSize in
                engine.setCanvasSize(newSize)
            }
        }
    }
}
