#!/usr/bin/env python3
"""Host side of Tmux Remote's spoken replies, as two Claude Code hooks.

  tmuxremote_voice.py prompt   UserPromptSubmit: when the phone is listening to this tmux
                               session, ask Claude to end its reply with a line for the ear.
  tmuxremote_voice.py stop     Stop: take that line from the reply and append it to
                               ~/.cache/tmuxremote/say.log, where the app picks it up.

The phone marks a session as "listening" by writing the current time into the tmux session
option @tmuxremote_voice every few seconds. Without a fresh mark both hooks do nothing, so they
are safe to install for every session. They never fail a turn: any error ends quietly.

The wording Claude gets is in ~/.config/tmuxremote/voice_prompt.txt when that file exists.
"""
import json
import os
import re
import subprocess
import sys
import time

MARK = "\U0001F50A"  # the speaker sign that starts the spoken line
FRESH_SECONDS = 180
LOG = os.path.expanduser("~/.cache/tmuxremote/say.log")
PROMPT_FILE = os.path.expanduser("~/.config/tmuxremote/voice_prompt.txt")
KEEP_LINES = 200

DEFAULT_PROMPT = f"""The user is listening on their phone: only the last line of your reply is read out to them, the rest stays on the screen. Their message was dictated, so expect wrong or missing words and go by the likely meaning.

Do the work as usual. Then end your reply with one line that starts with {MARK} and holds what you would say out loud to a colleague sitting next to you:
- one to three short sentences in plain words, in the language they spoke
- the outcome first, then the one thing you need from them, if anything
- no code, file paths, commands, lists, tables or symbols; say numbers only when they matter
- for small talk or a quick question, the {MARK} line is simply your answer, like in a conversation
Do not explain this line or mention that it is read out."""


def tmux(*args):
    try:
        r = subprocess.run(["tmux", *args], capture_output=True, text=True, timeout=3)
        return r.stdout.strip() if r.returncode == 0 else ""
    except Exception:
        return ""


def session_id():
    pane = os.environ.get("TMUX_PANE")
    if not pane:
        return ""
    return tmux("display-message", "-p", "-t", pane, "#{session_id}")


def listening(sid):
    stamp = tmux("show-options", "-t", sid, "-qv", "@tmuxremote_voice")
    return stamp.isdigit() and abs(time.time() - int(stamp)) < FRESH_SECONDS


def last_reply_from_transcript(path):
    """Text of the assistant's messages after the last thing the user typed."""
    texts = []
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                msg = e.get("message") or {}
                content = msg.get("content")
                if e.get("type") == "user":
                    typed = isinstance(content, str) or any(
                        isinstance(b, dict) and b.get("type") == "text" for b in (content or []))
                    if typed and not e.get("isMeta"):
                        texts = []
                elif e.get("type") == "assistant" and isinstance(content, list):
                    texts += [b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text"]
    except OSError:
        return ""
    return "\n".join(t for t in texts if t)


def for_the_ear(text):
    """Plain words only: no markdown signs, links or leftover symbols."""
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"https?://\S+", "a link", text)
    text = re.sub(r"[*_#>|]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def spoken_part(reply):
    """What follows the last speaker sign; without one, the first two sentences."""
    i = reply.rfind(MARK)
    if i >= 0:
        return for_the_ear(reply[i + len(MARK):])[:600], True
    plain = for_the_ear(re.sub(r"```.*?```", " ", reply, flags=re.S))
    parts = re.split(r"(?<=[.!?])\s+", plain)
    return " ".join(parts[:2])[:240], False


def append(sid, text):
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    line = f"{int(time.time())}\t{sid}\t{text}\n"
    try:
        with open(LOG, encoding="utf-8") as f:
            old = f.readlines()
    except OSError:
        old = []
    if len(old) > KEEP_LINES * 2:
        # Rewrite shorter. The app notices the smaller file and starts again at its end.
        with open(LOG, "w", encoding="utf-8") as f:
            f.writelines(old[-KEEP_LINES:] + [line])
    else:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(line)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        data = json.load(sys.stdin)
    except ValueError:
        data = {}
    sid = session_id()
    if not sid:
        return

    if mode == "prompt":
        if not listening(sid):
            tmux("set-option", "-t", sid, "-u", "@tmuxremote_voice_turn")
            return
        tmux("set-option", "-t", sid, "@tmuxremote_voice_turn", "1")
        try:
            with open(PROMPT_FILE, encoding="utf-8") as f:
                prompt = f.read().strip() or DEFAULT_PROMPT
        except OSError:
            prompt = DEFAULT_PROMPT
        print(json.dumps({"hookSpecificOutput": {"hookEventName": "UserPromptSubmit", "additionalContext": prompt}}))

    elif mode == "stop":
        if tmux("show-options", "-t", sid, "-qv", "@tmuxremote_voice_turn") != "1":
            return
        tmux("set-option", "-t", sid, "-u", "@tmuxremote_voice_turn")
        reply = data.get("last_assistant_message") or ""
        if not isinstance(reply, str) or not reply:
            reply = last_reply_from_transcript(data.get("transcript_path") or "")
        text, _ = spoken_part(reply)
        if text:
            append(sid, text)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        pass
    sys.exit(0)
