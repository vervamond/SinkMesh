package com.delamcode.datatransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.lang.Thread.sleep
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.nio.channels.Channels

class ServerService : Service() {
    lateinit var sharedPref: SharedPreferences
    val notificationId = "Server"
    private val serverUrlHandlerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var repo: Repo
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    var notification: Notification? = null
    var notificationsManager: NotificationManager? = null
    private var activePort: Int = 8080


    private fun stopServer() {
        repo.setServerStatus(false)
        serverSocket?.close()
        serverScope.cancel()
        updateServerUrl(null, activePort)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onCreate() {
        repo = (application as DataTransferApp).repository
        sharedPref = this.getSharedPreferences("${this.packageName}", MODE_PRIVATE)
        activePort = sharedPref.getInt("port", 8080)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("Stop", false) == true) {
            stopServer()
            return super.onStartCommand(intent, flags, startId)
        }


        // Create notification and elevate
        notificationsManager = applicationContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationsManager!!.createNotificationChannel(
            NotificationChannel(
                notificationId,
                notificationId,
                NotificationManager.IMPORTANCE_LOW
            )
        )
        notification = NotificationCompat.Builder(this, notificationId)
            //.setContent() TODO
            .build()
        ServiceCompat.startForeground(
            this,
            1,
            notification!!,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )

        repo.state
            .map { it.isRunningHotspot }
            .distinctUntilChanged()
            .onEach {
                serverUrlHandlerScope.launch {
                    updateServerUrl(serverSocket, activePort)
                    sleep(500)
                    updateServerUrl(serverSocket, activePort)
                    sleep(3000)
                    updateServerUrl(serverSocket, activePort)
                }
            }
            .launchIn(serverUrlHandlerScope)

        // Logic
        serverScope.launch {
            try {
                repo.setServerStatus(true)
                val uri = repo.state.value.openDirectoryUri
                if (uri == null) {
                    Log.e("Server", "no open directory")
                    return@launch
                }
                val files = ArrayList<DocumentFile>()
                val currentDir = DocumentFile.fromTreeUri(applicationContext, uri)
                if (currentDir != null) {
                    for (child in currentDir.listFiles()) {
                        if (child.isDirectory) continue
                        files.add(child)
                    }
                }
                activePort = sharedPref.getInt("port", 8080)
                serverSocket = ServerSocket(activePort)
                repo.setServerUri(computeServerUrl(serverSocket, activePort, repo))
                while (isActive && !serverSocket!!.isClosed && repo.state.value.isRunningServer) {
                    try {
                        val client = serverSocket!!.accept()
                        serverScope.launch {
                            handleClient(
                                client,
                                context = applicationContext,
                                filesRef = files,
                                uri = uri
                            )
                        }
                    } catch (e: SocketException) {
                        if (!repo.state.value.isRunningServer) break
                        Log.e("Server", e.toString())
                    }
                }
                stopServer()
            } catch (e: Exception) {
                Log.e("Server", e.toString())
                showGeneralDialog("An error occurred: $e")
                stopServer()
            } finally {
                stopServer()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    fun updateServerUrl(serverSocket: ServerSocket?, port: Int) {
        val serverUri = computeServerUrl(serverSocket, port, repo)
        repo.setServerUri(serverUri)
    }
    private fun computeServerUrl(serverSocket: ServerSocket?, port: Int, repo: Repo): String {
        return try {
            if (serverSocket == null || !serverSocket.isBound) return ""
            if (repo.state.value.wifiP2pRunning) return "http://192.168.49.1:$port" //TODO: use actual logic here instead of hardcoding
            val addr = serverSocket.inetAddress
            val host = if (addr.isAnyLocalAddress) {
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .flatMap { it.inetAddresses.asSequence() }
                    .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
            } else {
                addr.hostAddress
            } ?: return ""
            "http://$host:$port"
        } catch (e: Exception) {
            Log.e("Server", e.toString())
            return ""
        }
    }
    private fun readHeaders(input: InputStream, initialBuf: ByteArray, initialRead: Int): Pair<String, ByteArray> {
        @Suppress("SpellCheckingInspection") val baos = ByteArrayOutputStream()
        if (initialRead > 0) baos.write(initialBuf, 0, initialRead)
        val tmp = ByteArray(1024)
        val sep = byteArrayOf(13, 10, 13, 10) // "\r\n\r\n"
        fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty()) return 0
            val limit = haystack.size - needle.size
            for (i in 0..limit) {
                var k = 0
                while (k < needle.size && haystack[i + k] == needle[k]) k++
                if (k == needle.size) return i
            }
            return -1
        }
        while (true) {
            val data = baos.toByteArray()
            val idx = indexOf(data, sep)
            if (idx >= 0) {
                val headersBytes = data.copyOfRange(0, idx + sep.size)
                val remainder = data.copyOfRange(idx + sep.size, data.size)
                val headersString = String(headersBytes, Charsets.UTF_8)
                return Pair(headersString, remainder)
            }
            val r2 = input.read(tmp)
            if (r2 <= 0) return Pair(String(baos.toByteArray(), Charsets.UTF_8), ByteArray(0))
            baos.write(tmp, 0, r2)
        }
    }

    private suspend fun handleClient(socket: Socket, context: Context, filesRef: ArrayList<DocumentFile>, uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                val repo = (application as DataTransferApp).repository
                repo.setConnectedClients(repo.state.value.connectedClients + 1)
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                val buf = ByteArray(4096)
                val r = input.read(buf)
                if (r <= 0) { socket.close(); return@withContext }
                val (headersRaw, bodyInitialBytes) = readHeaders(input, buf, r)
                val headerLines = headersRaw.split("\r\n").filter { it.isNotBlank() }
                val requestLine = headerLines.firstOrNull() ?: ""
                val tokens = requestLine.split(Regex("\\s+"))
                val method = if (tokens.isNotEmpty()) tokens[0] else "GET"
                val rawPath = if (tokens.size >= 2) tokens[1] else "/"
                val path = when {
                    rawPath.isBlank() -> "/"
                    rawPath.startsWith("/") -> rawPath
                    else -> "/$rawPath"
                }
                val headers = headerLines.drop(1).mapNotNull {
                    val idx = it.indexOf(':')
                    if (idx > 0) Pair(it.substring(0, idx).trim().lowercase(), it.substring(idx + 1).trim()) else null
                }.toMap()

                if (method == "GET" && path == "/") {
                    val savedFileHtml = sharedPref.getString("fileHtml", null)!!
                    val savedBodyHtml = sharedPref.getString("bodyHtml", null)!!

                    val body: String
                    var fileUrlHtml = ""
                    filesRef.forEachIndexed { index, file ->
                        if (file.name != null) {
                            fileUrlHtml = savedFileHtml
                                .replace($$"$fileUrlHtml", fileUrlHtml)
                                .replace($$"$index", index.toString())
                                .replace($$"$filename", file.name!!)
                        }
                    }
                    body = savedBodyHtml.replace($$"$fileUrlHtml", fileUrlHtml)
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.flush()
                    socket.close()
                    return@withContext
                }
                if (method == "PUT" && path.startsWith("/upload")) {
                    val query = path.substringAfter("?", "")
                    var filename = query.split("&")
                        .mapNotNull { it.takeIf { it.contains("=") }?.split("=", limit = 2) }
                        .firstOrNull { it[0] == "name" }?.get(1)
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        ?: "upload_${System.currentTimeMillis()}"

                    val contentLength = headers["content-length"]?.toLongOrNull()
                    //val isChunked = headers["transfer-encoding"]?.equals("chunked", true) == true
                    val treeDocumentFile: DocumentFile? = DocumentFile.fromTreeUri(context, uri)
                    if (treeDocumentFile == null) {
                        showGeneralDialog("Failed to create temp directory with error code -1.")
                        socket.close()
                        return@withContext
                    }
                    val tmpExists = treeDocumentFile.findFile("tmp")
                    val tmpDirectory = tmpExists ?: treeDocumentFile.createDirectory("tmp")
                    if (tmpDirectory == null) {
                        showGeneralDialog("Failed to create temp directory with error code 0.")
                        socket.close()
                        return@withContext
                    }
                    var fileUploadDocumentFile = tmpDirectory.createFile("application/octet-stream", filename)
                    if (fileUploadDocumentFile == null) {
                        showGeneralDialog("Failed to create temp directory with error code 1.")
                        socket.close()
                        return@withContext
                    }
                    val contentResolver = context.contentResolver
                    val outStream = contentResolver.openOutputStream(fileUploadDocumentFile.uri, "w")
                    outStream?.use { fos ->
                        var written = 0L
                        val tmp = ByteArray(8192)
                        if (bodyInitialBytes.isNotEmpty()) {
                            fos.write(bodyInitialBytes); written += bodyInitialBytes.size }
                        //if (contentLength != null) {
                        var remaining = contentLength?.minus(written)
                        if (remaining != null) {
                            while (remaining!! > 0) {
                                val toRead = minOf(tmp.size.toLong(), remaining).toInt()
                                val read = input.read(tmp, 0, toRead)
                                if (read <= 0) break
                                fos.write(tmp, 0, read)
                                written += read
                                remaining = remaining.minus(read)
                            }
                        }
                        if (written != contentLength) {
                            val respBody = "Upload failed: expected $contentLength bytes, received $written"
                            val response = "HTTP/1.1 400 Bad Request\r\nContent-Length: ${respBody.toByteArray(
                                Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$respBody"
                            out.write(response.toByteArray(Charsets.UTF_8))
                            out.flush()
                            socket.close()
                            return@withContext
                        }
                        fos.flush()
                    }
                    if (treeDocumentFile.findFile(filename) != null) {
                        filename = "${System.currentTimeMillis()}_${filename}"
                        val renamedFileUri = DocumentsContract.renameDocument(
                            context.contentResolver,
                            fileUploadDocumentFile.uri,
                            filename
                        )
                        if (renamedFileUri != null) {
                            fileUploadDocumentFile = DocumentFile.fromSingleUri(context, renamedFileUri)
                        } else {
                            showGeneralDialog("Failed to save file with error code 2.")
                            socket.close()
                            return@withContext
                        }
                    }
                    if (fileUploadDocumentFile != null) {
                        DocumentsContract.moveDocument(context.contentResolver, fileUploadDocumentFile.uri,
                            tmpDirectory.uri, treeDocumentFile.uri)
                    } else {
                        showGeneralDialog("Failed to create save file with error code 3")
                        socket.close()
                        return@withContext
                    }
                    val respBody = "Uploaded: $filename"
                    val response = "HTTP/1.1 201 Created\r\nContent-Length: ${respBody.toByteArray(
                        Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$respBody"
                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.flush()
                    socket.close()
                    outStream?.close()
                    return@withContext
                }
                val index = path.substring(1).toIntOrNull()
                if (index == null || method != "GET") {
                    val body = "403 Forbidden"
                    val response = "HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.toByteArray(
                        Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.flush()
                    socket.close()
                    return@withContext
                }
                val file = filesRef.getOrNull(index)
                if (file == null || !file.isFile) {
                    val body = "404 Not Found"
                    val response = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.toByteArray(
                        Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"
                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.flush()
                    socket.close()
                    return@withContext
                }
                val contentResolver = context.contentResolver
                val parcelFileDescriptor: ParcelFileDescriptor? = contentResolver.openFileDescriptor(file.uri, "r")
                val fileDescriptor: FileDescriptor? = parcelFileDescriptor?.fileDescriptor

                val fileSize = parcelFileDescriptor?.statSize?.coerceAtLeast(0L)
                val fileName = file.name
                val response = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=\"${fileName}\"; filename*=UTF-8''${URLEncoder.encode(file.name, "UTF-8")}\r\nContent-Length: $fileSize\r\nConnection:close\r\n\r\n"

                out.write(response.toByteArray(Charsets.UTF_8))
                out.flush()

                val socketChannelOut = Channels.newChannel(out)
                FileInputStream(fileDescriptor).use { fis ->
                    var position = 0L
                    fileSize?.let {
                        while (position < it) {
                            val transferred = fis.channel.transferTo(
                                position,
                                fileSize - position,
                                socketChannelOut
                            )
                            if (transferred <= 0) break
                            position += transferred
                        }
                    }
                }
                out.flush()
                socket.close()
                parcelFileDescriptor?.close()
            } catch (e: SocketException) {
                showGeneralDialog(e.toString())
            } finally {
                try {
                    socket.close()
                } catch (e: Exception) {
                    Log.e("Server", e.toString())
                }
            }
        }
    }

    private fun showGeneralDialog(string: String) {
        repo.setGeneralDialog(string)
    }
}