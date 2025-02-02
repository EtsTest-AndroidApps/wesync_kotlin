package com.wesync.connection

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.wesync.connection.callbacks.MyConnectionLifecycleCallback
import com.wesync.connection.callbacks.MyEndpointCallback
import com.wesync.connection.callbacks.MyPayloadCallback
import com.wesync.connection.callbacks.SessionConnectionLifecycleCallback
import com.wesync.util.*
import com.wesync.util.ServiceUtil.Companion.SERVICE_ID
import com.wesync.util.service.ForegroundNotification
import com.wesync.util.service.ForegroundServiceLauncher


class ConnectionManagerService : LifecycleService() {

    companion object {
        private val LAUNCHER =
            ForegroundServiceLauncher(ConnectionManagerService::class.java)
        @JvmStatic
        fun start(context: Context) = LAUNCHER.startService(context)
        @JvmStatic
        fun stop(context: Context) = LAUNCHER.stopService(context)
    }

    private val _binder                         = LocalBinder()
    private val strategy: Strategy              = Strategy.P2P_STAR
    private val payloadCallback                 = MyPayloadCallback()
    private val endpointCallback                = MyEndpointCallback()
        var userType                        = UserTypes.SOLO
        var userName                               = ""
    private lateinit var connectionCallback      : MyConnectionLifecycleCallback
    private lateinit var advertiserConnectionCallback : SessionConnectionLifecycleCallback
    var ntpOffset : Long = 0



    private val _payload                               = MutableLiveData<Payload>()
        val payload: LiveData<Payload>                     = _payload //INI YANG DITERIMA, BUKAN YANG DIKIRIM
    private val _payloadSender                         = MutableLiveData<String>()
    private val _foundSessions                         = MutableLiveData<MutableList<DiscoveredEndpoint>>()
        val foundSessions: LiveData<MutableList<DiscoveredEndpoint>> = _foundSessions
    private val _connectedEndpointId                   = MutableLiveData<String>("")
        val connectedEndpointId:LiveData<String>           = _connectedEndpointId
    private val _connectionStatus                      = MutableLiveData<Int>()
        val connectionStatus:LiveData<Int>                 = _connectionStatus
    private val _connectedSlaves = MutableLiveData<MutableMap<String,ReceivedEndpoint>>()
    private val _latencyMap = mutableMapOf<String, Long>()
    private val _isDiscovering = MutableLiveData<Boolean>(false)
        val isDiscovering: LiveData<Boolean>  = _isDiscovering

    private val _preStartLatency = MutableLiveData<Long>()
        val preStartLatency: LiveData<Long> = _preStartLatency



    inner class LocalBinder : Binder() {
        fun getService() : ConnectionManagerService {
            return this@ConnectionManagerService
        }
    }

    fun mockList(): MutableList<DiscoveredEndpoint> {
        val l = mutableListOf<DiscoveredEndpoint>()
        l.add(DiscoveredEndpoint("test1", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test2", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test3", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test4", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test5", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test6", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test7", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test8", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test9", DiscoveredEndpointInfo("test1","test1")))
        l.add(DiscoveredEndpoint("test10", DiscoveredEndpointInfo("test1","test1")))
        return l
    }
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        startForeground(
            ForegroundNotification.NOTIFICATION_ID,
            ForegroundNotification.getNotification(this))
        LAUNCHER.onServiceCreated(this)
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }
    override fun onBind(intent: Intent): IBinder {
        connectionCallback = MyConnectionLifecycleCallback(
            applicationContext,payloadCallback)
        advertiserConnectionCallback = SessionConnectionLifecycleCallback(
            applicationContext,payloadCallback)
        observePayloadEndpointsAndCallbacks()
        super.onBind(intent)
        return this._binder
    }
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d("_con","connectionmanagerservice DISCONNECTED")
        if (userType == UserTypes.SOLO) {
            stopSelf()
        }
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        stopSelf()
        super.onDestroy()
    }


    private fun unpackPingPayload(payload: Payload) {
        val b = payload.asBytes()!!
        val time = ByteArrayEncoderDecoder.decodeTimestamp(b)
        var currTime = getCurrentTimeWithOffset() % ByteArrayEncoderDecoder.TWO_14
        when (b[0]) {
            PayloadType.PING -> {
                // SLAVE balas ke HOST
                if (time > currTime) currTime += ByteArrayEncoderDecoder.TWO_14
                val hostToHereTime = currTime - time
                sendTimestampedByteArray(hostToHereTime,PayloadType.PING_RESPONSE)
            }
            PayloadType.PING_RESPONSE -> {
                // DITERIMA oleh HOST
                //HOST mencatat berapa pingnya?
                _latencyMap[_payloadSender.value!!] = time
                var longest: Long = 0
                for (i in _latencyMap) {
                    if (i.value > longest) longest = i.value }
                for (i in _latencyMap) {
                    sendTimestampedByteArray(longest - i.value, PayloadType.PING_PRE_START_LATENCY)
                }


            }
            PayloadType.PING_PRE_START_LATENCY -> {
                //slave terima ini dari HOST
                // ganti ini jadi waktu preStartLatency.
                // makeSure viewModel tau tentang berapa preStartLatencynya.
                // Karena akan dipakai oleh MetronomeService
                _preStartLatency.value = time
                _connectionStatus.value = ConnectionStatus.CONNECTED
            }
        }

    }
    private fun getCurrentTimeWithOffset(): Long {
        return System.currentTimeMillis() + ntpOffset
    }
    private fun sendByteArray(toEndpointId: String, b: ByteArray) {
        sendPayload(toEndpointId, Payload.fromBytes(b))
    }
    private fun sendPayload(toEndpointId: String,payload: Payload) {
        if (TestMode.STATUS == TestMode.NEARBY_ON) {
            Nearby.getConnectionsClient(applicationContext)
                .sendPayload(toEndpointId, payload)
        }
    }
    private fun observePayloadEndpointsAndCallbacks() {
        payloadCallback.payload.observe(this , Observer {
            this@ConnectionManagerService._payload.value = it // oper Config Payload ke MutableLiveData (observed by VM)
            unpackPingPayload(it) //kalo ternyata bukan Config, payload akan diproses disini
        })
        payloadCallback.payloadSender.observe(this, Observer {
            _payloadSender.value = it
        })
        endpointCallback.sessions.observe(this, Observer {
            this@ConnectionManagerService._foundSessions.value = it
            Log.d("onEndpointFound","DiscoveredEndpoint added. List in ConnectionManagerService updated")})

        connectionCallback.connectedSessionId.observe(this, Observer {
            this@ConnectionManagerService._connectedEndpointId.value = it})
        connectionCallback.connectionStatus.observe(this, Observer {
            this@ConnectionManagerService._connectionStatus.value = it })
        advertiserConnectionCallback.connectedSlaves.observe(this, Observer {
            this@ConnectionManagerService._connectedSlaves.value = it
            if (it.isNotEmpty()) sendTimestampedByteArray(type = PayloadType.PING)
        })
    }
    private fun sendTimestampedByteArray(time: Long? = 0, type: Byte, to: String? = null) {
        when (type) {
            PayloadType.PING -> {
                this.sendByteArrayToAll(ByteArrayEncoderDecoder
                    .encodeTimestampByteArray(
                        getCurrentTimeWithOffset(),type))
            }
            PayloadType.PING_RESPONSE -> {
                if (userType == UserTypes.SLAVE) {
                    if (time != null && time > 0)
                        sendByteArray(_connectedEndpointId.value!!,
                            ByteArrayEncoderDecoder
                                .encodeTimestampByteArray(time,type))
                }
            }
            PayloadType.PING_PRE_START_LATENCY -> {
                if (time != null && time > 0 && to != null)
                this.sendByteArray(to,ByteArrayEncoderDecoder
                    .encodeTimestampByteArray(time,type))
            }
        }

    }

    fun startAdvertising() {
        if (TestMode.STATUS == TestMode.NEARBY_ON) {
            val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
            Nearby.getConnectionsClient(applicationContext)
                .startAdvertising(userName,SERVICE_ID, advertiserConnectionCallback, advertisingOptions)
                .addOnSuccessListener { Toast.makeText(this, "Accepting User...",Toast.LENGTH_SHORT).show() }
                .addOnFailureListener { throw it }
        }
    }
    fun stopAdvertising() {
        if (TestMode.STATUS == TestMode.NEARBY_ON)
            Nearby.getConnectionsClient(applicationContext).stopAdvertising()
    }
    fun startDiscovery() {
        Log.d("startDiscovery","DISCOVERING")
        if (TestMode.STATUS == TestMode.NEARBY_ON) {
            val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
            if (_foundSessions.value!!.size > 0) _foundSessions.value = mutableListOf()
            Nearby.getConnectionsClient(applicationContext)
                .startDiscovery(SERVICE_ID, endpointCallback, discoveryOptions)
                .addOnSuccessListener {
                    Toast.makeText(this, "Finding nearby session...", Toast.LENGTH_SHORT).show()
                    _isDiscovering.value = true
                }
                .addOnFailureListener {
                    _isDiscovering.value = false
                    throw it
                }
        }
    }
    fun stopDiscovery() {
        if (TestMode.STATUS == TestMode.NEARBY_ON) {
            _isDiscovering.value = false
            Nearby.getConnectionsClient(applicationContext).stopDiscovery()
        }
    }
    fun sendByteArrayToAll(b: ByteArray) {
        if (userType == UserTypes.SESSION_HOST) for (endpoint in _connectedSlaves.value!!) {
            sendByteArray(endpoint.key,b)
        }
    }
    fun connect(discoveredEndpoint: DiscoveredEndpoint, name: String) {
        if (TestMode.STATUS == TestMode.NEARBY_ON) {
            Nearby.getConnectionsClient(application)
                .requestConnection(name, discoveredEndpoint.endpointId, connectionCallback)
                .addOnSuccessListener { Toast.makeText(applicationContext,
                    "Connecting to ${discoveredEndpoint.info.endpointName} (${discoveredEndpoint.endpointId})", Toast.LENGTH_SHORT).show()
                    _connectionStatus.value = ConnectionStatus.CONNECTING
                }
                .addOnFailureListener { Toast.makeText(applicationContext,
                        "Failed to request connection to ${discoveredEndpoint.info.endpointName} " +
                                "(${discoveredEndpoint.endpointId})", Toast.LENGTH_SHORT).show()
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
        }
    }
    fun disconnect() {
        Nearby.getConnectionsClient(application).stopAllEndpoints()
        userType = UserTypes.SOLO
    }

}
