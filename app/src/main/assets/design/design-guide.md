# VibeApp UI Design Guide

Material Components baseline for on-device generated Android utility apps.
Bundled theme parent is `Theme.MaterialComponents.DayNight.NoActionBar`.
All tokens below are MaterialComponents (M2), not M3.

## Tokens

### Colors (attr reference only — do not hardcode hex unless Creative Mode)

- Brand: `?attr/colorPrimary`, `?attr/colorPrimaryVariant`, `?attr/colorOnPrimary`
- Secondary: `?attr/colorSecondary`, `?attr/colorSecondaryVariant`, `?attr/colorOnSecondary`
- Surfaces: `?attr/colorSurface`, `?attr/colorOnSurface`, `?android:attr/colorBackground`
- Feedback: `?attr/colorError`, `?attr/colorOnError`

For faded secondary text, use `?attr/colorOnSurface` + `android:alpha="0.6"`
(0.7 for slightly more contrast). Do NOT use `colorOnSurfaceVariant` — it
does not exist in MaterialComponents.

### Typography

Use textAppearance styles, not raw sp values.

- `@style/TextAppearance.MaterialComponents.Headline4` — large display numbers
- `@style/TextAppearance.MaterialComponents.Headline5` — screen headlines
- `@style/TextAppearance.MaterialComponents.Headline6` — toolbar titles, section headers
- `@style/TextAppearance.MaterialComponents.Subtitle1` — list item titles, card titles
- `@style/TextAppearance.MaterialComponents.Subtitle2` — secondary headers
- `@style/TextAppearance.MaterialComponents.Body1` — primary body copy
- `@style/TextAppearance.MaterialComponents.Body2` — secondary body copy, list subtitles
- `@style/TextAppearance.MaterialComponents.Button` — button labels (applied automatically by MaterialButton)
- `@style/TextAppearance.MaterialComponents.Caption` — small labels, footnotes
- `@style/TextAppearance.MaterialComponents.Overline` — uppercase section markers

### Spacing

Only pick from: **4 / 8 / 12 / 16 / 24 / 32 dp**.

- 4dp — micro gap (icon ↔ label)
- 8dp — tight grouping
- 12dp — button gap
- 16dp — screen horizontal padding, major vertical gap (default)
- 24dp — section gap
- 32dp — large centered padding (empty/error states)

### Shape

Corner radius: **4 / 8 / 12 / 16 / 28 dp**.

- 4dp — text fields
- 8dp — chips
- 12dp — cards (default)
- 16dp — large feature cards
- 28dp — full-height buttons, pill shapes

Elevation: **0 / 1 / 3 / 6 dp**.

- 0dp — flat surfaces
- 1dp — cards (default)
- 3dp — bottom action bars
- 6dp — FABs, elevated dialogs

## Components

### MaterialToolbar

ALWAYS use as a regular View. NEVER call `setSupportActionBar()`.

```xml
<com.google.android.material.appbar.MaterialToolbar
    android:layout_height="?attr/actionBarSize"
    android:background="?attr/colorPrimary"
    app:title="Title"
    app:titleTextColor="?attr/colorOnPrimary"
    app:navigationIcon="@android:drawable/ic_menu_revert"
    app:navigationIconTint="?attr/colorOnPrimary"/>
```

Wire in Java: `toolbar.setNavigationOnClickListener(v -> finish());`

### MaterialCardView

Default radius 12dp, elevation 1dp.

```xml
<com.google.android.material.card.MaterialCardView
    app:cardCornerRadius="12dp"
    app:cardElevation="1dp"
    app:cardBackgroundColor="?attr/colorSurface">
```

### MaterialButton

- Filled (`@style/Widget.MaterialComponents.Button`) — primary action
- Outlined (`@style/Widget.MaterialComponents.Button.OutlinedButton`) — secondary
- Text (`@style/Widget.MaterialComponents.Button.TextButton`) — tertiary / in-card

### TextInputLayout / TextInputEditText

Use the Outlined variant. Set `app:helperText` for hints, call
`setError(...)` for validation.

### RecyclerView

Item spacing via `paddingVertical` on the RecyclerView, not ItemDecoration.

### Switch

`MaterialSwitch` and `SwitchMaterial` are NOT bundled. Use
`androidx.appcompat.widget.SwitchCompat` instead.

## Layout

- Default screen horizontal padding: 16dp.
- Default vertical gap between components: 16dp.
- Minimum touch target: 48dp (list items 64dp for comfort).
- Form row height: ≥56dp.
- Avoid edge-to-edge by default; content sits below the status bar and
  above the navigation bar. Do not make system bars transparent unless
  the user explicitly asks for immersive UI.

## Creative Mode

Triggered when the user's request contains subjective aesthetic keywords
such as: 好看, 有设计感, 复古, 童趣, 酷炫, 极简, 暗黑, or "像 ___ 一样".

In Creative Mode:

- **Skip** `search_ui_pattern` entirely. Write fresh XML.
- **Allowed overrides:** hard-coded color hexes, custom Typeface from Fonts,
  custom background drawables, unconventional paddings *outside* the
  spacing whitelist (use sparingly).
- **Still enforced:** MaterialToolbar rule, ShadowActivity requirement,
  bundled-library-only constraint, 48dp touch targets, non-edge-to-edge
  default (unless user explicitly asks for fullscreen).

The goal is to let expressive requests feel distinctive while keeping the
output compilable and usable on a real phone.
