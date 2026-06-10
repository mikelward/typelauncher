# Privacy Policy

_Last updated: 2026-06-10_

Type Launcher is an Android home screen launcher. This policy describes what
the app does and does not do with your data.

## Summary

- Type Launcher does not collect, store, or transmit personally identifiable
  information (PII).
- All of your launcher data — your dock, hidden apps, launch counts, recents,
  widgets, and settings — lives only on your device.
- The app makes no network requests of its own to any backend operated by the
  developer.
- The app may collect anonymous crash reports and performance analytics.
  These are described below and never include PII, your typed queries, your
  app list, your notifications, or your calendar contents.

## Data stored on your device

Type Launcher persists the following entirely on your device, in Android
`SharedPreferences` and the app's private files directory:

- The list of apps you have docked.
- The list of apps you have hidden.
- Per-app launch counts and the most-recently-launched apps list (used to
  rank and show recents).
- Dock and UI settings (icon size, sort order, keyboard auto-show, etc.).
- A snapshot of the labels and metadata of installed apps, used to render the
  app list on the first frame after launch.
- Cached app icon bitmaps, used to render icons on the first frame after
  launch.
- Selected widgets and their custom heights.
- A short rolling in-memory log buffer (capped at 300 entries) used by the
  "Report bug" action.

None of this data is uploaded by the app. You can clear it at any time from
Android's **Settings → Apps → Type Launcher → Storage & cache → Clear
storage**.

## Permissions

Type Launcher requests Android permissions only for features it implements
locally on your device. Data accessed under these permissions is not
transmitted off your device by the app.

- **Query installed apps** (`QUERY_ALL_PACKAGES` / launcher app queries): read
  the list of apps installed on your device so they can be shown and
  launched.
- **Read calendar** (`READ_CALENDAR`): read calendar events from your device
  to render the Agenda screen. Event data is read on demand from
  `CalendarContract` and is never stored beyond the in-memory state of the
  current session.
- **Expand status bar** (`EXPAND_STATUS_BAR`): expand the system notification
  shade when you pull down on the home screen.

## What the app does not do

- It does not contact a backend service operated by the developer.
- It does not collect your name, email, phone number, contacts, location, or
  device identifiers (IMEI, advertising ID, etc.).
- It does not log or transmit your search queries.
- It does not log or transmit the names or contents of your installed apps,
  notifications, calendar events, or widgets.
- It does not show ads.
- It does not sell or share data with third parties for marketing.

## Anonymous crash reporting and performance analytics

Type Launcher may collect anonymous crash reports and performance analytics,
typically through a third-party service such as Google Firebase (for example,
**Firebase Crashlytics** and **Firebase Performance Monitoring**). The
specific service or services used may change over time.

These services may collect:

- Crash stack traces and the type of unhandled exception that caused them.
- A short rolling buffer of non-PII diagnostic breadcrumbs describing
  launcher lifecycle events (for example, "cold start began", "agenda load
  completed", "app icon cache miss"). These breadcrumbs do not include your
  search queries, app names, calendar contents, or notification contents.
- Anonymous performance traces (for example, how long cold start, agenda
  loading, or icon loading took) and high-level counters (for example, icon
  cache hit rate).
- Standard device and app metadata that the analytics service collects
  automatically: device model, OS version, app version, locale, and a
  service-generated installation identifier that is not tied to your Google
  account or to any PII collected by the app.

This information is used solely to diagnose crashes and performance
regressions and to improve the app. When Firebase is the provider, data is
processed by Google as a sub-processor; see Google's
[Firebase privacy and security](https://firebase.google.com/support/privacy)
documentation for details on its handling.

## Children

Type Launcher is a general-purpose launcher utility and is not directed at
children under 13. The app does not knowingly collect personal information
from anyone, including children.

## Changes to this policy

If this policy changes materially, the updated version will be published in
this repository and the "Last updated" date at the top of this document will
be revised.

## Contact

For questions about this policy, please open an issue at
<https://github.com/mikelward/typelauncher/issues>.
