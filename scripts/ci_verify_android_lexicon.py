#!/usr/bin/env python3
"""Require real corpus coverage and verify the dictionary inside each APK."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def verify(report: Path, assets: Path, corpus: Path, apks: Path):
    runtime = json.loads(report.read_text(encoding="utf-8"))
    manifest = json.loads((assets / "manifest.json").read_text(encoding="utf-8"))
    if not (runtime["runtime_rows_checked"] == manifest["indexed_typos"] == 1_000_000
            and runtime["distinct_words"] == manifest["wordforms"] == 100_000
            and runtime["automatic"] + runtime["manual"] + runtime.get("morphology_keep",0) == 1_000_000
            and 100_000 < runtime["automatic"] < 1_000_000):
        raise ValueError(f"Insufficient actual runtime coverage: {runtime}")
    if hashlib.sha256(corpus.read_bytes()).hexdigest() != manifest["corpus_sha256"]:
        raise ValueError("Runtime tests used a different corpus from the compiled asset")
    if hashlib.sha256((assets / "russian.bin").read_bytes()).hexdigest() != manifest["sha256"]:
        raise ValueError("Source asset differs from recorded SHA-256")
    context_assets=assets.parent/'context'
    context_manifest=json.loads((context_assets/'manifest.json').read_text(encoding='utf-8'))
    context_report=json.loads((report.parent/'context-runtime.json').read_text(encoding='utf-8'))
    if (context_report['test_sentences']!=context_manifest['sentences']['test'] or
        context_report['clean_tokens']<10_000 or context_report['synthetic_unseen_errors']<160 or
        context_report['clean_source_automatic_edits']/context_report['clean_tokens']>.005 or
        context_report['target_in_top3']<=context_report['synthetic_unseen_errors']/2):
        raise ValueError('Context runtime coverage/quality gate failed')
    holdout=corpus.parent/'context_holdout.tsv.gz'
    if hashlib.sha256(holdout.read_bytes()).hexdigest()!=context_manifest['holdout_sha256']:
        raise ValueError('Context holdout fixture differs from training manifest')
    for name,digest in context_manifest['sha256'].items():
        if hashlib.sha256((context_assets/name).read_bytes()).hexdigest()!=digest:
            raise ValueError('Context asset hash mismatch: '+name)
    punctuation_assets = assets.parent / 'punctuation'
    punctuation_manifest = json.loads((punctuation_assets / 'manifest.json').read_text(encoding='utf-8'))
    if (punctuation_manifest['license'] != 'MIT' or punctuation_manifest['model_bytes'] != 29259673 or
            punctuation_manifest['sha256']['model.bin'] != 'b105da023474d98aa13ba18953ae67b04b17bd0595034bc06030c17536893933'):
        raise ValueError('Unexpected punctuation model provenance')
    for name, digest in punctuation_manifest['sha256'].items():
        if hashlib.sha256((punctuation_assets / name).read_bytes()).hexdigest() != digest:
            raise ValueError('Punctuation asset hash mismatch: ' + name)
    apk_files = sorted(apks.glob("*.apk"))
    if not apk_files:
        raise ValueError("No APKs to verify")
    for apk in apk_files:
        with zipfile.ZipFile(apk) as archive:
            entry = archive.getinfo("assets/lexicon/russian.bin")
            if entry.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f"Asset must be uncompressed for mmap: {apk}")
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                while chunk := stream.read(1024 * 1024):
                    digest.update(chunk)
            if digest.hexdigest() != manifest["sha256"]:
                raise ValueError(f"Packaged dictionary SHA-256 mismatch: {apk}")
            for name in ("NOTICE.txt", "leeds-LICENSE.md", "hunspell-LICENSE.txt", "manifest.json"):
                if archive.read("assets/lexicon/" + name) != (assets / name).read_bytes():
                    raise ValueError(f"Missing or changed attribution/manifest in {apk}")
            for name,digest in context_manifest['sha256'].items():
                key='assets/context/'+name
                if name.endswith('.bin') and archive.getinfo(key).compress_type!=zipfile.ZIP_STORED:
                    raise ValueError('Context dictionary is compressed: '+key)
                if hashlib.sha256(archive.read(key)).hexdigest()!=digest:
                    raise ValueError('Packaged context hash mismatch: '+key)
            for name in ('NOTICE.txt','manifest.json','UD-LICENSE.txt','UD-README.md','OpenCorpora-NOTICE.txt','DAWG-Python-LICENSE.txt','Tatoeba-AUTHORS.txt'):
                if archive.read('assets/context/'+name)!=(context_assets/name).read_bytes():
                    raise ValueError('Context attribution/manifest mismatch: '+name)
            for name, digest in punctuation_manifest['sha256'].items():
                key = 'assets/punctuation/' + name
                if name == 'model.bin' and archive.getinfo(key).compress_type != zipfile.ZIP_STORED:
                    raise ValueError('Punctuation weights must be uncompressed for mmap')
                if hashlib.sha256(archive.read(key)).hexdigest() != digest:
                    raise ValueError('Packaged punctuation hash mismatch: ' + key)
            for name in ('NOTICE.txt', 'manifest.json'):
                if archive.read('assets/punctuation/' + name) != (punctuation_assets / name).read_bytes():
                    raise ValueError('Punctuation attribution mismatch: ' + name)
            if any(any(fixture in name for fixture in ('lexicon_corpus','context_holdout','morphology_parity','punctuation_reference')) for name in archive.namelist()):
                raise ValueError(f"Test corpus accidentally shipped in {apk}")
        print(f"Packaged lexicon OK: {apk.name}")
    return runtime


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, default=Path("android/app/build/reports/lexicon-runtime.json"))
    parser.add_argument("--assets", type=Path, default=Path("android/app/src/main/assets/lexicon"))
    parser.add_argument("--corpus", type=Path, default=Path("android/app/src/test/resources/lexicon_corpus.tsv.gz"))
    parser.add_argument("--apks", type=Path, default=Path("android/app/build/outputs/apk/debug"))
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    result = verify(args.report, args.assets, args.corpus, args.apks)
    message = (f"1,000,000 actual Kotlin dictionary lookups across 100,000 distinct wordforms passed; "
               f"{result['automatic']:,} automatic, {result['manual']:,} manual candidates and {result.get('morphology_keep',0):,} protected real-word KEEP cases. "
               "Packaged asset hashes, mmap storage and attribution verified. "
               "Synthetic integration coverage; not human accuracy or phone performance.")
    print(message)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as stream:
            stream.write("\n### Android lexicon coverage\n" + message + "\n")
