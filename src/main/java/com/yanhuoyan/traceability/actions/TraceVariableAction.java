package com.yanhuoyan.traceability.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.yanhuoyan.traceability.engine.TraceEngine;
import com.yanhuoyan.traceability.engine.TraceResult;
import com.yanhuoyan.traceability.ui.TraceabilityToolWindowFactory;
import com.yanhuoyan.traceability.util.PsiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

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
        
        // 显示设置对话框，让用户选择追踪深度
        TraceSettingsDialog dialog = new TraceSettingsDialog(project);
        if (dialog.showAndGet()) {
            int maxDepth = dialog.getMaxDepth();
            
            // 启动追踪引擎，分析变量赋值历史
            TraceEngine traceEngine = new TraceEngine(project, maxDepth);
            TraceResult traceResult = traceEngine.traceVariable(variable, containingMethod);
            
            // 在工具窗口中显示追踪结果
            TraceabilityToolWindowFactory.showResults(project, traceResult, editor);
        }
    }
    
    /**
     * 追踪设置对话框
     * 允许用户配置追踪参数
     */
    private class TraceSettingsDialog extends DialogWrapper {
        private JPanel mainPanel;
        private JSpinner depthSpinner;
        private static final int DEFAULT_MAX_DEPTH = 100;
        private static final int MAX_ALLOWED_DEPTH = 200; // 增加最大允许的追踪深度

        public TraceSettingsDialog(@Nullable Project project) {
            super(project);
            setTitle("变量追踪设置");
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            mainPanel = new JPanel(new BorderLayout());
            
            // 创建设置面板
            JPanel settingsPanel = new JPanel(new GridBagLayout());
            settingsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.gridwidth = 1;
            gbc.gridheight = 1;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(5, 5, 5, 5);
            
            JLabel depthLabel = new JLabel("最大追踪深度:");
            settingsPanel.add(depthLabel, gbc);
            
            gbc.gridx = 1;
            SpinnerNumberModel spinnerModel = new SpinnerNumberModel(DEFAULT_MAX_DEPTH, 1, MAX_ALLOWED_DEPTH, 10);
            depthSpinner = new JSpinner(spinnerModel);
            settingsPanel.add(depthSpinner, gbc);
            
            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.gridwidth = 2;
            JLabel noteLabel = new JLabel("<html><small>注意: 增大深度可能会导致性能下降，<br>建议从较小的值开始设置。</small></html>");
            noteLabel.setForeground(Color.GRAY);
            settingsPanel.add(noteLabel, gbc);
            
            mainPanel.add(settingsPanel, BorderLayout.CENTER);
            
            return mainPanel;
        }

        @Nullable
        @Override
        protected ValidationInfo doValidate() {
            int depthValue = (Integer) depthSpinner.getValue();
            if (depthValue <= 0) {
                return new ValidationInfo("深度必须大于0", depthSpinner);
            }
            if (depthValue > MAX_ALLOWED_DEPTH) {
                return new ValidationInfo("深度不能超过" + MAX_ALLOWED_DEPTH, depthSpinner);
            }
            return null;
        }

        public int getMaxDepth() {
            return (Integer) depthSpinner.getValue();
        }
    }
} 