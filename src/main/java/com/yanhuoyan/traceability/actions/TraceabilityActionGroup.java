package com.yanhuoyan.traceability.actions;

import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Action group for Traceability menu
 */
public class TraceabilityActionGroup extends DefaultActionGroup {

    public TraceabilityActionGroup() {
        super("Traceability", true);
        
        // Add trace variable action to the group
        add(new TraceVariableAction());
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Only show the menu in Java files when there's an editor
        e.getPresentation().setVisible(e.getProject() != null && e.getEditor() != null);
    }
} 