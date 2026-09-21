# -*- coding: utf-8 -*-
"""Fetch the bundled Agent Skills into the app assets.

These land in `app/src/main/assets/skills/<name>/...` and are seeded into
`<filesDir>/skills/` once on first run (see `BundledSkills`). The skill bodies
are third-party content, so the upstream commit sha is recorded in
`assets/skills/BUNDLED.json` for auditability.

Usage: python tools/fetch_bundled_skills.py [name ...]
       (no names = every skill listed in BUNDLED_SKILLS)
"""
from __future__ import print_function

import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.normpath(
    os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'skills'))

REPO = 'anthropics/skills'
REF = 'main'

# name -> path inside the upstream repo
BUNDLED_SKILLS = {
    'skill-creator': 'skills/skill-creator',
}


def api(url):
    request = urllib.request.Request(url, headers={
        'Accept': 'application/vnd.github+json',
        'User-Agent': 'memo-bundled-skills',
    })
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read().decode('utf-8')


def raw(path):
    url = 'https://raw.githubusercontent.com/%s/%s/%s' % (REPO, REF, path)
    request = urllib.request.Request(url, headers={'User-Agent': 'memo-bundled-skills'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def list_files(dir_path):
    """Recursively list blob paths under dir_path."""
    out = []
    for entry in json.loads(api(
            'https://api.github.com/repos/%s/contents/%s?ref=%s' % (REPO, dir_path, REF))):
        if entry['type'] == 'dir':
            out.extend(list_files(entry['path']))
        elif entry['type'] == 'file':
            out.append(entry['path'])
    return out


def fetch_skill(name, repo_path):
    target_root = os.path.join(ASSETS, name)
    paths = list_files(repo_path)
    if not any(p.endswith('/SKILL.md') for p in paths):
        raise SystemExit('%s has no SKILL.md' % repo_path)

    for path in paths:
        relative = path[len(repo_path):].lstrip('/')
        target = os.path.join(target_root, relative.replace('/', os.sep))
        parent = os.path.dirname(target)
        if parent and not os.path.isdir(parent):
            os.makedirs(parent)
        with open(target, 'wb') as handle:
            handle.write(raw(path))
    print('%s: %d files -> %s' % (name, len(paths), target_root))
    return paths


def main():
    wanted = sys.argv[1:] or sorted(BUNDLED_SKILLS)
    manifest = {}
    manifest_path = os.path.join(ASSETS, 'BUNDLED.json')
    if os.path.isfile(manifest_path):
        with open(manifest_path, 'r') as handle:
            manifest = json.load(handle)

    head = json.loads(api('https://api.github.com/repos/%s/commits/%s' % (REPO, REF)))
    commit = head['sha']

    for name in wanted:
        repo_path = BUNDLED_SKILLS.get(name)
        if repo_path is None:
            raise SystemExit('unknown bundled skill: %s' % name)
        files = fetch_skill(name, repo_path)
        manifest[name] = {
            'repo': REPO,
            'path': repo_path,
            'ref': REF,
            'commit': commit,
            'files': sorted(files),
        }

    if not os.path.isdir(ASSETS):
        os.makedirs(ASSETS)
    with open(manifest_path, 'w') as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write('\n')
    print('manifest -> %s (commit %s)' % (manifest_path, commit[:12]))


if __name__ == '__main__':
    main()
