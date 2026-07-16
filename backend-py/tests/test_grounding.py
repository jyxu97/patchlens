"""Unit tests for grounding validation (hallucination detection)."""

import pytest

from app.services.grounding import validate


def test_all_grounded():
    report = validate(
        risky_files=["src/auth/login.py", "requirements.txt"],
        changed_files=["src/auth/login.py", "requirements.txt", "README.md"],
    )
    assert report.hallucinated_count == 0
    assert report.grounded_count == 2
    assert report.grounding_rate == 1.0
    assert report.hallucinated_paths == []


def test_partial_hallucination():
    report = validate(
        risky_files=["src/auth/login.py", "src/auth/ghost.py"],
        changed_files=["src/auth/login.py"],
    )
    assert report.hallucinated_count == 1
    assert report.hallucinated_paths == ["src/auth/ghost.py"]
    assert report.grounded_count == 1
    assert report.grounding_rate == 0.5


def test_all_hallucinated():
    report = validate(
        risky_files=["invented/file.py"],
        changed_files=["real/file.py"],
    )
    assert report.hallucinated_count == 1
    assert report.grounded_count == 0
    assert report.grounding_rate == 0.0


def test_empty_risky_files_returns_perfect_score():
    report = validate(risky_files=[], changed_files=["src/main.py"])
    assert report.total_risky_files == 0
    assert report.grounding_rate == 1.0
