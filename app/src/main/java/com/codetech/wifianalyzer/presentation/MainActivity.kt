package com.codetech.wifianalyzer
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.codetech.wifianalyzer.abstraction.WifiSignal
import com.codetech.wifianalyzer.ui.theme.WifiAnalyzerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ){ permissions ->
        permissions.all { it.value }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Check if permissions are already granted
        permissionsGranted = checkPermissions()
        //request permissions
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        )
        setContent {
            WifiAnalyzerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WifiAnalyzerScreen()
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewWifiAnalyzerScreen() {
    WifiAnalyzerTheme {
        WifiAnalyzerScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiAnalyzerScreen(){
    val context = LocalContext.current
    val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val scope = rememberCoroutineScope()

    var isAnalyzing by remember { mutableStateOf(false) }
    val wifiSignals = remember { mutableStateListOf<WifiSignal>() }
    var currentWifiName by remember { mutableStateOf("No Network") }
    var currentSignal by remember { mutableStateOf(-1) }
    var scanProgress by remember { mutableStateOf(0f) }

    fun getCurrentWifiName(): String {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null && wifiInfo.ssid != null) {
                wifiInfo.ssid.replace("\"", "")
            } else {
                "No Network"
            }
        } catch (e: SecurityException) {
            "Permission Denied"
        }
    }

    fun hasWifiPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    // WiFi scan receiver
    val wifiScanReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
                if (success && hasWifiPermissions()) {
                    try {
                        val scanResults = wifiManager.scanResults

                        // Get current connected network name
                        currentWifiName = getCurrentWifiName()

                        if (scanResults.isNotEmpty()) {
                            // Try to get signal strength of connected network first
                            val connectedWifiInfo = wifiManager.connectionInfo
                            val connectedSSID = connectedWifiInfo?.ssid?.replace("\"", "")

                            val connectedNetworkResult = scanResults.find {
                                it.SSID == connectedSSID && it.SSID.isNotEmpty()
                            }

                            val signalToUse = connectedNetworkResult ?: scanResults.maxByOrNull { it.level }

                            signalToUse?.let { result ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    val pingValue = measurePing("8.8.8.8") // or use your gateway
                                    currentSignal = result.level
                                    wifiSignals.add(
                                        WifiSignal(
                                            ssid = result.SSID.ifEmpty { "Unknown Network" },
                                            strength = result.level,
                                            timestamp = System.currentTimeMillis(),
                                            ping = pingValue
                                        )
                                    )

                                    // Keep only last 100 readings to prevent memory issues
                                    if (wifiSignals.size > 100) {
                                        wifiSignals.removeAt(0)
                                    }
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        // Handle permission denied
                        currentSignal = -100
                    }
                }
            }
        }
    }

    // Register/unregister receiver based on scanning state
    DisposableEffect(isAnalyzing) {
        if (isAnalyzing) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            context.registerReceiver(wifiScanReceiver, filter)
        }

        onDispose {
            if (isAnalyzing) {
                try {
                    context.unregisterReceiver(wifiScanReceiver)
                } catch (e: Exception) {
                    // Receiver might already be unregistered
                }
            }
        }
    }

    // Scanning loop
    LaunchedEffect(isAnalyzing) {
        if (isAnalyzing) {
            scope.launch {
                while (isAnalyzing) {
                    if (hasWifiPermissions()) {
                        try {
                            wifiManager.startScan()
                            for (i in 0..100 step 5) {
                                if (!isAnalyzing) break
                                scanProgress = i / 100f
                                delay(50)
                            }

                            delay(1000)
                        } catch (e: SecurityException) {
                            currentSignal = -100
                            break
                        } catch (e: Exception) {
                            currentSignal = -100
                            break
                        }
                    } else {
                        currentSignal = -100
                        isAnalyzing = false
                        break
                    }
                }
            }
        } else {
            scanProgress = 0f
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Wifi Analyzer",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ){ innerPadding->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
        ) {
            //Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "Wifi",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Network Analysis",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Monitor WiFi signal strength in real-time",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            //signal strength indicator - Fixed centering
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                SignalStrengthIndicator(
                    strength = currentSignal,
                    wifiName = currentWifiName,
                    isAnalyzing = isAnalyzing,
                    modifier = Modifier.size(250.dp)
                )
            }

            // Progress Indicator
            AnimatedVisibility(visible = isAnalyzing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Scanning...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = scanProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeCap = StrokeCap.Butt
                    )
                }
            }

            // Control Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        if (!wifiManager.isWifiEnabled) {
                            // You might want to show a dialog asking user to enable WiFi
                            currentSignal = -100
                        } else {
                            isAnalyzing = true
                            wifiSignals.clear()
                            scanProgress = 0f
                            currentSignal = -1
                        }
                    },
                    enabled = !isAnalyzing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start")
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = { isAnalyzing = false },
                    enabled = isAnalyzing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop")
                }
            }

            // Signal Graph
            if (wifiSignals.isNotEmpty()) {
                SignalGraph(
                    signals = wifiSignals,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(16.dp)
                )
            }

            // Statistics
            if (wifiSignals.isNotEmpty()) {
                SignalStatistics(
                    signals = wifiSignals,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
        }
    }
}

suspend fun measurePing(host: String = "8.8.8.8"): Int {
    return try {
        val start = System.currentTimeMillis()
        val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 $host")
        process.waitFor()
        val end = System.currentTimeMillis()
        (end - start).toInt()
    } catch (e: Exception) {
        -1
    }
}

@Composable
fun SignalStrengthIndicator(
    strength:Int,
    wifiName:String,
    isAnalyzing:Boolean,
    modifier: Modifier = Modifier
){
    val animatedRotation by animateFloatAsState(
        targetValue = if (isAnalyzing) 360f else 0f,
        label = "rotation"
    )
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val signalColor = getSignalColor(strength)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Outer circle
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = surfaceVariantColor,
                style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // WiFi Name
            Text(
                text = wifiName.ifEmpty { "Unknown Wi-Fi" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Signal strength text (top)
            Text(
                text = if (strength > -100) "$strength dBm" else "N/A",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = signalColor,
                textAlign = TextAlign.Center
            )
            // Signal bars (bottom)
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = "signal",
                modifier = Modifier
                    .size(100.dp)
                    .rotate(animatedRotation),
                tint = signalColor
            )
        }
    }
}

@Composable
fun SignalGraph(signals: List<WifiSignal>, modifier: Modifier = Modifier){
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 24f
            textAlign = android.graphics.Paint.Align.RIGHT
        }
    }

    OutlinedCard(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Signal Strength Over Time",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            ) {
                if (signals.size > 1) {
                    val points = signals.takeLast(50)
                    val xStep = size.width / (points.size - 1).coerceAtLeast(1).toFloat()
                    val yRange = 70f

                    val pathPoints = points.mapIndexed { index, wifiSignal ->
                        Offset(
                            x = index * xStep,
                            y = size.height - (((wifiSignal.strength + 100) / yRange) * size.height).coerceIn(0f, size.height)
                        )
                    }

                    // Draw grid lines with labels
                    for (i in -30 downTo -100 step 10) {
                        val y = size.height - (((i + 100) / yRange) * size.height)
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.3f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )

                        // Draw dBm labels
                        drawContext.canvas.nativeCanvas.drawText(
                            "$i dBm",
                            size.width - 10f,
                            y - 5f,
                            textPaint
                        )
                    }

                    // Draw signal line
                    for (i in 0 until pathPoints.size - 1) {
                        drawLine(
                            color = getSignalColor(points[i].strength),
                            start = pathPoints[i],
                            end = pathPoints[i + 1],
                            strokeWidth = 3f
                        )
                    }

                    pathPoints.forEach { point ->
                        drawCircle(
                            color = getSignalColor(points[pathPoints.indexOf(point)].strength),
                            radius = 4f,
                            center = point
                        )
                    }
                } else {
                    // Show message when no data
                    drawContext.canvas.nativeCanvas.drawText(
                        "Collecting data...",
                        size.width / 2f,
                        size.height / 2f,
                        textPaint
                    )
                }
            }
        }
    }
}

@Composable
fun SignalStatistics(signals: List<WifiSignal>, modifier: Modifier = Modifier) {
    if (signals.isEmpty()) return

    val strengths = signals.map { it.strength }.sorted()
    val average = signals.map { it.strength }.average().toInt()
    val min = signals.minOfOrNull { it.strength } ?: 0
    val max = signals.maxOfOrNull { it.strength } ?: 0
    val median = if (strengths.size % 2 == 0){
        ((strengths[strengths.size / 2] + strengths[(strengths.size / 2) -1]) /2)
    }else{
        strengths[strengths.size/2]
    }
    val stdDev = kotlin.math.sqrt(
        strengths.map { (it - average).toDouble().pow(2.0) }.average()
    ).toInt()
    val count = strengths.size
    val last = strengths.last()

    val avgPing = signals.map { it.ping }.average().toInt()
    val minPing = signals.minOfOrNull { it.ping } ?: 0
    val maxPing = signals.maxOfOrNull { it.ping } ?: 0

    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Row 1: signal stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatItem("Average", "$average dBm", getSignalColor(average))
                StatItem("Minimum", "$min dBm", getSignalColor(min))
                StatItem("Maximum", "$max dBm", getSignalColor(max))
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Row 2: median, std dev, samples
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatItem("Median", "$median dBm", getSignalColor(median))
                StatItem("Std Dev", "$stdDev", MaterialTheme.colorScheme.primary)
                StatItem("Samples", "$count", MaterialTheme.colorScheme.secondary)
            }

            Spacer(modifier = Modifier.height(12.dp))
            // Row 3: Last signal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                StatItem("Last", "$last dBm", getSignalColor(last))
            }
            Spacer(modifier = Modifier.height(20.dp))
            //Ping stats
            Text(
                text = "Ping",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                StatItem("Average", "$avgPing ms", MaterialTheme.colorScheme.tertiary)
                StatItem("Minimum", "$minPing ms", MaterialTheme.colorScheme.tertiary)
                StatItem("Maximum", "$maxPing ms", MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
fun StatItem(title: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

fun getSignalColor(strength: Int): Color {
    return when {
        strength >= -50 -> Color(0xFF4CAF50) // Excellent - Green
        strength >= -60 -> Color(0xFF8BC34A) // Good - Light Green
        strength >= -70 -> Color(0xFFFFC107) // Fair - Amber
        strength >= -80 -> Color(0xFFFF9800) // Poor - Orange
        else -> Color(0xFFF44336) // Very Poor - Red
    }
}

