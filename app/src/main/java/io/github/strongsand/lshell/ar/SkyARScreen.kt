package io.github.strongsand.lshell.ar

import io.github.strongsand.lshell.OrbitSymbols

import android.app.Activity
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.Surface
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.strongsand.lshell.R
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
    // Inherit the app palette, including dynamic color and AMOLED preferences.
    val colors = arColorScheme()
    val darkTheme = io.github.strongsand.lshell.LocalOrbitDark.current
    val window = context.arActivity()?.window
    DisposableEffect(window, darkTheme) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        val oldBehavior = controller?.systemBarsBehavior
        controller?.apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
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
    val displayView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val arActive = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val colorScheme = MaterialTheme.colorScheme
    val moonInk = if (io.github.strongsand.lshell.LocalExperience.current.monochrome) Color.White else Color(0xFFD6DFE8)
    val darkTheme = io.github.strongsand.lshell.LocalOrbitDark.current
    val satellitePainter = rememberVectorPainter(OrbitSymbols.SatelliteAlt)
    val generationColors = remember(colorScheme) { SatelliteGeneration.entries.associateWith { it.visualColor(colorScheme) } }
    val generationTints = remember(generationColors) { generationColors.mapValues { ColorFilter.tint(it.value) } }
    val haptics = rememberDashHaptics()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var cameraErrorText by remember { mutableStateOf<ArUiText?>(null) }
    val cameraError = cameraErrorText?.resolve { id, args -> stringResource(id, *args) }

    val corMonetPrimaria = colorScheme.primary
    val corMonetTerciaria = colorScheme.tertiary
    val corMonetContainer = colorScheme.surfaceContainer
    val corMonetTexto = colorScheme.onSurface
    // The AR canvas alternates between camera imagery and a black ground fade.
    // Pick the lighter theme content token instead of assuming a surface background.
    val compassInk = if (colorScheme.onSurface.luminance() >= colorScheme.surface.luminance())
        colorScheme.onSurface else colorScheme.surface

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
    var latestGpsFix by remember { mutableStateOf<Location?>(null) }
    var gpsProviderEnabled by remember { mutableStateOf(false) }
    val gpsQuality = rememberArGpsQuality(latestGpsFix, temPermissoes && gpsProviderEnabled)
    var statusGpsText by remember { mutableStateOf(ArUiText(R.string.editorial_gps_search)) }
    val statusGps = statusGpsText.resolve { id, args -> stringResource(id, *args) }
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
                if (loc.provider == LocationManager.GPS_PROVIDER) latestGpsFix = loc
                val atual = localizacaoUsuario
                if (atual == null || loc.provider == LocationManager.GPS_PROVIDER || loc.accuracy <= atual.accuracy) {
                    localizacaoUsuario = loc
                    statusGpsText = if (loc.hasAccuracy()) ArUiText(R.string.editorial_gps_accuracy, listOf(loc.accuracy.toInt())) else ArUiText(R.string.editorial_gps_available)
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
            override fun onProviderEnabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) gpsProviderEnabled = true
            }
            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) {
                    gpsProviderEnabled = false
                    latestGpsFix = null
                    statusGpsText = ArUiText(R.string.editorial_gps_disabled)
                }
            }
        }

        try {
            gpsProviderEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            latestGpsFix = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val ultima = latestGpsFix ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ultima?.let {
                localizacaoUsuario = it
                statusGpsText = if (it.hasAccuracy()) ArUiText(R.string.editorial_initial_accuracy, listOf(it.accuracy.toInt()))
                    else ArUiText(R.string.editorial_initial_location)
            }

            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, locationListener)
            }
        } catch (_: SecurityException) {}

        onDispose { runCatching { locationManager.removeUpdates(locationListener) } }
    }

    var tipoSensorText by remember { mutableStateOf(ArUiText(R.string.editorial_sensor_search)) }
    val tipoSensor = tipoSensorText.resolve { id, args -> stringResource(id, *args) }

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
    var statusSatelitesText by remember { mutableStateOf(ArUiText(R.string.editorial_satellite_loading)) }
    val statusSatelites = statusSatelitesText.resolve { id, args -> stringResource(id, *args) }
    var catalogoTLE by remember { mutableStateOf<List<TLE>>(emptyList()) }
    var catalogRetry by remember { mutableIntStateOf(0) }
    var maxDistanceKm by rememberSaveable {
        mutableIntStateOf(MonitorPreferences.arMaxDistanceKm(context))
    }
    var showNames by rememberSaveable { mutableStateOf(true) }
    var showTrails by rememberSaveable { mutableStateOf(false) }
    var reduceEffects by rememberSaveable { mutableStateOf(false) }
    val spatialEffects = io.github.strongsand.lshell.LocalOrbitMotion.current && !reduceEffects
    var selectedOrbitKey by rememberSaveable { mutableStateOf<String?>(null) }
    var linkEstimate by remember { mutableStateOf(ArLinkEstimate(ArCandidateStatus.NO_LOCATION)) }
    var estimateDishId by remember { mutableStateOf("") }
    var orbitFrameTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val latestSnapshot by rememberUpdatedState(snapshot)
    val latestObserver by rememberUpdatedState(localizacaoUsuario)
    val locationAllowed by rememberUpdatedState(temPermissoes)
    val currentLinkInput = remember(snapshot, localizacaoUsuario, temPermissoes, orbitFrameTimeMs, arActive) {
        arLinkInputs(snapshot, localizacaoUsuario, temPermissoes)
    }
    val effectiveEstimate = if (!io.github.strongsand.lshell.CandidatePublicationPolicy.ESTIMATED_AR_LINK)
        ArLinkEstimate(ArCandidateStatus.NO_LIVE_DISH)
        else ArLinkCandidatePolicy.inputStatus(currentLinkInput)?.let { ArLinkEstimate(it) }
        ?: if (estimateDishId == snapshot.deviceInfo.id && arActive) linkEstimate
        else ArLinkEstimate(ArCandidateStatus.NO_LIVE_DISH)

    val satelitesEscaneados = remember { mutableStateSetOf<String>() }
    val animacoesDescoberta = remember { mutableStateMapOf<String, Long>() }
    var overlayEnabled by rememberSaveable { mutableStateOf(false) }
    var overlayProjection by remember { mutableStateOf<DishyMapProjection?>(null) }
    var overlayCells by remember { mutableStateOf<List<DishyOverlayCell>>(emptyList()) }
    var overlayLoading by remember { mutableStateOf(false) }
    LaunchedEffect(overlayEnabled) {
        overlayProjection = null; overlayCells = emptyList()
        if (overlayEnabled) {
            overlayLoading = true
            val client = io.github.strongsand.lshell.core.network.grpc.DishyGrpcClient()
            try {
                val map = client.fetchObstructionMap().getOrNull()
                withContext(Dispatchers.Default) {
                    val projection = map?.let(DishyMapProjection::from)
                    projection to projection?.cells().orEmpty()
                }.let { (projection, cells) -> overlayProjection = projection; overlayCells = cells }
            } finally { client.close(); overlayLoading = false }
        }
    }
    var onlyDishView by rememberSaveable { mutableStateOf(false) }
    var generationFilter by rememberSaveable { mutableStateOf<SatelliteGeneration?>(null) }
    val generationLabels = SatelliteGeneration.entries.associateWith { generationLabel(it) }
    val dishAzimuth = snapshot.deviceInfo.boresightAzimuthDeg
    val dishElevation = snapshot.deviceInfo.boresightElevationDeg
    val dishOrientationAvailable = snapshot.isOnline && snapshot.deviceInfo.id.isNotBlank() &&
        dishAzimuth.isFinite() && dishElevation.isFinite() && dishElevation > 0f
    LaunchedEffect(dishOrientationAvailable) {
        if (!dishOrientationAvailable) onlyDishView = false
    }
    val displayedSatellites by remember(onlyDishView, dishAzimuth, dishElevation, generationFilter, maxDistanceKm) {
        derivedStateOf {
            satelitesReais.filter {
                it.classification.matchesGenerationFilter(generationFilter) &&
                    (maxDistanceKm == 0 || it.rangeKm?.let { range -> range <= maxDistanceKm } == true) &&
                    (!onlyDishView || isWithinDishView(it.azimute, it.elevacao, dishAzimuth, dishElevation))
            }
        }
    }
    LaunchedEffect(catalogRetry) {
        statusSatelitesText = ArUiText(R.string.editorial_satellite_downloading)
        val tles = StarlinkOrbitTracker.carregarTLEs(context)
        catalogoTLE = tles
        statusSatelitesText = if (tles.isNotEmpty()) ArUiText(R.string.editorial_satellite_count, listOf(tles.size)) else ArUiText(R.string.editorial_catalog_error)
    }

    LaunchedEffect(catalogoTLE, lifecycleOwner) {
        if (catalogoTLE.isEmpty()) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastForecastAt = 0L
            var forecasts = emptyMap<String, ArCandidateForecast>()
            var previousKey: String? = null
            var previousInput: ArLinkInputs? = null
            var previousDishId: String? = null
            var previousObserver: Location? = null
            while (true) {
                val loc = latestObserver
                if (loc == null) {
                    linkEstimate = ArLinkEstimate(ArCandidateStatus.NO_LOCATION)
                    kotlinx.coroutines.delay(1000L)
                    continue
                }
                val sample = latestSnapshot
                val input = arLinkInputs(sample, loc, locationAllowed)
                val nowMs = System.currentTimeMillis()
                val elapsedMs = SystemClock.elapsedRealtime()
                val inputChanged = previousDishId != sample.deviceInfo.id || previousInput?.let {
                    it.liveDish != input.liveDish || ArLinkCandidatePolicy.inputStatus(it) != ArLinkCandidatePolicy.inputStatus(input) ||
                        (if (it.dishAzimuth != null && it.dishElevation != null && input.dishAzimuth != null && input.dishElevation != null)
                            angularSeparationDegrees(it.dishAzimuth, it.dishElevation, input.dishAzimuth, input.dishElevation) > 2f
                        else it.dishAzimuth != input.dishAzimuth || it.dishElevation != input.dishElevation)
                } != false || previousObserver?.distanceTo(loc)?.let { it > 100f } != false
                val (visiveis, astros, estimate) = withContext(Dispatchers.Default) {
                    val visible = StarlinkOrbitTracker.calcularSatellitesVisiveis(
                        tles = catalogoTLE,
                        obsLat = loc.latitude,
                        obsLon = loc.longitude,
                        obsAltM = loc.altitude,
                        // Dishylink's live sky pass keeps satellites just above the horizon.
                        minElevacao = 2f,
                        apenasFovAntena = false,
                        nowMs = nowMs
                    )
                    // Rank the full visible sky, NEVER a generation/distance/camera-filtered subset.
                    if (io.github.strongsand.lshell.CandidatePublicationPolicy.ESTIMATED_AR_LINK &&
                        (inputChanged || elapsedMs - lastForecastAt >= ArLinkCandidatePolicy.ESTIMATE_INTERVAL_MS)) {
                        val shortlist = ArLinkCandidatePolicy.rank(visible, input, nowMs).take(ArLinkCandidatePolicy.MAX_FORECASTS)
                        forecasts = StarlinkOrbitTracker.candidateForecasts(catalogoTLE, shortlist.map { it.satellite },
                            loc.latitude, loc.longitude, loc.altitude, nowMs)
                        lastForecastAt = elapsedMs
                    }
                    val result = if (io.github.strongsand.lshell.CandidatePublicationPolicy.ESTIMATED_AR_LINK)
                        ArLinkCandidatePolicy.estimate(visible, input, nowMs, forecasts, previousKey.takeUnless { inputChanged })
                        else ArLinkEstimate(ArCandidateStatus.NO_LIVE_DISH)
                    Triple(visible, StarlinkOrbitTracker.calcularAstroPosicoes(loc.latitude, loc.longitude), result)
                }
                previousKey = estimate.candidates.firstOrNull()?.satellite?.orbitKey
                previousInput = input; previousDishId = sample.deviceInfo.id; previousObserver = loc
                linkEstimate = estimate; estimateDishId = sample.deviceInfo.id; orbitFrameTimeMs = nowMs
                satelitesReais.clear()
                satelitesReais.addAll(visiveis)

                val totalFov = visiveis.size
                statusSatelitesText = ArUiText(R.string.editorial_satellites_sky, listOf(totalFov))

                astrosReais.clear()
                astrosReais.addAll(astros)

                kotlinx.coroutines.delay(1000L)
            }
        }
    }

    DisposableEffect(sensorManager, displayView) {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        tipoSensorText = when (rotationSensor?.type) {
            Sensor.TYPE_ROTATION_VECTOR -> ArUiText(R.string.editorial_sensor_compass)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> ArUiText(R.string.editorial_sensor_gyro)
            else -> ArUiText(R.string.editorial_sensor_none)
        }

        val listener = object : SensorEventListener {
            val rotMatrix = FloatArray(9)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

                val pose = arCameraPose(rotMatrix, displayView.display?.rotation ?: Surface.ROTATION_0, cameraRoll)
                rawSensorAzimute = pose.azimuth
                cameraElevacao = pose.elevation
                cameraRoll = pose.roll
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
    LaunchedEffect(visibleNames, catalogRetry, spatialEffects) {
        if (!spatialEffects) {
            satelitesEscaneados.addAll(visibleNames)
            animacoesDescoberta.clear()
            return@LaunchedEffect
        }
        val now = SystemClock.uptimeMillis()
        val newlyDiscovered = visibleNames.filter { it !in satelitesEscaneados && it !in animacoesDescoberta }
        if (newlyDiscovered.isNotEmpty()) haptics.perform(DashFeedback.TAP)
        newlyDiscovered.forEach { animacoesDescoberta[it] = now }
    }
    LaunchedEffect(animacoesDescoberta.isNotEmpty(), spatialEffects) {
        if (!spatialEffects) return@LaunchedEffect
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
                                cameraErrorText = ArUiText(R.string.ui_ar_camera_start_error, diagnostic = it.message)
                                return@addListener
                            }
                            cameraBinding.provider = cameraProvider
                            val preview = Preview.Builder()
                                .setTargetRotation(displayView.display?.rotation ?: Surface.ROTATION_0).also { builder ->
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
                                cameraErrorText = ArUiText(R.string.ui_ar_camera_open_error, diagnostic = e.message)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                update = { previewView ->
                    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    cameraBinding.preview?.let { preview ->
                        if (preview.targetRotation != rotation) preview.targetRotation = rotation
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 3. CAMADA DE REALIDADE AUMENTADA (SCANNER DO CÉU + MIRA 6-SIDED COOKIE + DIAMONDS)
        val textMeasurer = rememberTextMeasurer()
        val focusRadius = with(LocalDensity.current) { 140.dp.toPx() }
        // At most one focus label. Reuse the identity for accessible text outside drawing.
        val focusedSatellite = remember(displayedSatellites, viewport, cameraAzimute, cameraElevacao, focusRadius) {
            if (viewport.width <= 0 || viewport.height <= 0) null else {
                fun distance(sat: SateliteRA): Float {
                    val az = ((sat.azimute - cameraAzimute + 540f) % 360f) - 180f
                    return hypot(az / 60f * viewport.width, (sat.elevacao - cameraElevacao) / 60f * viewport.width)
                }
                displayedSatellites.minByOrNull(::distance)?.takeIf { distance(it) < focusRadius }
            }
        }
        val sunTemplate = stringResource(R.string.ui_ar_sun)
        val moonTemplate = stringResource(R.string.ui_ar_moon)
        val pointSky = stringResource(R.string.ar_point_sky)
        val canvasLocale = LocalConfiguration.current.locales[0]
        val markerDescription = focusedSatellite?.let { sat ->
            stringResource(R.string.satellite_marker_description, sat.nome,
                generationLabels.getValue(sat.classification.generation),
                sat.catalogNumber ?: generationLabels.getValue(SatelliteGeneration.UNKNOWN),
                sat.azimute.toInt(), sat.elevacao.toInt())
        } ?: stringResource(R.string.satellite_marker_overview, displayedSatellites.size)
        val displayedKeys = remember(displayedSatellites) { displayedSatellites.map { it.orbitKey }.toSet() }

        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = markerDescription }) {
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
            // Fade across both sides of the existing projected horizon, including roll.
            // Black at every stop avoids a tinted fringe; clamp keeps the ground opaque.
            val transition = minOf(w, h) * if (darkTheme) 0.65f else 0.5f
            val skyShare = if (darkTheme) 0.65f else 0.2f
            val normal = Offset(horizon.normalX, horizon.normalY)
            val stops = if (darkTheme) arrayOf(
                0f to Color.Black.copy(alpha = 0f),
                0.18f to Color.Black.copy(alpha = 0.04f),
                0.38f to Color.Black.copy(alpha = 0.18f),
                0.58f to Color.Black.copy(alpha = 0.46f),
                0.76f to Color.Black.copy(alpha = 0.78f),
                0.9f to Color.Black.copy(alpha = 0.96f),
                1f to Color.Black
            ) else arrayOf(
                0f to Color.Black.copy(alpha = 0f),
                0.2f to Color.Black.copy(alpha = 0.03f),
                0.4f to Color.Black.copy(alpha = 0.15f),
                0.6f to Color.Black.copy(alpha = 0.42f),
                0.8f to Color.Black.copy(alpha = 0.8f),
                1f to Color.Black
            )
            drawRect(Brush.linearGradient(*stops,
                start = horizonOrigin - normal * (transition * skyShare),
                end = horizonOrigin + normal * (transition * (1f - skyShare))))
            val span = hypot(w, h)
            val tangent = Offset(horizon.normalY * span, -horizon.normalX * span)
            drawLine(Color.Black.copy(alpha = 0.55f), horizonOrigin - tangent, horizonOrigin + tangent, 3.dp.toPx())
            drawLine(compassInk.copy(alpha = 0.68f), horizonOrigin - tangent, horizonOrigin + tangent, 1.dp.toPx())

            // A) PONTOS CARDEAIS NA LINHA DO HORIZONTE
            val deltaElHorizonte = 0f - cameraElevacao
            if (abs(deltaElHorizonte) <= fovVertical / 1.3f) {
                val horizonY = centroY - (deltaElHorizonte / fovVertical) * h

                val pontosCardeais = listOf(
                    context.getString(R.string.editorial_compass_0) to 0f,
                    context.getString(R.string.editorial_compass_1) to 45f,
                    context.getString(R.string.editorial_compass_2) to 90f,
                    context.getString(R.string.editorial_compass_3) to 135f,
                    context.getString(R.string.editorial_compass_4) to 180f,
                    context.getString(R.string.editorial_compass_5) to 225f,
                    context.getString(R.string.editorial_compass_6) to 270f,
                    context.getString(R.string.editorial_compass_7) to 315f
                )

                rotate(degrees = -cameraRoll, pivot = Offset(centroX, centroY)) {
                    for ((sigla, azPonto) in pontosCardeais) {
                        var deltaAz = azPonto - cameraAzimute
                        while (deltaAz > 180f) deltaAz -= 360f
                        while (deltaAz < -180f) deltaAz += 360f

                        if (abs(deltaAz) <= fovHorizontal / 1.5f) {
                            val posX = centroX + (deltaAz / fovHorizontal) * w

                            val tickStart = Offset(posX, horizonY - 6.dp.toPx())
                            val tickEnd = Offset(posX, horizonY + 6.dp.toPx())
                            drawLine(Color.Black.copy(alpha = 0.7f), tickStart, tickEnd, 4.dp.toPx())
                            drawLine(compassInk.copy(alpha = 0.9f), tickStart, tickEnd, 2.dp.toPx())

                            if (posX >= 0f && posX <= w && horizonY >= 0f && horizonY <= h) drawText(
                                textMeasurer = textMeasurer,
                                text = sigla,
                                topLeft = Offset((posX - 8.dp.toPx()).coerceIn(0f, (w - 1f).coerceAtLeast(0f)), (horizonY - 26.dp.toPx()).coerceIn(0f, (h - 1f).coerceAtLeast(0f))),
                                style = TextStyle(
                                    color = compassInk,
                                    fontSize = if (sigla.length == 1) 14.sp else 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset.Zero, 4.dp.toPx())
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
                                text = String.format(canvasLocale, sunTemplate, astro.elevacao.toInt()),
                                topLeft = Offset(posX + 24.dp.toPx(), (posY - 10.dp.toPx()).coerceIn(0f, h - 1f)),
                                style = TextStyle(
                                    color = corMonetTerciaria,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    background = colorScheme.surfaceContainer.copy(alpha = 0.96f)
                                )
                            )
                        } else {
                            drawCircle(color = Color(0x22FFFFFF), radius = 22.dp.toPx(), center = Offset(posX, posY))
                            drawCircle(color = moonInk, radius = 10.dp.toPx(), center = Offset(posX, posY))
                            drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(posX, posY))

                            if (posX + 14.dp.toPx() < w && posY >= 0f && posY < h) drawText(
                                textMeasurer = textMeasurer,
                                text = String.format(canvasLocale, moonTemplate, astro.elevacao.toInt()),
                                topLeft = Offset(posX + 14.dp.toPx(), (posY - 10.dp.toPx()).coerceIn(0f, h - 1f)),
                                style = TextStyle(
                                    color = colorScheme.onSurface,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    background = colorScheme.surfaceContainer.copy(alpha = 0.96f)
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
                if (overlayEnabled) overlayCells.forEach { cell ->
                    val da = ((cell.azimuth - cameraAzimute + 540f) % 360f) - 180f
                    val de = cell.elevation - cameraElevacao
                    if (abs(da) <= fovHorizontal / 1.4f && abs(de) <= fovVertical / 1.4f) {
                        val point = Offset(centroX + da / fovHorizontal * w, centroY - de / fovVertical * h)
                        val color = when (cell.region) {
                            DishySkyRegion.CLEAR -> colorScheme.secondary
                            DishySkyRegion.PARTIAL -> colorScheme.tertiary
                            else -> colorScheme.error
                        }
                        val r = (w / 180f).coerceIn(3.dp.toPx(), 8.dp.toPx())
                        drawCircle(color.copy(alpha = .22f), r, point)
                        if (cell.region == DishySkyRegion.OBSTRUCTED)
                            drawLine(color.copy(alpha = .65f), point - Offset(r / 2, r / 2), point + Offset(r / 2, r / 2), 1.dp.toPx())
                    }
                }
                displayedSatellites.forEach { sat ->
                    var deltaAz = sat.azimute - cameraAzimute
                    while (deltaAz > 180f) deltaAz -= 360f
                    while (deltaAz < -180f) deltaAz += 360f
                    val deltaEl = sat.elevacao - cameraElevacao

                    if (abs(deltaAz) <= fovHorizontal / 1.4f && abs(deltaEl) <= fovVertical / 1.4f) {
                        val posX = centroX + (deltaAz / fovHorizontal) * w
                        val posY = centroY - (deltaEl / fovVertical) * h

                        if (spatialEffects && !satelitesEscaneados.contains(sat.nome)) {
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

                            val iconSize = 18.dp.toPx()
                            translate(posParticula.x - iconSize / 2f, posParticula.y - iconSize / 2f) {
                                scale(0.75f, pivot = Offset.Zero) {
                                    with(satellitePainter) { draw(Size(24.dp.toPx(), 24.dp.toPx()),
                                        colorFilter = generationTints.getValue(sat.classification.generation)) }
                                }
                            }

                        }

                        if (!spatialEffects || satelitesEscaneados.contains(sat.nome)) {
                            val distCentro = hypot(posX - centroX, posY - centroY)
                            val emFoco = sat === focusedSatellite && distCentro < focusRadius
                            val region = overlayProjection?.takeIf { overlayEnabled }?.region(sat.azimute, sat.elevacao)
                            val regionAlpha = if (region == DishySkyRegion.OUTSIDE || region == DishySkyRegion.UNMAPPED) .42f else 1f
                            val iconSize = (if (emFoco) 24.dp else 18.dp).toPx()
                            if (region == DishySkyRegion.CLEAR || region == DishySkyRegion.PARTIAL)
                                drawCircle(colorScheme.secondary.copy(alpha = .5f), iconSize * .85f, Offset(posX, posY), style = Stroke(1.dp.toPx()))
                            drawCircle(color = colorScheme.surfaceContainer.copy(alpha = 0.88f),
                                radius = iconSize * 0.65f, center = Offset(posX, posY))
                            translate(posX - iconSize / 2f, posY - iconSize / 2f) {
                                // Keep VectorPainter's raster cache at one size, even for small markers.
                                scale(if (emFoco) 1f else 0.75f, pivot = Offset.Zero) {
                                    with(satellitePainter) { draw(Size(24.dp.toPx(), 24.dp.toPx()), alpha = regionAlpha,
                                        colorFilter = generationTints.getValue(sat.classification.generation)) }
                                }
                            }

                            if (emFoco && showNames) {
                                val label = "${sat.nome} · ${generationLabels.getValue(sat.classification.generation)} (${sat.elevacao.toInt()}°)"
                                val labelX = posX + 18.dp.toPx()
                                if (labelX < w && posY >= 0f && posY < h) {
                                    val layout = textMeasurer.measure(label, style = TextStyle(
                                        color = colorScheme.onSurface,
                                        fontSize = 12.sp,
                                        fontWeight = if (sat.conectado) FontWeight.Bold else FontWeight.Medium,
                                        background = colorScheme.surfaceContainer.copy(alpha = 0.94f)
                                    ), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = (w - labelX).toInt().coerceAtLeast(1)))
                                    drawText(layout, topLeft = Offset(labelX,
                                        (posY - 10.dp.toPx()).coerceIn(0f, (h - layout.size.height).coerceAtLeast(0f))))
                                }
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

                val textoAviso = pointSky
                drawText(
                    textMeasurer = textMeasurer,
                    text = textoAviso,
                    topLeft = Offset(centroX - 58.dp.toPx(), centroY + tamanhoSeta + 20.dp.toPx()),
                    style = TextStyle(
                        color = corMonetTexto,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        background = colorScheme.surfaceContainer.copy(alpha = 0.96f)
                    )
                )
            }
        }

        if (io.github.strongsand.lshell.CandidatePublicationPolicy.ESTIMATED_AR_LINK)
            ArCandidateOverlay(effectiveEstimate, displayedKeys, cameraAzimute, cameraElevacao, cameraRoll,
                showNames, showTrails, reduceEffects, arActive)
        ArSatelliteTapLayer(displayedSatellites, cameraAzimute, cameraElevacao, cameraRoll,
            onSelect = { selectedOrbitKey = it })
        val selectedSatellite = satelitesReais.firstOrNull { it.orbitKey == selectedOrbitKey }
        LaunchedEffect(selectedOrbitKey, selectedSatellite == null) {
            if (selectedSatellite == null) selectedOrbitKey = null
        }
        if (selectedSatellite != null) ArSatelliteDetails(selectedSatellite,
            effectiveEstimate.candidates.firstOrNull { it.satellite.orbitKey == selectedOrbitKey },
            onDismiss = { selectedOrbitKey = null })

        AnimatedVisibility(hudVisible, modifier = Modifier.fillMaxSize(), enter = fadeIn(tween(DashDesign.motionMillis)),
            exit = fadeOut(tween(DashDesign.motionMillis))) {
            ArHud(
                azimuth = cameraAzimute.toInt(), elevation = cameraElevacao.toInt(),
                satelliteStatus = if (onlyDishView && catalogoTLE.isNotEmpty())
                    context.getString(R.string.editorial_satellites_field, displayedSatellites.size) else statusSatelites,
                gpsStatus = statusGps, sensor = tipoSensor,
                gpsQuality = gpsQuality, hasCompass = temBussolaFisica,
                catalogReady = catalogoTLE.isNotEmpty(), phase = faseCalibracao,
                overlayEnabled = overlayEnabled,
                overlayStatus = stringResource(if (!overlayEnabled) R.string.overlay_off else if (overlayLoading) R.string.overlay_loading
                    else if (overlayProjection != null) R.string.overlay_approximate else R.string.overlay_unavailable),
                onToggleOverlay = { overlayEnabled = !overlayEnabled },
                onlyDishView = onlyDishView, dishOrientationAvailable = dishOrientationAvailable,
                onToggleDishView = { onlyDishView = !onlyDishView },
                generationFilter = generationFilter, onGenerationSelected = { generationFilter = it },
                maxDistanceKm = maxDistanceKm,
                onDistanceSelected = { distance ->
                    maxDistanceKm = distance
                    MonitorPreferences.setArMaxDistanceKm(context, distance)
                },
                bottomInset = bottomInset, onExit = onExit, onHide = onToggleHud,
                onRefresh = { satelitesEscaneados.clear(); animacoesDescoberta.clear(); catalogRetry++ },
                onCalibrate = { faseCalibracao = FaseCalibracao.AguardandoGps },
                onCancelCalibration = { faseCalibracao = FaseCalibracao.Desativada; amostrasRumo.clear(); pontoInicialGps = null },
                visualControls = { ArVisualControls(showNames, showTrails, reduceEffects,
                    onNames = { showNames = !showNames }, onTrails = { showTrails = !showTrails },
                    onReduced = { reduceEffects = !reduceEffects }) },
                onInspectFocused = focusedSatellite?.let { sat -> { selectedOrbitKey = sat.orbitKey } },
                inspectFocusedLabel = focusedSatellite?.let { stringResource(R.string.ar_link_inspect, it.nome) }
            )
        }
        AnimatedVisibility(!hudVisible,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            enter = fadeIn(tween(DashDesign.motionMillis)), exit = fadeOut(tween(DashDesign.motionMillis))) {
            ArIconButton(OrbitSymbols.FullscreenExit, androidx.compose.ui.res.stringResource(R.string.editorial_show_ar), onToggleHud,
                feedback = DashFeedback.TOGGLE_ON)
        }
        if (hudVisible && io.github.strongsand.lshell.LocalExperience.current.technical && catalogoTLE.isEmpty()) {
            StarlinkOrbitTracker.lastDownloadError?.let { detail ->
                Text(detail.resolve { id, args -> stringResource(id, *args) }, Modifier.align(Alignment.BottomCenter).padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (hudVisible && (!temPermissoes || cameraError != null)) {
            Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = DashDesign.section,
                color = colorScheme.surfaceContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (!temPermissoes) androidx.compose.ui.res.stringResource(R.string.editorial_camera_location) else androidx.compose.ui.res.stringResource(R.string.editorial_camera_unavailable), style = MaterialTheme.typography.titleLarge)
                    Text(if (!temPermissoes) androidx.compose.ui.res.stringResource(R.string.editorial_ar_permission_note)
                        else stringResource(R.string.editorial_camera_error), style = MaterialTheme.typography.bodyMedium)
                    if (io.github.strongsand.lshell.LocalExperience.current.technical && cameraError != null)
                        Text(cameraError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (!temPermissoes) Button(onClick = hapticClick {
                        launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                    }) { Text(androidx.compose.ui.res.stringResource(R.string.editorial_allow_access)) }
                }
            }
        }
    }
}

// =========================================================================
// FUNÇÕES DE FORMAS (PATH BUILDERS)
// =========================================================================

private fun arLinkInputs(snapshot: DishySnapshot, location: Location?, permissions: Boolean): ArLinkInputs {
    val validLocation = location?.takeIf { permissions && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
    val ageMs = validLocation?.takeIf { it.elapsedRealtimeNanos > 0L }?.let {
        (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L
    } ?: -1L
    return ArLinkInputs(snapshot.isOnline && !snapshot.isFromCache && snapshot.deviceInfo.id.isNotBlank() &&
        snapshot.state == io.github.strongsand.lshell.core.model.DishState.CONNECTED,
        System.currentTimeMillis() - snapshot.timestamp, snapshot.deviceInfo.boresightAzimuthDeg,
        snapshot.deviceInfo.boresightElevationDeg, ageMs, validLocation?.takeIf { it.hasAccuracy() }?.accuracy)
}

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

fun normalizarGraus(v: Float): Float = ((v % 360f) + 360f) % 360f

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
