package com.darkwisp.app.repo

import android.util.Log
import com.darkwisp.app.nostr.Nip10
import com.darkwisp.app.nostr.Nip17
import com.darkwisp.app.nostr.NostrEvent

/**
 * Routes non-DM rumors received via NIP-17 gift wrap (kind 1 private replies and kind 7
 * reactions carrying `k=1`) into the note + notification repositories.
 *
 * Shared by [com.darkwisp.app.viewmodel.EventRouter] (local-signer path, wraps decrypted
 * as they arrive) and the remote-signer pending-decrypt paths in
 * [com.darkwisp.app.viewmodel.DmListViewModel] and
 * [com.darkwisp.app.viewmodel.DmConversationViewModel], which would otherwise misfile
 * these rumors as DM messages/reactions.
 */
object PrivateRumorHandler {

    /** True when a kind 7 rumor targets a NIP-17 private reply (k=1) rather than a DM (k=14). */
    fun isPrivateReplyReaction(rumor: Nip17.Rumor): Boolean =
        Nip17.isReaction(rumor) &&
            rumor.tags.firstOrNull { it.size >= 2 && it[0] == "k" }?.get(1) == "1"

    /** Materialise a kind 1 private-reply rumor: mark it private, cache a synthetic event,
     *  bump the parent's reply count, and notify (unless it's our own self-copy wrap). */
    fun handlePrivateReply(
        rumor: Nip17.Rumor,
        myPubkey: String,
        eventRepo: EventRepository,
        notifRepo: NotificationRepository,
        muteRepo: MuteRepository?,
        onMissingProfile: (String) -> Unit = {}
    ) {
        if (muteRepo?.isBlocked(rumor.pubkey) == true) return

        val rumorId = Nip17.computeRumorId(rumor)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            kind = 1,
            tags = rumor.tags,
            content = rumor.content,
            sig = ""
        )
        eventRepo.markPrivate(rumorId)
        eventRepo.cacheEvent(synthetic)
        if (!Nip10.isStandaloneQuote(synthetic)) {
            val parentId = Nip10.getReplyTarget(synthetic)
            if (parentId != null) eventRepo.addReplyCount(parentId, synthetic.id)
        }
        // Self-copy wraps from another device land here too — skip the notification, but
        // the cache write above still surfaces the reply in our thread view.
        if (rumor.pubkey == myPubkey) return
        notifRepo.addEvent(synthetic, myPubkey, replyToMyEvent = true, source = "gift-wrap-private-reply")
        if (eventRepo.getProfileData(rumor.pubkey) == null) {
            onMissingProfile(rumor.pubkey)
        }
    }

    /** Materialise a kind 7 reaction on a private reply: mark it private, feed it through the
     *  normal reaction pipeline, and notify (unless it's our own self-copy wrap). */
    fun handlePrivateReaction(
        rumor: Nip17.Rumor,
        myPubkey: String,
        eventRepo: EventRepository,
        notifRepo: NotificationRepository,
        muteRepo: MuteRepository?,
        onMissingProfile: (String) -> Unit = {}
    ) {
        if (muteRepo?.isBlocked(rumor.pubkey) == true) return
        val targetId = rumor.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return
        if (eventRepo.getEvent(targetId) == null) {
            // Without the target rumor cached we can't bind ownership in NotificationRepository,
            // which would surface reactions on threads we never received. Drop quietly — the
            // target arrives via the same kind-1059 subscription, so a refetch will heal.
            Log.d("PrivateRumorHandler", "Skipping private reaction on uncached target ${targetId.take(12)}")
            return
        }
        val rumorId = Nip17.computeRumorId(rumor)
        val synthetic = NostrEvent(
            id = rumorId,
            pubkey = rumor.pubkey,
            created_at = rumor.createdAt,
            kind = 7,
            tags = rumor.tags,
            content = rumor.content,
            sig = ""
        )
        eventRepo.markPrivate(rumorId)
        eventRepo.addEvent(synthetic)
        if (rumor.pubkey == myPubkey) return
        notifRepo.addEvent(synthetic, myPubkey, source = "gift-wrap-private-reaction")
        if (eventRepo.getProfileData(rumor.pubkey) == null) {
            onMissingProfile(rumor.pubkey)
        }
    }
}
