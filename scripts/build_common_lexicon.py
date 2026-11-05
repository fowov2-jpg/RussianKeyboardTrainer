#!/usr/bin/env python3
"""Compile fast unambiguous physical-error mappings for frequent dialogue words.

OpenSubtitles frequency is a public conversational proxy, not messenger telemetry.
No runtime edit search. All candidate strings are precomputed and ambiguity-filtered.
"""
import hashlib
import json
import re
import struct
import sys
from pathlib import Path
from build_android_lexicon import MAGIC, single_key_errors, write_table
from build_russian_typos import select_words, decode_dictionary, blob_hash, SOURCES

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'data/frequency/ru-2018-50k.txt'
SHA = '6095f507cc167488ec66ada5a85ac50433503a08ad24a07c6eabdf54352c4e7f'
REV = 'f8a65e6ddc17e0baa2e366a909986798d8dbe55b'
WORD = re.compile('[а-яё]{1,28}\\Z')


def frequencies():
    assert hashlib.sha256(SOURCE.read_bytes()).hexdigest() == SHA
    result = {}
    for line in SOURCE.read_text().splitlines():
        word, count = line.split()
        if WORD.fullmatch(word): result[word] = int(count)
    return result


def valid_form(word, morphology, analyzer):
    return word in morphology or ('е' in word or 'ё' in word) and analyzer.word_is_known(word)


def main():
    sys.path.insert(0, str(ROOT / '.cache/morphology'))
    import dawg_python
    import pymorphy3
    morphology = dawg_python.RecordDAWG('>HH').load(str(ROOT / 'android/app/src/main/assets/context/morphology.bin'))
    analyzer = pymorphy3.MorphAnalyzer()
    frequency = frequencies()
    words = []
    for word in frequency:
        if not 3 <= len(word) <= 20 or not valid_form(word, morphology, analyzer): continue
        parses = analyzer.parse(word)
        if any(p.score >= .2 and set(p.tag.grammemes) & {'Name', 'Surn', 'Patr'} for p in parses): continue
        words.append(word)
        if len(words) == 5000: break
    assert len(words) == 5000
    ids = {word: i for i, word in enumerate(words)}
    candidates = {}
    conflicting = set()
    for word in words:
        for raw in set(single_key_errors(word)):
            if not 3 <= len(raw) <= 28 or valid_form(raw, morphology, analyzer): continue
            if raw in candidates and candidates[raw] != word: conflicting.add(raw)
            else: candidates[raw] = word
    texts = []
    for name, filename in [('leeds', 'leeds.txt'), ('hunspell', 'ru_RU.dic')]:
        raw = (ROOT / '.cache/lexicon' / filename).read_bytes()
        assert blob_hash(raw) == SOURCES[name][1]
        texts.append(decode_dictionary(raw))
    _, known, _ = select_words(*texts)
    # Cover short valid Russian forms too; the legacy list starts at 4 letters.
    alphabet = 'абвгдеёжзийклмнопрстуфхцчшщъыьэюя'
    from itertools import product
    for length in (1, 2, 3):
        known.update(word for letters in product(alphabet, repeat=length)
                     if (word := ''.join(letters)) in morphology)
    known.update(word for word in frequency if valid_form(word, morphology, analyzer))
    known.update(words)
    eligible = set(candidates) - conflicting - known
    # Intentional colloquial abbreviations are not spelling mistakes.
    eligible.difference_update({'щас', 'ща', 'чё', 'че', 'спс', 'пж', 'пжл', 'лол', 'ага', 'угу', 'ок'})
    initial = len(eligible)
    for i, word in enumerate(sorted(known)):
        for raw in single_key_errors(word):
            if raw in eligible and candidates[raw] != word: eligible.remove(raw)
        if i % 100000 == 0: print(f'Ambiguity: {i}/{len(known)}; eligible {len(eligible)}', flush=True)
    out = ROOT / 'android/app/src/main/assets/common'
    out.mkdir(parents=True, exist_ok=True)
    protected_slang = {'щас', 'ща', 'чё', 'че', 'спс', 'пж', 'пжл', 'лол', 'ага', 'угу', 'ок'}
    keys = sorted(set(candidates) - known - protected_slang)
    path = out / 'russian.bin'
    temporary = path.with_suffix('.tmp')
    with temporary.open('wb') as stream:
        stream.write(MAGIC)
        write_table(stream, words)
        write_table(stream, sorted(words))
        write_table(stream, keys, [ids[candidates[key]] for key in keys], [int(key in eligible) for key in keys])
    temporary.replace(path)
    metadata = {'format': MAGIC.decode(), 'source': 'hermitdave/FrequencyWords, content/2018/ru/ru_50k.txt',
        'source_revision': REV, 'source_sha256': SHA, 'content_license': 'CC BY-SA 3.0',
        'source_url': f'https://github.com/hermitdave/FrequencyWords/blob/{REV}/content/2018/ru/ru_50k.txt',
        'word_count': len(words), 'error_count': len(keys), 'automatic_candidates': len(eligible),
        'ambiguous_manual_candidates': len(keys) - len(eligible), 'known_ambiguity_pool': len(known),
        'ambiguous_candidates_removed': initial - len(eligible), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'bytes': path.stat().st_size,
        'scope': 'Top 5000 frequent dictionary-confirmed dialogue words excluding probable personal names. Exhaustive supported single physical edits; valid forms protected; ambiguity checked against pinned lexicons, short OpenCorpora forms and verified frequency words. Not human-error accuracy or messenger statistics.'}
    (out / 'manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    (out / 'NOTICE.txt').write_text(f'''Frequent dialogue-word correction index, derived by this project.
FrequencyWords contents by Hermit Dave / OpenSubtitles: CC BY-SA 3.0.
https://creativecommons.org/licenses/by-sa/3.0/
Source and pinned hashes: manifest.json. Source frequency file and original notices: data/frequency in the project.
OpenSubtitles tokenized source: http://opus.lingfil.uu.se/OpenSubtitles2016.php
Dictionary verification: OpenCorpora CC BY-SA 3.0. See context/OpenCorpora-NOTICE.txt.
Ambiguity exclusion uses existing pinned Leeds/Hunspell lexicons; see lexicon/NOTICE.txt.
punctuation_lowercase.txt: dictionary-confirmed ordinary verbs/adverbs from the pinned FrequencyWords and OpenCorpora data, CC BY-SA 3.0. Rule and hashes: manifest.json casing_guard.
No private conversations or messenger telemetry. Frequencies are a dialogue proxy.
''')
    source_ranks = {line.split()[0]: i + 1 for i, line in enumerate(SOURCE.read_text().splitlines())}
    (ROOT / 'data/frequency/common-targets.tsv').write_text('word\traw_frequency_rank\tcount\n' + ''.join(
        f'{word}\t{source_ranks[word]}\t{frequency[word]}\n' for word in words))
    from build_punctuation_case_guard import main as build_case_guard
    build_case_guard()
    print(json.dumps(metadata, ensure_ascii=False), flush=True)


if __name__ == '__main__': main()
