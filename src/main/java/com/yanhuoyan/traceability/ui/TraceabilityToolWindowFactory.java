package com.yanhuoyan.traceability.ui;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiElement;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.yanhuoyan.traceability.engine.TraceResult;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * 追踪工具窗口工厂类
 * 负责创建和管理变量追踪结果的显示窗口
 */
public class TraceabilityToolWindowFactory implements ToolWindowFactory {
    private static final Map<Project, TraceabilityPanel> panelMap = new HashMap<>(); // 项目到面板的映射
    private static final Map<Editor, RangeHighlighter> highlighterMap = new HashMap<>(); // 编辑器到高亮器的映射

    /**
     * 创建工具窗口内容
     * 当工具窗口第一次打开时调用
     * 
     * @param project 当前项目
     * @param toolWindow 工具窗口实例
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 创建追踪结果面板
        TraceabilityPanel traceabilityPanel = new TraceabilityPanel(project, toolWindow);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(traceabilityPanel, "", false);
        toolWindow.getContentManager().addContent(content);
        
        // 保存项目与面板的映射关系
        panelMap.put(project, traceabilityPanel);
    }

    /**
     * 在工具窗口中显示追踪结果
     * 
     * @param project 当前项目
     * @param traceResult 变量追踪结果
     * @param editor 当前编辑器
     */
    public static void showResults(@NotNull Project project, @NotNull TraceResult traceResult, @NotNull Editor editor) {
        // 获取工具窗口
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Traceability");
        if (toolWindow == null) {
            return;
        }
        
        // 显示工具窗口
        toolWindow.activate(null);
        
        // 获取面板
        TraceabilityPanel panel = panelMap.get(project);
        if (panel == null) {
            return;
        }
        
        // 更新面板显示追踪结果
        panel.setTraceResult(traceResult);
        
        // 在编辑器中高亮目标变量
        highlightVariable(traceResult, editor);
    }
    
    /**
     * 在编辑器中高亮被追踪的变量
     * 
     * @param traceResult 变量追踪结果
     * @param editor 当前编辑器
     */
    private static void highlightVariable(@NotNull TraceResult traceResult, @NotNull Editor editor) {
        // 清除之前的高亮
        clearHighlights(editor);
        
        // 获取目标变量
        PsiElement targetVariable = traceResult.getTargetVariable();
        if (targetVariable == null) {
            return;
        }
        
        // 计算变量在文本中的位置
        int startOffset = targetVariable.getTextOffset();
        int endOffset = startOffset + targetVariable.getTextLength();
        
        // 创建高亮文本属性
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(new Color(255, 255, 0, 50));  // 半透明黄色背景
        attributes.setEffectType(EffectType.BOXED);
        attributes.setEffectColor(Color.YELLOW);
        
        // 创建高亮器
        MarkupModel markupModel = editor.getMarkupModel();
        RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
        );
        
        // 保存高亮器以便后续移除
        highlighterMap.put(editor, highlighter);
    }
    
    /**
     * 清除编辑器中的变量高亮
     * 
     * @param editor 要清除高亮的编辑器
     */
    private static void clearHighlights(@NotNull Editor editor) {
        // 移除并获取关联的高亮器
        RangeHighlighter highlighter = highlighterMap.remove(editor);
        if (highlighter != null && highlighter.isValid()) {
            // 从编辑器标记模型中移除高亮
            editor.getMarkupModel().removeHighlighter(highlighter);
        }
    }
} 