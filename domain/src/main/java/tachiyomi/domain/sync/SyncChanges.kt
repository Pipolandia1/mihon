package tachiyomi.domain.sync

/**
 * How many records one snapshot adds, changes and removes relative to another, by entity: what the
 * sync screen shows before the first sync ("124 entries, 3 more on the PC").
 */
data class SyncChanges(val counts: Map<SyncEntity, Count>) {

    data class Count(val added: Int = 0, val changed: Int = 0, val removed: Int = 0) {
        val total: Int get() = added + changed + removed
    }

    val isEmpty: Boolean get() = counts.isEmpty()

    val total: Int get() = counts.values.sumOf { it.total }

    companion object {
        val NONE = SyncChanges(emptyMap())

        fun between(before: SyncSnapshot, after: SyncSnapshot): SyncChanges {
            val counts = HashMap<SyncEntity, Count>()
            after.records.forEach { (key, record) ->
                val old = before.records[key]
                if (old == record) return@forEach
                val wasAlive = old?.isAlive == true
                val count = counts[key.entity] ?: Count()
                counts[key.entity] = when {
                    record.isAlive && !wasAlive -> count.copy(added = count.added + 1)
                    record.isAlive -> count.copy(changed = count.changed + 1)
                    wasAlive -> count.copy(removed = count.removed + 1)
                    // A tombstone moved or arrived for something that was not here: nothing to show.
                    else -> return@forEach
                }
            }
            return SyncChanges(counts)
        }
    }
}
