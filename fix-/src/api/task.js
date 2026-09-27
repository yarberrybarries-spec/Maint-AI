import { request } from './request'

/**
 * 分页查询任务列表
 * @param {object} params - { page, size, status, deviceName, promotedProcedure, promotedGraph }
 */
export function getTaskList(params = {}) {
  return request({ url: '/weixiu/task', method: 'GET', params })
}

/** 获取任务当前生效的步骤；历史修订快照不通过此接口返回。 */
export function getTaskSteps(taskId) {
  return request({ url: `/weixiu/task/${taskId}/steps`, method: 'GET' })
}

/**
 * 沉淀为标准规程（仅管理员）
 * @param {number|string} taskId
 * @returns 创建的规程 ID
 */
export function promoteToProcedure(taskId) {
  return request({ url: `/weixiu/task/${taskId}/promote`, method: 'POST', throwOnError: true })
}

/**
 * 沉淀到知识图谱（仅管理员）
 * @param {number|string} taskId
 * @param {object} graphData - 管理员确认/修正后的图谱数据
 */
export function promoteToGraph(taskId, graphData = {}) {
  return request({ url: `/weixiu/task/${taskId}/promote-to-graph`, method: 'POST', data: graphData, throwOnError: true })
}

/**
 * 管理员跳过沉淀（仅管理员）
 * @param {number|string} taskId
 * @param {'procedure'|'graph'|'both'} type - 跳过类型
 */
export function skipPromotion(taskId, type = 'both') {
  return request({ url: `/weixiu/task/${taskId}/skip-promotion`, method: 'POST', data: { type }, throwOnError: true })
}

export function getTaskAutoReview(taskId) {
  return request({ url: `/weixiu/task/${taskId}/auto-review`, method: 'GET' })
}

export function decideTaskAutoReview(taskId, decision, comment = '') {
  return request({
    url: `/weixiu/task/${taskId}/auto-review/decision`,
    method: 'POST',
    data: { decision, comment },
    throwOnError: true,
  })
}
