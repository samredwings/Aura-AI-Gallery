package ai.sakana.aura.gallery.data.deduplication

/**
 * Disjoint-band locality-sensitive candidate generation for 64-bit difference hashes.
 *
 * With `threshold + 1` disjoint bands, any two hashes whose Hamming distance is at most
 * `threshold` must share at least one complete band: at most `threshold` bands can contain
 * a differing bit. Exact Hamming distance is still checked by the grouper before unioning.
 */
internal object DHashLshCandidateGenerator {
    private data class BandKey(val band: Int, val value: Long)

    fun candidates(hashes: List<Long>, threshold: Int): Set<Pair<Int, Int>> {
        require(threshold in 0 until Long.SIZE_BITS) { "Hamming threshold must be between 0 and 63." }
        if (hashes.size < 2) return emptySet()

        val bandCount = threshold + 1
        val baseWidth = Long.SIZE_BITS / bandCount
        val widerBands = Long.SIZE_BITS % bandCount
        val buckets = HashMap<BandKey, MutableList<Int>>()

        hashes.forEachIndexed { index, hash ->
            if (DHash.isDegenerate(hash)) return@forEachIndexed
            var shift = 0
            repeat(bandCount) { band ->
                val width = baseWidth + if (band < widerBands) 1 else 0
                val mask = if (width == Long.SIZE_BITS) -1L else (1L shl width) - 1L
                val value = (hash ushr shift) and mask
                buckets.getOrPut(BandKey(band, value), ::ArrayList).add(index)
                shift += width
            }
        }

        val candidates = LinkedHashSet<Pair<Int, Int>>()
        buckets.values.forEach { members ->
            for (leftIndex in 0 until members.size) {
                for (rightIndex in leftIndex + 1 until members.size) {
                    val left = members[leftIndex]
                    val right = members[rightIndex]
                    if (left != right) candidates += minOf(left, right) to maxOf(left, right)
                }
            }
        }
        return candidates
    }
}
