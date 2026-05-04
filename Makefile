ICON_SRC := app/src/main/res/drawable/ic_launcher_foreground.xml
ICON_PREVIEW := app/src/main/res/drawable/ic_launcher_foreground_preview.png
ICON_BADGE_PREVIEW := build/icon-previews/local_badge_contact_sheet.png

.PHONY: previews
previews: $(ICON_PREVIEW) $(ICON_BADGE_PREVIEW)

$(ICON_PREVIEW): $(ICON_SRC) scripts/render_icon_preview.py
	python3 scripts/render_icon_preview.py

$(ICON_BADGE_PREVIEW): $(ICON_SRC) scripts/render_icon_preview.py
	python3 scripts/render_icon_preview.py
