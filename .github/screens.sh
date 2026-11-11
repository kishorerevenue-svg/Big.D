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
shot() { # theme screen name [language]
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
  echo "== $3: $(adb shell am start -S -W -n $pkg/com.soulfriend.pet.DemoActivity --es theme "$1" --es screen "$2" --es lang "${4:-en}" 2>&1 | tr "\n" " ")" >> android/shots/starts.txt
  sleep 6
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
  sleep 1
  echo "== $3: $(adb shell dumpsys window | grep -m1 mCurrentFocus)" >> android/shots/focus.txt
  adb exec-out screencap -p > "android/shots/$3.png"
}
for th in dark light; do
  for sc in list chat group settings info ai devices; do shot $th $sc "$th-$sc"; done
done
# Telugu words take more room: check they fit
shot dark list te-dark-list te
shot light chat te-light-chat te
shot dark group te-dark-group te
# "Automatic" follows the phone's dark mode
adb shell cmd uimode night yes
sleep 4
shot auto list auto-night-list
shot auto chat auto-night-chat
adb shell cmd uimode night no
adb logcat -d -t 600 '*:E' | grep -iv "wifi\|bluetooth\|telephony" | tail -150 > android/shots/errors.txt || true
ls -la android/shots
