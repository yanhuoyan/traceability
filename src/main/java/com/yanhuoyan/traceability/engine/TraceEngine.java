package com.yanhuoyan.traceability.engine;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.yanhuoyan.traceability.util.PsiUtils;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * 变量追踪引擎
 * 负责分析和追踪变量的赋值历史
 */
public class TraceEngine {
    private final Project project; // 项目对象
    private final Map<PsiElement, Integer> processedElements; // 已处理元素集合及其深度
    private final Set<PsiMethod> processedMethods; // 已处理方法集合，避免方法递归调用死循环
    private static final int DEFAULT_MAX_DEPTH = 30; // 默认最大追踪深度
    private int currentDepth; // 当前追踪深度
    private int maxDepth; // 最大追踪深度

    /**
     * 构造函数
     * 
     * @param project 当前项目
     */
    public TraceEngine(@NotNull Project project) {
        this(project, DEFAULT_MAX_DEPTH);
    }
    
    /**
     * 构造函数，指定最大追踪深度
     * 
     * @param project 当前项目
     * @param maxDepth 最大追踪深度
     */
    public TraceEngine(@NotNull Project project, int maxDepth) {
        this.project = project;
        this.processedElements = new HashMap<>();
        this.processedMethods = new HashSet<>();
        this.maxDepth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
        this.currentDepth = 0;
    }
    
    /**
     * 设置最大追踪深度
     * 
     * @param maxDepth 新的最大追踪深度
     */
    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /**
     * 追踪变量的赋值历史
     * 分析变量在方法中的所有赋值来源
     * 
     * @param variable 要追踪的变量
     * @param containingMethod 包含该变量的方法
     * @return 追踪结果
     */
    @NotNull
    public TraceResult traceVariable(@NotNull PsiVariable variable, @NotNull PsiMethod containingMethod) {
        TraceResult result = new TraceResult(variable);
        processedElements.clear();
        processedMethods.clear();
        currentDepth = 0;
        
        // 添加变量声明节点
        TraceResult.TraceNode declarationNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.DECLARATION,
                variable,
                "变量声明: " + variable.getName(),
                null
        );
        result.addTraceNode(declarationNode);
        
        // 检查变量声明时的初始化
        PsiExpression initializer = variable.getInitializer();
        if (initializer != null) {
            traceExpression(initializer, declarationNode, result, containingMethod);
        }
        
        // 查找方法中所有对该变量的赋值表达式
        PsiAssignmentExpression[] assignments = PsiUtils.findAssignmentsInMethod(variable, containingMethod);
        for (PsiAssignmentExpression assignment : assignments) {
            TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    assignment,
                    "局部赋值: " + assignment.getText(),
                    null
            );
            result.addTraceNode(assignmentNode);
            
            // 追踪赋值表达式右侧的值来源
            traceExpression(assignment.getRExpression(), assignmentNode, result, containingMethod);
        }
        
        // 如果变量是参数，追踪方法调用
        if (variable instanceof PsiParameter) {
            traceParameterUsages((PsiParameter) variable, result);
        }
        
        return result;
    }

    /**
     * 追踪表达式的值来源
     * 递归分析表达式的组成部分
     * 
     * @param expression 要追踪的表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceExpression(PsiExpression expression, TraceResult.TraceNode parentNode, 
                               TraceResult result, PsiMethod containingMethod) {
        // 如果表达式为空，则返回
        if (expression == null) {
            return;
        }
        
        // 处理循环引用 - 只有当处理深度更浅时才重新处理
        Integer lastDepth = processedElements.get(expression);
        if (lastDepth != null && lastDepth <= currentDepth) {
            TraceResult.TraceNode cycleNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    expression,
                    "重复表达式: " + expression.getText(),
                    parentNode
            );
            result.addTraceNode(cycleNode);
            return;
        }
        
        // 检查是否超出最大深度
        if (currentDepth >= maxDepth) {
            TraceResult.TraceNode depthLimitNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.UNKNOWN,
                    expression,
                    "达到最大追踪深度 (" + maxDepth + ")",
                    parentNode
            );
            result.addTraceNode(depthLimitNode);
            return;
        }
        
        // 标记当前表达式为已处理，记录当前深度
        processedElements.put(expression, currentDepth);
        currentDepth++;
        
        try {
            // 根据表达式类型进行不同处理
            if (expression instanceof PsiReferenceExpression) {
                traceReference((PsiReferenceExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiMethodCallExpression) {
                traceMethodCall((PsiMethodCallExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiNewExpression) {
                traceConstructorCall((PsiNewExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiArrayInitializerExpression) {
                traceArrayInitializer((PsiArrayInitializerExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiConditionalExpression) {
                traceConditionalExpression((PsiConditionalExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiBinaryExpression) {
                traceBinaryExpression((PsiBinaryExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiLiteralExpression) {
                // 处理字面量
                TraceResult.TraceNode literalNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                        expression,
                        "字面量: " + expression.getText(),
                        parentNode
                );
                result.addTraceNode(literalNode);
            } else {
                // 处理其他类型表达式
                TraceResult.TraceNode otherNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.UNKNOWN,
                        expression,
                        "其他表达式: " + expression.getText(),
                        parentNode
                );
                result.addTraceNode(otherNode);
            }
        } finally {
            currentDepth--;
        }
    }

    /**
     * 追踪引用表达式
     * 分析变量引用的来源
     * 
     * @param refExpr 引用表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceReference(PsiReferenceExpression refExpr, TraceResult.TraceNode parentNode, 
                              TraceResult result, PsiMethod containingMethod) {
        // 解析引用表达式指向的元素
        PsiElement resolved = refExpr.resolve();
        
        if (resolved instanceof PsiVariable) {
            PsiVariable variable = (PsiVariable) resolved;
            
            // 根据变量类型确定节点类型和描述
            TraceResult.TraceNodeType nodeType;
            String description;
            
            if (variable instanceof PsiField) {
                nodeType = TraceResult.TraceNodeType.FIELD_ACCESS;
                description = "字段访问: " + variable.getName();
                
                // 创建字段节点
                TraceResult.TraceNode fieldNode = new TraceResult.TraceNode(
                        nodeType,
                        refExpr,
                        description,
                        parentNode
                );
                result.addTraceNode(fieldNode);
                
                // 尝试追踪字段初始化
                PsiExpression fieldInitializer = ((PsiField) variable).getInitializer();
                if (fieldInitializer != null) {
                    TraceResult.TraceNode initNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.FIELD_ACCESS,
                            fieldInitializer,
                            "字段初始化: " + fieldInitializer.getText(),
                            fieldNode
                    );
                    result.addTraceNode(initNode);
                    traceExpression(fieldInitializer, initNode, result, containingMethod);
                }
                
                // 尝试查找所有赋值点
                Collection<PsiReference> fieldRefs = ReferencesSearch.search(variable).findAll();
                if (!fieldRefs.isEmpty()) {
                    for (PsiReference fieldRef : fieldRefs) {
                        PsiElement element = fieldRef.getElement();
                        
                        if (element.getParent() instanceof PsiAssignmentExpression) {
                            PsiAssignmentExpression assignment = (PsiAssignmentExpression) element.getParent();
                            if (assignment.getLExpression().equals(element)) {
                                PsiMethod assignmentMethod = PsiTreeUtil.getParentOfType(assignment, PsiMethod.class);
                                if (assignmentMethod != null && !processedMethods.contains(assignmentMethod)) {
                                    TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                                            TraceResult.TraceNodeType.FIELD_ACCESS,
                                            assignment,
                                            "字段赋值: " + assignment.getText() + " (在方法: " + assignmentMethod.getName() + ")",
                                            fieldNode
                                    );
                                    result.addTraceNode(assignmentNode);
                                    
                                    processedMethods.add(assignmentMethod);
                                    traceExpression(assignment.getRExpression(), assignmentNode, result, assignmentMethod);
                                    processedMethods.remove(assignmentMethod);
                                }
                            }
                        }
                    }
                }
            } else if (variable instanceof PsiParameter) {
                nodeType = TraceResult.TraceNodeType.PARAMETER;
                description = "参数: " + variable.getName();
                
                // 创建参数节点
                TraceResult.TraceNode paramNode = new TraceResult.TraceNode(
                        nodeType,
                        refExpr,
                        description,
                        parentNode
                );
                result.addTraceNode(paramNode);
                
                // 如果是当前方法的参数，且不在当前方法中，尝试查找调用
                if (!PsiTreeUtil.isAncestor(containingMethod, variable, false)) {
                    // 寻找方法调用
                    PsiMethod method = PsiTreeUtil.getParentOfType(variable, PsiMethod.class);
                    if (method != null && !processedMethods.contains(method)) {
                        TraceResult.TraceNode methodNode = new TraceResult.TraceNode(
                                TraceResult.TraceNodeType.METHOD_CALL,
                                method,
                                "来自方法: " + method.getName() + "()",
                                paramNode
                        );
                        result.addTraceNode(methodNode);
                        
                        // 获取参数索引
                        int paramIndex = -1;
                        PsiParameterList paramList = method.getParameterList();
                        PsiParameter[] params = paramList.getParameters();
                        for (int i = 0; i < params.length; i++) {
                            if (params[i].equals(variable)) {
                                paramIndex = i;
                                break;
                            }
                        }
                        
                        if (paramIndex >= 0) {
                            // 搜索对此方法的调用
                            processedMethods.add(method);
                            traceMethodParameterUsages(method, paramIndex, methodNode, result);
                            processedMethods.remove(method);
                        }
                    }
                }
            } else {
                nodeType = TraceResult.TraceNodeType.LOCAL_ASSIGNMENT;
                description = "变量引用: " + variable.getName();
                
                // 创建引用节点
                TraceResult.TraceNode refNode = new TraceResult.TraceNode(
                        nodeType,
                        refExpr,
                        description,
                        parentNode
                );
                result.addTraceNode(refNode);
                
                // 如果是当前方法内的局部变量，继续追踪其赋值来源
                if (variable instanceof PsiLocalVariable) {
                    boolean isInCurrentMethod = PsiTreeUtil.isAncestor(containingMethod, variable, true);
                    PsiMethod variableMethod = isInCurrentMethod ? containingMethod : PsiTreeUtil.getParentOfType(variable, PsiMethod.class);
                    
                    if (variableMethod != null && (!processedMethods.contains(variableMethod) || isInCurrentMethod)) {
                        if (!isInCurrentMethod) {
                            processedMethods.add(variableMethod);
                        }
                        
                        // 检查变量初始化
                        PsiExpression initializer = variable.getInitializer();
                        if (initializer != null) {
                            TraceResult.TraceNode initNode = new TraceResult.TraceNode(
                                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                                    initializer,
                                    (isInCurrentMethod ? "变量初始化: " : "外部变量初始化: ") + initializer.getText(),
                                    refNode
                            );
                            result.addTraceNode(initNode);
                            traceExpression(initializer, initNode, result, variableMethod);
                        }
                        
                        // 查找变量的所有赋值表达式
                        PsiAssignmentExpression[] assignments = PsiUtils.findAssignmentsInMethod(variable, variableMethod);
                        for (PsiAssignmentExpression assignment : assignments) {
                            TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                                    assignment,
                                    (isInCurrentMethod ? "局部赋值: " : "外部方法赋值: ") + assignment.getText() + 
                                    (!isInCurrentMethod ? " (在方法: " + variableMethod.getName() + ")" : ""),
                                    refNode
                            );
                            result.addTraceNode(assignmentNode);
                            
                            // 追踪赋值表达式右侧的值来源
                            traceExpression(assignment.getRExpression(), assignmentNode, result, variableMethod);
                        }
                        
                        if (!isInCurrentMethod) {
                            processedMethods.remove(variableMethod);
                        }
                    }
                }
            }
        } else {
            // 解析引用到了非变量元素（可能是方法等）
            TraceResult.TraceNode unknownNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.UNKNOWN,
                    refExpr,
                    "非变量引用: " + refExpr.getText(),
                    parentNode
            );
            result.addTraceNode(unknownNode);
        }
    }

    /**
     * 追踪方法调用表达式
     * 分析方法调用返回值的来源
     * 
     * @param methodCall 方法调用表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceMethodCall(PsiMethodCallExpression methodCall, TraceResult.TraceNode parentNode, 
                               TraceResult result, PsiMethod containingMethod) {
        // 解析方法调用指向的方法
        PsiMethod method = methodCall.resolveMethod();
        if (method == null) {
            return;
        }
        
        // 追踪调用方法的参数
        PsiExpressionList argList = methodCall.getArgumentList();
        if (argList != null) {
            PsiExpression[] args = argList.getExpressions();
            PsiParameterList paramList = method.getParameterList();
            PsiParameter[] params = paramList.getParameters();
            
            for (int i = 0; i < Math.min(args.length, params.length); i++) {
                String paramName = params[i].getName();
                TraceResult.TraceNode argNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.PARAMETER,
                        args[i],
                        "方法参数 " + paramName + ": " + args[i].getText(),
                        parentNode
                );
                result.addTraceNode(argNode);
                traceExpression(args[i], argNode, result, containingMethod);
            }
        }
        
        // 如果方法已经处理过，避免循环递归，但仍允许追踪参数
        if (processedMethods.contains(method)) {
            TraceResult.TraceNode cycleNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.METHOD_CALL,
                    methodCall,
                    "已追踪过的方法调用: " + method.getName() + "()",
                    parentNode
            );
            result.addTraceNode(cycleNode);
            return;
        }
        
        // 创建方法调用节点
        TraceResult.TraceNode methodNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                methodCall,
                "方法调用: " + method.getName() + "()",
                parentNode
        );
        result.addTraceNode(methodNode);
        
        // 标记方法为已处理
        processedMethods.add(method);
        
        // 分析方法体中的返回语句
        if (!method.isConstructor() && method.getBody() != null) {
            // 查找所有return语句
            PsiReturnStatement[] returns = PsiTreeUtil.findChildrenOfType(method.getBody(), PsiReturnStatement.class).toArray(PsiReturnStatement.EMPTY_ARRAY);
            
            if (returns.length > 0) {
                for (PsiReturnStatement returnStmt : returns) {
                    PsiExpression returnValue = returnStmt.getReturnValue();
                    if (returnValue != null) {
                        TraceResult.TraceNode returnNode = new TraceResult.TraceNode(
                                TraceResult.TraceNodeType.METHOD_CALL,
                                returnStmt,
                                "返回值: " + returnValue.getText(),
                                methodNode
                        );
                        result.addTraceNode(returnNode);
                        
                        // 递归分析返回值表达式
                        traceExpression(returnValue, returnNode, result, method);
                    }
                }
            } else {
                // 如果没有显式的返回语句，尝试追踪方法中变量的使用
                PsiCodeBlock body = method.getBody();
                if (body != null) {
                    PsiStatement[] statements = body.getStatements();
                    if (statements.length > 0) {
                        TraceResult.TraceNode bodyNode = new TraceResult.TraceNode(
                                TraceResult.TraceNodeType.METHOD_CALL,
                                body,
                                "方法体执行分析",
                                methodNode
                        );
                        result.addTraceNode(bodyNode);
                        
                        // 分析方法中的局部变量和赋值
                        analyzeMethodBodyStatements(statements, bodyNode, result, method);
                    }
                }
            }
        }
        
        // 移除方法处理标记，允许其他路径处理相同方法
        processedMethods.remove(method);
    }

    /**
     * 分析方法体中的语句
     * 用于追踪方法内部的执行流程
     * 
     * @param statements 方法体中的语句
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param method 方法
     */
    private void analyzeMethodBodyStatements(PsiStatement[] statements, TraceResult.TraceNode parentNode, 
                                          TraceResult result, PsiMethod method) {
        for (PsiStatement statement : statements) {
            // 分析变量声明语句
            if (statement instanceof PsiDeclarationStatement) {
                PsiElement[] elements = ((PsiDeclarationStatement) statement).getDeclaredElements();
                for (PsiElement element : elements) {
                    if (element instanceof PsiLocalVariable) {
                        PsiLocalVariable localVar = (PsiLocalVariable) element;
                        PsiExpression initializer = localVar.getInitializer();
                        if (initializer != null) {
                            TraceResult.TraceNode varNode = new TraceResult.TraceNode(
                                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                                    localVar,
                                    "局部变量: " + localVar.getName() + " = " + initializer.getText(),
                                    parentNode
                            );
                            result.addTraceNode(varNode);
                            traceExpression(initializer, varNode, result, method);
                        }
                    }
                }
            }
            // 分析赋值语句
            else if (statement instanceof PsiExpressionStatement) {
                PsiExpression expr = ((PsiExpressionStatement) statement).getExpression();
                if (expr instanceof PsiAssignmentExpression) {
                    PsiAssignmentExpression assignment = (PsiAssignmentExpression) expr;
                    TraceResult.TraceNode assignNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                            assignment,
                            "赋值: " + assignment.getText(),
                            parentNode
                    );
                    result.addTraceNode(assignNode);
                    traceExpression(assignment.getRExpression(), assignNode, result, method);
                }
                else if (expr instanceof PsiMethodCallExpression) {
                    traceExpression(expr, parentNode, result, method);
                }
            }
            // 分析if语句
            else if (statement instanceof PsiIfStatement) {
                PsiIfStatement ifStmt = (PsiIfStatement) statement;
                PsiExpression condition = ifStmt.getCondition();
                if (condition != null) {
                    TraceResult.TraceNode condNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                            condition,
                            "条件判断: " + condition.getText(),
                            parentNode
                    );
                    result.addTraceNode(condNode);
                    traceExpression(condition, condNode, result, method);
                }
                
                // 分析if语句体
                PsiStatement thenBranch = ifStmt.getThenBranch();
                if (thenBranch instanceof PsiBlockStatement) {
                    PsiStatement[] thenStatements = ((PsiBlockStatement) thenBranch).getCodeBlock().getStatements();
                    if (thenStatements.length > 0) {
                        TraceResult.TraceNode thenNode = new TraceResult.TraceNode(
                                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                                thenBranch,
                                "Then分支",
                                parentNode
                        );
                        result.addTraceNode(thenNode);
                        analyzeMethodBodyStatements(thenStatements, thenNode, result, method);
                    }
                }
            }
        }
    }

    /**
     * 追踪构造函数调用
     * 分析对象创建的来源
     * 
     * @param newExpr 构造函数调用表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceConstructorCall(PsiNewExpression newExpr, TraceResult.TraceNode parentNode, 
                                    TraceResult result, PsiMethod containingMethod) {
        // 解析构造函数调用指向的构造方法
        PsiMethod constructor = newExpr.resolveConstructor();
        if (constructor == null) {
            return;
        }
        
        // 如果构造函数已处理过，避免循环
        if (processedMethods.contains(constructor)) {
            return;
        }
        
        // 创建构造函数调用节点
        TraceResult.TraceNode constructorNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                newExpr,
                "构造函数调用: new " + constructor.getName() + "()",
                parentNode
        );
        result.addTraceNode(constructorNode);
        
        // 标记构造函数为已处理
        processedMethods.add(constructor);
        
        // 追踪构造函数的参数表达式
        PsiExpressionList argList = newExpr.getArgumentList();
        if (argList != null) {
            PsiExpression[] args = argList.getExpressions();
            PsiParameterList paramList = constructor.getParameterList();
            PsiParameter[] params = paramList.getParameters();
            
            for (int i = 0; i < Math.min(args.length, params.length); i++) {
                TraceResult.TraceNode paramNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.PARAMETER,
                        args[i],
                        "构造参数 #" + (i + 1) + ": " + params[i].getName() + " = " + args[i].getText(),
                        constructorNode
                );
                result.addTraceNode(paramNode);
                
                // 递归追踪参数表达式
                traceExpression(args[i], paramNode, result, containingMethod);
            }
        }
        
        // 移除构造函数处理标记
        processedMethods.remove(constructor);
    }

    /**
     * 追踪数组初始化表达式
     * 分析数组初始化的元素来源
     * 
     * @param arrayInit 数组初始化表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceArrayInitializer(PsiArrayInitializerExpression arrayInit, TraceResult.TraceNode parentNode, 
                                     TraceResult result, PsiMethod containingMethod) {
        // 创建数组初始化节点
        TraceResult.TraceNode arrayNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                arrayInit,
                "数组初始化",
                parentNode
        );
        result.addTraceNode(arrayNode);
        
        // 追踪数组中每个初始化元素的来源
        PsiExpression[] initializers = arrayInit.getInitializers();
        for (int i = 0; i < initializers.length; i++) {
            PsiExpression initializer = initializers[i];
            
            TraceResult.TraceNode elementNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    initializer,
                    "数组元素 [" + i + "]: " + initializer.getText(),
                    arrayNode
            );
            result.addTraceNode(elementNode);
            
            traceExpression(initializer, elementNode, result, containingMethod);
        }
    }

    /**
     * 追踪条件表达式（三元运算符）
     * 分析条件表达式可能的值来源
     * 
     * @param conditionalExpr 条件表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceConditionalExpression(PsiConditionalExpression conditionalExpr, TraceResult.TraceNode parentNode, 
                                          TraceResult result, PsiMethod containingMethod) {
        // 创建条件表达式节点
        TraceResult.TraceNode condNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                conditionalExpr,
                "条件表达式: " + conditionalExpr.getCondition().getText() + " ? ... : ...",
                parentNode
        );
        result.addTraceNode(condNode);
        
        // 追踪条件表达式
        TraceResult.TraceNode conditionNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                conditionalExpr.getCondition(),
                "条件: " + conditionalExpr.getCondition().getText(),
                condNode
        );
        result.addTraceNode(conditionNode);
        traceExpression(conditionalExpr.getCondition(), conditionNode, result, containingMethod);
        
        // 追踪条件为真的表达式
        if (conditionalExpr.getThenExpression() != null) {
            TraceResult.TraceNode thenNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    conditionalExpr.getThenExpression(),
                    "为真分支: " + conditionalExpr.getThenExpression().getText(),
                    condNode
            );
            result.addTraceNode(thenNode);
            traceExpression(conditionalExpr.getThenExpression(), thenNode, result, containingMethod);
        }
        
        // 追踪条件为假的表达式
        if (conditionalExpr.getElseExpression() != null) {
            TraceResult.TraceNode elseNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    conditionalExpr.getElseExpression(),
                    "为假分支: " + conditionalExpr.getElseExpression().getText(),
                    condNode
            );
            result.addTraceNode(elseNode);
            traceExpression(conditionalExpr.getElseExpression(), elseNode, result, containingMethod);
        }
    }

    /**
     * 追踪二元表达式（如 a + b）
     * 分析二元表达式两侧操作数的来源
     * 
     * @param binaryExpr 二元表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceBinaryExpression(PsiBinaryExpression binaryExpr, TraceResult.TraceNode parentNode, 
                                     TraceResult result, PsiMethod containingMethod) {
        // 创建二元表达式节点
        TraceResult.TraceNode binaryNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                binaryExpr,
                "二元表达式: " + binaryExpr.getOperationSign().getText(),
                parentNode
        );
        result.addTraceNode(binaryNode);
        
        // 追踪左侧操作数
        PsiExpression lOperand = binaryExpr.getLOperand();
        if (lOperand != null) {
            TraceResult.TraceNode leftNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    lOperand,
                    "左操作数: " + lOperand.getText(),
                    binaryNode
            );
            result.addTraceNode(leftNode);
            traceExpression(lOperand, leftNode, result, containingMethod);
        }
        
        // 追踪右侧操作数
        PsiExpression rOperand = binaryExpr.getROperand();
        if (rOperand != null) {
            TraceResult.TraceNode rightNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    rOperand,
                    "右操作数: " + rOperand.getText(),
                    binaryNode
            );
            result.addTraceNode(rightNode);
            traceExpression(rOperand, rightNode, result, containingMethod);
        }
    }

    /**
     * 追踪特定方法参数的所有调用点
     * 
     * @param method 方法
     * @param paramIndex 参数索引
     * @param parentNode 父节点
     * @param result 追踪结果
     */
    private void traceMethodParameterUsages(PsiMethod method, int paramIndex, TraceResult.TraceNode parentNode, TraceResult result) {
        // 查找对该方法的所有调用
        Collection<PsiReference> methodRefs = ReferencesSearch.search(method).findAll();
        
        if (methodRefs.isEmpty()) {
            // 如果没有找到调用点，添加提示节点
            TraceResult.TraceNode noCallNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.UNKNOWN,
                    method,
                    "未找到对方法 " + method.getName() + " 的调用",
                    parentNode
            );
            result.addTraceNode(noCallNode);
            return;
        }
        
        boolean foundValidCall = false;
        
        for (PsiReference ref : methodRefs) {
            if (ref.getElement().getParent() instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getElement().getParent();
                PsiExpressionList argList = call.getArgumentList();
                
                // 获取调用时对应参数位置的实参
                PsiExpression[] args = argList.getExpressions();
                if (paramIndex < args.length) {
                    foundValidCall = true;
                    PsiExpression arg = args[paramIndex];
                    
                    // 查找包含该调用的方法
                    PsiMethod callingMethod = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                    
                    String callingMethodName = callingMethod != null ? callingMethod.getName() : "未知方法";
                    String callingClassName = callingMethod != null && callingMethod.getContainingClass() != null ? 
                            callingMethod.getContainingClass().getName() : "未知类";
                    
                    // 为该参数创建节点
                    TraceResult.TraceNode argNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.PARAMETER,
                            arg,
                            "调用参数: " + arg.getText() + " (在 " + callingClassName + "." + callingMethodName + ")",
                            parentNode
                    );
                    result.addTraceNode(argNode);
                    
                    if (callingMethod != null) {
                        // 避免无限递归 - 只有当方法未处理过时才继续追踪
                        if (!processedMethods.contains(callingMethod)) {
                            processedMethods.add(callingMethod);
                            
                            // 在调用方法中追踪参数表达式
                            traceExpression(arg, argNode, result, callingMethod);
                            
                            // 检查是否有更高层次的嵌套调用
                            if (arg instanceof PsiReferenceExpression) {
                                PsiElement resolved = ((PsiReferenceExpression) arg).resolve();
                                if (resolved instanceof PsiParameter) {
                                    // 如果参数是从另一个方法传递来的参数，继续向上追踪
                                    int upperParamIndex = -1;
                                    PsiParameterList upperParamList = callingMethod.getParameterList();
                                    PsiParameter[] upperParams = upperParamList.getParameters();
                                    
                                    for (int i = 0; i < upperParams.length; i++) {
                                        if (upperParams[i].equals(resolved)) {
                                            upperParamIndex = i;
                                            break;
                                        }
                                    }
                                    
                                    if (upperParamIndex >= 0) {
                                        TraceResult.TraceNode upperParamNode = new TraceResult.TraceNode(
                                                TraceResult.TraceNodeType.PARAMETER,
                                                resolved,
                                                "通过参数链传递: " + ((PsiParameter)resolved).getName() + " 从 " + callingMethod.getName() + "()",
                                                argNode
                                        );
                                        result.addTraceNode(upperParamNode);
                                        
                                        // 递归向上追踪调用链
                                        traceMethodParameterUsages(callingMethod, upperParamIndex, upperParamNode, result);
                                    }
                                }
                            }
                            
                            processedMethods.remove(callingMethod);
                        } else {
                            // 如果方法已经处理过，添加循环引用提示
                            TraceResult.TraceNode cycleNode = new TraceResult.TraceNode(
                                    TraceResult.TraceNodeType.UNKNOWN,
                                    call,
                                    "循环调用链: " + callingMethod.getName() + "()",
                                    argNode
                            );
                            result.addTraceNode(cycleNode);
                        }
                    }
                }
            }
        }
        
        if (!foundValidCall) {
            // 如果没有找到有效的参数调用，添加提示节点
            TraceResult.TraceNode noValidCallNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.UNKNOWN,
                    method,
                    "找到方法调用，但没有包含有效的第 " + (paramIndex + 1) + " 号参数",
                    parentNode
            );
            result.addTraceNode(noValidCallNode);
        }
    }

    /**
     * 追踪参数在调用方法中的使用情况
     * 分析参数值的传递来源
     * 
     * @param parameter 要追踪的参数
     * @param result 追踪结果
     */
    private void traceParameterUsages(PsiParameter parameter, TraceResult result) {
        // 获取包含参数的方法
        PsiMethod method = PsiTreeUtil.getParentOfType(parameter, PsiMethod.class);
        if (method == null) {
            return;
        }
        
        // 获取参数索引
        int paramIndex = -1;
        PsiParameterList paramList = method.getParameterList();
        PsiParameter[] parameters = paramList.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].equals(parameter)) {
                paramIndex = i;
                break;
            }
        }
        
        if (paramIndex < 0) {
            return;
        }
        
        // 创建参数节点
        TraceResult.TraceNode paramRootNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.PARAMETER,
                parameter,
                "方法参数: " + parameter.getName() + " 在方法 " + method.getName() + "()",
                null
        );
        result.addTraceNode(paramRootNode);
        
        // 查找对该方法的所有调用
        processedMethods.add(method);
        traceMethodParameterUsages(method, paramIndex, paramRootNode, result);
        processedMethods.remove(method);
    }
} 