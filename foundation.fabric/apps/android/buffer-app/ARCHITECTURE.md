# Buffer Android architecture

This module is organized by responsibility rather than by the original file size.

## Packages

- `domain/` contains Kotlin models, validation, scheduling and business rules. It has no Android, network, database or Compose imports.
- `domain/model/` contains the persisted Buffer card, capture and weekly summary models.
- `domain/CardLookup.kt` is the small port used by `FindCardUseCase`; the domain lookup rule does not depend on SQLite.
- `data/local/` owns SQLite, event persistence, durable jobs and settings. `BufferRepository` implements the domain lookup port and keeps presentation data separate from card content.
- `data/llm/` owns the OpenAI-compatible HTTP transport, MiMo ASR conversion/client, classification worker, multi-round classification agent and weekly LLM worker. `BufferCaptureAgent` executes only the local read tools exposed to the model and saves classification, relations and optional presentation atomically through the repository.
- `data/device/` owns Vela discovery, BLE/LAN enrollment, pairing persistence and the bridge protocol.
- `data/system/` contains Android system integrations such as Health Connect export.
- `ui/` contains Compose screens, card/detail components, overlays, labels, navigation and theme. `BufferScreen` resolves a selected card through the passed lookup callback, so archived cards outside the 100-item UI window can still open without a broad main-thread query.
- The package root contains Android entry points and dependency assembly (`BufferApplication`, `MainActivity`, widget/service/receiver entry points). It is still an orchestration layer; feature rules live below it.

## Persistence and editing rules

Captures and cards remain in the existing `buffer.db`; the refactor does not clear or migrate user records destructively. Editing card title or summary removes the saved `card_presentation:<cardId>` value because an LLM layout is stale after user content changes. Deleting a card removes the same presentation value.

The classification pipeline keeps the original capture immutable, then runs one bounded job. The agent may search old local records and read only IDs returned by that search. The assistant message is appended in full between HTTP rounds, including `reasoning_content`, followed by tool receipts. The final proposal, relations and presentation are applied together; the UI only renders the saved presentation.

## Verification scope

The unit suite covers repository behavior, domain rules, OpenAI-compatible request validation, AAC-to-WAV conversion, MiMo ASR request construction, classification persistence, the card lookup fallback and a two-round function-call exchange that checks reasoning and tool receipt forwarding. Tests use Robolectric and local HTTP servers; they do not call a real MiMo service and do not replace emulator/device UI acceptance.

Build from `apps/android` with JDK 21:

```sh
bash gradlew :buffer-app:assembleDebug :buffer-app:lintDebug :buffer-app:testDebugUnitTest
```

The final APK is written to `buffer-app/build/outputs/apk/debug/buffer-app-debug.apk`.
