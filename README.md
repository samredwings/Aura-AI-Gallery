# Aura AI Gallery — Android

Aura AI Gallery is a phone-first Android gallery. The app indexes device media through MediaStore or media selected with Android Photo Picker. Its primary product direction is private, on-device media recognition and organization.

- **Application ID:** `ai.sakana.aura.gallery`
- **Default branch:** `main`
- **Minimum Android:** API 26
- **Compile / target SDK:** 36 / 36

## Current implementation

- Real photo and video gallery with Library, Albums, People, AI Sets, Edit, Optimize and Settings destinations.
- Room-backed favourites, reviewed AI Sets, recognition data, edit history, agent skills and non-destructive media-version metadata.
- On-device scene feature extraction and face-location detection. Face boxes are not person identities; person identity grouping still requires validated model and acceptance work.
- Exact and visually similar duplicate scanning with review before destructive actions.
- Local tone adjustments and non-destructive export.
- **Aura Local Agent** for natural-language chat, supported gallery actions, local recognition and duplicate scans. See [the Local Agent guide](docs/LOCAL_AGENT.md).
- **AI Erase** for brush- or OCR-selected object/text/watermark removal using local LaMa inpainting. The original is retained and the edited output is saved as a separate copy.

## Aura Local Agent

Open **Explore → Aura Agent** (or the Agent entry in the More menu).

The agent runs Qwen3 0.6B through LiteRT-LM on the device. Its structured response is parsed against an explicit tool allowlist; generated text is never evaluated as code. It can navigate screens, search/open media, refresh and analyze the library, scan duplicates, open AI Erase or the tone editor, suggest continuation-shot groups, save a reviewed group, move explicitly selected media to recoverable trash, restore items, open Settings and launch a web search when needed.

Import Markdown/text skill files from the Agent screen. Imported instructions are stored locally and added as model guidance. They are not executable plugins: Aura does not run downloaded scripts, shell commands or arbitrary code.

The local chat model is approximately **614 MB**. Download it once from the Agent screen. Model downloads require network access; inference runs locally after installation.

## AI Erase and versions

AI Erase supports a manual brush mask and on-device ML Kit text detection. The selected region is passed to the local LaMa ONNX model. The generated fill is composited into a new bitmap only within the selected mask and exported as a separate media item. Version metadata records the source URI, output URI, operation, prompt, model ID and timestamp in Room.

The LaMa model is approximately **208 MB** and is downloaded once. Erase inference runs on-device after download. The current editor is a mask-and-confirm inpainting workflow; a general prompt-driven generative image editor is not yet implemented.

## Privacy and safety boundaries

- Gallery recognition, local chat and inpainting are designed to run on-device.
- Web search opens an external browser/search destination; private photos, content URIs and gallery contents must not be sent as search queries.
- Model downloads and web search require a network connection.
- Media removal commands are intended for explicit user requests and move items to recoverable trash; permanent deletion is not exposed by the agent.
- Imported skills are untrusted text guidance. They cannot expand the tool allowlist or execute code.
- AI Erase does not overwrite the original.

## Build requirements

- JDK 17+
- Android SDK platform 36
- Gradle wrapper 8.13 (committed)
- Android Gradle Plugin 8.13.2
- Kotlin 2.3.20 / KSP 2.3.12

## Build and test

```bash
./gradlew --no-daemon :app:check
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Successful GitHub Actions verification runs also execute Room migration tests on an Android API 35 emulator and publish the APK as the `aura-gallery-debug-apk-<run-number>` artifact (14-day retention); open the run under Actions → Android verification and download it from Artifacts. The release APK is currently unsigned unless the documented release-signing environment variables are supplied.

Room 2.8.5 schema processing currently uses KSP. The app adds `kotlin-metadata-jvm:2.4.21` to the KSP processor path for Kotlin 2.4 metadata carried by LiteRT-LM 0.19.1. Revalidate this compatibility bridge when upgrading LiteRT-LM, Kotlin or KSP.

## UI design system

The Compose UI follows the supplied Aura design system:

- Graphite surfaces (#0A0A0B, #101011, #171719) and warm paper text.
- Safelight amber (#E0A24A) for user action and selection; spectral blue-violet for real model inference.
- Instrument Serif for editorial headings, Manrope for controls/body, and JetBrains Mono for counts and machine metadata.
- Square image frames, 1dp contact-sheet gutters, hairline separators and 10dp control corners.

The fonts are bundled under `app/src/main/res/font`; their OFL license texts are included in `app/src/main/assets/font-licenses`. Fonts do not load from the network.

## M1 media access

- Photo Picker is available without broad media permissions.
- Full device-image access requests version-appropriate Android permissions.
- Android 14+ partial selection is supported and shown explicitly.
- Favourites are stored in Room and survive process restarts.
- The gallery displays device content URIs, not bundled sample images.

## Project documentation

- [Local Agent and AI Erase guide](docs/LOCAL_AGENT.md)
- [Project plan](docs/PROJECT_PLAN.md)
- [Roadmap](ROADMAP.md)

See the roadmap for milestone scope, acceptance gates, measured performance and outstanding validation.
