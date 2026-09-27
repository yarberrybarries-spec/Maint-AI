"""已完成检修任务的语义审核。Agent只输出事实，不决定最终审批。"""
import json
import logging
from typing import Any, Dict
from services.llm.service import get_llm_service
from schemas.task_auto_review import TaskAutoReviewResult

logger = logging.getLogger(__name__)

SYSTEM = """你是检修任务质量分析助手。只分析任务快照，不直接决定是否通过。
必须只输出JSON对象，字段为：
processRecordComplete(0-8), operationSpecificityScore(0-5), exceptionHandlingScore(0-3),
functionalTestScore(0-6), followupScore(0-3), manualStepMatchScore(0-7),
parameterSafetyScore(0-5), exceptionNormScore(0-3), taskOperationConsistencyScore(0-4),
entityTerminologyScore(0-3), stepMappingScore(0-4), resultMappingScore(0-2),
evidenceChainScore(0-1), operationResultConflict(true/false), warnings(array).
文字说明可以作为过程证据；不要因为没有图片直接判定失败；只根据快照中已有内容判断。"""

class TaskAutoReviewService:
    def __init__(self): self.llm = get_llm_service()

    async def review(self, snapshot: Dict[str, Any], request_id: str, task_id: int, evidence_version: int) -> Dict[str, Any]:
        try:
            raw = await self.llm.chat([
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": json.dumps(snapshot, ensure_ascii=False)},
            ], temperature=0, response_format={"type": "json_object"})
            content = raw.get("content", "")
            data = json.loads(content)
            facts = {k: v for k, v in data.items() if k != "warnings"}
            return TaskAutoReviewResult(success=True, requestId=request_id, taskId=task_id,
                evidenceVersion=evidence_version, semanticFacts=facts,
                warnings=data.get("warnings", []), model={"name": self.llm.model, "requestId": raw.get("request_id", "")}).model_dump(by_alias=True)
        except Exception as exc:
            logger.exception("任务自动审核Agent失败 taskId=%s", task_id)
            return TaskAutoReviewResult(success=False, requestId=request_id, taskId=task_id,
                evidenceVersion=evidence_version, error=str(exc)).model_dump(by_alias=True)

_service = None
def get_task_auto_review_service():
    global _service
    if _service is None: _service = TaskAutoReviewService()
    return _service
