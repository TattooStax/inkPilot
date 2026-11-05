#!/usr/bin/env bash
# On a disposable emulator with current debug app/test APKs installed, prove
# JobScheduler repairs an interrupted reminder enqueue while no Activity is open.
set -euo pipefail
if [[ $# -ne 1 || ! $1 =~ ^emulator-[0-9]+$ ]]; then
  echo 'Usage: inkpilot-calendar-closed-app.sh emulator-PORT' >&2
  exit 2
fi
serial=$1
package=app.inkpilot.android.debug
sdk=$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')
namespace=()
# WorkManager uses a JobScheduler namespace on Android 14 and newer.
if (( sdk >= 34 )); then namespace=(-n androidx.work.systemjobscheduler); fi
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || {
  echo 'This test requires a disposable emulator.' >&2; exit 2;
}
adb -s "$serial" shell pm path "$package" >/dev/null
adb -s "$serial" shell pm path "$package.test" >/dev/null
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT

run_class() {
  local class=$1
  if ! timeout 600 adb -s "$serial" shell am instrument -w -e class \
      "org.fossify.messages.inkpilot.studio.$class" \
      "$package.test/androidx.test.runner.AndroidJUnitRunner" > "$temporary/result" 2>&1 ||
      ! python3 scripts/inkpilot-device-result.py "$temporary/result" >/dev/null 2>&1; then
    echo "FAIL $class (private instrumentation output retained only until exit)" >&2
    exit 1
  fi
  echo "PASS $class"
}

# WorkManager moved its database into no_backup on recent Android versions.
# Snapshot it only after the preparation instrumentation has exited.
snapshot_work() {
  local location
  for location in no_backup/androidx.work.workdb databases/androidx.work.workdb; do
    if adb -s "$serial" exec-out run-as "$package" cat "$location" > "$temporary/work.db" 2>/dev/null; then
      adb -s "$serial" exec-out run-as "$package" cat "$location-wal" > "$temporary/work.db-wal" 2>/dev/null || :
      return 0
    fi
  done
  echo 'WorkManager database unavailable.' >&2
  return 1
}

run_class StudioReminderPrecommitTest
run_class StudioReminderClosedAppPrepareTest
# The shell baseline may have left inkPilot's Activity visible. Background it
# before `am kill`; a foreground process would be intentionally spared.
adb -s "$serial" shell input keyevent KEYCODE_HOME
adb -s "$serial" shell am kill "$package"
[[ -z $(adb -s "$serial" shell pidof "$package" 2>/dev/null | tr -d '\r') ]] || {
  echo 'App process remained alive after am kill.' >&2; exit 1;
}
adb -s "$serial" exec-out run-as "$package" \
  cat shared_prefs/studio_reminder_closed_app.xml > "$temporary/fixture.xml"
reminder_id=$(python3 - "$temporary/fixture.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
print(next(node.attrib['value'] for node in ET.parse(sys.argv[1]).getroot()
           if node.attrib.get('name') == 'id'))
PY
)
snapshot_work
job_id=$(python3 - "$temporary/work.db" <<'PY'
import sqlite3, sys
db = sqlite3.connect(f'file:{sys.argv[1]}?mode=ro', uri=True)
row = db.execute('''SELECT system_id FROM SystemIdInfo JOIN WorkName
    ON SystemIdInfo.work_spec_id=WorkName.work_spec_id
    JOIN WorkSpec ON WorkSpec.id=WorkName.work_spec_id
    WHERE WorkName.name='studio-calendar-recovery-periodic' AND WorkSpec.state=0 ''').fetchone()
if row is None: raise SystemExit('Periodic recovery has no JobScheduler registration')
print(row[0])
PY
)

# Force only the already persisted periodic JobScheduler job. This starts the
# Worker process, but does not launch the app's UI or register new work in test code.
adb -s "$serial" shell cmd jobscheduler run -f "${namespace[@]}" "$package" "$job_id" >/dev/null
requeued=0
for _ in $(seq 1 30); do
  sleep 1
  if snapshot_work && python3 - "$temporary/work.db" "$reminder_id" <<'PY' >/dev/null 2>&1
import sqlite3, sys
db = sqlite3.connect(f'file:{sys.argv[1]}?mode=ro', uri=True)
name = f'studio-reminder:{sys.argv[2]}:0'
rows = db.execute('''SELECT WorkSpec.state FROM WorkName JOIN WorkSpec
    ON WorkName.work_spec_id=WorkSpec.id WHERE WorkName.name=? AND WorkSpec.state=0''', (name,)).fetchall()
assert rows == [(0,)], rows  # One ENQUEUED job for the current revision.
PY
  then requeued=1; break; fi
done
[[ $requeued == 1 ]] || { echo 'FAIL: closed-app wakeup did not persist one reminder.' >&2; exit 1; }
echo 'PASS OS-dispatched recovery with the staff Activity closed'
run_class StudioReminderClosedAppVerifyTest
