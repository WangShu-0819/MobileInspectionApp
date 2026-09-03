# Plan: MVP Template Overlay + Legacy Template Import

## Context

B2 Task 1 DPM migration is SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING (needs physical DPM samples for A/B). Physical acceptance must not block non-DPM feature development.

Product direction has changed: real-time contour projection / homography alignment is DEFERRED / POST-MVP. Current MVP = "template original image semi-transparent overlay for manual framing".

This plan delivers:
1. **Commit 1**: Sync control docs (todo.md, plan.md, AGENTS.md) to reflect real state
2. **Commit 2**: Legacy template import + template overlay + alpha slider

---

## Commit 1: Document Sync

Update these files to reflect actual software state:

### tasks/todo.md
- Keep B1 history as-is
- Mark B2 Task 1 as `SOFTWARE_COMPLETE / PHYSICAL_ACCEPTANCE_PENDING`
- Add new section: **B2 Task 2: Legacy Template Import + Template Overlay MVP**
- Mark long-term contour/homography items as `DEFERRED / POST-MVP`

### tasks/plan.md
- Update B2 section to show Task 1 complete, Task 2 next
- Add MVP pipeline: template import → ROI config → template overlay → capture → comparison → session ROI adjust → inspection → save
- Mark contour projection, SIFT alignment, homography as DEFERRED

### AGENTS.md
- Update "当前事实" section: B2 Task 1 SOFTWARE_COMPLETE
- Update "当前唯一任务" to new task
- Add note about deferred contour items

---

## Commit 2: Template Import + Overlay + Slider

### 2A. Directory Template Importer

**New file**: `app/src/main/java/com/wearable/inspection/mobile/template/DirectoryTemplateImporter.kt`

Reuses `TemplatePackageImporter`'s parsing logic for `template.json`. Thin adapter that reads an already-extracted directory instead of a ZIP file.

- Input: directory path containing `template.json` + `images/`
- Same validation (path traversal, partId regex, size limits)
- Same `TemplatePackage` output model
- Falls through to same `parseManifest()` logic

**Old data field mapping** (from `extracted_data/template_exports/*.zip`):

| Old JSON field | New Entity | New field |
|---|---|---|
| `partId` | `PartEntity` | `id` |
| `partName` | `PartEntity` | `name` |
| `dpmCode` | `PartEntity` | `dpmCode` |
| `regionName` | `InspectionTemplateEntity` | `name` |
| `imageFiles[0]` (first) | `InspectionTemplateEntity` | `mainImagePath` |
| `roi.{x,y,width,height}` | `RoiDefinitionEntity` | `normalizedRect` (JSON) |
| region order | `InspectionTemplateEntity` | used for `RoiDefinitionEntity.order` |

Each region → one `InspectionTemplateEntity` + optionally one `RoiDefinitionEntity` (from roi field).

### 2B. Import Orchestration

**New file**: `app/src/main/java/com/wearable/inspection/mobile/template/TemplateImportService.kt`

Orchestrates the full import flow with transaction rollback:
1. Parse ZIP or directory → `TemplatePackage`
2. Copy images to app private directory (stable naming)
3. Upsert `PartEntity` (from partId/partName/dpmCode)
4. For each region: insert `InspectionTemplateEntity` (UUID id, mainImagePath = first image)
5. For each region with valid roi: insert `RoiDefinitionEntity`
6. On any failure: delete all copied files + rollback DB (using `withTransaction`)

**Modify**: `InspectionRepository.kt` — add `importTemplatePackage(pkg: TemplatePackage)` method with transaction support.

### 2C. Template Overlay on CameraPreview

**Modify**: `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt`

Add template image overlay inside the camera preview Box:
- New parameter: `templateImagePath: String?` and `overlayAlpha: Float`
- Load image via `BitmapFactory.decodeFile()` inside `remember`/`LaunchedEffect`
- Display using `Image()` composable, constrained to `contentRect` only
- Maintain template aspect ratio (no stretch)
- Use `Modifier.alpha(overlayAlpha)` for transparency
- If image missing/corrupt: show error state, no crash
- Template switch → atomic update (no old image residue)

Key constraints:
- Only overlay within contentRect, never in letterbox
- Don't modify CameraController's FIT_CENTER or contentRect infrastructure
- Don't rebind CameraX when slider changes
- No automatic contour drawing (deferred)

### 2D. Alpha Slider

**Modify**: `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt`

Add below the camera preview section (or as an overlay control):
- Slider range: 0.0f to 0.8f, default 0.45f
- Display "模板透明度 XX%" text
- State hoisted in composable (remember), not SettingsStore (one slider doesn't need new architecture)
- Real-time update, no CameraX rebind
- "隐藏/显示模板" toggle button (simple visibility switch)

### 2E. Wire Template Selection to Overlay

**Modify**: `LiveInspectionScreen.kt` — pass selected template's `mainImagePath` to CameraPreview's overlay parameters.

### 2F. Tests

**New file**: `app/src/test/java/com/wearable/inspection/mobile/template/DirectoryTemplateImporterTest.kt`
- Normal directory with template.json + images
- Missing template.json
- Corrupt JSON
- Missing images
- Path traversal attempts
- Empty regions

**New file**: `app/src/test/java/com/wearable/inspection/mobile/template/TemplateImportServiceTest.kt`
- Successful import → DB + files created
- Partial failure → rollback (no orphan files/records)
- Duplicate import (idempotent)
- Missing images → warnings but still imports regions

**New file**: `app/src/test/java/com/wearable/inspection/mobile/ui/screens/TemplateOverlayTest.kt`
- Alpha clamping (0.0 to 0.8)
- Default alpha = 0.45f
- No template → no overlay
- Template aspect ratio preservation

---

## Files Modified

### Commit 1 (docs)
- `tasks/todo.md`
- `tasks/plan.md`
- `AGENTS.md`

### Commit 2 (code)
**New**:
- `app/src/main/java/com/wearable/inspection/mobile/template/DirectoryTemplateImporter.kt`
- `app/src/main/java/com/wearable/inspection/mobile/template/TemplateImportService.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/DirectoryTemplateImporterTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/template/TemplateImportServiceTest.kt`
- `app/src/test/java/com/wearable/inspection/mobile/ui/screens/TemplateOverlayTest.kt`

**Modified**:
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/CameraPreview.kt` — add overlay
- `app/src/main/java/com/wearable/inspection/mobile/ui/screens/LiveInspectionScreen.kt` — alpha slider + overlay wiring
- `app/src/main/java/com/wearable/inspection/mobile/data/repository/InspectionRepository.kt` — import orchestration

---

## Test & Verification

1. `.\gradlew.bat :app:testDebugUnitTest --no-daemon` — all existing + new tests pass
2. `.\gradlew.bat :app:assembleDebug --no-daemon` — BUILD SUCCESSFUL
3. Install on ERLDU20429005890, import `template_part_black_1787034342672.zip`, verify template appears in UI
4. CameraPreview shows template overlay within contentRect only
5. Alpha slider works 0%–80%, no camera rebind
6. Template switch → atomic image update
7. All logcat gates pass (no FATAL EXCEPTION, no Camera already in use, etc.)

---

## DEFERRED / POST-MVP

These items are explicitly NOT part of this round:
- Real-time contour extraction and projection
- SIFT/feature matching homography alignment
- ALIGNED/LOST auto-alignment as capture gate
- ROI auto-tracking
- New ROI inspection algorithms
- OCR, TTS, ResultPackager, ForegroundService
- CaptureComparisonScreen (post-capture dual-image view)
