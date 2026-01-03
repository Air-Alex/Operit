package com.ai.assistance.operit.core.workflow

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ConditionNode
import com.ai.assistance.operit.data.model.ConditionOperator
import com.ai.assistance.operit.data.model.ExecuteNode
import com.ai.assistance.operit.data.model.ExtractMode
import com.ai.assistance.operit.data.model.ExtractNode
import com.ai.assistance.operit.data.model.LogicNode
import com.ai.assistance.operit.data.model.LogicOperator
import com.ai.assistance.operit.data.model.ParameterValue
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import com.ai.assistance.operit.data.model.WorkflowNode
import com.ai.assistance.operit.data.model.WorkflowNodeConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.Queue

/**
 * 节点执行状态
 */
sealed class NodeExecutionState {
    object Pending : NodeExecutionState()
    object Running : NodeExecutionState()
    data class Success(val result: String) : NodeExecutionState()
    data class Failed(val error: String) : NodeExecutionState()
}

/**
 * 依赖图数据结构
 */
data class DependencyGraph(
    val adjacencyList: Map<String, List<String>>,  // 节点ID -> 后继节点列表
    val inDegree: Map<String, Int>                 // 节点ID -> 入度
)

/**
 * 工作流执行结果
 */
data class WorkflowExecutionResult(
    val workflowId: String,
    val success: Boolean,
    val nodeResults: Map<String, NodeExecutionState>,
    val message: String,
    val executionTime: Long = System.currentTimeMillis()
)

/**
 * 工作流执行器
 * 负责解析和执行工作流
 */
class WorkflowExecutor(private val context: Context) {
    
    private val toolHandler = AIToolHandler.getInstance(context)
    
    companion object {
        private const val TAG = "WorkflowExecutor"
    }

    private fun isSkippedState(state: NodeExecutionState?): Boolean {
        return state is NodeExecutionState.Success && state.result == "跳过"
    }

    private fun parseBooleanLike(value: String): Boolean? {
        val normalized = value.trim().lowercase()
        return when (normalized) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> null
        }
    }

    private fun resolveParameterValue(
        value: ParameterValue,
        nodeResults: Map<String, NodeExecutionState>
    ): String {
        return when (value) {
            is ParameterValue.StaticValue -> value.value
            is ParameterValue.NodeReference -> {
                val refState = nodeResults[value.nodeId]
                when (refState) {
                    is NodeExecutionState.Success -> refState.result
                    is NodeExecutionState.Failed -> throw IllegalStateException("引用的节点 ${value.nodeId} 执行失败")
                    else -> throw IllegalStateException("引用的节点 ${value.nodeId} 尚未完成执行")
                }
            }
        }
    }

    private fun compareValues(leftRaw: String, rightRaw: String, operator: ConditionOperator): Boolean {
        val left = leftRaw
        val right = rightRaw

        fun numericCompare(op: (Double, Double) -> Boolean): Boolean? {
            val a = left.trim().toDoubleOrNull() ?: return null
            val b = right.trim().toDoubleOrNull() ?: return null
            return op(a, b)
        }

        return when (operator) {
            ConditionOperator.EQ -> left == right
            ConditionOperator.NE -> left != right
            ConditionOperator.GT -> numericCompare { a, b -> a > b } ?: (left > right)
            ConditionOperator.GTE -> numericCompare { a, b -> a >= b } ?: (left >= right)
            ConditionOperator.LT -> numericCompare { a, b -> a < b } ?: (left < right)
            ConditionOperator.LTE -> numericCompare { a, b -> a <= b } ?: (left <= right)
            ConditionOperator.CONTAINS -> left.contains(right)
            ConditionOperator.NOT_CONTAINS -> !left.contains(right)
            ConditionOperator.IN, ConditionOperator.NOT_IN -> {
                val items: List<String> = try {
                    val arr = org.json.JSONArray(right)
                    (0 until arr.length()).map { idx -> arr.optString(idx, "") }
                } catch (_: Exception) {
                    right.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
                val contains = items.contains(left)
                if (operator == ConditionOperator.IN) contains else !contains
            }
        }
    }

    private fun extractByRegex(source: String, pattern: String, group: Int, defaultValue: String): String {
        if (pattern.isBlank()) return defaultValue
        return try {
            val match = Regex(pattern).find(source)
            val groupValue = match?.groups?.get(group)?.value
            groupValue ?: defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    private fun extractByJsonPath(source: String, path: String, defaultValue: String): String {
        if (path.isBlank()) return defaultValue
        val root: Any = try {
            val trimmed = source.trim()
            if (trimmed.startsWith("[")) {
                org.json.JSONArray(trimmed)
            } else {
                org.json.JSONObject(trimmed)
            }
        } catch (_: Exception) {
            return defaultValue
        }

        fun readIndexToken(token: String): Pair<String, List<Int>> {
            val name = token.substringBefore("[")
            val indexes = mutableListOf<Int>()
            var rest = token.substringAfter("[", missingDelimiterValue = "")
            while (rest.isNotEmpty()) {
                val idxStr = rest.substringBefore("]", missingDelimiterValue = "")
                val idx = idxStr.toIntOrNull()
                if (idx != null) indexes.add(idx)
                rest = rest.substringAfter("[", missingDelimiterValue = "")
            }
            return name to indexes
        }

        fun getChild(current: Any?, name: String): Any? {
            return when (current) {
                is org.json.JSONObject -> if (name.isBlank()) current else current.opt(name)
                else -> null
            }
        }

        fun getIndex(current: Any?, index: Int): Any? {
            return when (current) {
                is org.json.JSONArray -> current.opt(index)
                else -> null
            }
        }

        var current: Any? = root
        val segments = path.split('.').map { it.trim() }.filter { it.isNotEmpty() }
        for (seg in segments) {
            val (name, indexes) = readIndexToken(seg)
            if (name.isNotBlank()) {
                current = getChild(current, name)
            }
            for (idx in indexes) {
                current = getIndex(current, idx)
            }
            if (current == null) return defaultValue
        }

        return when (current) {
            null -> defaultValue
            is org.json.JSONObject -> current.toString()
            is org.json.JSONArray -> current.toString()
            else -> current.toString()
        }
    }

    private fun getReachableNodeIds(
        startNodeIds: List<String>,
        adjacencyList: Map<String, List<String>>
    ): Set<String> {
        val visited = mutableSetOf<String>()
        val queue: ArrayDeque<String> = ArrayDeque()
        for (id in startNodeIds) {
            if (visited.add(id)) {
                queue.addLast(id)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in adjacencyList[current].orEmpty()) {
                if (visited.add(next)) {
                    queue.addLast(next)
                }
            }
        }

        return visited
    }
    
    /**
     * 执行工作流
     * @param workflow 要执行的工作流
     * @param triggerNodeId 指定要触发的节点ID，如果为null则触发所有触发节点
     * @param onNodeStateChange 节点状态变化回调
     * @return 工作流执行结果
     */
    suspend fun executeWorkflow(
        workflow: Workflow,
        triggerNodeId: String? = null,
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit
    ): WorkflowExecutionResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始执行工作流: ${workflow.name} (${workflow.id})")
        
        val nodeResults = mutableMapOf<String, NodeExecutionState>()
        
        try {
            // 1. 找到所有触发节点作为入口
            val allTriggerNodes = workflow.nodes.filterIsInstance<TriggerNode>()
            
            if (allTriggerNodes.isEmpty()) {
                AppLogger.w(TAG, "工作流没有触发节点")
                return@withContext WorkflowExecutionResult(
                    workflowId = workflow.id,
                    success = false,
                    nodeResults = nodeResults,
                    message = "工作流没有触发节点，无法执行"
                )
            }
            
            // 2. 根据 triggerNodeId 决定要执行哪些触发节点
            val triggerNodes = if (triggerNodeId != null) {
                // 如果指定了触发节点ID（通常是定时任务），只执行该触发节点
                val specificNode = allTriggerNodes.find { it.id == triggerNodeId }
                if (specificNode == null) {
                    AppLogger.w(TAG, "指定的触发节点不存在: $triggerNodeId")
                    return@withContext WorkflowExecutionResult(
                        workflowId = workflow.id,
                        success = false,
                        nodeResults = nodeResults,
                        message = "指定的触发节点不存在: $triggerNodeId"
                    )
                }
                AppLogger.d(TAG, "定时触发: 只执行指定触发节点 ${specificNode.name}")
                listOf(specificNode)
            } else {
                // 如果没有指定触发节点ID（通常是手动触发），执行所有手动触发类型的节点
                val manualTriggers = allTriggerNodes.filter { it.triggerType == "manual" }
                if (manualTriggers.isEmpty()) {
                    AppLogger.w(TAG, "没有手动触发类型的触发节点")
                    return@withContext WorkflowExecutionResult(
                        workflowId = workflow.id,
                        success = false,
                        nodeResults = nodeResults,
                        message = "没有手动触发类型的触发节点"
                    )
                }
                AppLogger.d(TAG, "手动触发: 执行所有手动触发类型的节点")
                manualTriggers
            }
            
            AppLogger.d(TAG, "将执行 ${triggerNodes.size} 个触发节点: ${triggerNodes.joinToString { it.name }}")
            
            // 3. 构建依赖图
            val dependencyGraph = buildDependencyGraph(workflow)
            
            // 4. 检测环
            if (detectCycle(dependencyGraph.adjacencyList, workflow.nodes)) {
                AppLogger.e(TAG, "工作流存在循环依赖，无法执行")
                return@withContext WorkflowExecutionResult(
                    workflowId = workflow.id,
                    success = false,
                    nodeResults = nodeResults,
                    message = "工作流存在循环依赖，无法执行"
                )
            }
            
            // 5. 标记所有触发节点为成功（触发节点本身不需要执行）
            for (triggerNode in triggerNodes) {
                AppLogger.d(TAG, "标记触发节点: ${triggerNode.name} (${triggerNode.id})")
                nodeResults[triggerNode.id] = NodeExecutionState.Success("触发节点")
                onNodeStateChange(triggerNode.id, NodeExecutionState.Success("触发节点"))
            }
            
            // 6. 使用拓扑排序执行所有后续节点
            val executionResult = executeTopologicalOrder(
                startNodeIds = triggerNodes.map { it.id },
                workflow = workflow,
                dependencyGraph = dependencyGraph,
                nodeResults = nodeResults,
                onNodeStateChange = onNodeStateChange
            )
            
            // 如果执行失败，停止整个流程
            if (!executionResult) {
                return@withContext WorkflowExecutionResult(
                    workflowId = workflow.id,
                    success = false,
                    nodeResults = nodeResults,
                    message = "工作流执行失败"
                )
            }
            
            AppLogger.d(TAG, "工作流执行完成: ${workflow.name}")
            
            return@withContext WorkflowExecutionResult(
                workflowId = workflow.id,
                success = true,
                nodeResults = nodeResults,
                message = "工作流执行成功"
            )
            
        } catch (e: Exception) {
            AppLogger.e(TAG, "工作流执行异常", e)
            return@withContext WorkflowExecutionResult(
                workflowId = workflow.id,
                success = false,
                nodeResults = nodeResults,
                message = "工作流执行异常: ${e.message}"
            )
        }
    }
    
    /**
     * 构建邻接表
     */
    private fun buildAdjacencyList(connections: List<WorkflowNodeConnection>): Map<String, List<String>> {
        val adjacencyList = mutableMapOf<String, MutableList<String>>()
        
        for (connection in connections) {
            adjacencyList.getOrPut(connection.sourceNodeId) { mutableListOf() }
                .add(connection.targetNodeId)
        }
        
        return adjacencyList
    }
    
    /**
     * 构建依赖图（包含入度信息）
     */
    private fun buildDependencyGraph(workflow: Workflow): DependencyGraph {
        val adjacencyList = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()
        
        // 初始化所有节点的入度为0
        for (node in workflow.nodes) {
            inDegree[node.id] = 0
            adjacencyList[node.id] = mutableListOf()
        }
        
        // 构建邻接表并计算入度
        for (connection in workflow.connections) {
            adjacencyList.getOrPut(connection.sourceNodeId) { mutableListOf() }
                .add(connection.targetNodeId)
            inDegree[connection.targetNodeId] = (inDegree[connection.targetNodeId] ?: 0) + 1
        }
        
        return DependencyGraph(adjacencyList, inDegree)
    }
    
    /**
     * 使用DFS检测有向图中的环
     * @return true 表示存在环，false 表示无环
     */
    private fun detectCycle(adjacencyList: Map<String, List<String>>, nodes: List<WorkflowNode>): Boolean {
        val visitState = mutableMapOf<String, Int>() // 0=未访问, 1=访问中, 2=已完成
        
        // 初始化所有节点为未访问
        for (node in nodes) {
            visitState[node.id] = 0
        }
        
        fun dfs(nodeId: String): Boolean {
            visitState[nodeId] = 1 // 标记为访问中
            
            // 访问所有后继节点
            for (nextNodeId in adjacencyList[nodeId] ?: emptyList()) {
                when (visitState[nextNodeId]) {
                    1 -> return true // 访问到"访问中"的节点，发现环
                    0 -> if (dfs(nextNodeId)) return true // 递归访问未访问的节点
                    // 2 -> 已完成的节点，跳过
                }
            }
            
            visitState[nodeId] = 2 // 标记为已完成
            return false
        }
        
        // 对每个未访问的节点执行DFS
        for (node in nodes) {
            if (visitState[node.id] == 0) {
                if (dfs(node.id)) {
                    return true
                }
            }
        }
        
        return false
    }
    
    /**
     * 使用拓扑排序执行节点（替换原来的BFS）
     * 确保所有前置依赖节点都完成后才执行当前节点
     * @return 是否执行成功
     */
    private suspend fun executeTopologicalOrder(
        startNodeIds: List<String>,
        workflow: Workflow,
        dependencyGraph: DependencyGraph,
        nodeResults: MutableMap<String, NodeExecutionState>,
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit
    ): Boolean {
        val reachableNodeIds = getReachableNodeIds(startNodeIds, dependencyGraph.adjacencyList)
        val nodeById = workflow.nodes.associateBy { it.id }
        val incomingConnectionsByTarget = workflow.connections.groupBy { it.targetNodeId }
        val triggerNodeIds = workflow.nodes.filterIsInstance<TriggerNode>().map { it.id }.toSet()
        val queue: Queue<String> = LinkedList()
        val currentInDegree = mutableMapOf<String, Int>()

        for (nodeId in reachableNodeIds) {
            if (triggerNodeIds.contains(nodeId)) {
                continue
            }
            currentInDegree[nodeId] = 0
        }

        for ((sourceId, targets) in dependencyGraph.adjacencyList) {
            if (!reachableNodeIds.contains(sourceId)) {
                continue
            }
            if (triggerNodeIds.contains(sourceId)) {
                continue
            }
            for (targetId in targets) {
                if (!reachableNodeIds.contains(targetId)) {
                    continue
                }
                if (triggerNodeIds.contains(targetId)) {
                    continue
                }
                currentInDegree[targetId] = (currentInDegree[targetId] ?: 0) + 1
            }
        }

        // 入度为0的节点加入队列作为执行起点
        for ((nodeId, inDegree) in currentInDegree) {
            if (inDegree == 0) {
                queue.offer(nodeId)
            }
        }
        
        while (queue.isNotEmpty()) {
            val currentNodeId = queue.poll() ?: break
            
            // 检查节点是否已经被执行过
            if (nodeResults.containsKey(currentNodeId)) {
                AppLogger.d(TAG, "节点已被执行，跳过: $currentNodeId")
                continue
            }
            
            // 查找节点
            val node = nodeById[currentNodeId]
            if (node == null) {
                AppLogger.w(TAG, "节点不存在: $currentNodeId")
                continue
            }

            val incomingConnections = incomingConnectionsByTarget[currentNodeId].orEmpty().filter { conn ->
                reachableNodeIds.contains(conn.sourceNodeId)
            }

            val shouldExecute = if (incomingConnections.isEmpty()) {
                true
            } else {
                incomingConnections.any { conn ->
                    val sourceNode = nodeById[conn.sourceNodeId]
                    val sourceState = nodeResults[conn.sourceNodeId]
                    if (isSkippedState(sourceState)) {
                        return@any false
                    }

                    val rawCondition = conn.condition?.trim().orEmpty()
                    val effectiveCondition = if (rawCondition.isBlank() && (sourceNode is ConditionNode || sourceNode is LogicNode)) {
                        "true"
                    } else {
                        rawCondition
                    }

                    if (effectiveCondition.isBlank()) {
                        return@any sourceState is NodeExecutionState.Success
                    }

                    val desiredBool = when (effectiveCondition.lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }

                    val sourceResult = (sourceState as? NodeExecutionState.Success)?.result
                    if (sourceResult == null) {
                        return@any false
                    }

                    if (desiredBool != null) {
                        val actual = parseBooleanLike(sourceResult) ?: false
                        return@any actual == desiredBool
                    }

                    return@any try {
                        Regex(effectiveCondition).containsMatchIn(sourceResult)
                    } catch (_: Exception) {
                        false
                    }
                }
            }

            if (!shouldExecute) {
                AppLogger.d(TAG, "节点条件不满足，跳过执行: ${node.name} (${node.id})")
                nodeResults[node.id] = NodeExecutionState.Success("跳过")
                onNodeStateChange(node.id, NodeExecutionState.Success("跳过"))

                for (nextNodeId in dependencyGraph.adjacencyList[currentNodeId] ?: emptyList()) {
                    if (!currentInDegree.containsKey(nextNodeId)) {
                        continue
                    }
                    currentInDegree[nextNodeId] = (currentInDegree[nextNodeId] ?: 0) - 1
                    if (currentInDegree[nextNodeId] == 0) {
                        queue.offer(nextNodeId)
                    }
                }
                continue
            }
            
            AppLogger.d(TAG, "执行节点: ${node.name} (${node.id})")
            
            // 执行节点
            val executionSuccess = executeNode(node, workflow, incomingConnections, nodeById, nodeResults, onNodeStateChange)
            
            // 如果执行失败，停止整个流程
            if (!executionSuccess) {
                AppLogger.e(TAG, "节点执行失败: ${node.name}")
                return false
            }
            
            // 将后继节点的入度减1，如果入度变为0则加入队列
            for (nextNodeId in dependencyGraph.adjacencyList[currentNodeId] ?: emptyList()) {
                if (!currentInDegree.containsKey(nextNodeId)) {
                    continue
                }
                currentInDegree[nextNodeId] = (currentInDegree[nextNodeId] ?: 0) - 1
                if (currentInDegree[nextNodeId] == 0) {
                    queue.offer(nextNodeId)
                }
            }
        }
        
        return true
    }
    
    private fun resolveParameters(
        node: ExecuteNode,
        nodeResults: Map<String, NodeExecutionState>
    ): List<ToolParameter> {
        return node.actionConfig.map { (key, paramValue) ->
            val resolvedValue = resolveParameterValue(paramValue, nodeResults)
            ToolParameter(name = key, value = resolvedValue)
        }
    }
    
    /**
     * 执行单个节点
     * @return 是否执行成功
     */
    private suspend fun executeNode(
        node: WorkflowNode,
        workflow: Workflow,
        incomingConnections: List<WorkflowNodeConnection>,
        nodeById: Map<String, WorkflowNode>,
        nodeResults: MutableMap<String, NodeExecutionState>,
        onNodeStateChange: (nodeId: String, state: NodeExecutionState) -> Unit
    ): Boolean {
        if (node is TriggerNode) {
            nodeResults[node.id] = NodeExecutionState.Success("触发节点")
            onNodeStateChange(node.id, NodeExecutionState.Success("触发节点"))
            return true
        }

        if (node is ConditionNode) {
            nodeResults[node.id] = NodeExecutionState.Running
            onNodeStateChange(node.id, NodeExecutionState.Running)

            return try {
                val left = resolveParameterValue(node.left, nodeResults)
                val right = resolveParameterValue(node.right, nodeResults)
                val ok = compareValues(left, right, node.operator)
                val result = ok.toString()
                nodeResults[node.id] = NodeExecutionState.Success(result)
                onNodeStateChange(node.id, NodeExecutionState.Success(result))
                true
            } catch (e: Exception) {
                val errorMsg = "节点执行异常: ${e.message}"
                nodeResults[node.id] = NodeExecutionState.Failed(errorMsg)
                onNodeStateChange(node.id, NodeExecutionState.Failed(errorMsg))
                false
            }
        }

        if (node is LogicNode) {
            nodeResults[node.id] = NodeExecutionState.Running
            onNodeStateChange(node.id, NodeExecutionState.Running)

            return try {
                val inputs = incomingConnections.mapNotNull { conn ->
                    val state = nodeResults[conn.sourceNodeId]
                    val result = (state as? NodeExecutionState.Success)?.result ?: return@mapNotNull null
                    if (isSkippedState(state)) return@mapNotNull null
                    parseBooleanLike(result)
                }

                val ok = when (node.operator) {
                    LogicOperator.AND -> inputs.isNotEmpty() && inputs.all { it }
                    LogicOperator.OR -> inputs.any { it }
                }
                val result = ok.toString()
                nodeResults[node.id] = NodeExecutionState.Success(result)
                onNodeStateChange(node.id, NodeExecutionState.Success(result))
                true
            } catch (e: Exception) {
                val errorMsg = "节点执行异常: ${e.message}"
                nodeResults[node.id] = NodeExecutionState.Failed(errorMsg)
                onNodeStateChange(node.id, NodeExecutionState.Failed(errorMsg))
                false
            }
        }

        if (node is ExtractNode) {
            nodeResults[node.id] = NodeExecutionState.Running
            onNodeStateChange(node.id, NodeExecutionState.Running)

            return try {
                var sourceText = resolveParameterValue(node.source, nodeResults)
                if (sourceText.isBlank() && node.source is ParameterValue.StaticValue) {
                    val fallbackSourceId = incomingConnections.firstOrNull()?.sourceNodeId
                    if (fallbackSourceId != null) {
                        val fallbackState = nodeResults[fallbackSourceId]
                        if (fallbackState is NodeExecutionState.Success && !isSkippedState(fallbackState)) {
                            sourceText = fallbackState.result
                        }
                    }
                }

                val extracted = when (node.mode) {
                    ExtractMode.REGEX -> extractByRegex(sourceText, node.expression, node.group, node.defaultValue)
                    ExtractMode.JSON -> extractByJsonPath(sourceText, node.expression, node.defaultValue)
                }

                nodeResults[node.id] = NodeExecutionState.Success(extracted)
                onNodeStateChange(node.id, NodeExecutionState.Success(extracted))
                true
            } catch (e: Exception) {
                val errorMsg = "节点执行异常: ${e.message}"
                nodeResults[node.id] = NodeExecutionState.Failed(errorMsg)
                onNodeStateChange(node.id, NodeExecutionState.Failed(errorMsg))
                false
            }
        }

        if (node !is ExecuteNode) {
            AppLogger.d(TAG, "跳过非执行节点: ${node.name}")
            nodeResults[node.id] = NodeExecutionState.Success("跳过")
            onNodeStateChange(node.id, NodeExecutionState.Success("跳过"))
            return true
        }
        
        // 标记为执行中
        nodeResults[node.id] = NodeExecutionState.Running
        onNodeStateChange(node.id, NodeExecutionState.Running)
        
        try {
            // 检查是否有 actionType
            if (node.actionType.isBlank()) {
                val errorMsg = "节点 ${node.name} 没有配置 actionType"
                AppLogger.w(TAG, errorMsg)
                nodeResults[node.id] = NodeExecutionState.Failed(errorMsg)
                onNodeStateChange(node.id, NodeExecutionState.Failed(errorMsg))
                return false
            }
            
            // 解析参数（支持静态值和节点引用）
            val parameters = resolveParameters(node, nodeResults)
            
            // 构造 AITool
            val tool = AITool(
                name = node.actionType,
                parameters = parameters
            )
            
            AppLogger.d(TAG, "调用工具: ${tool.name}, 参数: ${parameters.size} 个")
            
            // 执行工具
            val result = toolHandler.executeTool(tool)
            
            if (result.success) {
                val resultMessage = result.result.toString()
                AppLogger.d(TAG, "节点执行成功: ${node.name}, 结果: $resultMessage")
                nodeResults[node.id] = NodeExecutionState.Success(resultMessage)
                onNodeStateChange(node.id, NodeExecutionState.Success(resultMessage))
                return true
            } else {
                val errorMsg = result.error ?: "未知错误"
                AppLogger.e(TAG, "节点执行失败: ${node.name}, 错误: $errorMsg")
                nodeResults[node.id] = NodeExecutionState.Failed(errorMsg)
                onNodeStateChange(node.id, NodeExecutionState.Failed(errorMsg))
                return false
            }
            
        } catch (e: Exception) {
            val errorMsg = "节点执行异常: ${e.message}"
            AppLogger.e(TAG, "节点执行异常: ${node.name}", e)
            nodeResults[node.id] = NodeExecutionState.Failed(errorMsg)
            onNodeStateChange(node.id, NodeExecutionState.Failed(errorMsg))
            return false
        }
    }
}

