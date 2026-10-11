#!/usr/bin/env python3
"""Atest tools/verify-scroll-diagnostic.sh - WYŁĄCZNIE skryptu.

CZEGO TEN TEST NIE ROBI (jawnie):
  * nie uruchamia Androida, emulatora, ADB, Gradle'a ani aplikacji,
  * nie ocenia asercji aplikacyjnych ani logiki próbki testów scroll,
  * nie dotyka telefonu, sieci, buildu ani istniejącego verifiera/CI.

Atrapami są TYLKO zewnętrzne zależności skryptu: adb, ./gradlew oraz dwa
pythonowe narzędzia verifiera. Mierzony jest prawdziwy, niezmodyfikowany
plik skryptu podany w --script.

Użycie:
  python3 tools/test_scroll_diagnostic.py [--script PATH] [--sandbox DIR]
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONTROL_CLASS = "net.tyflopodcast.tyflocentrum.ui.common.RefreshAccessibilityControlTest"
STDERR_MARKER = "VERIFIER_STDERR_MARKER"

FAKE_ADB = r"""#!/usr/bin/env bash
printf 'adb %s\n' "$*" >> "$FAKE_TRACE"
if [ "$1" = "logcat" ]; then
  case "$2" in
    -G)
      if [ -n "${FAKE_ADB_FAIL_G:-}" ]; then echo "resize refused" >&2; exit 1; fi
      exit 0 ;;
    -c)
      if [ -n "${FAKE_ADB_FAIL_C:-}" ] && [ ! -e "$FAKE_STATE/clear_seen" ]; then
        : > "$FAKE_STATE/clear_seen"; echo "clear refused" >&2; exit 1
      fi
      : > "$FAKE_STATE/clear_seen"; exit 0 ;;
    -d)
      echo "01-01 00:00:00.000  1000  1000 I fake-logcat: dump"; exit 0 ;;
  esac
  exit 0
fi
if [ "$1" = "pull" ]; then
  dest="$3"
  mkdir -p "$dest" || exit 1
  printf '{"fake":"evidence"}\n' > "$dest/refresh-evidence.json"
  case "$dest" in
    *emergency*) : ;;
    *)
      if [ -n "${FAKE_PULL_SUBDIR:-}" ]; then
        mkdir -p "$dest/nested" && printf 'x\n' > "$dest/nested/inner.json"
      fi
      if [ -n "${FAKE_PULL_SYMLINK:-}" ]; then
        ln -sfn refresh-evidence.json "$dest/alias.json"
      fi ;;
  esac
  echo "pulled 1 file"; exit 0
fi
if [ "$1" = "shell" ]; then exit "${FAKE_ADB_SHELL_EXIT:-0}"; fi
exit 0
"""

FAKE_GRADLEW = r"""#!/usr/bin/env bash
printf 'gradlew %s\n' "$*" >> "$FAKE_TRACE"
xml=app/build/outputs/androidTest-results/connected
if [ -n "${FAKE_CP_BREAK:-}" ]; then
  rm -rf "$xml"
else
  mkdir -p "$xml" && printf '<testsuite/>\n' > "$xml/results.xml"
fi
# Rozróżnienie po przecinku: próbka selected jest listą wieloklasową,
# kontrolka to dokładnie jedna klasa (ta sama nazwa występuje w obu).
case "$*" in
  *,*)
    : > "$FAKE_STATE/selected_ran"; exit "${FAKE_GRADLE_SELECTED_EXIT:-0}" ;;
  *RefreshAccessibilityControlTest*)
    : > "$FAKE_STATE/control_ran"; exit "${FAKE_GRADLE_CONTROL_EXIT:-0}" ;;
  *)
    : > "$FAKE_STATE/selected_ran"; exit "${FAKE_GRADLE_SELECTED_EXIT:-0}" ;;
esac
"""

FAKE_VERIFIER = '''#!/usr/bin/env python3
import json, sys
print("verifier noise %s", file=sys.stderr)
print(json.dumps({"stage_dir": sys.argv[1], "logcat": sys.argv[2], "fake": True}))
''' % STDERR_MARKER

FAKE_SELFTEST = """#!/usr/bin/env python3
import sys
print("fake old-verifier selftest ok")
sys.exit(0)
"""


def selected_classes() -> tuple[str, str]:
    """Lista klas próbki: czytana z workflow, bez wymyślania nazw."""
    wf = REPO / ".github/workflows/scroll-diagnostic.yml"
    if wf.is_file():
        txt = wf.read_text(encoding="utf-8", errors="replace")
        m = re.search(r"SCROLL_TEST_CLASSES\s*:\s*[\"']?([A-Za-z0-9_.,#$-]+)", txt)
        if m and "," in m.group(1):
            return m.group(1), f"workflow {wf.name}"
    synth = ",".join([CONTROL_CLASS] + [f"net.tyflopodcast.fake.Case{i}Test" for i in range(1, 8)])
    return synth, "zastępcza lista syntetyczna (workflow nieodczytany)"


def make_case(sandbox: Path, name: str, script: Path) -> tuple[Path, dict]:
    case = sandbox / name
    shutil.rmtree(case, ignore_errors=True)
    (case / "tools").mkdir(parents=True)
    (case / "bin").mkdir()
    (case / "state").mkdir()
    shutil.copy2(script, case / "tools" / "verify-scroll-diagnostic.sh")
    for rel, body in (
        ("bin/adb", FAKE_ADB),
        ("gradlew", FAKE_GRADLEW),
        ("tools/verify-refresh-evidence.py", FAKE_VERIFIER),
        ("tools/test_refresh_evidence.py", FAKE_SELFTEST),
    ):
        p = case / rel
        p.write_text(body, encoding="utf-8")
        p.chmod(0o755)
    (case / "tools" / "verify-scroll-diagnostic.sh").chmod(0o755)
    return case, {
        "FAKE_TRACE": str(case / "trace.log"),
        "FAKE_STATE": str(case / "state"),
    }


def run_case(sandbox: Path, name: str, script: Path, classes: str, **fakes) -> dict:
    case, base = make_case(sandbox, name, script)
    env = dict(os.environ)
    env.update(base)
    env["PATH"] = f"{case / 'bin'}{os.pathsep}{env['PATH']}"
    env["SCROLL_TEST_CLASSES"] = classes
    env.update({k: str(v) for k, v in fakes.items() if v is not None})
    proc = subprocess.run(
        ["bash", "tools/verify-scroll-diagnostic.sh"],
        cwd=case, env=env, capture_output=True, text=True, timeout=180,
    )
    out = case / "app/build/scroll-diagnostic"
    trace = (case / "trace.log").read_text(encoding="utf-8", errors="replace") if (case / "trace.log").exists() else ""
    result_txt = (out / "result.txt").read_text(encoding="utf-8", errors="replace") if (out / "result.txt").exists() else ""
    return {
        "case": case, "out": out, "rc": proc.returncode, "trace": trace,
        "result": result_txt,
        "codes": dict(
            line.split("=", 1) for line in result_txt.splitlines() if "=" in line
        ),
        "control_ran": (case / "state/control_ran").exists(),
        "selected_ran": (case / "state/selected_ran").exists(),
        "stdout": proc.stdout, "stderr": proc.stderr,
    }


def check(results: list, name: str, ok: bool, detail: str) -> None:
    results.append((name, bool(ok), detail))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--script", default=str(REPO / "tools/verify-scroll-diagnostic.sh"))
    ap.add_argument("--sandbox", default=str(REPO.parent / "scroll-diagnostic-selftest"))
    args = ap.parse_args()
    script = Path(args.script).resolve()
    sandbox = Path(args.sandbox).resolve()
    sandbox.mkdir(parents=True, exist_ok=True)
    classes, src = selected_classes()

    print("ATEST DOTYCZY WYŁĄCZNIE SKRYPTU verify-scroll-diagnostic.sh.")
    print("NIE atestuje Androida, emulatora, aplikacji ani asercji próbki.")
    print(f"script : {script}")
    print(f"sandbox: {sandbox}")
    print(f"klasy  : {len(classes.split(','))} szt. ({src})\n")

    r: list = []

    # --- G1: preambuła logcat przed kontrolkami, błąd każdej z dwóch blokuje ---
    happy = run_case(sandbox, "happy", script, classes)
    t = happy["trace"]
    adb_lines = [l for l in t.splitlines() if l.startswith("adb ")]
    first_gradle = next((i for i, l in enumerate(t.splitlines()) if l.startswith("gradlew ")), -1)
    pre = [l for i, l in enumerate(t.splitlines()) if i < first_gradle]
    check(r, "G1a logcat -G 16M przed pierwszym gradlew",
          any("logcat -G 16M" in l for l in pre), f"preambuła={pre[:4]}")
    check(r, "G1b logcat -c przed pierwszym gradlew",
          any(l.strip() == "adb logcat -c" for l in pre), f"preambuła={pre[:4]}")
    check(r, "G1c kolejność -G przed -c",
          next((i for i, l in enumerate(adb_lines) if "logcat -G" in l), 99)
          < next((i for i, l in enumerate(adb_lines) if l.strip() == "adb logcat -c"), -1),
          f"adb={adb_lines[:3]}")
    fg = run_case(sandbox, "fail_resize", script, classes, FAKE_ADB_FAIL_G=1)
    check(r, "G1d błąd logcat -G blokuje (rc!=0, kontrolki nie startują)",
          fg["rc"] != 0 and not fg["control_ran"], f"rc={fg['rc']} control_ran={fg['control_ran']}")
    fc = run_case(sandbox, "fail_clear", script, classes, FAKE_ADB_FAIL_C=1)
    check(r, "G1e błąd logcat -c blokuje (rc!=0, kontrolki nie startują)",
          fc["rc"] != 0 and not fc["control_ran"], f"rc={fc['rc']} control_ran={fc['control_ran']}")

    # --- G2: stderr verifiera w osobnym stage-verification.err ---
    for stage in ("control", "selected"):
        err = happy["out"] / f"{stage}-verification.err"
        js = happy["out"] / f"{stage}-verification.json"
        err_ok = err.is_file() and STDERR_MARKER in err.read_text(encoding="utf-8", errors="replace")
        raw = js.read_text(encoding="utf-8", errors="replace") if js.is_file() else ""
        try:
            json.loads(raw)
            parsed = True
        except Exception:
            parsed = False
        check(r, f"G2a {stage}-verification.err zawiera stderr verifiera", err_ok,
              f"istnieje={err.is_file()}")
        check(r, f"G2b {stage}-verification.json to czysty JSON bez stderr",
              parsed and STDERR_MARKER not in raw, f"parsed={parsed} len={len(raw)}")

    # --- G3: kody kontrolki na dysku przed bramką, także przy błędzie cp XML ---
    check(r, "G3a happy: control_test_exit/control_collection_exit zapisane",
          happy["codes"].get("control_test_exit") == "0"
          and happy["codes"].get("control_collection_exit") == "0",
          f"codes={happy['codes']}")
    cpf = run_case(sandbox, "cp_break", script, classes, FAKE_CP_BREAK=1)
    check(r, "G3b błąd cp XML: kody kontrolki wciąż zapisane",
          "control_test_exit" in cpf["codes"] and "control_collection_exit" in cpf["codes"],
          f"codes={cpf['codes']}")
    check(r, "G3c błąd cp XML: odbiór kontrolki NIE pominięty",
          (cpf["out"] / "control-logcat.txt").is_file()
          and (cpf["out"] / "control-verification.json").is_file(),
          f"logcat={(cpf['out'] / 'control-logcat.txt').is_file()}")
    check(r, "G3d błąd cp XML: fail-closed i bramka trzyma selected",
          cpf["rc"] != 0 and not cpf["selected_ran"],
          f"rc={cpf['rc']} selected_ran={cpf['selected_ran']}")

    # --- G4: odrzucenie niepłaskiego katalogu dowodów ---
    sub = run_case(sandbox, "pull_subdir", script, classes, FAKE_PULL_SUBDIR=1)
    check(r, "G4a podkatalog w dowodach odrzucony",
          sub["rc"] != 0 and sub["codes"].get("control_collection_exit") not in (None, "0")
          and not sub["selected_ran"],
          f"rc={sub['rc']} codes={sub['codes']}")
    sym = run_case(sandbox, "pull_symlink", script, classes, FAKE_PULL_SYMLINK=1)
    check(r, "G4b symlink w dowodach odrzucony",
          sym["rc"] != 0 and sym["codes"].get("control_collection_exit") not in (None, "0")
          and not sym["selected_ran"],
          f"rc={sym['rc']} codes={sym['codes']}")
    check(r, "G4c zapisany raport naruszeń płaskości",
          (sub["out"] / "control.flatness-violations.txt").is_file(),
          "control.flatness-violations.txt")

    # --- R: regresja istniejącej struktury (kontrolki->odbiór->bramka->selected->odbiór) ---
    order = [l for l in happy["trace"].splitlines()
             if l.startswith("gradlew ") or "pull /data/local/tmp" in l]
    shape = [
        ("control-gradle", bool(order) and "," not in order[0]
         and CONTROL_CLASS in order[0]),
        ("control-pull", len(order) > 1 and "/control" in order[1]),
        ("selected-gradle", len(order) > 2 and "," in order[2]),
        ("selected-pull", len(order) > 3 and "/selected" in order[3]),
    ]
    check(r, "R1 kolejność kontrolki->odbiór->selected->odbiór zachowana",
          all(v for _, v in shape), f"{shape} order={order[:4]}")
    check(r, "R2 happy path kończy się rc=0 i test_exit/collection_exit=0",
          happy["rc"] == 0 and happy["codes"].get("test_exit") == "0"
          and happy["codes"].get("collection_exit") == "0",
          f"rc={happy['rc']} codes={happy['codes']}")
    check(r, "R3 lista klas przepisana bez zmian do selected-classes.txt",
          (happy["out"] / "selected-classes.txt").read_text(encoding="utf-8").strip() == classes.strip(),
          "selected-classes.txt")
    check(r, "R4 scope.txt nadal deklaruje NOT_EXECUTED dla czytnika",
          "NOT_EXECUTED" in (happy["out"] / "scope.txt").read_text(encoding="utf-8"),
          "scope.txt")
    cg = run_case(sandbox, "control_fail", script, classes, FAKE_GRADLE_CONTROL_EXIT=1)
    check(r, "R5 bramka: padnięta kontrolka nie wpuszcza selected",
          cg["rc"] != 0 and not cg["selected_ran"] and cg["codes"].get("control_test_exit") == "1",
          f"rc={cg['rc']} codes={cg['codes']}")
    sf = run_case(sandbox, "selected_fail", script, classes, FAKE_GRADLE_SELECTED_EXIT=1)
    check(r, "R6 padnięta próbka: rc!=0, ale odbiór selected wykonany",
          sf["rc"] != 0 and sf["codes"].get("test_exit") == "1"
          and (sf["out"] / "selected-logcat.txt").is_file(),
          f"rc={sf['rc']} codes={sf['codes']}")

    width = max(len(n) for n, _, _ in r)
    for name, ok, detail in r:
        print(f"[{'PASS' if ok else 'FAIL'}] {name.ljust(width)}  {detail}")
    failed = [n for n, ok, _ in r if not ok]
    print(f"\nrazem={len(r)} pass={len(r) - len(failed)} fail={len(failed)}")
    if failed:
        print("NIEZDANE: " + "; ".join(failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
