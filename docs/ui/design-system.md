# UI Design System

## 1. Principles

NetMeter TW uses Jetpack Compose and Material 3 to provide an experience consistent with stock Android and Samsung One UI:

- Use standard Material 3 components.
- Support edge-to-edge layouts.
- Respect system dark mode and font scaling.
- Internationalize all user-facing and accessibility text.
- Clearly explain Android-version and permission requirements when system features are restricted.

## 2. Theme

### Fixed Color

Fixed color is the default theme mode (`AppThemeMode.DEFAULT`), with `#0E4686` (`AppThemeMode.DEFAULT_THEME_COLOR`) as the default color. Users may choose another fixed theme color. Theme code derives a usable Material 3 palette from the selected color.

The default applies whenever `key_app_theme_mode` has never been written, so existing users who never changed the theme mode also move from Dynamic Color to the fixed default after upgrading.

### Dynamic Color

Dynamic Color remains selectable and derives the Light or Dark `ColorScheme` from the system wallpaper palette.

### AMOLED Black

AMOLED Black applies only when fixed-color mode is active, the system is in dark mode, and the user has enabled the option. Primary backgrounds and surfaces become pure black while preserving content contrast.

## 3. Main Screen

The main screen shows total, upload, and download speeds; monitoring state and start/stop controls; notification and Overlay shortcuts; a Cloudflare speed-test entry; and cards for permission or service-start errors.

The Activity owns system Intents and permission launchers. `MainViewModel` owns state and application actions.

## 4. Settings

Settings are divided into General, Notification, Overlay, Background, and About. Phones use a main directory with secondary pages. At widths of 840dp or more, settings use a two-pane layout. General settings include an entry for rerunning Onboarding.

## 5. Controls and Values

- Boolean options use `SwitchPreference` or a Material 3 Switch.
- Enumerations use `ListPreference`.
- Continuous ranges use `SliderPreference`.
- Exact values use `TextFieldPreference`.
- Colors use `colorpicker-compose`.
- UI labels must state px, dp, sp, and speed units where relevant.

## 6. Responsive Design and Accessibility

- Long pages use `LazyColumn` or a scrollable `Column`.
- Button groups must remain readable and reachable on narrow screens.
- Every `IconButton` requires a localized `contentDescription`.
- Use Start and End semantics for RTL support.
- Do not use color as the only state indicator.
