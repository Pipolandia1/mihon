package tachiyomi.domain.sync

/** How two versions of the same field become one. */
enum class SyncStrategy {
    /**
     * The latest change wins. On a tie, the greater value: read beats unread, a later page an
     * earlier one. Ties happen with what both devices had before sync was turned on (time 0).
     */
    LATEST,

    /** The greater number, whenever it was written: the time spent on a chapter only grows. */
    MAX,

    /** The smaller number: when recording started. */
    MIN,

    /** Both chapter progress records combined, as a backup restore combines them ([SyncProgress]). */
    PROGRESS,
}

/**
 * Merges two snapshots field by field (docs/DESKTOP_PLAN.md §3.4).
 *
 * The result does not depend on the order of the two: merging A into B gives what merging B into
 * A does, and merging a snapshot with itself changes nothing. A tombstone drops every field written
 * before it, from either side; a field written after it brings the record back.
 */
object SyncMerge {

    fun merge(a: SyncSnapshot, b: SyncSnapshot): SyncSnapshot {
        val keys = a.records.keys + b.records.keys
        val records = HashMap<SyncKey, SyncRecord>(keys.size)
        keys.forEach { key -> records[key] = merge(key.entity, a.records[key], b.records[key]) }
        return SyncSnapshot(records)
    }

    fun merge(entity: SyncEntity, a: SyncRecord?, b: SyncRecord?): SyncRecord {
        if (a == null) return requireNotNull(b).normalized()
        if (b == null) return a.normalized()
        val deletedAt = when {
            a.deletedAt == null -> b.deletedAt
            b.deletedAt == null -> a.deletedAt
            else -> maxOf(a.deletedAt, b.deletedAt)
        }
        val fields = HashMap<String, SyncField>()
        for (name in a.fields.keys + b.fields.keys) {
            val x = a.fields[name]?.takeIf { deletedAt == null || it.modifiedAt > deletedAt }
            val y = b.fields[name]?.takeIf { deletedAt == null || it.modifiedAt > deletedAt }
            fields[name] = when {
                x == null -> y ?: continue
                y == null -> x
                else -> combine(entity.strategy(name), x, y)
            }
        }
        return SyncRecord(fields, deletedAt)
    }

    fun combine(strategy: SyncStrategy, x: SyncField, y: SyncField): SyncField {
        val modifiedAt = maxOf(x.modifiedAt, y.modifiedAt)
        return when (strategy) {
            SyncStrategy.LATEST -> when {
                x.modifiedAt > y.modifiedAt -> x
                y.modifiedAt > x.modifiedAt -> y
                SyncValueOrder.compare(x.value, y.value) >= 0 -> x
                else -> y
            }
            SyncStrategy.MAX -> SyncField(maxOf(x.value, y.value, SyncValueOrder), modifiedAt)
            SyncStrategy.MIN -> SyncField(minOf(x.value, y.value, SyncValueOrder), modifiedAt)
            SyncStrategy.PROGRESS -> SyncField(SyncProgress.merge(x.value, y.value), modifiedAt)
        }
    }
}

/** Numbers by value, so a page 10 beats a page 9; anything else by its text. */
object SyncValueOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val x = a.toLongOrNull()
        val y = b.toLongOrNull()
        return if (x != null && y != null) x.compareTo(y) else a.compareTo(b)
    }
}
