#!/usr/bin/env python3
"""Host-only contract tests: the fake adb never contacts a device."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class RunnerContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'))
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        for name in ('run-inkpilot-test-class.sh', 'inkpilot-shell-baseline.sh',
                     'inkpilot-launcher-cold-start.sh', 'inkpilot-sms-role-preflight.sh', 'inkpilot-device-result.py'):
            shutil.copy2(ROOT / 'scripts' / name, self.root / 'scripts' / name)
        self.app = self.root / 'app/build/outputs/apk/foss/debug/app.apk'
        self.tests = self.root / 'app/build/outputs/apk/androidTest/foss/debug/test.apk'
        for apk in (self.app, self.tests):
            apk.parent.mkdir(parents=True)
            apk.touch()
        self.log = self.root / 'adb.log'
        adb = self.root / 'adb'
        adb.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['ADB_LOG'], 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
if os.environ.get('ADB_FAIL_COMMAND') in sys.argv[3:]: sys.exit(1)
if sys.argv[3:] == ['get-state']: print('device')
if sys.argv[3:] == ['shell', 'getprop', 'ro.kernel.qemu']:
    print(os.environ.get('FAKE_QEMU', '1'))
if sys.argv[3:] == ['shell', 'cmd', 'role', 'get-role-holders', '--user', '0', 'android.app.role.SMS']:
    print('app.inkpilot.android.debug')
if sys.argv[3:] == ['shell', 'appops', 'get', 'app.inkpilot.android.debug', 'WRITE_SMS']:
    print('Uid mode: WRITE_SMS: allow')
if 'query-activities' in sys.argv:
    print('app.inkpilot.android.debug/app.inkpilot.android.activities.SplashActivity.Green')
if sys.argv[3:7] == ['shell', 'am', 'start', '-W']:
    print('Status: ok')
if 'instrument' in sys.argv:
    if os.environ.get('FAKE_FAILED_CLASS') in sys.argv:
        print('FAILURES!!!')
        sys.exit(0)
    print(os.environ.get('INSTRUMENT_RESULT', 'OK (1 test)'))
    sys.exit(int(os.environ.get('ADB_EXIT', '0')))
''')
        adb.chmod(0o755)
        self.env = dict(os.environ, PATH=f'{self.root}:' + os.environ['PATH'], ADB_LOG=str(self.log), TMPDIR=str(self.root))

    def tearDown(self):
        self.temp.cleanup()

    def run_runner(self, *args):
        return subprocess.run(['bash', 'scripts/run-inkpilot-test-class.sh', *args],
                              cwd=self.root, env=self.env, capture_output=True, text=True)

    def calls(self):
        import json
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_rejects_invalid_arguments_before_adb(self):
        for args in [(), ('emulator-5554',), ('192.168.40.149:5555', 'a.b.Test'),
                     ('emulator-x', 'a.b.Test'), ('emulator-5554', 'a.b.Test#method'),
                     ('emulator-5554', 'a.b.Test,a.b.Other'), ('emulator-5554', '-bad'),
                     ('emulator-5554', 'a.b.Test', 'extra')]:
            with self.subTest(args=args):
                self.assertNotEqual(0, self.run_runner(*args).returncode)
                self.assertEqual([], self.calls())

    def test_missing_or_ambiguous_apks_never_contact_adb(self):
        self.app.unlink()
        self.assertNotEqual(0, self.run_runner('emulator-5554', 'a.b.Test').returncode)
        self.app.touch()
        self.tests.with_name('extra.apk').touch()
        self.assertNotEqual(0, self.run_runner('emulator-5554', 'a.b.Test').returncode)
        self.assertEqual([], self.calls())

    def test_only_exact_serial_installs_and_exact_class(self):
        result = self.run_runner('emulator-5554', 'a.b.Test')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([
            ['-s', 'emulator-5554', 'get-state'],
            ['-s', 'emulator-5554', 'shell', 'getprop', 'ro.kernel.qemu'],
            ['-s', 'emulator-5554', 'install', '-r', str(self.app.relative_to(self.root))],
            ['-s', 'emulator-5554', 'install', '-r', str(self.tests.relative_to(self.root))],
            ['-s', 'emulator-5554', 'get-state'],
            ['-s', 'emulator-5554', 'shell', 'getprop', 'ro.kernel.qemu'],
            ['-s', 'emulator-5554', 'shell', 'cmd', 'role', 'get-role-holders', '--user', '0', 'android.app.role.SMS'],
            ['-s', 'emulator-5554', 'shell', 'appops', 'get', 'app.inkpilot.android.debug', 'WRITE_SMS'],
            ['-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-e', 'class',
             'a.b.Test', 'app.inkpilot.android.debug.test/androidx.test.runner.AndroidJUnitRunner'],
        ], self.calls())

    def test_spoofed_emulator_serial_never_installs_on_physical_device(self):
        self.env['FAKE_QEMU'] = '0'
        self.assertNotEqual(0, self.run_runner('emulator-5554', 'a.b.Test').returncode)
        self.assertFalse(any('install' in call or 'instrument' in call for call in self.calls()))

    def test_transport_or_install_failure_stops_before_instrumentation(self):
        for command in ('get-state', 'install'):
            with self.subTest(command=command):
                self.env['ADB_FAIL_COMMAND'] = command
                self.assertNotEqual(0, self.run_runner('emulator-5554', 'a.b.Test').returncode)
                self.assertFalse(any('instrument' in call for call in self.calls()))

    def test_failed_retry_replaces_previous_passing_evidence(self):
        self.assertEqual(0, self.run_runner('emulator-5554', 'a.b.Test').returncode)
        self.env['ADB_FAIL_COMMAND'] = 'install'
        self.assertNotEqual(0, self.run_runner('emulator-5554', 'a.b.Test').returncode)
        verdict = self.root / 'app/build/test-class-evidence/a.b.Test/result.txt'
        self.assertTrue(verdict.read_text().startswith('FAIL '))

    def test_raw_assertion_content_is_not_published(self):
        self.env['INSTRUMENT_RESULT'] = ("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: synthetic-secret-body\n"
            "\tat org.fossify.messages.inkpilot.smoke.ShellBaselineTest.shellOwnsThread(ShellBaselineTest.kt:84)\n"
            "FAILURES!!! synthetic-secret-body")
        result = self.run_runner('emulator-5554', 'a.b.Test')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('synthetic-secret-body', result.stdout + result.stderr)
        verdict = self.root / 'app/build/test-class-evidence/a.b.Test/result.txt'
        self.assertNotIn('synthetic-secret-body', verdict.read_text())
        self.assertIn('ShellBaselineTest.kt:84', verdict.read_text())

    def test_failed_method_name_is_reported_without_assertion_text(self):
        self.env['INSTRUMENT_RESULT'] = (
            "INSTRUMENTATION_STATUS: test=safeRouteCheck\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            "INSTRUMENTATION_STATUS: test=exactSettingsRoute\n"
            "INSTRUMENTATION_STATUS: stack=private-client-body\n"
            "\tat a.b.ShellIngressTest.exactSettingsRoute(ShellIngressTest.kt:42)\n"
            "INSTRUMENTATION_STATUS_CODE: -2\n"
            "FAILURES!!! private-client-body"
        )
        result = self.run_runner('emulator-5554', 'a.b.Test')
        self.assertNotEqual(0, result.returncode)
        verdict = (self.root / 'app/build/test-class-evidence/a.b.Test/result.txt').read_text()
        self.assertIn('a.b.Test#exactSettingsRoute (ShellIngressTest.kt:42', verdict)
        self.assertNotIn('private-client-body', verdict + result.stdout + result.stderr)

    def test_summary_method_fallback_is_redacted(self):
        self.env['INSTRUMENT_RESULT'] = (
            'INSTRUMENTATION_STATUS: stack=private-client-body\n'
            'Error in historicalPendingIntentAndPreferencesResolveIntoShell(a.b.ShellIngressTest):\n'
            '\tat a.b.ShellIngressTest.awaitRenderedTitle(ShellIngressTest.kt:42)\n'
            'FAILURES!!! private-client-body'
        )
        result = self.run_runner('emulator-5554', 'a.b.Test')
        self.assertNotEqual(0, result.returncode)
        verdict = (self.root / 'app/build/test-class-evidence/a.b.Test/result.txt').read_text()
        self.assertIn('a.b.Test#historicalPendingIntentAndPreferencesResolveIntoShell (ShellIngressTest.kt:42', verdict)
        self.assertNotIn('private-client-body', verdict + result.stdout + result.stderr)

    def test_shell_keeps_later_class_evidence_after_an_assertion_failure(self):
        first = 'org.fossify.messages.inkpilot.data.LegacyFirstLaunchTest'
        last = 'org.fossify.messages.transport.HeartbeatAlarmCleanupTest'
        self.env['FAKE_FAILED_CLASS'] = first
        result = subprocess.run(['bash', 'scripts/inkpilot-shell-baseline.sh'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=120)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn(first, result.stderr)
        self.assertTrue((self.root / f'app/build/test-class-evidence/{first}/result.txt')
                        .read_text().startswith('FAIL '))
        self.assertEqual(f'PASS {last}\n', (self.root / f'app/build/test-class-evidence/{last}/result.txt').read_text())
        instruments = [call for call in self.calls() if 'instrument' in call]
        self.assertIn(first, instruments[0])
        self.assertIn(last, instruments[-1])

    def test_shell_stops_on_instrumentation_timeout(self):
        fake_timeout = self.root / 'timeout'
        fake_timeout.write_text('#!/bin/sh\nexit 124\n')
        fake_timeout.chmod(0o755)
        result = subprocess.run(['bash', 'scripts/inkpilot-shell-baseline.sh'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=30)
        self.assertEqual(2, result.returncode, result.stderr)
        first = 'org.fossify.messages.inkpilot.data.LegacyFirstLaunchTest'
        last = 'org.fossify.messages.transport.HeartbeatAlarmCleanupTest'
        self.assertIn('instrumentation timeout', (self.root / f'app/build/test-class-evidence/{first}/result.txt').read_text())
        self.assertFalse((self.root / f'app/build/test-class-evidence/{last}/result.txt').exists())

    def test_shell_forcibly_restarts_between_media_phases(self):
        result = subprocess.run(['bash', 'scripts/inkpilot-shell-baseline.sh'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=120)
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        prepare = 'org.fossify.messages.inkpilot.smoke.ShellComposerMediaPrepareTest'
        verify = 'org.fossify.messages.inkpilot.smoke.ShellComposerMediaVerifyTest'
        selected = [call for call in calls if 'instrument' in call or 'force-stop' in call]
        first = next(index for index, call in enumerate(selected) if prepare in call)
        self.assertEqual(['-s', 'emulator-5554', 'shell', 'am', 'force-stop',
                          'app.inkpilot.android.debug'], selected[first + 1])
        self.assertIn(verify, selected[first + 2])

    def test_failures_are_nonzero(self):
        for output, code in [('FAILURES!!!', '0'), ('OK (0 tests)', '0'),
                             ('INSTRUMENTATION_FAILED', '0'), ('', '0'), ('OK (1 test)', '1')]:
            with self.subTest(output=output, code=code):
                self.env.update(INSTRUMENT_RESULT=output, ADB_EXIT=code)
                self.assertNotEqual(0, self.run_runner('emulator-5554', 'a.b.Test').returncode)


if __name__ == '__main__':
    unittest.main()
