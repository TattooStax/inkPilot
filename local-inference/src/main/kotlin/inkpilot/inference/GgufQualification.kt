package inkpilot.inference

import java.io.File
import java.security.MessageDigest

/** Version 1 approves one audited text artifact, including its metadata and template.
 * Other LFM3 quantizations are UNQUALIFIED, not inherently incompatible.
 */
object GgufQualification {
    const val VERSION = 1
    const val QAD_BYTES = 1_049_615_680L
    const val QAD_SHA256 = "43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452"

    fun replaceApproved(candidate: File, target: File, beforeReplace: () -> Unit) {
        requireApproved(candidate)
        beforeReplace()
        check(candidate.renameTo(target)) { "Cannot replace the local model." }
    }

    fun requireApproved(file: File) {
        require(file.length() == QAD_BYTES) { "Unqualified model size. Choose the audited LFM3-1.7B QAD Q4_0 artifact." }
        val hash = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        requireApprovedDigest(file.length(), hash)
    }

    /** Whole-file identity pins GGUF metadata, architecture and the exact embedded chat template. */
    fun requireApprovedDigest(bytes: Long, sha256: String) {
        require(bytes == QAD_BYTES && sha256 == QAD_SHA256) {
            "Unqualified model checksum. Only the audited LFM3-1.7B QAD Q4_0 metadata and template are approved."
        }
    }
}
