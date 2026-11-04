# Offline Smart Keyboard v0.4

An **experimental Android IME** with a familiar Russian ЙЦУКЕН touchscreen layout and a **hidden 2–9 keypad index** for retrieving spelling candidates. Entirely local; the app requests no internet permission.

Changes in v0.4: 508 curated demo word forms (still **not** a full dictionary), a small previous-word context ranker, search for missing or transposed letters, greater resistance to changing existing correct words, and Kotlin JVM tests. Context is temporary and reset between fields.

Run JVM tests with `bash scripts/test-blocks.sh` and `bash scripts/test-context.sh`. Run `bash scripts/bench-index.sh 10000` for a **synthetic-only** scalability microbenchmark (not an Android timing guarantee).

Open this directory with Android Studio (Android SDK 35, JDK 17, AGP 8.9.2, Kotlin 2.2.20, Gradle 8.11.1). A Gradle wrapper JAR and a built APK are **not included**. Android installation and end-to-end latency have not been verified.

An optional, **development-only** importer can convert the externally downloaded, CC BY 2.5 attributed [Leeds-based 50k Russian list](https://github.com/hingston/russian) into app-local dictionary data: `python scripts/import-leeds-words.py path/to/list.txt --limit 15000`. This imported dataset is **not bundled** with v0.4. The source list is from University of Leeds Russian Internet Corpus, edited by hingston; preserve attribution and CC BY 2.5 license.

No full-sentence punctuation, grammar, semantic understanding, or general-purpose offline LLM is implemented yet. The 20 ms requirement remains an engineering target only. See `README_RU.md` for the full Russian-language instructions, features, and limitations.
