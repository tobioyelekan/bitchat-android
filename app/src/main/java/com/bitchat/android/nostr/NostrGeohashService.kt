
package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.ui.GeoPerson
import com.bitchat.android.ui.colorForPeerSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.random.Random

/**
 * Service responsible for all Nostr and Geohash business logic extracted from ChatViewModel
 * Maintains 100% iOS compatibility and exact same functionality
 */
class NostrGeohashService(
    private val application: Application,
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val coroutineScope: CoroutineScope,
    private val dataManager: com.bitchat.android.ui.DataManager,
    private val notificationManager: com.bitchat.android.ui.NotificationManager
) {
    
    companion object {
        private const val TAG = "NostrGeohashService"
        
        @Volatile
        private var INSTANCE: NostrGeohashService? = null
        
        fun getInstance(application: Application): NostrGeohashService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: throw IllegalStateException("NostrGeohashService not initialized. Call initialize() first.")
            }
        }
        
        fun initialize(
            application: Application,
            state: ChatState,
            messageManager: MessageManager,
            privateChatManager: PrivateChatManager,
            meshDelegateHandler: MeshDelegateHandler,
            coroutineScope: CoroutineScope,
            dataManager: com.bitchat.android.ui.DataManager,
            notificationManager: com.bitchat.android.ui.NotificationManager
        ): NostrGeohashService {
            return synchronized(this) {
                INSTANCE ?: NostrGeohashService(
                    application,
                    state,
                    messageManager,
                    privateChatManager,
                    meshDelegateHandler,
                    coroutineScope,
                    dataManager,
                    notificationManager
                ).also { INSTANCE = it }
            }
        }
    }
    
    // MARK: - Nostr Message Integration Properties
    
    private val processedNostrEvents = mutableSetOf<String>()
    private val processedNostrEventOrder = mutableListOf<String>()
    private val maxProcessedNostrEvents = 2000
    // removed unused processedNostrAcks
    private val nostrKeyMapping = mutableMapOf<String, String>() // senderPeerID -> nostrPubkey
    
    // MARK: - Geohash Participant Tracking Properties
    
    private val geohashParticipants = mutableMapOf<String, MutableMap<String, Date>>() // geohash -> participantId -> lastSeen
    private var geohashSamplingJob: Job? = null
    private var geoParticipantsTimer: Job? = null
    
    // MARK: - Geohash Message History Properties
    
    private val geohashMessageHistory = mutableMapOf<String, MutableList<BitchatMessage>>() // geohash -> messages
    private val maxGeohashMessages = 1000 // Maximum messages per geohash
    
    // MARK: - Location Channel Management Properties
    
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null
    private var currentGeohashSubscriptionId: String? = null
    private var currentGeohashDmSubscriptionId: String? = null
    private var currentGeohash: String? = null
    private var geoNicknames: MutableMap<String, String> = mutableMapOf() // pubkeyHex(lowercased) -> nickname
    
    // MARK: - Initialization
    
    /**
     * Initialize Nostr relay subscriptions for gift wraps and geohash events
     */
    fun initializeNostrIntegration() {
        coroutineScope.launch {
            val nostrRelayManager = NostrRelayManager.getInstance(application)
            
            // Connect to relays
            nostrRelayManager.connect()
            
            // Get current Nostr identity
            val currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(application)
            if (currentIdentity == null) {
                Log.w(TAG, "No Nostr identity available for subscriptions")
                return@launch
            }
            
            // Subscribe to gift wraps (NIP-17 private messages)
            val dmFilter = NostrFilter.giftWrapsFor(
                pubkey = currentIdentity.publicKeyHex,
                since = System.currentTimeMillis() - 172800000L // Last 48 hours (align with NIP-17 randomization)
            )
            
            nostrRelayManager.subscribe(
                filter = dmFilter,
                id = "chat-messages",
                handler = { event ->
                    handleNostrMessage(event)
                }
            )
            
            Log.i(TAG, "✅ Nostr integration initialized with gift wrap subscription")
        }
    }

    /**
     * Panic/reset flow for Nostr + geohash systems.
     * - Disconnect and clear all Nostr relay subscriptions and caches
     * - Delete device-wide Nostr keys and per-geohash seed cache
     * - Clear geohash participants, nicknames, message history
     * - Recreate identity and reconnect; reinitialize subscriptions from scratch
     */
    fun panicResetNostrAndGeohash() {
        coroutineScope.launch {
            try {
                val relayManager = NostrRelayManager.getInstance(application)

                // 1) Disconnect from all relays
                relayManager.disconnect()

                // 2) Clear all subscription tracking and caches
                relayManager.clearAllSubscriptions()

                // 3) Clear Nostr identity (npub/private) and geohash identity cache/seed
                try {
                    NostrIdentityBridge.clearAllAssociations(application)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear Nostr associations: ${e.message}")
                }

                // 4) Clear local geohash state (participants, nicknames, message history)
                try {
                    geohashParticipants.clear()
                    geoNicknames.clear()
                    geohashMessageHistory.clear()
                    processedNostrEvents.clear()
                    processedNostrEventOrder.clear()
                    currentGeohashSubscriptionId = null
                    currentGeohashDmSubscriptionId = null
                    currentGeohash = null
                    state.setGeohashPeople(emptyList())
                    state.setTeleportedGeo(emptySet())
                    state.setGeohashParticipantCounts(emptyMap())
                    // Stop any timers/jobs
                    geohashSamplingJob?.cancel()
                    geohashSamplingJob = null
                    geoParticipantsTimer?.cancel()
                    geoParticipantsTimer = null
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear geohash state: ${e.message}")
                }

                // 5) Recreate identity and reconnect, then re-subscribe
                try {
                    // Touch identity to recreate
                    val identity = NostrIdentityBridge.getCurrentNostrIdentity(application)
                    if (identity == null) {
                        Log.w(TAG, "No identity after reset; skipping subscriptions")
                        return@launch
                    }

                    // Reconnect to relays and reinitialize subscriptions
                    relayManager.connect()
                    initializeNostrIntegration()
                    // Re-establish location channel state observers and subscriptions
                    initializeLocationChannelState()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reinitialize Nostr after reset: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "panicResetNostrAndGeohash error: ${e.message}")
            }
        }
    }
    
    /**
     * Initialize location channel state
     */
    fun initializeLocationChannelState() {
        try {
            // Initialize location channel manager safely
            locationChannelManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(application)
            
            // Observe location channel manager state and trigger channel switching
            locationChannelManager?.selectedChannel?.observeForever { channel ->
                state.setSelectedLocationChannel(channel)
                // CRITICAL FIX: Switch to the channel when selection changes
                switchLocationChannel(channel)
            }
            
            locationChannelManager?.teleported?.observeForever { teleported ->
                state.setIsTeleported(teleported)
            }
            
            Log.d(TAG, "✅ Location channel state initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize location channel state: ${e.message}")
            // Set default values in case of failure
            state.setSelectedLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
            state.setIsTeleported(false)
        }
    }
    
    // MARK: - Message Sending
    
    /**
     * Send message to geohash channel via Nostr ephemeral event
     */
    fun sendGeohashMessage(content: String, channel: com.bitchat.android.geohash.GeohashChannel, myPeerID: String, nickname: String?) {
        coroutineScope.launch {
            // Generate a temporary message ID for tracking animation
            val tempMessageId = "temp_${System.currentTimeMillis()}_${Random.nextInt(1000)}"
            try {
                // Add local echo message IMMEDIATELY (with temporary ID)
                val powSettingsLocal = PoWPreferenceManager.getCurrentSettings()
                val localMessage = BitchatMessage(
                    id = tempMessageId,
                    sender = nickname ?: myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = "geohash:${channel.geohash}",
                    channel = "#${channel.geohash}",
                    powDifficulty = if (powSettingsLocal.enabled) powSettingsLocal.difficulty else null
                )
                
                // Store immediately; UI will display from geohash history (not main mesh timeline)
                storeGeohashMessage(channel.geohash, localMessage)
                // IMPORTANT: Do not add to main mesh timeline to avoid duplication in mesh chat view
                // messageManager.addMessage(localMessage)
                
                Log.d(TAG, "📝 Added geohash local echo with temp ID: $tempMessageId (not shown in mesh timeline)")
                
                // Check if PoW is enabled before starting animation
                val powSettings = PoWPreferenceManager.getCurrentSettings()
                if (powSettings.enabled && powSettings.difficulty > 0) {
                    // Start matrix animation for this message
                    com.bitchat.android.ui.PoWMiningTracker.startMiningMessage(tempMessageId)
                    Log.d(TAG, "🎭 Started matrix animation for message: $tempMessageId")
                }
                
                // Now begin the async PoW process
                val identity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = channel.geohash,
                    context = application
                )
                
                val teleported = state.isTeleported.value ?: false
                
                val event = NostrProtocol.createEphemeralGeohashEvent(
                    content = content,
                    geohash = channel.geohash,
                    senderIdentity = identity,
                    nickname = nickname,
                    teleported = teleported
                )
                
                val nostrRelayManager = NostrRelayManager.getInstance(application)
                nostrRelayManager.sendEventToGeohash(
                    event = event,
                    geohash = channel.geohash,
                    includeDefaults = false,
                    nRelays = 5
                )
                
                Log.i(TAG, "📤 Sent geohash message to ${channel.geohash}: ${content.take(50)}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash message: ${e.message}")
            } finally {
                com.bitchat.android.ui.PoWMiningTracker.stopMiningMessage(tempMessageId)
            }
        }
    }
    
    // MARK: - Nostr Message Handling
    
    /**
     * Handle incoming Nostr message (gift wrap)
     */
    private fun handleNostrMessage(giftWrap: NostrEvent) {
        // Offload processing to avoid blocking UI
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            // Simple deduplication
            if (processedNostrEvents.contains(giftWrap.id)) return@launch
            processedNostrEvents.add(giftWrap.id)
        
        // Manage deduplication cache size
        processedNostrEventOrder.add(giftWrap.id)
        if (processedNostrEventOrder.size > maxProcessedNostrEvents) {
            val oldestId = processedNostrEventOrder.removeAt(0)
            processedNostrEvents.remove(oldestId)
        }
        
        // Client-side filtering: ignore messages older than 24 hours + 15 minutes buffer
        val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
        if (messageAge > 173700) { // 48 hours + 15 minutes
            return@launch
        }
        
            Log.d(TAG, "Processing Nostr message: ${giftWrap.id.take(16)}...")

        // Removed legacy NostrReadStore usage; rely on SeenMessageStore by message ID
        
            val currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(application)
            if (currentIdentity == null) {
                Log.w(TAG, "No Nostr identity available for decryption")
                return@launch
            }
        
        try {
            val decryptResult = NostrProtocol.decryptPrivateMessage(
                giftWrap = giftWrap,
                recipientIdentity = currentIdentity
            )
            
            if (decryptResult == null) {
                Log.w(TAG, "Failed to decrypt Nostr message")
                return@launch
            }
            
            val (content, senderPubkey, rumorTimestamp) = decryptResult
            
            // Expect embedded BitChat packet content
            if (!content.startsWith("bitchat1:")) {
                Log.d(TAG, "Ignoring non-embedded Nostr DM content")
                return@launch
            }
            
            val base64Content = content.removePrefix("bitchat1:")
            val packetData = base64URLDecode(base64Content)
            if (packetData == null) {
                Log.e(TAG, "Failed to decode base64url BitChat packet")
                return@launch
            }
            
            val packet = com.bitchat.android.protocol.BitchatPacket.fromBinaryData(packetData)
            if (packet == null) {
                Log.e(TAG, "Failed to parse embedded BitChat packet from Nostr DM")
                return@launch
            }
            
            // Only process noiseEncrypted envelope for private messages/receipts
            if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) {
                Log.w(TAG, "Unsupported embedded packet type: ${packet.type}")
                return@launch
            }
            
            // Validate recipient if present
            packet.recipientID?.let { rid ->
                val ridHex = rid.joinToString("") { "%02x".format(it) }
                // Note: myPeerID needs to be passed in as parameter
                // if (ridHex != myPeerID) return
            }
            
            // Parse plaintext typed payload (NoisePayload)
            val noisePayload = com.bitchat.android.model.NoisePayload.decode(packet.payload)
            if (noisePayload == null) {
                Log.e(TAG, "Failed to parse embedded NoisePayload")
                return@launch
            }
            
            // Map sender by Nostr pubkey to Noise key when possible
            val senderNoiseKey = findNoiseKeyForNostrPubkey(senderPubkey)
            val messageTimestamp = Date(rumorTimestamp * 1000L)

            // If we know the Noise key, try to resolve a currently connected mesh peer ID for it
            val targetPeerID: String = if (senderNoiseKey != null) {
                val meshPeerId = resolveMeshPeerIdForNoiseKey(senderNoiseKey)
                if (meshPeerId != null) {
                    // Also unify existing noise-hex/nostr-temp chats into this mesh peer
                    val noiseHex = senderNoiseKey.joinToString("") { b -> "%02x".format(b) }
                    val tempKey = "nostr_${senderPubkey.take(16)}"
                    unifyChatsIntoPeer(meshPeerId, listOf(noiseHex, tempKey))

                    // If currently viewing the temporary or noise-hex chat, auto-switch to mesh peer
                    val selected = state.getSelectedPrivateChatPeerValue()
                    if (selected == noiseHex || selected == tempKey) {
                        state.setSelectedPrivateChatPeer(meshPeerId)
                    }
                    meshPeerId
                } else {
                    senderNoiseKey.joinToString("") { b -> "%02x".format(b) }
                }
            } else {
                "nostr_${senderPubkey.take(16)}"
            }

            // Prefer nickname from mesh when connected; else fallback to favorites
            val senderNickname: String = if (senderNoiseKey != null) {
                val meshPeerId = resolveMeshPeerIdForNoiseKey(senderNoiseKey)
                if (meshPeerId != null) {
                    // Use live mesh nickname if available
                    meshDelegateHandler.getPeerInfo(meshPeerId)?.nickname
                        ?: getFavoriteNickname(senderNoiseKey)
                        ?: "Unknown"
                } else {
                    getFavoriteNickname(senderNoiseKey) ?: "Unknown"
                }
            } else {
                "Unknown"
            }
            
            // Store Nostr key mapping
            nostrKeyMapping[targetPeerID] = senderPubkey
            
            // Process payload and update UI/state
            processNoisePayload(noisePayload, targetPeerID, senderNickname, messageTimestamp)

            // If this was a private message, send a delivery ACK back over Nostr
            if (noisePayload.type == com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE) {
                val pm = com.bitchat.android.model.PrivateMessagePacket.decode(noisePayload.data)
                pm?.let { pmsg ->
                    val seen = com.bitchat.android.services.SeenMessageStore.getInstance(application)
                    if (!seen.hasDelivered(pmsg.messageID)) {
                        val nostrTransport = NostrTransport.getInstance(application)
                        if (senderNoiseKey != null) {
                            val peerIdHex = senderNoiseKey.joinToString("") { b -> "%02x".format(b) }
                            nostrTransport.sendDeliveryAck(pmsg.messageID, peerIdHex)
                        } else {
                            val identity = NostrIdentityBridge.getCurrentNostrIdentity(application)
                            if (identity != null) {
                                nostrTransport.sendDeliveryAckGeohash(pmsg.messageID, senderPubkey, identity)
                            }
                        }
                        seen.markDelivered(pmsg.messageID)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Nostr message: ${e.message}")
        }
        }
    }
    
    /**
     * Process NoisePayload from Nostr message
     */
    private suspend fun processNoisePayload(
        noisePayload: com.bitchat.android.model.NoisePayload,
        targetPeerID: String,
        senderNickname: String,
        messageTimestamp: Date
    ) {
        when (noisePayload.type) {
            com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = com.bitchat.android.model.PrivateMessagePacket.decode(noisePayload.data)
                if (pm == null) {
                    Log.e(TAG, "Failed to decode PrivateMessagePacket")
                    return
                }
                
                val messageId = pm.messageID
                val messageContent = pm.content
                val seen = com.bitchat.android.services.SeenMessageStore.getInstance(application)
                val suppressUnread = seen.hasRead(messageId)
                
                // Handle favorite/unfavorite notifications
                if (messageContent.startsWith("[FAVORITED]") || messageContent.startsWith("[UNFAVORITED]")) {
                    handleFavoriteNotification(messageContent, targetPeerID, senderNickname)
                    return
                }
                
                // Check for duplicate message
                val existingChats = state.getPrivateChatsValue()
                var messageExists = false
                for ((_, messages) in existingChats) {
                    if (messages.any { it.id == messageId }) {
                        messageExists = true
                        break
                    }
                }
                if (messageExists) return
                
                // Check if viewing this chat
                val isViewingThisChat = state.getSelectedPrivateChatPeerValue() == targetPeerID
                
                // Create BitchatMessage
                val message = BitchatMessage(
                    id = messageId,
                    sender = senderNickname,
                    content = messageContent,
                    timestamp = messageTimestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = targetPeerID,
                    deliveryStatus = com.bitchat.android.model.DeliveryStatus.Delivered(
                        to = state.getNicknameValue() ?: "Unknown",
                        at = Date()
                    )
                )
                
                // Add to private chats on Main
                withContext(Dispatchers.Main) {
                    privateChatManager.handleIncomingPrivateMessage(message, suppressUnread)
                }

                // Send read receipt if viewing (only once across restarts)
                if (isViewingThisChat && !seen.hasRead(messageId)) {
                    try {
                        val rr = com.bitchat.android.model.ReadReceipt(originalMessageID = messageId)
                        NostrTransport.getInstance(application).sendReadReceipt(rr, targetPeerID)
                        seen.markRead(messageId)
                    } catch (_: Exception) { }
                }
                
                Log.i(TAG, "📥 Processed Nostr private message from $senderNickname")
            }
            
            com.bitchat.android.model.NoisePayloadType.DELIVERED -> {
                val messageId = String(noisePayload.data, Charsets.UTF_8)
                // Use the existing delegate to handle delivery acknowledgment on Main
                withContext(Dispatchers.Main) {
                    meshDelegateHandler.didReceiveDeliveryAck(messageId, targetPeerID)
                }
                Log.d(TAG, "📥 Processed Nostr delivery ACK for message $messageId")
            }
            
            com.bitchat.android.model.NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(noisePayload.data, Charsets.UTF_8)
                // Use the existing delegate to handle read receipt on Main
                withContext(Dispatchers.Main) {
                    meshDelegateHandler.didReceiveReadReceipt(messageId, targetPeerID)
                }
                Log.d(TAG, "📥 Processed Nostr read receipt for message $messageId")
            }
        }
    }
    
    /**
     * Find Noise key for Nostr pubkey from favorites
     */
    private fun findNoiseKeyForNostrPubkey(nostrPubkey: String): ByteArray? {
        return com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(nostrPubkey)
    }
    
    /**
     * Get favorite nickname for Noise key
     */
    private fun getFavoriteNickname(noiseKey: ByteArray): String? {
        return com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)?.peerNickname
    }

    /**
     * Resolve a currently-connected mesh peer ID for a given Noise public key
     * by matching against the mesh service's peer info noisePublicKey values.
     */
    private fun resolveMeshPeerIdForNoiseKey(noiseKey: ByteArray): String? {
        return try {
            val peers: List<String> = state.getConnectedPeersValue()
            peers.firstOrNull { peerId: String ->
                val info = meshDelegateHandler.getPeerInfo(peerId)
                info?.noisePublicKey?.contentEquals(noiseKey) == true
            }
        } catch (_: Exception) { null }
    }

    /**
     * Merge any chats stored under the given keys into the target peer's chat entry
     * so messages received while offline appear in the same chat when the peer connects.
     */
    private fun unifyChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(state, targetPeerID, keysToMerge)
    }
    
    /**
     * Handle favorite/unfavorite notification
     */
    private fun handleFavoriteNotification(content: String, fromPeerID: String, senderNickname: String) {
        val isFavorite = content.startsWith("[FAVORITED]")
        val action = if (isFavorite) "favorited" else "unfavorited"

        // Try to extract npub after colon, if present
        val npub = content.substringAfter(":", "").trim().takeIf { it.startsWith("npub1") }

        // Resolve noise key if possible and persist relationship + npub mapping
        try {
            var noiseKey: ByteArray? = null
            // If fromPeerID looks like hex (noise key), decode
            val hexRegex = Regex("^[0-9a-fA-F]+$")
            if (fromPeerID.matches(hexRegex) && (fromPeerID.length % 2 == 0)) {
                // Expect 64 hex chars for full Curve25519 key; accept others best-effort
                val bytes = fromPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                // Use only if length is plausible
                if (bytes.isNotEmpty()) noiseKey = bytes
            } else {
                // fromPeerID likely a temporary key like "nostr_..."; map to Nostr pubkey
                val senderPubkey = nostrKeyMapping[fromPeerID]
                if (senderPubkey != null) {
                    noiseKey = findNoiseKeyForNostrPubkey(senderPubkey)
                }
            }

            if (noiseKey != null) {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, isFavorite)
                if (npub != null) {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, npub)
                }
            }
        } catch (_: Exception) {
            // Best-effort
        }

        // Determine guidance text based on mutual status (iOS-style)
        val guidance = try {
            val rel = run {
                // Try to resolve via noise key directly or mapping
                var key: ByteArray? = null
                val hexRegex = Regex("^[0-9a-fA-F]+$")
                if (fromPeerID.matches(hexRegex) && (fromPeerID.length % 2 == 0)) {
                    key = fromPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } else {
                    val mappedNostr = nostrKeyMapping[fromPeerID]
                    key = mappedNostr?.let { findNoiseKeyForNostrPubkey(it) }
                }
                key?.let { com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(it) }
            }
            if (isFavorite) {
                if (rel?.isFavorite == true) {
                    " — mutual! You can continue DMs via Nostr when out of mesh."
                } else {
                    " — favorite back to continue DMs later."
                }
            } else {
                ". DMs over Nostr will pause unless you both favorite again."
            }
        } catch (_: Exception) { "" }

        // Show system message
        val systemMessage = BitchatMessage(
            sender = "system",
            content = "$senderNickname $action you$guidance",
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)

        Log.i(TAG, "📥 Processed favorite notification: $senderNickname $action you")
    }
    
    /**
     * Base64URL decode (without padding)
     */
    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
    
    // MARK: - Geohash Message History
    
    /**
     * Store a message in geohash history
     */
    private fun storeGeohashMessage(geohash: String, message: BitchatMessage) {
        val messages = geohashMessageHistory.getOrPut(geohash) { mutableListOf() }
        messages.add(message)
        
        // Limit message history to prevent memory issues
        if (messages.size > maxGeohashMessages) {
            messages.removeAt(0) // Remove oldest message
        }
        
        Log.v(TAG, "📦 Stored message in geohash $geohash history (${messages.size} total) - sender: ${message.sender}, content: '${message.content.take(30)}...'")
    }
    
    /**
     * Load stored messages for a geohash channel
     */
    fun loadGeohashMessages(geohash: String) {
        val storedMessages = geohashMessageHistory[geohash]
        if (storedMessages == null) {
            Log.d(TAG, "📥 No stored messages found for geohash $geohash")
            return
        }
        
        Log.d(TAG, "📥 Loading ${storedMessages.size} stored messages for geohash $geohash")
        
        // Add all stored messages to the current message timeline
        storedMessages.forEach { message ->
            Log.v(TAG, "📥 Loading stored message: ${message.sender} - '${message.content.take(30)}...'")
            messageManager.addMessage(message)
        }
    }

    /**
     * Get stored messages for a geohash without mutating UI state
     */
    fun getGeohashMessages(geohash: String): List<BitchatMessage> {
        return geohashMessageHistory[geohash]?.toList() ?: emptyList()
    }
    
    /**
     * Clear geohash message history
     */
    fun clearGeohashMessageHistory() {
        geohashMessageHistory.clear()
    }
    
    // MARK: - Geohash Participant Tracking
    
    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000) // 5 minutes ago
        val participants = geohashParticipants[geohash] ?: return 0
        
        // Remove expired participants
        val iterator = participants.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.before(cutoff)) {
                iterator.remove()
            }
        }
        
        return participants.size
    }
    
    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        // Cancel existing sampling
        geohashSamplingJob?.cancel()
        
        if (geohashes.isEmpty()) return
        
        Log.d(TAG, "🌍 Beginning geohash sampling for ${geohashes.size} geohashes")
        
        geohashSamplingJob = coroutineScope.launch {
            val nostrRelayManager = NostrRelayManager.getInstance(application)
            
            // Subscribe to each geohash for ephemeral events (kind 20000) using geohash-specific relays
            geohashes.forEach { geohash ->
                val filter = NostrFilter.geohashEphemeral(
                    geohash = geohash,
                    since = System.currentTimeMillis() - 86400000L, // Last 24 hours
                    limit = 200
                )
                
                nostrRelayManager.subscribeForGeohash(
                    geohash = geohash,
                    filter = filter,
                    id = "geohash-$geohash",
                    handler = { event ->
                        handleUnifiedGeohashEvent(event, geohash)
                    },
                    includeDefaults = false,
                    nRelays = 5
                )
                
                Log.d(TAG, "Subscribed to geohash events for: $geohash")
            }
        }
    }
    
    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        Log.d(TAG, "🌍 Ending geohash sampling")
        geohashSamplingJob?.cancel()
        geohashSamplingJob = null
    }
    
    /**
     * Update participant activity for a geohash
     */
    private fun updateGeohashParticipant(geohash: String, participantId: String, lastSeen: Date) {
        val participants = geohashParticipants.getOrPut(geohash) { mutableMapOf() }
        participants[participantId] = lastSeen
        
        // Update geohash people list if this is the current geohash
        if (currentGeohash == geohash) {
            refreshGeohashPeople()
        }
        
        // CRITICAL FIX: Force UI recomposition by updating reactive participant counts for location channel selector
        // This ensures that the location channels sheet shows live participant counts for ALL geohashes
        updateReactiveParticipantCounts()
    }
    
    /**
     * Update reactive participant counts for real-time location channel selector (CRITICAL FIX)
     */
    private fun updateReactiveParticipantCounts() {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000) // 5 minutes ago
        val counts = mutableMapOf<String, Int>()
        
        // Calculate current participant counts for all geohashes with recent activity
        for ((geohash, participants) in geohashParticipants) {
            // CRITICAL BUG FIX: Count active participants WITHOUT mutating original data
            // Don't remove from original structure - just count active ones
            val activeCount = participants.values.count { lastSeen ->
                !lastSeen.before(cutoff)
            }
            
            // Store the current count
            counts[geohash] = activeCount
        }
        
        // CRITICAL: Update reactive state to trigger UI recomposition
        state.setGeohashParticipantCounts(counts)
        
        Log.v(TAG, "🔄 Updated reactive participant counts: ${counts.size} geohashes with activity")
    }
    
    /**
     * Record geohash participant by pubkey hex (iOS-compatible)
     */
    private fun recordGeoParticipant(pubkeyHex: String) {
        currentGeohash?.let { geohash ->
            updateGeohashParticipant(geohash, pubkeyHex, Date())
        }
    }
    
    /**
     * Refresh geohash people list from current participants (iOS-compatible)
     */
    private fun refreshGeohashPeople() {
        val geohash = currentGeohash
        if (geohash == null) {
            state.setGeohashPeople(emptyList())
            return
        }
        
        // Use 5-minute activity window (matches iOS exactly)
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val participants = geohashParticipants[geohash] ?: mutableMapOf()
        
        // Remove expired participants
        val iterator = participants.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.before(cutoff)) {
                iterator.remove()
            }
        }
        geohashParticipants[geohash] = participants
        
        // Build GeoPerson list
        val people = participants.map { (pubkeyHex, lastSeen) ->
            val displayName = displayNameForNostrPubkey(pubkeyHex)
            //Log.v(TAG, "🏷️ Participant ${pubkeyHex.take(8)} -> displayName: $displayName")
            GeoPerson(
                id = pubkeyHex.lowercase(),
                displayName = displayName,
                lastSeen = lastSeen
            )
        }.sortedByDescending { it.lastSeen } // Most recent first
        
        state.setGeohashPeople(people)
        //Log.d(TAG, "🌍 Refreshed geohash people: ${people.size} participants in $geohash")

    }
    
    /**
     * Start participant refresh timer for geohash channels (iOS-compatible)
     */
    private fun startGeoParticipantsTimer() {
        // Cancel existing timer
        geoParticipantsTimer?.cancel()
        
        // Start 30-second refresh timer (matches iOS)
        geoParticipantsTimer = coroutineScope.launch {
            while (currentGeohash != null) {
                delay(30000) // 30 seconds
                refreshGeohashPeople()
            }
        }
    }
    
    /**
     * Stop participant refresh timer
     */
    private fun stopGeoParticipantsTimer() {
        geoParticipantsTimer?.cancel()
        geoParticipantsTimer = null
    }
    
    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return state.getTeleportedGeoValue().contains(pubkeyHex.lowercase())
    }
    
    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String, onStartPrivateChat: (String) -> Unit) {
        val convKey = "nostr_${pubkeyHex.take(16)}"
        nostrKeyMapping[convKey] = pubkeyHex
        onStartPrivateChat(convKey)
        Log.d(TAG, "🗨️ Started geohash DM with $pubkeyHex -> $convKey")
    }
    
    /**
     * Get the Nostr key mapping for geohash DMs
     */
    fun getNostrKeyMapping(): Map<String, String> {
        return nostrKeyMapping.toMap()
    }
    
    /**
     * Send read receipt for geohash DM
     */
    private fun sendGeohashReadReceipt(messageID: String, recipientPubkey: String, geohash: String) {
        coroutineScope.launch {
            try {
                // Derive geohash-specific identity for sending
                val senderIdentity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = geohash,
                    context = application
                )
                
                // Send via Nostr transport
                val nostrTransport = NostrTransport.getInstance(application)
                // Set sender peer ID (get from mesh service or use a placeholder)
                nostrTransport.senderPeerID = "geohash:$geohash"
                nostrTransport.sendReadReceiptGeohash(
                    messageID = messageID,
                    toRecipientHex = recipientPubkey,
                    fromIdentity = senderIdentity
                )
                
                Log.d(TAG, "📤 Sent geohash read receipt for $messageID to ${recipientPubkey.take(8)}...")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash read receipt: ${e.message}")
            }
        }
    }
    
    // MARK: - Location Channel Management
    
    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        locationChannelManager?.select(channel) ?: run {
            Log.w(TAG, "Cannot select location channel - LocationChannelManager not initialized")
        }
    }
    
    /**
     * Switch to location channel and set up proper Nostr subscriptions (iOS-compatible)
     * Optimized for non-blocking UI with immediate feedback
     */
    private fun switchLocationChannel(channel: com.bitchat.android.geohash.ChannelID?) {
        // STEP 1: Immediate UI updates (synchronous, no blocking)
        try {
            // NOTE: Don't clear messages here - let ChatScreen's displayMessages logic handle what to show
            // This preserves mesh message history when switching between views
            
            when (channel) {
                is com.bitchat.android.geohash.ChannelID.Mesh -> {
                    Log.d(TAG, "📡 Switched to mesh channel")
                    // Immediate UI state updates
                    currentGeohash = null
                    // Update notification manager with current geohash
                    notificationManager.setCurrentGeohash(null)
                    // Clear mesh mention notifications since user is now viewing mesh chat
                    notificationManager.clearMeshMentionNotifications()
                    // Note: Don't clear geoNicknames - keep cached for when we return to location channels
                    stopGeoParticipantsTimer()
                    state.setGeohashPeople(emptyList())
                    state.setTeleportedGeo(emptySet())
                }
                
                is com.bitchat.android.geohash.ChannelID.Location -> {
                    Log.d(TAG, "📍 Switching to geohash channel: ${channel.channel.geohash}")
                    currentGeohash = channel.channel.geohash
                    // Update notification manager with current geohash
                    notificationManager.setCurrentGeohash(channel.channel.geohash)
                    // Clear notifications for this geohash since user is now viewing it
                    notificationManager.clearNotificationsForGeohash(channel.channel.geohash)
                    // Note: Don't clear geoNicknames - they contain cached nicknames for all geohashes
                    // Note: Don't load messages here - ChatScreen will get them via getGeohashMessages()
                    
                    // Immediate self-registration for instant UI feedback
                    try {
                        val identity = NostrIdentityBridge.deriveIdentity(
                            forGeohash = channel.channel.geohash,
                            context = application
                        )
                        recordGeoParticipant(identity.publicKeyHex)
                        
                        // Mark teleported state immediately
                        val teleported = state.isTeleported.value ?: false
                        if (teleported) {
                            val currentTeleported = state.getTeleportedGeoValue().toMutableSet()
                            currentTeleported.add(identity.publicKeyHex.lowercase())
                            state.setTeleportedGeo(currentTeleported)
                        }
                        
                        Log.d(TAG, "📍 Immediate self-registration completed for geohash UI")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed immediate identity setup: ${e.message}")
                    }
                    
                    // Start participant refresh timer immediately
                    startGeoParticipantsTimer()
                    
                    // Force immediate refresh to show any cached nicknames
                    refreshGeohashPeople()
                }
                
                null -> {
                    Log.d(TAG, "📡 No channel selected")
                    currentGeohash = null
                    // Note: Don't clear geoNicknames - keep cached nicknames for when we return
                    stopGeoParticipantsTimer()
                    state.setGeohashPeople(emptyList())
                    state.setTeleportedGeo(emptySet())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in immediate channel switch: ${e.message}")
        }
        
        // STEP 2: Async subscription setup (non-blocking background)
        coroutineScope.launch {
            try {
                Log.d(TAG, "🔄 Starting async subscription setup...")
                
                // Clear processed events when switching channels to get fresh timeline
                processedNostrEvents.clear()
                processedNostrEventOrder.clear()
                
                // Unsubscribe from previous geohash ephemeral events (async)
                currentGeohashSubscriptionId?.let { subId ->
                    try {
                        val nostrRelayManager = NostrRelayManager.getInstance(application)
                        nostrRelayManager.unsubscribe(subId)
                        currentGeohashSubscriptionId = null
                        Log.d(TAG, "🔄 Unsubscribed from previous geohash ephemeral events: $subId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unsubscribe from geohash events: ${e.message}")
                    }
                }
                
                // Unsubscribe from previous geohash DMs (async)
                currentGeohashDmSubscriptionId?.let { dmSubId ->
                    try {
                        val nostrRelayManager = NostrRelayManager.getInstance(application)
                        nostrRelayManager.unsubscribe(dmSubId)
                        currentGeohashDmSubscriptionId = null
                        Log.d(TAG, "🔄 Unsubscribed from previous geohash DMs: $dmSubId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to unsubscribe from DMs: ${e.message}")
                    }
                }
                
                // Setup new subscriptions for location channels
                if (channel is com.bitchat.android.geohash.ChannelID.Location) {
                    Log.d(TAG, "🌐 Setting up Nostr subscriptions for geohash: ${channel.channel.geohash}")
                    
                    try {
                        val nostrRelayManager = NostrRelayManager.getInstance(application)
                        
                        // Subscribe to geohash ephemeral events for this specific channel using geohash-specific relays
                        val geohashSubId = "geohash-${channel.channel.geohash}"
                        currentGeohashSubscriptionId = geohashSubId
                        
                        val geohashFilter = NostrFilter.geohashEphemeral(
                            geohash = channel.channel.geohash,
                            since = System.currentTimeMillis() - 3600000L, // Last hour for channel messages
                            limit = 200
                        )
                        
                        nostrRelayManager.subscribeForGeohash(
                            geohash = channel.channel.geohash,
                            filter = geohashFilter,
                            id = geohashSubId,
                            handler = { event ->
                                handleUnifiedGeohashEvent(event, channel.channel.geohash)
                            },
                            includeDefaults = false,
                            nRelays = 5
                        )
                        
                        Log.i(TAG, "✅ Subscribed to geohash ephemeral events: #${channel.channel.geohash}")
                        
                        // Subscribe to DMs for this channel's identity
                        val dmIdentity = NostrIdentityBridge.deriveIdentity(
                            forGeohash = channel.channel.geohash,
                            context = application
                        )
                        
                        val dmSubId = "geo-dm-${channel.channel.geohash}"
                        currentGeohashDmSubscriptionId = dmSubId
                        
                        val dmFilter = NostrFilter.giftWrapsFor(
                            pubkey = dmIdentity.publicKeyHex,
                            since = System.currentTimeMillis() - 172800000L // Last 48 hours (align with NIP-17 randomization)
                        )
                        
                        // IMPORTANT: For geohash DMs, use default relays (iOS behavior)
                        nostrRelayManager.subscribe(
                            filter = dmFilter,
                            id = dmSubId,
                            handler = { giftWrap ->
                                handleGeohashDmEvent(giftWrap, channel.channel.geohash, dmIdentity)
                            },
                            targetRelayUrls = null
                        )
                        
                        Log.i(TAG, "✅ Subscribed to geohash DMs for identity: ${dmIdentity.publicKeyHex.take(16)}...")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to setup geohash subscriptions: ${e.message}")
                    }
                }
                
                Log.d(TAG, "✅ Async subscription setup completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed in async channel switching: ${e.message}")
            }
        }
    }
    
    /**
     * Unified handler for all geohash ephemeral events (kind 20000)
     * Handles participant tracking, nickname caching, message display, and teleport state
     */
    private fun handleUnifiedGeohashEvent(event: NostrEvent, geohash: String) {
        coroutineScope.launch(Dispatchers.Default) {
        try {
            Log.v(TAG, "🔍 handleUnifiedGeohashEvent called - subGeohash: $geohash, currentGeohash: $currentGeohash, kind: ${event.kind}, id: ${event.id.take(8)}...")
            
            // Only handle ephemeral kind 20000 events
            if (event.kind != 20000) {
                Log.v(TAG, "❌ Skipping non-ephemeral event (kind ${event.kind})")
                return@launch
            }

            // VALIDATE EVENT G TAG AGAINST SUBSCRIPTION GEOHASH
            val eventGeohash = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
            if (eventGeohash == null) {
                Log.w(TAG, "🚫 Dropping kind=20000 without 'g' tag id=${event.id.take(8)}")
                return@launch
            }
            if (!eventGeohash.equals(geohash, ignoreCase = true)) {
                Log.w(TAG, "🚫 Dropping mismatched geohash event: sub=$geohash eventTag=$eventGeohash id=${event.id.take(8)}")
                return@launch
            }
            
            // Check Proof of Work validation BEFORE other processing
            val powSettings = PoWPreferenceManager.getCurrentSettings()
            if (powSettings.enabled && powSettings.difficulty > 0) {
                if (!NostrProofOfWork.validateDifficulty(event, powSettings.difficulty)) {
                    Log.w(TAG, "🚫 Rejecting geohash event ${event.id.take(8)}... due to insufficient PoW (required: ${powSettings.difficulty})")
                    return@launch
                }
                Log.v(TAG, "✅ PoW validation passed for event ${event.id.take(8)}...")
            }
            
            // Check if this user is blocked in geohash channels BEFORE any processing
            if (isGeohashUserBlocked(event.pubkey)) {
                Log.v(TAG, "🚫 Skipping event from blocked geohash user: ${event.pubkey.take(8)}...")
                return@launch
            }
            
            // Deduplicate events
            if (processedNostrEvents.contains(event.id)) {
                Log.v(TAG, "❌ Skipping duplicate event ${event.id.take(8)}...")
                return@launch
            }
            processedNostrEvents.add(event.id)
            
            // Manage deduplication cache size
            processedNostrEventOrder.add(event.id)
            if (processedNostrEventOrder.size > maxProcessedNostrEvents) {
                val oldestId = processedNostrEventOrder.removeAt(0)
                processedNostrEvents.remove(oldestId)
            }
            
            // STEP 1: Always update participant activity for all geohashes (for location channel list)
            val timestamp = Date(event.createdAt * 1000L)
            withContext(Dispatchers.Main) { updateGeohashParticipant(geohash, event.pubkey, timestamp) }
            
            // STEP 2: Always cache nickname from tag if present (for all geohashes)
            event.tags.find { it.size >= 2 && it[0] == "n" }?.let { nickTag ->
                val nick = nickTag[1]
                val pubkeyLower = event.pubkey.lowercase()
                val previousNick = geoNicknames[pubkeyLower]
                geoNicknames[pubkeyLower] = nick
                Log.v(TAG, "📝 Cached nickname for ${event.pubkey.take(8)}: $nick")
                
                // If this is a new nickname or nickname change for current geohash, refresh people list
                if (previousNick != nick && currentGeohash == geohash) {
                    withContext(Dispatchers.Main) { refreshGeohashPeople() }
                }
            }
            
            // STEP 3: Always track teleport tag for participants (iOS-compatible)
            event.tags.find { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }?.let {
                val key = event.pubkey.lowercase()
                val currentTeleported = state.getTeleportedGeoValue().toMutableSet()
                if (!currentTeleported.contains(key)) {
                    currentTeleported.add(key)
                    withContext(Dispatchers.Main) { state.setTeleportedGeo(currentTeleported) }
                    Log.d(TAG, "📍 Marked geohash participant as teleported: ${event.pubkey.take(8)}...")
                }
            }
            
            // STEP 4: Skip our own events for message display (we already locally echoed)
            val myGeoIdentity = NostrIdentityBridge.deriveIdentity(
                forGeohash = geohash,
                context = application
            )
            if (myGeoIdentity.publicKeyHex.lowercase() == event.pubkey.lowercase()) {
                return@launch
            }
            
            // STEP 5: Store mapping for potential geohash DM initiation
            val key16 = "nostr_${event.pubkey.take(16)}"
            val key8 = "nostr:${event.pubkey.take(8)}"
            nostrKeyMapping[key16] = event.pubkey
            nostrKeyMapping[key8] = event.pubkey
            
            // STEP 6: Process message content for display (only for current/visible geohash)
            // Skip empty teleport presence events
            val isTeleportPresence = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" } && 
                                     event.content.trim().isEmpty()
            if (isTeleportPresence) {
                Log.v(TAG, "Skipping empty teleport presence event")
                return@launch
            }
            
            val senderHandle = displayNameForNostrPubkey(event.pubkey)
            val senderName = displayNameForNostrPubkeyUI(event.pubkey)
            val content = event.content
            
            // Preserve Nostr event time to avoid reordering
            val messageTimestamp = Date(event.createdAt.toLong() * 1000L)
            // Note: mentions parsing needs peer nicknames parameter
            // val mentions = messageManager.parseMentions(content, peerNicknames, nickname)
            
            // Calculate actual PoW difficulty from the finalized event ID so we can show it for incoming messages too
            val eventHasNonseTag = event.tags.any { it.isNotEmpty() && it[0] == "nonce" }
            val actualPow = try { NostrProofOfWork.calculateDifficulty(event.id) } catch (e: Exception) { 0 }

            val message = BitchatMessage(
                id = event.id,
                sender = senderName,
                content = content,
                timestamp = messageTimestamp,
                isRelay = false,
                originalSender = senderHandle,
                senderPeerID = "nostr:${event.pubkey.take(8)}",
                mentions = null, // mentions need to be passed from outside
                channel = "#$geohash",
                powDifficulty = actualPow.takeIf { it > 0 && eventHasNonseTag } ?: null
            )
            
            // Store in geohash history for persistence across channel switches
            storeGeohashMessage(geohash, message)
            
            // NOTE: Don't add to main message timeline here - ChatScreen will display geohash messages
            // from the separate geohash history via getGeohashMessages()
            
            // NOTIFICATION LOGIC: Check for mentions and first messages
            checkAndTriggerGeohashNotifications(geohash, senderName, content, message)
            
            Log.d(TAG, "📥 Unified geohash event processed - geohash: $geohash, sender: $senderName, content: ${content.take(50)}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling unified geohash event: ${e.message}")
        }
        }
    }
    
    /**
     * Check and trigger geohash notifications for mentions and first messages
     */
    private suspend fun checkAndTriggerGeohashNotifications(
        geohash: String,
        senderName: String,
        content: String,
        message: BitchatMessage
    ) {
        try {
            // Get user's current nickname
            val currentNickname = state.getNicknameValue()
            if (currentNickname.isNullOrEmpty()) {
                return
            }
            
            // Check if this message mentions the current user
            val isMention = checkForMention(content, currentNickname)
            
            // Check if this is the first message in a subscribed geohash chat
            val isFirstMessage = checkIfFirstMessage(geohash, message)
            
            // Only trigger notifications if we have a mention or first message
            if (isMention || isFirstMessage) {
                Log.d(TAG, "🔔 Triggering geohash notification - geohash: $geohash, mention: $isMention, first: $isFirstMessage")
                
                withContext(Dispatchers.Main) {
                    // Sanitize mention for notifications: hide '#hash' from @username#hash if not needed
                    val contentForNotification = if (isMention) {
                        var sanitized = content
                        try {
                            val myGeoIdentity = NostrIdentityBridge.deriveIdentity(
                                forGeohash = geohash,
                                context = application
                            )
                            val myDisplay = displayNameForNostrPubkeyUI(myGeoIdentity.publicKeyHex)
                            val needsDisambiguation = myDisplay.contains("#")
                            if (!needsDisambiguation) {
                                val pattern = ("@" + java.util.regex.Pattern.quote(currentNickname) + "#[a-fA-F0-9]{4}\\b").toRegex()
                                sanitized = sanitized.replace(pattern, "@" + currentNickname)
                            }
                        } catch (_: Exception) { }
                        sanitized
                    } else content
                    
                    val locationName = getLocationNameForGeohash(geohash)
                    notificationManager.showGeohashNotification(
                        geohash = geohash,
                        senderNickname = senderName,
                        messageContent = contentForNotification,
                        isMention = isMention,
                        isFirstMessage = isFirstMessage,
                        locationName = locationName
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking geohash notifications: ${e.message}")
        }
    }
    
    /**
     * Check if the content mentions the current user with @nickname#hash format
     */
    private fun checkForMention(content: String, currentNickname: String): Boolean {
        // iOS-style mention pattern: @nickname#1234 or @nickname
        val mentionPattern = "@([\\p{L}0-9_]+(?:#[a-fA-F0-9]{4})?)".toRegex()
        
        return mentionPattern.findAll(content).any { match ->
            val mentionWithoutAt = match.groupValues[1]
            // Split the mention to get base nickname (without #hash suffix)
            val baseName = if (mentionWithoutAt.contains("#")) {
                mentionWithoutAt.substringBeforeLast("#")
            } else {
                mentionWithoutAt
            }
            
            // Check if the base name matches current user's nickname
            baseName.equals(currentNickname, ignoreCase = true)
        }
    }
    
    /**
     * Check if this is the first message in a subscribed geohash chat
     */
    private fun checkIfFirstMessage(geohash: String, message: BitchatMessage): Boolean {
        // Get the message history for this geohash
        val messageHistory = geohashMessageHistory[geohash] ?: return true
        
        // Filter out our own messages (local echoes and messages from our identity)
        val otherUserMessages = messageHistory.filter { msg ->
            // Check if this is our own message by comparing sender ID with our geohash identity
            try {
                val myGeoIdentity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = geohash,
                    context = application
                )
                val myIdentityId = "nostr:${myGeoIdentity.publicKeyHex.take(8)}"
                msg.senderPeerID != myIdentityId && msg.senderPeerID != message.senderPeerID
            } catch (e: Exception) {
                // If we can't determine identity, assume it's not our message
                msg.senderPeerID != message.senderPeerID
            }
        }
        
        // This is a first message if there are no other user messages and this isn't from us
        val isFromUs = try {
            val myGeoIdentity = NostrIdentityBridge.deriveIdentity(
                forGeohash = geohash,
                context = application
            )
            val myIdentityId = "nostr:${myGeoIdentity.publicKeyHex.take(8)}"
            message.senderPeerID == myIdentityId
        } catch (e: Exception) {
            false
        }
        
        return otherUserMessages.isEmpty() && !isFromUs
    }
    
    /**
     * Handle geohash DM event (private messages in geohash context) - iOS compatible
     */
    private fun handleGeohashDmEvent(
        giftWrap: NostrEvent, 
        geohash: String, 
        identity: NostrIdentity
    ) {
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Default) {
        try {
            // Deduplicate
            if (processedNostrEvents.contains(giftWrap.id)) return@launch
            processedNostrEvents.add(giftWrap.id)

            // Removed legacy NostrReadStore usage; rely on SeenMessageStore by message ID
            
            // Decrypt with per-geohash identity
            val decryptResult = NostrProtocol.decryptPrivateMessage(
                giftWrap = giftWrap,
                recipientIdentity = identity
            )
            
            if (decryptResult == null) {
                Log.d(TAG, "Skipping geohash DM: unwrap/open failed (non-fatal)")
                return@launch
            }
            
            val (content, senderPubkey, rumorTimestamp) = decryptResult
            
            // Only process BitChat embedded messages
            if (!content.startsWith("bitchat1:")) return@launch
            
            val base64Content = content.removePrefix("bitchat1:")
            val packetData = base64URLDecode(base64Content) ?: return@launch
            val packet = com.bitchat.android.protocol.BitchatPacket.fromBinaryData(packetData) ?: return@launch
            
            if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) return@launch
            
            val noisePayload = com.bitchat.android.model.NoisePayload.decode(packet.payload) ?: return@launch
            val messageTimestamp = Date(rumorTimestamp * 1000L)
            val convKey = "nostr_${senderPubkey.take(16)}"
            nostrKeyMapping[convKey] = senderPubkey
            
            when (noisePayload.type) {
                com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                    val pm = com.bitchat.android.model.PrivateMessagePacket.decode(noisePayload.data) ?: return@launch
                    val messageId = pm.messageID
                    
                    Log.d(TAG, "📥 Received geohash DM from ${senderPubkey.take(8)}...")
                    
                    // Check for duplicate message
                    val existingChats = state.getPrivateChatsValue()
                    var messageExists = false
                    for ((_, messages) in existingChats) {
                        if (messages.any { it.id == messageId }) {
                            messageExists = true
                            break
                        }
                    }
                    if (messageExists) return@launch
                    
                    val senderHandle = displayNameForNostrPubkey(senderPubkey)
                    val senderName = displayNameForNostrPubkeyUI(senderPubkey)
                    val isViewingThisChat = state.getSelectedPrivateChatPeerValue() == convKey
                    
                    val message = BitchatMessage(
                        id = messageId,
                        sender = senderName,
                        content = pm.content,
                        timestamp = messageTimestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = state.getNicknameValue(),
                        senderPeerID = convKey,
                        originalSender = senderHandle,
                        deliveryStatus = com.bitchat.android.model.DeliveryStatus.Delivered(
                            to = state.getNicknameValue() ?: "Unknown",
                            at = Date()
                        )
                    )
                    
                    // Add to private chats (suppress unread if already read)
                    val seen = com.bitchat.android.services.SeenMessageStore.getInstance(application)
                    privateChatManager.handleIncomingPrivateMessage(message, suppressUnread = seen.hasRead(messageId))

                    // Send delivery ACK for geohash DMs only once
                    if (!seen.hasDelivered(messageId)) {
                        val nostrTransport = NostrTransport.getInstance(application)
                        nostrTransport.sendDeliveryAckGeohash(messageId, senderPubkey, identity)
                        seen.markDelivered(messageId)
                    }

                    // Send read receipt if viewing this chat
                    if (isViewingThisChat && !seen.hasRead(messageId)) {
                        // Send read receipt via Nostr for geohash DM
                        sendGeohashReadReceipt(messageId, senderPubkey, geohash)
                        // Mark message as read persistently
                        try { seen.markRead(messageId) } catch (_: Exception) { }
                    }
                }
                
                com.bitchat.android.model.NoisePayloadType.DELIVERED -> {
                    val messageId = String(noisePayload.data, Charsets.UTF_8)
                    meshDelegateHandler.didReceiveDeliveryAck(messageId, convKey)
                }
                
                com.bitchat.android.model.NoisePayloadType.READ_RECEIPT -> {
                    val messageId = String(noisePayload.data, Charsets.UTF_8)
                    meshDelegateHandler.didReceiveReadReceipt(messageId, convKey)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling geohash DM event: ${e.message}")
        }
        }
    }
    
    /**
     * Display name for Nostr pubkey (iOS-compatible)
     */
    private fun displayNameForNostrPubkey(pubkeyHex: String): String {
        val suffix = pubkeyHex.takeLast(4)
        val pubkeyLower = pubkeyHex.lowercase()
        
        // If this is our per-geohash identity, use our nickname
        val currentGeohash = this.currentGeohash
        if (currentGeohash != null) {
            try {
                val myGeoIdentity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = currentGeohash,
                    context = application
                )
                if (myGeoIdentity.publicKeyHex.lowercase() == pubkeyLower) {
                    return "${state.getNicknameValue()}#$suffix"
                }
            } catch (e: Exception) {
                // Continue with other methods
            }
        }
        
        // If we have a cached nickname for this pubkey, use it
        geoNicknames[pubkeyLower]?.let { nick ->
            //Log.v(TAG, "✅ Found cached nickname for ${pubkeyHex.take(8)}: $nick")
            return "$nick#$suffix"
        }
        
        // Otherwise, anonymous with collision-resistant suffix
        //Log.v(TAG, "❌ No cached nickname for ${pubkeyHex.take(8)}, using anon")
        return "anon#$suffix"
    }

    /**
     * UI display name for Nostr pubkey (conditionally hides #hash when not needed)
     * Used in chat window sender labels, geohash people list, and notifications.
     */
    fun displayNameForNostrPubkeyUI(pubkeyHex: String): String {
        val pubkeyLower = pubkeyHex.lowercase()
        val suffix = pubkeyHex.takeLast(4)
        val currentGeohash = this.currentGeohash

        // Determine base name (self nickname, cached nickname, or anon)
        val baseName: String = try {
            if (currentGeohash != null) {
                val myGeoIdentity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = currentGeohash,
                    context = application
                )
                if (myGeoIdentity.publicKeyHex.lowercase() == pubkeyLower) {
                    state.getNicknameValue() ?: "anon"
                } else {
                    geoNicknames[pubkeyLower] ?: "anon"
                }
            } else {
                geoNicknames[pubkeyLower] ?: "anon"
            }
        } catch (_: Exception) {
            geoNicknames[pubkeyLower] ?: "anon"
        }

        // If not in a geohash context, no need for disambiguation
        if (currentGeohash == null) return baseName

        // Count active participants sharing the same base name within 5 minutes
        return try {
            val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
            val participants = geohashParticipants[currentGeohash] ?: emptyMap()

            var count = 0
            for ((participantKey, lastSeen) in participants) {
                if (lastSeen.before(cutoff)) continue
                val participantLower = participantKey.lowercase()
                val name = if (participantLower == pubkeyLower) baseName else (geoNicknames[participantLower] ?: "anon")
                if (name.equals(baseName, ignoreCase = true)) {
                    count++
                    if (count > 1) break
                }
            }

            if (!participants.containsKey(pubkeyLower)) {
                count += 1
            }

            if (count > 1) "$baseName#$suffix" else baseName
        } catch (_: Exception) {
            baseName
        }
    }

    /**
     * Mention handle for a Nostr pubkey (ALWAYS includes #hash)
     * Use for mentions, hug/slap targets, and mention detection contexts.
     */
    fun mentionHandleForNostrPubkey(pubkeyHex: String): String {
        return displayNameForNostrPubkey(pubkeyHex)
    }
    
    // MARK: - Color System
    
    /**
     * Get consistent color for a Nostr pubkey (iOS-compatible)
     */
    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        val seed = "nostr:${pubkeyHex.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }
    
    // MARK: - Nostr Direct Message Sending
    
    /**
     * Send private message via Nostr for geohash contacts
     */
    fun sendNostrGeohashDM(content: String, recipientPeerID: String, messageID: String, myPeerID: String) {
        coroutineScope.launch {
            try {
                // Get the current geohash from location channel manager
                val locationChannelManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(application)
                val selectedChannel = locationChannelManager.selectedChannel.value
                
                if (selectedChannel !is com.bitchat.android.geohash.ChannelID.Location) {
                    Log.w(TAG, "Cannot send geohash DM: not in a location channel")
                    return@launch
                }
                
                // Get the recipient's Nostr public key from the mapping
                val recipientHex = nostrKeyMapping[recipientPeerID]
                
                if (recipientHex == null) {
                    Log.w(TAG, "Cannot send geohash DM: no public key mapping for $recipientPeerID")
                    return@launch
                }
                
                // Derive geohash-specific identity for sending
                val senderIdentity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = selectedChannel.channel.geohash,
                    context = application
                )
                
                // Send via Nostr transport
                val nostrTransport = NostrTransport.getInstance(application)
                // Ensure the senderPeerID is set properly
                nostrTransport.senderPeerID = myPeerID
                nostrTransport.sendPrivateMessageGeohash(
                    content = content,
                    toRecipientHex = recipientHex,
                    fromIdentity = senderIdentity,
                    messageID = messageID
                )
                
                Log.d(TAG, "📤 Sent geohash DM to $recipientPeerID via Nostr")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Nostr geohash DM: ${e.message}")
            }
        }
    }
    
    /**
     * Send read receipt via Nostr for geohash contacts
     */
    fun sendNostrGeohashReadReceipt(messageID: String, recipientPeerID: String, myPeerID: String) {
        coroutineScope.launch {
            try {
                // Get the current geohash from location channel manager
                val locationChannelManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(application)
                val selectedChannel = locationChannelManager.selectedChannel.value
                
                if (selectedChannel !is com.bitchat.android.geohash.ChannelID.Location) {
                    Log.w(TAG, "Cannot send geohash read receipt: not in a location channel")
                    return@launch
                }
                
                // Get the recipient's Nostr public key from the mapping
                val recipientHex = nostrKeyMapping[recipientPeerID]
                
                if (recipientHex == null) {
                    Log.w(TAG, "Cannot send geohash read receipt: no public key mapping for $recipientPeerID")
                    return@launch
                }
                
                // Derive geohash-specific identity for sending
                val senderIdentity = NostrIdentityBridge.deriveIdentity(
                    forGeohash = selectedChannel.channel.geohash,
                    context = application
                )
                
                // Send via Nostr transport
                val nostrTransport = NostrTransport.getInstance(application)
                // Ensure the senderPeerID is set properly
                nostrTransport.senderPeerID = myPeerID
                nostrTransport.sendReadReceiptGeohash(
                    messageID = messageID,
                    toRecipientHex = recipientHex,
                    fromIdentity = senderIdentity
                )
                
                Log.d(TAG, "📤 Sent geohash read receipt for $messageID to $recipientPeerID via Nostr")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Nostr geohash read receipt: ${e.message}")
            }
        }
    }
    
    // MARK: - Geohash Blocking
    
    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        // Find the pubkey for this nickname
        val pubkeyHex = geoNicknames.entries.firstOrNull { (_, nickname) ->
            val baseName = nickname.split("#").firstOrNull() ?: nickname
            baseName == targetNickname
        }?.key
        
        if (pubkeyHex != null) {
            // Add to geohash block list
            dataManager.addGeohashBlockedUser(pubkeyHex)
            
            // Add system message
            val systemMessage = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "blocked $targetNickname in geohash channels",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            
            Log.i(TAG, "🚫 Blocked geohash user: $targetNickname (pubkey: ${pubkeyHex.take(8)}...)")
        } else {
            // User not found
            val systemMessage = com.bitchat.android.model.BitchatMessage(
                sender = "system",
                content = "user '$targetNickname' not found in current geohash",
                timestamp = java.util.Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    /**
     * Check if a user is blocked in geohash channels
     */
    private fun isGeohashUserBlocked(pubkeyHex: String): Boolean {
        return dataManager.isGeohashUserBlocked(pubkeyHex)
    }
    
    /**
     * Get location name for a geohash if available
     */
    private fun getLocationNameForGeohash(geohash: String): String? {
        return try {
            val locationManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(application)
            val locationNames = locationManager.locationNames.value ?: return null
            val available = locationManager.availableChannels.value ?: emptyList()
            
            // Determine the level from geohash length
            val level = when (geohash.length) {
                in 0..2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                in 3..4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                7 -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                else -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
            }

            // Only show location name if the notification's geohash matches the device's current geohash at that level
            val currentAtLevel = available.firstOrNull { it.level == level }?.geohash
            if (currentAtLevel != null && currentAtLevel.equals(geohash, ignoreCase = true)) {
                locationNames[level]
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get location name for geohash $geohash: ${e.message}")
            null
        }
    }

}
