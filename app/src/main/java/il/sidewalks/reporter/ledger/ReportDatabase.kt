package il.sidewalks.reporter.ledger

import androidx.room.*

@Entity(tableName = "reports", indices = [Index(value = ["originalSha256"], unique = true)])
data class ReportRecord(
    @PrimaryKey val stableId: String,
    val originalSha256: String,
    val originalRelativePath: String,
    val revision: Long,
    val draftJson: String,
    val reviewedDigest: String?,
)
@Dao
interface ReportDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun reserve(record: ReportRecord)
    @Query("SELECT * FROM reports WHERE stableId = :id")
    suspend fun find(id: String): ReportRecord?
    @Query("SELECT * FROM reports WHERE originalSha256 = :hash")
    suspend fun findPhoto(hash: String): ReportRecord?
    @Query("SELECT * FROM reports ORDER BY stableId")
    suspend fun all(): List<ReportRecord>
    @Query("UPDATE reports SET revision = revision + 1, draftJson = :json, reviewedDigest = :digest WHERE stableId = :id AND revision = :expectedRevision")
    suspend fun store(id: String, expectedRevision: Long, json: String, digest: String?): Int
    @Query("UPDATE reports SET reviewedDigest = :digest WHERE stableId = :id AND revision = :expectedRevision")
    suspend fun approve(id: String, expectedRevision: Long, digest: String): Int
}
@Database(entities = [ReportRecord::class], version = 1, exportSchema = true)
abstract class ReportDatabase : RoomDatabase() { abstract fun reports(): ReportDao }
