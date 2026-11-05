#!/usr/bin/env python3
"""Copies the iOS app's string tables into assets/i18n/<lang>/<Table>.json.

The iOS app (../PawMiawNegocios) keeps one table per module (Localizable for the
shared pieces, Schedule, Clients, …) keyed by the English sentence. Android reads
the same tables: run this after the iOS tables change, then add Android-only keys
to the JSON. Run from the repo root:  python3 scripts/strings-from-ios.py ../PawMiawNegocios
"""
import json, os, subprocess, sys, glob
IOS = sys.argv[1] if len(sys.argv) > 1 else '../PawMiawNegocios'
for lang in ('es', 'en'):
    out = os.path.join('app/src/main/assets/i18n', lang)
    os.makedirs(out, exist_ok=True)
    for path in sorted(glob.glob(os.path.join(IOS, 'PawMiawNegocios', f'{lang}.lproj', '*.strings'))):
        name = os.path.basename(path)[:-len('.strings')]
        if name == 'InfoPlist':
            continue
        raw = subprocess.run(['plutil', '-convert', 'json', '-o', '-', path], capture_output=True, text=True, check=True).stdout
        table = json.loads(raw) if raw.strip() else {}
        target = os.path.join(out, name + '.json')
        # Keys added on Android stay (the iOS table wins where both have one).
        if os.path.exists(target):
            mine = json.load(open(target))
            mine.update(table)
            table = mine
        json.dump(table, open(target, 'w'), ensure_ascii=False, indent=1, sort_keys=True)
        print(lang, name, len(table))
