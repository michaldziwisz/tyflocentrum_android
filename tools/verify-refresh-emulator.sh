#!/usr/bin/env bash
# Jeden przebieg: kontrolki i odebrane dowody, następnie pełna suita.
set -uo pipefail
python3 -B tools/test_refresh_evidence.py || exit 1
out=app/build/refresh-evidence
mkdir -p "$out" || exit 1
collect_emergency() {
  adb logcat -d -v threadtime > app/build/content-time-semantics-logcat.txt
  adb pull /data/local/tmp/tyflo-refresh "$out/emergency" > "$out/emergency-pull.log" 2>&1 || true
}
trap collect_emergency EXIT
collect_stage() {
  local stage="$1"
  adb logcat -d -v threadtime > "$out/$stage-logcat.txt" || return 1
  adb pull /data/local/tmp/tyflo-refresh "$out/$stage" > "$out/$stage-pull.log" 2>&1 || return 1
  python3 tools/verify-refresh-evidence.py "$out/$stage" "$out/$stage-logcat.txt" > "$out/$stage-verification.json"
}
./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.tyflopodcast.tyflocentrum.ui.common.RefreshAccessibilityControlTest
control_result=$?
cp -a app/build/outputs/androidTest-results/connected "$out/control-xml" || exit 1
collect_stage control
collection_result=$?
if [ "$control_result" -ne 0 ] || [ "$collection_result" -ne 0 ]; then
  printf 'Kontrolki lub odbiór dowodów: FAIL. Pełna macierz nie została uruchomiona.\n'
  exit 1
fi
# Przebieg pełny nie może nadpisać kontrolek ani odziedziczyć ich deklaracji zapisu.
adb shell mv /data/local/tmp/tyflo-refresh /data/local/tmp/tyflo-refresh-control || exit 1
adb logcat -c || exit 1
./gradlew --no-daemon connectedDebugAndroidTest
full_result=$?
collect_stage full
collection_result=$?
if [ "$full_result" -ne 0 ] || [ "$collection_result" -ne 0 ]; then exit 1; fi
