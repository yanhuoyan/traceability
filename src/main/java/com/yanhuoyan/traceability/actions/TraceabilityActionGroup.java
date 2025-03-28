package com.yanhuoyan.traceability.actions;

import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import org.jetbrains.annotations.NotNull;

/**
 * Traceability菜单动作组
 * 负责在编辑器右键菜单中创建Traceability菜单项及子菜单
 */
public class TraceabilityActionGroup extends DefaultActionGroup {

    /**
     * 构造函数
     * 初始化菜单组并添加追踪变量的子菜单项
     */
    public TraceabilityActionGroup() {
        super("Traceability", true);
        
        // 添加追踪变量动作到菜单组
        add(new TraceVariableAction());
    }

    /**
     * 更新菜单项的可见性
     * 
     * @param e 动作事件，包含当前上下文信息
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        // 仅在Java文件中且有编辑器打开时显示菜单
        e.getPresentation().setVisible(e.getProject() != null && e.getData(CommonDataKeys.EDITOR) != null);
    }
} 