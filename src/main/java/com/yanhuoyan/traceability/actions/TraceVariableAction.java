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
 * Action to trace a selected variable
 */
public class TraceVariableAction extends AnAction {

    public TraceVariableAction() {
        super("Trace Variable", "Trace the selected variable's assignments", null);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action only if a variable is selected
        Project project = e.getData(CommonDataKeys.PROJECT);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        
        boolean enabled = false;
        if (project != null && editor != null) {
            SelectionModel selectionModel = editor.getSelectionModel();
            if (selectionModel.hasSelection()) {
                PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
                if (psiFile instanceof PsiJavaFile) {
                    int startOffset = selectionModel.getSelectionStart();
                    PsiElement element = psiFile.findElementAt(startOffset);
                    
                    // Check if selected element is a variable
                    enabled = PsiUtils.isVariable(element);
                }
            }
        }
        
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getData(CommonDataKeys.PROJECT);
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        
        if (project == null || editor == null || psiFile == null) {
            return;
        }
        
        // Get the selected variable
        SelectionModel selectionModel = editor.getSelectionModel();
        if (!selectionModel.hasSelection()) {
            return;
        }
        
        int startOffset = selectionModel.getSelectionStart();
        PsiElement element = psiFile.findElementAt(startOffset);
        if (!PsiUtils.isVariable(element)) {
            return;
        }
        
        // Get the PsiVariable from the selected element
        PsiVariable variable = PsiUtils.resolveToVariable(element);
        if (variable == null) {
            return;
        }
        
        // Get the method that contains this variable
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (containingMethod == null) {
            return;
        }
        
        // Start the trace engine
        TraceEngine traceEngine = new TraceEngine(project);
        TraceResult traceResult = traceEngine.traceVariable(variable, containingMethod);
        
        // Show the result in tool window
        TraceabilityToolWindowFactory.showResults(project, traceResult, editor);
    }
} 