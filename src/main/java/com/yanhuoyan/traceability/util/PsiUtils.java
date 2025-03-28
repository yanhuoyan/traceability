package com.yanhuoyan.traceability.util;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PSI元素工具类
 * 提供处理PSI元素的实用方法
 */
public class PsiUtils {

    /**
     * 检查PSI元素是否为变量引用
     * 
     * @param element 要检查的元素
     * @return 如果元素是变量引用则返回true
     */
    public static boolean isVariable(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        
        // 检查元素是否为标识符
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            
            // 检查父元素是否为引用表达式
            if (parent instanceof PsiReferenceExpression) {
                PsiElement resolved = ((PsiReferenceExpression) parent).resolve();
                return resolved instanceof PsiVariable;
            }
        }
        
        return false;
    }
    
    /**
     * 将PSI元素解析为其变量声明
     * 
     * @param element 要解析的元素
     * @return 变量声明，如果不是变量则返回null
     */
    @Nullable
    public static PsiVariable resolveToVariable(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        
        // 如果元素是标识符，获取其父元素
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            
            // 如果父元素是引用表达式，解析它
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
     * 查找方法中对变量的所有引用
     * 
     * @param variable 要查找引用的变量
     * @param method 要搜索的方法
     * @return 引用表达式数组
     */
    @NotNull
    public static PsiReferenceExpression[] findReferencesInMethod(@NotNull PsiVariable variable, @NotNull PsiMethod method) {
        // 收集方法中所有引用表达式，并过滤出引用目标变量的表达式
        return PsiTreeUtil.collectElementsOfType(method, PsiReferenceExpression.class)
                .stream()
                .filter(ref -> {
                    PsiElement resolved = ref.resolve();
                    return resolved != null && resolved.equals(variable);
                })
                .toArray(PsiReferenceExpression[]::new);
    }
    
    /**
     * 查找方法中对变量的所有赋值
     * 
     * @param variable 要查找赋值的变量
     * @param method 要搜索的方法
     * @return 赋值表达式数组
     */
    @NotNull
    public static PsiAssignmentExpression[] findAssignmentsInMethod(@NotNull PsiVariable variable, @NotNull PsiMethod method) {
        // 先获取对变量的所有引用
        PsiReferenceExpression[] references = findReferencesInMethod(variable, method);
        
        // 收集方法中所有赋值表达式，并过滤出左侧是目标变量的表达式
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