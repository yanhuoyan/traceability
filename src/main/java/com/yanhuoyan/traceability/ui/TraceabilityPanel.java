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
 * Panel for displaying trace results
 */
public class TraceabilityPanel extends JPanel {
    private final Project project;
    private final ToolWindow toolWindow;
    private final JTree resultTree;
    private final JTextArea detailsTextArea;
    private TraceResult currentTraceResult;

    public TraceabilityPanel(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        super(new BorderLayout());
        this.project = project;
        this.toolWindow = toolWindow;
        
        // Create tree for showing trace results
        resultTree = new Tree();
        resultTree.setCellRenderer(new TraceNodeRenderer());
        resultTree.addTreeSelectionListener(this::onNodeSelected);
        
        // Create details panel
        detailsTextArea = new JTextArea();
        detailsTextArea.setEditable(false);
        
        // Create splitter
        JBSplitter splitter = new JBSplitter(true, 0.7f);
        splitter.setFirstComponent(new JBScrollPane(resultTree));
        splitter.setSecondComponent(new JBScrollPane(detailsTextArea));
        
        add(splitter, BorderLayout.CENTER);
    }

    /**
     * Update the panel with trace results
     */
    public void setTraceResult(@NotNull TraceResult traceResult) {
        this.currentTraceResult = traceResult;
        
        // Create root node
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Trace for: " + traceResult.getTargetVariable().getName());
        
        // Add trace nodes
        List<TraceResult.TraceNode> rootNodes = traceResult.getRootNodes();
        for (TraceResult.TraceNode node : rootNodes) {
            buildTreeNode(node, rootNode);
        }
        
        // Update tree model
        DefaultTreeModel model = new DefaultTreeModel(rootNode);
        resultTree.setModel(model);
        
        // Expand root
        resultTree.expandPath(new TreePath(rootNode.getPath()));
    }
    
    /**
     * Build tree nodes from trace nodes
     */
    private void buildTreeNode(@NotNull TraceResult.TraceNode traceNode, @NotNull DefaultMutableTreeNode parentTreeNode) {
        // Create tree node for this trace node
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(traceNode);
        parentTreeNode.add(treeNode);
        
        // Add children
        List<TraceResult.TraceNode> children = traceNode.getChildren();
        for (TraceResult.TraceNode child : children) {
            buildTreeNode(child, treeNode);
        }
    }
    
    /**
     * Handle node selection
     */
    private void onNodeSelected(TreeSelectionEvent e) {
        DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) resultTree.getLastSelectedPathComponent();
        if (treeNode == null || !(treeNode.getUserObject() instanceof TraceResult.TraceNode)) {
            return;
        }
        
        TraceResult.TraceNode traceNode = (TraceResult.TraceNode) treeNode.getUserObject();
        PsiElement element = traceNode.getElement();
        
        // Update details
        updateDetailsPanel(traceNode);
        
        // Navigate to element
        navigateToElement(element);
    }
    
    /**
     * Update details panel with information about the selected trace node
     */
    private void updateDetailsPanel(@NotNull TraceResult.TraceNode traceNode) {
        PsiElement element = traceNode.getElement();
        
        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(traceNode.getType()).append("\n\n");
        sb.append("Description: ").append(traceNode.getDescription()).append("\n\n");
        
        // Add containing method if available
        PsiMethod containingMethod = findContainingMethod(element);
        if (containingMethod != null) {
            sb.append("Containing Method: ").append(containingMethod.getName()).append("\n\n");
        }
        
        // Add element text
        sb.append("Element Text: \n").append(element.getText());
        
        detailsTextArea.setText(sb.toString());
        detailsTextArea.setCaretPosition(0);
    }
    
    /**
     * Find the method containing an element
     */
    private PsiMethod findContainingMethod(PsiElement element) {
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
     * Navigate to a PSI element in the editor
     */
    private void navigateToElement(PsiElement element) {
        if (element == null || !element.isValid() || project.isDisposed()) {
            return;
        }
        
        // Get containing file
        if (element.getContainingFile() == null) {
            return;
        }
        
        // Get offset
        int offset = element.getTextOffset();
        
        // Create descriptor and navigate
        OpenFileDescriptor descriptor = new OpenFileDescriptor(project, element.getContainingFile().getVirtualFile(), offset);
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true);
    }
    
    /**
     * Custom renderer for trace nodes
     */
    private static class TraceNodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, 
                                                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            
            if (value instanceof DefaultMutableTreeNode) {
                Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
                
                if (userObject instanceof TraceResult.TraceNode) {
                    TraceResult.TraceNode traceNode = (TraceResult.TraceNode) userObject;
                    setText(traceNode.getDescription());
                    
                    // Set icon based on node type
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