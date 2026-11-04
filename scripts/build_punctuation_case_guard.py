#!/usr/bin/env python3
"""Pin dictionary-confirmed ordinary verbs/adverbs, independently of QA outputs."""
from pathlib import Path
import hashlib
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    sys.path.insert(0, str(ROOT / '.cache/morphology'))
    import pymorphy3
    morphology = pymorphy3.MorphAnalyzer()
    assets = ROOT / 'android/app/src/main/assets'
    common = assets / 'common'
    metadata = json.loads((common / 'manifest.json').read_text())
    source = ROOT / 'data/frequency/ru-2018-50k.txt'
    digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    assert digest(source) == metadata['source_sha256']
    dictionary = Path(morphology.dictionary.path)
    assert digest(dictionary / 'words.dawg') == json.loads((assets / 'context/manifest.json').read_text())['morphology']['sha256']
    words = set()
    for line in source.read_text().splitlines():
        word = line.split()[0]
        if not re.fullmatch('[а-яё]{3,28}', word): continue
        parses = morphology.parse(word)
        if parses and all(p.is_known and p.tag.POS in {'VERB', 'INFN', 'ADVB', 'GRND'} and
                not set(p.tag.grammemes) & {'Name', 'Surn', 'Patr', 'Geox', 'Orgn', 'Trad', 'Abbr'} for p in parses):
            words.add(word)
    path = common / 'punctuation_lowercase.txt'
    path.write_text(''.join(word + '\n' for word in sorted(words)))
    metadata['casing_guard'] = {
        'file': path.name, 'words': len(words), 'sha256': digest(path),
        'license': 'CC BY-SA 3.0', 'source': 'Pinned FrequencyWords Russian and OpenCorpora via pymorphy3-dicts-ru 2.4.417150.4580142',
        'dictionary_files': {name: digest(dictionary / name) for name in
                             ('words.dawg', 'gramtab-opencorpora-int.json', 'paradigms.array')},
        'rule': 'Every dictionary analysis must be known VERB/INFN/ADVB/GRND, without proper-name or abbreviation tags. No QA output selection.'}
    (common / 'manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    print('Compiled unambiguous ordinary-word casing guard:', len(words))


if __name__ == '__main__': main()
