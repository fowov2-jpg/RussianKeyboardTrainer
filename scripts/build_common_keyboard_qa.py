#!/usr/bin/env python3
"""7500 frequent-word/chat-proxy cases, including an untouched short-phrase slice.

This is frequency-stratified dictionary integration + corpus/controlled-scenario QA,
not private-chat statistics or a representative real-human typo benchmark.
"""
import argparse
import base64
import csv
import gzip
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from build_common_lexicon import frequencies, valid_form, SOURCE, SHA, REV, ROOT
from build_russian_typos import variants
from build_extended_keyboard_qa import reference

APP = ROOT / 'android/app'
DEST = APP / 'src/sharedTest/fixtures'
RESOURCE = APP / 'src/test/resources'


def rank(value): return hashlib.sha256(('common-qa-v1:' + value).encode()).digest()


def main(reference_logits=False):
    sys.path.insert(0, str(ROOT / '.cache/morphology'))
    import dawg_python
    import pymorphy3
    morphology = dawg_python.RecordDAWG('>HH').load(str(APP / 'src/main/assets/context/morphology.bin'))
    analyzer = pymorphy3.MorphAnalyzer()
    freq = frequencies(); ranks = {line.split()[0]: i + 1 for i, line in enumerate(SOURCE.read_text().splitlines())}
    targets = list(csv.DictReader((ROOT / 'data/frequency/common-targets.tsv').open(), delimiter='\t'))[:1500]
    assert len(targets) == 1500
    rows = []; occupied = set(); counts = {}
    def add(kind, text, expected, ref='', provenance=''):
        assert text not in occupied, text
        occupied.add(text); number = counts.get(kind, 0); counts[kind] = number + 1
        rows.append((f'common-{kind}-{number:04d}', kind, text, expected, ref, provenance))
    for i, target in enumerate(targets):
        word = target['word']; pool = list(dict.fromkeys(variants(word)))
        allowed = [(raw, error) for raw, error in pool if 3 <= len(raw) <= 28 and not valid_form(raw, morphology, analyzer) and raw not in occupied]
        adjacent = [(raw, error) for raw, error in allowed if error == 'adjacent_substitution']
        double_error = int.from_bytes(rank(word)[:4], 'big') % 10 == 0
        extra = [(raw, error) for raw, error in allowed if error != 'adjacent_substitution' and
                 (error == 'two_neighbor_errors' if double_error else error != 'two_neighbor_errors')]
        assert adjacent and extra, word
        for raw, error in (min(adjacent, key=lambda x: rank(x[0])), min(extra, key=lambda x: rank(x[0]))):
            add('typo', raw, word, provenance=f'{error};rank={target["raw_frequency_rank"]};count={target["count"]}')
    clean = [word for word in freq if valid_form(word, morphology, analyzer) and word not in occupied][:2000]
    assert len(clean) == 2000
    for word in clean: add('word_keep', word, word, provenance=f'rank={ranks[word]};count={freq[word]}')
    typo_rows = [r for r in rows if r[1] == 'typo']
    for i, row in enumerate(typo_rows[:500]):
        raw = row[2]; text = [f'@{raw}', f'#{raw}', f'https://example.test/{raw}', f'{raw}_tag', f'/docs/{raw}'][i % 5]
        add('protected', text, text, provenance='synthetic identifier built from a frequent-word typo')
    def bare(text): return ' '.join(re.findall('[А-Яа-яЁё0-9]+', text)).lower()
    authored = (ROOT / 'data/frequency/chat-scenarios.txt').read_text().splitlines()
    assert len(authored) == len({bare(s) for s in authored}) == 100
    used = {bare(s) for s in authored}
    with gzip.open(DEST / 'extended_qa.tsv.gz', 'rt') as stream:
        previous = [r for r in csv.DictReader(stream, delimiter='\t') if r['kind'] == 'restore']
        old = {r['reference'] for r in previous}
        old_lexical = {base64.b64decode(r['input_b64']).decode() for r in previous}
    dialogue = set('я ты мы вы мне тебе нам вам меня тебя нас вас мой твой давай можно нужно надо привет спасибо пожалуйста ладно извини прости сейчас сегодня завтра куда где когда почему как сколько зачем'.split())
    names = set('том тома тому томом томе мэри джон джона джону джоном алиса алисы джек джека джейн бостон бостоне'.split())
    eligible = []
    with gzip.open(RESOURCE / 'context_holdout.tsv.gz', 'rt') as stream:
        next(stream)
        for line in stream:
            split, identity, text = line.rstrip('\n').split('\t', 2)
            if split != 'test' or identity in old or not re.fullmatch('[А-Яа-яЁё ,!?;:.]+', text): continue
            words = re.findall('[А-Яа-яЁё]+', text.lower())
            if not 2 <= len(words) <= 12 or not text.endswith(('.', '?', '!')) or any(mark in text[:-1] for mark in '.!?'): continue
            if not set(words) & dialogue or set(words) & names or not any(len(w) >= 4 for w in words): continue
            if sum(ranks.get(w, 999999) <= 5000 for w in words) / len(words) < .8: continue
            if any(ranks.get(w, 999999) > 20000 for w in words): continue
            score = sum(math.log(freq.get(w, 1)) for w in words) / len(words)
            eligible.append((score, identity, text, ' '.join(words)))
    phrases = []
    for _, identity, text, raw in sorted(eligible, key=lambda x: (-x[0], rank(x[1]))):
        if raw in used or raw in old_lexical: continue
        used.add(raw); phrases.append((identity, text, raw))
        if len(phrases) == 900: break
    assert len(phrases) == 900, len(phrases)
    for i, text in enumerate(authored): phrases.append((f'chat-{i:03d}', text, bare(text)))
    for identity, text, raw in phrases:
        provenance = 'authored household scenario; not observed user frequency' if identity.startswith('chat-') else 'short Tatoeba test slice selected by dialogue-word frequency; no forced punctuation strata'
        add('restore', raw, text, identity, provenance)
        add('phrase_keep', text, text, identity, provenance)
    assert len(rows) == len(occupied) == 7500
    encode = lambda text: base64.b64encode(text.encode()).decode()
    content = 'id\tkind\tinput_b64\texpected_b64\treference\tprovenance\n' + ''.join('\t'.join((i, k, encode(t), encode(g), r, p))+'\n' for i,k,t,g,r,p in rows)
    path = DEST / 'common_qa.tsv.gz'; path.write_bytes(gzip.compress(content.encode(), mtime=0))
    manifest = {'format': 'common-qa-v1', 'cases': len(rows), 'unique_inputs': len(occupied), 'by_kind': counts,
        'distinct_typo_targets': len(targets), 'distinct_source_phrases': len(phrases), 'corpus_phrases': 900, 'authored_phrases': 100,
        'source': 'FrequencyWords 2018 Russian / OpenSubtitles dialogue-frequency proxy', 'source_revision': REV,
        'source_sha256': SHA, 'license': 'CC BY-SA 3.0', 'source_url': f'https://github.com/hermitdave/FrequencyWords/blob/{REV}/content/2018/ru/ru_50k.txt',
        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'rank_range_for_typo_targets': [int(targets[0]['raw_frequency_rank']), int(targets[-1]['raw_frequency_rank'])],
        'corpus_lexical_overlap_previous_qa': sum(raw in old_lexical for _,_,raw in phrases[:900]),
        'authored_lexical_overlap_previous_qa': sum(raw in old_lexical for _,_,raw in phrases[900:]),
        'phrase_types': {mark: sum(text.endswith(mark) for _,text,_ in phrases) for mark in '.?!'},
        'selection': 'First 1500 dictionary-verified non-name frequent targets; two independent synthetic physical errors per target. First 2000 valid frequency words for KEEP. 900 unused short dialogue-like Tatoeba test sentences ranked by mean log word frequency, 100 authored controls. No messenger data or actual phrase popularity claims.',
        'scope': 'Frequency-aware integration and language evaluation; synthetic edits use the same declared keyboard-error model as the compiled index, so recovery is coverage, NOT real human-typo accuracy. Pretrained model train overlap unknown.'}
    metadata = DEST / 'common_qa_manifest.json'; metadata.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(manifest, ensure_ascii=False), flush=True)
    if reference_logits: reference(phrases, RESOURCE / 'common_phrase_logits.bin', metadata)


if __name__ == '__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--reference',action='store_true');args=ap.parse_args();main(args.reference)
