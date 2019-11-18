package com.wesync

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.nearby.connection.Payload
import com.wesync.connection.Endpoint
import com.wesync.util.Config
import com.wesync.util.ConnectionStatus
import com.wesync.util.Tempo
import com.wesync.util.UserTypes
import com.wesync.util.service.ServiceSubscriber

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val SESSION_KEY = "currentSession"
        const val USER_TYPE_KEY = "currentUserType"
        const val BPM_KEY = "currentBPM"
        const val IS_PLAYING_KEY = "currentIsPlaying"
    }

    override fun onCleared() {
        subscriber.unsubscribe()
        super.onCleared()
    }

    private val subscriber = ServiceSubscriber(this.getApplication(),null)
    // Viewmodel lifecycle management
    init {
        subscriber.subscribe()
        observeService()

    }

    private val _bpm                                   = MutableLiveData<Long>(Tempo.DEFAULT_BPM)
        val bpm         :LiveData<Long>                    = _bpm
    private val _isPlaying                             = MutableLiveData<Boolean>(false)
        val isPlaying   :LiveData<Boolean>                 = _isPlaying
    private val _userName                               = MutableLiveData<String>("MusicDirector")
        val userName     :LiveData<String>                  = _userName
    private val _userType                              = MutableLiveData<String>(UserTypes.SOLO)
        val userType    :LiveData<String>                  = _userType
        val connectionStatus                               = MutableLiveData<Int>() //TODO: OBSERVE FROM INTERNAL SERVICE
        val payload                                        = MutableLiveData<Payload>()
        val connectedEndpointId                            = MutableLiveData<String>(null)
    private val _isAdvertising                         = MutableLiveData<Boolean>(false)
        val isAdvertising: LiveData<Boolean> = _isAdvertising
    private val _isDiscovering           = MutableLiveData<Boolean>(false)
        val isDiscovering: LiveData<Boolean> = _isAdvertising
    private val _foundSessions = MutableLiveData<MutableList<Endpoint>>()
        val foundSessions: LiveData<MutableList<Endpoint>> = _foundSessions

    private fun observeService() {
        subscriber.connectionService?.foundSessions?.observeForever {_foundSessions.value = it}
        subscriber.connectionService?.payload?.observeForever{payload.value = it}
    }
    // a callback which always be called when BPM or isPlaying is changing.
    // it is used to inform mCService to send payload for everytime changes at mService happens.
    private fun onConfigChanged() {


    }
    private fun connect(e: Endpoint) { subscriber.connectionService?.connect(e,_userName.value!!)}
    private fun disconnect() {subscriber.connectionService?.disconnect()}
    private fun Long.setBPM() {
        //state.set(BPM_KEY, this)
        _bpm.value = this
        subscriber.metronomeService?.setBPM(this)
        onConfigChanged()
    }
    private fun setIsPlaying(b: Boolean) {
        _isPlaying.value = b
        if (b) subscriber.metronomeService?.play()
        else subscriber.metronomeService?.stop()
        onConfigChanged()
    }
    private fun setUserName(sessionName: String?) {
        if (sessionName != null && sessionName.isNotEmpty()) {
            //state.set(SESSION_KEY,sessionName)
            _userName.value = sessionName
        } else
        { _userName.value = "MusicDirector" }
        subscriber.connectionService?.userName = _userName.value!!

    }
    private fun setUserType(userTypes: String?) {
        //state.set(USER_TYPE_KEY,userTypes)
        if (userTypes!= null) _userType.value = userTypes
        else _userType.value = UserTypes.SOLO
        subscriber.connectionService?.userType = _userType.value!!
    }
    private fun setIsAdvertising(a: Boolean) {
        _isAdvertising.value = a
        if (a) subscriber.connectionService?.startAdvertising()
        else subscriber.connectionService?.stopAdvertising()
    }
    private fun setIsDiscovering(a: Boolean){
        _isDiscovering.value = a
        if (a) subscriber.connectionService?.startDiscovery()
        else subscriber.connectionService?.stopDiscovering()

    }
    private fun reset(){
        Tempo.DEFAULT_BPM.setBPM()
        setIsPlaying(false)
        setUserName("MusicDirector")
        setUserType(UserTypes.SOLO)
        connectionStatus.value = ConnectionStatus.DISCONNECTED
        connectedEndpointId.value = null
    }

    fun onNewSession(sessionName: String?) {
        setUserName(sessionName)
        setUserType(UserTypes.SESSION_HOST)
        if (!_isAdvertising.value!!) setIsAdvertising(true)
    }
    fun onJoinSession(yourName: String?, it: Endpoint) {
        setUserName(yourName)
        setUserType(UserTypes.SLAVE)
        connect(it)
    }
    fun endSession() {
        setUserType(UserTypes.SOLO)

    }
    fun toggleAdvertise() {
        val p = _isAdvertising.value
        setIsAdvertising(!p!!)
    }
    fun startDiscovery() {
        setIsDiscovering(true)
    }
    fun stopDiscovery() {
        setIsDiscovering(false)
    }




    fun flipIsPlaying() {
        setIsPlaying(!_isPlaying.value!!)
    }
    fun modifyBPM(plus:Long) {
        val r = bpm.value!!
        when {
            r+plus < Tempo.MINIMUM_BPM -> Tempo.MINIMUM_BPM.setBPM()
            r+plus > Tempo.MAXIMUM_BPM -> Tempo.MAXIMUM_BPM.setBPM()
            else -> (r + plus).setBPM()
        }
    }

    fun getConfig(): Config {
        return Config(
            bpm.value!!,
            isPlaying.value!!,
            userName.value!!,
            userType.value!!
        )
    }

    fun unpackBundle(b: Bundle?) {
        if (b != null) {
            b.getLong(BPM_KEY).setBPM()
            setIsPlaying(b.getBoolean(IS_PLAYING_KEY))
            setUserName(b.getString(SESSION_KEY))
            setUserType(b.getString(USER_TYPE_KEY))
        } else {
            Log.d("states","Bundle was null. resetting.")
            reset()
        }
    }
    fun getSessionName(): String {
        return _userName.value!!
    }

    fun printValues() {
        Log.d("states","bpm::${bpm.value}")
        Log.d("states","isPlaying:${isPlaying.value}")
        Log.d("states","session: ${userName.value}")
        Log.d("states","userType: ${userType.value}")
    }



}
