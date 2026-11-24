#!/usr/bin/env bash
# Build on another machine over SSH (for when this one is too small for Gradle).
# A normal build needs none of this: tools/fetch_speech_models.sh && ./gradlew assembleDebug
#
# Usage: TMUXREMOTE_BUILD_BOX=<ssh host> tools/build.sh [gradle tasks...]   (default: assembleDebug)
#
# Copies this working tree to <box>:~/tmuxapp-build/src, runs the build there and fetches the
# APK to ./out/ (tmux-remote-debug.apk, and tmux-remote.apk for assembleRelease). The box needs a JDK 17 and the Android SDK; set
# TMUXREMOTE_BUILD_CMD to how a build is started there (it gets the source dir and the tasks),
# default: "cd <dir> && ./gradlew --no-daemon <tasks>".
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
box=${TMUXREMOTE_BUILD_BOX:?set TMUXREMOTE_BUILD_BOX to the ssh name of the build machine}
dir=tmuxapp-build/src
[ $# -gt 0 ] || set -- assembleDebug
"$here/tools/fetch_speech_models.sh"
ssh -o BatchMode=yes "$box" "mkdir -p ~/$dir"
rsync -a --delete --exclude .git --exclude .cache --exclude out --exclude build --exclude .gradle --exclude .kotlin --exclude local.properties "$here/" "$box:$dir/"
run=${TMUXREMOTE_BUILD_CMD:-"cd ~/$dir && ./gradlew --no-daemon"}
# The integration test (src/test) talks to a real host only when these are set.
testenv="TMUXREMOTE_TEST_HOST='${TMUXREMOTE_TEST_HOST:-}' TMUXREMOTE_TEST_USER='${TMUXREMOTE_TEST_USER:-}' TMUXREMOTE_TEST_KEY='${TMUXREMOTE_TEST_KEY:-}'"
if ssh -o BatchMode=yes "$box" "$testenv $run $* > ~/tmuxapp-build/build.log 2>&1"; then
    mkdir -p "$here/out"
    case " $* " in
        *Release*) scp -q "$box:$dir/app/build/outputs/apk/release/app-release.apk" "$here/out/tmux-remote.apk" && echo "OK: $here/out/tmux-remote.apk" ;;
    esac
    case " $* " in
        *Debug*) scp -q "$box:$dir/app/build/outputs/apk/debug/app-debug.apk" "$here/out/tmux-remote-debug.apk" && echo "OK: $here/out/tmux-remote-debug.apk" ;;
    esac
    ssh -o BatchMode=yes "$box" "grep -hoE '<testsuite name=\"[^\"]*\" tests=\"[0-9]*\" skipped=\"[0-9]*\" failures=\"[0-9]*\" errors=\"[0-9]*\"' ~/$dir/app/build/test-results/*/*.xml 2>/dev/null" || true
else
    echo "FAILED"
    ssh -o BatchMode=yes "$box" "grep -E '^e: |error:|FAILED|What went wrong|AssertionError|ComparisonFailure' -A3 ~/tmuxapp-build/build.log | head -60"
    exit 1
fi
