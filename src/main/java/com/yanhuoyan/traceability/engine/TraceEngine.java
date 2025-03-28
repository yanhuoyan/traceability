package com.yanhuoyan.traceability.engine;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.yanhuoyan.traceability.util.PsiUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 变量追踪引擎
 * 负责分析和追踪变量的赋值历史
 */
public class TraceEngine {
    private final Project project; // 项目对象
    private final Set<PsiElement> processedElements; // 已处理元素集合，避免循环引用

    /**
     * 构造函数
     * 
     * @param project 当前项目
     */
    public TraceEngine(@NotNull Project project) {
        this.project = project;
        this.processedElements = new HashSet<>();
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
        
        // 添加变量声明节点
        TraceResult.TraceNode declarationNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.DECLARATION,
                variable,
                "Declaration: " + variable.getName(),
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
                    "Assignment: " + assignment.getText(),
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
        // 如果表达式为空或已处理过，则返回
        if (expression == null || processedElements.contains(expression)) {
            return;
        }
        
        // 标记当前表达式为已处理
        processedElements.add(expression);
        
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
        }
        // 可根据需要添加更多表达式类型的处理
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
                description = "Field access: " + variable.getName();
            } else if (variable instanceof PsiParameter) {
                nodeType = TraceResult.TraceNodeType.PARAMETER;
                description = "Parameter: " + variable.getName();
            } else {
                nodeType = TraceResult.TraceNodeType.LOCAL_ASSIGNMENT;
                description = "Variable reference: " + variable.getName();
            }
            
            // 创建引用节点
            TraceResult.TraceNode refNode = new TraceResult.TraceNode(
                    nodeType,
                    refExpr,
                    description,
                    parentNode
            );
            result.addTraceNode(refNode);
            
            // 如果是当前方法内的局部变量，继续追踪其赋值来源
            if (variable instanceof PsiLocalVariable && 
                    PsiTreeUtil.isAncestor(containingMethod, variable, true)) {
                
                // 检查变量初始化
                PsiExpression initializer = variable.getInitializer();
                if (initializer != null) {
                    traceExpression(initializer, refNode, result, containingMethod);
                }
                
                // 查找变量的所有赋值表达式
                PsiAssignmentExpression[] assignments = PsiUtils.findAssignmentsInMethod(variable, containingMethod);
                for (PsiAssignmentExpression assignment : assignments) {
                    TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                            assignment,
                            "Assignment: " + assignment.getText(),
                            refNode
                    );
                    result.addTraceNode(assignmentNode);
                    
                    // 追踪赋值表达式右侧的值来源
                    traceExpression(assignment.getRExpression(), assignmentNode, result, containingMethod);
                }
            }
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
        
        // 创建方法调用节点
        TraceResult.TraceNode methodNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                methodCall,
                "Method call: " + method.getName() + "()",
                parentNode
        );
        result.addTraceNode(methodNode);
        
        // TODO: 通过分析方法体追踪方法返回值，如果可用的话
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
        
        // 创建构造函数调用节点
        TraceResult.TraceNode constructorNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                newExpr,
                "Constructor call: new " + constructor.getName() + "()",
                parentNode
        );
        result.addTraceNode(constructorNode);
        
        // TODO: 根据需要追踪构造函数参数值
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
                "Array initializer",
                parentNode
        );
        result.addTraceNode(arrayNode);
        
        // 追踪数组中每个初始化元素的来源
        for (PsiExpression initializer : arrayInit.getInitializers()) {
            traceExpression(initializer, arrayNode, result, containingMethod);
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
                "Conditional expression",
                parentNode
        );
        result.addTraceNode(condNode);
        
        // 追踪条件为真和条件为假时的表达式
        traceExpression(conditionalExpr.getThenExpression(), condNode, result, containingMethod);
        traceExpression(conditionalExpr.getElseExpression(), condNode, result, containingMethod);
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
                "Binary expression: " + binaryExpr.getOperationSign().getText(),
                parentNode
        );
        result.addTraceNode(binaryNode);
        
        // 追踪二元表达式的左右操作数
        traceExpression(binaryExpr.getLOperand(), binaryNode, result, containingMethod);
        traceExpression(binaryExpr.getROperand(), binaryNode, result, containingMethod);
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
        
        // 查找对该方法的所有调用
        Collection<PsiReference> methodRefs = ReferencesSearch.search(method).findAll();
        for (PsiReference ref : methodRefs) {
            if (ref.getElement().getParent() instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getElement().getParent();
                PsiExpressionList argList = call.getArgumentList();
                
                // 获取调用时对应参数位置的实参
                PsiExpression[] args = argList.getExpressions();
                if (paramIndex < args.length) {
                    PsiExpression arg = args[paramIndex];
                    
                    // 为该参数创建节点
                    TraceResult.TraceNode argNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.PARAMETER,
                            arg,
                            "Parameter value at call site: " + arg.getText(),
                            null
                    );
                    result.addTraceNode(argNode);
                    
                    // 查找包含该调用的方法
                    PsiMethod callingMethod = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                    if (callingMethod != null) {
                        // 在调用方法中追踪参数表达式
                        traceExpression(arg, argNode, result, callingMethod);
                    }
                }
            }
        }
    }
} 