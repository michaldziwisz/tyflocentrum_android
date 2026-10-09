#!/usr/bin/env bash
# Jeden przebieg: kontrolki aparatury, następnie nieprzefiltrowana pełna suita.
set -uo pipefail
mkdir -p app/build/refresh-evidence
collect_final() {
  adb logcat -d -v threadtime > app/build/content-time-semantics-logcat.txt
  adb pull /data/local/tmp/tyflo-refresh app/build/refresh-evidence/full
}
trap collect_final EXIT
./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.tyflopodcast.tyflocentrum.ui.common.RefreshAccessibilityControlTest
control_result=$?
cp -a app/build/outputs/androidTest-results/connected app/build/refresh-evidence/control-xml
adb logcat -d -v threadtime > app/build/refresh-evidence/control-logcat.txt
adb pull /data/local/tmp/tyflo-refresh app/build/refresh-evidence/control
if [ "$control_result" -ne 0 ]; then
  printf 'Kontrolki aparatury: FAIL. Pełna macierz nie została uruchomiona.\n'
  exit "$control_result"
fi
# Zachowaj kontrolkę oddzielnie. Nie nadpisuj jej dowodów drugim uruchomieniem.
adb shell mv /data/local/tmp/tyflo-refresh /data/local/tmp/tyflo-refresh-control
adb logcat -c
./gradlew --no-daemon connectedDebugAndroidTest
exit $?
