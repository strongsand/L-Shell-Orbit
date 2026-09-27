package io.github.strongsand.lshell.ar

import android.app.Activity
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.strongsand.lshell.DashDesign
import io.github.strongsand.lshell.DashFeedback
import io.github.strongsand.lshell.rememberDashHaptics
import io.github.strongsand.lshell.hapticClick
import io.github.strongsand.lshell.MonitorPreferences
import io.github.strongsand.lshell.core.model.DishySnapshot
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.hardware.camera2.CaptureRequest
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

sealed interface FaseCalibracao {
    data object Desativada : FaseCalibracao
    data object AguardandoGps : FaseCalibracao
    data class Caminhando(val distanciaMetros: Float, val amostrasColetadas: Int) : FaseCalibracao
    data class Concluida(val azimute: Float) : FaseCalibracao
}

@Composable
fun SkyARScreen(snapshot: DishySnapshot, hudVisible: Boolean, onToggleHud: () -> Unit, bottomInset: Dp, onExit: () -> Unit) {
    val context = LocalContext.current
    // A dark tonal HUD maintains contrast over daylight camera images, in either app theme.
    val colors = arColorScheme()
    val window = context.arActivity()?.window
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        val oldBehavior = controller?.systemBarsBehavior
        controller?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            controller?.apply {
                show(WindowInsetsCompat.Type.systemBars())
                if (oldStatus != null) isAppearanceLightStatusBars = oldStatus
                if (oldNavigation != null) isAppearanceLightNavigationBars = oldNavigation
                if (oldBehavior != null) systemBarsBehavior = oldBehavior
            }
        }
    }
    LaunchedEffect(window, hudVisible) {
        window?.let { activeWindow ->
            WindowCompat.getInsetsController(activeWindow, activeWindow.decorView).apply {
                if (hudVisible) show(WindowInsetsCompat.Type.systemBars())
                else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    MaterialTheme(colorScheme = colors) {
        SkyARContent(snapshot, hudVisible, onToggleHud, bottomInset, onExit)
    }
}

private tailrec fun Context.arActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.arActivity()
    else -> null
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun SkyARContent(snapshot: DishySnapshot, hudVisible: Boolean, onToggleHud: () -> Unit, bottomInset: Dp, onExit: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colorScheme = MaterialTheme.colorScheme
    val haptics = rememberDashHaptics()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val corMonetPrimaria = colorScheme.primary
    val corMonetTerciaria = colorScheme.tertiary
    val corMonetContainer = colorScheme.surfaceContainer
    val corMonetTexto = colorScheme.onSurface

    var temPermissoes by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        temPermissoes = res[Manifest.permission.CAMERA] == true &&
                res[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    LaunchedEffect(Unit) {
        if (!temPermissoes) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    var localizacaoUsuario by remember { mutableStateOf<Location?>(null) }
    var statusGps by remember { mutableStateOf("Buscando GPS...") }
    var faseCalibracao by remember { mutableStateOf<FaseCalibracao>(FaseCalibracao.Desativada) }

    LaunchedEffect(faseCalibracao is FaseCalibracao.Concluida) {
        if (faseCalibracao is FaseCalibracao.Concluida) {
            haptics.perform(DashFeedback.CONFIRM)
            kotlinx.coroutines.delay(3000L)
            faseCalibracao = FaseCalibracao.Desativada
        }
    }

    val amostrasRumo = remember { mutableListOf<Float>() }
    var pontoInicialGps by remember { mutableStateOf<Location?>(null) }
    var offsetAzimute by remember { mutableFloatStateOf(0f) }
    var rawSensorAzimute by remember { mutableFloatStateOf(0f) }
    var cameraElevacao by remember { mutableFloatStateOf(0f) }
    var cameraRoll by remember { mutableFloatStateOf(0f) }

    DisposableEffect(temPermissoes) {
        if (!temPermissoes) return@DisposableEffect onDispose {}

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val atual = localizacaoUsuario
                if (atual == null || loc.provider == LocationManager.GPS_PROVIDER || loc.accuracy <= atual.accuracy) {
                    localizacaoUsuario = loc
                    statusGps = if (loc.hasAccuracy()) "GPS Conectado (${loc.accuracy.toInt()}m)" else "GPS Conectado"
                }

                when (val fase = faseCalibracao) {
                    is FaseCalibracao.AguardandoGps -> {
                        if (loc.provider == LocationManager.GPS_PROVIDER && loc.hasAccuracy() && loc.accuracy <= 5f) {
                            pontoInicialGps = loc
                            amostrasRumo.clear()
                            faseCalibracao = FaseCalibracao.Caminhando(0f, 0)
                        }
                    }
                    is FaseCalibracao.Caminhando -> {
                        if (loc.provider != LocationManager.GPS_PROVIDER || !loc.hasAccuracy() || loc.accuracy > 5f) return
                        val p0 = pontoInicialGps ?: return
                        val dist = p0.distanceTo(loc)

                        if (loc.hasSpeed() && loc.speed >= 1f && loc.hasBearing()) {
                            amostrasRumo.add(loc.bearing)
                        }

                        faseCalibracao = FaseCalibracao.Caminhando(dist, amostrasRumo.size)

                        if (dist >= 20f && amostrasRumo.size >= 3) {
                            val azimuteFinal = calcularMediaAngular(amostrasRumo)
                            offsetAzimute = normalizarGraus(azimuteFinal - rawSensorAzimute)
                            faseCalibracao = FaseCalibracao.Concluida(azimuteFinal)
                        }
                    }
                    else -> {}
                }
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) statusGps = "GPS Desativado"
            }
        }

        try {
            val ultima = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ultima?.let {
                localizacaoUsuario = it
                statusGps = "Posição Inicial Obtida (${it.accuracy.toInt()}m)"
            }

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener)
            }
        } catch (_: SecurityException) {}

        onDispose { runCatching { locationManager.removeUpdates(locationListener) } }
    }

    var tipoSensor by remember { mutableStateOf("Detectando...") }

    val temBussolaFisica = remember {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
    }

    val declinacao = remember(localizacaoUsuario) {
        localizacaoUsuario?.let { loc ->
            GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()).declination
        } ?: 0f
    }
    val cameraAzimute = remember(rawSensorAzimute, offsetAzimute, temBussolaFisica, declinacao) {
        normalizarGraus(rawSensorAzimute + if (temBussolaFisica) declinacao else offsetAzimute)
    }

    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val satelitesReais = remember { mutableStateListOf<SateliteRA>() }
    val astrosReais = remember { mutableStateListOf<AstroObjeto>() }
    var statusSatelites by remember { mutableStateOf("Aguardando satélites...") }
    var catalogoTLE by remember { mutableStateOf<List<TLE>>(emptyList()) }
    var catalogRetry by remember { mutableIntStateOf(0) }
    var maxDistanceKm by rememberSaveable {
        mutableIntStateOf(MonitorPreferences.arMaxDistanceKm(context))
    }

    val satelitesEscaneados = remember { mutableStateSetOf<String>() }
    val animacoesDescoberta = remember { mutableStateMapOf<String, Long>() }
    var onlyDishView by rememberSaveable { mutableStateOf(false) }
    val dishAzimuth = snapshot.deviceInfo.boresightAzimuthDeg
    val dishElevation = snapshot.deviceInfo.boresightElevationDeg
    val dishOrientationAvailable = snapshot.isOnline && snapshot.deviceInfo.id.isNotBlank() &&
        dishAzimuth.isFinite() && dishElevation.isFinite() && dishElevation > 0f
    LaunchedEffect(dishOrientationAvailable) {
        if (!dishOrientationAvailable) onlyDishView = false
    }
    val displayedSatellites by remember(onlyDishView, dishAzimuth, dishElevation) {
        derivedStateOf {
            if (onlyDishView) satelitesReais.filter {
                isWithinDishView(it.azimute, it.elevacao, dishAzimuth, dishElevation)
            } else satelitesReais.toList()
        }
    }
    LaunchedEffect(catalogRetry) {
        statusSatelites = "Baixando catálogo Starlink..."
        val tles = StarlinkOrbitTracker.carregarTLEs(context)
        catalogoTLE = tles
        statusSatelites = if (tles.isNotEmpty()) "${tles.size} satélites carregados" else "Catálogo indisponível: ${StarlinkOrbitTracker.lastDownloadError}. Toque em atualizar"
    }

    LaunchedEffect(catalogoTLE, localizacaoUsuario, maxDistanceKm) {
        val loc = localizacaoUsuario ?: return@LaunchedEffect
        if (catalogoTLE.isEmpty()) return@LaunchedEffect

        while (true) {
            val (visiveis, astros) = withContext(Dispatchers.Default) {
                StarlinkOrbitTracker.calcularSatellitesVisiveis(
                    tles = catalogoTLE,
                    obsLat = loc.latitude,
                    obsLon = loc.longitude,
                    obsAltM = loc.altitude,
                    // Dishylink's live sky pass keeps satellites just above the horizon.
                    minElevacao = 2f,
                    apenasFovAntena = false,
                    distanciaMaximaKm = maxDistanceKm.takeIf { it > 0 }?.toFloat()
                ) to StarlinkOrbitTracker.calcularAstroPosicoes(loc.latitude, loc.longitude)
            }
            satelitesReais.clear()
            satelitesReais.addAll(visiveis)

            val totalFov = visiveis.size
            statusSatelites = "$totalFov no céu"

            astrosReais.clear()
            astrosReais.addAll(astros)

            kotlinx.coroutines.delay(1000L)
        }
    }

    DisposableEffect(Unit) {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        tipoSensor = when (rotationSensor?.type) {
            Sensor.TYPE_ROTATION_VECTOR -> "Bússola + Giro"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Giroscópio 3D"
            else -> "Sem sensor de orientação"
        }

        val listener = object : SensorEventListener {
            val rotMatrix = FloatArray(9)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

                val vx = -rotMatrix.get(2)
                val vy = -rotMatrix.get(5)
                val vz = -rotMatrix.get(8)

                val elDeg = Math.toDegrees(asin(vz.coerceIn(-1f, 1f)).toDouble()).toFloat()

                var azDeg = Math.toDegrees(atan2(vx.toDouble(), vy.toDouble())).toFloat()
                if (azDeg < 0) azDeg += 360f

                val rollDeg = Math.toDegrees(atan2(rotMatrix.get(6).toDouble(), rotMatrix.get(7).toDouble())).toFloat()

                rawSensorAzimute = azDeg
                cameraElevacao = elDeg
                cameraRoll = rollDeg
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        rotationSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        onDispose { sensorManager.unregisterListener(listener) }
    }

    val cameraBinding = remember {
        object {
            var active = true
            var provider: ProcessCameraProvider? = null
            var preview: Preview? = null
        }
    }
    DisposableEffect(lifecycleOwner) {
        cameraBinding.active = true
        onDispose {
            cameraBinding.active = false
            cameraBinding.preview?.let { preview -> runCatching { cameraBinding.provider?.unbind(preview) } }
        }
    }

    // Drive frames only during the short discovery effect. This spatial layer stays active when
    // fullscreen hides the HUD, so satellites do not disappear with the controls.
    var scanFrame by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val fovVerticalForScan = if (viewport.width > 0) 60f * viewport.height / viewport.width else 100f
    // Discovery and spatial rendering belong to the AR layer, not to the optional HUD.
    val visibleNames = displayedSatellites.filter { sat ->
        val az = ((sat.azimute - cameraAzimute + 540f) % 360f) - 180f
        abs(az) <= 60f / 1.4f && abs(sat.elevacao - cameraElevacao) <= fovVerticalForScan / 1.4f
    }.map { it.nome }
    LaunchedEffect(visibleNames, catalogRetry) {
        val now = SystemClock.uptimeMillis()
        val newlyDiscovered = visibleNames.filter { it !in satelitesEscaneados && it !in animacoesDescoberta }
        if (newlyDiscovered.isNotEmpty()) haptics.perform(DashFeedback.TAP)
        newlyDiscovered.forEach { animacoesDescoberta[it] = now }
    }
    LaunchedEffect(animacoesDescoberta.isNotEmpty()) {
        while (animacoesDescoberta.isNotEmpty()) {
            withFrameNanos {
                val now = SystemClock.uptimeMillis()
                scanFrame = now
                val complete = animacoesDescoberta.filterValues { now - it >= 500L }.keys.toList()
                complete.forEach { name -> satelitesEscaneados.add(name); animacoesDescoberta.remove(name) }
            }
        }
    }

    val anguloAlvoCluster = remember(displayedSatellites, cameraAzimute) {
        if (displayedSatellites.isEmpty()) 0f
        else {
            var sinSum = 0.0
            var cosSum = 0.0
            for (sat in displayedSatellites) {
                val rad = Math.toRadians((sat.azimute - cameraAzimute).toDouble())
                sinSum += sin(rad)
                cosSum += cos(rad)
            }
            normalizarGraus(Math.toDegrees(atan2(sinSum, cosSum)).toFloat())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(temBussolaFisica, hudVisible) {
                if (!temBussolaFisica && hudVisible) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        offsetAzimute = normalizarGraus(offsetAzimute - dragAmount * 0.15f)
                    }
                }
            }
    ) {
        // 1. CÂMERA AO VIVO
        if (temPermissoes) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            if (!cameraBinding.active) return@addListener
                            val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrElse {
                                cameraError = it.message ?: "Não foi possível iniciar a câmera."
                                return@addListener
                            }
                            cameraBinding.provider = cameraProvider
                            val preview = Preview.Builder().also { builder ->
                                Camera2Interop.Extender(builder).apply {
                                    setCaptureRequestOption(
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                                    )
                                    setCaptureRequestOption(
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                                    )
                                }
                            }.build().also {
                                it.setSurfaceProvider(surfaceProvider)
                            }
                            try {
                                cameraBinding.preview = preview
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview
                                )
                            } catch (e: Exception) {
                                cameraError = e.message ?: "Não foi possível abrir a câmera."
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. CAMADA DE REALIDADE AUMENTADA (SCANNER DO CÉU + MIRA 6-SIDED COOKIE + DIAMONDS)
        val textMeasurer = rememberTextMeasurer()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val centroX = w / 2f
            val centroY = h / 2f
            val nowMs = maxOf(scanFrame, SystemClock.uptimeMillis())

            val fovHorizontal = 60f
            val fovVertical = fovHorizontal * (h / w)

            // Ground is a half-plane, rotated with the same pose as the cardinal markers.
            val horizon = projectHorizon(w, h, cameraElevacao, cameraRoll)
            val horizonOrigin = Offset(horizon.x, horizon.y)
            val feather = 42.dp.toPx()
            drawRect(Brush.linearGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.52f)),
                start = horizonOrigin,
                end = horizonOrigin + Offset(horizon.normalX * feather, horizon.normalY * feather)))
            val span = hypot(w, h)
            val tangent = Offset(horizon.normalY * span, -horizon.normalX * span)
            drawLine(corMonetPrimaria.copy(alpha = 0.8f), horizonOrigin - tangent, horizonOrigin + tangent, 1.5.dp.toPx())

            // A) PONTOS CARDEAIS NA LINHA DO HORIZONTE
            val deltaElHorizonte = 0f - cameraElevacao
            if (abs(deltaElHorizonte) <= fovVertical / 1.3f) {
                val horizonY = centroY - (deltaElHorizonte / fovVertical) * h

                val pontosCardeais = listOf(
                    Triple("N", 0f, Color(0xFFFF5252)),
                    Triple("NE", 45f, corMonetTexto.copy(alpha = 0.8f)),
                    Triple("L", 90f, corMonetPrimaria),
                    Triple("SE", 135f, corMonetTexto.copy(alpha = 0.8f)),
                    Triple("S", 180f, corMonetTerciaria),
                    Triple("SO", 225f, corMonetTexto.copy(alpha = 0.8f)),
                    Triple("O", 270f, corMonetPrimaria),
                    Triple("NO", 315f, corMonetTexto.copy(alpha = 0.8f))
                )

                rotate(degrees = -cameraRoll, pivot = Offset(centroX, centroY)) {
                    for ((sigla, azPonto, corPonto) in pontosCardeais) {
                        var deltaAz = azPonto - cameraAzimute
                        while (deltaAz > 180f) deltaAz -= 360f
                        while (deltaAz < -180f) deltaAz += 360f

                        if (abs(deltaAz) <= fovHorizontal / 1.5f) {
                            val posX = centroX + (deltaAz / fovHorizontal) * w

                            drawLine(
                                color = corPonto,
                                start = Offset(posX, horizonY - 6.dp.toPx()),
                                end = Offset(posX, horizonY + 6.dp.toPx()),
                                strokeWidth = 2.dp.toPx()
                            )

                            if (posX >= 0f && posX <= w && horizonY >= 0f && horizonY <= h) drawText(
                                textMeasurer = textMeasurer,
                                text = sigla,
                                topLeft = Offset((posX - 8.dp.toPx()).coerceIn(0f, (w - 1f).coerceAtLeast(0f)), (horizonY - 26.dp.toPx()).coerceIn(0f, (h - 1f).coerceAtLeast(0f))),
                                style = TextStyle(
                                    color = corPonto,
                                    fontSize = if (sigla.length == 1) 14.sp else 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }

            // B) CORPOS CELESTES: SOL (VERY SUNNY SÓLIDO) E LUA
            rotate(degrees = -cameraRoll, pivot = Offset(centroX, centroY)) {
                for (astro in astrosReais) {
                    var deltaAz = astro.azimute - cameraAzimute
                    while (deltaAz > 180f) deltaAz -= 360f
                    while (deltaAz < -180f) deltaAz += 360f
                    val deltaEl = astro.elevacao - cameraElevacao

                    if (abs(deltaAz) <= fovHorizontal / 1.4f && abs(deltaEl) <= fovVertical / 1.4f) {
                        val posX = centroX + (deltaAz / fovHorizontal) * w
                        val posY = centroY - (deltaEl / fovVertical) * h

                        if (astro.tipo == TipoAstro.SOL) {
                            drawCircle(color = corMonetTerciaria.copy(alpha = 0.28f), radius = 25.dp.toPx(), center = Offset(posX, posY))
                            drawCircle(color = corMonetTerciaria, radius = 12.dp.toPx(), center = Offset(posX, posY))

                            if (posX + 24.dp.toPx() < w && posY >= 0f && posY < h) drawText(
                                textMeasurer = textMeasurer,
                                text = "☀️ Sol (${astro.elevacao.toInt()}°)",
                                topLeft = Offset(posX + 24.dp.toPx(), (posY - 10.dp.toPx()).coerceIn(0f, h - 1f)),
                                style = TextStyle(
                                    color = corMonetTerciaria,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    background = Color(0x99161A22)
                                )
                            )
                        } else {
                            drawCircle(color = Color(0x22FFFFFF), radius = 22.dp.toPx(), center = Offset(posX, posY))
                            drawCircle(color = Color(0xFFD6DFE8), radius = 10.dp.toPx(), center = Offset(posX, posY))
                            drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(posX, posY))

                            if (posX + 14.dp.toPx() < w && posY >= 0f && posY < h) drawText(
                                textMeasurer = textMeasurer,
                                text = "🌙 Lua (${astro.elevacao.toInt()}°)",
                                topLeft = Offset(posX + 14.dp.toPx(), (posY - 10.dp.toPx()).coerceIn(0f, h - 1f)),
                                style = TextStyle(
                                    color = Color(0xFFE0E6ED),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    background = Color(0x99161A22)
                                )
                            )
                        }
                    }
                }
            }

            // C) MIRA CENTRAL: '6-SIDED COOKIE' DO MATERIAL EXPRESSIVE COM RADAR SCAN
            if (cameraElevacao >= -10f) {
                val raioMira = 22.dp.toPx()

                rotate(degrees = -cameraRoll, pivot = Offset(centroX, centroY)) {
                    drawCircle(color = corMonetPrimaria.copy(alpha = 0.18f), radius = raioMira * 1.8f, center = Offset(centroX, centroY))
                    drawCircle(color = corMonetPrimaria, radius = raioMira, center = Offset(centroX, centroY), style = Stroke(2.dp.toPx()))

                    drawLine(
                        color = corMonetPrimaria,
                        start = Offset(centroX - 8.dp.toPx(), centroY),
                        end = Offset(centroX + 8.dp.toPx(), centroY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        color = corMonetPrimaria,
                        start = Offset(centroX, centroY - 8.dp.toPx()),
                        end = Offset(centroX, centroY + 8.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = Offset(centroX, centroY))
                }
            }

            // D) SCANNER DO CÉU E SATÉLITES DESCOBERTOS
            rotate(degrees = -cameraRoll, pivot = Offset(centroX, centroY)) {
                displayedSatellites.forEach { sat ->
                    var deltaAz = sat.azimute - cameraAzimute
                    while (deltaAz > 180f) deltaAz -= 360f
                    while (deltaAz < -180f) deltaAz += 360f
                    val deltaEl = sat.elevacao - cameraElevacao

                    if (abs(deltaAz) <= fovHorizontal / 1.4f && abs(deltaEl) <= fovVertical / 1.4f) {
                        val posX = centroX + (deltaAz / fovHorizontal) * w
                        val posY = centroY - (deltaEl / fovVertical) * h

                        if (!satelitesEscaneados.contains(sat.nome)) {
                            val tInicio = animacoesDescoberta[sat.nome] ?: nowMs
                            val progressoScan = ((nowMs - tInicio) / 500f).coerceIn(0f, 1f)

                            val curX = centroX + (posX - centroX) * progressoScan
                            val curY = centroY + (posY - centroY) * progressoScan
                            val posParticula = Offset(curX, curY)

                            drawLine(
                                color = corMonetPrimaria.copy(alpha = 0.5f * (1f - progressoScan * 0.3f)),
                                start = Offset(centroX, centroY),
                                end = posParticula,
                                strokeWidth = 1.8.dp.toPx()
                            )

                            drawCircle(
                                color = corMonetPrimaria,
                                radius = 5.dp.toPx(),
                                center = posParticula
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 2.5.dp.toPx(),
                                center = posParticula
                            )

                        }

                        if (satelitesEscaneados.contains(sat.nome)) {
                            val distCentro = hypot(posX - centroX, posY - centroY)
                            val emFoco = distCentro < 140.dp.toPx() || sat.conectado
                            val corSatSolida = if (sat.conectado) corMonetTerciaria else corMonetPrimaria
                            val raioSat = if (emFoco) 9.dp.toPx() else 6.dp.toPx()
                            drawCircle(color = corSatSolida.copy(alpha = 0.24f), radius = raioSat * 1.8f, center = Offset(posX, posY))
                            drawCircle(color = corSatSolida, radius = raioSat, center = Offset(posX, posY))
                            drawCircle(color = colorScheme.onPrimary, radius = 2.dp.toPx(), center = Offset(posX, posY))

                            if (emFoco) {
                                val label = "${sat.nome} (${sat.elevacao.toInt()}°)"
                                if (posX + 14.dp.toPx() < w && posY >= 0f && posY < h) drawText(
                                    textMeasurer = textMeasurer,
                                    text = label,
                                    topLeft = Offset(posX + 14.dp.toPx(), (posY - 10.dp.toPx()).coerceIn(0f, h - 1f)),
                                    style = TextStyle(
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (sat.conectado) FontWeight.Bold else FontWeight.Medium,
                                        background = Color(0xCC161A22)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // E) SETINHA NORMAL COM CORES DO MONET (QUANDO APONTADO PARA BAIXO)
            if (cameraElevacao < -12f) {
                val tamanhoSeta = 36.dp.toPx()

                rotate(degrees = anguloAlvoCluster, pivot = Offset(centroX, centroY)) {
                    drawCircle(color = corMonetContainer.copy(alpha = 0.9f), radius = tamanhoSeta, center = Offset(centroX, centroY))
                    val pathSeta = criarPathSetaNormal(centroX, centroY, tamanhoSeta)
                    drawPath(path = pathSeta, color = corMonetPrimaria)
                }

                val textoAviso = "Aponte para o céu"
                drawText(
                    textMeasurer = textMeasurer,
                    text = textoAviso,
                    topLeft = Offset(centroX - 58.dp.toPx(), centroY + tamanhoSeta + 20.dp.toPx()),
                    style = TextStyle(
                        color = corMonetTexto,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        background = Color(0xCC161A22)
                    )
                )
            }
        }

        AnimatedVisibility(hudVisible, enter = fadeIn(tween(DashDesign.motionMillis)),
            exit = fadeOut(tween(DashDesign.motionMillis))) {
            ArHud(
                azimuth = cameraAzimute.toInt(), elevation = cameraElevacao.toInt(),
                satelliteStatus = if (onlyDishView && catalogoTLE.isNotEmpty())
                    "${displayedSatellites.size} no campo estimado da antena" else statusSatelites,
                gpsStatus = statusGps, sensor = tipoSensor,
                hasLocation = localizacaoUsuario != null, hasCompass = temBussolaFisica,
                catalogReady = catalogoTLE.isNotEmpty(), phase = faseCalibracao,
                onlyDishView = onlyDishView, dishOrientationAvailable = dishOrientationAvailable,
                onToggleDishView = { onlyDishView = !onlyDishView },
                maxDistanceKm = maxDistanceKm,
                onDistanceSelected = { distance ->
                    maxDistanceKm = distance
                    MonitorPreferences.setArMaxDistanceKm(context, distance)
                },
                bottomInset = bottomInset, onExit = onExit, onHide = onToggleHud,
                onRefresh = { satelitesEscaneados.clear(); animacoesDescoberta.clear(); catalogRetry++ },
                onCalibrate = { faseCalibracao = FaseCalibracao.AguardandoGps },
                onCancelCalibration = { faseCalibracao = FaseCalibracao.Desativada; amostrasRumo.clear(); pontoInicialGps = null }
            )
        }
        AnimatedVisibility(!hudVisible,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            enter = fadeIn(tween(DashDesign.motionMillis)), exit = fadeOut(tween(DashDesign.motionMillis))) {
            ArIconButton(Icons.Rounded.FullscreenExit, "Mostrar interface da AR", onToggleHud,
                feedback = DashFeedback.TOGGLE_ON)
        }
        if (hudVisible && (!temPermissoes || cameraError != null)) {
            Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = DashDesign.section,
                color = colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (!temPermissoes) "Câmera e localização" else "Câmera indisponível", style = MaterialTheme.typography.titleLarge)
                    Text(if (!temPermissoes) "Permita o acesso para mostrar o céu na sua localização."
                        else cameraError.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    if (!temPermissoes) Button(onClick = hapticClick {
                        launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text("Permitir acesso") }
                }
            }
        }
    }
}

// =========================================================================
// FUNÇÕES DE FORMAS (PATH BUILDERS)
// =========================================================================

fun criarPathSetaNormal(cx: Float, cy: Float, tamanho: Float): Path {
    val path = Path()
    val r = tamanho / 2f
    path.moveTo(cx, cy - r)
    path.lineTo(cx + r * 0.72f, cy + r * 0.85f)
    path.lineTo(cx, cy + r * 0.35f)
    path.lineTo(cx - r * 0.72f, cy + r * 0.85f)
    path.close()
    return path
}

fun criarPathCookie(cx: Float, cy: Float, r: Float, numLados: Int, rotGraus: Float = 0f): Path {
    val path = Path()
    val steps = numLados * 8
    val amp = 0.16f
    for (i in 0 until steps) {
        val theta = (2.0 * PI * i / steps)
        val raioAtual = r * (1.0f + amp * cos(numLados * theta)).toFloat()
        val angTotal = theta + Math.toRadians(rotGraus.toDouble())
        val x = cx + raioAtual * cos(angTotal).toFloat()
        val y = cy + raioAtual * sin(angTotal).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

fun criarPathDiamond(cx: Float, cy: Float, r: Float): Path {
    val path = Path()
    path.moveTo(cx, cy - r)
    path.cubicTo(cx + r * 0.45f, cy - r * 0.45f, cx + r * 0.55f, cy - r * 0.35f, cx + r, cy)
    path.cubicTo(cx + r * 0.55f, cy + r * 0.35f, cx + r * 0.45f, cy + r * 0.45f, cx, cy + r)
    path.cubicTo(cx - r * 0.45f, cy + r * 0.45f, cx - r * 0.55f, cy + r * 0.35f, cx - r, cy)
    path.cubicTo(cx - r * 0.55f, cy - r * 0.35f, cx - r * 0.45f, cy - r * 0.45f, cx, cy - r)
    path.close()
    return path
}

fun criarPathVerySunny(cx: Float, cy: Float, r: Float, rotGraus: Float = 0f): Path {
    val path = Path()
    val numRaios = 8
    val steps = 64
    for (i in 0 until steps) {
        val theta = (2.0 * PI * i / steps)
        val raioAtual = r * (0.86f + 0.18f * cos(numRaios * theta)).toFloat()
        val angTotal = theta + Math.toRadians(rotGraus.toDouble())
        val x = cx + raioAtual * cos(angTotal).toFloat()
        val y = cy + raioAtual * sin(angTotal).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

fun normalizarGraus(v: Float): Float = ((v % 360f) + 360f) % 360f

fun calcularRumoGeodesico(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dL = Math.toRadians(lon2 - lon1)
    val y = sin(dL) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(dL)
    return normalizarGraus(Math.toDegrees(atan2(y, x)).toFloat())
}

fun calcularMediaAngular(angulos: List<Float>): Float {
    var sinSum = 0.0
    var cosSum = 0.0
    for (ang in angulos) {
        val rad = Math.toRadians(ang.toDouble())
        sinSum += sin(rad)
        cosSum += cos(rad)
    }
    val avgRad = atan2(sinSum, cosSum)
    return normalizarGraus(Math.toDegrees(avgRad).toFloat())
}
