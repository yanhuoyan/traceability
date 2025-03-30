package com.yanhuoyan.traceability.engine;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 变量追踪结果类
 * 用于存储和管理变量追踪操作的结果数据
 */
public class TraceResult {
    private final PsiVariable targetVariable; // 目标变量
    private final List<TraceNode> traceNodes; // 追踪节点列表

    /**
     * 构造函数
     * 
     * @param targetVariable 要追踪的目标变量
     */
    public TraceResult(@NotNull PsiVariable targetVariable) {
        this.targetVariable = targetVariable;
        this.traceNodes = new ArrayList<>();
    }

    /**
     * 获取被追踪的变量
     * 
     * @return 目标变量
     */
    @NotNull
    public PsiVariable getTargetVariable() {
        return targetVariable;
    }

    /**
     * 获取所有追踪节点
     * 
     * @return 追踪节点列表
     */
    @NotNull
    public List<TraceNode> getTraceNodes() {
        return traceNodes;
    }

    /**
     * 添加追踪节点到结果中
     * 
     * @param node 要添加的追踪节点
     */
    public void addTraceNode(@NotNull TraceNode node) {
        traceNodes.add(node);
    }

    /**
     * 获取根追踪节点（没有父节点的节点）
     * 
     * @return 根节点列表
     */
    @NotNull
    public List<TraceNode> getRootNodes() {
        List<TraceNode> rootNodes = new ArrayList<>();
        for (TraceNode node : traceNodes) {
            if (node.getParent() == null) {
                rootNodes.add(node);
            }
        }
        return rootNodes;
    }

    /**
     * 查找或创建对应方法调用的节点
     * 确保相同的方法调用只有一个根节点
     * 
     * @param methodCall 方法调用表达式
     * @return 对应的追踪节点
     */
    public TraceNode findOrCreateCallNode(PsiMethodCallExpression methodCall) {
        // 在根节点中查找对应的方法调用节点
        for (TraceNode rootNode : getRootNodes()) {
            if (rootNode.getType() == TraceNodeType.METHOD_CALL && 
                    rootNode.getElement() instanceof PsiMethodCallExpression &&
                    methodCall.getText().equals(rootNode.getElement().getText())) {
                return rootNode;
            }
        }
        
        // 如果没找到，创建新节点
        TraceNode callNode = new TraceNode(
                TraceNodeType.METHOD_CALL,
                methodCall,
                "方法调用: " + methodCall.getText(),
                null
        );
        addTraceNode(callNode);
        return callNode;
    }

    /**
     * 追踪树节点类
     * 表示追踪树中的一个节点，记录变量赋值信息
     */
    public static class TraceNode {
        private final TraceNodeType type; // 节点类型
        private final PsiElement element; // PSI元素
        private final String description; // 描述信息
        private final TraceNode parent; // 父节点
        private final List<TraceNode> children; // 子节点列表

        /**
         * 构造函数
         * 
         * @param type 节点类型
         * @param element PSI元素
         * @param description 描述信息
         * @param parent 父节点
         */
        public TraceNode(@NotNull TraceNodeType type, @NotNull PsiElement element, 
                        @NotNull String description, TraceNode parent) {
            this.type = type;
            this.element = element;
            this.description = description;
            this.parent = parent;
            this.children = new ArrayList<>();
            
            // 如果父节点不为空，则将此节点添加为父节点的子节点
            if (parent != null) {
                parent.addChild(this);
            }
        }

        /**
         * 获取节点类型
         * 
         * @return 节点类型
         */
        @NotNull
        public TraceNodeType getType() {
            return type;
        }

        /**
         * 获取PSI元素
         * 
         * @return PSI元素
         */
        @NotNull
        public PsiElement getElement() {
            return element;
        }

        /**
         * 获取描述信息
         * 
         * @return 描述信息
         */
        @NotNull
        public String getDescription() {
            return description;
        }

        /**
         * 获取父节点
         * 
         * @return 父节点
         */
        public TraceNode getParent() {
            return parent;
        }

        /**
         * 获取子节点列表
         * 
         * @return 子节点列表
         */
        @NotNull
        public List<TraceNode> getChildren() {
            return children;
        }

        /**
         * 添加子节点
         * 
         * @param child 子节点
         */
        public void addChild(@NotNull TraceNode child) {
            children.add(child);
        }
    }

    /**
     * 追踪节点类型
     * 定义追踪结果中各节点的类型
     */
    public enum TraceNodeType {
        DECLARATION,       // 变量声明
        LOCAL_ASSIGNMENT,  // 方法内赋值
        PARAMETER,         // 方法参数
        METHOD_CALL,       // 方法调用返回值
        FIELD_ACCESS,      // 字段访问
        UNKNOWN,           // 未知来源
        FIELD_ASSIGNMENT,  // 字段赋值
        FIELD_INITIALIZATION, // 字段初始化
        CONSTRUCTOR_ASSIGNMENT, // 构造函数中的赋值
        FIELD_REFERENCE,   // 字段引用
        QUALIFIER,         // 调用者限定符
        CONSTRUCTOR_ARG,   // 构造函数参数
        METHOD_ARG,        // 方法参数引用
        METHOD_RETURN      // 方法返回值
    }
} 