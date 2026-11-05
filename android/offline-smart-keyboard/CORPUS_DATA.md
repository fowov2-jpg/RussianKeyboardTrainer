# Corpus-derived offline correction: provenance, licensing, limits

## What is bundled in Smart Keyboard v0.16

This is **NOT** a fine-tuned neural language model. The APK uses a statistical
offline word-frequency and bigram model, plus safe orthographic candidates.
All computation at runtime remains on-device; no typed messages are uploaded
or logged. Corpus sentences and learner identifiers are NOT bundled.

| Asset | Derived data | Rows |
|---|---|---:|
| `ru_gec_wordforms.txt` | Frequent correct inflected forms | 1,622 |
| `ru_gec_bigrams.txt` | Prior/next-word frequency hints | 1,200 |
| `ru_rlc_repeated_typos.txt` | Human-annotated spelling proposals | 149 |
| `gec_holdout.tsv` (tests only) | Held-out token pairs | 200 |
| `rlc_test_holdout.tsv` (tests only) | Independent RLC-Test annotations | 84 |

The raw source files are **not** stored in git or embedded in the APK.
The two test-only holdout files ship in the source repository, not the app.

## Verified sources and license conditions

1. **Russian Grammar Error Correction (dreuxx26)** — CC BY 4.0.
   Source: https://github.com/dreuxx/Russian-Grammar-Error-Correction-Dataset
   and https://huggingface.co/datasets/dreuxx26/russian_gec.
   Attribution to Dreux X., *Russian Grammar Error-Correction Dataset* (2025).
   Its README says 25,362 pairs, but the inspected CSV contains **24,998 rows**.
   We removed repeated corrected sentences (17,569 unique), split them by
   FNV-1a hash of the corrected sentence (13,971 train / 3,598 holdout),
   and derived aggregate wordform/bigram counts from training sentences.
   Data were normalized, filtered and weighted, not copied verbatim.

2. **Russian Learner Corpus: RLC-GEC** — MIT license in repository.
   https://github.com/Russian-Learner-Corpus/rlc-annotated
   Authors associated with the 2024 LREC paper: Kosakin et al.,
   https://aclanthology.org/2024.lrec-main.1241/.
   41,410 error annotations inspected. We kept repeated (>=2 independent
   annotated sentence IDs) manual orthographic corrections with simple
   Cyrillic word forms, known correct targets, and edit distance <=2.
   These **only nominate candidates**; they never override a known word
   or force an automatic correction. Independent `rlc_test.csv` supplied
   the separate held-out evaluation tokens.

3. **Social Media Text Normalization** — **CC BY-NC-SA 4.0**.
   https://huggingface.co/datasets/ruscorpora/normalization
   Contains 1,930 aligned examples. NonCommercial/ShareAlike restrictions
   make automatic inclusion in a distributed general-purpose APK unsuitable
   without clarification of the project's distribution and licensing.
   **NOT bundled, trained on, or used for shipping model statistics.**
   Research-only opt-in source download is available.

4. **GERA (school essays)**:
   https://github.com/ReginaNasyrova/GERA
   (Sorokin & Nasyrova 2025, AIST).
   The public repository provides .m2 train/dev/test partitions but
   no explicit redistribution license was found. **NOT bundled or trained
   into the APK.** Research evaluation can read locally obtained .m2 data
   without committing the corpus. Obtain explicit rights before redistribution.

## Reproduction

From `android/offline-smart-keyboard`:

```bash
python3 tools/fetch_corpora.py --sources gec rlc
python3 tools/train_corpus_features.py
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Optional access to GERA is explicitly evaluation-only:
```bash
python3 tools/fetch_corpora.py --sources gera --evaluation-only
```

Social-media normalization requires `--noncommercial-research`.
Never include raw text files from `data/raw/` in the APK or public git history.

## Interpretation of test metrics

GEC and RLC contain numerous learner grammatical or word-choice errors,
which are **not representative of mistyping on a Russian touch keyboard**.
Metrics from these corpora are deliberately labeled separately from the
old 346-case synthetic keyboard typo benchmark and the 316-case expanded
lexicon benchmark. A correct grammatical form can be an incorrect replacement
in another context. Preserve exact known words and require conservative
confidence thresholds for automatic substitution.
