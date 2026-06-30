package com.darkwisp.app.nostr

/**
 * Custom NIP (kind 30817) — a "NUD" (Nostr Unofficial Document): a community-authored NIP.
 * Structurally identical to a NIP-23 long-form article (addressable by `kind:pubkey:d`,
 * markdown body, `title`/`published_at`/`summary` tags), plus zero or more `k` tags naming
 * the event kinds the document defines. We render it with the existing article machinery; this
 * object just holds the kind constant and the `k`-tag parsing for the defined-kind chips.
 */
object CustomNip {
    const val KIND = 30817

    /** A kind this NUD defines, from a `["k", "<number>", "<name>"]` tag (name optional). */
    data class DefinedKind(val num: String, val name: String)

    /** Parse the `k` tags into the kinds this document defines, in tag order. */
    fun parseDefinedKinds(event: NostrEvent): List<DefinedKind> =
        event.tags
            .filter { it.size >= 2 && it[0] == "k" && it[1].isNotBlank() }
            .map { DefinedKind(num = it[1], name = it.getOrNull(2)?.trim().orEmpty()) }
}
