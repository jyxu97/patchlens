from pydantic import BaseModel


class Repository(BaseModel):
    full_name: str
    owner: "Owner"
    name: str


class Owner(BaseModel):
    login: str


Repository.model_rebuild()


class PullRequest(BaseModel):
    number: int
    html_url: str
    head: "Head"


class Head(BaseModel):
    sha: str


PullRequest.model_rebuild()


class WebhookPayload(BaseModel):
    action: str
    pull_request: PullRequest
    repository: Repository
