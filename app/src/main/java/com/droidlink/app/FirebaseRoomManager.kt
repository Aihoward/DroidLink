package com.droidlink.app

import android.util.Log
import com.google.firebase.database.*
import kotlin.random.Random

class FirebaseRoomManager {
    companion object { private const val TAG = "DroidLink" }
    private val rooms = FirebaseDatabase.getInstance().getReference("rooms")
    private val valueListeners = mutableListOf<Pair<Query, ValueEventListener>>()
    private val childListeners = mutableListOf<Pair<Query, ChildEventListener>>()

    fun createRoom(onSuccess: (String) -> Unit, onError: (String) -> Unit) = createRoomAttempt(0, onSuccess, onError)

    private fun createRoomAttempt(attempt: Int, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val code = Random.nextInt(100000, 1_000_000).toString()
        rooms.child(code).runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (data.value != null) return Transaction.abort()
                data.value = mapOf("status" to "waiting", "createdAt" to ServerValue.TIMESTAMP)
                return Transaction.success(data)
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                when {
                    error != null -> onError(error.message)
                    committed -> { Log.d(TAG, "Firebase room created: $code"); onSuccess(code) }
                    attempt < 4 -> createRoomAttempt(attempt + 1, onSuccess, onError)
                    else -> onError("Could not allocate a unique room code")
                }
            }
        })
    }

    fun joinRoom(code: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        rooms.child(code).get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) return@addOnSuccessListener onError("Room not found")
            Log.d(TAG, "Room found: $code")
            rooms.child(code).child("status").setValue("connected")
                .addOnSuccessListener { onSuccess() }.addOnFailureListener { onError(it.message ?: "Failed to join room") }
        }.addOnFailureListener { onError(it.message ?: "Firebase lookup failed") }
    }

    fun saveOffer(code: String, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = saveSdp(code, "offer", sdp, onSuccess, onError)
    fun saveAnswer(code: String, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = saveSdp(code, "answer", sdp, onSuccess, onError)
    fun saveVoiceOffer(code: String, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = saveSdp(code, "voiceOffer", sdp, onSuccess, onError)
    fun saveVoiceAnswer(code: String, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) = saveSdp(code, "voiceAnswer", sdp, onSuccess, onError)
    private fun saveSdp(code: String, name: String, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        rooms.child(code).child(name).setValue(sdp).addOnSuccessListener { Log.d(TAG, "Firebase $name stored: $code"); onSuccess() }
            .addOnFailureListener { onError(it.message ?: "Failed to save $name") }
    }

    fun getOffer(code: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        rooms.child(code).child("offer").get().addOnSuccessListener {
            it.getValue(String::class.java)?.let { offer -> Log.d(TAG, "Offer found: $code"); onSuccess(offer) }
                ?: onError("Room has no WebRTC offer yet")
        }.addOnFailureListener { onError(it.message ?: "Failed to load offer") }
    }

    fun listenForAnswer(code: String, onAnswer: (String) -> Unit, onError: (String) -> Unit) {
        val query = rooms.child(code).child("answer")
        var delivered = false
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val answer = snapshot.getValue(String::class.java)
                if (!delivered && answer != null) { delivered = true; Log.d(TAG, "Answer received: $code"); onAnswer(answer) }
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        query.addValueEventListener(listener); valueListeners += query to listener
        Log.d(TAG, "Answer listener registered: $code")
    }

    fun requestVoiceNegotiation(code: String, onError: (String) -> Unit) {
        rooms.child(code).child("voiceRequest").setValue(ServerValue.TIMESTAMP)
            .addOnFailureListener { onError(it.message ?: "Failed to request voice negotiation") }
    }

    fun listenForVoiceRequest(code: String, onRequest: () -> Unit, onError: (String) -> Unit) {
        val query = rooms.child(code).child("voiceRequest")
        var last: Long? = null
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val value = snapshot.getValue(Long::class.java) ?: return
                if (value != last) { last = value; onRequest() }
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        query.addValueEventListener(listener); valueListeners += query to listener
    }

    fun listenForVoiceOffer(code: String, onOffer: (String) -> Unit, onError: (String) -> Unit) = listenForChangingSdp(code, "voiceOffer", onOffer, onError)
    fun listenForVoiceAnswer(code: String, onAnswer: (String) -> Unit, onError: (String) -> Unit) = listenForChangingSdp(code, "voiceAnswer", onAnswer, onError)

    private fun listenForChangingSdp(code: String, name: String, onSdp: (String) -> Unit, onError: (String) -> Unit) {
        val query = rooms.child(code).child(name)
        var last: String? = null
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.getValue(String::class.java) ?: return
                if (sdp != last) { last = sdp; Log.d(TAG, "$name received: $code"); onSdp(sdp) }
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        query.addValueEventListener(listener); valueListeners += query to listener
    }

    fun saveIceCandidate(code: String, side: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int, onError: (String) -> Unit) {
        val data = mapOf("candidate" to candidate, "sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex, "createdAt" to ServerValue.TIMESTAMP)
        rooms.child(code).child("${side}Candidates").push().setValue(data)
            .addOnSuccessListener { Log.d(TAG, "ICE candidate stored: room=$code side=$side") }
            .addOnFailureListener { onError(it.message ?: "Failed to save ICE candidate") }
    }

    fun listenForIceCandidates(code: String, side: String, onCandidate: (String, String?, Int) -> Unit, onError: (String) -> Unit) {
        val query = rooms.child(code).child("${side}Candidates")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidate = snapshot.child("candidate").getValue(String::class.java) ?: return
                val mid = snapshot.child("sdpMid").getValue(String::class.java)
                val line = snapshot.child("sdpMLineIndex").getValue(Long::class.java)?.toInt() ?: return
                Log.d(TAG, "ICE candidate received from Firebase: room=$code side=$side")
                onCandidate(candidate, mid, line)
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
        }
        query.addChildEventListener(listener); childListeners += query to listener
        Log.d(TAG, "ICE listener registered: room=$code remoteSide=$side path=${query.path}")
    }

    fun stopListening() {
        valueListeners.forEach { (query, listener) -> query.removeEventListener(listener) }
        childListeners.forEach { (query, listener) -> query.removeEventListener(listener) }
        valueListeners.clear(); childListeners.clear()
        Log.d(TAG, "Firebase signaling listeners removed")
    }

    fun deleteRoom(code: String) { rooms.child(code).removeValue().addOnCompleteListener { Log.d(TAG, "Host room removed: $code success=${it.isSuccessful}") } }
}
