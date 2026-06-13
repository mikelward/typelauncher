# Play Store listing assets

These files are uploaded by hand in the Google Play Console. They are **not**
part of the APK/AAB and are not referenced by the app at runtime.

## `icon-512.png`

The 512x512 "App icon" for the store listing (Play Console -> Grow -> Store
presence -> Main store listing -> App icon).

Play does **not** derive the store icon from the APK's mipmaps, so it has to be
kept in sync by hand. It is generated from the same adaptive-icon sources the
launcher ships (`ic_launcher_background.xml` + `ic_launcher_foreground.xml`),
cropped to the adaptive-icon safe zone so the glyph matches its on-device size,
and flattened to **opaque RGB** — a transparent store icon shows through as a
dark block on the store's dark theme, which is the problem this asset fixes.

Regenerate after editing the icon XML:

```sh
make play-icon          # or: python3 scripts/render_play_store_icon.py
```

Then re-upload `icon-512.png` in the Play Console and submit the store-listing
update (no new app release is required — listing changes publish separately).
