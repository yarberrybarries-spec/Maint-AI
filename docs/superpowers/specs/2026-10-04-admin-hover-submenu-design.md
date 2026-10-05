# 管理端悬停子菜单导航设计

## 目标

将管理端顶部导航中的“任务”“知识中心”“系统”改为带悬停浮层的一级菜单，减少页面顶部长期占用的导航空间，并让具体业务入口通过子菜单选择。

## 交互规则

- 一级菜单悬停时显示对应浮层子菜单。
- 一级菜单悬停只打开浮层，不触发路由跳转。
- 有子菜单的一级菜单点击时只切换浮层开合状态，不直接跳转。
- 子菜单项必须通过点击触发路由跳转。
- 没有子菜单的一级菜单（总览、AI 助手、我的）仍然通过点击直接导航。
- 鼠标离开一级菜单和浮层整体区域后关闭浮层；移动端菜单改为点击展开，避免依赖 hover。

## 菜单映射

### 任务

- 任务列表：`/admin/tasks?tab=list`
- 沉淀审核：`/admin/tasks?tab=review`
- 任务证据候选：`/admin/tasks?tab=evidence`

### 知识中心

- 知识库管理：`/admin/knowledge-center?tab=knowledge`
- 知识图谱：`/admin/knowledge-center?tab=graph`
- 标准规程：`/admin/knowledge-center?tab=procedures`
- 回答纠错：`/admin/knowledge-center?tab=answer-feedback`
- 诊断规则：`/admin/knowledge-center?tab=domain-rules`
- 案例审核：`/admin/knowledge-center?tab=case-review`

### 系统

- 用户管理：`/admin/system?tab=users`
- 通知中心：`/admin/system?tab=notify`

## 实现范围

- 修改 `AdminLayout.vue` 导航数据、展开状态和点击行为，并让 `AdminTasks.vue` 将 `query.tab` 同步到现有任务 Tab。
- 在 `admin-vivo.css` 增加浮层定位、阴影、子项状态和移动端样式。
- 删除一级菜单的 hover 自动跳转逻辑。
- 不修改业务页面、API、路由定义和后端代码。

## 验证

- 桌面端悬停三个一级菜单可显示对应子菜单。
- 鼠标移动到子菜单不会关闭浮层；点击子项后正确进入对应 Tab。
- 一级菜单悬停不会改变 URL。
- 无子菜单项仍可点击进入页面。
- 移动端菜单可点击展开且不依赖 hover。
- `npm run build` 通过。
