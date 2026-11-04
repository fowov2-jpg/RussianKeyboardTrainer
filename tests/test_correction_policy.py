import math

import pytest

from rsk_trainer.correction_policy import CandidateScore, choose_replacement


def rows():
    return [
        CandidateScore("севодня", 0.3, is_keep=True),
        CandidateScore("сегодня", 0.8),
    ]


def decide(candidates=None, **kwargs):
    options = dict(keep_margin=0.1, request_revision=4, current_revision=4)
    options.update(kwargs)
    return choose_replacement("севодня", rows() if candidates is None else candidates, **options)


def test_spelling_autocorrect_only_when_margin_is_exceeded():
    assert decide() == "сегодня"
    assert decide(keep_margin=0.5) == "севодня"  # strict >, not >=


def test_stale_inference_never_overwrites_current_input():
    assert decide(current_revision=5) == "севодня"


def test_sensitive_fields_are_not_changed():
    assert decide(sensitive_field=True) == "севодня"


def test_missing_keep_or_invalid_scores_do_not_cause_replacement():
    assert decide([CandidateScore("сегодня", 99)]) == "севодня"
    assert decide([CandidateScore("севодня", float("nan"), is_keep=True), CandidateScore("сегодня", 99)]) == "севодня"
    assert decide([CandidateScore("севодня", 0, is_keep=True), CandidateScore("сегодня", math.inf)]) == "севодня"


def test_grammar_rewrite_is_not_automatic_by_default():
    candidates = [CandidateScore("севодня", 0, is_keep=True), CandidateScore("сегодня", 4, kind="grammar")]
    assert decide(candidates) == "севодня"
    assert decide(candidates, allow_grammar=True) == "сегодня"


def test_negative_or_nonfinite_margin_is_rejected():
    for bad in (-0.1, float("nan"), float("inf")):
        with pytest.raises(ValueError):
            decide(keep_margin=bad)
