# Russian frequency lexicon — third-party attribution

The file `app/src/main/assets/ru_words_leeds_10k.txt` is derived from the
`10000-russian-words-cyrillic-only.txt` list in
[hingston/russian](https://github.com/hingston/russian), compiled from the
University of Leeds Russian Corpus:
http://corpus.leeds.ac.uk/frqc/internet-ru.num

Source license: **Creative Commons Attribution 2.5 (CC BY 2.5)**,
https://creativecommons.org/licenses/by/2.5/

Attribution: University of Leeds Corpus; list editing/cleanup by hingston.
The list has been normalized to lowercase Cyrillic tokens, deduplicated,
filtered to word lengths of 2–24 letters, and assigned approximate weights
based on its original frequency order. These weights are ranking heuristics,
not calibrated corpus counts. The derived asset is bundled inside the APK:
**no network connection is needed at runtime**.

The original handcrafted `ru_words.txt` remains separate, with higher
weights for common conversational forms and reviewed examples.
