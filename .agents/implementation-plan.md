# MCP-box Implementation Plan

## Planning Horizon
This roadmap is designed for future AI sessions to continue work without re-discovery.

## Phase 0 - Baseline Stabilization
- [ ] Verify local build environment (`JAVA_HOME`, Android SDK, Gradle sync).
- [ ] Run:
  - `:app:compileDebugKotlin`
  - `:app:assembleDebug`
  - `:app:testDebugUnitTest`
- [ ] Resolve remaining lint/warning debt that impacts maintainability.
- [ ] Add a short `docs/test-matrix.md` for device/API validation notes.

## Phase 1 - Virtual Screen Reliability
- [ ] Add explicit state machine in `FloatingVirtualDisplayService`:
  - `Idle`, `OverlayVisible`, `SurfaceReady`, `DisplayReady`, `LaunchPending`, `Launched`.
- [ ] Add resilient timeout/retry for pending app launch.
- [ ] Persist and restore selected package across service recreation.
- [ ] Add clearer user feedback (toast/snackbar/notification update) on launch failures.
- [ ] Capture display metrics/device policy details in logs for troubleshooting.

## Phase 2 - MCP API Hardening
- [ ] Introduce versioned API prefix (`/v1/...`) while keeping legacy endpoints temporarily.
- [ ] Add lightweight auth token check for localhost requests.
- [ ] Standardize response envelope:
  - `{ ok: boolean, data?: object, error?: { code, message } }`
- [ ] Validate JSON payloads with strict schema checks.
- [ ] Add health details (`overlayVisible`, `virtualDisplayReady`, `accessibilityConnected`).

## Phase 3 - Accessibility Tooling Expansion
- [ ] Add actions:
  - input text by selector,
  - long-click,
  - scroll forward/backward,
  - coordinate gesture tap/swipe.
- [ ] Add safer selectors (exact text, contains text, view-id, content-desc).
- [ ] Add dry-run mode returning matched nodes without performing action.
- [ ] Add audit log for user-visible action history.

## Phase 4 - UX and Session Productivity
- [ ] Add in-app MCP tester screen to issue endpoint calls without adb.
- [ ] Add last-command replay and templates for common flows.
- [ ] Show real-time service statuses on home screen.
- [ ] Add guided setup checklist UI (overlay -> accessibility -> services -> pick app).

## Phase 5 - Testing and CI
- [ ] Unit tests:
  - endpoint routing/parsing,
  - app listing filters,
  - action mapping logic.
- [ ] Instrumentation tests for permission-gated flows where possible.
- [ ] Add GitHub Actions or local CI script for compile + tests + lint.
- [ ] Add regression checklist for Android 12/13/14/15 devices.

## Risk Register
- Virtual display launch behavior differs by OEM and security policy.
- Accessibility policy compliance must remain explicit and user-driven.
- Overlay restrictions may break expected UX in aggressive ROMs.
- Foreground service policy changes (Android 14+) can require manifest updates.

## Definition of Done (Per Feature)
- [ ] Code compiles (`compileDebugKotlin`).
- [ ] No new critical lint or manifest errors.
- [ ] Endpoint or UI behavior documented in `README.md`.
- [ ] Manual test steps captured with expected results.
- [ ] Failure mode and fallback behavior explicitly handled.

## Session Handoff Template
When ending an AI session, append:
- What changed (files + intent).
- What was verified (commands/tests).
- Known blockers (env/device/policy).
- Next 1-3 actions for the next session.

