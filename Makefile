ICON_SRC := app/src/main/res/drawable/ic_launcher_foreground.xml
ICON_BG := app/src/main/res/drawable/ic_launcher_background.xml
ICON_PREVIEW := app/src/main/res/drawable/ic_launcher_foreground_preview.png
PLAY_ICON := play/icon-512.png

.PHONY: previews
previews: $(ICON_PREVIEW)

$(ICON_PREVIEW): $(ICON_SRC)
	python3 scripts/render_icon_preview.py

.PHONY: play-icon
play-icon: $(PLAY_ICON)

$(PLAY_ICON): $(ICON_SRC) $(ICON_BG)
	python3 scripts/render_play_store_icon.py
