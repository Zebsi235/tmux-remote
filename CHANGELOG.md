# Changelog

## 0.4.2

- Scrolling by finger now works in Codex. It draws full-screen, takes no mouse events and
  ignores arrow keys, so a drag is turned into PgUp/PgDn there.

## 0.4.1

- Key help text reworded.
- Housekeeping: build workflow on current action versions, sorted imports, `SECURITY.md`.

## 0.4.0

First version prepared for the public.

- Release builds are signed with their own key (`keystore.properties`, not in git).
- Licence (GPL-3.0), third-party notes, README, tests split into three files, build workflow.

## 0.3.1

- Microphone key in the key rows: dictated words are typed into the terminal. Hold it to
  switch between German and English. It took the place of the `~` key.
- "What the keys do" in the menu.

## 0.3.0

- Dictation with a recogniser that is part of the app (Vosk, German and English), instead of
  the system one that many phones lack.

## 0.2.0

- Marks on the session tabs: printing now, or new output not looked at yet.
- "Notify when quiet" per session, with a background service that keeps the connection up.
- Message box for longer texts.

## 0.1.2

- New app icon.

## 0.1.1

- TAB key moved to the bottom right corner.

## 0.1.0

- SSH login (app key, imported key, password), tmux attach, session tabs and drawer, scrolling
  by finger through tmux copy mode, key rows with a prefix key, reconnect.
