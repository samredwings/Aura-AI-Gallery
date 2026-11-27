package ai.sakana.aura.gallery.data.agent

data class AgentModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val approximateSizeLabel: String,
    val purpose: String,
)

object AgentModelCatalog {
    val LOCAL_LLM = AgentModelSpec(
        id = "qwen3-0.6b",
        displayName = "Qwen3 0.6B",
        fileName = "Qwen3-0.6B.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
        approximateSizeLabel = "614 MB",
        purpose = "Offline chat and Aura tool planning",
    )

    val INPAINTING = AgentModelSpec(
        id = "lama-inpaint",
        displayName = "LaMa Inpainting",
        fileName = "lama_fp32.onnx",
        downloadUrl = "https://huggingface.co/sapienkit/LaMa-ONNX/resolve/main/lama_fp32.onnx",
        sha256 = "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6",
        approximateSizeLabel = "208 MB",
        purpose = "Local object, text and watermark removal",
    )

    val all = listOf(LOCAL_LLM, INPAINTING)
    fun find(id: String): AgentModelSpec? = all.firstOrNull { it.id == id }
}
