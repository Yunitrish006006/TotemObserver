#!/usr/bin/env python3
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import subprocess
import unittest
import zipfile
from email.parser import BytesParser
from email.policy import default

spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-modrinth.py'))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


class FakeClient:
    def __init__(self):
        self.calls = []
        self.status = 'processing'
        self.permission = 1
        self.versions = []
        self.remote = None

    def request(self, path, data=None, content_type=None):
        self.calls.append((path, data))
        if path == '/project/observer':
            return dict(id='project', team='team', title='TotemObserver', project_type='mod', status=self.status)
        if path == '/user':
            return {'id': 'user', 'email': 'private-sentinel'}
        if path == '/team/team/members':
            return [dict(user={'id': 'user'}, accepted=True, permissions=self.permission)]
        if path.startswith('/project/project/version'):
            return self.versions
        if path == '/version':
            message = BytesParser(policy=default).parsebytes(('Content-Type: ' + content_type + '\r\nMIME-Version: 1.0\r\n\r\n').encode() + data)
            parts = list(message.iter_parts())
            metadata = json.loads(parts[0].get_payload(decode=True))
            file_bytes = parts[1].get_payload(decode=True)
            self.remote = metadata | {'id': 'version', 'files': [dict(filename=parts[1].get_filename(), primary=True,
                           hashes={'sha512': p.hashlib.sha512(file_bytes).hexdigest()})]}
            return self.remote
        if path == '/version/version':
            return self.remote
        raise AssertionError(path)


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'build/libs').mkdir(parents=True)
        (self.root / '.github/staging').mkdir(parents=True)
        (self.root / 'gradle.properties').write_text('mod_version=0.1.0\nminecraft_version=26.2\n')
        (self.root / '.github/staging/modrinth-changelog-0.1.0.md').write_text('Test; 中文 "quoted"\nsecond line')
        self.artifact = self.root / 'build/libs/totem-observer-0.1.0.jar'
        with zipfile.ZipFile(self.artifact, 'w') as z:
            z.writestr('fabric.mod.json', json.dumps(dict(id=p.MODULE, version='0.1.0',
                depends={'minecraft': '~26.2', 'totem-core': '>=0.7.18 <0.8.0'},
                breaks={'totem-vanilla-tweaks': '<=0.1.27'}, icon='icon.png')))
            z.writestr('icon.png', b'fixture')
        self.client = FakeClient()

    def test_pending_project_dry_run_is_read_only_and_sanitized(self):
        result = p.run(self.root, self.client, 'observer')
        self.assertTrue(result['dry_run'])
        self.assertTrue(result['upload_team_permission'])
        self.assertTrue(all(data is None for _, data in self.client.calls))
        self.assertNotIn('private-sentinel', (self.root / 'build/modrinth-release/validation.json').read_text())
        self.assertEqual(result['token_version_create_scope'], 'not_proven_by_read_only_checks')

    def test_no_upload_permission_rejected_without_write(self):
        self.client.permission = 2
        with self.assertRaisesRegex(ValueError, 'UPLOAD_VERSION'):
            p.run(self.root, self.client, 'observer', True)
        self.assertTrue(all(data is None for _, data in self.client.calls))

    def test_upload_multipart_roundtrip_and_readback(self):
        result = p.run(self.root, self.client, 'observer', True)
        self.assertEqual(result['modrinth_version_id'], 'version')
        self.assertEqual(self.client.remote['changelog'], 'Test; 中文 "quoted"\nsecond line')
        self.assertEqual(sum(data is not None for _, data in self.client.calls), 1)
        self.assertEqual(self.client.calls[-1][0], '/version/version')

    def test_identical_existing_version_is_idempotent(self):
        p.run(self.root, self.client, 'observer', True)
        self.client.calls.clear()
        self.client.versions = [{'id': 'version', 'version_number': '0.1.0'}]
        result = p.run(self.root, self.client, 'observer', True)
        self.assertTrue(result['existing_identical_version'])
        self.assertTrue(all(data is None for _, data in self.client.calls))

    def test_changed_existing_artifact_rejected(self):
        p.run(self.root, self.client, 'observer', True)
        self.client.versions = [{'id': 'version', 'version_number': '0.1.0'}]
        self.client.remote['files'][0]['hashes']['sha512'] = 'wrong'
        with self.assertRaisesRegex(ValueError, 'SHA-512'):
            p.run(self.root, self.client, 'observer')

    def test_dependency_normalization_and_incorrect_dependencies(self):
        deps = [{'project_id': p.FABRIC, 'dependency_type': 'required'},
                {'file_name': p.CORE_FILE, 'dependency_type': 'required'}]
        self.assertTrue(p.dependencies_valid(deps))
        deps[1]['file_name'] = None
        self.assertTrue(p.dependencies_valid(deps))
        deps[1]['file_name'] = 'wrong.jar'
        self.assertFalse(p.dependencies_valid(deps))
        self.assertFalse(p.dependencies_valid(deps[:1]))

    def test_invalid_project_reference_is_not_requested(self):
        with self.assertRaisesRegex(ValueError, 'MODRINTH_PROJECT_ID'):
            p.run(self.root, self.client, 'other/path')
        self.assertEqual(self.client.calls, [])

    def test_missing_changelog_rejected(self):
        (self.root / '.github/staging/modrinth-changelog-0.1.0.md').unlink()
        with self.assertRaises(OSError):
            p.run(self.root, self.client, 'observer')
        self.assertEqual(self.client.calls, [])

    def test_cli_upload_requires_main_and_exact_commit_gates(self):
        script = str(Path(__file__).with_name('publish-modrinth.py'))
        for ref, gate in [('refs/heads/release/test', 'abc'), ('refs/heads/main', 'wrong')]:
            result = subprocess.run(['python3', script, '--publish'], cwd=self.root,
                env={**os.environ, 'GITHUB_REF': ref, 'GITHUB_SHA': 'abc',
                     'OBSERVER_RELEASE_GATES': gate}, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Release validation failed:', result.stderr)


if __name__ == '__main__':
    unittest.main()
