#!/usr/bin/env python3
"""Train a compact, reproducible context model on licensed public Tatoeba text.

The statistical model ranks explicit suggestions; it never authorizes rewriting.
"""
import argparse
import bz2
import collections
import gzip
import hashlib
import io
import json
import math
import re
import shutil
import struct
import sys
from pathlib import Path
from urllib.request import urlopen

TOKEN = re.compile(r"[а-яё]+|[.!?,;:]", re.I)
WORD = re.compile(r"[а-яё]{1,28}\Z")


def sentence_digest(text):
    # Different punctuation/casing of the same lexical sentence stays together.
    group=' '.join(re.findall(r'[a-zа-яё0-9]+',text.lower()))
    return hashlib.blake2b(group.encode(),digest_size=16).digest()


def write_table(path, magic, values):
    keys = sorted(values)
    encoded = [key.encode() for key in keys]
    with path.open('wb') as f:
        f.write(magic)
        f.write(struct.pack('>I', len(keys)))
        offset = 0
        for key in encoded:
            f.write(struct.pack('>I', offset)); offset += len(key)
        f.write(struct.pack('>I', offset))
        for key in keys: f.write(struct.pack('>f', values[key]))
        for key in encoded: f.write(key)


def build_trie(path, words):
    nodes = [[0, {}, -1] ]
    for rank, word in enumerate(words):
        node = 0
        for char in word:
            if char not in nodes[node][1]:
                nodes[node][1][char] = len(nodes)
                nodes.append([ord(char), {}, -1])
            node = nodes[node][1][char]
        nodes[node][2] = rank
    # children are always allocated after parents, allowing a reverse DP.
    minimum = [len(words)] * len(nodes)
    first = [-1] * len(nodes); sibling = [-1] * len(nodes)
    for i in range(len(nodes)-1, -1, -1):
        children = [value for _, value in sorted(nodes[i][1].items())]
        if children: first[i] = children[0]
        for left, right in zip(children, children[1:]): sibling[left] = right
        minimum[i] = min([nodes[i][2] if nodes[i][2] >= 0 else len(words)] + [minimum[x] for x in children])
    encoded = [word.encode() for word in words]
    with path.open('wb') as f:
        f.write(b'RSKTRI01'); f.write(struct.pack('>I', len(words)))
        offset = 0
        for value in encoded: f.write(struct.pack('>I', offset)); offset += len(value)
        f.write(struct.pack('>I', offset))
        for value in encoded: f.write(value)
        f.write(struct.pack('>I', len(nodes)))
        for i, (char, _, rank) in enumerate(nodes):
            f.write(struct.pack('>iiiii', char, first[i], sibling[i], rank, minimum[i]))
    return len(nodes)


def grammar(cache):
    forms = {}; families = collections.defaultdict(dict)
    for line in (cache/'ru_gsd-ud-train.conllu').read_text().splitlines():
        if not line or line.startswith('#'): continue
        fields = line.split('\t')
        if len(fields) != 10 or not fields[0].isdigit() or fields[3] not in ('VERB','AUX'): continue
        word, lemma = fields[1].lower(), fields[2].lower()
        feats = dict(x.split('=',1) for x in fields[5].split('|') if '=' in x)
        gender = feats.get('Gender')
        if (WORD.fullmatch(word) and feats.get('VerbForm') == 'Fin' and feats.get('Tense') == 'Past' and feats.get('Number') == 'Sing'
                and gender in ('Masc','Fem','Neut')):
            previous = forms.get(word)
            if previous is not None and previous != (lemma, gender):
                forms[word] = ('<ambiguous>','')
            else: forms[word] = (lemma, gender)
            families[lemma][gender] = word
    result = {}
    for word, (lemma, gender) in forms.items():
        if lemma != '<ambiguous>':
            alternatives = {g: value for g, value in families[lemma].items() if g != gender}
            if alternatives: result[word] = {'gender':gender, 'forms':alternatives}
    return result


def expand_past_forms(result, vocabulary, cache):
    sys.path.insert(0,str((cache.parent/'morphology').resolve()))
    import pymorphy3
    analyzer=pymorphy3.MorphAnalyzer()
    names={'masc':'Masc','femn':'Fem','neut':'Neut'}
    for word in sorted(vocabulary):
        if not re.search(r'л(?:а|о)?(?:сь|ся)?\Z',word) or not analyzer.word_is_known(word): continue
        parsed=analyzer.parse(word)
        if not parsed: continue
        item=parsed[0]
        if (item.score<.8 or item.tag.POS!='VERB' or item.tag.tense!='past' or
                item.tag.number!='sing' or item.tag.gender not in names): continue
        forms={}
        for tag,gender in names.items():
            changed=item.inflect({tag})
            if changed is not None and changed.word!=word and analyzer.word_is_known(changed.word): forms[gender]=changed.word
        if forms: result[word]={'gender':names[item.tag.gender],'forms':forms}
    return result


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--cache',type=Path,default=Path('.cache/context'))
    ap.add_argument('--words',type=Path,default=Path('reports/lexicon/words-100000.txt'))
    ap.add_argument('--out',type=Path,default=Path('android/app/src/main/assets/context'))
    ap.add_argument('--eval',type=Path,default=Path('android/app/src/test/resources/context_holdout.tsv.gz'))
    ap.add_argument('--download',action='store_true',help='Fetch missing pinned UD sources; prefer bundled CC0 snapshot')
    ap.add_argument('--detailed',action='store_true',help='Use a 150k sentence hash sample from attributed CC BY 2.0 FR Tatoeba')
    args=ap.parse_args(); args.out.mkdir(parents=True,exist_ok=True)
    expected={
        'rus_sentences_CC0.tsv.bz2':'3b6645bbc18e4e3444ebc9195c4a6c41ff521a8bc4306fec8fcc217ae734db76',
        'ru_gsd-ud-train.conllu':'0ae33a4bbda12697e2eb0a32af4971bda73af5852c8521744a974a316c02bab5',
        'LICENSE.txt':'899b1804a12ebc090b96339614eede1b64b686721b650a71430b55b5235f7f79',
        'README.md':'9de2b999444f417d186a41339d56651f10f3720d957316f35de7d251b7823d6b'}
    if args.detailed:
        expected['rus_sentences_detailed.tsv.bz2']='16e6147510b4f09f97636940aaf674dee68a47910276c9a122e81453527eb329'
    for filename,digest in expected.items():
        path=args.cache/filename
        if not path.exists() and args.download:
            path.parent.mkdir(parents=True,exist_ok=True)
            if filename.startswith('rus_sentences'):
                bundled=Path(__file__).resolve().parents[1]/'data/context_sources/tatoeba-rus-cc0-20261031.tsv.bz2'
                if filename=='rus_sentences_CC0.tsv.bz2' and bundled.exists(): path.write_bytes(bundled.read_bytes())
                else:
                    with urlopen('https://downloads.tatoeba.org/exports/per_language/rus/'+filename,timeout=60) as response: path.write_bytes(response.read())
            else:
                with urlopen('https://raw.githubusercontent.com/UniversalDependencies/UD_Russian-GSD/0f34b7362ac3c3facd1d6ff4b876d241bb15793e/'+filename,timeout=60) as response: path.write_bytes(response.read())
        if hashlib.sha256((args.cache/filename).read_bytes()).hexdigest()!=digest:
            raise ValueError('Source snapshot hash mismatch: '+filename)
    morph_source=args.cache.parent/'morphology/pymorphy3_dicts_ru/data/words.dawg'
    if morph_source.exists(): shutil.copyfile(morph_source,args.out/'morphology.bin')
    morph_hash='ae4381a9d6e4e636309ac1a242e480a9470a05723f6673d5fcc3fcf293aa8a9e'
    if hashlib.sha256((args.out/'morphology.bin').read_bytes()).hexdigest()!=morph_hash:
        raise ValueError('Unexpected OpenCorpora dictionary snapshot')
    license_source=args.cache.parent/'morphology/dawg2_python-0.9.0.dist-info/LICENSE'
    if license_source.exists(): shutil.copyfile(license_source,args.out/'DAWG-Python-LICENSE.txt')
    else:
        bundled_license=Path(__file__).resolve().parents[1]/'android/app/src/main/assets/context/DAWG-Python-LICENSE.txt'
        if bundled_license.resolve()!=(args.out/'DAWG-Python-LICENSE.txt').resolve(): shutil.copyfile(bundled_license,args.out/'DAWG-Python-LICENSE.txt')
    (args.out/'OpenCorpora-NOTICE.txt').write_text('OpenCorpora Russian dictionary, revision 417150 / corpus 4580142.\nCompiled by pymorphy3-dicts-ru 2.4.417150.4580142. Data: CC BY-SA 3.0.\nhttps://creativecommons.org/licenses/by-sa/3.0/\nSources: https://opencorpora.org/ and https://github.com/no-plagiarism/pymorphy3-dicts\nUnchanged words.dawg; Darts reader adapted from DAWG-Python (MIT, license included).\n',encoding='utf-8')
    corpus_name='rus_sentences_detailed.tsv.bz2' if args.detailed else 'rus_sentences_CC0.tsv.bz2'
    raw=(args.cache/corpus_name).read_bytes()
    unique={}
    for line in bz2.decompress(raw).decode().splitlines():
        fields=line.split('\t')
        if len(fields)<3 or fields[1]!='rus': continue
        text=fields[2].strip(); normalized=' '.join(text.lower().split())
        digest=sentence_digest(normalized)
        if args.detailed and int.from_bytes(digest[:8],'big')%4!=0: continue
        tokens=TOKEN.findall(normalized)
        if 2 <= len(tokens) <= 60 and any(WORD.fullmatch(w) for w in tokens):
            unique.setdefault(normalized,(fields[0],text,tokens,fields[3] if args.detailed else 'CC0'))
    if args.detailed and len(unique)>150_000:
        unique=dict(sorted(unique.items(),key=lambda item:(sentence_digest(item[0]),item[0]))[:150_000])
    splits=collections.Counter(); unigram=collections.Counter(); bigram=collections.Counter(); trigram=collections.Counter()
    prefix1=collections.Counter(); prefix2=collections.Counter(); following=collections.defaultdict(collections.Counter)
    heldout=[]
    contributors=collections.Counter()
    for normalized,(identity,text,tokens,author) in sorted(unique.items()):
        digest=sentence_digest(normalized)
        bucket=int.from_bytes(digest[8:],'big')%100
        if author not in ('CC0','\\N'): contributors[author]+=1
        split='train' if bucket<85 else 'validation' if bucket<92 else 'test'
        splits[split]+=1
        if split != 'train': heldout.append((split,identity,text)); continue
        seq=['^','^']+tokens+['$']
        for i in range(2,len(seq)):
            a,b,w=seq[i-2:i+1]
            unigram[w]+=1; bigram[(b,w)]+=1; trigram[(a,b,w)]+=1
            prefix1[b]+=1; prefix2[(a,b)]+=1
            if WORD.fullmatch(w):
                following[b][w]+=1; following[a+' '+b][w]+=1
    total=sum(unigram.values()); vocab=len(unigram)
    values={word:math.log((count+.1)/(total+.1*vocab)) for word,count in unigram.items()}
    for (prev,word),count in bigram.items():
        if count>=3: values[prev+' '+word]=math.log((count+.1)/(prefix1[prev]+.1*vocab))
    for (a,b,word),count in trigram.items():
        if count>=3: values[a+' '+b+' '+word]=math.log((count+.1)/(prefix2[(a,b)]+.1*vocab))
    write_table(args.out/'language.bin',b'RSKCTX01',values)
    predictions={}
    for key,choices in sorted(following.items(),key=lambda item:(-sum(item[1].values()),item[0]))[:15_000]:
        top=[word for word,count in sorted(choices.items(),key=lambda x:(-x[1],x[0])) if count>=3][:3]
        if top: predictions[key]=top
    (args.out/'predictions.tsv').write_text(''.join(key+'\t'+'\t'.join(value)+'\n' for key,value in sorted(predictions.items())),encoding='utf-8')
    words=args.words.read_text(encoding='utf-8').splitlines()
    wordset=set(words)
    morphology=expand_past_forms(grammar(args.cache),unigram,args.cache)
    extra=[w for w,c in sorted(unigram.items(),key=lambda x:(-x[1],x[0]))
           if WORD.fullmatch(w) and c>=2 and w not in wordset][:80_000]
    wordset.update(extra)
    extra.extend(sorted({word for word,record in morphology.items() for word in [word,*record['forms'].values()]}-wordset))
    nodes=build_trie(args.out/'spelling.bin',words+extra)
    (args.out/'grammar.tsv').write_text(''.join(word+'\t'+record['gender']+'\t'+'\t'.join(record['forms'].get(g,'') for g in ('Masc','Fem','Neut'))+'\n' for word,record in sorted(morphology.items())),encoding='utf-8')
    args.eval.parent.mkdir(parents=True,exist_ok=True)
    with args.eval.open('wb') as raw_eval:
        with gzip.GzipFile(fileobj=raw_eval,mode='wb',filename='',mtime=0) as zipped:
            with io.TextIOWrapper(zipped,encoding='utf-8',newline='\n') as f:
                f.write('split\tsentence_id\ttext\n')
                for split,identity,text in heldout: f.write(f'{split}\t{identity}\t{text}\n')
    source_license='CC BY 2.0 FR' if args.detailed else 'CC0 1.0'
    metadata={'format':'context-1','source':'Tatoeba Russian attributed 150k hash sample' if args.detailed else 'Tatoeba Russian CC0 subset','source_url':'https://downloads.tatoeba.org/exports/per_language/rus/'+corpus_name,
        'license':source_license,'source_sha256':hashlib.sha256(raw).hexdigest(),'split_by':'lexical sentence hash (ignoring punctuation/case/spacing) before n-gram counting; separate digest halves for sampling/split',
        'sentences':dict(splits),'context_entries':len(values),'spelling_words':len(words)+len(extra),'trie_nodes':nodes,
        'prediction_contexts':len(predictions),'grammar_source':'UniversalDependencies/UD_Russian-GSD @ 0f34b7362ac3c3facd1d6ff4b876d241bb15793e',
        'grammar_license':'CC BY-SA 4.0 (UD 4.0 + OpenCorpora 3.0 adaptation)',
        'grammar_forms':len(morphology),'source_snapshots':expected,
        'holdout_sha256':hashlib.sha256(args.eval.read_bytes()).hexdigest(),
        'morphology':{'source':'OpenCorpora via pymorphy3-dicts-ru 2.4.417150.4580142','records':5140211,'license':'CC BY-SA 3.0','sha256':morph_hash},
        'sha256':{name:hashlib.sha256((args.out/name).read_bytes()).hexdigest() for name in ['spelling.bin','language.bin','grammar.tsv','predictions.tsv','morphology.bin']},
        'scope':'Backoff n-grams and dictionary morphology, not a neural grammar model; explicit suggestions only'}
    (args.out/'manifest.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    shutil.copyfile(args.cache/'LICENSE.txt',args.out/'UD-LICENSE.txt')
    shutil.copyfile(args.cache/'README.md',args.out/'UD-README.md')
    (args.out/'Tatoeba-AUTHORS.txt').write_text('Tatoeba Russian sentences. Contributor usernames as supplied in the source export.\nhttps://tatoeba.org/en/downloads\nLicense: '+source_license+'\nhttps://creativecommons.org/licenses/by/2.0/fr/\nContext statistics are derived/adapted by this project. Attribution source URLs: https://tatoeba.org/en/user/profile/USERNAME\n\n'+'\n'.join(sorted(contributors))+'\n',encoding='utf-8')
    (args.out/'NOTICE.txt').write_text('Tatoeba Russian sentences, '+source_license+', https://tatoeba.org/en/downloads\nhttps://creativecommons.org/licenses/by/2.0/fr/\nContext statistics adapted by this project; contributor attribution in Tatoeba-AUTHORS.txt; sentence IDs retained in heldout fixture.\nGrammar forms derived from UD Russian GSD (Google; McDonald, Nikolaev, Lyashevskaya), CC BY-SA 4.0, and OpenCorpora, CC BY-SA 3.0; adapted grammar table under CC BY-SA 4.0.\nSee manifest.json, UD-README.md and OpenCorpora-NOTICE.txt. No private user messages used.\n',encoding='utf-8')
    print(json.dumps(metadata,ensure_ascii=False,indent=2))


if __name__=='__main__': main()
