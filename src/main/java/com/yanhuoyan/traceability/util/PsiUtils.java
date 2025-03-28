package com.yanhuoyan.traceability.util;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility methods for working with PSI elements
 */
public class PsiUtils {

    /**
     * Check if a PsiElement is a variable reference
     * @param element the element to check
     * @return true if the element is a variable reference
     */
    public static boolean isVariable(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        
        // Check if element is an identifier
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            
            // Check if parent is a reference expression
            if (parent instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) parent).resolve();
                return resolved instanceof PsiVariable;
            }
        }
        
        return false;
    }
    
    /**
     * Resolve a PSI element to its variable declaration
     * @param element the element to resolve
     * @return the variable declaration or null if not a variable
     */
    @Nullable
    public static PsiVariable resolveToVariable(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        
        // If element is an identifier, get its parent
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            
            // If parent is a reference expression, resolve it
            if (parent instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) parent).resolve();
                if (resolved instanceof PsiVariable) {
                    return (PsiVariable) resolved;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Find all references to a variable within a method
     * @param variable the variable to find references to
     * @param method the method to search in
     * @return an array of reference expressions
     */
    @NotNull
    public static PsiReferenceExpression[] findReferencesInMethod(@NotNull PsiVariable variable, @NotNull PsiMethod method) {
        return PsiTreeUtil.collectElementsOfType(method, PsiReferenceExpression.class)
                .stream()
                .filter(ref -> {
                    PsiElement resolved = ref.resolve();
                    return resolved != null && resolved.equals(variable);
                })
                .toArray(PsiReferenceExpression[]::new);
    }
    
    /**
     * Find all assignments to a variable within a method
     * @param variable the variable to find assignments for
     * @param method the method to search in
     * @return an array of assignment expressions
     */
    @NotNull
    public static PsiAssignmentExpression[] findAssignmentsInMethod(@NotNull PsiVariable variable, @NotNull PsiMethod method) {
        PsiReferenceExpression[] references = findReferencesInMethod(variable, method);
        
        return PsiTreeUtil.collectElementsOfType(method, PsiAssignmentExpression.class)
                .stream()
                .filter(assignment -> {
                    PsiExpression lExpression = assignment.getLExpression();
                    if (lExpression instanceof PsiReferenceExpression) {
                        PsiElement resolved = ((PsiReferenceExpression) lExpression).resolve();
                        return resolved != null && resolved.equals(variable);
                    }
                    return false;
                })
                .toArray(PsiAssignmentExpression[]::new);
    }
} 