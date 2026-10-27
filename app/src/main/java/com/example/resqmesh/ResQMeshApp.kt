package com.example.resqmesh

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ResQMeshApp(viewModel: MeshViewModel = viewModel()) {
    val context = LocalContext.current

    // ---- ViewModel state, observed reactively ----
    val currentScreen by viewModel.currentScreen.collectAsState()
    val gpsState by viewModel.gpsState.collectAsState()
    val peerCount by viewModel.connectedPeersCount.collectAsState()
    val incomingAlertPacket by viewModel.incomingAlertPacket.collectAsState()
    val permissionsGranted by viewModel.permissionsGranted.collectAsState()

    // ---- Local UI state ----
    var selectedTriage by remember { mutableStateOf(1) } // 1: Medical, 2: Fire, 3: Trapped
    var pendingSosAfterPermission by remember { mutableStateOf(false) }

    // ---- Single permission launcher (handles both boot and SOS-fallback requests) ----
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (LocationTracker.hasCoreMeshPermissions(context)) {
            viewModel.onPermissionsGranted()
            // If the user pressed SOS before permissions were granted, fire it now
            if (pendingSosAfterPermission) {
                pendingSosAfterPermission = false
                viewModel.triggerSos(selectedTriage)
            }
        }
    }

    // ---- Boot-time permission request ----
    // Per Algorithm 1: the app must enter Passive Discovering Mode (Mule Mode) on launch.
    LaunchedEffect(Unit) {
        if (LocationTracker.hasCoreMeshPermissions(context)) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(LocationTracker.getRequiredPermissions())
        }
    }

    // ---- SOS trigger with permission fallback ----
    fun triggerSOS() {
        if (!LocationTracker.hasCoreMeshPermissions(context)) {
            // Permissions were denied at boot — re-request and queue the SOS
            pendingSosAfterPermission = true
            permissionLauncher.launch(LocationTracker.getRequiredPermissions())
        } else {
            // Ensure mesh is running (idempotent)
            if (!permissionsGranted) viewModel.onPermissionsGranted()
            viewModel.triggerSos(selectedTriage)
        }
    }

    // ---- UI Layout ----
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (currentScreen == AppScreen.RECEIVER_ALERT) Color(0xFFEF4444)
                else Color(0xFF0F172A)
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Bar Simulation
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "10:48",
                    color = if (currentScreen == AppScreen.RECEIVER_ALERT) Color.White else Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.SignalCellularAlt,
                        contentDescription = "No Signal",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(16.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "Bluetooth Mesh",
                        tint = Color(0xFF06B6D4),
                        modifier = Modifier.size(16.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = "Battery",
                        tint = if (currentScreen == AppScreen.RECEIVER_ALERT) Color.White else Color(0xFF94A3B8),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Screen Dispatcher — strictly driven by ViewModel state. No dummy toggles.
            when (currentScreen) {
                AppScreen.HOME -> HomeScreen(
                    gpsState = gpsState,
                    selectedTriage = selectedTriage,
                    onSelectTriage = { id -> selectedTriage = id },
                    onTriggerSos = { triggerSOS() }
                )
                AppScreen.BROADCASTING -> BroadcastingScreen(
                    peerCount = peerCount,
                    onCancel = { viewModel.cancelBroadcast() }
                )
                AppScreen.RECEIVER_ALERT -> ReceiverAlertScreen(
                    packet = incomingAlertPacket,
                    onDismiss = { viewModel.clearAlertPacket() }
                )
            }
        }

        // In-App Alarm Pop-Up Dialog when SOS packet arrives
        if (currentScreen == AppScreen.RECEIVER_ALERT && incomingAlertPacket != null) {
            val packet = incomingAlertPacket!!
            val triageName = when (packet.triage_type) {
                1 -> "Medical Issue"
                2 -> "Fire Emergency"
                3 -> "Person Trapped"
                else -> "General Emergency"
            }

            AlertDialog(
                onDismissRequest = { /* Modal: require user action */ },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Campaign,
                        contentDescription = "Alert",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(44.dp)
                    )
                },
                title = {
                    Text(
                        text = "\u26A0\uFE0F YOUR NEIGHBOUR NEEDS HELP!",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "An emergency SOS was received from a nearby peer via BLE Mesh!",
                            color = Color(0xFFE2E8F0),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Surface(
                            color = Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "\u26A0\uFE0F Emergency: $triageName",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "\uD83D\uDCCD Lat: ${String.format("%.4f", packet.origin_lat)}, Lon: ${String.format("%.4f", packet.origin_lon)}",
                                    color = Color(0xFF38BDF8),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "\uD83D\uDD17 Mesh Hops Remaining: ${packet.ttl}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.clearAlertPacket() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Turn Off Alarm & View Map", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                containerColor = Color(0xFF1E293B),
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
fun HomeScreen(
    gpsState: GpsState,
    selectedTriage: Int,
    onSelectTriage: (Int) -> Unit,
    onTriggerSos: () -> Unit
) {
    // ---- Derive GPS badge appearance from live state ----
    val gpsBadgeText = when (gpsState.status) {
        GpsStatus.LOCKED -> "GPS Locked"
        GpsStatus.ACQUIRING -> "GPS Acquiring..."
        GpsStatus.UNAVAILABLE -> "GPS Unavailable"
    }
    val gpsBadgeColor = when (gpsState.status) {
        GpsStatus.LOCKED -> Color(0xFF22C55E)      // Green
        GpsStatus.ACQUIRING -> Color(0xFFFBBF24)    // Amber
        GpsStatus.UNAVAILABLE -> Color(0xFFEF4444)  // Red
    }
    val gpsBadgeIcon = when (gpsState.status) {
        GpsStatus.LOCKED -> Icons.Default.LocationOn
        GpsStatus.ACQUIRING -> Icons.Default.LocationSearching
        GpsStatus.UNAVAILABLE -> Icons.Default.LocationOff
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // GPS Status Badge — driven by real GPS state, not hardcoded
        Surface(
            color = gpsBadgeColor.copy(alpha = 0.1f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = gpsBadgeIcon,
                    contentDescription = "GPS",
                    tint = gpsBadgeColor,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = gpsBadgeText,
                    color = gpsBadgeColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Triage Section
        Text(
            text = "OPTIONAL TRIAGE CONTEXT",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TriageChipItem(
                title = "Medical",
                icon = Icons.Default.MedicalServices,
                isActive = selectedTriage == 1,
                onClick = { onSelectTriage(1) },
                modifier = Modifier.weight(1f)
            )
            TriageChipItem(
                title = "Fire",
                icon = Icons.Default.LocalFireDepartment,
                isActive = selectedTriage == 2,
                onClick = { onSelectTriage(2) },
                modifier = Modifier.weight(1f)
            )
            TriageChipItem(
                title = "Trapped",
                icon = Icons.Default.Person,
                isActive = selectedTriage == 3,
                onClick = { onSelectTriage(3) },
                modifier = Modifier.weight(1f)
            )
        }

        // SOS Button Container (Centered)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "sos_shadow")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse"
            )

            Box(
                modifier = Modifier
                    .size(240.dp)
                    .scale(pulseScale),
                contentAlignment = Alignment.Center
            ) {
                // Outer glow rings matching CSS box-shadow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = 0.05f))
                )
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEF4444).copy(alpha = 0.2f))
                )
                // Main SOS Button (220dp)
                Button(
                    onClick = onTriggerSos,
                    modifier = Modifier.size(220.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text(
                        text = "SOS",
                        color = Color.White,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // Footer
        Text(
            text = "ResQMesh Protocol Ready.\nZero cellular required.",
            color = Color(0xFF64748B),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 60.dp)
        )
    }
}

@Composable
fun TriageChipItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .border(
                width = 2.dp,
                color = if (isActive) Color(0xFF06B6D4) else Color(0xFF334155),
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF06B6D4).copy(alpha = 0.1f) else Color(0xFF1E293B)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 16.dp, horizontal = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isActive) Color(0xFF06B6D4) else Color(0xFF94A3B8),
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                color = if (isActive) Color(0xFF06B6D4) else Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun BroadcastingScreen(
    peerCount: Int,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Broadcasting...",
            color = Color(0xFF06B6D4),
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Delay-Tolerant Payload active.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Radar Animation Matching CSS Ripple
        Box(
            modifier = Modifier
                .size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "radar")
            val ringScale1 by infiniteTransition.animateFloat(0.3f, 2f, infiniteRepeatable(tween(2000, 0, LinearEasing), RepeatMode.Restart), "r1")
            val ringAlpha1 by infiniteTransition.animateFloat(1f, 0f, infiniteRepeatable(tween(2000, 0, LinearEasing), RepeatMode.Restart), "a1")

            val ringScale2 by infiniteTransition.animateFloat(0.3f, 2f, infiniteRepeatable(tween(2000, 600, LinearEasing), RepeatMode.Restart), "r2")
            val ringAlpha2 by infiniteTransition.animateFloat(1f, 0f, infiniteRepeatable(tween(2000, 600, LinearEasing), RepeatMode.Restart), "a2")

            val ringScale3 by infiniteTransition.animateFloat(0.3f, 2f, infiniteRepeatable(tween(2000, 1200, LinearEasing), RepeatMode.Restart), "r3")
            val ringAlpha3 by infiniteTransition.animateFloat(1f, 0f, infiniteRepeatable(tween(2000, 1200, LinearEasing), RepeatMode.Restart), "a3")

            // Ripple Rings
            Box(modifier = Modifier.size(200.dp).scale(ringScale1).border(2.dp, Color(0xFF06B6D4).copy(alpha = ringAlpha1), CircleShape))
            Box(modifier = Modifier.size(200.dp).scale(ringScale2).border(2.dp, Color(0xFF06B6D4).copy(alpha = ringAlpha2), CircleShape))
            Box(modifier = Modifier.size(200.dp).scale(ringScale3).border(2.dp, Color(0xFF06B6D4).copy(alpha = ringAlpha3), CircleShape))

            // Radar Center
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF06B6D4)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BroadcastOnHome,
                    contentDescription = "Broadcast",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Stats Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatRow(label = "Payload Size:", value = "312 Bytes")
                StatRow(label = "Initial TTL:", value = "5 Hops")
                StatRow(label = "Peers Reached:", value = "$peerCount Nodes")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Cancel Button — returns to Home without killing the mesh
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(2.dp, Color(0xFF334155)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
        ) {
            Text(
                text = "Cancel Broadcast",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(text = value, color = Color(0xFF06B6D4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ReceiverAlertScreen(
    packet: PacketCache?,
    onDismiss: () -> Unit
) {
    // Derive all display values from the REAL incoming packet — no hardcoded mock data.
    val triageName = when (packet?.triage_type) {
        1 -> "Medical Issue"
        2 -> "Fire Emergency"
        3 -> "Person Trapped"
        else -> "General Emergency"
    }
    val ttlRemaining = packet?.ttl ?: 0
    val lat = packet?.origin_lat ?: 0.0
    val lon = packet?.origin_lon ?: 0.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Shaking Alert Icon Animation
        val infiniteTransition = rememberInfiniteTransition(label = "shake")
        val shakeOffset by infiniteTransition.animateFloat(
            initialValue = -5f,
            targetValue = 5f,
            animationSpec = infiniteRepeatable(
                animation = tween(100, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "shake_anim"
        )

        Icon(
            imageVector = Icons.Default.Campaign,
            contentDescription = "Alert",
            tint = Color.White,
            modifier = Modifier
                .size(80.dp)
                .offset(x = shakeOffset.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "NEIGHBOR\nEMERGENCY",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            lineHeight = 38.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Real coordinates from the sender's GPS, not hardcoded 0.0000
        Text(
            text = "\uD83D\uDCCD Lat: ${String.format("%.4f", lat)}, Lon: ${String.format("%.4f", lon)}",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(30.dp))

        // Alert Details Box — all values from the deserialized PacketCache
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.2f))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "• Type: $triageName", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(text = "• Received via: BLE Mesh", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(text = "• TTL Remaining: $ttlRemaining Hops", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Navigate Button
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = "Navigate",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Navigate to Help",
                    color = Color(0xFFEF4444),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}
