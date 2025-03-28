package com.yanhuoyan.traceability.ui;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.yanhuoyan.traceability.engine.TraceResult;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.List;

/**
 * 追踪结果显示面板
 * 用于在工具窗口中以树形结构显示变量追踪结果
 */
public class TraceabilityPanel extends JPanel {
    private final Project project; // 当前项目
    private final ToolWindow toolWindow; // 工具窗口
    private final JTree resultTree; // 结果显示树
    private final JTextArea detailsTextArea; // 详情显示区域
    private TraceResult currentTraceResult; // 当前追踪结果

    /**
     * 构造函数
     * 
     * @param project 当前项目
     * @param toolWindow 工具窗口
     */
    public TraceabilityPanel(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project;
        this.toolWindow = toolWindow;
        
        // 创建用于显示追踪结果的树控件
        resultTree = new Tree();
        resultTree.setCellRenderer(new TraceNodeRenderer());
        resultTree.addTreeSelectionListener(this::onNodeSelected);
        
        // 创建详情面板
        detailsTextArea = new JTextArea();
        detailsTextArea.setEditable(false);
        
        // 创建分割器
        JBSplitter splitter = new JBSplitter(true, 0.7f);
        splitter.setFirstComponent(new JBScrollPane(resultTree));
        splitter.setSecondComponent(new JBScrollPane(detailsTextArea));
        
        add(splitter, BorderLayout.CENTER);
    }

    /**
     * 更新面板显示追踪结果
     * 
     * @param traceResult 要显示的追踪结果
     */
    public void setTraceResult(@NotNull TraceResult traceResult) {
        this.currentTraceResult = traceResult;
        
        // 创建根节点
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("追踪变量: " + traceResult.getTargetVariable().getName());
        
        // 添加追踪节点
        List<TraceResult.TraceNode> rootNodes = traceResult.getRootNodes();
        for (TraceResult.TraceNode node : rootNodes) {
            buildTreeNode(node, rootNode);
        }
        
        // 更新树模型
        DefaultTreeModel model = new DefaultTreeModel(rootNode);
        resultTree.setModel(model);
        
        // 展开根节点
        resultTree.expandPath(new TreePath(rootNode.getPath()));
    }
    
    /**
     * 从追踪节点构建树节点
     * 
     * @param traceNode 追踪节点
     * @param parentTreeNode 父树节点
     */
    private void buildTreeNode(@NotNull TraceResult.TraceNode traceNode, @NotNull DefaultMutableTreeNode parentTreeNode) {
        // 为此追踪节点创建树节点
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(traceNode);
        parentTreeNode.add(treeNode);
        
        // 添加子节点
        List<TraceResult.TraceNode> children = traceNode.getChildren();
        for (TraceResult.TraceNode child : children) {
            buildTreeNode(child, treeNode);
        }
    }
    
    /**
     * 处理节点选择事件
     * 
     * @param e 树选择事件
     */
    private void onNodeSelected(TreeSelectionEvent e) {
        // 获取选中的树节点
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) resultTree.getLastSelectedPathComponent();
        if (treeNode == null || !(treeNode.getUserObject() instanceof TraceResult.TraceNode)) {
            return;
        }
        
        // 获取关联的追踪节点和PSI元素
        TraceResult.TraceNode traceNode = (TraceResult.TraceNode) treeNode.getUserObject();
        PsiElement element = traceNode.getElement();
        
        // 更新详情面板
        updateDetailsPanel(traceNode);
        
        // 导航到元素位置
        navigateToElement(element);
    }
    
    /**
     * 更新详情面板，显示选中追踪节点的信息
     * 
     * @param traceNode 选中的追踪节点
     */
    private void updateDetailsPanel(@NotNull TraceResult.TraceNode traceNode) {
        PsiElement element = traceNode.getElement();
        
        // 构建详情文本
        StringBuilder sb = new StringBuilder();
        sb.append("类型: ").append(traceNode.getType()).append("\n\n");
        sb.append("描述: ").append(traceNode.getDescription()).append("\n\n");
        
        // 如果有包含方法，添加方法信息
        PsiMethod containingMethod = findContainingMethod(element);
        if (containingMethod != null) {
            sb.append("所在方法: ").append(containingMethod.getName()).append("\n\n");
        }
        
        // 添加元素文本
        sb.append("元素文本: \n").append(element.getText());
        
        // 更新文本区域
        detailsTextArea.setText(sb.toString());
        detailsTextArea.setCaretPosition(0);
    }
    
    /**
     * 查找包含指定元素的方法
     * 
     * @param element 要查找的元素
     * @return 包含该元素的方法，如果没有则返回null
     */
    private PsiMethod findContainingMethod(PsiElement element) {
        // 逐级向上查找父元素，直到找到方法
        PsiElement current = element;
        while (current != null) {
            if (current instanceof PsiMethod) {
                return (PsiMethod) current;
            }
            current = current.getParent();
        }
        return null;
    }
    
    /**
     * 在编辑器中导航到指定的PSI元素
     * 
     * @param element 要导航到的元素
     */
    private void navigateToElement(PsiElement element) {
        // 检查元素有效性
        if (element == null || !element.isValid() || project.isDisposed()) {
            return;
        }
        
        // 获取包含文件
        if (element.getContainingFile() == null) {
            return;
        }
        
        // 获取偏移量
        int offset = element.getTextOffset();
        
        // 创建描述符并导航
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, element.getContainingFile().getVirtualFile(), offset);
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
    }
    
    /**
     * 追踪节点的自定义渲染器
     * 负责在树中显示追踪节点的图标和文本
     */
    private static class TraceNodeRenderer extends DefaultTreeCellRenderer {
        /**
         * 获取树单元格的渲染组件
         */
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, 
                                                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            
            // 检查节点类型
            if (value instanceof DefaultMutableTreeNode) {
                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                
                if (userObject instanceof TraceResult.TraceNode) {
                    TraceResult.TraceNode traceNode = (TraceResult.TraceNode) userObject;
                    setText(traceNode.getDescription());
                    
                    // 根据节点类型设置图标
                    switch (traceNode.getType()) {
                        case DECLARATION:
                            setIcon(com.intellij.icons.AllIcons.Nodes.Variable);
                            break;
                        case LOCAL_ASSIGNMENT:
                            setIcon(com.intellij.icons.AllIcons.Nodes.Variable);
                            break;
                        case PARAMETER:
                            setIcon(com.intellij.icons.AllIcons.Nodes.Parameter);
                            break;
                        case METHOD_CALL:
                            setIcon(com.intellij.icons.AllIcons.Nodes.Method);
                            break;
                        case FIELD_ACCESS:
                            setIcon(com.intellij.icons.AllIcons.Nodes.Field);
                            break;
                        default:
                            setIcon(com.intellij.icons.AllIcons.Nodes.Unknown);
                    }
                }
            }
            
            return this;
        }
    }
} 