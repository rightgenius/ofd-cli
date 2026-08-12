#!/usr/bin/env python3
"""
Merge native-image-agent reachability metadata from multiple directories
into a single reflect-config.json.

Usage: merge-reflect-config.py <output.json> <input-dir-1> [<input-dir-2> ...]
"""
import json
import os
import sys

def merge(dirs):
    reflection = {}
    resources = []
    proxies = []
    for d in dirs:
        path = os.path.join(d, 'reachability-metadata.json')
        if not os.path.exists(path):
            continue
        with open(path) as f:
            data = json.load(f)
        for entry in data.get('reflection', []):
            name = entry.get('type', entry.get('name'))
            if not name:
                continue
            e = reflection.setdefault(name, {'name': name})
            for k in ('methods', 'fields'):
                if k in entry:
                    e.setdefault(k, []).extend(entry[k])
            for k in ('allDeclaredMethods', 'allDeclaredConstructors', 'allDeclaredFields',
                     'allPublicMethods', 'allPublicConstructors', 'allPublicFields',
                     'queryAllPublicMethods', 'queryAllDeclaredMethods', 'unsafeAllocated'):
                if entry.get(k):
                    e[k] = True
            if 'condition' in entry:
                e['condition'] = entry['condition']
        # resources / proxies — only keep the resource bundles (no duplicates)
        for r in data.get('resources', []):
            if r not in resources:
                resources.append(r)
        for p in data.get('proxies', []):
            if p not in proxies:
                proxies.append(p)
    return list(reflection.values()), resources, proxies

def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    out = sys.argv[1]
    dirs = sys.argv[2:]
    refl, resources, proxies = merge(dirs)
    # native-image expects: a single JSON file with "resources": [...], "reflection": [...] when bundled together.
    # But reflect-config.json is a plain array. We emit a bundle and tell the user to split if needed.
    bundle = {'reflection': refl, 'resources': resources, 'proxies': proxies}
    with open(out, 'w') as f:
        json.dump(bundle, f, indent=2, ensure_ascii=False)
    print(f"Wrote {out}  reflection={len(refl)}  resources={len(resources)}  proxies={len(proxies)}")

if __name__ == '__main__':
    main()
