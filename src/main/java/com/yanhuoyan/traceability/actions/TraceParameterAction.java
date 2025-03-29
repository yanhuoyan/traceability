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
 * 参数追踪动作类
 * 实现实体类getter方法的参数追踪逻辑，追溯参数的来源链
 */
public class TraceParameterAction extends AnAction {

    /**
     * 构造函数
     * 初始化追踪参数动作，设置名称和描述
     */
    public TraceParameterAction() {
        super("Trace Parameter", "Trace the selected getter method's value source and parameters", null);
    }

    /**
     * 更新动作的可用状态
     * 根据当前选中内容决定是否启用追踪参数动作
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
                    
                    // 检查选中元素是否为getter方法调用
                    enabled = isGetterMethodCall(element);
                }
            }
        }
        
        // 设置动作的可见性和可用性
        e.getPresentation().setEnabledAndVisible(enabled);
    }

    /**
     * 执行参数追踪动作
     * 分析选中getter方法的参数来源链，并在工具窗口中显示结果
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
        if (!isGetterMethodCall(element)) {
            return;
        }
        
        // 解析为PSI方法引用
        PsiMethod getterMethod = resolveToGetterMethod(element);
        if (getterMethod == null) {
            return;
        }
        
        // 获取包含该getter调用的方法
        PsiMethod containingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (containingMethod == null) {
            return;
        }
        
        // 显示设置对话框，让用户选择追踪深度
        TraceSettingsDialog dialog = new TraceSettingsDialog(project);
        if (dialog.showAndGet()) {
            int maxDepth = dialog.getMaxDepth();
            
            // 获取getter方法内部引用的字段
            PsiField field = getFieldFromGetter(getterMethod);
            
            if (field != null) {
                // 创建追踪结果
                TraceResult traceResult = new TraceResult(field);
                
                // 添加字段声明节点
                TraceResult.TraceNode fieldNode = new TraceResult.TraceNode(
                        TraceResult.TraceNodeType.DECLARATION,
                        field,
                        "字段声明: " + field.getName(),
                        null
                );
                traceResult.addTraceNode(fieldNode);
                
                // 启动追踪引擎，分析参数来源
                TraceEngine traceEngine = new TraceEngine(project, maxDepth);
                
                // 根据getter方法找到调用表达式
                PsiMethodCallExpression methodCallExpr = findMethodCallExpression(element);
                if (methodCallExpr != null) {
                    // 分析方法调用，并将追踪结果添加到结果集中
                    traceEngine.traceGetterMethod(methodCallExpr, getterMethod, field, fieldNode, traceResult, containingMethod);
                }
                
                // 在工具窗口中显示追踪结果
                TraceabilityToolWindowFactory.showResults(project, traceResult, editor);
            }
        }
    }
    
    /**
     * 检查PSI元素是否为getter方法调用
     * 
     * @param element 要检查的元素
     * @return 如果元素是getter方法则返回true
     */
    private boolean isGetterMethodCall(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        
        // 检查元素是否为标识符
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            
            // 检查父元素是否为引用表达式
            if (parent instanceof PsiReferenceExpression) {
                PsiElement grandParent = parent.getParent();
                
                // 检查是否为方法调用表达式
                if (grandParent instanceof PsiMethodCallExpression) {
                    PsiMethod method = ((PsiMethodCallExpression) grandParent).resolveMethod();
                    return method != null && isGetterMethod(method);
                }
            }
        }
        
        return false;
    }
    
    /**
     * 将PSI元素解析为其getter方法
     * 
     * @param element 要解析的元素
     * @return getter方法，如果不是getter则返回null
     */
    @Nullable
    private PsiMethod resolveToGetterMethod(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        
        // 如果元素是标识符，获取其父元素
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            
            // 如果父元素是引用表达式，检查其祖父元素
            if (parent instanceof PsiReferenceExpression) {
                PsiElement grandParent = parent.getParent();
                
                // 如果祖父元素是方法调用表达式，解析方法
                if (grandParent instanceof PsiMethodCallExpression) {
                    PsiMethod method = ((PsiMethodCallExpression) grandParent).resolveMethod();
                    if (method != null && isGetterMethod(method)) {
                        return method;
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * 判断方法是否为getter方法
     * 
     * @param method 要检查的方法
     * @return 如果是getter方法则返回true
     */
    private boolean isGetterMethod(@NotNull PsiMethod method) {
        String methodName = method.getName();
        
        // getter方法通常以get开头，没有参数
        if ((methodName.startsWith("get") && methodName.length() > 3) && 
                method.getParameterList().getParametersCount() == 0) {
            // 方法体应当仅返回一个字段值
            PsiCodeBlock body = method.getBody();
            if (body != null) {
                PsiStatement[] statements = body.getStatements();
                if (statements.length == 1 && statements[0] instanceof PsiReturnStatement) {
                    PsiReturnStatement returnStmt = (PsiReturnStatement) statements[0];
                    PsiExpression returnValue = returnStmt.getReturnValue();
                    if (returnValue instanceof PsiReferenceExpression) {
                        PsiElement resolved = ((PsiReferenceExpression) returnValue).resolve();
                        return resolved instanceof PsiField;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * 从getter方法中提取相关字段
     * 
     * @param method getter方法
     * @return 相关字段，如果无法提取则返回null
     */
    @Nullable
    private PsiField getFieldFromGetter(@NotNull PsiMethod method) {
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
    
    /**
     * 查找方法调用表达式
     * 
     * @param element 元素
     * @return 方法调用表达式
     */
    @Nullable
    private PsiMethodCallExpression findMethodCallExpression(@Nullable PsiElement element) {
        if (element == null) {
            return null;
        }
        
        if (element instanceof PsiIdentifier) {
            PsiElement parent = element.getParent();
            if (parent instanceof PsiReferenceExpression) {
                PsiElement grandParent = parent.getParent();
                if (grandParent instanceof PsiMethodCallExpression) {
                    return (PsiMethodCallExpression) grandParent;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 追踪设置对话框
     * 允许用户配置追踪参数
     */
    private class TraceSettingsDialog extends DialogWrapper {
        private JPanel mainPanel;
        private JSpinner depthSpinner;
        private static final int DEFAULT_MAX_DEPTH = 100;
        private static final int MAX_ALLOWED_DEPTH = 200;

        public TraceSettingsDialog(@Nullable Project project) {
            super(project);
            setTitle("参数追踪设置");
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