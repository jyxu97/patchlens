import hashlib
import re
from dataclasses import dataclass


@dataclass
class FileDiff:
    filename: str
    additions: int
    deletions: int
    patch: str
    content_hash: str


def parse_diff(raw_diff: str) -> list[FileDiff]:
    """Split a unified diff into per-file FileDiff objects and attach a SHA-256 content hash."""
    file_blocks: list[str] = re.split(r"(?=^diff --git )", raw_diff, flags=re.MULTILINE)
    results: list[FileDiff] = []

    for block in file_blocks:
        if not block.strip():
            continue

        filename_match = re.search(r"^\+\+\+ b/(.+)$", block, re.MULTILINE)
        if not filename_match:
            continue
        filename = filename_match.group(1)

        additions = len(re.findall(r"^\+(?!\+\+)", block, re.MULTILINE))
        deletions = len(re.findall(r"^-(?!--)", block, re.MULTILINE))
        content_hash = hashlib.sha256(block.encode()).hexdigest()

        results.append(
            FileDiff(
                filename=filename,
                additions=additions,
                deletions=deletions,
                patch=block,
                content_hash=content_hash,
            )
        )

    return results


def overall_hash(file_diffs: list[FileDiff]) -> str:
    combined = "".join(d.content_hash for d in file_diffs)
    return hashlib.sha256(combined.encode()).hexdigest()
