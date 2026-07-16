from dataclasses import dataclass

from app.services.diff_parser import FileDiff

# High-risk path patterns
HIGH_RISK_PATTERNS = [
    "auth",
    "security",
    "payment",
    "crypto",
    "secret",
    "password",
    "token",
    "credential",
    "migration",
    "schema",
]

# High-risk file extensions
HIGH_RISK_EXTENSIONS = {".sql", ".sh", ".bash", ".yml", ".yaml", ".env", ".tf", ".tfvars"}


@dataclass
class FileRisk:
    filename: str
    risk_score: float
    risk_factors: list[str]


def score_file(diff: FileDiff) -> FileRisk:
    factors: list[str] = []
    score = 0.0

    lower = diff.filename.lower()

    for pattern in HIGH_RISK_PATTERNS:
        if pattern in lower:
            factors.append(f"filename contains '{pattern}'")
            score += 2.0
            break

    ext = "." + diff.filename.rsplit(".", 1)[-1] if "." in diff.filename else ""
    if ext in HIGH_RISK_EXTENSIONS:
        factors.append(f"high-risk file type ({ext})")
        score += 1.5

    churn = diff.additions + diff.deletions
    if churn > 200:
        factors.append(f"large change ({churn} lines)")
        score += 2.0
    elif churn > 50:
        factors.append(f"moderate change ({churn} lines)")
        score += 1.0

    return FileRisk(filename=diff.filename, risk_score=min(score, 10.0), risk_factors=factors)


def score_pr(file_diffs: list[FileDiff]) -> tuple[float, list[str]]:
    """Return (overall_score 0-10, aggregated_factors)."""
    if not file_diffs:
        return 0.0, []

    file_risks = [score_file(d) for d in file_diffs]
    avg = sum(r.risk_score for r in file_risks) / len(file_risks)
    all_factors = [f for r in file_risks for f in r.risk_factors]
    return round(min(avg, 10.0), 2), list(dict.fromkeys(all_factors))
