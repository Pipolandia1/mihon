package tachiyomi.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncField
import tachiyomi.domain.sync.SyncKey
import tachiyomi.domain.sync.SyncRecord
import tachiyomi.domain.sync.SyncSnapshot
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The file every device reads and writes in the app's hidden Drive folder (docs/DESKTOP_PLAN.md
 * §3.4): a merged [SyncSnapshot], as gzipped protobuf, with who wrote it and when.
 */
object SyncSnapshotCodec {

    /** Bump on a change older apps cannot read: they then refuse the file instead of misreading it. */
    const val FORMAT = 1

    data class Decoded(val snapshot: SyncSnapshot, val writtenAt: Long, val device: String)

    class NewerFormatException(val format: Int) : Exception("Sync file format $format is newer than $FORMAT")

    @Serializable
    private class File(
        @ProtoNumber(1) val format: Int,
        @ProtoNumber(2) val writtenAt: Long,
        @ProtoNumber(3) val device: String,
        @ProtoNumber(4) val records: List<Entry> = emptyList(),
    )

    @Serializable
    private class Entry(
        @ProtoNumber(1) val entity: String,
        @ProtoNumber(2) val key: List<String>,
        @ProtoNumber(3) val fields: List<Field> = emptyList(),
        @ProtoNumber(4) val deletedAt: Long? = null,
    )

    @Serializable
    private class Field(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val value: String,
        @ProtoNumber(3) val modifiedAt: Long,
    )

    /** Records and fields go in a fixed order, so the same snapshot always makes the same bytes. */
    fun encode(snapshot: SyncSnapshot, writtenAt: Long, device: String): ByteArray {
        val entries = snapshot.records.entries
            .sortedWith(compareBy({ it.key.entity.id }, { it.key.parts.joinToString("\u0000") }))
            .map { (key, record) ->
                Entry(
                    entity = key.entity.id,
                    key = key.parts,
                    fields = record.fields.entries.sortedBy { it.key }.map { (name, field) ->
                        Field(name, field.value, field.modifiedAt)
                    },
                    deletedAt = record.deletedAt,
                )
            }
        val bytes = ProtoBuf.encodeToByteArray(File.serializer(), File(FORMAT, writtenAt, device, entries))
        return ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
            out.toByteArray()
        }
    }

    /** Records of a kind this app does not know, written by a newer one, are left out. */
    fun decode(gzipped: ByteArray): Decoded {
        val bytes = GZIPInputStream(gzipped.inputStream()).use { it.readBytes() }
        val file = ProtoBuf.decodeFromByteArray(File.serializer(), bytes)
        if (file.format > FORMAT) throw NewerFormatException(file.format)
        val records = file.records.mapNotNull { entry ->
            val entity = SyncEntity.of(entry.entity) ?: return@mapNotNull null
            if (entry.key.size != entity.keySize) return@mapNotNull null
            SyncKey(entity, entry.key) to SyncRecord(
                fields = entry.fields.associate { it.name to SyncField(it.value, it.modifiedAt) },
                deletedAt = entry.deletedAt,
            )
        }
        return Decoded(SyncSnapshot(records.toMap()), file.writtenAt, file.device)
    }
}
