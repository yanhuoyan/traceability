package com.yanhuoyan.traceability.engine;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiVariable;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Class representing the result of a variable trace operation
 */
public class TraceResult {
    private final PsiVariable targetVariable;
    private final List<TraceNode> traceNodes;

    public TraceResult(@NotNull PsiVariable targetVariable) {
        this.targetVariable = targetVariable;
        this.traceNodes = new ArrayList<>();
    }

    /**
     * Get the variable being traced
     */
    @NotNull
    public PsiVariable getTargetVariable() {
        return targetVariable;
    }

    /**
     * Get all trace nodes
     */
    @NotNull
    public List<TraceNode> getTraceNodes() {
        return traceNodes;
    }

    /**
     * Add a trace node to the result
     */
    public void addTraceNode(@NotNull TraceNode node) {
        traceNodes.add(node);
    }

    /**
     * Get the root trace nodes (nodes with no parent)
     */
    @NotNull
    public List<TraceNode> getRootNodes() {
        List<TraceNode> rootNodes = new ArrayList<>();
        for (TraceNode node : traceNodes) {
            if (node.getParent() == null) {
                rootNodes.add(node);
            }
        }
        return rootNodes;
    }

    /**
     * Class representing a node in the trace tree
     */
    public static class TraceNode {
        private final TraceNodeType type;
        private final PsiElement element;
        private final String description;
        private final TraceNode parent;
        private final List<TraceNode> children;

        public TraceNode(@NotNull TraceNodeType type, @NotNull PsiElement element, 
                        @NotNull String description, TraceNode parent) {
            this.type = type;
            this.element = element;
            this.description = description;
            this.parent = parent;
            this.children = new ArrayList<>();
            
            // Add this node as a child to parent if parent is not null
            if (parent != null) {
                parent.addChild(this);
            }
        }

        @NotNull
        public TraceNodeType getType() {
            return type;
        }

        @NotNull
        public PsiElement getElement() {
            return element;
        }

        @NotNull
        public String getDescription() {
            return description;
        }

        public TraceNode getParent() {
            return parent;
        }

        @NotNull
        public List<TraceNode> getChildren() {
            return children;
        }

        public void addChild(@NotNull TraceNode child) {
            children.add(child);
        }
    }

    /**
     * Enum representing the type of a trace node
     */
    public enum TraceNodeType {
        DECLARATION,       // Variable declaration
        LOCAL_ASSIGNMENT,  // Assignment within the same method
        PARAMETER,         // Method parameter
        METHOD_CALL,       // Value from method call
        FIELD_ACCESS,      // Field access
        UNKNOWN            // Unknown source
    }
} 