#!/usr/bin/env bash
# Disposable emulator only. Android's SMS role grants WRITE_SMS at the UID
# level; a stale package override can still silently reject provider inserts.
set -euo pipefail
if [[ $# -ne 2 || ! $1 =~ ^emulator-[0-9]+$ || ! $2 =~ ^[A-Za-z_][A-Za-z0-9_.]*$ ]]; then
  echo 'Usage: inkpilot-sms-role-preflight.sh emulator-PORT package.name' >&2
  exit 2
fi
serial=$1
package=$2
adb -s "$serial" get-state | tr -d '\r' | grep -Fx device > /dev/null
if [[ $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') != 1 ]]; then
  echo 'SMS role preflight is only for a disposable emulator' >&2
  exit 2
fi
role=$(adb -s "$serial" shell cmd role get-role-holders --user 0 android.app.role.SMS | tr -d '\r')
if [[ $role != "$package" ]]; then
  echo 'SMS role holder does not match the test package' >&2
  exit 1
fi

# The role's UID allowance is authoritative. A package-level ignore can
# survive an install/role transition and override it on a disposable emulator.
# Wait briefly for the platform's asynchronous role grant, then clear only
# that contradictory package override. Missing UID allowance still fails.
uid_allowed() {
  printf '%s\n' "$1" | grep -Eq '^Uid mode: WRITE_SMS: allow([;[:space:]]|$)'
}
package_mode() {
  printf '%s\n' "$1" | sed -nE 's/^WRITE_SMS: ([A-Za-z_]+).*/\1/p' | head -n 1
}
for attempt in {1..20}; do
  ops=$(adb -s "$serial" shell appops get "$package" WRITE_SMS | tr -d '\r')
  if uid_allowed "$ops"; then
    mode=$(package_mode "$ops")
    if [[ $mode == ignore ]]; then
      adb -s "$serial" shell appops set "$package" WRITE_SMS default
      ops=$(adb -s "$serial" shell appops get "$package" WRITE_SMS | tr -d '\r')
      mode=$(package_mode "$ops")
    fi
    if uid_allowed "$ops"; then
      case "$mode" in
        ''|allow|default) exit 0 ;;
      esac
    fi
  fi
  sleep 0.25
done
echo 'SMS role is held but WRITE_SMS provider access is not allowed' >&2
exit 1
