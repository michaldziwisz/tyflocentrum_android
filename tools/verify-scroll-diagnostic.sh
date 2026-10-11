#!/usr/bin/env bash
# Jedna celowana próba w izolowanym emulatorze CI, bez zmian telefonu.
# Zakres: wyłącznie diagnostyka nowej ścieżki scroll. Nie zmienia źródeł Androida
# ani istniejącego verifiera/pełnego CI.
set -uo pipefail
: "${SCROLL_TEST_CLASSES:?Brak jawnej listy testów do pomiaru}"
out=app/build/scroll-diagnostic
mkdir -p "$out" || exit 1
: > "$out/result.txt" || exit 1
printf '%s\n' "$SCROLL_TEST_CLASSES" > "$out/selected-classes.txt"
printf '%s\n' 'reader-controls: NOT_EXECUTED (requires physical TalkBack)' > "$out/scope.txt"

# Każdy kod wyjścia trafia na dysk w chwili powstania, nie dopiero za bramką.
record() { printf '%s=%s\n' "$1" "$2" >> "$out/result.txt"; }

collect_emergency() {
  adb logcat -d -v threadtime > "$out/emergency-logcat.txt"
  adb pull /data/local/tmp/tyflo-refresh "$out/emergency" > "$out/emergency-pull.log" 2>&1 || true
}
trap collect_emergency EXIT

# Dowody z urządzenia muszą być płaskie: same zwykłe pliki.
# Jakikolwiek podkatalog, symlink lub inny typ wpisu = odrzucenie odbioru.
assert_flat_evidence() {
  local dir="$1"
  [ -e "$dir" ] || return 1
  if [ -L "$dir" ]; then
    printf '%s\n' "$dir (katalog dowodów jest symlinkiem)" > "$dir.flatness-violations.txt"
    return 1
  fi
  [ -d "$dir" ] || return 1
  local offenders
  offenders=$(find "$dir" -mindepth 1 ! -type f -print 2>/dev/null) || return 1
  if [ -n "$offenders" ]; then
    printf '%s\n' "$offenders" > "$dir.flatness-violations.txt"
    return 1
  fi
  return 0
}

collect_stage() {
  local stage="$1"
  adb logcat -d -v threadtime > "$out/$stage-logcat.txt" || return 1
  adb pull /data/local/tmp/tyflo-refresh "$out/$stage" > "$out/$stage-pull.log" 2>&1 || return 1
  assert_flat_evidence "$out/$stage" || return 1
  # stdout = maszynowy JSON, stderr = osobny plik, żeby jedno nie psuło drugiego.
  python3 -B tools/verify-refresh-evidence.py "$out/$stage" "$out/$stage-logcat.txt" \
    > "$out/$stage-verification.json" 2> "$out/$stage-verification.err"
}

# Bufor logcat: powiększenie i czyszczenie JESZCZE przed kontrolkami.
# Błąd którejkolwiek z dwóch operacji blokuje całą próbę (fail-closed).
adb logcat -G 16M > "$out/logcat-resize.log" 2>&1
logcat_resize_exit=$?
adb logcat -c > "$out/logcat-clear.log" 2>&1
logcat_clear_exit=$?
record logcat_resize_exit "$logcat_resize_exit"
record logcat_clear_exit "$logcat_clear_exit"
if [ "$logcat_resize_exit" -ne 0 ] || [ "$logcat_clear_exit" -ne 0 ]; then exit 1; fi

python3 -B tools/test_refresh_evidence.py || exit 1

# --- kontrolki ---
./gradlew --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.tyflopodcast.tyflocentrum.ui.common.RefreshAccessibilityControlTest
control_result=$?
# Błąd kopii XML nie może skrócić odbioru kontrolki ani zgubić kodów wyjścia.
cp -a app/build/outputs/androidTest-results/connected "$out/control-xml" > "$out/control-xml-copy.log" 2>&1
control_xml_copy_exit=$?

# --- odbiór kontrolki ---
collect_stage control
control_collection_exit=$?
record control_test_exit "$control_result"
record control_collection_exit "$control_collection_exit"
record control_xml_copy_exit "$control_xml_copy_exit"

# --- bramka ---
if [ "$control_result" -ne 0 ] \
  || [ "$control_collection_exit" -ne 0 ] \
  || [ "$control_xml_copy_exit" -ne 0 ]; then exit 1; fi

adb shell mv /data/local/tmp/tyflo-refresh /data/local/tmp/tyflo-refresh-control
control_evidence_move_exit=$?
record control_evidence_move_exit "$control_evidence_move_exit"
[ "$control_evidence_move_exit" -eq 0 ] || exit 1

adb logcat -c > "$out/logcat-clear-selected.log" 2>&1
logcat_clear_selected_exit=$?
record logcat_clear_selected_exit "$logcat_clear_selected_exit"
[ "$logcat_clear_selected_exit" -eq 0 ] || exit 1

# --- próbka selected ---
./gradlew --no-daemon connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$SCROLL_TEST_CLASSES"
selected_result=$?

# --- odbiór selected ---
collect_stage selected
collection_result=$?
record test_exit "$selected_result"
record collection_exit "$collection_result"
if [ "$selected_result" -ne 0 ] || [ "$collection_result" -ne 0 ]; then exit 1; fi
exit 0
