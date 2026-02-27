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
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButtonDefaults.extraLargeContainerSize
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.viewmodel.compose.viewModel
import com.delamcode.datatransfer.ui.theme.DatatransferTheme
import com.delamcode.datatransfer.ui.theme.Typography
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
            val serverStartIntent = Intent(this, ServerService::class.java)
            this.startForegroundService(serverStartIntent)
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

        setContent {
            DatatransferTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        innerPadding = innerPadding,
                        window = window
                    )
                }
            }
        }
    }


    @Composable
    fun MainScreen(
        mainViewModel: MainViewModel = viewModel(),
        innerPadding: PaddingValues,
        window: Window
    ) {
        val mainUiState by mainViewModel.uiState.collectAsState()
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
        fun AlertDialogWrapper(
            visible: Boolean,
            onDismiss: () -> Unit,
            title: String,
            content: @Composable () -> Unit,
            actions: @Composable RowScope.() -> Unit = {},
            leftArrangedActions: @Composable RowScope.() -> Unit = {}
        ) {
            if (!visible) return
            BasicAlertDialog(onDismissRequest = onDismiss) {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .wrapContentHeight(),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = AlertDialogDefaults.TonalElevation
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        if (title.isNotEmpty()) {
                            Text(text = title, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(12.dp))
                        }
                        Column(modifier = Modifier.heightIn(max = 550.dp)) {
                            content()
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            content = {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    content = leftArrangedActions
                                )
                                Spacer(Modifier.width(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.End,
                                    content = actions
                                )
                            }
                        )
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
                    Button(onClick = {
                        if (!mainUiState.uiRepoState.isCheckedServer) filePicker.launch(null) else mainViewModel.filePickerWarning(
                            true
                        )
                    }) {
                        Text("Open Dir")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(mainUiState.uiRepoState.openDirectoryUri?.path ?: "No open directory")
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

        AlertDialogWrapper(
            visible = mainUiState.uiRepoState.qrCodeBitmap != null,
            onDismiss = { mainViewModel.hideQrCode() },
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
            actions = {
                OutlinedButton(onClick = { mainViewModel.hideQrCode() }) {
                    Text("Dismiss")
                }
            }
        )

        AlertDialogWrapper(
            visible = mainUiState.uiOnlyState.missingPermission.isNotBlank(),
            onDismiss = { mainViewModel.showMissingPermissionAlert("") },
            title = "Missing permission",
            content = {
                Text("${mainUiState.uiOnlyState.missingPermission} is a required permission for this function. No data is collected.")
            },
            actions = {
                OutlinedButton(onClick = { mainViewModel.showMissingPermissionAlert("") }) {
                    Text("Dismiss")
                }
            }
        )

        AlertDialogWrapper(
            visible = mainUiState.uiOnlyState.filePickerWarning,
            onDismiss = { mainViewModel.filePickerWarning(false) },
            title = "File transfers could fail",
            content = {
                Text("Changing the directory while the server is running could be dangerous. Make sure no file transfers are ongoing.")
            },
            actions = {
                OutlinedButton(onClick = { mainViewModel.filePickerWarning(false) }) { Text("Cancel") }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = {
                        mainViewModel.filePickerWarning(false)
                        filePicker.launch(null)
                    }
                ) {
                    Text("Continue")
                }
            }
        )

        AlertDialogWrapper(
            visible = mainUiState.uiRepoState.generalDialog.isNotBlank(),
            onDismiss = { mainViewModel.showGeneralDialog("") },
            title = "",
            content = { Text(mainUiState.uiRepoState.generalDialog) },
            actions = {
                OutlinedButton(onClick = { mainViewModel.showGeneralDialog("") }) { Text("Dismiss") }
            }
        )

        var fileHtml = savedFileHtml
        var bodyHtml = savedBodyHtml

        AlertDialogWrapper(
            visible = mainUiState.uiOnlyState.htmlPopup,
            onDismiss = { mainViewModel.showModifyHtmlPopup(false) },
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
            leftArrangedActions = {
                OutlinedButton(
                    onClick = {
                        mainViewModel.showModifyHtmlPopup(false)
                        sharedPref.edit {
                            putString("fileHtml", defaultFileHtml)
                            putString("bodyHtml", defaultBody)
                            apply()
                        }
                        savedFileHtml = defaultFileHtml
                        savedBodyHtml = defaultBody
                    }
                ) { Text("Reset") }
            },
            actions = {
                OutlinedButton(
                    onClick = { mainViewModel.showModifyHtmlPopup(false) }
                ) { Text("Cancel") }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(
                    onClick = {
                        sharedPref.edit {
                            putString("fileHtml", fileHtml)
                            putString("bodyHtml", bodyHtml)
                            apply()
                        }
                        mainViewModel.showModifyHtmlPopup(false)
                    }
                ) {
                    Text("Save")
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