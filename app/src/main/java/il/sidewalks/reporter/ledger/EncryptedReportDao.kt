package il.sidewalks.reporter.ledger

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Inactive candidate schema. Not registered in production ReportDatabase. */
@Entity(tableName = "reports", indices = [Index(value = ["originalSha256"], unique = true)])
class EncryptedReportEntity(
    @PrimaryKey val stableId: String,
    val originalSha256: String,
    val originalRelativePath: String,
    val revision: Long,
    val draftJson: ByteArray,
    val reviewedDigest: ByteArray?,
    @ColumnInfo(defaultValue = "0") val tombstoned: Boolean = false,
) {
    fun storageRow() = EncryptedRecordRow(stableId, originalSha256, originalRelativePath,
        revision, draftJson, reviewedDigest, tombstoned)
    override fun toString() = "EncryptedReportEntity(redacted)"
    companion object {
        fun from(row: EncryptedRecordRow) = EncryptedReportEntity(row.stableId,
            row.originalSha256, row.originalRelativePath, row.revision,
            row.draftCiphertext(), row.digestCiphertext(), row.tombstoned)
    }
}

@Dao
interface EncryptedReportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun reserve(record: EncryptedReportEntity)
    @Query("SELECT * FROM reports WHERE stableId = :id")
    suspend fun find(id: String): EncryptedReportEntity?
    @Query("SELECT * FROM reports WHERE originalSha256 = :hash")
    suspend fun findPhoto(hash: String): EncryptedReportEntity?
    @Query("SELECT * FROM reports ORDER BY stableId")
    suspend fun all(): List<EncryptedReportEntity>
    @Query("UPDATE reports SET revision = revision + 1, draftJson = :json, reviewedDigest = :digest WHERE stableId = :id AND revision = :expectedRevision AND tombstoned = 0")
    suspend fun store(id: String, expectedRevision: Long, json: ByteArray, digest: ByteArray?): Int
}
