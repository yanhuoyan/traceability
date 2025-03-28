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
 * Engine for tracing variable assignments
 */
public class TraceEngine {
    private final Project project;
    private final Set<PsiElement> processedElements;

    public TraceEngine(@NotNull Project project) {
        this.project = project;
        this.processedElements = new HashSet<>();
    }

    /**
     * Trace a variable's assignments
     * @param variable the variable to trace
     * @param containingMethod the method containing the variable
     * @return the trace result
     */
    @NotNull
    public TraceResult traceVariable(@NotNull PsiVariable variable, @NotNull PsiMethod containingMethod) {
        TraceResult result = new TraceResult(variable);
        processedElements.clear();
        
        // Add declaration node
        TraceResult.TraceNode declarationNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.DECLARATION,
                variable,
                "Declaration: " + variable.getName(),
                null
        );
        result.addTraceNode(declarationNode);
        
        // Check initialization at declaration
        PsiExpression initializer = variable.getInitializer();
        if (initializer != null) {
            traceExpression(initializer, declarationNode, result, containingMethod);
        }
        
        // Find all assignments to the variable in the method
        PsiAssignmentExpression[] assignments = PsiUtils.findAssignmentsInMethod(variable, containingMethod);
        for (PsiAssignmentExpression assignment : assignments) {
            TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                    TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                    assignment,
                    "Assignment: " + assignment.getText(),
                    null
            );
            result.addTraceNode(assignmentNode);
            
            // Trace the right-hand side expression
            traceExpression(assignment.getRExpression(), assignmentNode, result, containingMethod);
        }
        
        // If variable is a parameter, trace method calls
        if (variable instanceof PsiParameter) {
            traceParameterUsages((PsiParameter) variable, result);
        }
        
        return result;
    }

    /**
     * Trace the source of an expression
     */
    private void traceExpression(PsiExpression expression, TraceResult.TraceNode parentNode, 
                               TraceResult result, PsiMethod containingMethod) {
        if (expression == null || processedElements.contains(expression)) {
            return;
        }
        
        processedElements.add(expression);
        
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
        // Add more expression types as needed
    }

    /**
     * Trace a reference expression
     */
    private void traceReference(PsiReferenceExpression refExpr, TraceResult.TraceNode parentNode, 
                              TraceResult result, PsiMethod containingMethod) {
        PsiElement resolved = refExpr.resolve();
        
        if (resolved instanceof PsiVariable) {
            PsiVariable variable = (PsiVariable) resolved;
            
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
            
            TraceResult.TraceNode refNode = new TraceResult.TraceNode(
                    nodeType,
                    refExpr,
                    description,
                    parentNode
            );
            result.addTraceNode(refNode);
            
            // If local variable, trace its assignments
            if (variable instanceof PsiLocalVariable && 
                    PsiTreeUtil.isAncestor(containingMethod, variable, true)) {
                
                // Check initialization
                PsiExpression initializer = variable.getInitializer();
                if (initializer != null) {
                    traceExpression(initializer, refNode, result, containingMethod);
                }
                
                // Find assignments
                PsiAssignmentExpression[] assignments = PsiUtils.findAssignmentsInMethod(variable, containingMethod);
                for (PsiAssignmentExpression assignment : assignments) {
                    TraceResult.TraceNode assignmentNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                            assignment,
                            "Assignment: " + assignment.getText(),
                            refNode
                    );
                    result.addTraceNode(assignmentNode);
                    
                    traceExpression(assignment.getRExpression(), assignmentNode, result, containingMethod);
                }
            }
        }
    }

    /**
     * Trace a method call expression
     */
    private void traceMethodCall(PsiMethodCallExpression methodCall, TraceResult.TraceNode parentNode, 
                               TraceResult result, PsiMethod containingMethod) {
        PsiMethod method = methodCall.resolveMethod();
        if (method == null) {
            return;
        }
        
        TraceResult.TraceNode methodNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                methodCall,
                "Method call: " + method.getName() + "()",
                parentNode
        );
        result.addTraceNode(methodNode);
        
        // TODO: Trace method return values by analyzing method body if available
    }

    /**
     * Trace constructor call
     */
    private void traceConstructorCall(PsiNewExpression newExpr, TraceResult.TraceNode parentNode, 
                                    TraceResult result, PsiMethod containingMethod) {
        PsiMethod constructor = newExpr.resolveConstructor();
        if (constructor == null) {
            return;
        }
        
        TraceResult.TraceNode constructorNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.METHOD_CALL,
                newExpr,
                "Constructor call: new " + constructor.getName() + "()",
                parentNode
        );
        result.addTraceNode(constructorNode);
        
        // TODO: Trace constructor parameter values if needed
    }

    /**
     * Trace array initializer
     */
    private void traceArrayInitializer(PsiArrayInitializerExpression arrayInit, TraceResult.TraceNode parentNode, 
                                     TraceResult result, PsiMethod containingMethod) {
        TraceResult.TraceNode arrayNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                arrayInit,
                "Array initializer",
                parentNode
        );
        result.addTraceNode(arrayNode);
        
        for (PsiExpression initializer : arrayInit.getInitializers()) {
            traceExpression(initializer, arrayNode, result, containingMethod);
        }
    }

    /**
     * Trace conditional expression (ternary operator)
     */
    private void traceConditionalExpression(PsiConditionalExpression conditionalExpr, TraceResult.TraceNode parentNode, 
                                          TraceResult result, PsiMethod containingMethod) {
        TraceResult.TraceNode condNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                conditionalExpr,
                "Conditional expression",
                parentNode
        );
        result.addTraceNode(condNode);
        
        traceExpression(conditionalExpr.getThenExpression(), condNode, result, containingMethod);
        traceExpression(conditionalExpr.getElseExpression(), condNode, result, containingMethod);
    }

    /**
     * Trace binary expression (e.g., a + b)
     */
    private void traceBinaryExpression(PsiBinaryExpression binaryExpr, TraceResult.TraceNode parentNode, 
                                     TraceResult result, PsiMethod containingMethod) {
        TraceResult.TraceNode binaryNode = new TraceResult.TraceNode(
                TraceResult.TraceNodeType.LOCAL_ASSIGNMENT,
                binaryExpr,
                "Binary expression: " + binaryExpr.getOperationSign().getText(),
                parentNode
        );
        result.addTraceNode(binaryNode);
        
        traceExpression(binaryExpr.getLOperand(), binaryNode, result, containingMethod);
        traceExpression(binaryExpr.getROperand(), binaryNode, result, containingMethod);
    }

    /**
     * Trace usages of a parameter in calling methods
     */
    private void traceParameterUsages(PsiParameter parameter, TraceResult result) {
        // Find the containing method
        PsiMethod method = PsiTreeUtil.getParentOfType(parameter, PsiMethod.class);
        if (method == null) {
            return;
        }
        
        // Get parameter index
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
        
        // Find method calls to this method
        Collection<PsiReference> methodRefs = ReferencesSearch.search(method).findAll();
        for (PsiReference ref : methodRefs) {
            if (ref.getElement().getParent() instanceof PsiMethodCallExpression) {
                PsiMethodCallExpression call = (PsiMethodCallExpression) ref.getElement().getParent();
                PsiExpressionList argList = call.getArgumentList();
                
                // Get the argument at the parameter's index
                PsiExpression[] args = argList.getExpressions();
                if (paramIndex < args.length) {
                    PsiExpression arg = args[paramIndex];
                    
                    // Create a node for this argument
                    TraceResult.TraceNode argNode = new TraceResult.TraceNode(
                            TraceResult.TraceNodeType.PARAMETER,
                            arg,
                            "Parameter value at call site: " + arg.getText(),
                            null
                    );
                    result.addTraceNode(argNode);
                    
                    // Find the method containing this call
                    PsiMethod callingMethod = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                    if (callingMethod != null) {
                        // Trace the argument expression in the calling method
                        traceExpression(arg, argNode, result, callingMethod);
                    }
                }
            }
        }
    }
} 