package com.delamcode.datatransfer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

data class UiState (
    val uiRepoState: UiRepoState,
    val uiOnlyState: UiOnlyState
)
data class UiRepoState (
    var isCheckedServer: Boolean = false,
    var isEnabledServer: Boolean = true,
    var isCheckedHotspot: Boolean = false,
    var isEnabledHotspot: Boolean = true,
    var openDirectoryUri: Uri? = null,
    var generalDialog: String = "",
    var wifiMode: Int = 0,
    var ssid: String = "",
    var password: String = "",
    var connectedClients: Int = 0,
    var serverUri: String = "",
    var qrCodeBitmap: Bitmap? = null,
    var wifiP2pRunning: Boolean = false,
)
data class UiOnlyState (
    var filePickerWarning: Boolean = false,
    var missingPermission: String = "",
    var htmlPopup: Boolean = false
)

class MainViewModel(private val repo: Repo) : ViewModel() {
    private val _uiOnlyState = MutableStateFlow(UiOnlyState())
    val uiState: StateFlow<UiState> = combine(
        repo.state,
        _uiOnlyState
    ) { uiRepoState, uiOnlyState ->
        UiState(
            uiRepoState = UiRepoState(
                isCheckedServer = uiRepoState.isRunningServer,
                isEnabledServer = !uiRepoState.isConnectingServer,
                isCheckedHotspot = uiRepoState.isRunningHotspot,
                isEnabledHotspot = !uiRepoState.isConnectingHotspot,
                openDirectoryUri = uiRepoState.openDirectoryUri,
                wifiMode = uiRepoState.wifiMode,
                ssid = uiRepoState.ssid,
                password = uiRepoState.password,
                connectedClients = uiRepoState.connectedClients,
                serverUri = uiRepoState.serverUri,
                qrCodeBitmap = uiRepoState.qrCodeBitmap,
                wifiP2pRunning = uiRepoState.wifiP2pRunning,
                generalDialog = uiRepoState.generalDialog),
            uiOnlyState = uiOnlyState
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState(uiRepoState = UiRepoState(), uiOnlyState = UiOnlyState()))
    lateinit var wifiManager: WifiManager
    lateinit var wifiP2pManager: WifiP2pManager
    lateinit var wifiP2pChannel: WifiP2pManager.Channel
    private val handler = Handler(Looper.getMainLooper())
    private var localOnlyHotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val localOnlyHotspotCallback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onFailed(reason: Int) {
            super.onFailed(reason)
            showGeneralDialog("The Local Only Hotspot failed with error code $reason")
            Log.e("Hotspot", reason.toString())
            repo.setHotspotStatus(false)
        }

        override fun onStopped() {
            Log.i("Hotspot", "Killed by OS")
            super.onStopped()
            repo.setHotspotStatus(false)
        }

        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
            super.onStarted(reservation)
            if (reservation != null) {
                repo.setHotspotStatus(true)
                repo.setHotspotConnectingStatus(false)
            } else {
                Log.e("Hotspot", "Failed to get reservation")
                repo.setHotspotStatus(false)
                return
            }
            localOnlyHotspotReservation = reservation
            val info = reservation.softApConfiguration
            var mSsid = ""
            var mPassword = ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info.wifiSsid?.let { mSsid = it.toString() }
            } else {
                info.ssid?.let { mSsid = it }
            }
            info.passphrase?.let { mPassword = it }
            mSsid = mSsid.replace("\"", "")
            repo.setHotspotCredentials(mSsid, mPassword)
        }
    }

    private val wifiP2pActionListener = object : WifiP2pManager.ActionListener {
        override fun onFailure(reason: Int) {
            if (uiState.value.uiRepoState.wifiP2pRunning) {
                showGeneralDialog("Starting a WiFiDirect connection failed with error code $reason. It may not be supported on your device.")
                Log.e("Hotspot", reason.toString())
                repo.setHotspotStatus(false)
            } else {
                showGeneralDialog("Failed to stop WiFiDirect connection with error code $reason.")
                repo.setHotspotStatus(true)
                repo.setHotspotConnectingStatus(false)
            }
        }

        override fun onSuccess() {
            if (uiState.value.uiRepoState.wifiP2pRunning) {
                repo.setHotspotStatus(true)
                repo.setHotspotConnectingStatus(false)
            } else {
                repo.setHotspotStatus(false)
            }
        }
    }

    fun filePickerWarning(state: Boolean) {
        _uiOnlyState.update { currentState ->
            currentState.copy(filePickerWarning = state)
        }
    }

    fun onOpenDirectory(uri: Uri?) {
        repo.setDirectoryUri(uri)
    }

    @SuppressLint("MissingPermission", "InlinedApi")
    fun onToggleHotspot() {
        if (!uiState.value.uiRepoState.isCheckedHotspot) {
            repo.setHotspotStatus(false)
            repo.setHotspotConnectingStatus(true)
            Log.i("Hotspot", "Starting hotspot ${uiState.value.uiRepoState.wifiMode}")
            if (uiState.value.uiRepoState.wifiMode == 0) {
                wifiManager.startLocalOnlyHotspot(localOnlyHotspotCallback, handler)
            } else {
                try {
                    val band = listOf(
                        WifiP2pConfig.GROUP_OWNER_BAND_2GHZ, WifiP2pConfig.GROUP_OWNER_BAND_5GHZ,
                        WifiP2pConfig.GROUP_OWNER_BAND_6GHZ)[uiState.value.uiRepoState.wifiMode - 1]
                    val wifiP2pConfigBuilder = WifiP2pConfig.Builder()
                    val mSsid = "DIRECT-SINKMESH"
                    val characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*"
                    val mPassword = List(15) { characters[SecureRandom().nextInt(characters.length)] }.joinToString("")
                    wifiP2pConfigBuilder.setNetworkName(mSsid)
                    wifiP2pConfigBuilder.setPassphrase(mPassword)
                    wifiP2pConfigBuilder.setGroupOperatingBand(band)
                    val wifiP2pConfig = wifiP2pConfigBuilder.build()
                    repo.setWifiP2pRunning(true)
                    repo.setHotspotCredentials(mSsid, mPassword)
                    wifiP2pManager.createGroup(wifiP2pChannel, wifiP2pConfig, wifiP2pActionListener)
                } catch (e: Exception) {
                    showGeneralDialog(e.toString())
                }
            }
        } else {
            Log.i("Hotspot", "Stopping hotspot")
            if (uiState.value.uiRepoState.wifiMode == 0) {
                localOnlyHotspotReservation?.close()
                repo.setHotspotStatus(false)
            } else {
                repo.setWifiP2pRunning(false)
                wifiP2pManager.removeGroup(wifiP2pChannel, wifiP2pActionListener)
            }
        }
    }

    fun onToggleServer() {
        if (!uiState.value.uiRepoState.isCheckedServer) {
            repo.setServerStatus(true)
        } else {
            repo.setServerStatus(false)
        }
    }

    fun showMissingPermissionAlert(missingPermission: String) {
        _uiOnlyState.update { currentState ->
            currentState.copy(missingPermission = missingPermission)
        }
    }

    fun setWifiMode(mode: Int) {
        repo.setWifiMode(mode)
    }

    fun showQrCodeWifi(ssid: String, password: String) { showQrCode("WIFI:T:WPA;S:$ssid;P:$password;H:false;;") }

    fun showQrCode(string: String) {
        val qrCode = buildQrCode(string)
        repo.setQrCodeBitmap(qrCode)
    }

    fun buildQrCode(string: String): Bitmap {
        val qrCodeWriter = QRCodeWriter()
        val bitMatrix = qrCodeWriter.encode(string, BarcodeFormat.QR_CODE, 200, 200)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0..<width) {
            for (y in 0..<height) {
                bitmap[x, y] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return bitmap
    }

    fun hideQrCode() {
        repo.setQrCodeBitmap(null)
    }

    fun showGeneralDialog(string: String) {
        repo.setGeneralDialog(string)
    }

    fun modifyHtml() {
        //repo.something
    }

    fun showModifyHtmlPopup(state: Boolean) {
        _uiOnlyState.update {
            it.copy(htmlPopup = state)
        }
    }

    companion object {

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val application = checkNotNull(extras[APPLICATION_KEY])
                return MainViewModel(
                    (application as DataTransferApp).repository
                ) as T
            }
        }
    }
}