"""OpenAI integration with Pydantic v2 output validation.

Key differentiator: `ReviewResult.model_validate(data)` raises a structured
`ValidationError` instead of silently accepting malformed JSON from the model.
"""

from __future__ import annotations

import json

from openai import AsyncOpenAI
from pydantic import ValidationError

from app.config import settings
from app.schemas.review import ReviewResult

_client = AsyncOpenAI(api_key=settings.openai_api_key)

SYSTEM_PROMPT = """\
You are an expert code reviewer. Analyze the provided pull request diff and return a
JSON object with exactly this schema:
{
  "summary": {
    "overview": "<string>",
    "key_changes": ["<string>", ...],
    "impact": "<string>"
  },
  "risk_assessment": {
    "overall_risk": "<LOW|MEDIUM|HIGH|CRITICAL>",
    "risk_factors": ["<string>", ...],
    "risk_score": <float 0-10>
  },
  "suggested_tests": ["<string>", ...],
  "review_checklist": ["<string>", ...]
}
Return only valid JSON, no markdown fences.
"""


async def analyze(
    diff: str,
    risk_score: float,
    risk_factors: list[str],
    rag_context: list[str],
) -> ReviewResult:
    context_block = "\n".join(rag_context) if rag_context else "No prior context available."
    user_content = (
        f"Risk score: {risk_score}/10\n"
        f"Risk factors: {', '.join(risk_factors) or 'none'}\n\n"
        f"Similar past reviews:\n{context_block}\n\n"
        f"Diff:\n{diff[:12_000]}"  # guard against token overflow
    )

    response = await _client.chat.completions.create(
        model=settings.openai_model,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ],
        temperature=0.2,
        response_format={"type": "json_object"},
    )

    content = response.choices[0].message.content
    data = json.loads(content)

    # Pydantic v2 structural validation – raises ValidationError on bad schema.
    try:
        result = ReviewResult.model_validate(data)
    except ValidationError as exc:
        raise ValueError(f"OpenAI returned invalid schema: {exc}") from exc

    return result
