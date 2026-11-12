#!/bin/bash
# Takes screenshots of Habit Chat (test build with made-up sample chats) in both themes, on an emulator.
set -x
apk=android/app/build/outputs/apk/lite/debug/app-lite-debug.apk
pkg=com.kishore.bigd.lite
mkdir -p android/shots
# no "isn't responding" pop-ups on top of the screenshots (the emulator is slow right after it starts)
adb shell settings put global hide_error_dialogs 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell cmd uimode night yes; sleep 2; adb shell cmd uimode night no
sleep 45
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
adb shell input keyevent KEYCODE_HOME
adb install -r "$apk"
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS || true
shot() { # theme screen name [language] [app colour]
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
  echo "== $3: $(adb shell am start -S -W -n $pkg/com.soulfriend.pet.DemoActivity --es theme "$1" --es screen "$2" --es lang "${4:-en}" --es colour "${5:-purple}" 2>&1 | tr "\n" " ")" >> android/shots/starts.txt
  sleep 6
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
  sleep 1
  echo "== $3: $(adb shell dumpsys window | grep -m1 mCurrentFocus)" >> android/shots/focus.txt
  adb exec-out screencap -p > "android/shots/$3.png"
}
snap() { sleep 2; adb exec-out screencap -p > "android/shots/$1.png"; }
for th in dark light; do
  for sc in list chat group settings info contact media starred ai devices; do shot $th $sc "$th-$sc"; done
  # the chat list's other tabs: Updates, Communities, Calls
  shot $th list "$th-list"
  adb shell input tap 418 396; snap "$th-updates"
  adb shell input tap 662 396; snap "$th-comms"
  adb shell input tap 906 396; snap "$th-calls"
  # the group's older messages (the task card and the poll)
  shot $th group "$th-group"
  adb shell input swipe 540 800 540 2000 250; adb shell input swipe 540 800 540 2000 250; snap "$th-group-top"
  # the attach sheet; then picking messages like WhatsApp: long-press one (top bar + emojis above it), tap another, ⋮
  shot $th chat "$th-chat"
  adb shell input tap 630 2242; snap "$th-attach"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell input swipe 360 1890 360 1890 900; snap "$th-pick1"
  adb shell input tap 840 2040; snap "$th-pick2"
  adb shell input tap 1022 208; snap "$th-pickmore"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell input keyevent KEYCODE_BACK; snap "$th-pick-back"
  # a message near the top: the emojis go under it
  adb shell input swipe 460 600 460 600 900; snap "$th-picktop"
  adb shell input keyevent KEYCODE_BACK
  # picking chats in the list: long-press one, tap another, ⋮
  shot $th list "$th-list"
  adb shell input swipe 540 1236 540 1236 900; snap "$th-listpick1"
  adb shell input tap 540 1424; snap "$th-listpick2"
  adb shell input tap 1017 211; snap "$th-listpickmore"
  adb shell input keyevent KEYCODE_BACK; sleep 1
  adb shell input keyevent KEYCODE_BACK; snap "$th-listpick-back"
done
# the sign-in screen, light and dark
shot light login login-light
shot dark login login-dark
# the rest of the app: Home in each colour choice, Home scrolled, the colour sheet, then each page
for col in teal purple orange green blue maroon; do shot light home "home-$col" en $col; done
shot light home home-top
adb shell input swipe 540 1700 540 700 400; snap home-mid
adb shell input swipe 540 1700 540 500 400; adb shell input swipe 540 1700 540 500 400; snap home-bottom
shot light home home-sheet0
adb shell input tap 983 212; snap home-sheet
adb shell input keyevent KEYCODE_BACK
for sc in tasks files vault pet translate family govt feed; do shot light $sc "page-$sc"; done
# the dark look of the rest of the app (the phone's dark mode, or Theme › Dark)
for col in teal purple orange green blue maroon; do shot dark home "dark-home-$col" en $col; done
shot dark home dark-home-top
adb shell input swipe 540 1700 540 700 400; snap dark-home-mid
adb shell input swipe 540 1700 540 500 400; adb shell input swipe 540 1700 540 500 400; snap dark-home-bottom
shot dark home dark-home-sheet0
adb shell input tap 983 212; snap dark-home-sheet
adb shell input keyevent KEYCODE_BACK
for sc in tasks files pet translate family govt feed; do shot dark $sc "dark-page-$sc"; done
shot dark home te-dark-home te
shot light home te-home te
# Telugu words take more room: check they fit
shot dark list te-dark-list te
shot light chat te-light-chat te
adb shell input swipe 360 1890 360 1890 900; snap te-light-pick1
adb shell input keyevent KEYCODE_BACK
shot dark group te-dark-group te
# "Automatic" follows the phone's dark mode
adb shell cmd uimode night yes
sleep 4
shot auto list auto-night-list
shot auto chat auto-night-chat
adb shell cmd uimode night no
adb logcat -d -b crash > android/shots/crash.txt || true
adb logcat -d | grep -E "soulfriend|kishore|AndroidRuntime|FATAL" | tail -300 > android/shots/app-log.txt || true
ls -la android/shots
