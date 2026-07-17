"""create analysis_runs table

Revision ID: 002
Revises: 001
Create Date: 2025-01-01 00:00:01.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "002"
down_revision = "001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "analysis_runs",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("owner", sa.String(255), nullable=False),
        sa.Column("repo", sa.String(255), nullable=False),
        sa.Column("pull_number", sa.Integer(), nullable=False),
        sa.Column("pr_url", sa.String(1024), nullable=False),
        sa.Column("diff_hash", sa.String(64), nullable=True),
        sa.Column("cache_hit", sa.Boolean(), nullable=False, server_default="false"),
        sa.Column("github_latency_ms", sa.Integer(), nullable=True),
        sa.Column("retrieval_latency_ms", sa.Integer(), nullable=True),
        sa.Column("llm_latency_ms", sa.Integer(), nullable=True),
        sa.Column("total_latency_ms", sa.Integer(), nullable=True),
        sa.Column("prompt_tokens", sa.Integer(), nullable=True),
        sa.Column("completion_tokens", sa.Integer(), nullable=True),
        sa.Column("model_name", sa.String(100), nullable=True),
        sa.Column("hallucinated_ref_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("grounding_rate", sa.Float(), nullable=False, server_default="1.0"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
    )
    op.create_index("ix_analysis_runs_owner_repo", "analysis_runs", ["owner", "repo"])


def downgrade() -> None:
    op.drop_index("ix_analysis_runs_owner_repo", table_name="analysis_runs")
    op.drop_table("analysis_runs")
