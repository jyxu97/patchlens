from dataclasses import dataclass

import httpx

from app.config import settings

GITHUB_API = "https://api.github.com"


@dataclass
class PRMetadata:
    owner: str
    repo: str
    pull_number: int
    title: str
    body: str
    base_sha: str
    head_sha: str
    changed_files: list[str]
    diff: str


async def fetch_pr_metadata(owner: str, repo: str, pull_number: int) -> PRMetadata:
    headers = {
        "Authorization": f"Bearer {settings.github_token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }

    async with httpx.AsyncClient(headers=headers, timeout=30) as client:
        pr_resp = await client.get(f"{GITHUB_API}/repos/{owner}/{repo}/pulls/{pull_number}")
        pr_resp.raise_for_status()
        pr = pr_resp.json()

        files_resp = await client.get(f"{GITHUB_API}/repos/{owner}/{repo}/pulls/{pull_number}/files")
        files_resp.raise_for_status()
        files = files_resp.json()
        changed_files = [f["filename"] for f in files]

        diff_resp = await client.get(
            f"{GITHUB_API}/repos/{owner}/{repo}/pulls/{pull_number}",
            headers={**headers, "Accept": "application/vnd.github.v3.diff"},
        )
        diff_resp.raise_for_status()
        diff = diff_resp.text

    return PRMetadata(
        owner=owner,
        repo=repo,
        pull_number=pull_number,
        title=pr["title"],
        body=pr.get("body") or "",
        base_sha=pr["base"]["sha"],
        head_sha=pr["head"]["sha"],
        changed_files=changed_files,
        diff=diff,
    )
