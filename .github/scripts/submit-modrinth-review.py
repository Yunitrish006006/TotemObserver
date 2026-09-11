"""Submit the verified Observer draft for moderation and record fresh project state."""
import json
import os
from pathlib import Path
import urllib.error
import urllib.request


def request(path, data=None):
    token = os.environ['MODRINTH_TOKEN']
    if not token:
        raise RuntimeError('MODRINTH_TOKEN is empty')
    req = urllib.request.Request('https://api.modrinth.com/v2/' + path,
                                 data=json.dumps(data).encode() if data is not None else None,
                                 headers={'Authorization': token, 'Content-Type': 'application/json',
                                          'User-Agent': 'Yunitrish006006/TotemObserver-review/1.0'},
                                 method='PATCH' if data is not None else 'GET')
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'Modrinth review request returned HTTP {error.code}') from None


version = next(line.split('=', 1)[1] for line in Path('gradle.properties').read_text().splitlines()
               if line.startswith('mod_version='))
marker = json.loads(Path(f'.github/staging/modrinth-published-{version}.json').read_text())
project_id = marker['project_id']
remote = request('version/' + marker['modrinth_version_id'])
assert remote['project_id'] == project_id and remote['version_number'] == version
assert any(f.get('primary') and f['hashes']['sha512'] == marker['sha512'] for f in remote['files'])
project = request('project/' + project_id)
assert project['source_url'] == 'https://github.com/Yunitrish006006/TotemObserver'
if project['status'] == 'draft':
    request('project/' + project_id, {'status': 'processing', 'requested_status': 'approved'})
project = request('project/' + project_id)
summary = {key: project.get(key) for key in ('id', 'slug', 'status', 'requested_status', 'queued')}
summary['version'] = version
summary['modrinth_version_id'] = marker['modrinth_version_id']
output = Path('build/modrinth-release')
output.mkdir(parents=True, exist_ok=True)
(output / 'review-status.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
if project['status'] not in ('processing', 'approved'):
    raise RuntimeError('Project is not in review or approved; inspect its moderation status')
