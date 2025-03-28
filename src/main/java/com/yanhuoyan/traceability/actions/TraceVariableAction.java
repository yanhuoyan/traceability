package com.yanhuoyan.traceability.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.yanhuoyan.traceability.engine.TraceEngine;
import com.yanhuoyan.traceability.engine.TraceResult;
import com.yanhuoyan.traceability.ui.TraceabilityToolWindowFactory;
import com.yanhuoyan.traceability.util.PsiUtils;
import org.jetbrains.annotations.NotNull;

/**
 * 变量追踪动作类
 * 实现变量追踪的主要逻辑，处理用户选择变量后的追踪操作
 */
public class TraceVariableAction extends AnAction {

    /**
     * 构造函数
     * 初始化追踪变量动作，设置名称和描述
     */
    public TraceVariableAction() {
        super("Trace Variable", "Trace the selected variable's assignments", null);
    }

    /**
     * 更新动作的可用状态
     * 根据当前选中内容决定是否启用追踪变量动作
     * 
     * @param e 动作事件，包含当前上下文信息
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        // 获取当前项目和编辑器
        Project project = e.getData(CommonDataKeys.PROJECT);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        boolean enabled = false;
        if (project != null && editor != null) {
            // 获取选择模型
            SelectionModel selectionModel = editor.getSelectionModel();
            if (selectionModel.hasSelection()) {
                // 获取当前PSI文件
                PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
                if (psiFile instanceof PsiJavaFile) {
                    // 获取选中位置的起始偏移量
                    int startOffset = selectionModel.getSelectionStart();
                    // 获取选中位置的PSI元素
                    PsiElement element = psiFile.findElementAt(startOffset);
                    
                    // 检查选中元素是否为变量
                    enabled = PsiUtils.isVariable(element);
                }
            }
        }
        
        // 设置动作的可见性和可用性
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    /**
     * 执行变量追踪动作
     * 分析选中变量的赋值历史，并在工具窗口中显示结果
     * 
     * @param e 动作事件，包含当前上下文信息
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        // 获取当前项目、编辑器和PSI文件
        Project project = e.getData(CommonDataKeys.PROJECT);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        
        // 检查必要对象是否存在
        if (project == null || editor == null || psiFile == null) {
            return;
        }
        
        // 获取选择模型
        SelectionModel selectionModel = editor.getSelectionModel();
        if (!selectionModel.hasSelection()) {
            return;
        }
        
        // 获取选中位置的起始偏移量
        int startOffset = selectionModel.getSelectionStart();
        // 获取选中位置的PSI元素
        PsiElement element = psiFile.findElementAt(startOffset);
        if (!PsiUtils.isVariable(element)) {
            return;
        }
        
        // 解析为PSI变量对象
        PsiVariable variable = PsiUtils.resolveToVariable(element);
        if (variable == null) {
            return;
        }
        
        // 获取包含该变量的方法
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (containingMethod == null) {
            return;
        }
        
        // 启动追踪引擎，分析变量赋值历史
        TraceEngine traceEngine = new TraceEngine(project);
        TraceResult traceResult = traceEngine.traceVariable(variable, containingMethod);
        
        // 在工具窗口中显示追踪结果
        TraceabilityToolWindowFactory.showResults(project, traceResult, editor);
    }
} 