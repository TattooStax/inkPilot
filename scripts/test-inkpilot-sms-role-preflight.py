#!/usr/bin/env python3
"""Exercise the emulator-only role/app-op boundary with a fake adb."""
import os
import subprocess
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name('inkpilot-sms-role-preflight.sh')
FAKE_ADB = '''#!/usr/bin/env python3
import os
import sys
from pathlib import Path
args = sys.argv[1:]
assert args[:2] == ['-s', 'emulator-5554'], args
args = args[2:]
state = Path(os.environ['FAKE_OP_STATE_FILE'])
if args == ['get-state']:
    print('device')
elif args == ['shell', 'getprop', 'ro.kernel.qemu']:
    print(os.environ.get('FAKE_QEMU', '1'))
elif args == ['shell', 'cmd', 'role', 'get-role-holders', '--user', '0', 'android.app.role.SMS']:
    print(os.environ.get('FAKE_ROLE', 'app.inkpilot.android.debug'))
elif args == ['shell', 'appops', 'get', 'app.inkpilot.android.debug', 'WRITE_SMS']:
    if os.environ.get('FAKE_UID_MODE', 'allow') != 'missing':
        print('Uid mode: WRITE_SMS: ' + os.environ.get('FAKE_UID_MODE', 'allow'))
    if state.read_text() != 'none':
        print('WRITE_SMS: ' + state.read_text() + '; time=+1s ago')
elif args == ['shell', 'appops', 'set', 'app.inkpilot.android.debug', 'WRITE_SMS', 'default']:
    state.write_text('default')
else:
    raise SystemExit('unexpected adb command: ' + repr(args))
'''


def probe(initial, **overrides):
    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        (directory / 'adb').write_text(FAKE_ADB)
        (directory / 'adb').chmod(0o755)
        (directory / 'sleep').write_text('#!/bin/sh\nexit 0\n')
        (directory / 'sleep').chmod(0o755)
        state = directory / 'op-state'
        state.write_text(initial)
        env = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ['PATH'],
                   FAKE_OP_STATE_FILE=str(state), **overrides)
        run = subprocess.run([str(SCRIPT), 'emulator-5554', 'app.inkpilot.android.debug'],
                             env=env, capture_output=True, text=True, check=False)
        return run, state.read_text()


normal, state = probe('none')
assert normal.returncode == 0 and state == 'none', normal.stderr
for permitted_mode in ('allow', 'default'):
    permitted, state = probe(permitted_mode)
    assert permitted.returncode == 0 and state == permitted_mode, permitted.stderr
stale, state = probe('ignore')
assert stale.returncode == 0 and state == 'default', stale.stderr
wrong_role, state = probe('ignore', FAKE_ROLE='com.google.android.apps.messaging')
assert wrong_role.returncode == 1 and state == 'ignore', wrong_role.stderr
missing_uid, state = probe('allow', FAKE_UID_MODE='missing')
assert missing_uid.returncode == 1 and state == 'allow', missing_uid.stderr
denied_uid, state = probe('allow', FAKE_UID_MODE='ignore')
assert denied_uid.returncode == 1 and state == 'allow', denied_uid.stderr
for denied_mode in ('errored', 'foreground'):
    denied, state = probe(denied_mode)
    assert denied.returncode == 1 and state == denied_mode, denied.stderr
physical, state = probe('ignore', FAKE_QEMU='0')
assert physical.returncode == 2 and state == 'ignore', physical.stderr
print('PASS SMS role preflight: normal, stale override, wrong role, missing/denied UID, forbidden package modes, non-emulator')
