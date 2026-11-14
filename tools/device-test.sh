#!/usr/bin/env bash
# Wing Custom on-device test: switches between the main screen and the second screen with
# volume up + down, and after every switch checks what a person would notice first.
#
#   tools/device-test.sh [rounds]      (default 4 switches; the phone must be unlocked,
#                                        main screen mode applied and kept once)
#
# Checks per switch (B = the bottom panel's logical display: 0 swapped, 4 not):
#   layout     bottom panel touch viewport is on B and active
#   watcher    LG home's gesture watcher (home / recents) is on B
#   home       an app on B, swipe up from the bottom edge -> home on B
#   back       a settings page on B, swipe in from the left edge -> leaves the page
#   recents    (main screen only) recents key -> overview on B
#   power      power key held -> a power menu with focus on B
#   density    the bottom panel keeps the density it started with
#   leaks      SystemUI gesture watchers on B stay few (warning only)
set -u
export MSYS_NO_PATHCONV=1

ROUNDS=${1:-4}
BOTTOM=4630946299134858882
PASS=0
FAIL=0
FAILS=()

sh() { adb shell "$@" 2>/dev/null | tr -d '\r'; }
ok() { PASS=$((PASS + 1)); echo "  ok    $1"; }
bad() { FAIL=$((FAIL + 1)); FAILS+=("round $R: $1"); echo "  FAIL  $1"; }
warn() { echo "  warn  $1"; }
pause() { sleep "$1"; }

# Logical display showing the bottom panel, from the input viewports.
bottom_display() {
    sh dumpsys input | grep -m1 "Viewport INTERNAL: displayId=[0-9]*, uniqueId=local:$BOTTOM" \
        | sed -E 's/.*displayId=([0-9]+).*/\1/'
}

viewport_active() {
    sh dumpsys input | grep -m1 "Viewport INTERNAL: displayId=$1, uniqueId=local:$BOTTOM" | grep -q "isActive=\[1\]"
}

# Resumed activity on a display (component), from the activity manager.
resumed_on() {
    sh dumpsys activity activities | awk -v d="Display #$1 " '
        index($0, d) == 1 { on = 1; next }
        /^Display #/ { on = 0 }
        on && /ResumedActivity:/ { print $4; exit }'
}

focus_on() {
    sh dumpsys window displays | awk -v d="Display: mDisplayId=$1 " '
        index($0, d) == 1 { on = 1; next }
        /Display: mDisplayId=/ { on = 0 }
        on && /mCurrentFocus=/ { sub(/.*mCurrentFocus=/, ""); print; exit }'
}

pid_of() { sh pidof "$1"; }

watchers_on() { # owner pid, display
    sh dumpsys input | grep "name='\[Gesture Monitor\] swipe-up'" | grep "displayId=$2," | grep -c "ownerPid=$1,"
}

home_pkg() { [ "$1" = 0 ] && echo com.lge.launcher3 || echo com.lge.secondlauncher; }

open_settings() { # display, [action]
    if [ -n "${2:-}" ]; then
        sh am start --display "$1" -a "$2" >/dev/null
    else
        sh am start --display "$1" -n com.android.settings/.Settings >/dev/null
    fi
    pause 2
}

check_round() {
    local swapped b expect l3 sui
    swapped=$(sh settings get global godaijishou_swapped)
    [ "$swapped" = 1 ] && expect=0 || expect=4
    b=$(bottom_display)
    echo "round $R: $([ "$swapped" = 1 ] && echo main || echo second) screen (bottom panel = display ${b:-?})"

    # layout
    if [ "$b" = "$expect" ] && viewport_active "$b"; then ok layout; else bad "layout: bottom on '${b}', expected $expect"; return; fi

    # watcher
    l3=$(pid_of com.lge.launcher3)
    if [ "$(watchers_on "$l3" "$b")" -ge 1 ]; then ok watcher; else bad "watcher: LG home has no gesture watcher on display $b"; fi

    # home
    open_settings "$b"
    sh input -d "$b" swipe 540 1236 540 500 180
    pause 2
    case "$(resumed_on "$b")" in
        "$(home_pkg "$b")"/*) ok home ;;
        *) bad "home: swipe up left '$(resumed_on "$b")' on display $b" ;;
    esac

    # back
    open_settings "$b" android.settings.WIFI_SETTINGS
    local before after
    before=$(resumed_on "$b")
    sh input -d "$b" swipe 3 600 420 600 200
    pause 2
    after=$(resumed_on "$b")
    if [ -n "$before" ] && [ "$after" != "$before" ]; then ok back; else bad "back: still on '$before'"; fi
    sh input keyevent KEYCODE_HOME
    pause 1

    # recents (main screen only: LG keeps the second screen without it)
    if [ "$b" = 0 ]; then
        open_settings "$b"
        sh input keyevent KEYCODE_APP_SWITCH
        pause 2
        case "$(resumed_on "$b")" in
            com.lge.launcher3/*) ok recents ;;
            *) bad "recents: '$(resumed_on "$b")' on display $b" ;;
        esac
        sh input keyevent KEYCODE_HOME
        pause 1
    fi

    # power
    sh input keyevent --longpress KEYCODE_POWER
    pause 2
    local f
    f=$(focus_on "$b")
    if [ "$b" = 4 ]; then
        case "$f" in *PowerMenuActivity*) ok power ;; *) bad "power: focus '$f' on display 4" ;; esac
    else
        case "$f" in *ActionsDialog*|*GlobalActions*|*PowerMenu*) ok power ;; *) bad "power: focus '$f' on display 0" ;; esac
    fi
    sh input keyevent KEYCODE_BACK
    pause 1

    # density
    local d
    d=$(sh wm density -d "$b" | tail -1 | sed -E 's/.*: //')
    if [ -z "$DENSITY" ]; then DENSITY=$d; fi
    if [ "$d" = "$DENSITY" ]; then ok "density $d"; else bad "density: $d, started at $DENSITY"; fi

    # leaks
    sui=$(pid_of com.android.systemui)
    local n
    n=$(watchers_on "$sui" "$b")
    if [ "$n" -le 2 ]; then ok "systemui watchers $n"; else warn "systemui watchers on display $b: $n"; fi
}

switch() {
    sh input keyevent KEYCODE_HOME
    pause 1
    sh input keycombination -t 200 KEYCODE_VOLUME_UP KEYCODE_VOLUME_DOWN
    pause 5
}

adb get-state >/dev/null 2>&1 || { echo "no device"; exit 2; }
if sh dumpsys window | grep -q "isKeyguardShowing=true"; then echo "unlock the phone first"; exit 2; fi

DENSITY=""
R=0
check_round
for R in $(seq 1 "$ROUNDS"); do
    switch
    check_round
done

echo
echo "passed $PASS, failed $FAIL"
for f in "${FAILS[@]}"; do echo "  $f"; done
[ "$FAIL" = 0 ]
