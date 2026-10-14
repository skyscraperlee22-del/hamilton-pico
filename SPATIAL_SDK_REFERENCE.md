# PICO implementation reference

## Versions and provenance
- Official installed template: PICO SDK 6.1 `templates/spatial/stage`.
- PICO Spatial BOM: 6.1.9; Kotlin: 2.1.20; AGP: 8.13.2; Gradle: 8.13.
- Android: min/target/compile API 35, arm64-v8a.
- Template origin recorded in `.spatialsdk/template-source.txt`.
- Geometry source: `../web-stage/public/assets/stage.glb`, the established game asset.
- `theater-ambient.ktx`: six-face KTX from the installed Spatial Editor's `resources/builtin/assets/env/SystemIbl_ibl_brightness.ktx`. This is an environment lighting resource, not a visible theater background. Preserve PICO SDK/tool asset usage terms when distributing a release.

## Scene contract
The existing source and optimized GLB were independently measured with the scene-builder skill's `calculate_bbox.py`. Both have bounds min (-16, -0.515, -9.5), max (16, 12.3, 15.5) metres. This includes the extended floor, side wings and overhead rigging. Runtime scale is (1, 1, 1), rotation is zero. Viewer placement is recorded in `.spatialsdk/scene_transforms.json`.

The floor origin is at the viewer's feet; the OS supplies head position and height. Do not add an artificial 1.6-metre head-height offset. The world is now lowered to Y=-0.42 m, raising the audience by 45 cm relative to the stage-only version. The viewer starts 9.5 metres in front of the turntable centre, facing the backdrop.

Original GLB: 1,492 mesh nodes. The preparation script excludes 117 baked actor meshes, so the independently loaded cast can animate. It merges the remaining 1,375 objects by material into 17 mesh batches, preserving 21,790 triangles and baking original world transforms once. Matte roughness follows the existing web renderer. The sky adds one primitive mesh; lighting adds one directional key and two spots.

## Native APIs verified against installed 6.1.9 source
- `DefaultStage { ... }` and manifest `pico.spatial.stage.style = 3` enter Full Space.
- `SpatialView(initial = { content, _ -> ... })` creates the ECS scene once.
- `Entity.loadSuspend("asset://hamilton-stage.glb")` imports native geometry asynchronously.
- `TransformComponent.setPosition`, `setEulerAngles` and `Entity.addChild` place the hierarchy.
- `StageEnvironmentLightingComponent(ImageBasedLightSource.Single(texture), exponent)` applies environment lighting. The input must be a cube map. An ordinary EXR loaded as a 2D texture is invalid for this component.
- `DirectionalLightComponent` and `SpotLightComponent` provide the stage lights; their local forward direction is -Z.
- `ShadowFaceCullingMode.AUTO` preserves each material's culling in the shadow pass. The sky material uses FRONT culling so its outward front hemisphere does not blanket the stage with shadow.
- `Entity.destroy(recursively = true)` and owned resource `.close()` calls release the stage on disposal.

## Local reference locations
- `~/.codex/skills/spatialonboarding/SKILL.md`
- `~/.codex/skills/spatialsdk/SKILL.md`
- `~/.codex/skills/spatialsdk/references/rendering-materials-lighting/`
- `~/.codex/skills/spatial-sdk-scene-builder/SKILL.md`
- `~/.codex/skills/emulator-cli/SKILL.md`

## Cast interaction and presentation
- `CollisionComponent + InteractableComponent` on each actor root; measured bounds are expanded by only 4–5 cm for hitting.
- `HoverEffectComponent` provides the fallback native effect. Its SDK API has no intensity or dwell callback. With valid eye/controller tracking, a per-actor emissive tint implements the timed fade and the name panel appears after 750 ms. Base emissive values are restored on exit.
- `EyeTrackingProvider` requires `com.picovr.permission.EYE_TRACKING` at runtime; the app uses Android's permission dialog once. Controller tracking is Full Space only. Data is consumed locally without recording or logging gaze.
- Ray endpoints are converted from Stage coordinates into each actor's measured local bounds; nearest positive intersection wins. Stale tracking is discarded.
- `detectSpatialTapGesture(context)` retains native tap release/cancellation. Dispatch resolves actor/name-card ancestry; valid GazePinch taps with no entity UUID use a fresh permitted gaze ray. Year dragging uses a separate recognizer.
- `attachmentPanelComponent` sizes are converted from dp to metres via `LocalPhysicalLengthConverter`; physical widths are 6.8 m (year), 2.4 m (biography, native WRAP_CONTENT height), 5.2 m (subtitles), and 1.5 m (name).
- Biography placement follows the selected model head bounds in theater coordinates, including Hamilton movement and the king on the gallery. BOTTOM_CENTER anchors its content-sized bottom 18 cm above the head/crown; the front offset is 80 cm. No selection hides it; every year change clears it. Subtitle panels have no background, only text shadows; hover names use 50% rounded stadium ends.
- `MediaPlayer` plays bundled asset file descriptors. Bilingual lyrics use actual decoder position, not elapsed animation time. Selecting a new character or year cancels the previous route/audio.
- Native `System.update` remains scene-scoped; an Android Choreographer pump shares and deduplicates monotonic frame times so SDK idle callback throttling cannot freeze interactions. Lifecycle cleanup removes callbacks, stops audio/tracking, destroys entities, then releases owned physics resources.

- During PLAYING, hover eligibility follows the same singer list as mouth animation, including co-singers. Remove native HoverEffectComponent, restore base emissive values and clear any prior dwell/card immediately for these actors. Collision/tap targets remain present, so pointing at a singer cannot hover someone hidden behind them. Completion, interruption and errors restore hover eligibility.

- Hover input gives valid eye gaze precedence; idle connected controller poses cannot suppress eye dwell. Controller rays are a fallback when eye samples are unavailable. Debug logs contain availability states only, never raw gaze vectors or target identity.

- HMDTrackingProvider uses a fixed AudienceReference to convert global head position/heading. PerformanceViewRig only moves the theater root with metre-space translation and yaw; no headset camera override or continuous tracking of the world.
- AttachmentPanelComponent supports WRAP_CONTENT through panelSize(480.dp, Dp.Unspecified), with Alignment.BOTTOM_CENTER. Biography Column uses wrapContentHeight and reports measured dp to the framing calculation.

- v1.6 native visual QA shows Burr and Hamilton centered together, both fully visible, subtitles under their shared midpoint and a content-height biography above Burr. Regression coverage distinguishes actual line-count changes from different strings that wrap to the same number of lines.

- v1.6.1: physical-device input logs showed valid GAZE_PINCH tap events with entityUUID=-1 and no SELECT. The previous TargetEntity predicate rejected them before the callback. The unfiltered native tap recognizer now dispatches through selectFromTap; only GazePinch with a missing entity may fall back to a fresh (<180 ms) permitted gaze. Known non-actor UI, non-gaze input and empty space never use this fallback. The current ray must hit a model or the shown name-card area; fading hover identity alone is insufficient. Name-card descendants resolve to their owning actor. HamiltonTap diagnostics log received/resolved booleans only, no gaze coordinates.

- v1.7: StageCatalog contains six StageScene entries and 14 shared model resources. activateScene swaps the active cast, placement, profiles and music, resets the navigator, and disables inactive actor roots. The native lyric panel and Compose content both require PLAYING plus an active cue. Audio cancellation tokens invalidate old callbacks on selection/year changes.

## Native caption surface
StageCaptions uses MeshResource.createPlane(5.2, 0.9048) in metres, on the XY plane facing Z; bounds are [-2.6,-0.4524,0] to [2.6,0.4524,0] per SDK plane documentation. TextureResource.create(Bitmap) and UnlitMaterial with TRANSPARENT blending retain transparent background and text shadow. Text is laid out at 2000×348; textures are replaced only on cue change and the previous texture is released. No collider is attached. The mesh is disabled whenever playback has no actual cue. Material, texture and mesh are closed after the scene hierarchy is destroyed.
