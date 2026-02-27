package com.delamcode.datatransfer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DataModel (
    var isConnectingHotspot: Boolean = false,
    var isRunningHotspot: Boolean = false,
    var isConnectingServer: Boolean = true,
    var isRunningServer: Boolean = false,
    var openDirectoryUri: Uri? = null,
    var ssid: String = "",
    var password: String = "",
    var wifiMode: Int = 0,
    var qrCodeBitmap: Bitmap? = null,
    var connectedClients: Int = 0,
    var serverUri: String = "",
    var generalDialog: String = "",
    var wifiP2pRunning: Boolean = false,
)

class Repo (dataModel: DataModel) {
    private val _state = MutableStateFlow(dataModel)
    val state: StateFlow<DataModel> = _state.asStateFlow()
    
    fun setServerStatus(status: Boolean) {
        _state.update {
            it.copy(
                isRunningServer = status,
                connectedClients = 0
            )
        }
        if (!status) {
            _state.update {
                it.copy(
                    isConnectingServer = false,
                    serverUri = ""
                )
            }
        }
    }
    
    fun setHotspotConnectingStatus(status: Boolean) {
        _state.update { it.copy(isConnectingHotspot = status) }
    }
    
    fun setHotspotStatus(status: Boolean) {
        _state.update { it.copy(isRunningHotspot = status) }
        if (!status) {
            _state.update {
                it.copy(
                    isConnectingHotspot = false,
                    ssid = "",
                    password = ""
                )
            }
        }
    }

    fun setHotspotCredentials(ssid: String, password: String) {
        _state.update {
            it.copy(
                ssid = ssid,
                password = password
            )
        }
    }
    fun setServerConnectingStatus(status: Boolean) {
        _state.update { it.copy(isConnectingServer = status) }
    }
    fun setDirectoryUri(mUri: Uri?) {
        _state.update { it.copy(openDirectoryUri = mUri) }
        if (_state.value.openDirectoryUri == null) {
            _state.update {
                it.copy(
                    isConnectingServer = true,
                    isRunningServer = false
                )
            }
        } else {
            _state.update { it.copy(isConnectingServer = false) }
        }
    }
    fun setWifiMode(mode: Int) {
        _state.update { it.copy(wifiMode = mode) }
    }
    fun setQrCodeBitmap(bitmap: Bitmap?) {
        _state.update { it.copy(qrCodeBitmap = bitmap) }
    }
    fun setGeneralDialog(string: String) {
        _state.update { it.copy(generalDialog = string) }
    }
    fun setConnectedClients(int: Int) {
        _state.update { it.copy(connectedClients = int) }
    }
    fun setWifiP2pRunning(status: Boolean) {
        _state.update { it.copy(wifiP2pRunning = status) }
    }
    fun setServerUri(string: String) {
        _state.update { it.copy(serverUri = string) }
    }
}