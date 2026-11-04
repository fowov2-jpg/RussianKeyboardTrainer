"""Generator sanity checks without network, independent of the Android app."""
import gzip
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from build_russian_typos import blob_hash, decode_dictionary, select_words, NEIGHBORS, create_pairs, variants


class RussianTypoGeneratorTest(unittest.TestCase):
    def test_dictionary_utf16_with_ascii_header(self):
        sample = bytes.fromhex("3537353438340afffe") + ("словарь" + chr(10) + "слово" + chr(10)).encode("utf-16-le")
        self.assertEqual(decode_dictionary(sample).splitlines()[:2], ["575484", "словарь"])

    def test_git_blob_id(self):
        self.assertEqual(blob_hash(b"hello"), "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0")

    def test_neighbors_are_close_not_arbitrary(self):
        self.assertIn("о", NEIGHBORS["р"])
        self.assertIn("и", NEIGHBORS["м"])
        self.assertNotIn("й", NEIGHBORS["ю"])

    def test_word_filter_removes_latin_and_hunspell_flags(self):
        chosen, all_valid, _ = select_words("привет\nслово\nhello\n",
                                              "3\nсловарик/A\nГородок/E\n", minimum=3)
        self.assertEqual(chosen[:2], ["привет", "слово"])
        self.assertIn("городок", all_valid)
        self.assertNotIn("hello", chosen)

    def test_40_distinct_typo_pairs_are_not_words(self):
        words = ["привет", "сегодня", "спасибо", "пожалуйста"]
        valid = set(words) | {"привит", "спас", "привета"}
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "pairs.gz"
            m = create_pairs(words, valid, out, n=10)
            self.assertEqual((m["unique_typos"], m["distinct_pairs"]), (40, 40))
            with gzip.open(out, "rt", encoding="utf-8") as file:
                rows = [r.split("\t") for r in file.read().splitlines()[1:]]
            self.assertEqual(len({x[0] for x in rows}), 40)
            self.assertTrue(all(x[0] not in valid for x in rows))
            self.assertGreaterEqual(len(set(x[2] for x in rows)), 3)

    def test_variants_never_return_original(self):
        self.assertTrue(all(a != "клавиатура" for a, _ in variants("клавиатура")))


if __name__ == "__main__":
    unittest.main()
