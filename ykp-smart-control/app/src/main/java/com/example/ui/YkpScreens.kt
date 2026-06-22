package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.api.OtaJobResponse
import com.example.data.AutomationEntity
import com.example.data.DeviceEntity
import com.example.data.GroupEntity
import kotlinx.coroutines.launch

// Shared custom colors to establish our unified premium Indigo/Dark-slate theme
val PremiumGlowGreen = Color(0xFF00FF87)
val PremiumDeepSlate = Color(0xFF0F172A)
val PremiumCardBg = Color(0xFF1E293B)
val PremiumAccent = Color(0xFF6366F1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(viewModel: YkpViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }

    val errorToShow = viewModel.authErrorText

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(PremiumDeepSlate, Color(0xFF020617))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // YKP Branding Logo (Sleek stylized power emblem)
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .background(PremiumAccent.copy(alpha = 0.15f), CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(PremiumAccent, Color(0xFFEC4899))
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "YKP Logo",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "YKP SMART CONTROL",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                color = Color.White
            )

            Text(
                text = "Secure local and cloud device telemetry hub",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
                textAlign = TextAlign.Center
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRegisterMode) "Create YKP Account" else "Authorized Access Only",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; viewModel.authErrorText = null },
                        label = { Text("Developer/User Email") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("username_input"),
                        leadingIcon = { Icon(Icons.Default.Email, "Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PremiumAccent,
                            focusedLabelColor = PremiumAccent
                        ),
                        singleLine = true,
                        enabled = !viewModel.isAuthLoading
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; viewModel.authErrorText = null },
                        label = { Text("Security Pin code or Password") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Lock, "Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PremiumAccent,
                            focusedLabelColor = PremiumAccent
                        ),
                        singleLine = true,
                        enabled = !viewModel.isAuthLoading
                    )

                    if (errorToShow != null) {
                        Text(
                            text = errorToShow,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (viewModel.isAuthLoading) {
                        CircularProgressIndicator(
                            color = PremiumAccent,
                            modifier = Modifier.size(30.dp)
                        )
                    } else {
                        Button(
                            onClick = {
                                if (isRegisterMode) {
                                    viewModel.registerAccount(email, password) { success ->
                                        if (success) {
                                            isRegisterMode = false
                                            viewModel.authErrorText = "Registered successfully! Please log in."
                                        }
                                    }
                                } else {
                                    viewModel.authenticate(email, password) { success ->
                                        // navigated handled on success
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("submit_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = if (isRegisterMode) "Register New Account" else "Connect Node Controller",
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TextButton(
                        onClick = {
                            isRegisterMode = !isRegisterMode
                            viewModel.authErrorText = null
                        },
                        enabled = !viewModel.isAuthLoading
                    ) {
                        Text(
                            text = if (isRegisterMode) "Already have an account? Log in" else "New user? Create an account",
                            color = PremiumGlowGreen,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = {
                            // Quick simulation login bypass for developers
                            email = "developer@ykp.io"
                            password = "password123"
                            viewModel.authenticate(email, password) { }
                        },
                        enabled = !viewModel.isAuthLoading
                    ) {
                        Text(
                            "Simulation Mode Bypass",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Normal,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(viewModel: YkpViewModel) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") } // "All", "Online", "switch", "sensor", "motor"

    // ── Dynamic Bluetooth Permission Requests ─────────────────
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        if (granted) {
            viewModel.startBackgroundOobeScan()
        }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            launcher.launch(
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        } else {
            viewModel.startBackgroundOobeScan()
        }
    }

    // ── Diagnostic Auto-Prompt Dialog ────────────────────────
    if (viewModel.activeOfflineDiagnosticDevice != null) {
        val dev = viewModel.activeOfflineDiagnosticDevice!!
        AlertDialog(
            onDismissRequest = { viewModel.activeOfflineDiagnosticDevice = null },
            title = {
                Text(
                    "Cannot Reach Device",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    "Your smart switch '${dev.deviceName}' is offline. Would you like to connect directly via Bluetooth to diagnose and control the issue offline?",
                    color = Color.LightGray,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val devId = dev.deviceId
                        viewModel.activeOfflineDiagnosticDevice = null
                        viewModel.startDiagnosticsMode(devId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
                ) {
                    Text("Connect via Bluetooth", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.activeOfflineDiagnosticDevice = null }
                ) {
                    Text("Cancel", color = Color.LightGray)
                }
            },
            containerColor = PremiumCardBg,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "YKP DEVICE HUB",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            letterSpacing = 1.sp,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.signOut() }) {
                            Icon(Icons.Default.ExitToApp, "Logout", tint = Color.White)
                        }
                    },
                    actions = {
                        if (!viewModel.isDemoMode) {
                            IconButton(onClick = { viewModel.refreshBrokerDevices() }) {
                                if (viewModel.isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = PremiumGlowGreen,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, "Refresh", tint = PremiumGlowGreen)
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.navigateTo(ScreenRoute.Settings) }) {
                            Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = PremiumDeepSlate
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { viewModel.startProvisioningWizard() },
                    containerColor = PremiumAccent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.testTag("add_device_fab")
                ) {
                    Icon(Icons.Default.Add, "Provision New Device")
                }
            },
            containerColor = PremiumDeepSlate
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                // Environment mode status ticker
                val modeBg = if (viewModel.isDemoMode) Color(0xFFF59E0B).copy(alpha = 0.15f) else PremiumGlowGreen.copy(alpha = 0.15f)
                val modeText = if (viewModel.isDemoMode) "RUNNING LOCAL SIMULATION (DEMO)" else "CONNECTED TO CLOUD ROUTER SERVER UNIT"
                val modeBorder = if (viewModel.isDemoMode) Color(0xFFF59E0B) else PremiumGlowGreen
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(modeBg, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.navigateTo(ScreenRoute.Settings) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(modeBorder, CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = modeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = modeBorder
                        )
                    }
                }

                // Search Bar Component
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter devices by ID/Name...", color = Color.White.copy(0.4f)) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    leadingIcon = { Icon(Icons.Default.Search, "Search", tint = Color.LightGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = PremiumAccent,
                        unfocusedBorderColor = PremiumCardBg
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                // Filtering Row Scroll-list
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("All", "Online", "Switch", "Sensor", "Motor")
                    filters.forEach { filter ->
                        val active = selectedFilter.lowercase() == filter.lowercase()
                        FilterChip(
                            selected = active,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = PremiumCardBg,
                                labelColor = Color.LightGray,
                                selectedContainerColor = PremiumAccent,
                                selectedLabelColor = Color.White
                            ),
                            border = null
                        )
                    }
                }

                // Devices list matcher
                val filteredDevices = devices.filter { dev ->
                    // Search query match
                    val qMatch = dev.deviceName.contains(searchQuery, true) || dev.deviceId.contains(searchQuery, true)
                    // Filter type match
                    val fMatch = when (selectedFilter.lowercase()) {
                        "all" -> true
                        "online" -> dev.isOnline
                        "switch" -> dev.deviceType == "switch"
                        "sensor" -> dev.deviceType == "sensor"
                        "motor" -> dev.deviceType == "motor"
                        else -> true
                    }
                    qMatch && fMatch
                }

                if (filteredDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Warning,
                                "Empty Nodes",
                                modifier = Modifier.size(64.dp),
                                tint = Color.White.copy(0.3f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No smart nodes found",
                                color = Color.LightGray,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Tap + to provision a new device",
                                color = Color.White.copy(0.4f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredDevices, key = { it.deviceId }) { device ->
                            DeviceCard(
                                device = device,
                                onToggle = { viewModel.toggleDeviceState(device.deviceId) },
                                onClick = {
                                    if (device.isOnline) {
                                        viewModel.navigateTo(ScreenRoute.DeviceDetail(device.deviceId))
                                    } else {
                                        viewModel.activeOfflineDiagnosticDevice = device
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── OOBE Auto-Discovery Bottom Sheet ────────────────────
        AnimatedVisibility(
            visible = viewModel.showOobeBottomSheet,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("oobe_bottom_sheet"),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp, 4.dp)
                            .background(Color.Gray.copy(0.4f), RoundedCornerShape(2.dp))
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(PremiumAccent.copy(0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✨", fontSize = 24.sp)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "New YKP Smart Switch Found!",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "A nearby device '${viewModel.discoveredOobeDeviceName}' is broadcasting. Set up now to link it.",
                                fontSize = 12.sp,
                                color = Color.LightGray
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.showOobeBottomSheet = false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Dismiss")
                        }

                        Button(
                            onClick = {
                                viewModel.showOobeBottomSheet = false
                                viewModel.startProvisioningWizard()
                                if (viewModel.discoveredOobeDeviceName.isNotEmpty()) {
                                    viewModel.scannedBleDevices.clear()
                                    viewModel.scannedBleDevices.add(
                                        BleScannedDevice(
                                            name = viewModel.discoveredOobeDeviceName,
                                            deviceId = viewModel.discoveredOobeDeviceAddress.ifEmpty { "7C:9E:BD:F0:A9:C2" },
                                            rssi = -48,
                                            bluetoothDevice = viewModel.discoveredOobeDeviceHardware
                                        )
                                    )
                                    viewModel.selectBleDevice(viewModel.scannedBleDevices[0])
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumGlowGreen, contentColor = Color.Black),
                            modifier = Modifier.weight(1.2f)
                        ) {
                            Text("Set Up Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceEntity,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    val cardBg = if (device.isOnline) PremiumCardBg else PremiumCardBg.copy(alpha = 0.5f)
    val cardBorder = if (device.isOnline) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f))
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("device_item_card_${device.deviceId}"),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(16.dp),
        border = cardBorder,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Node symbol depending on its types
                val icon = when (device.deviceType) {
                    "switch" -> Icons.Default.Settings
                    "sensor" -> Icons.Default.Info
                    "motor" -> Icons.Default.Home
                    else -> Icons.Default.Settings
                }
                val iconColor = if (device.isOnline) PremiumAccent else Color.Gray
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(iconColor.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, device.deviceType, tint = iconColor, modifier = Modifier.size(18.dp))
                }

                // Online/Offline glow indicator dot & Update Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (device.isOnline && device.firmwareVer < "1.3.0") {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFEF4444), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "UPDATE",
                                fontSize = 7.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    
                    val dotColor = if (device.isOnline) PremiumGlowGreen else Color(0xFFEF4444)
                    val statusText = if (device.isOnline) "ONLINE" else "OFFLINE"
                    Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        statusText,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = dotColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = device.deviceName,
                fontSize = 14.sp,
                color = if (device.isOnline) Color.White else Color.Gray,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                text = "ID: ${device.deviceId}",
                fontSize = 10.sp,
                color = Color.LightGray.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace
            )

            if (device.isOnline) {
                val rssi = device.rssi
                val rtt = device.rttMs
                if (rssi != null || rtt != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (rssi != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📶", fontSize = 10.sp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "$rssi dBm",
                                    fontSize = 11.sp,
                                    color = Color.LightGray.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (rtt != null) {
                            val latencyColor = when {
                                rtt <= 100 -> Color(0xFF10B981) // Green
                                rtt <= 200 -> Color(0xFFFBBF24) // Yellow
                                else -> Color(0xFFEF4444) // Red
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⏱️", fontSize = 10.sp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "$rtt ms",
                                    fontSize = 11.sp,
                                    color = latencyColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Dual command controller block: switch state indicator / switch controller / read-only sensor
            if (device.isOnline) {
                if (device.deviceType == "sensor") {
                    val sensorValueText = if (device.relayState) "MOTION DETECTED" else "STANDBY"
                    val valueBgColor = if (device.relayState) Color(0xFFEF4444).copy(alpha = 0.2f) else PremiumGlowGreen.copy(alpha = 0.15f)
                    val valueTextColor = if (device.relayState) Color(0xFFEF4444) else PremiumGlowGreen
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .background(valueBgColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = sensorValueText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = valueTextColor
                            )
                        }
                        
                        Text(
                            text = "72°F",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White.copy(0.7f)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (device.relayState) "ACTIVE" else "INACTIVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = if (device.relayState) PremiumGlowGreen else Color.LightGray
                        )

                        Switch(
                            checked = device.relayState,
                            onCheckedChange = { onToggle() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PremiumGlowGreen,
                                checkedTrackColor = PremiumAccent
                            ),
                            modifier = Modifier.testTag("device_toggle_${device.deviceId}")
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", fontSize = 14.sp)
                    Text(
                        "Device Offline",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(viewModel: YkpViewModel) {
    val device by viewModel.activeDevice.collectAsStateWithLifecycle()
    val healthHistory by viewModel.activeDeviceHealthHistory.collectAsStateWithLifecycle()
    val latestHealth by viewModel.activeDeviceLatestHealth.collectAsStateWithLifecycle()
    
    var activeTab by remember { mutableStateOf(0) } // 0 = Control, 1 = Health, 2 = Info

    if (device == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(PremiumDeepSlate),
            contentAlignment = Alignment.Center
        ) {
            Text("No device details active", color = Color.LightGray)
        }
        return
    }

    val currentDev = device!!

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            currentDev.deviceName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White
                        )
                        Text(
                            "ID: ${currentDev.deviceId} • ${currentDev.deviceType.uppercase()}",
                            fontSize = 11.sp,
                            color = Color.LightGray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.deleteDevice(currentDev.deviceId) }
                    ) {
                        Icon(Icons.Default.Delete, "Delete Device", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PremiumDeepSlate
                )
            )
        },
        containerColor = PremiumDeepSlate
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Tab Row Configuration with Material 3 styling
            TabRow(
                selectedTabIndex = activeTab,
                containerColor = PremiumDeepSlate,
                contentColor = PremiumAccent,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                        color = PremiumAccent
                    )
                }
            ) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    text = { Text("CONTROL", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    selectedContentColor = PremiumAccent,
                    unselectedContentColor = Color.LightGray
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    text = { Text("HEALTH", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    selectedContentColor = PremiumAccent,
                    unselectedContentColor = Color.LightGray
                )
                Tab(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    text = { Text("INFO / OTA", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    selectedContentColor = PremiumAccent,
                    unselectedContentColor = Color.LightGray
                )
            }

            when (activeTab) {
                0 -> ControlDetailTab(currentDev, viewModel)
                1 -> HealthDetailTab(currentDev, latestHealth, healthHistory)
                2 -> InfoOtaDetailTab(currentDev, viewModel)
            }
        }
    }
}

@Composable
fun ControlDetailTab(device: DeviceEntity, viewModel: YkpViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Core Status Ring
        val ringColor = if (device.relayState) PremiumGlowGreen else Color.LightGray.copy(alpha = 0.3f)
        val ringGlowBrush = if (device.relayState) {
            Brush.radialGradient(colors = listOf(PremiumGlowGreen.copy(0.15f), Color.Transparent))
        } else {
            Brush.radialGradient(colors = listOf(Color.Transparent, Color.Transparent))
        }

        Box(
            modifier = Modifier
                .size(180.dp)
                .background(ringGlowBrush, CircleShape)
                .clickable { viewModel.toggleDeviceState(device.deviceId) },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .background(PremiumCardBg, CircleShape)
                    .testTag("big_control_ring_${device.deviceId}"),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Toggle",
                        tint = ringColor,
                        modifier = Modifier.size(50.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (device.relayState) "ON" else "OFF",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = if (device.relayState) PremiumGlowGreen else Color.LightGray
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { viewModel.setDeviceRelay(device.deviceId, true) },
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Turn ON", color = Color.White)
            }

            Button(
                onClick = { viewModel.setDeviceRelay(device.deviceId, false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Turn OFF", color = Color.White)
            }
        }

        // Network telemetry metrics card row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val rssi = device.rssi
            val rtt = device.rttMs
            
            // Signal Strength Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "SIGNAL STRENGTH",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("📶", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (rssi != null) "$rssi dBm" else "-- dBm",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (rssi != null) {
                            when {
                                rssi >= -60 -> "Excellent"
                                rssi >= -75 -> "Good"
                                rssi >= -85 -> "Fair"
                                else -> "Weak"
                            }
                        } else "No Connection",
                        fontSize = 11.sp,
                        color = if (rssi != null) {
                            when {
                                rssi >= -60 -> Color(0xFF10B981)
                                rssi >= -75 -> Color(0xFF3B82F6)
                                rssi >= -85 -> Color(0xFFFBBF24)
                                else -> Color(0xFFEF4444)
                            }
                        } else Color.Gray,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Latency (RTT) Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "LATENCY (RTT)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("⏱️", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (rtt != null) "$rtt ms" else "-- ms",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (rtt != null) {
                            when {
                                rtt <= 100 -> "Fast"
                                rtt <= 200 -> "Moderate"
                                else -> "Slow"
                            }
                        } else "No Data",
                        fontSize = 11.sp,
                        color = if (rtt != null) {
                            when {
                                rtt <= 100 -> Color(0xFF10B981)
                                rtt <= 200 -> Color(0xFFFBBF24)
                                else -> Color(0xFFEF4444)
                            }
                        } else Color.Gray,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().testTag("control_fallback_card"),
            colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "INTELLIGENT ROUTING & RESILIENCE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PremiumAccent,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(12.dp))
                
                // Communication Mode Selector UI dropdown
                var expanded by remember { mutableStateOf(false) }
                val selectedMode = viewModel.deviceCommModes[device.deviceId] ?: CommMode.AUTO
                val activeChannel = viewModel.deviceActiveChannels[device.deviceId] ?: "PENDING"
                
                Column {
                    Text(
                        "Communication Mode Selector",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.LightGray
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box {
                        Button(
                            onClick = { expanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedMode.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("▼", color = Color.White, fontSize = 12.sp)
                            }
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(PremiumCardBg)
                        ) {
                            CommMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.name, color = Color.White) },
                                    onClick = {
                                        viewModel.deviceCommModes[device.deviceId] = mode
                                        expanded = false
                                        if (mode != CommMode.AUTO) {
                                            viewModel.deviceActiveChannels[device.deviceId] = mode.name
                                        } else {
                                            viewModel.deviceActiveChannels[device.deviceId] = "PENDING"
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Display active/resolved channel label
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Active Channel:",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.LightGray
                    )
                    Text(
                        text = activeChannel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = PremiumGlowGreen,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Explicit BLE Direct Control",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            "Bypasses Wi-Fi / Cloud and routes commands directly over BLE profile",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                    Switch(
                        checked = viewModel.isBleMode,
                        onCheckedChange = { viewModel.isBleMode = it },
                        modifier = Modifier.testTag("ble_direct_control_switch")
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    "ACTIVE CONTROL PATHWAYS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.LightGray,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val active = selectedMode == CommMode.LOCAL_UDP || (selectedMode == CommMode.AUTO && !viewModel.isBleMode && device.isOnline && device.ipAddress?.isNotEmpty() == true)
                    Icon(
                        imageVector = if (active) Icons.Default.Check else Icons.Default.Info,
                        contentDescription = "UDP Active",
                        tint = if (active) PremiumGlowGreen else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Local UDP (Port 3333, SECURE AES-GCM)",
                        fontSize = 12.sp,
                        color = if (active) Color.White else Color.Gray
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val active = selectedMode == CommMode.CLOUD_WSS || (selectedMode == CommMode.AUTO && !viewModel.isBleMode && (!device.isOnline || device.ipAddress.isNullOrEmpty()))
                    Icon(
                        imageVector = if (active) Icons.Default.Check else Icons.Default.Info,
                        contentDescription = "Cloud Active",
                        tint = if (active) PremiumGlowGreen else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "Cloud API Proxy (OkHttp Server Live Sync)",
                        fontSize = 12.sp,
                        color = if (active) Color.White else Color.Gray
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val active = selectedMode == CommMode.BLE || (selectedMode == CommMode.AUTO && viewModel.isBleMode)
                    Icon(
                        imageVector = if (active) Icons.Default.Check else Icons.Default.Info,
                        contentDescription = "BLE Active",
                        tint = if (active) PremiumGlowGreen else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "BLE Fallback Layer (Interactive Gatt Write)",
                        fontSize = 12.sp,
                        color = if (active) Color.White else Color.Gray
                    )
                }
            }
        }

        // Live Sensor Active Simulator Interface (To make tests and triggers visible immediately!)
        if (device.deviceType == "sensor") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg.copy(0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Local Sensor State Simulator",
                        fontWeight = FontWeight.Bold,
                        color = PremiumGlowGreen,
                        fontSize = 14.sp
                    )
                    Text(
                        "Simulate motion/events to test local automation configurations on linked device nodes:",
                        fontSize = 11.sp,
                        color = Color.LightGray,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (device.relayState) "⚠️ MOTION TRIGGERED" else "🟢 NORMAL STATE",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = if (device.relayState) Color.Yellow else PremiumGlowGreen
                        )

                        Button(
                            onClick = { viewModel.simulateSensorActivity(device.deviceId, !device.relayState) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (device.relayState) Color.Gray else Color.Yellow
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                if (device.relayState) "Revert State" else "Trigger Motion",
                                color = Color.Black,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

fun formatUptime(uptimeSec: Long): String {
    val days = uptimeSec / (24 * 3600)
    val hours = (uptimeSec % (24 * 3600)) / 3600
    val mins = (uptimeSec % 3600) / 60
    return when {
        days > 0 -> "${days}d, ${hours}h, ${mins}m"
        hours > 0 -> "${hours}h, ${mins}m"
        else -> "${mins}m"
    }
}

@Composable
fun RssiSignalIcon(rssi: Int, color: Color) {
    val activeBars = when {
        rssi >= -55 -> 5
        rssi >= -65 -> 4
        rssi >= -75 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(
        modifier = Modifier.size(20.dp, 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..5) {
            val barHeight = (i * 2.2).dp
            val isFilled = i <= activeBars
            val barColor = if (isFilled) color else Color.White.copy(0.2f)
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(barHeight)
                    .background(barColor, shape = RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
fun HealthDetailTab(
    device: DeviceEntity,
    latestHealth: com.example.data.HealthEntity?,
    healthHistory: List<com.example.data.HealthEntity>
) {
    val nonNullHealth = latestHealth ?: com.example.data.HealthEntity(
        deviceId = device.deviceId,
        cpuUsage = 2.4,
        freeHeap = 184320L,
        minHeap = 170000,
        rssi = -71,
        temperature = 41.5,
        recordedAt = "",
        uptimeSec = 5200L,
        restartCount = 0
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Live Telemetry Status",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color.White
            )
        }

        // Numeric Health Badges Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HealthGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = "RSSI",
                    value = "${nonNullHealth.rssi} dBm",
                    sub = if (nonNullHealth.rssi > -67) "Excellent" else "Poor Connection",
                    color = if (nonNullHealth.rssi > -67) PremiumGlowGreen else Color.Yellow,
                    icon = Icons.Default.Settings,
                    customIcon = { RssiSignalIcon(nonNullHealth.rssi, if (nonNullHealth.rssi > -67) PremiumGlowGreen else Color.Yellow) }
                )

                HealthGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = "Free Heap",
                    value = "${nonNullHealth.freeHeap / 1024} KB Free RAM",
                    sub = "${((nonNullHealth.freeHeap.toFloat() / 250000) * 100).toInt()}% Initial RAM",
                    color = PremiumAccent,
                    icon = Icons.Default.Info
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HealthGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = "MCU Temperatura",
                    value = "${String.format("%.1f", nonNullHealth.temperature)} °C",
                    sub = "Safe margins",
                    color = Color(0xFFEF4444),
                    icon = Icons.Default.Info
                )

                HealthGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = "MCU CPU Load",
                    value = "${String.format("%.1f", nonNullHealth.cpuUsage)} %",
                    sub = "Low utilization",
                    color = Color.Cyan,
                    icon = Icons.Default.Refresh
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HealthGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = "MCU Uptime",
                    value = formatUptime(nonNullHealth.uptimeSec),
                    sub = "Since last boot",
                    color = PremiumGlowGreen,
                    icon = Icons.Default.Info
                )

                HealthGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = "MCU Restarts",
                    value = "${nonNullHealth.restartCount} Boots",
                    sub = "Device stability indicator",
                    color = Color.Magenta,
                    icon = Icons.Default.PlayArrow
                )
            }
        }

        // Dynamic visual Canvas charts
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Signal Strength Chronological Tracking",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    HealthTelemetryChart(history = healthHistory, type = "rssi")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "NVS Heap Allocations Activity",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    HealthTelemetryChart(history = healthHistory, type = "heap")
                }
            }
        }
    }
}

@Composable
fun HealthGaugeCard(
    modifier: Modifier,
    title: String,
    value: String,
    sub: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    customIcon: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White.copy(0.6f), fontSize = 11.sp)
                if (customIcon != null) {
                    customIcon()
                } else {
                    Icon(icon, title, tint = color, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(sub, fontSize = 9.sp, color = color, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
fun InfoOtaDetailTab(device: DeviceEntity, viewModel: YkpViewModel) {
    var checkUpdateClicked by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Metadata Specifications", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))

                SpecRow("IP Address", device.ipAddress ?: "No allocation (Local)")
                Divider(color = Color.White.copy(0.1f))
                SpecRow("Device MAC Address", "7C:9E:BD:F0:A9:C2")
                Divider(color = Color.White.copy(0.1f))
                SpecRow("Firmware Version", device.firmwareVer)
                Divider(color = Color.White.copy(0.1f))
                SpecRow("Capabilities code", device.capabilities.toString())
                Divider(color = Color.White.copy(0.1f))
                SpecRow("Registered area", device.location ?: "Default Area")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Firmware Over-The-Air (OTA) Manager", fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    "Deploy binary firmwares direct via target secure HTTP binary pipeline endpoints.",
                    fontSize = 11.sp,
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                if (viewModel.otaJobActive) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "STATUS: ${viewModel.otaStatusText}",
                            fontWeight = FontWeight.Bold,
                            color = PremiumAccent,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = viewModel.otaProgress,
                            color = PremiumGlowGreen,
                            trackColor = Color.LightGray.copy(0.2f),
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${(viewModel.otaProgress * 100).toInt()}% Transmitted",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                } else if (checkUpdateClicked) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("New Binary Ready!", fontWeight = FontWeight.Bold, color = PremiumGlowGreen, fontSize = 13.sp)
                            Text("Build v1.3.0 stable edition available", fontSize = 10.sp, color = Color.LightGray)
                        }

                        Button(
                            onClick = { viewModel.launchOtaUpdate(device.deviceId, "1.3.0") },
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Launch Upgrade", color = Color.White, fontSize = 11.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = { checkUpdateClicked = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
                    ) {
                        Text("Search firmware updates code repository", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun SpecRow(label: String, valStr: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(0.6f), fontSize = 12.sp)
        Text(valStr, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvisioningScreen(viewModel: YkpViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PROVISION NEW NODE", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PremiumDeepSlate)
            )
        },
        containerColor = PremiumDeepSlate
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Step Wizard State Bar Component
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (s in 1..4) {
                    val done = viewModel.provisionStep >= s
                    val activeColor = if (done) PremiumAccent else Color.Gray
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(activeColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(s.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        if (s < 4) {
                            val lineCol = if (viewModel.provisionStep > s) PremiumAccent else Color.Gray
                            Box(modifier = Modifier.width(30.dp).height(2.dp).background(lineCol))
                        }
                    }
                }
            }

            Text(
                text = "Step ${if (viewModel.provisionStep > 4) 4 else viewModel.provisionStep} of 4: " + when (viewModel.provisionStep) {
                    1 -> if (viewModel.isBleMode) "Search BLE Nodes" else "Pair Setup Hotspot"
                    2 -> if (viewModel.isBleMode) "Secure Handshake & WiFi" else "Scribe Wifi Details"
                    3 -> "Register Node Profile"
                    4, 5 -> "Online Verification loop"
                    else -> "Fallback Error Recovery"
                },
                fontWeight = FontWeight.Bold,
                color = PremiumAccent
            )

            Divider(color = Color.White.copy(0.1f))

            when (viewModel.provisionStep) {
                1 -> Step1SetupAp(viewModel)
                2 -> Step2WifiDetailsForm(viewModel)
                3 -> Step3DeviceRegister(viewModel)
                4, 5 -> Step4ConfirmOnline(viewModel)
                6 -> Step5FallbackRetryScreen(viewModel)
                else -> Step4ConfirmOnline(viewModel)
            }
        }
    }
}

@Composable
fun Step1SetupAp(viewModel: YkpViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selector Tab Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PremiumCardBg, RoundedCornerShape(8.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val modes = listOf(true to "⚡ BLE PROV (SUGGESTED)", false to "🌐 WIFI SOFTAP")
            modes.forEach { (isBle, labelText) ->
                val active = viewModel.isBleMode == isBle
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (active) PremiumAccent else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { viewModel.isBleMode = isBle }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelText,
                        color = if (active) Color.White else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (viewModel.isBleMode) {
            Text(
                "Info:\nNo need to switch to or connect your phone to any setup Wi-Fi AP! The application scans for nearby ESP32 nodes using Bluetooth LE on Service ID '021a9004-0382-4aea-bff4-6b3f1c5adfb4'.",
                color = Color.LightGray,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = viewModel.setupWaitingText,
                        fontSize = 12.sp,
                        color = PremiumGlowGreen,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (viewModel.isBleScanning) {
                        CircularProgressIndicator(
                            color = PremiumAccent,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            if (viewModel.scannedBleDevices.isNotEmpty()) {
                Text(
                    "Detected BLE Nodes Nearby:",
                    color = Color.White.copy(0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )

                viewModel.scannedBleDevices.forEach { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.selectBleDevice(device) },
                        colors = CardDefaults.cardColors(containerColor = PremiumCardBg.copy(0.5f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.1f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("📱", fontSize = 18.sp)
                                Column {
                                    Text(device.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Service: 021a9004-...", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("📶 ${device.rssi} dBm", color = PremiumGlowGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Box(
                                    modifier = Modifier
                                        .background(PremiumAccent, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("SELECT", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { viewModel.startBleScan() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                enabled = !viewModel.isBleScanning
            ) {
                Text(if (viewModel.isBleScanning) "Scanning..." else "Scan for BLE Nodes", color = Color.White)
            }

        } else {
            Text(
                "Instructions:\n1. Unplug and restart your ESP32 switch node so that it initiates Setup Mode.\n2. In your device settings, connect to the setup hotspot SSID:\n\nSSID: ${viewModel.setupApSsid}\nPASSWORD: ${viewModel.setupApPassword}",
                color = Color.LightGray,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(viewModel.setupWaitingText, fontSize = 11.sp, color = PremiumGlowGreen, textAlign = TextAlign.Center)
                }
            }

            Button(
                onClick = { viewModel.checkApConnectivity() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
            ) {
                Text("Poll SoftAP Status Endpoint", color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Step2WifiDetailsForm(viewModel: YkpViewModel) {
    if (viewModel.isProvisioningActive) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                "Transmitting credentials & verifying link status securely...",
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(PremiumAccent.copy(0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = PremiumGlowGreen,
                    modifier = Modifier.size(60.dp),
                    strokeWidth = 4.dp
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, PremiumAccent.copy(0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "LIVE ESP32 SETUP STATUS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PremiumAccent
                    )
                    Text(
                        text = viewModel.setupWaitingText,
                        fontSize = 13.sp,
                        color = PremiumGlowGreen,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (viewModel.isBleMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PremiumAccent.copy(alpha = 0.1f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PremiumAccent.copy(0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("🔒 BLE SECURED SESSION STATUS", color = PremiumAccent, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = viewModel.bleHandshakeProgress,
                            fontSize = 12.sp,
                            color = Color.White,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Wi-Fi Scanning list inside Step 2
                if (viewModel.isWifiScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = PremiumAccent)
                        Text("Scanning nearby wireless Access Points...", fontSize = 12.sp, color = Color.Gray)
                    }
                } else if (viewModel.scannedWifiNetworks.isNotEmpty()) {
                    Text(
                        "Nearby Wi-Fi Networks (Tap to select):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = PremiumGlowGreen,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.scannedWifiNetworks.forEach { wifi ->
                            val isSelected = viewModel.wifiSsidInput == wifi.ssid
                            val authIcon = if (wifi.auth != 0) "🔒" else "🔓"
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.wifiSsidInput = wifi.ssid },
                                label = { Text("${wifi.ssid} ${authIcon} (${wifi.rssi}dBm)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = PremiumCardBg,
                                    selectedContainerColor = PremiumAccent,
                                    selectedLabelColor = Color.White,
                                    labelColor = Color.LightGray
                                ),
                                border = null
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("No wireless scan results found.", fontSize = 11.sp, color = Color.Gray)
                        TextButton(onClick = { viewModel.startBleWifiScan() }) {
                            Text("Re-scan", color = PremiumAccent)
                        }
                    }
                }
            }

            Text(
                "Configure the local router details so that the node links securely to the cloud broker:",
                color = Color.LightGray,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Start)
            )

            OutlinedTextField(
                value = viewModel.wifiSsidInput,
                onValueChange = { viewModel.wifiSsidInput = it },
                label = { Text("Local Wi-Fi SSID") },
                modifier = Modifier.fillMaxWidth().testTag("wifi_ssid_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PremiumAccent
                )
            )

            OutlinedTextField(
                value = viewModel.wifiPasswordInput,
                onValueChange = { viewModel.wifiPasswordInput = it },
                label = { Text("Wi-Fi Password") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PremiumAccent
                )
            )

            Divider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 4.dp))

            Text(
                "Custom Device Configuration (ykp-config):",
                color = PremiumAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Start)
            )

            OutlinedTextField(
                value = viewModel.devIdInput,
                onValueChange = { viewModel.devIdInput = it },
                label = { Text("Device ID (Required)") },
                modifier = Modifier.fillMaxWidth().testTag("device_id_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PremiumAccent
                )
            )

            OutlinedTextField(
                value = viewModel.devNameInput,
                onValueChange = { viewModel.devNameInput = it },
                label = { Text("Device Name (Optional)") },
                modifier = Modifier.fillMaxWidth().testTag("device_name_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PremiumAccent
                )
            )

            OutlinedTextField(
                value = viewModel.serverUrlInput,
                onValueChange = { viewModel.serverUrlInput = it },
                label = { Text("Server URL (WebSocket)") },
                modifier = Modifier.fillMaxWidth().testTag("server_url_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = PremiumAccent
                )
            )

            Text(
                "Device Hardware Type (Required):",
                color = Color.LightGray,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.Start)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val types = listOf("switch", "sensor", "motor")
                types.forEach { t ->
                    val active = viewModel.devTypeInput == t
                    FilterChip(
                        selected = active,
                        onClick = { viewModel.devTypeInput = t },
                        label = { Text(t.uppercase(), fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = PremiumCardBg,
                            selectedContainerColor = PremiumAccent,
                            selectedLabelColor = Color.White
                        ),
                        border = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (viewModel.wifiSsidInput.isNotEmpty() && viewModel.devIdInput.isNotEmpty()) {
                        viewModel.applyWifiConfig()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                enabled = if (viewModel.isProvisioningActive) false else (if (viewModel.isBleMode) viewModel.isBleConnected else true)
            ) {
                Text(
                    text = if (viewModel.isBleMode) "Send Credentials & Config over BLE" else "Send Credentials to SoftAP",
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun Step3DeviceRegister(viewModel: YkpViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Assign details for your registered device in your database:",
            color = Color.LightGray,
            fontSize = 13.sp
        )

        OutlinedTextField(
            value = viewModel.devNameInput,
            onValueChange = { viewModel.devNameInput = it },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = PremiumAccent
            )
        )

        // Dropdown type descriptor selector
        Text(
            "Toggle Device Hardware classification:",
            color = Color.LightGray,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val types = listOf("switch", "sensor", "motor")
            types.forEach { t ->
                val active = viewModel.devTypeInput == t
                FilterChip(
                    selected = active,
                    onClick = { viewModel.devTypeInput = t },
                    label = { Text(t.uppercase(), fontSize = 10.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = PremiumCardBg,
                        selectedContainerColor = PremiumAccent,
                        selectedLabelColor = Color.White
                    ),
                    border = null
                )
            }
        }

        Button(
            onClick = { viewModel.completeDatabaseRegistry() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
        ) {
            Text("Complete Database Registry", color = Color.White)
        }
    }
}

@Composable
fun Step4ConfirmOnline(viewModel: YkpViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Waiting for the hardware node to connect to the cloud broker...",
            color = Color.LightGray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Time Remaining: ${viewModel.verificationTimeRemaining}s",
            color = PremiumAccent,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.testTag("verification_countdown_timer")
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PremiumCardBg, RoundedCornerShape(8.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Host Address Pinpointing:", color = Color.White, fontSize = 13.sp)
                Text(
                    text = if (viewModel.verificationStep1Passed) "🟢 PASSED" else "⏳ WAITING",
                    color = if (viewModel.verificationStep1Passed) PremiumGlowGreen else Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("step_1_indicator")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Local UDP Handshaking:", color = Color.White, fontSize = 13.sp)
                Text(
                    text = if (viewModel.verificationStep2Passed) "🟢 PASSED" else "⏳ WAITING",
                    color = if (viewModel.verificationStep2Passed) PremiumGlowGreen else Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("step_2_indicator")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Cloud Broker Synchrony:", color = Color.White, fontSize = 13.sp)
                Text(
                    text = if (viewModel.verificationStep3Passed) "🟢 PASSED" else "⏳ WAITING",
                    color = if (viewModel.verificationStep3Passed) PremiumGlowGreen else Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("step_3_indicator")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        CircularProgressIndicator(
            color = PremiumGlowGreen,
            modifier = Modifier.size(36.dp)
        )

        if (viewModel.isBleMode) {
            Button(
                onClick = {
                    viewModel.confirmProvision()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("confirm_provision_button"),
                enabled = viewModel.isConfirmProvisionEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PremiumGlowGreen,
                    disabledContainerColor = Color.DarkGray
                )
            ) {
                Text(
                    text = "Confirm Provision & Reboot",
                    color = if (viewModel.isConfirmProvisionEnabled) Color.Black else Color.LightGray,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                viewModel.navigateTo(ScreenRoute.Home)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("go_dashboard_button"),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
        ) {
            Text("Done, Go to Dashboard", color = Color.White)
        }
    }
}

@Composable
fun Step5FallbackRetryScreen(viewModel: YkpViewModel) {
    var ssid by remember { mutableStateOf(viewModel.wifiSsidInput) }
    var password by remember { mutableStateOf(viewModel.wifiPasswordInput) }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2C131A)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE04F5E))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "⚠️ ROUTE FAILURE DETECTED",
                    color = Color(0xFFE04F5E),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "Incorrect Wi-Fi password. Please re-enter.",
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("wifi_error_text_description")
                )
                if (viewModel.fallbackErrorMessage.isNotEmpty()) {
                    Text(
                        text = "Hardware Report: ${viewModel.fallbackErrorMessage}",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
        
        Text(
            "Re-Provision Fallback details (Scenario B Direct Transmit):",
            color = Color.LightGray,
            fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Start)
        )
        
        OutlinedTextField(
            value = ssid,
            onValueChange = { ssid = it },
            label = { Text("Corrected Wi-Fi SSID") },
            modifier = Modifier.fillMaxWidth().testTag("corrected_wifi_ssid_input"),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = PremiumAccent
            )
        )
        
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Corrected Password") },
            modifier = Modifier.fillMaxWidth().testTag("corrected_wifi_password_input"),
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = PremiumAccent
            )
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                viewModel.submitScenarioBRetry(ssid, password)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("submit_corrected_credentials_button"),
            colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
            enabled = !viewModel.isProvisioningActive
        ) {
            Text(
                text = if (viewModel.isProvisioningActive) "Re-Transmitting..." else "Submit Correct Credentials",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        
        TextButton(
            onClick = {
                viewModel.provisionStep = 1
                viewModel.fallbackActiveError = false
            }
        ) {
            Text("Cancel and restart setup from step 1", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(viewModel: YkpViewModel) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    val selectedDevices = remember { mutableStateListOf<String>() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("DEVICE GROUPS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, "Add Group", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = PremiumDeepSlate)
            )
        },
        containerColor = PremiumDeepSlate
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (groups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Refresh, "Empty Groups", modifier = Modifier.size(64.dp), tint = Color.White.copy(0.3f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No active groups configured", color = Color.LightGray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(groups, key = { it.groupId }) { g ->
                        GroupCard(group = g, onToggle = { state -> 
                            viewModel.toggleGroupState(g.groupId, state)
                        }, onDelete = {
                            viewModel.removeGroup(g.groupId)
                        })
                    }
                }
            }

            // Create Group Dialog block
            if (showCreateDialog) {
                AlertDialog(
                    onDismissRequest = { showCreateDialog = false },
                    title = { Text("Assemble New Node Group", color = Color.White) },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = groupName,
                                onValueChange = { groupName = it },
                                label = { Text("Group Name") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Text(
                                "Configure linked switch devices:",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )

                            // Lazy Grid selection nodes
                            Box(modifier = Modifier.height(180.dp).fillMaxWidth()) {
                                LazyColumn {
                                    items(devices) { d ->
                                        val active = selectedDevices.contains(d.deviceId)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (active) selectedDevices.remove(d.deviceId) else selectedDevices.add(d.deviceId)
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = active,
                                                onCheckedChange = {
                                                    if (active) selectedDevices.remove(d.deviceId) else selectedDevices.add(d.deviceId)
                                                }
                                            )
                                            Text(d.deviceName, color = Color.White, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (groupName.isNotEmpty()) {
                                    viewModel.createGroup(groupName, selectedDevices.toList())
                                    showCreateDialog = false
                                    groupName = ""
                                    selectedDevices.clear()
                                }
                            }
                        ) {
                            Text("Create Group")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCreateDialog = false }) {
                            Text("Discard", color = Color.Gray)
                        }
                    },
                    containerColor = PremiumCardBg
                )
            }
        }
    }
}

@Composable
fun GroupCard(
    group: GroupEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(group.groupName, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Text("Nodes: ${group.members}", color = Color.LightGray, fontSize = 11.sp)
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { onToggle(true) },
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
                ) {
                    Text("Group ON", color = Color.White, fontSize = 12.sp)
                }

                Button(
                    onClick = { onToggle(false) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                ) {
                    Text("Group OFF", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(viewModel: YkpViewModel) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()

    var showRuleDialog by remember { mutableStateOf(false) }
    var ruleName by remember { mutableStateOf("") }
    var trigDev by remember { mutableStateOf("") }
    var targetDev by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("AUTOMATION RULES", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                actions = {
                    IconButton(onClick = { showRuleDialog = true }) {
                        Icon(Icons.Default.Add, "Add Trigger", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = PremiumDeepSlate)
            )
        },
        containerColor = PremiumDeepSlate
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (rules.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Refresh, "Empty", modifier = Modifier.size(64.dp), tint = Color.White.copy(0.3f))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No rules configured", color = Color.LightGray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(rules, key = { it.ruleId }) { r ->
                        RuleCard(
                            rule = r,
                            onToggle = { active -> viewModel.toggleRule(r.ruleId, active) },
                            onDelete = { viewModel.removeRule(r.ruleId) }
                        )
                    }
                }
            }

            if (showRuleDialog) {
                AlertDialog(
                    onDismissRequest = { showRuleDialog = false },
                    title = { Text("Assemble Local Rule binding", color = Color.White) },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = ruleName,
                                onValueChange = { ruleName = it },
                                label = { Text("Rule name") },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White)
                            )

                            // Select Trigger device
                            Text("IF Trigger Device Node:", color = Color.LightGray, fontSize = 11.sp)
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                devices.forEach { d ->
                                    val act = trigDev == d.deviceId
                                    FilterChip(
                                        selected = act,
                                        onClick = { trigDev = d.deviceId },
                                        label = { Text(d.deviceName, fontSize = 11.sp) },
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }

                            // Select Target action device
                            Text("THEN Execute Action Device Node:", color = Color.LightGray, fontSize = 11.sp)
                            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                devices.filter { it.deviceId != trigDev }.forEach { d ->
                                    val act = targetDev == d.deviceId
                                    FilterChip(
                                        selected = act,
                                        onClick = { targetDev = d.deviceId },
                                        label = { Text(d.deviceName, fontSize = 11.sp) },
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (ruleName.isNotEmpty() && trigDev.isNotEmpty() && targetDev.isNotEmpty()) {
                                    // Default trigger: Sensor active (1) -> Target active (1)
                                    viewModel.addAutomationRule(ruleName, trigDev, 1, targetDev, 1)
                                    showRuleDialog = false
                                    ruleName = ""
                                    trigDev = ""
                                    targetDev = ""
                                }
                            }
                        ) {
                            Text("Create Rule")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRuleDialog = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    },
                    containerColor = PremiumCardBg
                )
            }
        }
    }
}

@Composable
fun RuleCard(
    rule: AutomationEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(rule.ruleName, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(0.2f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("IF ", color = PremiumGlowGreen, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Text("Node [${rule.triggerDevice}] triggers ON", color = Color.White, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("THEN ", color = PremiumAccent, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Text("Execute Relay ON on Node [${rule.targetDevice}]", color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (rule.isEnabled) "🟢 Rule Enabled" else "⚪ Rule Disabled",
                    fontSize = 11.sp,
                    color = if (rule.isEnabled) PremiumGlowGreen else Color.LightGray
                )

                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PremiumGlowGreen,
                        checkedTrackColor = PremiumAccent
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: YkpViewModel) {
    var editUrlInput by remember { mutableStateOf(viewModel.cloudServerUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YKP CONTROL PANEL", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PremiumDeepSlate)
            )
        },
        containerColor = PremiumDeepSlate
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // User section card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).background(PremiumAccent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, "Account", tint = Color.White)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text("Logged Account", color = Color.White.copy(0.6f), fontSize = 10.sp)
                            Text(viewModel.loggedInEmail ?: "Demo User", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { viewModel.signOut() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Disconnect Node Controller (Logout)")
                    }
                }
            }

            // Server section card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Broker REST Backend URL", fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = editUrlInput,
                        onValueChange = { editUrlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = { viewModel.setServerUrl(editUrlInput) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent)
                    ) {
                        Text("Save Server parameters")
                    }
                }
            }

            // Demostration mode settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Local Mock Engine (Demo)", fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                "Redirect networks logic to internally mocked devices to preview features without dynamic ESP32 nodes.",
                                fontSize = 10.sp,
                                color = Color.LightGray,
                                lineHeight = 14.sp
                            )
                        }

                        Switch(
                            checked = viewModel.isDemoMode,
                            onCheckedChange = { viewModel.toggleDemoMode(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PremiumGlowGreen,
                                checkedTrackColor = PremiumAccent
                            )
                        )
                    }
                }
            }

            // Network Diagnostics / Radio Scanner button card
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    viewModel.navigateTo(ScreenRoute.NetworkScanner)
                },
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, PremiumAccent.copy(0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📡 RF Wi-Fi & BLE Scanner Analyzer", fontWeight = FontWeight.Black, color = PremiumGlowGreen, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Scan local 2.4/5GHz Wi-Fi access points and Bluetooth LE beacons to view signal RSSI and raw hex packet payloads in real-time.",
                            fontSize = 11.sp,
                            color = Color.LightGray,
                            lineHeight = 15.sp
                        )
                    }
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Open RF Scanner",
                        tint = PremiumAccent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

// ── HEALTH DIAGNOSTIC PANEL COMPOSABLE (Zone 1, 2, 3) ───────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleDiagnosticsScreen(viewModel: YkpViewModel) {
    val logs = viewModel.diagnosticsLogs
    val isConnecting = viewModel.isDiagnosticsConnecting
    val isConnected = viewModel.isDiagnosticsConnected
    val relayState = viewModel.diagnosticsRelayState
    val waitingText = viewModel.diagnosticsWaitingText

    val listState = rememberScrollState()
    
    // Automatically auto-scroll log terminal screen to bottom
    LaunchedEffect(logs.size) {
        listState.animateScrollTo(listState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OFFLINE DIAGNOSTICS", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.disconnectDiagnostics() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close/Disconnect", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.disconnectDiagnostics() }) {
                        Icon(Icons.Default.Close, "Disconnect", tint = Color.Red)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PremiumDeepSlate)
            )
        },
        containerColor = PremiumDeepSlate
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // BLE Peripheral Service Information Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isConnected) PremiumGlowGreen.copy(0.3f) else Color.Gray.copy(0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "TARGET NODE MAC",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Text(
                                text = viewModel.activeDiagnosticsDeviceId ?: "Unknown MAC Address",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        // Connection Status Glow Dot
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = if (isConnected) PremiumGlowGreen else if (isConnecting) Color.Yellow else Color.Red
                            val textLabel = if (isConnected) "CONNECTED" else if (isConnecting) "PEERING..." else "DISCONNECTED"
                            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(textLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = dotColor)
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.08f))

                    Text(
                        text = waitingText,
                        fontSize = 12.sp,
                        color = if (isConnected) PremiumGlowGreen else Color.LightGray,
                        lineHeight = 16.sp
                    )

                    // Show connection indicator
                    if (isConnecting) {
                        LinearProgressIndicator(
                            color = PremiumAccent,
                            trackColor = Color.LightGray.copy(0.15f),
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                        )
                    }

                    if (!isConnected && !isConnecting) {
                        Button(
                            onClick = { viewModel.startDiagnosticsMode(viewModel.activeDiagnosticsDeviceId ?: "") },
                            colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Re-establish secure BLE session")
                        }
                    }
                }
            }

            // ZONE 1: Direct Local Control via Bluetooth RF
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isConnected) PremiumCardBg else PremiumCardBg.copy(0.5f)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, PremiumAccent.copy(0.2f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "ZONE 1: DIRECT LOCAL CONTROL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PremiumAccent
                        )
                    }

                    Text(
                        text = "Toggle the device output relay directly over Bluetooth command packets, bypassing local wifi network router systems.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 14.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (relayState) "OUTPUT ACTIVE" else "OUTPUT STANDBY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (relayState) PremiumGlowGreen else Color.LightGray
                        )

                        Switch(
                            checked = relayState,
                            onCheckedChange = { viewModel.toggleDiagnosticsRelay() },
                            enabled = isConnected,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PremiumGlowGreen,
                                checkedTrackColor = PremiumAccent
                            ),
                            modifier = Modifier.testTag("diag_local_relay_toggle")
                        )
                    }
                }
            }

            // ZONE 2: Network Re-Provisioning Gateway
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, PremiumAccent.copy(0.2f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📶", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "ZONE 2: NETWORK RE-PROVISIONING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PremiumAccent
                        )
                    }

                    Text(
                        text = "Need to associate this smart switch to a new Wi-Fi Access Point? Jump straight to the commissioning wizard.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        lineHeight = 14.sp
                    )

                    Button(
                        onClick = {
                            viewModel.disconnectDiagnostics()
                            viewModel.startProvisioningWizard()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Update Wi-Fi / Broker Settings", fontSize = 12.sp)
                    }
                }
            }

            // ZONE 3: Live Status Logs (Terminal terminal box)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF030712)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1F2937))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📁", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "ZONE 3: LIVE STATUS LOGS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF9CA3AF)
                            )
                        }

                        // Clear Logs Button/Triggers
                        TextButton(
                            onClick = { viewModel.diagnosticsLogs.clear() },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Clear", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    // Terminal View Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(Color(0xFF020617), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(listState)
                    ) {
                        Column {
                            if (logs.isEmpty()) {
                                Text(
                                    text = "Ready. Listening for BLE notifications...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color(0xFF4B5563)
                                )
                            } else {
                                logs.forEach { entry ->
                                    val col = if (entry.contains("[ERR]", true) || entry.contains("fail", true)) {
                                        Color(0xFFEF4444)
                                    } else if (entry.contains("[NOTIFY]", true) || entry.contains("[SUCCESS]", true)) {
                                        PremiumGlowGreen
                                    } else if (entry.contains("[GATT]", true)) {
                                        PremiumAccent
                                    } else {
                                        Color(0xFF34D399)
                                    }
                                    Text(
                                        text = entry,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = col,
                                        lineHeight = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScannerScreen(viewModel: YkpViewModel) {
    var activeTab by remember { mutableIntStateOf(0) } // 0: BLE Scanner, 1: Wi-Fi Scanner, 2: Packet Receiver
    val localBleDevices = viewModel.localScannedBleDevices
    val localWifiNetworks = viewModel.localScannedWifiNetworks
    val receiverLogs = viewModel.scannerReceiverLogs
    val isBleScanning = viewModel.isLocalBleScanning
    val isWifiScanning = viewModel.isLocalWifiScanning

    val logScrollState = rememberScrollState()

    // Auto-scroll logs to bottom as they arrive
    LaunchedEffect(receiverLogs.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RF NETWORK SCANNER & RECEIVER", color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.stopLocalBleScan()
                        viewModel.navigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearScannerLogs() }) {
                        Icon(Icons.Default.Delete, "Clear Logs", tint = Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PremiumDeepSlate)
            )
        },
        containerColor = PremiumDeepSlate
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // RF Metrics Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                border = androidx.compose.foundation.BorderStroke(1.dp, PremiumAccent.copy(0.2f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("BLE BEACONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text("${localBleDevices.size}", fontSize = 18.sp, fontWeight = FontWeight.Black, color = PremiumAccent)
                    }
                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color.White.copy(0.1f)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("WI-FI NETWORKS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text("${localWifiNetworks.size}", fontSize = 18.sp, fontWeight = FontWeight.Black, color = PremiumGlowGreen)
                    }
                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color.White.copy(0.1f)))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("RCV PACKETS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text("${receiverLogs.size}", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.Yellow)
                    }
                }
            }

            // Navigation Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PremiumCardBg, RoundedCornerShape(8.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val tabs = listOf("📱 BLE SCAN", "📶 WI-FI SCAN", "📁 PACKET MONITOR")
                tabs.forEachIndexed { index, label ->
                    val active = activeTab == index
                    val activeBg = if (active) PremiumAccent else Color.Transparent
                    val activeFg = if (active) Color.White else Color.Gray

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(activeBg, RoundedCornerShape(6.dp))
                            .clickable { activeTab = index }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = activeFg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Tab Content Window
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    0 -> { // BLE Scanner
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Nearby Bluetooth Peripherals:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (isBleScanning) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = PremiumAccent)
                                        Text("RECV...", color = PremiumAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        TextButton(onClick = { viewModel.stopLocalBleScan() }) {
                                            Text("STOP", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.startLocalBleScan() },
                                        colors = ButtonDefaults.buttonColors(containerColor = PremiumAccent),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Scan BLE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            if (localBleDevices.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No BLE Peripherals discovered.", color = Color.Gray, fontSize = 12.sp)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(localBleDevices.size) { i ->
                                        val dev = localBleDevices[i]
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.06f))
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        Text("📱", fontSize = 16.sp)
                                                        Column {
                                                            Text(dev.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                            Text(dev.address, color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                                        }
                                                    }
                                                    
                                                    // Signal level color indicator
                                                    val barsColor = if (dev.rssi >= -60) PremiumGlowGreen else if (dev.rssi >= -80) Color.Yellow else Color.Red
                                                    Text(
                                                        text = "${dev.rssi} dBm",
                                                        color = barsColor,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                                
                                                if (dev.primaryUuid != null) {
                                                    Text(
                                                        text = "Service UUID: ${dev.primaryUuid}",
                                                        color = PremiumAccent.copy(0.8f),
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }

                                                Card(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF030712))
                                                ) {
                                                    Column(modifier = Modifier.padding(6.dp)) {
                                                        Text("RAW PAYLOAD BYTE RECEIVED:", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                                        Text(
                                                            text = dev.rawBytes,
                                                            fontSize = 9.sp,
                                                            color = Color(0xFF10B981),
                                                            fontFamily = FontFamily.Monospace,
                                                            lineHeight = 11.sp,
                                                            maxLines = 2,
                                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> { // Wi-Fi Scanner
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Local Wi-Fi Access Points (AP):", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                if (isWifiScanning) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = PremiumGlowGreen)
                                        Text("WIFI SCAN...", color = PremiumGlowGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.startLocalWifiScan() },
                                        colors = ButtonDefaults.buttonColors(containerColor = PremiumGlowGreen),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Scan Wi-Fi", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                            }

                            if (localWifiNetworks.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No Wi-Fi APs scanned yet.", color = Color.Gray, fontSize = 12.sp)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(localWifiNetworks.size) { i ->
                                        val wifi = localWifiNetworks[i]
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = PremiumCardBg),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.06f))
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    val channelGhz = if (wifi.frequency > 4000) "5G" else "2.4G"
                                                    Text("📶", fontSize = 16.sp)
                                                    Column {
                                                        Text("${wifi.ssid} [${channelGhz}]", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                        Text("BSSID: ${wifi.bssid} | Caps: ${wifi.capabilities}", color = Color.Gray, fontSize = 10.sp)
                                                    }
                                                }
                                                val signalColor = if (wifi.rssi >= -60) PremiumGlowGreen else if (wifi.rssi >= -80) Color.Yellow else Color.Red
                                                Text(
                                                    text = "${wifi.rssi} dBm",
                                                    color = signalColor,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> { // Packet Receiver Logs
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Live Packet Logging Receiver Terminal:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                TextButton(onClick = { viewModel.clearScannerLogs() }) {
                                    Text("CLEAR TERMINAL", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(Color(0xFF020617), RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                                    .verticalScroll(logScrollState)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (receiverLogs.isEmpty()) {
                                        Text(
                                            "System initialized. Tap \"Scan BLE\" or \"Scan Wi-Fi\" to receive raw wireless telemetry packets...",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = Color.Gray,
                                            lineHeight = 14.sp
                                        )
                                    } else {
                                        receiverLogs.forEach { log ->
                                            val textCol = when {
                                                log.contains("[RECEIVER]", ignoreCase = true) || log.contains("[WIFI DEC]", ignoreCase = true) -> PremiumGlowGreen
                                                log.contains("[BLE RX]", ignoreCase = true) || log.contains("[UPDATE RX]", ignoreCase = true) -> PremiumAccent
                                                log.contains("[ERROR]", ignoreCase = true) -> Color(0xFFEF4444)
                                                else -> Color.LightGray
                                            }
                                            Text(
                                                text = log,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                color = textCol,
                                                lineHeight = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WifiNetworkScannerView(viewModel: YkpViewModel, gatt: android.bluetooth.BluetoothGatt?) {
    val networks = viewModel.scannedWifiNetworks
    val isScanning = viewModel.isWifiScanning
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Available Networks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Rescan Button - Enabled only when not currently scanning
            Button(
                onClick = { gatt?.let { viewModel.triggerWifiScan(it) } },
                enabled = !isScanning,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(text = "Rescan", style = MaterialTheme.typography.bodyMedium)
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (isScanning) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            Text(
                text = "Scanning airspace...",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (networks.isEmpty()) {
            Text(
                text = "No networks found. Tap Rescan to scan airspace.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            LazyColumn {
                items(networks) { network ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = network.ssid, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${network.rssi} dBm", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                network.rssi > -60 -> Color.Green
                                network.rssi > -80 -> Color.Yellow
                                else -> Color.Red
                            }
                        )
                    }
                }
            }
        }
    }
}


