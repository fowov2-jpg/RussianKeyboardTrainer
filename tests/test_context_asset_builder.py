import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from build_context_assets import sentence_digest, grammar


class ContextAssetBuilderTest(unittest.TestCase):
    def test_punctuation_and_case_variants_cannot_cross_sentence_splits(self):
        self.assertEqual(sentence_digest("Привет,   мир!"), sentence_digest("ПРИВЕТ мир."))
        self.assertNotEqual(sentence_digest("Привет мир"), sentence_digest("Мир привет"))

    def test_participles_do_not_become_finite_verb_gender_replacements(self):
        rows = [
            "1\tбросил\tбросить\tVERB\t_\tGender=Masc|Number=Sing|Tense=Past|VerbForm=Fin\t0\troot\t_\t_",
            "2\tбросила\tбросить\tVERB\t_\tGender=Fem|Number=Sing|Tense=Past|VerbForm=Fin\t0\troot\t_\t_",
            "3\tброшенный\tбросить\tVERB\t_\tGender=Masc|Number=Sing|Tense=Past|VerbForm=Part\t0\troot\t_\t_",
        ]
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "ru_gsd-ud-train.conllu").write_text("\n".join(rows), encoding="utf-8")
            forms = grammar(root)
        self.assertNotIn("брошенный", forms)
        self.assertEqual(forms["бросила"]["forms"]["Masc"], "бросил")


if __name__ == "__main__":
    unittest.main()
