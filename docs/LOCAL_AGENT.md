# Aura Local Agent and AI Erase

This guide describes the implementation currently present in the Android `main` branch. It distinguishes implemented workflows from work that still needs model-quality and device acceptance.

## 1. Local Agent

### Entry point

Open **Explore → Aura Agent** (or the Agent entry in More). The screen provides local chat, model download status, skill import, and action review/execution.

### Language model

| Property | Value |
|---|---|
| Model | Qwen3 0.6B |
| Runtime | LiteRT-LM |
| Backend | CPU |
| Approximate download | 614 MB |
| Local file | `files/ai-models/Qwen3-0.6B.litertlm` |
| Download source | [Hugging Face — LiteRT Community Qwen3 0.6B](https://huggingface.co/litert-community/Qwen3-0.6B) |

The model download is queued with WorkManager and requires network access. The app checks the downloaded model against the SHA-256 pinned in `AgentModelCatalog`. Once installed, chat and tool planning run locally.

### Supported agent actions

The current response parser accepts only these named tools:

- `navigate(screen)`
- `search_media(query, media_type)`
- `open_latest_media(media_type)`
- `refresh_library()`
- `analyze_library()`
- `scan_duplicates(mode)`
- `open_ai_erase(query, operation)`
- `open_tone_editor(query)`
- `find_continuation_shots()`
- `save_continuation_set(group_index, name)`
- `move_media_to_trash(query)`
- `restore_from_trash(query)`
- `open_settings()`
- `search_web(query)`

Tool responses are returned to the model so it can explain actual results or request another supported action. The parser limits action count, argument count and string lengths. Unknown tool names and malformed structures are ignored rather than executed.

### Continuation-shot suggestions

The sequence grouper uses media metadata and perceptual similarity:

- capture-time proximity,
- image aspect-ratio similarity,
- perceptual-hash Hamming distance.

Results are suggestions, not proof that shots belong to the same burst or scene. The agent can save a user-named continuation set only after an explicit request.

### Skills

Use **Learn Skill** to import a Markdown or text file. Aura parses and stores the skill's name, description and instructions in Room. Enabled skills are supplied as bounded, explicitly untrusted reference text alongside the user's next message. They are not inserted into the privileged system prompt and are not treated as independent user requests.

A skill is **untrusted reference material, not executable code or a new user request**. Importing a skill does not install a binary, run a script, grant permissions, add tools or bypass the action allowlist, privacy policy or confirmation requirements. Skill context is limited to five enabled skills and 6,000 instruction characters per turn.

### Trash and restore

The agent may move matching media to Aura's recoverable trash only when the user explicitly asks to delete, trash or move it to Recently Deleted. A request to remove text or a watermark is an image-edit request, not a request to trash the photo. The agent does not expose permanent deletion. Users can ask it to restore items from Recently Deleted.

### Web access and privacy

`search_web` is available when external information is needed or the user requests a search. Before opening an external browser, Aura validates the query and presents the exact text for user approval. The app rejects content URIs, local storage paths, common photo/video filenames, and values matching the visible or recently deleted gallery metadata. No photo or file is attached to the search. Network access is used for model downloads and approved external searches; local chat, recognition and inpainting are on-device operations.

## 2. AI Erase

### User workflow

1. Open a photo in Aura and enter **AI Erase** (or ask the agent to open AI Erase for the selected photo).
2. Paint over the object, text or watermark with the brush, or run **Detect text** to select OCR text regions.
3. Review the mask. Clear or undo strokes as needed.
4. Download the inpainting model if it is not installed.
5. Choose **Erase & Save New Version**.

### Inpainting model

| Property | Value |
|---|---|
| Model | LaMa Inpainting ONNX |
| Runtime | ONNX Runtime |
| Approximate download | 208 MB |
| Local file | `files/ai-models/lama_fp32.onnx` |
| Download source | [Hugging Face — sapienkit/LaMa-ONNX](https://huggingface.co/sapienkit/LaMa-ONNX) |

The app pins the model SHA-256 in `AgentModelCatalog`. The download requires network access; inpainting runs locally after installation.

### Processing behavior

- Text regions are detected on-device with ML Kit Text Recognition.
- The user can add or refine a mask with the manual brush.
- The processor crops the region around the mask, resizes the model input to 512 × 512, and runs LaMa with image and mask tensors.
- The generated fill is composited only into the masked pixels of a copy of the source bitmap.
- The source media is not overwritten. The result is exported as a separate media item.

The current workflow is targeted inpainting, not a general prompt-driven image generator. Output quality depends on mask placement, available surrounding context, model behavior and source-image content. Image-level acceptance on a broad real-photo set remains a required validation task.

## 3. Non-destructive version history

Room schema version 10 adds:

- `media_versions`: source URI, derivative URI, operation, prompt, model ID and creation timestamp.
- `agent_skills`: skill identity, name, description, instructions, source URI, enabled state and creation timestamp.

The version table indexes source URI plus creation time and uniquely indexes the derivative URI. Migration `MIGRATION_9_10` creates both tables. The original media row remains the source of truth; edited output is stored separately.

## 4. Implementation map

| Area | Source |
|---|---|
| Agent chat and model lifecycle | `presentation/LocalAgentViewModel.kt` |
| Agent screen | `presentation/LocalAgentScreen.kt` |
| Tool response parser and allowlist | `domain/agent/AgentResponseParser.kt` |
| Continuation-shot grouping and policy | `domain/agent/LocalAgentRules.kt` |
| Model IDs, URLs and checksums | `data/agent/AgentModelCatalog.kt` |
| Model download and storage | `data/agent/AgentModelManager.kt`, `AgentModelDownloadWorker.kt` |
| AI Erase UI and masks | `presentation/AiEraseScreen.kt` |
| Inpainting inference | `data/agent/AiEraseProcessor.kt` |
| Version and skill persistence | `data/local/MediaVersionEntity.kt`, `AgentSkillEntity.kt`, `AgentDataDao.kt` |
| Room schema and migration | `data/local/AuraGalleryDatabase.kt` |

## 5. Known limitations and next acceptance gates

- Downloading model files and initializing the LLM have not been verified on every supported Android device or memory class.
- The LaMa processor has not yet passed a comprehensive real-photo quality benchmark for object, text and watermark removal.
- The current agent opens the existing tone editor or AI Erase workflow; full free-form conversational generative image editing is not implemented.
- Continuation-shot grouping is heuristic and requires precision/recall evaluation against labelled sequences.
- Browser search is external; local photo data must remain local.
- Skills are parsed Markdown/text guidance only. There is no arbitrary plugin-code execution or unrestricted shell access.

Run the standard checks from the repository root:

```bash
./gradlew --no-daemon :app:check
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```
