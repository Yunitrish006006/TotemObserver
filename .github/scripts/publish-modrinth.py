#!/usr/bin/env python3
"""Validate or publish the Observer artifact; dry runs never mutate Modrinth."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
import uuid
import zipfile

API = 'https://api.modrinth.com/v2'
CORE_FILE = 'totem-core-0.7.18.jar'
FABRIC = 'P7dR8mSH'
MODULE = 'totem-observer'


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Client:
    def __init__(self, token):
        require(bool(token), 'MODRINTH_TOKEN is required')
        self.token = token

    def request(self, path, data=None, content_type=None):
        headers = {'Authorization': self.token,
                   'User-Agent': 'Yunitrish006006/TotemObserver-publisher/1.0'}
        if content_type:
            headers['Content-Type'] = content_type
        request = urllib.request.Request(API + path, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # Never print server bodies, credentials or authenticated user records.
            raise ValueError(f'Modrinth API returned HTTP {error.code}') from None
        except urllib.error.URLError:
            raise ValueError('Modrinth API connection failed') from None


def dependencies_valid(items):
    if not isinstance(items, list) or len(items) != 2:
        return False
    fabric = [d for d in items if d.get('project_id') == FABRIC
              and d.get('version_id') is None and d.get('file_name') is None
              and d.get('dependency_type') == 'required']
    core = [d for d in items if d.get('project_id') is None
            and d.get('version_id') is None and d.get('file_name') in (None, CORE_FILE)
            and d.get('dependency_type') == 'required']
    return len(fabric) == len(core) == 1


def verify_remote(remote, metadata, artifact, sha):
    for field in ('project_id', 'version_number', 'game_versions', 'loaders', 'version_type',
                  'status', 'environment', 'changelog'):
        require(remote.get(field) == metadata[field], f'Remote {field} mismatch')
    files = remote.get('files', [])
    require(bool(files), 'Remote version has no files')
    primary = next((f for f in files if f.get('primary')), files[0])
    require(primary.get('filename') == artifact.name, 'Remote filename mismatch')
    require(primary.get('hashes', {}).get('sha512') == sha, 'Remote SHA-512 mismatch')
    require(dependencies_valid(remote.get('dependencies')), 'Remote dependencies mismatch')


def multipart(metadata, artifact):
    boundary = 'observer-' + uuid.uuid4().hex
    # Build byte parts explicitly so changelog punctuation remains literal.
    parts = [(f'--{boundary}\r\nContent-Disposition: form-data; name="data"\r\n'
              'Content-Type: application/json\r\n\r\n').encode(),
             json.dumps(metadata, ensure_ascii=False).encode(), b'\r\n',
             (f'--{boundary}\r\nContent-Disposition: form-data; name="primary"; '
              f'filename="{artifact.name}"\r\nContent-Type: application/java-archive\r\n\r\n').encode(),
             artifact.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()]
    return b''.join(parts), 'multipart/form-data; boundary=' + boundary


def check_project(client, project_ref, output):
    project_ref = project_ref.strip().removeprefix('https://modrinth.com/mod/').removeprefix('https://modrinth.com/project/').rstrip('/')
    require(re.fullmatch(r'[A-Za-z0-9_-]+', project_ref), 'Invalid MODRINTH_PROJECT_ID')
    project = client.request('/project/' + project_ref)
    output.mkdir(parents=True, exist_ok=True)
    project_summary = {key: project.get(key) for key in
                       ('title', 'slug', 'project_type', 'status', 'requested_status')}
    project_summary['version_count'] = len(project.get('versions', []))
    (output / 'project-summary.json').write_text(json.dumps(project_summary, indent=2) + '\n')
    print(json.dumps(project_summary, indent=2))
    # An initial empty draft is returned as generic "project" until its first
    # loader-bearing version exists. Do not accept another established type.
    empty_draft = (project.get('project_type') == 'project'
                   and project.get('status') == 'draft' and project.get('versions') == [])
    require(project.get('project_type') == 'mod' or empty_draft,
            'Configured project is neither a mod nor an empty initial draft')
    require(project.get('title', '').replace(' ', '').lower() == 'totemobserver',
            'Configured project is not TotemObserver')
    require(project.get('status') in ('approved', 'processing', 'draft', 'unlisted', 'withheld'),
            'Project status does not permit this release workflow')
    user = client.request('/user')
    members = client.request('/team/' + project['team'] + '/members')
    require(any(m.get('user', {}).get('id') == user['id'] and m.get('accepted') is True
                and int(m.get('permissions') or 0) & 1 for m in members),
            'Authenticated member lacks accepted UPLOAD_VERSION permission')
    return project


def run(root, client, project_ref, publish=False):
    props = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines()
                 if '=' in line and not line.startswith('#'))
    version, minecraft = props['mod_version'], props['minecraft_version']
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version), 'Invalid release version')
    artifact = root / 'build/libs' / f'{MODULE}-{version}.jar'
    require(artifact.is_file(), 'Release JAR is missing')
    with zipfile.ZipFile(artifact) as jar:
        mod = json.loads(jar.read('fabric.mod.json'))
        require(mod['id'] == MODULE and mod['version'] == version, 'JAR identity mismatch')
        require(mod['depends']['minecraft'] == '~' + minecraft, 'Minecraft version mismatch')
        require(mod['depends']['totem-core'] == '>=0.7.18 <0.8.0', 'Core dependency mismatch')
        require(mod.get('breaks', {}).get('totem-vanilla-tweaks') == '<=0.1.27',
                'Observer extraction incompatibility is missing')
        require(mod.get('icon') in jar.namelist(), 'JAR icon is missing')
    changelog = (root / '.github/staging' / f'modrinth-changelog-{version}.md').read_text()
    require(bool(changelog.strip()), 'Release changelog is empty')
    project = check_project(client, project_ref, root / 'build/modrinth-release')
    versions = client.request('/project/' + project['id'] + '/version?include_changelog=false')
    matching = [v for v in versions if v.get('version_number') == version]
    require(len(matching) <= 1, 'Duplicate remote version numbers')
    sha = hashlib.sha512(artifact.read_bytes()).hexdigest()
    metadata = dict(name=f'{MODULE} {version}', version_number=version, changelog=changelog,
                    project_id=project['id'], game_versions=[minecraft], loaders=['fabric'],
                    version_type='release', featured=False, status='listed',
                    environment='client_and_server', file_parts=['primary'], primary_file='primary',
                    dependencies=[{'project_id': FABRIC, 'dependency_type': 'required'},
                                  {'file_name': CORE_FILE, 'dependency_type': 'required'}])
    if matching:
        remote = client.request('/version/' + matching[0]['id'])
        verify_remote(remote, metadata, artifact, sha)
    summary = dict(version=version, project_id=project['id'], project_status=project['status'],
                   requested_status=project.get('requested_status'), sha512=sha, file=artifact.name,
                   upload_team_permission=True, existing_identical_version=bool(matching),
                   dry_run=not publish, token_version_create_scope='not_proven_by_read_only_checks')
    output = root / 'build/modrinth-release'
    output.mkdir(parents=True, exist_ok=True)
    (output / 'validated-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    if publish:
        if not matching:
            data, content_type = multipart(metadata, artifact)
            remote = client.request('/version', data, content_type)
        remote = client.request('/version/' + remote['id'])
        verify_remote(remote, metadata, artifact, sha)
        summary.update(modrinth_version_id=remote['id'], token_version_create_scope='verified_by_upload' if not matching else 'not_exercised_existing_version')
    (output / 'validation.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--publish', action='store_true', help='Create version after CI gates; default is read-only')
    parser.add_argument('--check-project', action='store_true', help='Read-only authentication/project preflight without a JAR')
    args = parser.parse_args()
    require(not (args.publish and args.check_project), 'Select project preflight or publication')
    if args.check_project:
        check_project(Client(os.environ.get('MODRINTH_TOKEN', '')),
                      os.environ.get('MODRINTH_PROJECT_ID', ''), Path('build/modrinth-release'))
        return
    if args.publish:
        require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Publishing is restricted to main')
        require(os.environ.get('OBSERVER_RELEASE_GATES') == os.environ.get('GITHUB_SHA')
                and bool(os.environ.get('GITHUB_SHA')), 'Exact-commit release gates are required')
    run(Path('.'), Client(os.environ.get('MODRINTH_TOKEN', '')),
        os.environ.get('MODRINTH_PROJECT_ID', ''), args.publish)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        # Known local validation errors only; HTTP response bodies are never emitted.
        print(f'Release validation failed: {error}', file=sys.stderr)
        sys.exit(1)
