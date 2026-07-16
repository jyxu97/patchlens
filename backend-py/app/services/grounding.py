"""Grounding validation: detect hallucinated file paths in the LLM's review.

The LLM is asked to list risky files it identified. This service checks each
path against the actual set of changed files in the PR. Any path that does not
appear in the real diff is a hallucination.

Example:
    changed_files = ["src/auth/login.py", "requirements.txt"]
    risky_files   = ["src/auth/login.py", "src/auth/nonexistent.py"]
    → hallucinated_paths = ["src/auth/nonexistent.py"]
    → grounding_rate = 0.5
"""

from app.schemas.review import GroundingReport


def validate(risky_files: list[str], changed_files: list[str]) -> GroundingReport:
    """Compare LLM-cited risky file paths against the actual PR changed files.

    Args:
        risky_files:   Paths returned by the LLM in risk_assessment.risky_files.
        changed_files: Real file paths from the GitHub /pulls/{n}/files API.

    Returns:
        GroundingReport with counts and grounding_rate in [0, 1].
    """
    changed_set = set(changed_files)
    total = len(risky_files)

    if total == 0:
        return GroundingReport(
            total_risky_files=0,
            grounded_count=0,
            hallucinated_count=0,
            hallucinated_paths=[],
            grounding_rate=1.0,  # nothing to hallucinate
        )

    hallucinated = [f for f in risky_files if f not in changed_set]
    grounded_count = total - len(hallucinated)

    return GroundingReport(
        total_risky_files=total,
        grounded_count=grounded_count,
        hallucinated_count=len(hallucinated),
        hallucinated_paths=hallucinated,
        grounding_rate=round(grounded_count / total, 4),
    )
