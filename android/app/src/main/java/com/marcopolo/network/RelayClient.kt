package com.marcopolo.network

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.marcopolo.BuildConfig
import com.marcopolo.model.WsMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Shared WebSocket client used by both Marco and Polo.
 * Marco creates room → gets code → connects WS.
 * Polo enters code → connects WS.
 */
class RelayClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            // BODY is too verbose for production — logs every WS message
            level = HttpLoggingInterceptor.Level.NONE
        })
        .build()

    private var webSocket: WebSocket? = null
    private val _messages = Channel<WsMessage>(Channel.BUFFERED)
    val messages: Flow<WsMessage> = _messages.receiveAsFlow()

    /** Resolve base URL dynamically so runtime changes to ServerConfig.baseUrl take effect */
    private val baseUrl: String get() = ServerConfig.baseUrl

    /**
     * POST /rooms → get {code, wsUrl}
     */
    suspend fun createRoom(): Result<String> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/rooms")
                .post("".toRequestBody())
                .build()
            val response = httpClient.newCall(request).await()
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))
            val room = json.decodeFromString<com.marcopolo.model.RoomResponse>(body)
            Result.success(room.code)
        } catch (e: Exception) {
            if (!BuildConfig.DEBUG) {
                FirebaseCrashlytics.getInstance().recordException(e)
            }
            Result.failure(e)
        }
    }

    /**
     * Connect WebSocket to relay room
     */
    fun connect(roomCode: String) {
        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        val request = Request.Builder()
            .url("$wsUrl/ws/$roomCode")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _messages.trySend(WsMessage(type = "connected"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = json.decodeFromString<WsMessage>(text)
                    _messages.trySend(msg)
                } catch (e: Exception) {
                    // Ignore malformed messages
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _messages.trySend(WsMessage(type = "disconnected"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!BuildConfig.DEBUG) {
                    FirebaseCrashlytics.getInstance().recordException(t)
                }
                _messages.trySend(WsMessage(type = "error"))
            }
        })
    }

    /**
     * Send GPS location to partner
     */
    fun sendLocation(lat: Double, lng: Double, accuracy: Float) {
        val msg = WsMessage(
            type = "location",
            lat = lat,
            lng = lng,
            timestamp = System.currentTimeMillis(),
            accuracy = accuracy
        )
        webSocket?.send(json.encodeToString(WsMessage.serializer(), msg))
    }

    /**
     * Notify the partner that this user has marked the session as complete.
     * Both sides display the congratulations dialog.
     */
    fun sendSessionComplete() {
        val msg = WsMessage(type = "session_complete")
        webSocket?.send(json.encodeToString(WsMessage.serializer(), msg))
    }

    fun disconnect() {
        webSocket?.close(1000, "Session ended")
        webSocket = null
    }
}

/**
 * OkHttp Call.await() extension for coroutines
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resumeWith(Result.success(response))
            }

            override fun onFailure(call: Call, e: java.io.IOException) {
                cont.resumeWith(Result.failure(e))
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
