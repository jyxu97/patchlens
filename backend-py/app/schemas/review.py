from pydantic import BaseModel, Field


class Summary(BaseModel):
    overview: str
    key_changes: list[str]
    impact: str


class RiskAssessment(BaseModel):
    overall_risk: str = Field(pattern="^(LOW|MEDIUM|HIGH|CRITICAL)$")
    risk_factors: list[str]
    risk_score: float = Field(ge=0.0, le=10.0)


class ReviewResult(BaseModel):
    """Pydantic v2 model that validates OpenAI JSON output structurally.

    Replaces Java's manual validate() with automatic field-level validation.
    Raises ValidationError on any schema mismatch before results are persisted.
    """

    summary: Summary
    risk_assessment: RiskAssessment
    suggested_tests: list[str]
    review_checklist: list[str]


class JobResponse(BaseModel):
    id: int
    owner: str
    repo: str
    pull_number: int
    pr_url: str
    head_sha: str
    status: str
    result: ReviewResult | None = None
    error_message: str | None = None
