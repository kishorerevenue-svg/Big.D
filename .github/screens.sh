#!/bin/bash
# Takes screenshots of Habit Chat (test build with made-up sample chats) in both themes, on an emulator.
set -x
apk=android/app/build/outputs/apk/lite/debug/app-lite-debug.apk
pkg=com.kishore.bigd.lite
mkdir -p android/shots
adb install -r "$apk"
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
shot() { # theme screen name
  adb shell am start -W -n $pkg/com.soulfriend.pet.DemoActivity --es theme "$1" --es screen "$2"
  sleep 5
  adb exec-out screencap -p > "android/shots/$3.png"
}
adb shell cmd uimode night no
for th in dark light; do
  for sc in list chat group settings info ai devices; do shot $th $sc "$th-$sc"; done
done
# "Automatic" follows the phone's dark mode
adb shell cmd uimode night yes
sleep 3
shot auto list auto-night-list
shot auto chat auto-night-chat
adb logcat -d -t 400 '*:E' > android/shots/errors.txt || true
ls -la android/shots
