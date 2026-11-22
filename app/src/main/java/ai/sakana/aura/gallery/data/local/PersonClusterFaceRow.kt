package ai.sakana.aura.gallery.data.local

/** Nullable face columns come from the LEFT JOIN for saved groups that temporarily have no members. */
data class PersonClusterFaceRow(
    val personId: Long,
    val name: String,
    val representativeFaceId: Long,
    val confidence: Float,
    val memberCount: Int,
    val faceId: Long?,
    val contentUri: String?,
    val modelVersion: String?,
    val vectorBytes: ByteArray?,
    val dimensions: Int?,
)
