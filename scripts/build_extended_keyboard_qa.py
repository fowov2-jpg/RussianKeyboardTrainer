#!/usr/bin/env python3
"""Deterministic diverse QA, separate from training. Optional real RUPunct logits.

Fixtures only: never use this generated evaluation set for tuning model weights.
With --reference: numpy/tokenizers/onnxruntime required; no downloads.
"""
import argparse
import base64
import collections
import gzip
import hashlib
import json
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'android/app'
RESOURCES = APP / 'src/test/resources'
DEST = APP / 'src/sharedTest/fixtures'


def rank(value):
    return hashlib.sha256(('extended-qa-v1:' + value).encode()).digest()


def build():
    targets = {}
    with gzip.open(RESOURCES / 'lexicon_corpus.tsv.gz', 'rt') as source:
        next(source)
        for line in source:
            raw, intended, error = line.rstrip('\n').split('\t')
            if not 5 <= len(intended) <= 24:
                continue
            score = rank(raw)
            if intended not in targets or score < targets[intended][0]:
                targets[intended] = score, raw, error
    selected = sorted(targets, key=rank)[:2000]
    rows = []
    def add(kind, text, expected, reference='', provenance=''):
        rows.append((f'{kind}-{sum(r[1] == kind for r in rows):04d}', kind, text, expected, reference, provenance))
    for intended in selected:
        _, raw, error = targets[intended]
        add('typo', raw, intended, provenance=error)
    occupied = {r[2] for r in rows}
    with gzip.open(RESOURCES / 'morphology_parity.tsv.gz', 'rt') as source:
        clean = {word for word, known in (line.rstrip('\n').split('\t') for line in source)
                 if known == '1' and word not in occupied}
    for word in sorted(clean, key=rank)[:2000]:
        add('word_keep', word, word, provenance='OpenCorpora reference membership')
    for i, intended in enumerate(selected[:500]):
        raw = targets[intended][1]
        text = [f'@{raw}', f'#{raw}', f'https://example.test/{raw}', f'{raw}_tag', f'/docs/{raw}'][i % 5]
        add('protected', text, text, provenance='synthetic protected identifier')
    bins = collections.defaultdict(list)
    with gzip.open(RESOURCES / 'context_holdout.tsv.gz', 'rt') as source:
        next(source)
        for line in source:
            split, sentence_id, text = line.rstrip('\n').split('\t', 2)
            if split != 'test' or not re.fullmatch(r'[А-Яа-яЁё\s,.!?:;]+', text) or not text.endswith(('.', '!', '?')):
                continue
            words = re.findall('[А-Яа-яЁё]+', text)
            if not 3 <= len(words) <= 24:
                continue
            group = 'question' if text.endswith('?') else 'comma' if ',' in text else 'statement'
            bins[group].append((sentence_id, text, ' '.join(words).lower()))
    phrases = []
    seen = set()
    for group in ('question', 'comma', 'statement'):
        count = 0
        for sentence_id, text, bare in sorted(bins[group], key=lambda item: rank(item[0])):
            if bare in seen:
                continue
            seen.add(bare); count += 1
            phrases.append((sentence_id, text, bare))
            add('restore', bare, text, sentence_id, group)
            add('phrase_keep', text, text, sentence_id, group)
            if count == 500:
                break
        assert count == 500, (group, count)
    assert len(rows) == len({row[2] for row in rows}) == 7500
    DEST.mkdir(parents=True, exist_ok=True)
    lines = ['id\tkind\tinput_b64\texpected_b64\treference\tprovenance']
    for case_id, kind, text, expected, reference, provenance in rows:
        encode = lambda value: base64.b64encode(value.encode()).decode()
        lines.append('\t'.join((case_id, kind, encode(text), encode(expected), reference, provenance)))
    fixture = DEST / 'extended_qa.tsv.gz'
    fixture.write_bytes(gzip.compress(('\n'.join(lines) + '\n').encode(), mtime=0))
    manifest = {'format': 'extended-qa-v1', 'seed': 'SHA256 extended-qa-v1:',
                'cases': len(rows), 'globally_unique_inputs': len({row[2] for row in rows}),
                'distinct_typo_targets': len(selected), 'distinct_source_phrases': len(phrases),
                'by_kind': dict(collections.Counter(row[1] for row in rows)),
                'sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
                'source_sha256': {name: hashlib.sha256((RESOURCES / name).read_bytes()).hexdigest() for name in
                                  ('lexicon_corpus.tsv.gz', 'morphology_parity.tsv.gz', 'context_holdout.tsv.gz')},
                'phrase_filter': '3..24 Cyrillic words, Cyrillic/whitespace/.,!?:; only, final .!?; unique lexical sentences; fixed hash ordering within strata',
                'sources': {'words': 'existing licensed synthetic corpus + OpenCorpora reference fixture',
                            'phrases': 'test-only lexical-group-disjoint Tatoeba holdout, 500 questions/500 comma/500 other statements',
                            'attribution': 'sentence IDs link to https://tatoeba.org/en/sentences/show/ID; author attribution in main/assets/context/Tatoeba-AUTHORS.txt',
                            'licenses': 'Tatoeba CC BY 2.0 FR; OpenCorpora CC BY-SA 3.0; word sources see main/assets/lexicon/NOTICE.txt'},
                'scope': '7500 distinct inputs; synthetic typos and corpus annotations, not a representative human-error benchmark; pretrained model train overlap unknown'}
    (DEST / 'extended_qa_manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(manifest['by_kind']), flush=True)
    return phrases


def reference(phrases):
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer
    assets = APP / 'src/main/assets/punctuation'
    tokenizer = Tokenizer.from_file(str(assets / 'tokenizer.json'))
    options = ort.SessionOptions(); options.intra_op_num_threads = 1
    session = ort.InferenceSession(str(assets / 'model.bin'), options, providers=['CPUExecutionProvider'])
    output = bytearray(b'RUPQA001')
    def integer(value): output.extend(struct.pack('>i', value))
    def string(value):
        value = value.encode(); integer(len(value)); output.extend(value)
    integer(len(phrases))
    for i, (sentence_id, _, bare) in enumerate(phrases):
        ids = [2]; positions = []
        for word in bare.split():
            positions.append(len(ids)); ids.extend(tokenizer.encode(word, add_special_tokens=False).ids)
        ids.append(3)
        assert len(ids) <= 192
        values = np.array([ids], dtype=np.int64)
        logits = session.run(None, {'input_ids': values, 'attention_mask': np.ones_like(values),
                                    'token_type_ids': np.zeros_like(values)})[0][0]
        string(sentence_id); integer(len(ids))
        for token in ids: integer(token)
        integer(len(positions))
        for position in positions:
            integer(position); output.extend(struct.pack('>33f', *logits[position]))
        if (i + 1) % 300 == 0: print('Real model references:', i + 1, flush=True)
    path = RESOURCES / 'extended_phrase_logits.bin'
    path.write_bytes(output)
    manifest_path = DEST / 'extended_qa_manifest.json'
    manifest = json.loads(manifest_path.read_text())
    manifest['reference_logits_sha256'] = hashlib.sha256(output).hexdigest()
    manifest['reference_model_sha256'] = hashlib.sha256((assets / 'model.bin').read_bytes()).hexdigest()
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print('Wrote', len(phrases), 'real-model references;', len(output), 'bytes', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', action='store_true')
    args = parser.parse_args()
    phrases = build()
    if args.reference: reference(phrases)
