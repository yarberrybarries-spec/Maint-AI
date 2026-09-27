# 检修结果 AI 审核右下角动态托盘设计

## 目标

用户点击“保存任务结果”后，立即在右下角看到与知识导入、检修步骤生成相同的后台任务托盘。托盘显示“AI审核中”及旋转/流动动效；审核完成后显示完成勾选并自动收起。

## 方案

- 继续复用 `RunningTasksTray.vue`，不新增第二套弹窗样式。
- 在 `notifyStore` 增加 `review` 后台任务类型，并通过任务详情轮询和 WebSocket 通知判断终态。
- `TaskResolutionDialog` 保存成功后登记 `review` 任务，任务标题包含设备或任务标识。
- `RunningTasksTray` 对 `review` 使用与普通任务相同的 indeterminate 旋转环和流动条，完成时复用现有勾选动画。
- 任务结果仍由页面自身刷新展示，托盘只负责后台状态反馈，不阻塞用户继续浏览。

## 状态与兜底

- `PENDING`、`PROCESSING`：保持“AI审核中”，继续显示动态效果。
- `AUTO_ARCHIVED`、`MANUAL_REVIEW`、`MANUAL_APPROVED`、`MANUAL_REJECTED`：视为后台审核阶段完成，显示完成态后自动移除。
- 轮询失败不改变当前托盘状态，下一轮继续重试；WebSocket 到达时立即刷新并收起。
- 尊重 `prefers-reduced-motion`，沿用现有托盘的减弱动效策略。

## 验证

- 保存任务结果后托盘出现并显示旋转动效。
- 刷新页面后仍能通过 localStorage 恢复进行中的审核任务。
- 模拟审核终态后显示完成勾选并自动消失。
- 前端构建通过。
