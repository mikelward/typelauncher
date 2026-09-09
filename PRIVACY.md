# Privacy Policy

_Last updated: 2026-08-31_

Type Launcher is an Android home screen launcher. This policy describes what
the app does and does not do with your data.

## Summary

- Type Launcher does not collect, store, or transmit personally identifiable
  information (PII).
- All of your launcher data — your dock, hidden apps, launch counts, recents,
  widgets, and settings — lives only on your device.
- The app makes no network requests of its own to any backend operated by the
  developer.
- The app asks before sending anonymous crash reports and performance
  analytics, and sends nothing until you say yes.
  These are described below and never include PII, your typed queries, your
  app list, your notifications, your contacts, or your calendar contents.

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
  to render the Agenda screen and, if you turn on "Search calendar events" in
  Settings, to match upcoming events in home screen search. Event data is
  read on demand from `CalendarContract` and is never stored beyond the
  in-memory state of the current session.
- **Read contacts** (`READ_CONTACTS`): requested only if you turn on "Search
  contacts" in Settings, and used to show matching contacts — their names and
  photo thumbnails — in home screen search. Contact data is read from
  `ContactsContract` and is never stored beyond the in-memory state of the
  current session; turning the setting off releases it immediately.
- **Modify contacts** (`WRITE_CONTACTS`): requested only the first time you use
  a contact's long-press menu to favorite or unfavorite it, and used solely to
  set that contact's starred flag (`ContactsContract.Contacts.STARRED`) in your
  contacts app. No other contact fields are read or changed by it, nothing is
  sent off the device, and a denial simply leaves the contact unchanged.
- **Place calls** (`CALL_PHONE`): requested the first time you tap Call on a
  contact's quick actions, and used solely to dial the number you chose —
  either by handing it to Android's telephony service or, if you set Settings →
  "Call using" to "Ask which app", by asking Android which of your apps should
  place it. Either way the call is routed by Android's phone subsystem and your
  phone app, exactly as if you had dialed it there. If you deny the permission,
  tapping Call opens your default phone app with the number pre-filled instead,
  so nothing is ever dialed without the permission. The app places no calls on
  its own and dials nothing you didn't tap.
- **Expand status bar** (`EXPAND_STATUS_BAR`): expand the system notification
  shade when you pull down on the home screen.

## What the app does not do

- It does not contact a backend service operated by the developer.
- It does not collect your name, email, phone number, location, or device
  identifiers (IMEI, advertising ID, etc.). Type Launcher never asks Android
  for your location and holds no location permission, so nothing here reads
  where you are. One thing is worth naming rather than leaving to be inferred:
  if you have turned the analytics below on, the service receives those reports
  over the internet and, like any server, sees the network address they arrive
  from, from which it derives a coarse region — see **Coarse region** there. Contacts and calendar events are
  read on your device only for the opt-in search and agenda features
  described under "Permissions" and are never stored beyond the current
  session or transmitted.
- It does not log or transmit your search queries.
- It does not log or transmit the names or contents of your installed apps,
  notifications, contacts, calendar events, or widgets.
- It does not show ads.
- It does not sell or share data with third parties for marketing.

## Anonymous crash reporting, performance analytics, and usage statistics

With your permission, Type Launcher collects anonymous crash reports,
performance analytics, and usage statistics,
typically through a third-party service such as Google Firebase (for example,
**Firebase Crashlytics**, **Firebase Performance Monitoring**, and **Firebase
Analytics**). The specific service or services used may change over time.

These services may collect:

- Crash stack traces and the type of unhandled exception that caused them.
- A short rolling buffer of non-PII diagnostic breadcrumbs describing
  launcher lifecycle events (for example, "cold start began", "agenda load
  completed", "app icon cache miss"). These breadcrumbs do not include your
  search queries or the keys you press, the names or package names of your
  installed apps, contact names, photos or identifiers, calendar contents or
  identifiers, or notification contents.

  Each breadcrumb is a fixed sentence written into the app's source, plus
  values filled into it. A value is only included if its type cannot name
  anything of yours — a count, a duration, a time, a setting, a yes or no.
  Times are the app's own: when a previous run of the launcher ended, and
  when this build was installed or last updated, which together say whether
  it disappeared because the installer was replacing it. Anything
  that could identify you or something of yours is replaced with
  `<redacted>` before the breadcrumb leaves your device, and that is the
  default for every value the app does not explicitly mark otherwise. The
  full breadcrumb, with the values intact, stays on your device, where you
  can read it and choose whether to share it with the **Report bug** action.
- Anonymous performance traces (for example, how long cold start, agenda
  loading, or icon loading took) and high-level counters (for example, icon
  cache hit rate).
- A snapshot of your launcher **settings and counts** attached to each crash
  report, so a crash can be diagnosed without you having to send anything: the
  values you picked in Settings (app list layout, dock layout, icon size, sort
  order, theme, icon shape, and whether the dock, agenda, wallpaper, contact
  search, and calendar search are on), coarse app state (which page was open,
  whether the launcher was on screen, whether Type Launcher is your default
  launcher), and *how many* apps, docked apps, hidden apps, dock folders,
  widgets, and widget pages you have. These are counts and settings only —
  never which apps, which widgets, or what you searched for.
- Anonymous usage statistics: the events the analytics service records on its
  own, without Type Launcher asking it to. They cover installing, opening,
  closing and updating the app — the first launch after install, a session
  starting and ending, a screen opening, an update to a new version, an
  Android upgrade, and your clearing the app's data or uninstalling it — and
  with them **how long you were actively in the app**, which the service
  reports as engagement time. The service decides that list, not Type
  Launcher, and can add to it; what stays true whatever it adds is the line
  below, because it is a fact about this app rather than about the service:
  **Type Launcher sends no events of its own and sets no properties of its
  own.** Everything above is the service's automatic collection and there is
  no code here that could attach anything to it — so what it learns is that
  the launcher was opened, how often, and for how long, and never what you did
  inside it: no search queries or keys pressed, no app names or package names,
  no contacts, calendar entries, notifications, or widget contents.
- Standard device and app metadata that the analytics service collects
  automatically: device model, OS version, app version, locale, and a
  service-generated installation identifier that is not tied to your Google
  account or to any PII collected by the app.

  **Coarse region.** Reports reach the service over the internet, so it sees
  the network address they come from and derives an approximate region from it
  — typically the country. That is not a location Type Launcher collected: the
  app has no location permission and never asks Android where you are, and the
  region is inferred by the service from the connection itself, as it would be
  for any site you visit. It is named here because "no location" would
  otherwise read as a stronger promise than the internet can keep. Turning
  analytics off stops the reports, and with them this.

  **No advertising ID.** Type Launcher removes the `AD_ID` permission that
  Firebase Analytics would otherwise declare, and switches the advertising-ID
  collection off in the app's manifest. The identifier above is per-install
  and specific to this app: it cannot be joined up with your activity in other
  apps, and it is reset if you clear the app's data or reinstall.

This information is used solely to diagnose crashes and performance
regressions and to understand how much the app is used, so it can be
improved. When Firebase is the provider, data is
processed by Google as a sub-processor; see Google's
[Firebase privacy and security](https://firebase.google.com/support/privacy)
documentation for details on its handling.

### Nothing is sent until you say yes

Type Launcher asks. Settings shows a **Help make Type Launcher better?** card
with **No thanks** and **Yes please**, and the Settings gear carries a dot
until you answer it one way or the other. All three services are switched off
until you say yes, so nothing in this section is uploaded while the question is
unanswered. Ignoring the card is therefore a complete answer on its own; you
never have to act to keep it off.

Each service is switched off twice over: once in the app's manifest, which is
what the SDK reads as it starts up before any of the launcher's own code runs,
and again by the app once it has read your answer. The manifest default is
what closes the gap in between — without it a service would decide for itself
on that first launch, which is the launch that was supposed to be asking.

To be exact about the guarantee: what is switched off is *sending*. Crashlytics
can still write a crash record to your own device's storage, but an unanswered
question is treated as a no, so that record is discarded on the next launch
rather than held for you to consent to later. A crash that happened before you
answered is not sent even if you later allow — only crashes after that are.

The stored default is off, so this holds from the first launch of a fresh
install onward — there is no window in which anything is sent because nobody has
told the app not to yet.

### Changing your mind

Settings → **Help make Type Launcher better** turns crash reporting,
performance analytics, and usage statistics on or off at any time, and answers
the question if the card is still showing. The choice persists across restarts
and updates. There is one switch, not three: a single answer covers all of
them, and nothing described here is ever sent against it.

Turning it off — or tapping **No thanks** — also discards any crash report
that has been recorded but not yet sent, so a crash that happened while it was
off is not uploaded later if you turn it back on.

Turning it off does not affect the on-device debug log or the **Report bug**
action — that report is assembled on your device, and nothing is sent until
you choose an app to send it to and can review what it contains first.

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
