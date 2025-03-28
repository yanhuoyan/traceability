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
 * Factory for creating the Traceability tool window
 */
public class TraceabilityToolWindowFactory implements ToolWindowFactory {
    private static final Map<Project, TraceabilityPanel> panelMap = new HashMap<>();
    private static final Map<Editor, RangeHighlighter> highlighterMap = new HashMap<>();

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        TraceabilityPanel traceabilityPanel = new TraceabilityPanel(project, toolWindow);
        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(traceabilityPanel, "", false);
        toolWindow.getContentManager().addContent(content);
        
        panelMap.put(project, traceabilityPanel);
    }

    /**
     * Show trace results in the tool window
     */
    public static void showResults(@NotNull Project project, @NotNull TraceResult traceResult, @NotNull Editor editor) {
        // Get tool window
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Traceability");
        if (toolWindow == null) {
            return;
        }
        
        // Show tool window
        toolWindow.activate(null);
        
        // Get panel
        TraceabilityPanel panel = panelMap.get(project);
        if (panel == null) {
            return;
        }
        
        // Update panel with trace result
        panel.setTraceResult(traceResult);
        
        // Highlight the target variable
        highlightVariable(traceResult, editor);
    }
    
    /**
     * Highlight the traced variable in the editor
     */
    private static void highlightVariable(@NotNull TraceResult traceResult, @NotNull Editor editor) {
        // Clear previous highlights
        clearHighlights(editor);
        
        // Get target variable
        PsiElement targetVariable = traceResult.getTargetVariable();
        if (targetVariable == null) {
            return;
        }
        
        int startOffset = targetVariable.getTextOffset();
        int endOffset = startOffset + targetVariable.getTextLength();
        
        // Create text attributes for highlighting
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(new Color(255, 255, 0, 50));  // Yellow with alpha
        attributes.setEffectType(EffectType.BOXED);
        attributes.setEffectColor(Color.YELLOW);
        
        // Create highlighter
        MarkupModel markupModel = editor.getMarkupModel();
        RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                startOffset, endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
        );
        
        // Store highlighter for later removal
        highlighterMap.put(editor, highlighter);
    }
    
    /**
     * Clear variable highlights from an editor
     */
    private static void clearHighlights(@NotNull Editor editor) {
        RangeHighlighter highlighter = highlighterMap.remove(editor);
        if (highlighter != null && highlighter.isValid()) {
            editor.getMarkupModel().removeHighlighter(highlighter);
        }
    }
} 