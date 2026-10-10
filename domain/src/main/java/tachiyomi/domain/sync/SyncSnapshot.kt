package tachiyomi.domain.sync

/**
 * Everything one device knows that the others should know too, as records with the time of every
 * field (docs/DESKTOP_PLAN.md §3.4).
 *
 * Values are opaque strings: what they hold is the data layer's business. Merging only needs the
 * times, a strategy per field ([SyncEntity.strategy]) and an order between values for ties.
 */
data class SyncSnapshot(val records: Map<SyncKey, SyncRecord>) {

    fun alive(entity: SyncEntity): Map<SyncKey, SyncRecord> =
        records.filter { (key, record) -> key.entity == entity && record.isAlive }

    companion object {
        val EMPTY = SyncSnapshot(emptyMap())
    }
}

/**
 * A record's natural key. Never a local row id: those differ on every device. A manga is its source
 * and URL, a chapter adds its own URL, a category is its name.
 */
data class SyncKey(val entity: SyncEntity, val parts: List<String>) {
    init {
        require(parts.size == entity.keySize) { "${entity.id} takes ${entity.keySize} key parts, got $parts" }
    }

    constructor(entity: SyncEntity, vararg parts: String) : this(entity, parts.toList())
}

/** A field's value and when it last changed on the device that changed it, in epoch milliseconds. */
data class SyncField(val value: String, val modifiedAt: Long)

/**
 * A record's fields and, if it was deleted, when. A field changed after the deletion brings the
 * record back; one from before it is dropped by the merge, so a live record is one with fields left.
 */
data class SyncRecord(
    val fields: Map<String, SyncField>,
    val deletedAt: Long? = null,
) {
    val isAlive: Boolean get() = fields.isNotEmpty()

    operator fun get(field: String): String? = fields[field]?.value

    /** The same record with every field the deletion outdates dropped. */
    fun normalized(): SyncRecord {
        if (deletedAt == null) return this
        val kept = fields.filterValues { it.modifiedAt > deletedAt }
        return if (kept.size == fields.size) this else copy(fields = kept)
    }
}
