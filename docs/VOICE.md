# Spoken replies

With "Speak replies" on (⋮ menu), the app reads out one short line per reply of the session on
screen, using the phone's own text-to-speech engine. The long reply stays on the screen.

How it fits together:

1. While the switch is on, the app writes the time into the tmux session option
   `@tmuxremote_voice` of the session on screen every few seconds ("the phone is listening").
2. On the host, a Claude Code `UserPromptSubmit` hook sees that mark and asks Claude to end
   its reply with a line that starts with 🔊 and is written for the ear.
3. A `Stop` hook takes that line from the finished reply and appends it to
   `~/.cache/tmuxremote/say.log` (time, tmux session id, text).
4. The app reads new lines of that file over its control channel and speaks those of the
   session on screen.

Without a fresh mark both hooks do nothing, so they can be installed for every session.

## Host setup

Copy `tools/host/tmuxremote_voice.py` to the host (needs `python3` and `tmux`) and add to
`~/.claude/settings.json`:

    "hooks": {
      "UserPromptSubmit": [{"hooks": [{"type": "command", "command": "python3 ~/.local/bin/tmuxremote_voice.py prompt"}]}],
      "Stop":             [{"hooks": [{"type": "command", "command": "python3 ~/.local/bin/tmuxremote_voice.py stop"}]}]
    }

Sessions pick the hooks up when they start.

## How Claude talks

The wording Claude gets is the `DEFAULT_PROMPT` in the script. Put your own text into
`~/.config/tmuxremote/voice_prompt.txt` to change it; it is read on every message.

## Limits

- The voice and its quality are the phone's (Settings, Text-to-speech). The language follows
  the dictation language (hold the microphone key).
- It speaks while the app is open, or in the background for a session with "Notify when quiet".
- If a reply has no 🔊 line, its first two sentences are spoken.
