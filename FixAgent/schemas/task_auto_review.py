from typing import Any, Dict, Optional, List
from pydantic import BaseModel, ConfigDict, Field

class TaskAutoReviewRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")
    schema_version: str = Field(..., alias="schemaVersion")
    prompt_version: str = Field(..., alias="promptVersion")
    request_id: str = Field(..., alias="requestId")
    task_id: int = Field(..., alias="taskId")
    evidence_version: int = Field(..., alias="evidenceVersion")
    snapshot: Dict[str, Any]

class TaskAutoReviewResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)
    success: bool
    request_id: str = Field(..., alias="requestId")
    task_id: int = Field(..., alias="taskId")
    evidence_version: int = Field(..., alias="evidenceVersion")
    semantic_facts: Dict[str, Any] = Field(default_factory=dict, alias="semanticFacts")
    # 模型可能返回字符串或结构化告警；告警只用于展示，不参与规则评分。
    warnings: List[Any] = Field(default_factory=list)
    error: Optional[str] = None
    model: Dict[str, Any] = Field(default_factory=dict)
