#!/usr/bin/env python3
"""Exercise wrapper process ownership without Xorg, root, or a graphical client."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest

WRAPPER = Path(__file__).with_name('run-glx-client.sh').resolve()


class GraphicsLifecycleTest(unittest.TestCase):
    def exercise(self, terminate):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = {
                'sudo': '#!/bin/sh\nexec "$@"\n',
                'xdpyinfo': '#!/bin/sh\nexit 0\n',
                'Xorg': '#!/usr/bin/env python3\nimport os,time\nfrom pathlib import Path\nPath(os.environ["TEST_XORG_PID"]).write_text(str(os.getpid()))\ntime.sleep(60)\n',
                'client': '#!/usr/bin/env python3\nimport os,time\nfrom pathlib import Path\nPath(os.environ["TEST_CLIENT_PID"]).write_text(str(os.getpid()))\nif os.environ["TEST_TERMINATE"] == "1": time.sleep(60)\nelse: time.sleep(.2); raise SystemExit(7)\n',
            }
            for name, content in scripts.items():
                file = root / name
                file.write_text(content)
                file.chmod(0o755)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       TMPDIR=str(root), TEST_XORG_PID=str(root / 'xorg.pid'),
                       TEST_CLIENT_PID=str(root / 'client.pid'),
                       TEST_TERMINATE='1' if terminate else '0')
            process = subprocess.Popen(['bash', str(WRAPPER), str(root / 'client')], env=env,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            tracked = []
            try:
                deadline = time.monotonic() + 5
                while not all((root / name).exists() for name in ['xorg.pid', 'client.pid']):
                    if process.poll() is not None or time.monotonic() > deadline:
                        self.fail('Wrapper did not start both owned processes')
                    time.sleep(.01)
                tracked = [int((root / name).read_text()) for name in ['xorg.pid', 'client.pid']]
                if terminate:
                    process.send_signal(signal.SIGTERM)
                _, error = process.communicate(timeout=5)
                self.assertEqual(process.returncode, 143 if terminate else 7, error.decode())
                for pid in tracked:
                    with self.assertRaises(ProcessLookupError):
                        os.kill(pid, 0)
                self.assertFalse(list(root.glob('tmp.*')), 'Owned Xorg configuration was retained')
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
                for pid in tracked:
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_term_reaps_client_and_xorg(self):
        self.exercise(True)

    def test_client_failure_preserves_status_and_reaps_xorg(self):
        self.exercise(False)


if __name__ == '__main__':
    unittest.main()
