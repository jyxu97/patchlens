"""add prompt_version_id to analysis_runs

Revision ID: 004
Revises: 003
Create Date: 2025-01-01 00:00:03.000000
"""

import sqlalchemy as sa
from alembic import op

revision = "004"
down_revision = "003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "analysis_runs",
        sa.Column("prompt_version_id", sa.Integer(), nullable=True),
    )
    op.create_foreign_key(
        "fk_analysis_runs_prompt_version",
        "analysis_runs",
        "prompt_versions",
        ["prompt_version_id"],
        ["id"],
        ondelete="SET NULL",
    )


def downgrade() -> None:
    op.drop_constraint("fk_analysis_runs_prompt_version", "analysis_runs", type_="foreignkey")
    op.drop_column("analysis_runs", "prompt_version_id")
