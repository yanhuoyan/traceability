package com.yanhuoyan.traceability.engine;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.yanhuoyan.traceability.util.PsiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * 变量追踪引擎
 * 负责分析和追踪变量的赋值历史
 */
public class TraceEngine {
    private final Project project; // 项目对象
    private final Map<String, Integer> processedElements; // 已处理元素集合及其深度，使用表达式和方法的组合作为标识
    private final Set<PsiMethod> processedMethods; // 已处理方法集合，避免方法递归调用死循环
    private static final int DEFAULT_MAX_DEPTH = 100; // 默认最大追踪深度
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
        this.maxDepth = maxDepth > 0 ? maxDepth : 100; // 将默认值从30更新为100
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
            
            // 重置当前深度，每个主赋值点可以独立追踪到最大深度
            currentDepth = 0;
            
            // 追踪赋值表达式右侧的值来源
            traceExpression(assignment.getRExpression(), assignmentNode, result, containingMethod);
        }
        
        // 如果变量是参数，追踪方法调用
        if (variable instanceof PsiParameter) {
            // 重置当前深度，参数追踪可以独立到最大深度
            currentDepth = 0;
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
        
        // 生成唯一标识符，结合表达式文本、位置和当前方法名
        String expressionKey = generateExpressionKey(expression, containingMethod);
        
        // 处理循环引用 - 基于上下文感知的循环检测
        Integer lastDepth = processedElements.get(expressionKey);
        if (lastDepth != null && lastDepth <= currentDepth) {
            // 在相同上下文中已经处理过，可能是循环引用
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
        processedElements.put(expressionKey, currentDepth);
        currentDepth++;
        
        try {
            // 根据表达式类型进行不同处理
            if (expression instanceof PsiReferenceExpression) {
                // 处理引用表达式，调用traceReference方法进行跟踪
                traceReference((PsiReferenceExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiMethodCallExpression) {
                // 处理方法调用表达式，调用traceMethodCall方法进行跟踪
                traceMethodCall((PsiMethodCallExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiNewExpression) {
                // 处理构造函数调用表达式，调用traceConstructorCall方法进行跟踪
                traceConstructorCall((PsiNewExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiArrayInitializerExpression) {
                // 处理数组初始化表达式，调用traceArrayInitializer方法进行跟踪
                traceArrayInitializer((PsiArrayInitializerExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiConditionalExpression) {
                // 处理条件表达式，调用traceConditionalExpression方法进行跟踪
                traceConditionalExpression((PsiConditionalExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiBinaryExpression) {
                // 处理二元表达式，调用traceBinaryExpression方法进行跟踪
                traceBinaryExpression((PsiBinaryExpression) expression, parentNode, result, containingMethod);
            } else if (expression instanceof PsiLiteralExpression) {
                // 处理字面量表达式，创建LOCAL_ASSIGNMENT类型的TraceNode并添加到结果中
                TraceResult.TraceNode literalNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                        expression,
                        "字面量: " + expression.getText(),
                        parentNode
                );
                result.addTraceNode(literalNode);
            } else {
                // 处理其他类型的表达式，创建UNKNOWN类型的TraceNode并添加到结果中
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
     * 生成表达式的唯一标识符
     * 
     * @param expression 表达式
     * @param containingMethod 包含方法
     * @return 唯一标识符
     */
    private String generateExpressionKey(PsiExpression expression, PsiMethod containingMethod) {
        // 获取表达式的文本内容
        String expressionText = expression.getText();
        
        // 获取表达式的源代码位置信息
        PsiFile containingFile = expression.getContainingFile();
        int textOffset = expression.getTextOffset();
        
        // 获取方法信息
        String methodInfo = "unknown";
        if (containingMethod != null) {
            methodInfo = containingMethod.getName();
            if (containingMethod.getContainingClass() != null) {
                methodInfo = containingMethod.getContainingClass().getQualifiedName() + "." + methodInfo;
            }
        }
        
        // 构建唯一标识符
        return String.format("%s#%s#%d#%d", 
                containingFile != null ? containingFile.getName() : "unknown", 
                methodInfo, 
                textOffset, 
                currentDepth);
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
                for (PsiReference ref : fieldRefs) {
                    if (ref.getElement().getParent() instanceof PsiAssignmentExpression) {
                        PsiAssignmentExpression assignment = (PsiAssignmentExpression) ref.getElement().getParent();
                        // 确保左侧是当前字段
                        if (assignment.getLExpression() == ref.getElement()) {
                            // 查找包含该赋值的方法
                            PsiMethod methodWithAssignment = PsiTreeUtil.getParentOfType(assignment, PsiMethod.class);
                            
                            if (methodWithAssignment != null) {
                                // 检查是否已处理过此方法，避免循环
                                if (!processedMethods.contains(methodWithAssignment)) {
                                    // 创建字段赋值节点
                                    String className = methodWithAssignment.getContainingClass() != null ? 
                                            methodWithAssignment.getContainingClass().getName() : "未知类";
                                    
                                    // 特别标记Controller类的赋值
                                    boolean isController = isControllerClass(methodWithAssignment.getContainingClass());
                                    String methodDescription = methodWithAssignment.getName();
                                    if (isController) {
                                        methodDescription = "Controller." + methodDescription;
                                    }
                                    
                                    TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                                            TraceResult.TraceNodeType.FIELD_ACCESS,
                                            assignment,
                                            "字段赋值: " + assignment.getText() + " (在 " + className + "." + methodDescription + ")",
                                            fieldNode
                                    );
                                    result.addTraceNode(assignmentNode);
                                    
                                    // 暂时标记此方法已处理，避免循环
                                    processedMethods.add(methodWithAssignment);
                                    
                                    // 追踪赋值表达式右侧的值
                                    // 为Controller类方法重置深度计数器，确保完整追踪控制器中的依赖
                                    if (isController) {
                                        int oldDepth = currentDepth;
                                        currentDepth = 0;
                                        traceExpression(assignment.getRExpression(), assignmentNode, result, methodWithAssignment);
                                        currentDepth = oldDepth;
                                    } else {
                                        traceExpression(assignment.getRExpression(), assignmentNode, result, methodWithAssignment);
                                    }
                                    
                                    // 移除方法标记
                                    processedMethods.remove(methodWithAssignment);
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
        // 解析方法调用目标
        PsiMethod method = methodCall.resolveMethod();
        
        // 如果无法解析方法目标
        if (method == null) {
            return;
        }
        
        // 检查是否是Controller类中的方法
        boolean isController = isControllerClass(method.getContainingClass());
        
        // 创建方法调用节点
        String className = method.getContainingClass() != null ? method.getContainingClass().getName() : "未知类";
        String methodDescription = method.getName();
        if (isController) {
            methodDescription = "Controller." + methodDescription;
        }
        
        TraceResult.TraceNode methodNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                methodCall,
                "方法调用: " + (isController ? "[Controller] " : "") + className + "." + methodDescription + "()",
                parentNode
        );
        result.addTraceNode(methodNode);
        
        // 追踪方法调用的参数 - 总是分析参数
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
                        "方法参数 " + paramName + ": " + args[i].getText() + (isController ? " [传递给Controller]" : ""),
                        methodNode
                );
                result.addTraceNode(argNode);
                
                // 如果是传递给Controller的参数，重置深度计数器
                if (isController) {
                    int oldDepth = currentDepth;
                    currentDepth = 0;
                    traceExpression(args[i], argNode, result, containingMethod);
                    currentDepth = oldDepth;
                } else {
                    traceExpression(args[i], argNode, result, containingMethod);
                }
            }
        }
        
        // 如果方法已经处理过，避免循环递归，但上面仍允许追踪参数
        if (processedMethods.contains(method)) {
            TraceResult.TraceNode cycleNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.METHOD_CALL,
                    methodCall,
                    "已追踪过的方法调用: " + methodDescription + "() - 避免循环",
                    methodNode
            );
            result.addTraceNode(cycleNode);
            return;
        }
        
        // 标记方法为已处理
        processedMethods.add(method);
        
        // 如果是Controller类的方法，重置深度计数器确保完整追踪
        int oldDepth = currentDepth;
        if (isController) {
            currentDepth = 0;
        }
        
        // 处理接口方法 - 查找实现类方法
        if (method.getContainingClass() != null && method.getContainingClass().isInterface()) {
            findAndTraceImplementations(method, methodNode, result);
        }
        
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
                                "返回值: " + returnValue.getText() + (isController ? " [来自Controller]" : ""),
                                methodNode
                        );
                        result.addTraceNode(returnNode);
                        
                        // 递归分析返回值表达式
                        traceExpression(returnValue, returnNode, result, method);
                    }
                }
            } else {
                // 对于没有return语句的方法
                TraceResult.TraceNode noReturnNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.UNKNOWN,
                        method,
                        "方法没有返回值" + (isController ? " [Controller方法]" : ""),
                        methodNode
                );
                result.addTraceNode(noReturnNode);
            }
        }
        
        // 恢复原始深度
        if (isController) {
            currentDepth = oldDepth;
        }
        
        // 完成后移除方法标记
        processedMethods.remove(method);
    }

    /**
     * 查找并追踪接口方法的实现
     * 用于处理Controller通过接口调用实现类的情况
     * 
     * @param interfaceMethod 接口方法
     * @param parentNode 父节点
     * @param result 追踪结果
     */
    private void findAndTraceImplementations(PsiMethod interfaceMethod, TraceResult.TraceNode parentNode, 
                                          TraceResult result) {
        PsiClass interfaceClass = interfaceMethod.getContainingClass();
        if (interfaceClass == null) {
            return;
        }
        
        // 添加接口信息
        TraceResult.TraceNode interfaceNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.UNKNOWN,
                interfaceMethod,
                "接口方法: " + interfaceMethod.getName() + " 在 " + interfaceClass.getName(),
                parentNode
        );
        result.addTraceNode(interfaceNode);
        
        // 查找实现此接口的类
        Collection<PsiClass> implementations = findImplementingClasses(interfaceClass);
        if (implementations.isEmpty()) {
            TraceResult.TraceNode noImplNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.UNKNOWN,
                    interfaceMethod,
                    "未找到接口 " + interfaceClass.getName() + " 的实现类",
                    interfaceNode
            );
            result.addTraceNode(noImplNode);
            return;
        }
        
        boolean foundImplementation = false;
        
        // 对每个实现类查找对应的方法
        for (PsiClass implClass : implementations) {
            // 检查是否已经处理过这个类（避免循环）
            if (processedMethods.stream().anyMatch(m -> m.getContainingClass() == implClass)) {
                TraceResult.TraceNode skipNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.UNKNOWN,
                        implClass,
                        "跳过实现类 " + implClass.getName() + " (已处理过)",
                        interfaceNode
                );
                result.addTraceNode(skipNode);
                continue;
            }
            
            // 查找实现方法
            PsiMethod[] methods = implClass.findMethodsByName(interfaceMethod.getName(), false);
            for (PsiMethod implMethod : methods) {
                // 确认这是接口方法的实现
                if (isImplementationOf(implMethod, interfaceMethod)) {
                    foundImplementation = true;
                    
                    // 使用更描述性的标记，特别是对Spring的服务实现类
                    boolean isServiceImpl = isServiceImplementation(implClass);
                    String implDescription = isServiceImpl ? "服务实现: " : "实现: ";
                    
                    TraceResult.TraceNode implNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.METHOD_CALL,
                            implMethod,
                            implDescription + implMethod.getName() + " 在 " + implClass.getName(),
                            interfaceNode
                    );
                    result.addTraceNode(implNode);
                    
                    // 如果实现方法有返回语句，分析它们
                    if (implMethod.getBody() != null) {
                        // 标记此方法为已处理
                        processedMethods.add(implMethod);
                        
                        // 查找所有return语句
                        PsiReturnStatement[] returns = PsiTreeUtil.findChildrenOfType(implMethod.getBody(), PsiReturnStatement.class)
                                .toArray(PsiReturnStatement.EMPTY_ARRAY);
                        
                        if (returns.length > 0) {
                            for (PsiReturnStatement returnStmt : returns) {
                                PsiExpression returnValue = returnStmt.getReturnValue();
                                if (returnValue != null) {
                                    TraceResult.TraceNode returnNode = new TraceResult.TraceNode(
                                            TraceResult.TraceNodeType.METHOD_CALL,
                                            returnStmt,
                                            "实现返回值: " + returnValue.getText() + (isServiceImpl ? " [来自服务实现]" : ""),
                                            implNode
                                    );
                                    result.addTraceNode(returnNode);
                                    
                                    // 重置深度以确保完整追踪实现类中的返回值
                                    int oldDepth = currentDepth;
                                    currentDepth = 0;
                                    
                                    // 递归分析返回值表达式
                                    traceExpression(returnValue, returnNode, result, implMethod);
                                    
                                    // 恢复深度
                                    currentDepth = oldDepth;
                                }
                            }
                        } else {
                            // 没有返回语句的情况
                            TraceResult.TraceNode noReturnNode = new TraceResult.TraceNode(
                                    TraceResult.TraceNodeType.UNKNOWN,
                                    implMethod,
                                    "实现方法没有返回值",
                                    implNode
                            );
                            result.addTraceNode(noReturnNode);
                        }
                        
                        // 移除方法标记
                        processedMethods.remove(implMethod);
                    }
                }
            }
        }
        
        if (!foundImplementation) {
            TraceResult.TraceNode noImplMethodNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.UNKNOWN,
                    interfaceMethod,
                    "找到实现类，但未找到方法 " + interfaceMethod.getName() + " 的实现",
                    interfaceNode
            );
            result.addTraceNode(noImplMethodNode);
        }
    }

    /**
     * 查找实现给定接口的所有类
     * 
     * @param interfaceClass 接口类
     * @return 实现此接口的类集合
     */
    private Collection<PsiClass> findImplementingClasses(PsiClass interfaceClass) {
        // 使用ReferencesSearch查找所有引用此接口的地方
        Collection<PsiReference> refs = ReferencesSearch.search(interfaceClass).findAll();
        Set<PsiClass> implementations = new HashSet<>();
        
        for (PsiReference ref : refs) {
            PsiElement element = ref.getElement();
            PsiClass containingClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            
            if (containingClass != null && !containingClass.isInterface() && !containingClass.isEnum()) {
                // 检查是否实现此接口
                PsiClassType[] implementedInterfaces = containingClass.getImplementsListTypes();
                for (PsiClassType implementedInterface : implementedInterfaces) {
                    PsiClass resolvedInterface = implementedInterface.resolve();
                    if (resolvedInterface != null && (resolvedInterface.equals(interfaceClass) || 
                            PsiTreeUtil.isAncestor(resolvedInterface, interfaceClass, true))) {
                        implementations.add(containingClass);
                        break;
                    }
                }
                
                // 检查父类是否实现此接口（可能通过继承获得接口实现）
                PsiClass superClass = containingClass.getSuperClass();
                while (superClass != null) {
                    PsiClassType[] superImplements = superClass.getImplementsListTypes();
                    for (PsiClassType superInterface : superImplements) {
                        PsiClass resolvedSuperInterface = superInterface.resolve();
                        if (resolvedSuperInterface != null && (resolvedSuperInterface.equals(interfaceClass) || 
                                PsiTreeUtil.isAncestor(resolvedSuperInterface, interfaceClass, true))) {
                            implementations.add(containingClass);
                            break;
                        }
                    }
                    superClass = superClass.getSuperClass();
                }
            }
        }
        
        return implementations;
    }

    /**
     * 检查一个方法是否是另一个接口方法的实现
     * 
     * @param method 可能的实现方法
     * @param interfaceMethod 接口方法
     * @return 如果是实现返回true
     */
    private boolean isImplementationOf(PsiMethod method, PsiMethod interfaceMethod) {
        if (!method.getName().equals(interfaceMethod.getName())) {
            return false;
        }
        
        // 检查返回类型
        PsiType methodReturnType = method.getReturnType();
        PsiType interfaceReturnType = interfaceMethod.getReturnType();
        if (methodReturnType == null || interfaceReturnType == null || 
                !methodReturnType.isAssignableFrom(interfaceReturnType)) {
            return false;
        }
        
        // 检查参数列表
        PsiParameterList methodParams = method.getParameterList();
        PsiParameterList interfaceParams = interfaceMethod.getParameterList();
        
        if (methodParams.getParametersCount() != interfaceParams.getParametersCount()) {
            return false;
        }
        
        PsiParameter[] methodParameters = methodParams.getParameters();
        PsiParameter[] interfaceParameters = interfaceParams.getParameters();
        
        for (int i = 0; i < methodParameters.length; i++) {
            if (!methodParameters[i].getType().equals(interfaceParameters[i].getType())) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * 检查是否为服务实现类
     * 特别处理Spring等框架的服务实现模式
     * 
     * @param psiClass 要检查的类
     * @return 如果是服务实现类返回true
     */
    private boolean isServiceImplementation(PsiClass psiClass) {
        if (psiClass == null) {
            return false;
        }
        
        // 检查类名是否符合服务实现命名模式
        String className = psiClass.getName();
        if (className != null && (
                className.endsWith("ServiceImpl") || 
                className.endsWith("RepositoryImpl") || 
                className.endsWith("DaoImpl") ||
                className.endsWith("ManagerImpl"))) {
            return true;
        }
        
        // 检查类上的注解
        PsiAnnotation[] annotations = psiClass.getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && (
                    qualifiedName.contains("Service") ||
                    qualifiedName.contains("Repository") ||
                    qualifiedName.contains("Component") ||
                    qualifiedName.contains("Bean"))) {
                return true;
            }
        }
        
        return false;
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
        if (method != null) {
            // 查找父类方法 - 如果当前方法是覆盖父类的方法，优先使用父类方法进行追踪
            PsiMethod parentMethod = findParentMethod(method);
            if (parentMethod != null) {
                // 使用父类方法替代当前方法进行追踪
                method = parentMethod;
            }
        }

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
        
        // 查找父类方法 - 如果当前方法是覆盖父类的方法，优先使用父类方法进行追踪
        PsiMethod parentMethod = findParentMethod(method);
        if (parentMethod != null) {
            // 在追踪结果中添加父类方法信息
            PsiClass parentClass = parentMethod.getContainingClass();
            String parentClassName = parentClass != null ? parentClass.getName() : "未知父类";
            
            TraceResult.TraceNode overrideNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.METHOD_CALL,
                    method,
                    "覆盖父类方法: " + parentClassName + "." + parentMethod.getName() + "()",
                    null
            );
            result.addTraceNode(overrideNode);
            
            // 使用父类方法替代当前方法进行追踪
            method = parentMethod;
        }
        
        // 获取参数索引
        int paramIndex = -1;
        PsiParameterList paramList = method.getParameterList();
        PsiParameter[] parameters = paramList.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].equals(parameter) || 
                (parentMethod != null && parameters[i].getName().equals(parameter.getName()) && 
                parameters[i].getType().equals(parameter.getType()))) {
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
                "方法参数: " + parameter.getName() + " 在方法 " + method.getName() + "()" +
                (parentMethod != null ? " (父类方法)" : ""),
                null
        );
        result.addTraceNode(paramRootNode);
        
        // 查找对该方法的所有调用
        processedMethods.add(method);
        traceMethodParameterUsages(method, paramIndex, paramRootNode, result);
        processedMethods.remove(method);
    }
    
    /**
     * 查找方法的父类方法
     * 如果方法覆盖了父类方法，返回父类中的原始方法
     * 
     * @param method 当前方法
     * @return 父类方法，如果没有则返回null
     */
    private PsiMethod findParentMethod(PsiMethod method) {
        // 检查方法是否有父类
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            return null;
        }
        
        // 获取方法名和参数列表，用于在父类中查找匹配方法
        String methodName = method.getName();
        PsiParameterList paramList = method.getParameterList();
        int paramCount = paramList.getParametersCount();
        
        // 检查方法是否有Override注解
        boolean hasOverrideAnnotation = false;
        for (PsiAnnotation annotation : method.getAnnotations()) {
            if ("java.lang.Override".equals(annotation.getQualifiedName()) || "Override".equals(annotation.getQualifiedName())) {
                hasOverrideAnnotation = true;
                break;
            }
        }
        
        // 收集父类和接口
        Set<PsiClass> superTypes = new HashSet<>();
        
        // 添加父类
        PsiClass superClass = containingClass.getSuperClass();
        if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
            superTypes.add(superClass);
        }
        
        // 添加接口
        PsiClassType[] interfaces = containingClass.getImplementsListTypes();
        for (PsiClassType interfaceType : interfaces) {
            PsiClass interfaceClass = interfaceType.resolve();
            if (interfaceClass != null) {
                superTypes.add(interfaceClass);
            }
        }
        
        // 查找父类型中的匹配方法
        for (PsiClass superType : superTypes) {
            PsiMethod[] superMethods = superType.findMethodsByName(methodName, false);
            for (PsiMethod superMethod : superMethods) {
                // 检查参数列表是否匹配
                PsiParameterList superParamList = superMethod.getParameterList();
                if (superParamList.getParametersCount() == paramCount) {
                    boolean parametersMatch = true;
                    PsiParameter[] methodParams = paramList.getParameters();
                    PsiParameter[] superParams = superParamList.getParameters();
                    
                    for (int i = 0; i < paramCount; i++) {
                        if (!methodParams[i].getType().equals(superParams[i].getType())) {
                            parametersMatch = false;
                            break;
                        }
                    }
                    
                    if (parametersMatch) {
                        return superMethod;
                    }
                }
            }
            
            // 递归检查父类的父类和接口
            PsiMethod ancestorMethod = findMethodInSuperTypes(superType, methodName, paramList);
            if (ancestorMethod != null) {
                return ancestorMethod;
            }
        }
        
        // 如果方法有Override注解但没有找到父类方法，尝试更广泛的搜索
        if (hasOverrideAnnotation) {
            return findOverriddenMethodByHierarchy(method);
        }
        
        return null;
    }
    
    /**
     * 在父类层次结构中递归查找匹配的方法
     * 
     * @param psiClass 当前类
     * @param methodName 要查找的方法名
     * @param paramList 参数列表
     * @return 匹配的方法，如果没有则返回null
     */
    private PsiMethod findMethodInSuperTypes(PsiClass psiClass, String methodName, PsiParameterList paramList) {
        if (psiClass == null) {
            return null;
        }
        
        int paramCount = paramList.getParametersCount();
        
        // 检查父类
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
            // 在父类中查找方法
            PsiMethod[] superMethods = superClass.findMethodsByName(methodName, false);
            for (PsiMethod superMethod : superMethods) {
                PsiParameterList superParamList = superMethod.getParameterList();
                if (superParamList.getParametersCount() == paramCount) {
                    boolean parametersMatch = true;
                    PsiParameter[] methodParams = paramList.getParameters();
                    PsiParameter[] superParams = superParamList.getParameters();
                    
                    for (int i = 0; i < paramCount; i++) {
                        if (!methodParams[i].getType().equals(superParams[i].getType())) {
                            parametersMatch = false;
                            break;
                        }
                    }
                    
                    if (parametersMatch) {
                        return superMethod;
                    }
                }
            }
            
            // 递归检查父类的父类
            PsiMethod ancestorMethod = findMethodInSuperTypes(superClass, methodName, paramList);
            if (ancestorMethod != null) {
                return ancestorMethod;
            }
        }
        
        // 检查接口
        PsiClassType[] interfaces = psiClass.getImplementsListTypes();
        for (PsiClassType interfaceType : interfaces) {
            PsiClass interfaceClass = interfaceType.resolve();
            if (interfaceClass != null) {
                // 在接口中查找方法
                PsiMethod[] interfaceMethods = interfaceClass.findMethodsByName(methodName, false);
                for (PsiMethod interfaceMethod : interfaceMethods) {
                    PsiParameterList interfaceParamList = interfaceMethod.getParameterList();
                    if (interfaceParamList.getParametersCount() == paramCount) {
                        boolean parametersMatch = true;
                        PsiParameter[] methodParams = paramList.getParameters();
                        PsiParameter[] interfaceParams = interfaceParamList.getParameters();
                        
                        for (int i = 0; i < paramCount; i++) {
                            if (!methodParams[i].getType().equals(interfaceParams[i].getType())) {
                                parametersMatch = false;
                                break;
                            }
                        }
                        
                        if (parametersMatch) {
                            return interfaceMethod;
                        }
                    }
                }
                
                // 递归检查接口的父接口
                PsiMethod ancestorMethod = findMethodInSuperTypes(interfaceClass, methodName, paramList);
                if (ancestorMethod != null) {
                    return ancestorMethod;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 通过更广泛的搜索找到被覆盖的方法
     * 处理复杂继承和泛型情况
     * 
     * @param method 当前方法
     * @return 被覆盖的父类方法
     */
    private PsiMethod findOverriddenMethodByHierarchy(PsiMethod method) {
        // 使用IntelliJ平台API尝试查找被覆盖的方法
        PsiMethod[] superMethods = method.findSuperMethods();
        if (superMethods.length > 0) {
            return superMethods[0]; // 返回第一个找到的父类方法
        }
        return null;
    }

    /**
     * 检查给定类是否为Controller类
     * 根据类名或注解判断
     * 
     * @param psiClass 要检查的类
     * @return 如果是Controller则返回true
     */
    private boolean isControllerClass(@Nullable PsiClass psiClass) {
        if (psiClass == null) {
            return false;
        }
        
        // 1. 通过类名检查 - 常见的Controller命名模式
        String className = psiClass.getName();
        if (className != null && (
                className.endsWith("Controller") || 
                className.endsWith("Resource") || 
                className.endsWith("RestController") ||
                className.endsWith("Api"))) {
            return true;
        }
        
        // 2. 通过注解检查 - Spring和JAX-RS常用注解
        PsiAnnotation[] annotations = psiClass.getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && (
                    qualifiedName.contains("Controller") ||
                    qualifiedName.contains("RestController") ||
                    qualifiedName.contains("RequestMapping") ||
                    qualifiedName.contains("Path") ||  // JAX-RS
                    qualifiedName.contains("Api"))) {  // Swagger
                return true;
            }
        }
        
        // 3. 检查类的方法是否有控制器相关注解
        PsiMethod[] methods = psiClass.getMethods();
        for (PsiMethod method : methods) {
            PsiAnnotation[] methodAnnotations = method.getAnnotations();
            for (PsiAnnotation annotation : methodAnnotations) {
                String qualifiedName = annotation.getQualifiedName();
                if (qualifiedName != null && (
                        qualifiedName.contains("RequestMapping") ||
                        qualifiedName.contains("GetMapping") ||
                        qualifiedName.contains("PostMapping") ||
                        qualifiedName.contains("PutMapping") ||
                        qualifiedName.contains("DeleteMapping") ||
                        qualifiedName.contains("GET") ||  // JAX-RS
                        qualifiedName.contains("POST") || // JAX-RS
                        qualifiedName.contains("PUT") ||  // JAX-RS
                        qualifiedName.contains("DELETE") || // JAX-RS
                        qualifiedName.contains("ApiOperation"))) { // Swagger
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * 追踪getter方法的值来源
     * 分析getter方法取值字段的赋值来源和方法调用链
     * 
     * @param methodCallExpr getter方法调用表达式
     * @param getterMethod getter方法
     * @param field 对应的字段
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 当前包含方法
     */
    public void traceGetterMethod(@NotNull PsiMethodCallExpression methodCallExpr, 
                                @NotNull PsiMethod getterMethod, 
                                @NotNull PsiField field, 
                                TraceResult.TraceNode parentNode, 
                                TraceResult result, 
                                PsiMethod containingMethod) {
        // 重置追踪状态
        processedElements.clear();
        processedMethods.clear();
        currentDepth = 0;
        
        // 创建方法调用节点
        TraceResult.TraceNode methodCallNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                methodCallExpr,
                "方法调用: " + methodCallExpr.getText(),
                parentNode
        );
        result.addTraceNode(methodCallNode);
        
        // 递归向上追踪字段的赋值来源
        PsiClass containingClass = field.getContainingClass();
        if (containingClass != null) {
            // 查找当前类中所有对该字段的赋值表达式
            findAndTraceFieldAssignments(field, containingClass, methodCallNode, result);
            
            // 查找构造函数中的赋值
            traceConstructorAssignments(field, containingClass, methodCallNode, result);
        }
        
        // 向上追踪所有对这个方法调用者的赋值情况
        PsiExpression qualifierExpression = methodCallExpr.getMethodExpression().getQualifierExpression();
        if (qualifierExpression != null) {
            traceQualifierExpression(qualifierExpression, methodCallNode, result, containingMethod);
        }
    }

    /**
     * 查找并追踪字段的所有赋值点
     * 
     * @param field 目标字段
     * @param containingClass 包含类
     * @param parentNode 父节点
     * @param result 追踪结果
     */
    private void findAndTraceFieldAssignments(@NotNull PsiField field, 
                                          @NotNull PsiClass containingClass, 
                                          TraceResult.TraceNode parentNode, 
                                          TraceResult result) {
        // 查找所有方法中对字段的赋值
        PsiMethod[] methods = containingClass.getMethods();
        for (PsiMethod method : methods) {
            // 跳过构造函数，构造函数单独处理
            if (method.isConstructor()) {
                continue;
            }
            
            PsiCodeBlock body = method.getBody();
            if (body == null) {
                continue;
            }
            
            // 在方法体中查找对字段的赋值表达式
            Collection<PsiAssignmentExpression> assignments = PsiTreeUtil.findChildrenOfType(body, PsiAssignmentExpression.class);
            for (PsiAssignmentExpression assignment : assignments) {
                PsiExpression lExpr = assignment.getLExpression();
                if (lExpr instanceof PsiReferenceExpression) {
                    PsiElement resolved = ((PsiReferenceExpression) lExpr).resolve();
                    if (resolved != null && resolved.equals(field)) {
                        // 创建字段赋值节点
                        TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                                TraceResult.TraceNodeType.FIELD_ASSIGNMENT,
                                assignment,
                                "字段赋值: " + assignment.getText() + " 在方法 " + method.getName(),
                                parentNode
                        );
                        result.addTraceNode(assignmentNode);
                        
                        // 重置当前深度，每个主赋值点可以独立追踪到最大深度
                        currentDepth = 0;
                        
                        // 追踪赋值表达式右侧的值来源
                        traceExpression(assignment.getRExpression(), assignmentNode, result, method);
                    }
                }
            }
        }
    }

    /**
     * 追踪构造函数中的字段赋值
     * 
     * @param field 目标字段
     * @param containingClass 包含类
     * @param parentNode 父节点
     * @param result 追踪结果
     */
    private void traceConstructorAssignments(@NotNull PsiField field, 
                                         @NotNull PsiClass containingClass, 
                                         TraceResult.TraceNode parentNode, 
                                         TraceResult result) {
        // 获取所有构造函数
        PsiMethod[] constructors = containingClass.getConstructors();
        
        // 如果没有显式构造函数，查找字段初始化器
        if (constructors.length == 0) {
            PsiExpression initializer = field.getInitializer();
            if (initializer != null) {
                TraceResult.TraceNode initializerNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.FIELD_INITIALIZATION,
                        initializer,
                        "字段初始化: " + field.getName() + " = " + initializer.getText(),
                        parentNode
                );
                result.addTraceNode(initializerNode);
                
                // 重置当前深度
                currentDepth = 0;
                
                // 追踪初始化表达式的值来源
                traceExpression(initializer, initializerNode, result, null);
            }
            return;
        }
        
        // 分析每个构造函数
        for (PsiMethod constructor : constructors) {
            PsiCodeBlock body = constructor.getBody();
            if (body == null) {
                continue;
            }
            
            // 查找构造函数参数中是否有被用于初始化目标字段的
            PsiParameter[] parameters = constructor.getParameterList().getParameters();
            if (parameters.length > 0) {
                // 在构造函数体中查找对字段的赋值
                Collection<PsiAssignmentExpression> assignments = PsiTreeUtil.findChildrenOfType(body, PsiAssignmentExpression.class);
                for (PsiAssignmentExpression assignment : assignments) {
                    PsiExpression lExpr = assignment.getLExpression();
                    if (lExpr instanceof PsiReferenceExpression) {
                        PsiElement resolved = ((PsiReferenceExpression) lExpr).resolve();
                        if (resolved != null && resolved.equals(field)) {
                            // 创建构造函数赋值节点
                            TraceResult.TraceNode constructorAssignNode = new TraceResult.TraceNode(
                                    TraceResult.TraceNodeType.CONSTRUCTOR_ASSIGNMENT,
                                    assignment,
                                    "构造函数赋值: " + assignment.getText() + " 在构造函数 " + constructor.getName(),
                                    parentNode
                            );
                            result.addTraceNode(constructorAssignNode);
                            
                            // 重置当前深度
                            currentDepth = 0;
                            
                            // 追踪右侧表达式
                            PsiExpression rExpr = assignment.getRExpression();
                            if (rExpr != null) {
                                // 特别处理: 如果右侧是来自构造函数参数的引用，标记这一点
                                if (rExpr instanceof PsiReferenceExpression) {
                                    PsiElement resolvedParam = ((PsiReferenceExpression) rExpr).resolve();
                                    if (resolvedParam instanceof PsiParameter && 
                                            PsiTreeUtil.isAncestor(constructor, resolvedParam, false)) {
                                        // 创建构造函数参数节点
                                        TraceResult.TraceNode paramNode = new TraceResult.TraceNode(
                                                TraceResult.TraceNodeType.PARAMETER,
                                                resolvedParam,
                                                "构造函数参数: " + ((PsiParameter) resolvedParam).getName(),
                                                constructorAssignNode
                                        );
                                        result.addTraceNode(paramNode);
                                        
                                        // 继续追踪构造函数参数的使用情况
                                        traceConstructorParameterUsages(constructor, (PsiParameter) resolvedParam, paramNode, result);
                                    } else {
                                        // 如果不是参数，正常追踪表达式
                                        traceExpression(rExpr, constructorAssignNode, result, constructor);
                                    }
                                } else {
                                    // 正常追踪右侧表达式
                                    traceExpression(rExpr, constructorAssignNode, result, constructor);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 追踪构造函数参数的使用情况
     * 查找传递给该构造函数参数的值
     * 
     * @param constructor 构造函数
     * @param parameter 参数
     * @param parentNode 父节点
     * @param result 追踪结果
     */
    private void traceConstructorParameterUsages(@NotNull PsiMethod constructor, 
                                             @NotNull PsiParameter parameter, 
                                             TraceResult.TraceNode parentNode, 
                                             TraceResult result) {
        PsiClass containingClass = constructor.getContainingClass();
        if (containingClass == null) {
            return;
        }
        
        // 查找所有对构造函数的调用
        int paramIndex = -1;
        PsiParameter[] parameters = constructor.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].equals(parameter)) {
                paramIndex = i;
                break;
            }
        }
        
        if (paramIndex < 0) {
            return;
        }
        
        // 搜索整个项目中对此构造函数的调用
        com.intellij.psi.search.searches.ReferencesSearch.SearchParameters searchParameters = 
                new com.intellij.psi.search.searches.ReferencesSearch.SearchParameters(
                        constructor, 
                        constructor.getUseScope(), 
                        false);
        
        Collection<PsiReference> references = com.intellij.psi.search.searches.ReferencesSearch.search(searchParameters).findAll();
        
        for (PsiReference reference : references) {
            PsiElement element = reference.getElement();
            if (element.getParent() instanceof PsiNewExpression) {
                PsiNewExpression newExpr = (PsiNewExpression) element.getParent();
                PsiExpressionList argList = newExpr.getArgumentList();
                
                if (argList != null && argList.getExpressionCount() > paramIndex) {
                    PsiExpression argExpression = argList.getExpressions()[paramIndex];
                    
                    // 创建构造调用参数节点
                    TraceResult.TraceNode argNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.CONSTRUCTOR_ARG,
                            argExpression,
                            "构造调用参数: " + argExpression.getText(),
                            parentNode
                    );
                    result.addTraceNode(argNode);
                    
                    // 重置深度，以便充分追踪参数来源
                    currentDepth = 0;
                    
                    // 查找包含该new表达式的方法
                    PsiMethod containingMethod = PsiTreeUtil.getParentOfType(newExpr, PsiMethod.class);
                    if (containingMethod != null) {
                        // 追踪参数表达式的值来源
                        traceExpression(argExpression, argNode, result, containingMethod);
                    }
                }
            }
        }
    }

    /**
     * 追踪方法调用表达式限定符的来源
     * 例如对于 object.getProperty()，追踪object的来源
     * 
     * @param qualifier 方法调用限定符表达式
     * @param parentNode 父节点
     * @param result 追踪结果
     * @param containingMethod 包含方法
     */
    private void traceQualifierExpression(@NotNull PsiExpression qualifier, 
                                       TraceResult.TraceNode parentNode, 
                                       TraceResult result, 
                                       PsiMethod containingMethod) {
        TraceResult.TraceNode qualifierNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.QUALIFIER,
                qualifier,
                "调用者: " + qualifier.getText(),
                parentNode
        );
        result.addTraceNode(qualifierNode);
        
        // 重置深度
        currentDepth = 0;
        
        // 基于表达式类型进行不同处理
        if (qualifier instanceof PsiReferenceExpression) {
            PsiElement resolved = ((PsiReferenceExpression) qualifier).resolve();
            
            // 如果是局部变量或参数
            if (resolved instanceof PsiVariable) {
                // 追踪变量赋值来源
                if (resolved instanceof PsiLocalVariable) {
                    // 本地变量初始化
                    PsiExpression initializer = ((PsiVariable) resolved).getInitializer();
                    if (initializer != null) {
                        TraceResult.TraceNode initNode = new TraceResult.TraceNode(
                                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                                initializer,
                                "局部变量初始化: " + ((PsiVariable) resolved).getName() + " = " + initializer.getText(),
                                qualifierNode
                        );
                        result.addTraceNode(initNode);
                        
                        // 重置深度
                        currentDepth = 0;
                        
                        // 追踪初始化表达式
                        traceExpression(initializer, initNode, result, containingMethod);
                    }
                    
                    // 查找所有对该变量的赋值
                    if (containingMethod != null) {
                        PsiAssignmentExpression[] assignments = PsiUtils.findAssignmentsInMethod((PsiVariable) resolved, containingMethod);
                        for (PsiAssignmentExpression assignment : assignments) {
                            TraceResult.TraceNode assignNode = new TraceResult.TraceNode(
                                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                                    assignment,
                                    "局部赋值: " + assignment.getText(),
                                    qualifierNode
                            );
                            result.addTraceNode(assignNode);
                            
                            // 重置深度
                            currentDepth = 0;
                            
                            // 追踪赋值表达式右侧的值来源
                            traceExpression(assignment.getRExpression(), assignNode, result, containingMethod);
                        }
                    }
                } 
                // 如果是参数
                else if (resolved instanceof PsiParameter) {
                    TraceResult.TraceNode paramNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.PARAMETER,
                            resolved,
                            "方法参数: " + ((PsiParameter) resolved).getName(),
                            qualifierNode
                    );
                    result.addTraceNode(paramNode);
                    
                    // 重置深度
                    currentDepth = 0;
                    
                    // 根据方法参数查找方法调用
                    traceParameterUsages((PsiParameter) resolved, result);
                }
            } 
            // 如果是字段
            else if (resolved instanceof PsiField) {
                TraceResult.TraceNode fieldNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.FIELD_REFERENCE,
                        resolved,
                        "字段引用: " + ((PsiField) resolved).getName(),
                        qualifierNode
                );
                result.addTraceNode(fieldNode);
                
                // 重置深度
                currentDepth = 0;
                
                // 追踪字段来源 (递归应用相同的getter追踪逻辑)
                PsiField field = (PsiField) resolved;
                PsiClass containingClass = field.getContainingClass();
                if (containingClass != null) {
                    findAndTraceFieldAssignments(field, containingClass, fieldNode, result);
                    traceConstructorAssignments(field, containingClass, fieldNode, result);
                }
            }
        } 
        // 如果是方法调用
        else if (qualifier instanceof PsiMethodCallExpression) {
            PsiMethodCallExpression qualifierMethodCall = (PsiMethodCallExpression) qualifier;
            PsiMethod qualifierMethod = qualifierMethodCall.resolveMethod();
            
            if (qualifierMethod != null) {
                // 检查这个方法调用是否也是getter
                boolean isGetter = qualifierMethod.getName().startsWith("get") && 
                        qualifierMethod.getParameterList().getParametersCount() == 0;
                        
                if (isGetter) {
                    // 递归应用相同的getter追踪逻辑
                    PsiField fieldFromGetter = extractFieldFromGetter(qualifierMethod);
                    if (fieldFromGetter != null) {
                        // 追踪此getter方法返回的字段
                        TraceResult.TraceNode nestedGetterNode = new TraceResult.TraceNode(
                                TraceResult.TraceNodeType.METHOD_CALL,
                                qualifierMethodCall,
                                "嵌套getter调用: " + qualifierMethodCall.getText(),
                                qualifierNode
                        );
                        result.addTraceNode(nestedGetterNode);
                        
                        // 重置深度
                        currentDepth = 0;
                        
                        // 递归追踪此getter方法
                        traceGetterMethod(qualifierMethodCall, qualifierMethod, fieldFromGetter, nestedGetterNode, result, containingMethod);
                    }
                } else {
                    // 追踪普通方法调用
                    traceMethodCall((PsiMethodCallExpression) qualifier, qualifierNode, result, containingMethod);
                }
            }
        } else {
            // 对于其他类型的表达式，使用标准的表达式追踪
            traceExpression(qualifier, qualifierNode, result, containingMethod);
        }
    }

    /**
     * 从getter方法中提取相关字段
     * 
     * @param method getter方法
     * @return 相关字段，如果无法提取则返回null
     */
    @Nullable
    private PsiField extractFieldFromGetter(@NotNull PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            PsiStatement[] statements = body.getStatements();
            if (statements.length == 1 && statements[0] instanceof PsiReturnStatement) {
                PsiReturnStatement returnStmt = (PsiReturnStatement) statements[0];
                PsiExpression returnValue = returnStmt.getReturnValue();
                if (returnValue instanceof PsiReferenceExpression) {
                    PsiElement resolved = ((PsiReferenceExpression) returnValue).resolve();
                    if (resolved instanceof PsiField) {
                        return (PsiField) resolved;
                    }
                }
            }
        }
        return null;
    }
} 