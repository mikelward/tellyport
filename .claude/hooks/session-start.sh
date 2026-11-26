#!/bin/bash
# SessionStart hook: provision the Android SDK for Claude Code on the web.
#
# Remote (web) sessions start from a fresh container where ANDROID_HOME points
# at an SDK that is not yet installed, so Gradle builds, unit tests, and lint
# cannot run until the SDK is seeded. This installs the command-line tools and
# the base platform; AGP auto-installs a matching build-tools on the first
# build. Local and desktop environments manage their own SDK, so this is a
# no-op outside the remote environment.
set -euo pipefail

checkout=$(cd "$(dirname "$0")/../.." && pwd)

# Deepen the clone before anything reads history -- see scripts/unshallow.sh.
#
# Above the remote-only guard on purpose, unlike everything below it: the
# versionCode is `git rev-list --count`, and a shallow clone answers that with a
# confident wrong number wherever the clone came from. On a complete clone it is
# a no-op that says so. Run directly rather than through `sh` so it needs nothing
# on PATH, and best-effort: it reports its own failures, and a clone it could
# not deepen is never a reason to refuse to start the session.
(cd "$checkout" && "$checkout/scripts/unshallow.sh") || true

# Only provision in the remote (Claude Code on the web) environment.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# The hook's record: when it started, how it ended, and the SDK tools' own
# output, kept where it outlasts the session's terminal.
log_dir="${XDG_STATE_HOME:-$HOME/.local/state}/tellyport"
mkdir -p "$log_dir"
LOG="$log_dir/session-start.log"
log() { printf '%s session-start: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" | tee -a "$LOG" >&2; }
tmp=""
finish() {
  status=$?
  if [ -n "$tmp" ]; then rm -rf "$tmp"; fi
  if [ "$status" -eq 0 ]; then log "ended: Android SDK ready"; else log "ended: failed with status $status (details in $LOG)"; fi
}
trap finish EXIT
log "started"

accept_licenses() {
  local accepted=0
  set +o pipefail
  yes | "$@" --licenses >>"$LOG" 2>&1 || accepted=$?
  set -o pipefail
  if [ "$accepted" -ne 0 ]; then log "accepting the SDK licenses failed with status $accepted"; fi
}

ANDROID_SDK_ROOT="${ANDROID_HOME:-/opt/android-sdk}"
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-13114758}"
PLATFORM="platforms;android-34"

export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT

# Persist the SDK location and tool paths for the rest of the session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_SDK_ROOT\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_SDK_ROOT\""
    echo "export PATH=\"\$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools\""
  } >> "$CLAUDE_ENV_FILE"
fi

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  log "bootstrapping the Android command-line tools into $ANDROID_SDK_ROOT"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 4 --retry-delay 2 -o "$tmp/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" 2>>"$LOG"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp/extracted" >>"$LOG" 2>&1
  bootstrap_sdkmanager="$tmp/extracted/cmdline-tools/bin/sdkmanager"
  accept_licenses "$bootstrap_sdkmanager" --sdk_root="$ANDROID_SDK_ROOT"
  "$bootstrap_sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" "cmdline-tools;latest" >>"$LOG" 2>&1
fi

accept_licenses "$SDKMANAGER"
"$SDKMANAGER" "$PLATFORM" "platform-tools" >>"$LOG" 2>&1
