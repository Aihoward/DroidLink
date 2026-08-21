package com.droidlink.app

import android.util.Log
import com.google.firebase.database.*
import java.security.SecureRandom

data class RemoteSlotAssignment(val playerSlot: Int, val joinerId: String)

class FirebaseRoomManager {
    companion object { private const val TAG = "DroidLink" }

    private data class ValueRegistration(val owner: String, val query: Query, val listener: ValueEventListener)
    private data class ChildRegistration(val owner: String, val query: Query, val listener: ChildEventListener)

    private val secureRandom = SecureRandom()
    private val rooms = FirebaseDatabase.getInstance().getReference("rooms")
    private val valueListeners = mutableListOf<ValueRegistration>()
    private val childListeners = mutableListOf<ChildRegistration>()
    private val claimDisconnects = mutableMapOf<String, OnDisconnect>()

    fun createRoom(onSuccess: (String) -> Unit, onError: (String) -> Unit) =
        createRoomAttempt(0, onSuccess, onError)

    private fun createRoomAttempt(attempt: Int, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val code = secureRandom.nextInt(900_000).plus(100_000).toString()
        rooms.child(code).runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (data.value != null) return Transaction.abort()
                data.value = mapOf(
                    "status" to "waiting",
                    "createdAt" to ServerValue.TIMESTAMP,
                    "maxRemotePlayers" to RemotePlayerSlots.MAX_REMOTE_PLAYERS
                )
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                when {
                    error != null -> onError(error.message)
                    committed -> { Log.d(TAG, "Firebase room created: $code maxPlayers=${RemotePlayerSlots.MAX_TOTAL_PLAYERS}"); onSuccess(code) }
                    attempt < 4 -> createRoomAttempt(attempt + 1, onSuccess, onError)
                    else -> onError("Could not allocate a unique room code")
                }
            }
        })
    }

    fun claimRemoteSlot(code: String, joinerId: String, onSuccess: (RemoteSlotAssignment) -> Unit, onError: (String) -> Unit) {
        if (!SessionSecurityPolicy.validRoomCode(code) || joinerId.isBlank()) return onError("Invalid join request")
        rooms.child(code).get().addOnSuccessListener { room ->
            if (!room.exists()) return@addOnSuccessListener onError("Room not found")
            val createdAt = room.child("createdAt").getValue(Long::class.java) ?: 0L
            if (!SessionSecurityPolicy.fresh(createdAt)) return@addOnSuccessListener onError("Room expired")
            if (room.child("status").getValue(String::class.java) !in setOf("waiting", "connected")) {
                return@addOnSuccessListener onError("Room is unavailable")
            }
            claimAvailableSlot(code, joinerId, onSuccess, onError)
        }.addOnFailureListener { onError(it.message ?: "Firebase lookup failed") }
    }

    private fun claimAvailableSlot(code: String, joinerId: String, onSuccess: (RemoteSlotAssignment) -> Unit, onError: (String) -> Unit) {
        val claims = rooms.child(code).child("claims")
        var assignedSlot: Int? = null
        claims.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                assignedSlot = null
                val alreadyAssigned = RemotePlayerSlots.activeRemoteSlots.firstOrNull { slot ->
                    data.child(slot.toString()).child("joinerId").getValue(String::class.java) == joinerId
                }
                val slot = alreadyAssigned ?: RemotePlayerSlots.firstAvailable(
                    RemotePlayerSlots.activeRemoteSlots.filterTo(mutableSetOf()) { data.child(it.toString()).value != null }
                ) ?: return Transaction.abort()
                data.child(slot.toString()).value = mapOf("joinerId" to joinerId, "claimedAt" to System.currentTimeMillis())
                assignedSlot = slot
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                val slot = assignedSlot
                when {
                    error != null -> onError(error.message)
                    !committed || slot == null -> onError("Session is full (Host + Player 2 + Player 3)")
                    else -> {
                        val claimRef = claims.child(slot.toString())
                        val disconnect = claimRef.onDisconnect()
                        disconnect.removeValue()
                            .addOnSuccessListener {
                                claimDisconnects[joinerId] = disconnect
                                rooms.child(code).child("status").setValue("connected")
                                Log.d(TAG, "P$slot remote slot claimed: room=$code joiner=$joinerId")
                                onSuccess(RemoteSlotAssignment(slot, joinerId))
                            }
                            .addOnFailureListener { failure ->
                                Log.w(TAG, "P$slot onDisconnect registration failed", failure)
                                releaseRemoteSlot(code, RemoteSlotAssignment(slot, joinerId))
                                onError("Could not protect the player slot from a stale disconnect")
                            }
                    }
                }
            }
        })
    }

    fun releaseRemoteSlot(code: String, assignment: RemoteSlotAssignment) {
        if (!SessionSecurityPolicy.validRoomCode(code) || !SessionSecurityPolicy.validRemoteSlot(assignment.playerSlot)) return
        claimDisconnects.remove(assignment.joinerId)?.cancel()
        val claim = rooms.child(code).child("claims").child(assignment.playerSlot.toString())
        claim.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (data.child("joinerId").getValue(String::class.java) != assignment.joinerId) return Transaction.abort()
                data.value = null
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) Log.w(TAG, "P${assignment.playerSlot} slot release failed: ${error.message}")
                else Log.d(TAG, "P${assignment.playerSlot} slot release committed=$committed")
            }
        })
    }

    fun listenForRemoteClaims(code: String, owner: String, onJoined: (Int, String) -> Unit, onLeft: (Int, String) -> Unit, onError: (String) -> Unit) {
        if (!SessionSecurityPolicy.validRoomCode(code)) return onError("Invalid room code")
        val query = rooms.child(code).child("claims")
        val known = mutableMapOf<Int, String>()
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) = updateClaim(snapshot)
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = updateClaim(snapshot)
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val slot = snapshot.key?.toIntOrNull()?.takeIf(SessionSecurityPolicy::validRemoteSlot) ?: return
                val joinerId = known.remove(slot) ?: snapshot.child("joinerId").getValue(String::class.java) ?: "unknown"
                Log.d(TAG, "P$slot claim removed: room=$code")
                onLeft(slot, joinerId)
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) = onError(error.message)

            private fun updateClaim(snapshot: DataSnapshot) {
                val slot = snapshot.key?.toIntOrNull()?.takeIf(SessionSecurityPolicy::validRemoteSlot) ?: return
                val joinerId = snapshot.child("joinerId").getValue(String::class.java) ?: return
                val previous = known.put(slot, joinerId)
                if (previous != null && previous != joinerId) onLeft(slot, previous)
                if (previous != joinerId) {
                    Log.d(TAG, "P$slot claim observed: room=$code")
                    onJoined(slot, joinerId)
                }
            }
        }
        query.addChildEventListener(listener)
        childListeners += ChildRegistration(owner, query, listener)
        Log.d(TAG, "Remote claim listener registered: room=$code owner=$owner")
    }

    fun saveOffer(code: String, slot: Int, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) =
        saveSdp(code, slot, "offer", sdp, onSuccess, onError)

    fun saveAnswer(code: String, slot: Int, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) =
        saveSdp(code, slot, "answer", sdp, onSuccess, onError)

    private fun saveSdp(code: String, slot: Int, name: String, sdp: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!validSignalTarget(code, slot) || name !in setOf("offer", "answer") || !SessionSecurityPolicy.validSdp(sdp)) {
            return onError("Invalid WebRTC description")
        }
        slotRef(code, slot).child(name).setValue(sdp)
            .addOnSuccessListener { Log.d(TAG, "P$slot Firebase $name stored: room=$code"); onSuccess() }
            .addOnFailureListener { onError(it.message ?: "Failed to save $name") }
    }

    fun listenForOffer(code: String, slot: Int, owner: String, onOffer: (String) -> Unit, onError: (String) -> Unit) =
        listenForSdp(code, slot, owner, "offer", onOffer, onError)

    fun listenForAnswer(code: String, slot: Int, owner: String, onAnswer: (String) -> Unit, onError: (String) -> Unit) =
        listenForSdp(code, slot, owner, "answer", onAnswer, onError)

    private fun listenForSdp(code: String, slot: Int, owner: String, name: String, onSdp: (String) -> Unit, onError: (String) -> Unit) {
        if (!validSignalTarget(code, slot)) return onError("Invalid signaling listener")
        val query = slotRef(code, slot).child(name)
        var delivered: String? = null
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val sdp = snapshot.getValue(String::class.java) ?: return
                if (sdp == delivered) return
                if (!SessionSecurityPolicy.validSdp(sdp)) return onError("Invalid WebRTC $name")
                delivered = sdp
                Log.d(TAG, "P$slot $name received: room=$code")
                onSdp(sdp)
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
        }
        query.addValueEventListener(listener)
        valueListeners += ValueRegistration(owner, query, listener)
        Log.d(TAG, "P$slot $name listener registered: room=$code owner=$owner")
    }

    fun saveIceCandidate(code: String, slot: Int, side: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int, onError: (String) -> Unit) {
        if (!validSignalTarget(code, slot) || !SessionSecurityPolicy.validSide(side) || !SessionSecurityPolicy.validIce(candidate, sdpMid, sdpMLineIndex)) {
            onError("Invalid ICE candidate")
            return
        }
        val data = mapOf("candidate" to candidate, "sdpMid" to sdpMid, "sdpMLineIndex" to sdpMLineIndex, "createdAt" to ServerValue.TIMESTAMP)
        slotRef(code, slot).child("${side}Candidates").push().setValue(data)
            .addOnSuccessListener { Log.d(TAG, "P$slot ICE candidate stored: room=$code side=$side") }
            .addOnFailureListener { onError(it.message ?: "Failed to save ICE candidate") }
    }

    fun listenForIceCandidates(code: String, slot: Int, side: String, owner: String, onCandidate: (String, String?, Int) -> Unit, onError: (String) -> Unit) {
        if (!validSignalTarget(code, slot) || !SessionSecurityPolicy.validSide(side)) return onError("Invalid ICE listener")
        val query = slotRef(code, slot).child("${side}Candidates")
        val delivered = HashSet<String>()
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (delivered.size >= SessionSecurityPolicy.MAX_PENDING_ICE) {
                    Log.w(TAG, "P$slot ICE candidate limit reached; extra signaling ignored")
                    return
                }
                val candidate = snapshot.child("candidate").getValue(String::class.java) ?: return
                val mid = snapshot.child("sdpMid").getValue(String::class.java)
                val line = snapshot.child("sdpMLineIndex").getValue(Long::class.java)?.toInt() ?: return
                val createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: return
                if (!SessionSecurityPolicy.fresh(createdAt) || !SessionSecurityPolicy.validIce(candidate, mid, line) || !delivered.add("$mid|$line|$candidate")) {
                    Log.w(TAG, "P$slot malformed, stale, or duplicate ICE candidate ignored")
                    return
                }
                Log.d(TAG, "P$slot ICE candidate received: room=$code side=$side")
                onCandidate(candidate, mid, line)
            }
            override fun onCancelled(error: DatabaseError) = onError(error.message)
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
        }
        query.addChildEventListener(listener)
        childListeners += ChildRegistration(owner, query, listener)
        Log.d(TAG, "P$slot ICE listener registered: room=$code side=$side owner=$owner")
    }

    fun clearSlotSignaling(code: String, slot: Int, onComplete: () -> Unit = {}) {
        if (!validSignalTarget(code, slot)) return onComplete()
        slotRef(code, slot).removeValue().addOnCompleteListener {
            Log.d(TAG, "P$slot signaling cleared: success=${it.isSuccessful}")
            onComplete()
        }
    }

    fun stopListening(owner: String) {
        valueListeners.filter { it.owner == owner }.forEach { it.query.removeEventListener(it.listener) }
        childListeners.filter { it.owner == owner }.forEach { it.query.removeEventListener(it.listener) }
        valueListeners.removeAll { it.owner == owner }
        childListeners.removeAll { it.owner == owner }
        Log.d(TAG, "Firebase signaling listeners removed: owner=$owner")
    }

    fun stopListening() {
        valueListeners.forEach { it.query.removeEventListener(it.listener) }
        childListeners.forEach { it.query.removeEventListener(it.listener) }
        valueListeners.clear()
        childListeners.clear()
        Log.d(TAG, "All Firebase signaling listeners removed")
    }

    fun deleteRoom(code: String) {
        if (!SessionSecurityPolicy.validRoomCode(code)) return
        rooms.child(code).removeValue().addOnCompleteListener { Log.d(TAG, "Host room removed: success=${it.isSuccessful}") }
    }

    private fun validSignalTarget(code: String, slot: Int) =
        SessionSecurityPolicy.validRoomCode(code) && SessionSecurityPolicy.validRemoteSlot(slot)

    private fun slotRef(code: String, slot: Int) = rooms.child(code).child("slots").child(slot.toString())
}
