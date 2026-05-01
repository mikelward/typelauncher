ICON_SRC := app/src/main/res/drawable/ic_launcher_foreground.xml
ICON_PREVIEW := app/src/main/res/drawable/ic_launcher_foreground_preview.png

.PHONY: previews
previews: $(ICON_PREVIEW)

$(ICON_PREVIEW): $(ICON_SRC)
	python3 scripts/render_icon_preview.py
