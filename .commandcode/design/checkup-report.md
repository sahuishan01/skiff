# Checkup Report — Skiff Android UI

**Date**: 2026-07-18  
**Target**: `MainActivity.kt` + `Theme.kt` (redesigned)  
**Score**: 43 / 60

---

## Vital Sign Scores

| Vital | Score | Status | Evidence |
|---|---|---|---|
| Intentionality | 8 / 10 | Healthy | Deliberate dark-teal palette, authored type scale, purposeful composition shift from card-list to hero+timeline. Icons are generic system defaults (Refresh, Menu) — no custom iconography. |
| Readability | 10 / 10 | Healthy | 15:1 text contrast on primary, 5:1 on secondary, 3.5:1 on muted labels. 36sp monospace pairing code with 6sp letter-spacing is exceptionally scannable. Body at 14sp is correct for mobile. |
| Usability | 7 / 10 | Watch | Primary task flow (pair → send → track) is intact and well-served. Save location configuration is buried in a Settings dialog behind a hamburger icon — discoverability risk. |
| Responsiveness | 5 / 10 | Critical | Functions on 320–480px phones but has no tablet-aware layout. Column stretches full-width with no max-width constraint. No landscape reflow — the same vertical stack applies at any aspect ratio. |
| Speed | 7 / 10 | Watch | LazyColumn and Room Flow are efficient. Entire `setContent` block recomposes on every state flow emission — no per-composable `remember` isolation. Could jank during rapid progress updates with many active transfers. |
| Accessibility | 6 / 10 | Watch | Good content descriptions on icons and semantic colors. `PairRequestDialog` blocks back-button dismiss intentionally but traps keyboard users. No custom focus ring styling — relies entirely on Material3 defaults. |

---

## Critical Issues

### 1. No tablet or landscape adaptation (Responsiveness: 5/10)

The entire layout is a single `Column` with no breakpoint awareness. On a 10-inch tablet in portrait, the pairing code hero stretches absurdly wide. In landscape on a phone, the hero + actions consume the full viewport height, pushing the transfer list below the fold.

**Prescription**: Wrap the layout in a `BoxWithConstraints` and reflow into a side-by-side composition above 600dp width (hero+actions on left, transfers on right). At minimum, constrain content width with `Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally)` on tablets.

**Fixing command**: `/design responsive`

---

## Watch Issues

### 2. Save location discoverability (Usability)

The `custom_save_path_uri` preference and folder picker are only accessible through the Settings dialog. A first-time user who receives a file has no prompt or indication of where files are saved or that they can change it.

**Prescription**: Add a one-time prompt/banner on first file received ("Files saved to Downloads. Tap to change location.") or surface a compact indicator in the transfer area.

### 3. Recomposure scope (Speed)

State flows are collected at the `setContent` boundary, meaning the entire composable tree recomposes when any state value changes — including rapid `bytesTransferred` updates on active transfers.

**Prescription**: Extract `TransferTimelineItem` to be a standalone composable with its own `derivedStateOf` for progress calculations. Isolate the transfer list into a `@Composable` function that only observes the transfers flow.

### 4. Dialog escape trap (Accessibility)

`PairRequestDialog` has `onDismissRequest = { /* Force action */ }` — an empty lambda that prevents back-button and tap-outside dismissal. This is intentional to force accept/reject, but it creates a focus trap for keyboard/switch-access users.

**Prescription**: If the user opens the dialog and navigates away (app backgrounded), treat it as implicit rejection. Add `onDismissRequest = { SkiffBackgroundService.rejectPairRequest(senderId) }` or at minimum provide a "Not now" escape.

### 5. No reduced-motion consideration (Accessibility)

Compose's default animations (ripple, dialog appear) will fire regardless of `android:animateLayoutChanges` or system-level reduced-motion settings.

**Prescription**: Wrap animations in `LocalView.current.isInEditMode` alternatives or use `MutableTransitionState` with `animateEnterExit` gated behind `AccessibilityManager.isTouchExplorationEnabled`. Compose respects system animation scale settings by default for most components, but explicit checking would harden it.

---

## Healthy Verdicts

**Intentionality** is strong — the warm-teal-on-charcoal palette, monospace pairing code hero, and flat timeline rows show authored choices rather than defaults. The icon set is the only placeholder tell.

**Readability** exceeds WCAG AA across all text levels. The pairing code is a standout — 36sp monospace with wide letter-spacing is optimized for the exact use case (verbal transmission of 6 characters).

---

## Next Actions by Impact

1. **Prescription**: `/design responsive` — add tablet/landscape reflow and max-width constraint
2. **Fix**: Replace `onDismissRequest = { }` with actual dismiss handler
3. **Fix**: Add one-time save-location prompt for first-time receivers
4. **Low**: Source or create custom icon assets to replace Refresh/Menu
