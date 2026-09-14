@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.delamcode.datatransfer

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.then
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults.extraLargeContainerSize
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.delamcode.datatransfer.ui.theme.DatatransferTheme
import com.delamcode.datatransfer.ui.theme.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.properties.Delegates


class MainActivity : ComponentActivity() {
    val mainViewModel: MainViewModel by viewModels { MainViewModel.Factory }
    var repo: Repo? = null
    lateinit var sharedPref: SharedPreferences
    var savedPort by Delegates.notNull<Int>()
    lateinit var savedFileHtml: String
    lateinit var savedBodyHtml: String

    // Permissions
    fun checkLocationOnToggleHotspot() {
        if (Build.VERSION.SDK_INT < 33) {
            val locationManager =
                applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager
            if (!locationManager.isLocationEnabled) {
                mainViewModel.showMissingPermissionAlert("Location services")
            } else {
                mainViewModel.onToggleHotspot()
            }
        } else {
            mainViewModel.onToggleHotspot()
        }
    }

    val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                checkLocationOnToggleHotspot()
            } else {
                mainViewModel.showMissingPermissionAlert(if (Build.VERSION.SDK_INT < 33) "Location access" else "Nearby WiFi Devices")
            }
        }

    fun onToggleHotspot() {
        val requiredPermission = if (Build.VERSION.SDK_INT < 33) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (ActivityCompat.checkSelfPermission(
                application, requiredPermission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(requiredPermission)
        } else {
            checkLocationOnToggleHotspot()
        }
    }

    fun onToggleServer() {
        if (!repo!!.state.value.isRunningServer) {
            val perm = "android.permission.ACCESS_LOCAL_NETWORK"
            if (ActivityCompat.checkSelfPermission(application, perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(perm)
            } else {
                val serverStartIntent = Intent(this, ServerService::class.java)
                this.startForegroundService(serverStartIntent)
            }
        } else {
            val serverStopIntent = Intent(this, ServerService::class.java)
            serverStopIntent.putExtra("Stop", true)
            startService(serverStopIntent)
        }
        mainViewModel.onToggleServer()
    }

    val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            mainViewModel.onOpenDirectory(uri)
        }

    val singleFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                mainViewModel.onOpenFile(it)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mainViewModel.wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        mainViewModel.wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        mainViewModel.wifiP2pChannel =
            mainViewModel.wifiP2pManager.initialize(this, mainLooper, null)

        repo = (application as DataTransferApp).repository
        sharedPref = this.getSharedPreferences("${this.packageName}", MODE_PRIVATE)
        savedPort = sharedPref.getInt("port", 8080)

        val tempFileHtml = sharedPref.getString("fileHtml", null)
        val tempBodyHtml = sharedPref.getString("bodyHtml", null)

        if (tempFileHtml == null) {
            sharedPref.edit {
                putString("fileHtml", defaultFileHtml)
                apply()
            }
            savedFileHtml = defaultFileHtml
        } else {
            savedFileHtml = tempFileHtml
        }

        if (tempBodyHtml == null) {
            sharedPref.edit {
                putString("bodyHtml", defaultBody)
                apply()
            }
            savedBodyHtml = defaultBody
        } else {
            savedBodyHtml = tempBodyHtml
        }

        handleShareIntent(intent)

        setContent {
            DatatransferTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    MainScreen(
                        innerPadding = innerPadding,
                        window = window,
                        snackbarHostState = snackbarHostState
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type != null) {
            val fileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            }
            fileUri?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val cachedUri = copyUriToCache(uri)
                        if (cachedUri != null) {
                            withContext(Dispatchers.Main) {
                                mainViewModel.onOpenFile(cachedUri)
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            mainViewModel.showSnackbar("Failed to process shared file: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri): Uri? {
        val returnCursor = contentResolver.query(uri, null, null, null, null) ?: return null
        val nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (!returnCursor.moveToFirst()) {
            returnCursor.close()
            return null
        }
        val rawFileName = returnCursor.getString(nameIndex)
        returnCursor.close()

        val cleanFileName = rawFileName.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")

        if (cleanFileName.trim().isEmpty()) return null

        val cacheFile = File(cacheDir, cleanFileName)
        contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(cacheFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return Uri.fromFile(cacheFile)
    }


    @Composable
    fun MainScreen(
        mainViewModel: MainViewModel = viewModel(),
        innerPadding: PaddingValues,
        window: Window,
        snackbarHostState: SnackbarHostState
    ) {
        val mainUiState by mainViewModel.uiState.collectAsState()
        val context = LocalContext.current
        var isFileMode by remember { mutableStateOf(mainUiState.uiRepoState.openFileUri != null) }

        LaunchedEffect(mainUiState.uiRepoState.openFileUri) {
            if (mainUiState.uiRepoState.openFileUri != null) {
                isFileMode = true
            }
        }

        LaunchedEffect(mainUiState.uiRepoState.openDirectoryUri) {
            if (mainUiState.uiRepoState.openDirectoryUri != null) {
                isFileMode = false
            }
        }

        LaunchedEffect(mainUiState.uiOnlyState.missingPermission) {
            if (mainUiState.uiOnlyState.missingPermission.isNotBlank()) {
                snackbarHostState.showSnackbar(
                    message = "${mainUiState.uiOnlyState.missingPermission} is a required permission. No data is collected."
                )
            }
        }

        LaunchedEffect(mainUiState.uiRepoState.snackbarMessage) {
            if (mainUiState.uiRepoState.snackbarMessage.isNotBlank()) {
                snackbarHostState.showSnackbar(
                    message = mainUiState.uiRepoState.snackbarMessage
                )
                mainViewModel.clearSnackbarMessage()
            }
        }
        if (mainUiState.uiRepoState.isCheckedHotspot || mainUiState.uiRepoState.isCheckedServer) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        @Composable
        fun Section(content: @Composable ColumnScope.() -> Unit) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content
                )
            }
        }

        @Composable
        fun withHaptic(type: HapticFeedbackType, onClick: () -> Unit): () -> Unit {
            val haptic = LocalHapticFeedback.current
            return {
                haptic.performHapticFeedback(type)
                onClick()
            }
        }

        @OptIn(ExperimentalMaterial3ExpressiveApi::class)
        @Composable
        fun CommonDialog(
            visible: Boolean = true,
            onDismissRequest: () -> Unit,
            title: String = "",
            text: String? = null,
            confirmButtonText: String? = null,
            onConfirm: (() -> Unit)? = null,
            dismissButtonText: String? = null,
            onDismiss: () -> Unit = onDismissRequest,
            resetButton: Boolean = false,
            onExtra: (() -> Unit)? = null,
            isDestructive: Boolean = false,
            content: @Composable (() -> Unit)? = null,
        ) {
            if (!visible) return
            val interactionSources = remember { List(3) { MutableInteractionSource() } }

            Dialog(
                onDismissRequest = onDismissRequest,
                properties = DialogProperties(dismissOnClickOutside = true)
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .wrapContentHeight(),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .widthIn(min = 280.dp)
                    ) {
                        if (title.isNotEmpty()) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        if (text != null) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (content != null) {
                            Column(modifier = Modifier.heightIn(max = 550.dp)) {
                                content()
                            }
                        }

                        if (confirmButtonText != null || dismissButtonText != null || resetButton) {
                            Spacer(modifier = Modifier.height(24.dp))
                            ButtonGroup(
                                overflowIndicator = { menuState ->
                                    ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
                                },
                            ) {
                                val scope = this
                                if (resetButton && onExtra != null) {
                                    customItem(
                                        buttonGroupContent = {
                                            OutlinedButton(
                                                onClick = withHaptic(HapticFeedbackType.Reject) {
                                                    onExtra()
                                                    onDismiss()
                                                },
                                                shapes = ButtonDefaults.shapes(),
                                                modifier = with(scope) {
                                                    Modifier
                                                        .weight(0.75f)
                                                        .animateWidth(interactionSources[0])
                                                },
                                                interactionSource = interactionSources[0],
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.outline_reset_wrench_24),
                                                    contentDescription = "Reset"
                                                )
                                            }
                                        },
                                        menuContent = { menuState ->
                                            DropdownMenuItem(
                                                text = { Text("Reset") },
                                                onClick = {
                                                    onExtra()
                                                    onDismiss()
                                                    menuState.dismiss()
                                                }
                                            )
                                        }
                                    )
                                }
                                if (dismissButtonText != null) {
                                    customItem(
                                        buttonGroupContent = {
                                            OutlinedButton(
                                                onClick = withHaptic(HapticFeedbackType.Reject) {
                                                    onDismiss()
                                                },
                                                shapes = ButtonDefaults.shapes(),
                                                modifier = with(scope) {
                                                    Modifier
                                                        .weight(1f)
                                                        .animateWidth(interactionSources[1])
                                                },
                                                interactionSource = interactionSources[1],
                                            ) {
                                                Text(
                                                    text = dismissButtonText,
                                                    style = MaterialTheme.typography.labelLarge
                                                )
                                            }
                                        },
                                        menuContent = { menuState ->
                                            DropdownMenuItem(
                                                text = { Text(dismissButtonText) },
                                                onClick = {
                                                    onDismiss()
                                                    menuState.dismiss()
                                                }
                                            )
                                        }
                                    )
                                }
                                if (confirmButtonText != null && onConfirm != null) {
                                    customItem(
                                        buttonGroupContent = {
                                            Button(
                                                onClick = withHaptic(HapticFeedbackType.Confirm) {
                                                    onConfirm()
                                                    onDismiss()
                                                },
                                                colors = if (isDestructive) {
                                                    ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.error,
                                                        contentColor = MaterialTheme.colorScheme.onError
                                                    )
                                                } else {
                                                    ButtonDefaults.buttonColors()
                                                },
                                                modifier = with(scope) {
                                                    Modifier
                                                        .weight(1f)
                                                        .animateWidth(interactionSources[2])
                                                },
                                                interactionSource = interactionSources[2],
                                                shapes = ButtonDefaults.shapes(),
                                            ) {
                                                Text(
                                                    text = confirmButtonText,
                                                )
                                            }
                                        },
                                        menuContent = { menuState ->
                                            DropdownMenuItem(
                                                text = { Text(confirmButtonText) },
                                                onClick = {
                                                    onConfirm()
                                                    onDismiss()
                                                    menuState.dismiss()
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }



        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (!mainUiState.uiRepoState.isCheckedServer) filePicker.launch(null) else mainViewModel.filePickerWarning(
                                    true
                                )
                            },
                            enabled = !isFileMode
                        ) {
                            Text("Open Dir")
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = isFileMode,
                            onCheckedChange = {
                                isFileMode = it
                                mainViewModel.onOpenDirectory(null)
                            },
                            enabled = !mainUiState.uiRepoState.isCheckedServer
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (!mainUiState.uiRepoState.isCheckedServer) singleFilePicker.launch(
                                    arrayOf("*/*")
                                ) else mainViewModel.filePickerWarning(
                                    true
                                )
                            },
                            enabled = isFileMode
                        ) {
                            Text("Open File")
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                mainUiState.uiRepoState.openDirectoryUri?.let { uri ->
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        val documentUri =
                                            DocumentsContract.buildDocumentUriUsingTree(
                                                uri,
                                                DocumentsContract.getTreeDocumentId(uri)
                                            )
                                        setDataAndType(
                                            documentUri,
                                            "vnd.android.document/directory"
                                        )
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        mainViewModel.showSnackbar("Could not open directory: ${e.message}")
                                    }
                                }
                            },
                            enabled = mainUiState.uiRepoState.openDirectoryUri != null
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.outline_open_in_new_24),
                                contentDescription = "Open directory"
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val selectionLabel = when {
                        mainUiState.uiRepoState.openFileUri != null -> {
                            "Selected File: " + (mainUiState.uiRepoState.openFileUri?.lastPathSegment
                                ?: "Unknown File")
                        }

                        mainUiState.uiRepoState.openDirectoryUri != null -> {
                            "Selected Dir: " + mainUiState.uiRepoState.openDirectoryUri?.path
                        }

                        else -> "No source selection"
                    }
                    Text(selectionLabel)
                }
            }
            item {
                Section {
                    if (Build.VERSION.SDK_INT >= 36) {
                        val wifiModes = listOf("LOH", "2GHz", "5GHz", "6GHz")
                        ButtonGroup(
                            overflowIndicator = { },
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .fillMaxWidth(0.9f)
                        ) {
                            wifiModes.forEachIndexed { index, label ->
                                toggleableItem(
                                    weight = 1f,
                                    checked = mainUiState.uiRepoState.wifiMode == index,
                                    onCheckedChange = { mainViewModel.setWifiMode(index) },
                                    label = label,
                                    enabled = !mainUiState.uiRepoState.isCheckedHotspot && mainUiState.uiRepoState.isEnabledHotspot
                                )
                            }
                        }
                    }

                    ToggleButton(
                        onCheckedChange = { onToggleHotspot() },
                        checked = mainUiState.uiRepoState.isCheckedHotspot,
                        modifier = Modifier.size(extraLargeContainerSize()),
                        enabled = mainUiState.uiRepoState.isEnabledHotspot
                    ) {
                        if (mainUiState.uiRepoState.isEnabledHotspot) {
                            Text("Hotspot")
                        } else {
                            LoadingIndicator(modifier = Modifier.fillMaxSize())
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (mainUiState.uiRepoState.ssid.isNotBlank() && mainUiState.uiRepoState.password.isNotBlank()) {
                        Text(mainUiState.uiRepoState.ssid)
                        Text(mainUiState.uiRepoState.password)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                mainViewModel.showQrCodeWifi(
                                    mainUiState.uiRepoState.ssid,
                                    mainUiState.uiRepoState.password
                                )
                            }
                        ) {
                            Text("Show QR Code")
                        }
                    }
                }
            }
            item {
                Section {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                savedFileHtml = sharedPref.getString("fileHtml", defaultFileHtml).toString()
                                savedBodyHtml = sharedPref.getString("bodyHtml", defaultBody).toString()
                                mainViewModel.showModifyHtmlPopup(true)
                            },
                            enabled = !mainUiState.uiRepoState.isCheckedServer
                        ) {
                            Text("Modify HTML")
                        }
                        Spacer(Modifier.padding(12.dp))
                        var error by remember { mutableStateOf(false) }
                        val textMeasurer = rememberTextMeasurer()
                        val portInputWidth = textMeasurer.measure("0").size.width.dp * 4
                        OutlinedTextField(
                            modifier = Modifier.width(portInputWidth),
                            state = rememberTextFieldState(savedPort.toString()),
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            enabled = !mainUiState.uiRepoState.isCheckedServer,
                            placeholder = { Text("8080") },
                            inputTransformation =
                                InputTransformation.maxLength(5).then {
                                    if (!this.asCharSequence().isDigitsOnly()) {
                                        revertAllChanges()
                                    }
                                    val port = this.toString().toIntOrNull()
                                    error = port !in 1..65535 || port == null
                                    if (!error) {
                                        sharedPref.edit {
                                            putInt("port", port!!)
                                            apply()
                                        }
                                    }
                                },
                            isError = error
                        )
                    }
                    ToggleButton(
                        onCheckedChange = { onToggleServer() },
                        checked = mainUiState.uiRepoState.isCheckedServer,
                        modifier = Modifier.size(extraLargeContainerSize()),
                        enabled = mainUiState.uiRepoState.isEnabledServer
                    ) {
                        Text("Server")
                    }
                    Spacer(Modifier.height(12.dp))
                    if (mainUiState.uiRepoState.isCheckedServer) {
                        Text(mainUiState.uiRepoState.serverUri)
                        Text(
                            "${mainUiState.uiRepoState.connectedClients} connected clients",
                            style = Typography.bodySmallEmphasized
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { mainViewModel.showQrCode(mainUiState.uiRepoState.serverUri) },
                            modifier = Modifier.fillMaxWidth(0.6f)
                        ) {
                            Text("Show QR Code")
                        }
                    }
                }
            }
        }

        CommonDialog(
            visible = mainUiState.uiRepoState.qrCodeBitmap != null,
            onDismissRequest = { mainViewModel.hideQrCode() },
            title = "QR Code",
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(8.dp))
                    mainUiState.uiRepoState.qrCodeBitmap?.asImageBitmap()?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "QR Code",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                        )
                    }
                }
            },
            dismissButtonText = "Dismiss"
        )

        CommonDialog(
            visible = mainUiState.uiRepoState.showWifiDisabledDialog,
            onDismissRequest = { mainViewModel.showWifiDisabledDialog(false) },
            title = "WiFi is disabled",
            text = "WiFi Aware and WiFi Direct require WiFi to be enabled. Do you want to enable it now?",
            dismissButtonText = "Cancel",
            confirmButtonText = "Enable",
            onConfirm = {
                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        )

        CommonDialog(
            visible = mainUiState.uiOnlyState.filePickerWarning,
            onDismissRequest = { mainViewModel.filePickerWarning(false) },
            title = "File transfers could fail",
            text = "Changing the directory while the server is running could be dangerous. Make sure no file transfers are ongoing.",
            dismissButtonText = "Cancel",
            confirmButtonText = "Continue",
            onConfirm = {
                filePicker.launch(null)
            }
        )

        var fileHtml = savedFileHtml
        var bodyHtml = savedBodyHtml

        CommonDialog(
            visible = mainUiState.uiOnlyState.htmlPopup,
            onDismissRequest = { mainViewModel.showModifyHtmlPopup(false) },
            title = "Custom HTML",
            content = {
                OutlinedTextField(
                    modifier = Modifier.heightIn(max = 275.dp),
                    state = rememberTextFieldState(savedFileHtml),
                    label = { Text("File HTML") },
                    placeholder = { Text($$"$fileUrlHtml<a...") },
                    inputTransformation =
                        {
                            val charSequence = this.asCharSequence().toString()
                            fileHtml = charSequence
                        }
                )
                OutlinedTextField(
                    modifier = Modifier.heightIn(max = 275.dp),
                    state = rememberTextFieldState(savedBodyHtml),
                    label = { Text("Body HTML") },
                    placeholder = { Text("<!DOCTYPE html>\n<html><body>...") },
                    inputTransformation =
                        {
                            val charSequence = this.asCharSequence().toString()
                            bodyHtml = charSequence
                        }
                )
            },
            resetButton = true,
            onExtra = {
                sharedPref.edit {
                    putString("fileHtml", defaultFileHtml)
                    putString("bodyHtml", defaultBody)
                    apply()
                }
                savedFileHtml = defaultFileHtml
                savedBodyHtml = defaultBody
            },
            dismissButtonText = "Cancel",
            confirmButtonText = "Save",
            onConfirm = {
                sharedPref.edit {
                    putString("fileHtml", fileHtml)
                    putString("bodyHtml", bodyHtml)
                    apply()
                }
            },
        )
    }


    val defaultFileHtml = $$"""
        $fileUrlHtml<a href=/$index>$filename</a><br>
    """.trimIndent()
    val defaultBody = $$"""
        <!DOCTYPE html>
        <html><body>
        <input id="file" type="file" />
        <button id="upload">Upload</button>
        <br><br>
        <progress id="progress" value="0" max="100" style="width:300px"></progress>
        
        <script>
        document.getElementById('upload').addEventListener('click', () => {
          const file = document.getElementById('file').files[0];
          if (!file) return alert('Select a file');
          const progress = document.getElementById('progress');
          progress.value = 0;
          const xhr = new XMLHttpRequest();
          const name = encodeURIComponent(file.name);
          xhr.open('PUT', `/upload?name=${name}`);
          xhr.upload.onprogress = (e) => {
            if (e.lengthComputable) {
              progress.value = Math.round((e.loaded / e.total) * 100);
            }
          };
          xhr.onload = () => {
            alert('Server response: ' + xhr.responseText);
          };
          xhr.onerror = () => {
            alert('Upload failed');
          };
          xhr.send(file);
        });
        </script>
        <br><br>
        $fileUrlHtml
        </body></html>
    """.trimIndent()

}
