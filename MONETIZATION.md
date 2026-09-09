# Monetization

**Status: exploration, and nothing here is being locked in** (maintainer,
2026-09-03). It is written as options and their costs, not as a set of decisions
waiting for a signature. `SPEC.md` records what the launcher does; `TODO.md`
records what gets built next. This page records the money argument: what a paid
tier would sell, what it must not sell, and what has to happen before a price is
worth setting at all.

## The constraint that outranks the price

**Distribution, not pricing.** A paywall on an app with few installs teaches you
that the app has few installs. Type Launcher is the app in this family closest to
having a real audience — it is the most complete, it is localized, and "launcher"
is a category people actively search — so the cheapest work here is the store
listing, not the billing integration. See "Marketing".

## What is never for sale

- **Being a home screen.** Typed launching, the app list, the dock, and **app
  search** — the thing the user chose this launcher for stays free. A launcher
  that degrades is not an upsell, it is a phone that stops working.

  **"App search", not "search" flatly** (Codex, 2026-09-03). An earlier version
  of this bullet exempted search without qualification, which contradicts the
  strongest gate candidate on the page: local *content* search — contacts and
  calendar — is exactly what the tier would sell, and the billing section has the
  gated sections consulting the entitlement. So the invariant is that **typing a
  few letters always finds and launches an app**, whatever the entitlement says or
  fails to say; whether the same box also returns people and events is the paid
  question. Naming the boundary here rather than only in the tier section is what
  keeps the two from reading as a contradiction.
- **A feature someone already has, on the device they have it on.** Not the same
  as "anything already shipped" — see the next section, which is the distinction
  this list previously got wrong. Revoking working behavior from an existing
  install is what earns review bombs; charging *new* installs for a built feature
  does not.
- **A user's own data.** Renames, hidden apps, dock layout, icon overrides and
  launch stats are the user's; export and restore of them is not a hostage.
  **We don't hold a user's data captive** — that is the reason, and it holds on
  its own (maintainer, 2026-09-03). The platform also **requires backup and
  restore from 2027**, which makes it moot as well as wrong.

---

## Shipped features are candidates — the two moves are not the same

An earlier version of this page ruled out charging for anything already built.
That was wrong, and it was wrong by conflating two different acts (maintainer,
2026-09-03):

| | **Revoke** | **Gate for new installs** |
|---|---|---|
| What happens | A working feature stops working for someone who has it | Existing users keep it; new installs need the unlock |
| How it reads | "They took it away" | "That's a paid feature" |
| Review risk | High — this is the classic review-bomb trigger | Ordinary; the category does this constantly |
| Eligible here? | **No** | **Yes** |

So the built features are back on the table, and they are the best candidates the
app has, because they are the ones that are actually *finished*:

- **Contacts and calendar search.** `SPEC.md` "Home screen behavior": two optional
  sections appended to typed results, each behind its own Settings switch and
  runtime permission, both off by default. Substantial, differentiating, and the
  thing a launcher user would notice missing.
- **Acting on a result**, which goes further than it first looks: a contact result
  opens quick-action channels in the app list — Phone, Message, every installed
  app registering a contact action, then Email — with a second step for multiple
  numbers, SIM-country-aware ordering keyed off the default voice and SMS SIMs,
  a long-press that writes `IS_SUPER_PRIMARY`, and a favorite toggle.
- **The Agenda page.**

**The one honest cost, stated once.** Gating for new installs only works if the
app can reliably tell an existing user from a new one, and it cannot — a marker no
older build ever wrote can't identify a pre-cutoff install, so a reinstall or a
device move misclassifies a genuine early user and turns *gate* into *revoke* for
them. That is not a reason to abandon the approach — at roughly two users it is a
reason to **settle it case by case on the evidence** rather than build machinery
for it (maintainer, 2026-09-03). Someone abusing your judgment costs nothing;
someone denied costs a review.

**And it is much cheaper now than later**, since the population that could be
misclassified only grows.

**Still worth knowing what is shipped before pricing it.** *"Is this already
shipped?"* stays a gate every candidate passes — not to disqualify it, but because
a tier built on features that already work is a different product decision (and a
different migration) from one built on features that don't exist yet. A `grep`
through `SPEC.md` and the relevant store or view model, not a guess.

## Why wallpaper is a weak paid feature

The other floated candidate is backgrounds and wallpaper. Three problems:

1. **Every free launcher has it**, including the system one. It is the least
   differentiated thing a launcher can charge for.
2. **It is off-brand.** This launcher's identity is a search field and a keyboard
   — it earns its keep by getting out of the way. A wallpaper picker is what a
   *decorative* launcher sells, and competing there means competing with launchers
   that are much better at decoration.
3. **It is the feature most likely to cost frames.** `AGENTS.md` makes jank a hard
   acceptance criterion, and a blurred or animated background on the home screen
   is exactly the kind of per-frame cost that criterion exists to catch. Paying to
   make the launcher slower is a bad trade in both directions.

**And the system wallpaper is already shipped**, so it would be a gate on a built
feature rather than a new one: `SPEC.md` documents the `Show wallpaper` setting
(off by default) that makes the live device wallpaper the Home backdrop via
`FLAG_SHOW_WALLPAPER`, plus a `Wallpaper` row whose `Change` action opens the
system picker. That is *allowed* under the section above — but it is the weakest
thing on the table to spend the gate on, because points 1–3 still hold: it is
undifferentiated, off-brand, and the version worth paying for is the version that
costs frames. Gate the search features instead and let wallpaper stay free.

---

## What would actually be worth paying for

Ranked by *would a user notice this was missing*, and filtered by: no recurring
developer cost, no permission the free app does not already ask for, and — for
anything already built — worth the misclassification cost above.

**Shipped, and the strongest thing to gate:**

1. **Local content search: contacts and calendar.** The best candidate the app
   has. It is finished, it is the feature that makes a typed launcher a *search*
   tool rather than an app grid, and it is the one a user would immediately notice
   missing. **Zero *incremental* build cost — the search itself is done — but not
   zero cost and not all margin** (Codex, 2026-09-03): selling it still means a
   Billing integration, a purchase and paywall UI, entitlement persistence that
   survives a reinstall without surviving a restore onto a different buyer, the
   can't-answer handling below, and tests for all of it. Play then takes 30% of
   each sale unless the account is enrolled in the reduced first-$1M tier. The
   argument for this candidate is that the *feature* is free to supply, not that
   the tier is free to build; the rest of its cost is on the migration side, which
   the section above lays out.
2. **The contact quick-action channels**, and the Agenda page. Same argument, and
   they naturally bundle with (1) as one "your stuff, not just your apps" tier —
   which is a cleaner story than three unrelated unlocks.

   **Gating Agenda has a cost the other candidates don't: it makes a known
   carousel bug reachable** (Codex, 2026-09-03). `TODO.md` item 1 under the
   carousel-gesture list records that `SwipeNavigationBox`'s horizontal
   `pointerInput` is keyed on `isAgendaEnabled`, so a mid-swipe change tears down
   `awaitEachGesture` and drops the gesture — filed as a theoretical bug precisely
   because nothing changes that value mid-gesture today (Settings is a separate
   screen; widget add/remove requires a release first). An entitlement that
   hydrates **after first frame** is exactly the async trigger that entry says is
   currently unreachable, so folding ownership into `isAgendaEnabled` would create
   it. And the same entry's caveat says the obvious defensive fix is worse on its
   own: surviving the recomposition leaves `claimGestureStartPage` anchored in the
   old modulo space, so the release can land on the wrong page without also
   re-anchoring via `LauncherScreen.reanchoredCarouselPage`.

   Two ways out, neither free: keep the **page always present** and render a
   locked placeholder inside it, so membership never changes; or apply the
   entitlement change **only at an idle boundary**, never mid-gesture. The first
   is simpler and is what a paid page should probably look like anyway — the
   second reopens the re-anchoring work that PR #298 backed out of. Either way
   this is a real cost on candidate (2) that (1) does not carry, since search
   sections change no page set.

**Unbuilt, and worth building for the tier:**

3. **Third-party icon pack support.** The canonical launcher unlock, and the one
   users of this category already expect to pay for. Real work: parsing pack APKs,
   resolving per-component drawables, a picker, fallbacks. **Its cold-start cost is
   the design risk, and the existing icon-override index is not the pattern to
   copy** — `LauncherViewModel` calls `applyIconOverrides()` synchronously in the
   cached-metadata prefill, and `IconOverrideStore.index()` does a
   `directory.listFiles()` plus a per-file stat on first lookup, so that path
   already does disk I/O before the first frame. A pack mapping is potentially
   much larger, so it needs an explicitly asynchronous or lazy load that cannot
   block the first frame — and the jank criterion is a hard gate, so this is a
   requirement on the feature, not a note about it.
   *(Worth flagging separately from this page: that synchronous
   `listFiles()` + stat on the prefill path looks like a real cold-start cost in
   the app as it stands today. Not fixed here — this is a docs-only change — but
   it deserves its own look.)*
4. **Gestures and custom actions.** Swipe up / down / double-tap bound to an app,
   a shortcut, or a launcher action. Cheap to build, immediately felt, and what
   the power users who install a third-party launcher come looking for.
5. **Typography.** On-brand in a way nothing else here is — the app is called
   *Type* Launcher, its whole surface is text. No permissions, no network, no
   per-frame cost beyond a font load at startup.

**The shape this suggests:** gate (1) and (2) now — a real tier with **no new
feature to build**, while the misclassifiable population is at its smallest — and
add (3)–(5) over time as the reasons to keep paying. That is a better sequence
than building for months before there is anything to sell. **"No feature to
build" is not "zero cost"** (Codex, 2026-09-03): the tier still needs the Billing
integration, the purchase and paywall UI, the no-backup entitlement cache and its
three-state hydration, and tests — all of it specified further down this page, so
an estimate that skipped it would be underbudgeting against this document's own
requirements. What (1) and (2) save is the *feature* work, which is the part that
takes months.

**One thing stays off the list:** *"more widget pages"*. Not because it is shipped,
but because there is no page cap to lift — `MainActivity` passes
`addToNewPageAfterSelection` whenever the current page is scrollable and nothing
bounds the count, so "more pages" is not a thing anyone would notice being
gated. It would be an arbitrary limit invented to have something to sell, which is
a different and worse move than gating a real feature.

**Deliberately not on the list:** work-profile calendar in the agenda. `TODO.md`
"Not planned" establishes it needs an MDM admin allowlist that neither the user
nor the app can grant, so it would light up for approximately nobody. Selling it
would be selling something that does not work.

---

## Price

**One-time, ~$5, as a "Prime"-style unlock.** The instinct behind this is right
and the precedent is strong: Nova Launcher ran on exactly this shape for a
decade, and launcher users understand the model without being taught it.

The reasoning, not just the precedent:

- **A launcher is configured once and then used forever.** That profile churns
  hard on a subscription — month two arrives, the user has not opened a settings
  screen since setup, and the charge reads as rent on a home screen.
- **There is no recurring developer cost to fund.** No API bill, no server, no
  per-user marginal cost of any kind. A subscription would be charging for time
  rather than for anything that costs time.
- **$5 is the anchor the category set.** Going higher needs a reason the buyer can
  see; going lower leaves money on the table without buying more installs, because
  at this price the decision is "do I want this" and not "can I afford it".

Play's cut on a one-time in-app purchase is 15% under the first $1M/year — **but
that rate is a program the developer account has to be enrolled in**, not an
automatic default, so ~$4.25 net per unlock assumes enrollment is done. Confirm it
in Play Console before budgeting on it; unenrolled, the standard 30% applies and the
same $5 nets ~$3.50. Enrolling is a prerequisite of the price, not a detail after
it. **The unlock must
be an in-app purchase, not a paid app** — a paid listing loses the free install
that gets someone to try a launcher at all, and a launcher has to be lived with
for a day before anyone knows if they want it.

**A trial is worth considering and probably is not needed**: installing the
launcher *is* the trial, since every free feature is the core product. If one
ships, make it a time window on the unlock features, not a countdown on the
launcher itself.

**And a time window resets on reinstall, which is a cost the option carries**
(Codex, 2026-09-03). With no account and no backend there is nowhere to put the
start timestamp but app-private storage, which a reinstall or a new device
clears — the same identity limitation the grandfathering section documents, in
the other direction. So a trial is either *resettable by anyone who notices*,
which for a launcher's small paid tier may be an acceptable leak and is the
cheap answer, or it needs the durable buyer identity that section prices, which
is a much larger decision than the trial. Ship it knowing which of those was
chosen; don't recommend the window as if enforcing it were free.

---

## Migrating existing free users

**Don't build a mechanism for this** (maintainer, 2026-09-03). An earlier draft of
this section specified a legacy stamp, enumerated three ways it gets lost, and
prescribed a self-service re-grant row — a policy for a population of **about two
people.** That is painting into a corner: it commits the app to machinery, and
commits this page to promises, on a problem that today is two conversations.

**Decide it case by case, on the evidence.** Someone who says they had a feature
before the cutoff either plausibly did or plausibly didn't, and at this scale a
person can tell — from what they have configured, when they installed, what they
say. Handle it, move on, and don't write a rule that a later user count would make
you regret.

What is worth keeping is the *fact* that limits any mechanism, so nobody rebuilds
one on a false premise. An earlier version of this paragraph said **"no older
build ever wrote a marker"**, which is wrong (Codex, 2026-09-03): `SPEC.md`
records `contact_search_enabled` and `calendar_search_enabled` in the
`dock_settings` store, and the launcher opts into Auto Backup, so a pre-cutoff
user who *turned these on* has affirmative persisted evidence of exactly the
features this tier would gate — a fourth option alongside `firstInstallTime`, a
new stamp, and nothing.

**"Exactly the features" means contacts and calendar *search*, and no more**
(Codex, 2026-09-03). `agenda_enabled` is a separate setting that **defaults to
`true`**, so it is evidence of nothing: a user who never touched it is
indistinguishable from one who chose it. If Agenda ever joins the paid tier, this
reading does not cover it and something else has to — the general rule being that
a default-on setting cannot serve as evidence of use.

Three things bound it, and they are why it is an option rather than the answer.
**It sees only the users who enabled the feature** — a `false` flag is
indistinguishable from a fresh install, and it carries no date, so it identifies
the population that would lose something rather than a cutoff. And **the flag is
overwritten before anything can read it**:
`coerceContentSearchSettingsToPermissions()` runs in `LauncherViewModel`'s `init`
and turns either flag off when its runtime permission is missing — which is
exactly the state a restore onto a new device arrives in. So a migration reading
it has to snapshot before that coercion, in the same constructor, ahead of the
call. **And the grant has to be durably committed before the coercion clears the
flags** (Codex, 2026-09-03) — otherwise a process death between the snapshot and
the deferred entitlement hydration leaves neither the flags nor the grant, which
is the permanent revocation this mechanism exists to prevent. Ordering, not a
synchronous write on the launch path: coerce only after the migration's write has
landed. **And the grant is stored separately from the Billing cache and unioned
into every entitlement transition** (Codex, 2026-09-03): a grandfathered user has
no Play purchase, so a successful *empty* `queryPurchasesAsync` would otherwise
erase the grant — and treating an empty result as inconclusive instead would cost
the client-side refund detection the cache exists for. Alternatively, grant it as
a real Play entitlement (the promo code below), which sidesteps the union. That
is a real constraint on the implementation, not a reason to skip the option.

**Those two requirements compose into a third** (Codex, 2026-09-03): the
migration must be provably one-time and pre-cutoff, or a *refunded* buyer keeps
the feature forever. A post-cutoff purchaser enables content search, so the flags
are `true`; the entitlement cache is excluded from backup but the flags are not,
so after a refund and a reinstall Auto Backup restores them, the migration reads
them as legacy evidence, and the union then makes the empty `queryPurchasesAsync`
unable to revoke anything. So the migration needs a backed-up version marker
recording a build from before any post-cutoff user could have enabled the
feature, and it runs once against that marker — never against whatever the flags
happen to say.

That marker adds an ordering boundary of its own, and it runs the opposite way to
the one above (Codex, 2026-09-03): **the grant is persisted first and the marker
only after that write lands**, or both commit atomically. Marker first means a
process death in between leaves an install whose next launch sees the marker,
skips the migration, and then coerces the still-present flags off with no grant
ever written — the same permanent revocation, reached by the other door.

**And the grant shares the marker's backup fate** (Codex, 2026-09-03). The
Billing cache is excluded from backup; the grant is stored separately from it, so
its own policy has to be stated rather than inherited. Put the grant in no-backup
storage while the marker is backed up and a device restore returns the marker
alone: the migration is skipped as already run, `queryPurchasesAsync` is legitimately
empty, and the user is locked out permanently with no path back. So the grant is
backed up alongside the marker.

**"Re-run the migration" is not the alternative it looked like** (Codex,
2026-09-03), which is why only one option survives. *Marker present, grant
absent* is not a state that identifies a grandfathered user: a pre-cutoff user
whose flags were coerced off after a successful grant and a post-cutoff user who
must carry a marker precisely so later flags are not misread as legacy evidence
reach the same marker-plus-`false`-flags state, and re-running against it either
grants the second user the feature or fails to restore the first. So the record
has to say what happened, not merely that it happened: **the marker stores the
migration's outcome — granted, or ran and granted nothing — atomically with
itself, in one backed-up record**, and the grant is restored from that rather
than re-derived from the flags.

The rest of the original fact stands: `firstInstallTime` resets on reinstall, a
local stamp is lost with it, and Auto Backup is periodic rather than synchronous
so even a stamped install can restore without it. A claim durable against all of
that would need an identity recoverable outside app-private data — an account, or
a Play purchase a free user by definition has none of. That is a much bigger
decision than the tier, and at two users it buys nothing.

**Case-by-case is reactive, and that is the cost of choosing it** (Codex,
2026-09-03). Nobody is grandfathered until they notice and get in touch, so the
first thing a pre-cutoff user experiences is the feature gone. At about two users
that is two conversations; the point is to choose it knowing that, not to read
"case by case" as "nobody loses anything".

The cheap middle option, stated because it is a real one rather than because it
should be taken: **a single `firstInstallTime < cutoff` check**, no storage and
no policy. What it buys is the live, never-reinstalled install — probably both
current users. What it cannot do is the rest: it resets on reinstall, and it
says nothing about a device the user moved to. So it converts some of the
reactive cases into silent ones and leaves the rest exactly as they were, for a
few lines of code. The alternatives are the **enabled-flag** reading described
below (which sees a different population — whoever turned the features on,
whenever they installed), the veto'd machinery (a stamp plus a re-grant path,
which the facts below make weak anyway), and nothing at all. The first two are
not exclusive: `firstInstallTime` and the flags answer different questions and
could be read in the same pass.

**It reads through the entitlement's hydration, not at the gate** (Codex,
2026-09-03). `firstInstallTime` comes from `PackageManager.getPackageInfo`, so
"check it at the gate" would put `PackageManager` IPC on the typing path the
first time a gated section evaluates — the thing the hydration design below
exists to prevent, and a hard criterion in `AGENTS.md`. Read it once off the
main thread in the same after-first-frame pass that hydrates the entitlement,
fold the result into that in-memory three-state value, and the gated sections
still read a plain memory value. It costs one more field, not a second
mechanism.

Two things follow whichever is chosen, and they are cheap:

- **Gate on the earliest release that can.** The misclassifiable population only
  grows, so waiting makes this worse and buys nothing. This is the argument for
  deciding sooner rather than the argument for building anything.
- **Say it in the app, not only in the release notes.** Play's "What's new" is a
  store-listing field an auto-updating user never sees at update time — this
  repo's own conventions say exactly that, which is why `docs:` commits are kept
  out of it, so it cannot be the only channel. A paywall notice needs an in-app
  surface: a one-time card naming what became paid, what stayed free, and how to
  get in touch if the app got it wrong. The release note is the copy of it for
  anyone who does go and read the listing.

  **What that card may promise depends on which option above is taken, and no
  option earns the flat promise** (Codex, 2026-09-03, twice). "Existing users keep
  everything they had" is false under case-by-case for exactly the people reading
  it — the feature is already gone and comes back only after they get in touch —
  **and it is still false under the `firstInstallTime` gate**, which by this
  page's own account covers the live, never-reinstalled install and nothing else:
  anyone who reinstalled or moved device before the paywall build arrives has a
  post-cutoff timestamp and is locked out exactly like a new user.

  The two mechanisms cover different populations, which is worth stating because
  it is the one place they are not interchangeable:
  - `firstInstallTime` — live installs that were never reinstalled. Lost on
    reinstall and on a new device.
  - The **enabled-flag** reading — anyone who turned contacts or calendar search
    on, including through a restore, since the flag is in `SharedPreferences` and
    the launcher opts into Auto Backup. Content search only: it says nothing
    about Agenda, whose setting is default-on. That is precisely why it must be
    snapshotted ahead of `coerceContentSearchSettingsToPermissions()`, which
    clears it when the permission is missing — the state a restore arrives in.
    Auto Backup is periodic rather than synchronous, so it is better coverage,
    not complete coverage.

  So the copy follows the mechanism and stays inside it: **"if you were already
  using these, they stay on — and if the app got that wrong, get in touch"** works
  under either *gate*, because it promises the intent and names the fallback
  instead of asserting a guarantee no mechanism here delivers. **It does not work
  under case-by-case** (Codex, 2026-09-03): there the feature is already gone
  when the card is read, so "stay on" is false at the moment it matters, and
  naming a fallback does not repair the first clause. That branch needs its own
  plainer line — something like *"content search and the Agenda page are now part
  of the paid unlock; if you were using either, get in touch and we'll sort it
  out"* — which is the real cost of that option rather than a reason to write a
  nicer card. **The line has to name whatever the bundle actually gates** (Codex,
  2026-09-03): Agenda is independently default-on, so a card naming only content
  search lets a pre-cutoff user lose the page without learning that it joined the
  unlock or that the remedy covers it. If the bundle changes, this copy changes
  with it.

  **A promise to grant it back needs a way to grant it back** (Codex,
  2026-09-03). The section above rejects a local stamp and its re-grant path, and
  establishes there is no durable identity for a free user — so "ask and get them
  back" as written leaves support with nothing to actually do. The mechanism that
  fits without building any of the rejected machinery is a **Play promo code**
  for the unlock product, generated in the Play Console and sent to whoever gets
  in touch: it is durable because redeeming it makes the user a purchaser like
  any other, so it survives a reinstall and a new device by the same route every
  paying user does, and it needs no identity of our own. Its costs are that codes
  are minted in finite batches and by hand, and that it makes the grandfathered
  user indistinguishable from a buyer in the Play reporting. Neither matters at
  about two users, which is the point — but the card should not promise the grant
  until whichever mechanism is chosen actually exists.

---

## Marketing

### The listing is doing better than the siblings, and can still improve

"Type Launcher" already carries a category keyword, which is more than the other
apps in this family manage. The Play title field allows 30 characters and is
weighted for search; `Type Launcher — app search` (26) spends the remainder on the
terms people actually type: *launcher*, *minimal launcher*, *app search*, *home
screen*. Count the em dash and both spaces before proposing one — the first draft
of this line suggested a 31-character title under a 30-character limit.

Screenshots are the conversion lever in this category, and this repo has no
pipeline for them. `scripts/render_play_store_icon.py` renders the store *icon*
and the Roborazzi snapshots cover the UI, but nothing produces or updates listing
screenshots — so treat the snapshots as source material and count the asset work
as real, rather than assuming it is already automated. (The sibling simmo repo
does render its store graphics through a Roborazzi test; that is simmo's
arrangement, not this one's.)

### Where the audience is

Launcher communities are unusually well-defined and unusually willing to pay:
the launcher and Android-customization subreddits, XDA, minimalist-phone and
digital-wellbeing communities (a search-first launcher with no icon grid is a
natural fit for the "reduce phone use" audience, and that framing reaches people
who would never read a launcher thread), and Pixel communities.

The honest pitch is the one the app already lives by: **it is a home screen you
type at, not one you decorate.** That is a real position, it is defensible, and
it is why the wallpaper direction above is worth resisting — the differentiator
is the absence.

### What happens when Play Billing can't answer

**Undefined in an earlier draft, and it matters more here than in the siblings**
(Codex, 2026-09-03), because this tier gates features that already *work*: default
to locked and a legitimate purchaser has their contacts search taken away on an
offline launch. And the naive fix is worse — querying Billing synchronously at
launch puts IPC on the cold-start path, which `AGENTS.md` makes a hard criterion.

So the policy, matching what the siblings settled on:

- **Cache the entitlement locally and read the cache**, never Billing, on any path
  the user is waiting on. Refresh asynchronously, well after first frame.
- **The buying path needs its own failure states, and they are not the
  entitlement's** (Codex, 2026-09-03). Everything here describes what a user who
  may already own it sees; a prospective buyer opening the paywall with Play
  disabled, offline, or unable to return `ProductDetails` is a different path.
  Load the offer asynchronously and show the localized price from
  `ProductDetails` — never a hard-coded approximation, which is wrong in most
  currencies — with explicit states for *unavailable*, *retry*, *canceled* and
  *checkout failed*. **`ITEM_ALREADY_OWNED` is none of those, and reading it as
  "checkout failed" locks out someone who has already paid** (Codex,
  2026-09-03). It is a normal response, not an error: a buyer with a cold or
  stale cache — bought on another device, or reinstalled — can reach checkout
  before the connection/resume ownership query has answered, and
  `launchBillingFlow` then returns it instead of invoking a purchase callback,
  so nothing in the success path ever runs. Treated as a failure it shows a
  retry that will return the same code forever while Play is telling the app the
  purchase exists. It has to trigger a `queryPurchasesAsync` reconciliation
  instead, with checkout unavailable until that finishes and the entitlement
  taken from its result. A paywall that hangs, or silently disables its own buy
  button, loses the sale without saying why. Until these are specified the
  Billing integration is not end to end, whatever the entitlement side says.
- **Grant on `PURCHASED`, never on `PENDING`, and acknowledge within three days**
  (Codex, 2026-09-03). Play's purchase flow can return a `PENDING` state — a
  deferred payment method the user has not completed — and treating that as owned
  hands out the unlock for a sale that may never settle. The mirror failure is
  worse and easy to miss: **Play auto-refunds and revokes any purchase left
  unacknowledged for three days**, so a buyer whose acknowledgment silently failed
  loses what they paid for with no signal. So the lifecycle is part of the design,
  not an implementation detail: validate the purchase before writing the cache,
  acknowledge on `PURCHASED` with a retry that survives process death, and give
  the user something to look at while a `PENDING` purchase is outstanding rather
  than a screen that reads as "not owned". The sibling ClothesCast plan already
  carries the acknowledgment half (`docs/ROADMAP.md`, `linkPurchase`), which is
  where to copy the shape from.

  **A retry that survives process death is not the same as one that finishes**
  (Codex, 2026-09-03), and an earlier draft stopped at the retry. If Play stays
  unavailable, retrying forever runs out the same three days and the buyer loses
  the unlock with exactly the silence this bullet exists to prevent. So the retry
  is **bounded by the deadline, not by attempts**: it records **when the purchase
  became `PURCHASED`** and retries until acknowledged or the window closes.

  **But the estimated deadline must never be what stops it** (Codex,
  2026-09-03), which is the trap the conservative origin below sets: if
  `WorkManager` delayed a poll past three days and the payment settled shortly
  before that poll, the *estimate* reads as already expired while most of the
  real window remains — and a single transient failure would then get no second
  attempt. So the estimate is used **only to decide when to warn the user**, and
  the retry's actual stop condition is Play's own state: **keep retrying while
  Play still reports the purchase `PURCHASED` and unacknowledged**, and stop when
  the acknowledgment succeeds or Play no longer returns it. Play is the authority
  on whether there is still something to acknowledge; a client-side estimate is
  not, and must never be allowed to give up on the buyer's behalf. That
  timestamp is the transition, **not when the purchase was initiated** (Codex,
  2026-09-03) — Play's three days start when a deferred payment completes, so a
  `PENDING` purchase that settles a week later would look, against its initiation
  time, like a window that had already closed: the worker would give up after one
  failure on a purchase with three full days left, and Play would refund it.

  **But the client often cannot observe that transition when it happens, and the
  observation time is not a safe substitute** (Codex, 2026-09-03). A deferred
  payment that completes while the process is dead is seen only when the pending
  monitor below next runs, so treating *when we noticed* as the clock origin
  starts a three-day budget that Play started earlier — and the further behind
  the observation is, the further past the real deadline the retries and warnings
  run, with an auto-refund arriving first. Two things follow, and they are the
  reason the monitor's interval is not a free parameter:
  - **The origin is the previous check, not this one.** The transition happened
    somewhere in that interval and the client cannot narrow it, so the only safe
    reading is the earliest point it could have. That keeps the estimate
    conservative — it never over-states the budget remaining.
  - **But the period is not a latency bound, and calling it one was wrong**
    (Codex, 2026-09-03). A `WorkManager` interval is a *minimum*, not a maximum:
    Doze, unmet constraints and OEM schedulers can postpone a run well past it,
    and past three days if the device sits idle long enough. So the previous
    check gives a conservative origin but **no finite worst case**, and nothing
    client-side does. Stated plainly rather than papered over: **a client-only
    design cannot guarantee that a deferred payment completing while the process
    is dead is acknowledged inside Play's window.** The honest mitigations are to
    shorten the period (buying probability, not a guarantee, at a battery cost)
    and to accept the residual — or to take the backend, which is the only thing
    that removes it. In scope this is the minority path: it is deferred payments
    only, not the ordinary card purchase, where the listener fires while the
    process is alive.
  - **Prefer an authoritative timestamp if the purchase record carries one**,
    which would remove the guess entirely. `Purchase` exposes a purchase time,
    but whether it is rewritten when a deferred payment settles or stays at
    initiation decides whether it is usable here, and **this sandbox cannot reach
    Play's Billing documentation to check** (`developer.android.com/google/play/billing/…`
    404s from here). Confirm it before relying on it; until then the conservative
    origin above is what the design assumes. Play's own prompt channel for this
    is a Real-time Developer Notification, which needs the backend the
    verification bullet above declines to stand up.
  **The debt is also recorded before the grant is exposed, not when a retry
  fails** (Codex, 2026-09-03). Enqueue-on-failure leaves a window with no retry in
  it at all: publish `owned`, die with the first acknowledgment call still in
  flight, and nothing was ever scheduled — so if the launcher is not foregrounded
  again the deadline passes and Play refunds a purchase the user is meanwhile
  using. So the order is **persist the acknowledgment debt, then expose the
  unlock**, and clear the debt only once Play confirms the acknowledgment.
  **Enqueuing the worker is how the debt is recorded, not a substitute for
  committing the grant first** (Codex, 2026-09-03), and an earlier draft offered
  the two as interchangeable. WorkManager may run the job immediately, so the
  acknowledgment can succeed — and the debt clear — while the durable `owned`
  write this page requires forty lines down is still in flight off the main
  thread. Die in that interval and Play considers the purchase delivered and will
  not refund it, while the next offline launch reads a cold cache and hides the
  features from someone who paid: the same silent loss, arrived at from the other
  side. So the acknowledgment is allowed to start only once the grant is
  committed — enqueue with that write as its precondition, or persist the two
  together — and the ordering is three steps, not two. **Its trigger cannot be
  app activity alone** (Codex, 2026-09-03) — an earlier draft pointed it at the
  entitlement refresh's own triggers, which are resume and Billing connection, so
  a user who switches launchers for a few days is never foregrounded again and
  the retry never runs while the deadline passes on a device that was online the
  whole time. Being the home screen makes this less likely, not impossible, and
  it is the one case where the loss is total. So the retry is **enqueued as
  deadline-aware background work** with a network constraint, persisted across
  reboot — WorkManager is the obvious vehicle and is **not currently a dependency
  of this app**, which is a real (if small) cost to name: one library, one worker,
  and its own failure modes on OEMs that are aggressive about background work.
  The refresh's existing triggers stay as an opportunistic fast path. The design
  also owes two user-visible states before that clock runs out — *acknowledgment
  outstanding* (the unlock works; nothing is wrong yet) and, once it has been at
  risk long enough to matter, an explicit **"we could not confirm this purchase
  with Google — open the app while online, and if it still fails, contact
  support"**. Escalating inside the window is the whole point: after it, the
  refund has already happened and there is nothing to tell the user that they
  will not have noticed themselves.

  **"Validate" means more than reading the state field** (Codex, 2026-09-03). A
  `PURCHASED` state on its own is a claim the client is repeating, so before it
  seeds a durable `owned` cache the purchase also has to be *this* product — the
  expected id, not whatever came back — and has to be genuine. Two ways to do the
  second, and they are a real trade rather than a detail:
  - **Locally**, by verifying Play's signature on the purchase payload against the
    app's public key. No infrastructure, and it is the pragmatic choice for a
    one-time unlock in a launcher — but the key and the check both ship inside the
    APK, so it stops a tampered response rather than a determined patcher.
  - **Server-side**, through the Play Developer API. Actually authoritative, and
    it is a backend this app does not have and would otherwise never need, plus a
    service account to hold and a network dependency on a path that currently has
    none.

  For a launcher whose paid tier is contact and calendar search, local verification
  of the right product id is the proportionate answer and the server is not worth
  standing up — but that is a judgment about this product's stakes, so it is stated
  rather than assumed. ClothesCast is the opposite case and already has the
  backend, which is another reason it ships billing first.
- **Keep that cache out of backup, or treat a restored value as untrusted**
  (Codex, 2026-09-03). This app opts into default Auto Backup with *no custom
  rules*, so `SPEC.md` "Backup and restore" says its `SharedPreferences` stores are
  backed up and restored — which breaks the rule above in both directions if the
  entitlement lives in one. A backup taken **before** purchase restores `false`,
  and "last known value" then reads that stale `false` as authoritative and tells a
  legitimate purchaser they don't own it — the exact failure the cold-cache state
  below exists to avoid, arriving through the mechanism that was supposed to help.
  A stale `true` is the mirror image: a refunded entitlement comes back. So the
  entitlement goes in `no-backup` storage (or is excluded via backup rules), which
  makes a reinstall a genuine cold cache and therefore land in the pending-check
  state rather than a confidently wrong one.
- **"Cannot determine" means the last known value** — offline, Play disabled, a
  transient error, all the same answer. Never a downgrade. For a one-time unlock
  ownership is permanent, so the only downgrade is a **positively confirmed refund
  or revocation**; there is nothing to expire and no grace window to invent. **And
  every confirmed verdict is persisted before it is published** (Codex,
  2026-09-03), off the main thread — **a grant as much as a downgrade** (Codex,
  2026-09-03), which an earlier draft required only of the downgrade: publish
  `owned` in memory, die before the write lands, and the next offline launch has a
  cold cache and shows the paid features as *unavailable pending a check* to
  someone who just bought them. Serializing the writes stops a stale one landing
  last; it does nothing about a write that never happened. In the downgrade
  direction: publish `not owned` in memory first and the process can die
  before the write lands, so the next offline launch reloads the stale `owned`
  value and restores an entitlement that was positively revoked. **A dirty marker written before
  the refresh is not an equivalent** (Codex, 2026-09-03) and an earlier draft
  offered it as one: a process death, or a Play that never answers, leaves the
  marker set with no verdict behind it, and the next launch cannot tell that from
  a confirmed revocation — trust the cached `owned` and the marker bought nothing,
  distrust it and a purchaser is downgraded on an indeterminate refresh, which is
  the rule directly above. Persist the confirmed revocation, then publish it;
  that ordering is the whole mechanism. **It costs a window in which the
  published entitlement is still `owned`**, which is why the revocation-cleanup
  rule below gates render and dispatch through a separate transient latch rather
  than by publishing early — the two rules meet there, and resolving the seam in
  the other direction would undo this one.
- **The cold cache is its own state**, not a downgrade: a purchaser who reinstalls
  while Billing is unreachable has nothing cached. Show the gated features as
  *unavailable pending a check* rather than absent or locked, and never as "you
  don't own this" — that is a refund. Granting them instead is the opposite error,
  since it would make every fresh install paid until Billing answered.
- **Hydrate the cache into memory once, and let only the gated sections read that
  in-memory value.** An earlier version of this bullet said "search never consults
  the entitlement", which contradicts the tier itself — the whole proposal gates
  *contacts and calendar search*, and `LauncherViewModel.setQuery` rebuilds those
  sections through `contactResultsFor` / `eventResultsFor` on **every keystroke**
  (Codex, 2026-09-03). Read literally it left the paid results ungated; reading
  the no-backup store from that path instead would put disk I/O on the typing
  path, which is worse. So:
  - **Read the store once**, asynchronously, off the typing path — at startup
    after first frame, and again on each refresh. **"Refresh" has to name its
    triggers, and an earlier version of this bullet left them undefined** (Codex,
    2026-09-03): a launcher process outlives almost everything, so a purchase
    that completes out-of-band — the `PENDING` case above, paid at a shop counter
    hours later — is never observed if nothing goes looking. Then it is never
    acknowledged, and the three-day auto-refund below collects it. The triggers
    are the `PurchasesUpdatedListener` for a completion that arrives while the
    process is alive, plus a `queryPurchasesAsync` on Billing connection or
    reconnection and on resume, which is what catches one that completed while
    the process was dead. **Those three are still all app activity, and a known
    `PENDING` purchase needs one that isn't** (Codex, 2026-09-03). The
    acknowledgment worker further down does not cover this: it exists only once
    `PURCHASED` has been *observed*, and the gap here is the observation itself.
    Switch launchers for three days with a cash payment outstanding and nothing
    runs — no listener, no connection, no resume — so the transition is never
    seen, no debt is ever recorded, and Play refunds a completed payment the user
    made. So **a `PENDING` purchase is itself persisted, and schedules its own
    deadline-aware background check** on the same vehicle as the acknowledgment
    retry. **Creating that monitor has an ordering requirement of its own, not
    just handing it off** (Codex, 2026-09-03): if the process dies between
    persisting the pending record and enqueueing the check — or after showing the
    pending UI but before either lands — nothing exists to observe the
    completion, and the launcher may not be foregrounded again before the window
    passes. The later debt-before-clear rule only protects a monitor that already
    exists. So **the durable enqueue is the record**: `WorkManager` persists a
    `WorkRequest` to disk, so a self-contained request carrying the purchase
    token *is* the pending record and needs no separate write to race with —
    and, either way, durable scheduling completes **before** the pending state is
    exposed to the user, the same order as debt-before-grant further up.
    **A foreground run of it takes the same idle boundary as everything
    else that touches Billing** (Codex, 2026-09-03): a scheduled worker has no
    coordination with the transition-idle signal, so one that fires while the
    launcher is visible mid-swipe would open Play/Binder IPC on the animating
    frame — routing around the boundary the queries above are held to, for the
    same IPC. So it runs directly while the launcher is **backgrounded**, where
    there is no frame to protect, and defers to the transition-idle boundary when
    it is foreground. **Clearing that record is a handoff, not a completion** (Codex,
    2026-09-03): "cleared when the purchase resolves either way" let the pending
    monitor go the moment `PURCHASED` was seen, while the acknowledgment debt is
    created separately — and a process death in between leaves nothing scheduled
    at all, so an unforegrounded launcher runs out the three days and Play
    refunds it. That is the debt-before-grant ordering from further up, applied
    at the seam between the two mechanisms rather than inside either: **persist
    the acknowledgment debt first, then clear the pending record** — atomically
    if the store allows it, and in that order if it does not. A resolution the
    other way (canceled, expired) has no successor to hand to, so it clears on
    its own. Two costs to state:
    it is background work for a purchase that may simply never complete, so it
    needs its own stop condition rather than polling forever, and it only covers
    purchases this app saw go `PENDING` — Play's own authoritative channel for a
    completion nobody was watching is a Real-time Developer Notification, which
    needs the backend the verification bullet above already declines to stand up
    for this product. **No resume triggers it synchronously — every one is
    queued past that resume's own first frame** (Codex, 2026-09-03). `onResume`
    fires before the frame it precedes, and not only at cold start: a warm return
    from another app resumes the launcher the same way, so "subsequent resumes"
    would still open Billing IPC ahead of rendering the Home surface, exactly
    where this design promises nothing happens. The cold-start resume is covered
    by the after-first-frame hydration above; every later one takes the same
    treatment. **And the boundary is idle, not one frame** (Codex, 2026-09-03):
    Home resumes into a multi-frame system transition, so waiting a single frame
    still starts Play/Binder work while the animation is running and the user's
    first keystroke or swipe is landing. Coalesce repeats so a burst of resumes
    does not queue a burst of queries — and note that **message-queue idleness is
    not the boundary either** (Codex, 2026-09-03): a main-queue idle handler can
    run between vsync frames with the transition still animating. What is wanted
    is an explicit transition- or carousel-idle signal, or a defined input-quiet
    window.
  - **Three triggers with no ordering rule is a revocation bug** (Codex,
    2026-09-03), and coalescing resume bursts does not reach it — it orders one
    trigger against itself, not the three against each other. A
    `queryPurchasesAsync` started before checkout can capture an empty purchase
    set and return *after* `PurchasesUpdatedListener` has granted the purchase;
    since this design reads a successful empty query as a confirmed revocation,
    that stale result takes the entitlement straight back off a buyer who just
    paid. So the refresh is **single-flight, and every result carries the
    generation it was issued under** — a result older than the current generation
    is dropped rather than applied, and a purchase event bumps the generation.
    The same guard already exists a few paragraphs down for source loaders; this
    is it applied one level up, to the entitlement itself. **And the generation
    has to reach the durable write, not just the acceptance test** (Codex,
    2026-09-03): the cache write is off the main thread, so an empty result that
    was current when it passed the check can begin persisting `not owned`, a
    purchase callback can then bump the generation and persist `owned`, and the
    older write can land last — leaving the next offline start loading a
    revocation that was superseded before it hit disk. So the transitions and
    their writes go through **one serializing owner** — a single actor or
    mutex-guarded path — or the write itself is generation-aware, refusing to
    commit under a generation older than the stored one. Either way the check and
    the commit are one step, not two.
  - **The typing path reads a plain in-memory value**, which must be a
    three-state one (`owned` / `not owned` / `unknown`) rather than a boolean, so
    the cold-cache case above can render "checking" instead of collapsing into
    "not owned". **That third state is *unknown*, and it is not Play's `PENDING`**
    (Codex, 2026-09-03) — an earlier draft called it `pending` and thereby merged
    two states that need opposite handling. *Unknown* means "we have not been able
    to ask yet": show "checking", and asking again is the whole remedy. A Play
    `PENDING` purchase means the user has committed and payment is outstanding
    (the cash-at-a-counter case above): it says so, must **not** offer checkout
    again, and resolves only when Play reports the completion. So purchase status
    is its own field alongside the entitlement, not a fourth value inside it.
  - **An entitlement transition has to recompute what is already on screen**
    (Codex, 2026-09-03). `LauncherUiState` *materializes* `contactResults` and
    `eventResults`, and `launchTopResult` acts on those stored entries — they are
    recomputed by `setQuery` and the index-refresh paths, by nothing else. So
    flipping the in-memory value alone leaves a displayed non-blank query showing
    the old behavior until the next keystroke: revoked results stay listed *and
    launchable* after a refund, and a completed purchase appears not to have
    worked. Every transition therefore clears or recomputes the current query's
    results and the contact-action mode, or the gate is applied at render and
    dispatch rather than at the point the results were built. Either is fine;
    doing neither is the bug. **A revocation has to reach the sources too**
    (Codex, 2026-09-03): gating the display leaves `contactIndex`,
    `searchEventIndex` and the photo loader populated, and
    `refreshContentSearchIndices()` consults only the two switches — so every
    later resume keeps querying both content providers for a feature the user no
    longer has. Losing the entitlement therefore cancels in-flight loads, drops
    the retained content, and gates the refresh path, not just the results.
    **That cleanup takes an idle boundary as well, and only the gating is
    immediate** (Codex, 2026-09-03): an ownership query started at an idle
    boundary can still have its refund verdict arrive mid-swipe, and canceling
    loaders, dropping two indices and the retained events, evicting the photo
    cache and recomputing the visible results is exactly the work this design
    keeps off an animating frame. So split it by what correctness needs *now* —
    **render and dispatch are gated immediately**, since a revoked result must
    not stay listed or launchable for even one more frame — and defer everything
    heavier to the next transition-idle signal under the same generation guards.

    **That immediate gating is a third state, not an early publish** (Codex,
    2026-09-03), and saying "gated immediately" without one contradicted the
    persist-before-publish rule above: that rule deliberately keeps the published
    entitlement at `owned` until the durable write commits, so either the revoked
    results stay launchable for the length of that write or the entitlement is
    published early and a process death restores the stale grant — the exact
    failure persist-before-publish exists to prevent. What resolves it is a
    **transient in-memory `revoking` latch**, set the moment a refund verdict is
    confirmed and observed by render and dispatch alone. The durable entitlement
    is untouched until its write commits and is published only after; the latch
    carries no authority of its own and does not survive process death.

    **And "a restart lands in the cold-cache state" was wrong** (Codex,
    2026-09-03). Die after the latch is set but before the `not owned` write
    commits, and the next launch reads a *warm, stale* `owned` cache — not a cold
    one — with the latch gone, so refunded features are launchable again until
    Billing next answers. **That window is real and it has to be stated rather
    than argued away.** Two honest ways to hold it:

    - **Accept and bound it.** It lasts one small off-main write, it closes at
      the next Billing answer, and it fails in the *safe* direction under this
      page's own asymmetry — a refunded user keeps access briefly, rather than a
      paying user losing it. No pair of a memory write and a disk write is
      atomic, so some window of this shape is unavoidable once the durable write
      is off the main thread, which the launch-path rules require.
    - **Or close it, by making the revocation the first durable write.** Persist
      a restart-visible *confirmed-revocation* record before the latch is
      published, so a crash leaves the verdict on disk rather than the stale
      grant. This is **not** the dirty marker rejected further up: that one was
      written speculatively *before* a refresh, with no verdict behind it, and
      the next launch could not tell it from a real revocation. Here the verdict
      already exists and is what gets written.

    Which to take is a real choice and this page does not make it; what it will
    not do is claim the window isn't there.
    Writing the boundary for the gain side alone left the loss side on the hot
    path, **and *gaining* it restarts every enabled source loader** (Codex,
    2026-09-03), under the same entitlement and generation guards, since
    recomputing results from sources this policy just emptied would unlock the
    features blank until some unrelated resume or settings toggle refilled them.
    **That restart takes the same idle boundary as the Billing queries above**
    (Codex, 2026-09-03), and this is easy to miss because the boundary was
    written for the *queries*: the grant usually arrives on
    `PurchasesUpdatedListener` as the Play checkout sheet is closing, so an
    immediate restart fires contacts and calendar provider IPC — and then
    publishes a large result set — into the middle of that transition. Queue both
    the restart and its publication behind the transition- or carousel-idle
    signal, so the blank-after-purchase fix does not buy itself a frame drop.
    **Agenda needs the same treatment through its own loader** (Codex,
    2026-09-03): `triggerInitialAgendaLoadIfEnabled()`, `showAgenda()` and the
    resume refresh consult `agenda_enabled` and nothing else, and an always-
    present locked placeholder keeps that flag true — so a bundle containing
    Agenda has to cancel the in-flight `agendaVersion` load, clear the retained
    events, and stop the Calendar-provider work as well.

    **But cancelation is the loss side only, and the initial query never went
    through it** (Codex, 2026-09-03). With the placeholder keeping
    `agenda_enabled` true, those three entry points can start Calendar-provider
    IPC while the entitlement is still `unknown` or already `not owned` —
    especially since Home can become ready before the asynchronous entitlement
    hydration finishes, so "cancel it later" races a query that has already
    fired. So the rule is **entry-point gating, not cleanup**: every Agenda load
    path starts provider work only for a confirmed `owned`, and the
    ownership-*gain* transition is what kicks the loader, on the same idle
    boundary as everything else above. Cancelation then handles only what a
    mid-session revocation leaves in flight, which is what it was for.
  - **App launching, the app list and the dock still consult nothing** — those
    are not gated, so the floor at the top of this page holds regardless of what
    the entitlement says or fails to say.

Latency and failure modes stated up front, per `AGENTS.md`'s cost-and-reliability
rule: **zero I/O and zero IPC on the typing and launch paths** — a memory read on
the gated sections, and everything else deferred past first frame.

---

## What this shares with the sibling apps

- **Play Billing is new to every app in this family.** Build it once, end to end,
  in one app; do not start with a shared library. Extract only after a second app
  needs it and the shape is known — the same order `androidlog` was extracted in,
  after the copies existed rather than ahead of them.
- **ClothesCast should go first.** It is the only sibling with a genuine marginal
  cost per user, its billing design is already worked out end to end in its
  roadmap, and whatever is learned there lands here cheaply.
- **`PRIVACY.md` and the Play Data Safety form** change for any purchase flow,
  even one that stores nothing but a local entitlement flag.

---

## Open questions, and what each way out costs

Nothing here needs an answer today. Each question is written as the choice plus
what each branch costs, so the reasoning is on the page whenever one comes up.

**1. Paid at all, or free forever with the launcher as the portfolio piece.**
- *Free forever*: costs nothing to choose, costs nothing to change your mind
  about later, and earns nothing. It is also the only branch with no Billing
  integration to build, no entitlement cache to get right, and no
  "can't-answer" state to design (see the section above).
- *Paid*: everything below matters, and the launcher's distribution problem —
  nobody knows it exists — is unchanged by any of it.

**2. What the tier gates.** The maintainer has ruled out treating shipped
features as ineligible (2026-09-03), so both branches are live.
- *Gate the shipped contacts/calendar search*: the strongest candidate on the
  page — it is the one thing the launcher does that a stock launcher doesn't, and
  it is already built and already good. The cost is that gating it is a
  *revocation* for anyone using it today, plus the misclassification handling
  above: search runs on the typing path, so a Billing call that can't answer must
  never block a keystroke.
- *Wait for an unbuilt feature*: no revocation, no migration question, and it
  postpones any revenue behind work that hasn't started.
- *Both, in sequence*: gate contacts and calendar search **now**, while the
  misclassifiable population is smallest, and add the unbuilt features to the same
  tier as they ship. This is what "the shape this suggests" above recommends —
  earns soonest and gives the tier a reason to keep growing, at the cost of the
  revocation in the first branch.

  **That cost collides with this page's own table, and the two decisions are
  therefore coupled** (Codex, 2026-09-03). The table above marks *Revoke* as
  **not eligible here**, so recommending a branch whose stated cost is a
  revocation is a contradiction as long as the migration question is answered
  "case by case, no mechanism" — under that answer a live pre-cutoff install
  genuinely loses contact and calendar search until they notice and get in touch,
  which is a revocation by the table's own definition. So this is not a free
  choice sitting beside the migration one:
  - **With an automatic check** — `firstInstallTime`, or the enabled-flag reading,
    both described under *Migrating existing free users* — a live install keeps
    what it had, and gating shipped search becomes a *gate for new installs* in
    fact and not just in framing. The recommendation and the table agree, at the
    cost of the few lines that check costs.

    **But the enabled flags do not cover Agenda, and the tier may include it**
    (Codex, 2026-09-03). `agenda_enabled` is independent and **defaults to
    `true`** (`SPEC.md`), so a pre-cutoff user who simply left the Agenda page on
    and never turned content search on has *no* affirmative flag — the flag
    reading treats them as a new install and takes Agenda away. That is the
    revocation this bullet is supposed to avoid, arriving through the mechanism
    chosen to avoid it. So the coverage has to match the bundle: for
    contacts/calendar search the flags are the sharper instrument, and **the
    moment Agenda joins the paid tier the check needs `firstInstallTime` or an
    Agenda-specific criterion alongside them**. A default-on setting cannot serve
    as evidence of use, which is the general form of it.
  - **Without one**, the honest description is that the page is recommending a
    revocation, and the table's "No" is what has to give. That is a maintainer's
    call, not something to resolve by quietly reading "gate" onto an act the page
    already calls a revocation.

  Nothing here adopts either. What it stops is picking *both in sequence* and
  *case by case* independently, believing each is compatible with the rule at the
  top of the page. (An earlier version of this bullet described
  gating only the unbuilt ones, which was just the second branch again — Codex,
  2026-09-03.)

**3. The price.** ~$5 one-time is the maintainer's starting figure and this page
does not argue against it.
- *$2.99*: an impulse yes, and it makes the arithmetic depend entirely on volume
  the launcher does not have yet.
- *$5*: fair for a launcher people use dozens of times a day, and unremarkable
  next to what paid launchers charge.
- *Higher*: needs the tier to contain more than search.
- Whatever the number, the fee applies: a one-time product takes 30% unless the
  account is enrolled in the reduced first-$1M tier, which is a Play Console
  action rather than a default.

**4. Whether any new background type ships** — solid colors, gradients, blur —
and whether existing wallpaper support stays free.
- *Ship them, gate them*: permitted now, and this page argues it is the weakest
  use of the gate available: wallpaper is table stakes on every launcher, so a
  paywall there reads as withholding rather than as selling something.
- *Ship them free*: costs the jank work (blur especially, against the
  first-frame criterion) and buys goodwill rather than revenue.
- *Don't ship them*: nothing to test on the first-frame path, nothing gained.

Before any of them: the listing work, which needs no decision.
