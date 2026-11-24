# Third-party parts

| Part | Where | Licence |
|---|---|---|
| Termux terminal emulator and terminal view | source in `app/src/main/java/com/termux/` and `app/src/main/res/` (text selection handles, strings) | The `termux-app` repository is GPL-3.0-only; its terminal libraries are built on Terminal Emulator for Android, which is Apache-2.0. Full text: [third_party/termux-LICENSE.md](third_party/termux-LICENSE.md) |
| ConnectBot sshlib (`org.connectbot:sshlib`) | Gradle dependency | BSD-3-Clause |
| Vosk (`com.alphacephei:vosk-android`) | Gradle dependency | Apache-2.0 |
| Vosk models `vosk-model-small-de-0.15`, `vosk-model-small-en-us-0.15` | downloaded by `tools/fetch_speech_models.sh`, packed into the APK | Apache-2.0 |
| JNA (`net.java.dev.jna:jna`) | Gradle dependency | Apache-2.0 or LGPL-2.1 |
| AndroidX annotation, core, drawerlayout | Gradle dependencies | Apache-2.0 |

Changes made to the Termux code here:

- `com/termux/terminal/TerminalSession.java` is rewritten: the local subprocess and its JNI
  part are replaced by a `Transport` for a remote byte stream. `JNI.java` and the native code
  are removed.
- `com/termux/view/TerminalView.java` gained a `ScrollHook`, so the app can take over
  scrolling and sideways flings.
- The resource import in the text selection classes points at this app's package.

Because part of the app derives from GPL-3.0 code, the app as a whole is under GPL-3.0.
