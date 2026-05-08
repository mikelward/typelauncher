# TODO

- Extend the widget picker filter to also match individual widget labels (and possibly description) within an app group, not just the app group names. Today (`WidgetsScreen.kt`'s `WidgetPickerCard`) only filters by `WidgetProvider.appName`.
- Decide whether empty-query Enter/Search should continue opening Type Launcher settings or do something else.
- Revisit cached Home keyboard geometry after adding a permanent bottom reservation for recents / notification bars. Today `keyboard_reservation_bottom_px` keeps Home in typing-height geometry even after the user dismisses the IME with Back; that is intentional for the current hot path, but it should become redundant once the app list and dock reserve stable bottom space independent of keyboard visibility.
- Decide the secondary-tray behavior when `Show keyboard automatically` is disabled. The current tray is coupled to cached keyboard geometry from the type-first path; keyboard-opt-out users may need a stable bottom reservation that is not derived from IME auto-show.
- Design keyboard access for launching docked apps.
