package com.flatide.propertee2.interp;

import com.flatide.parser.ProperTeeParser.*;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Opt-in <b>static validation pass</b> for host restrictions (ProperTee issue #9). The runtime
 * enforces {@code setHiddenKeywords}/{@code setIgnoredFunctions} only when a construct is reached,
 * so a forbidden statement in an untaken branch goes undetected until input steers execution there.
 * This pass conservatively scans the whole parse tree — dead branches included — and reports every
 * hidden-keyword construct and ignored-function call, with the runtime's message text and the
 * construct's position.
 *
 * <p>The scan has no false negatives for these two restriction types: hidden keywords are
 * grammar-level constructs, and calls are identifiable by name (since spec v0.12.0 a script cannot
 * even define an all-uppercase function, and the runtime checks the ignore list before script
 * functions, so a name match is exactly the runtime behavior). The runtime checks stay in place as
 * a backstop; this pass is additional, never a replacement.
 */
public final class Validator {

    private Validator() {}

    /** Scan {@code tree} and return one {@code "line L:C: 'X' is not available in this environment"} entry per violation (empty = clean). */
    public static List<String> validate(ParseTree tree, Set<String> hiddenKeywords, Set<String> ignoredFunctions) {
        List<String> out = new ArrayList<>();
        walk(tree, hiddenKeywords, ignoredFunctions, out);
        return out;
    }

    private static void walk(ParseTree t, Set<String> hidden, Set<String> ignored, List<String> out) {
        if (t instanceof IfStatementContext c)          checkKeyword("if", c, hidden, out);       // covers the elseif chain
        else if (t instanceof IterationStmtContext c)   checkKeyword("loop", c, hidden, out);     // all three loop forms
        else if (t instanceof FunctionDefContext c)     checkKeyword("function", c, hidden, out);
        else if (t instanceof ParallelStmtContext c)    checkKeyword("multi", c, hidden, out);
        else if (t instanceof SpawnStmtContext c)       checkKeyword("thread", c, hidden, out);
        else if (t instanceof DebugStmtContext c)       checkKeyword("debug", c, hidden, out);
        else if (t instanceof FunctionCallContext c && ignored.contains(c.funcName.getText())) {
            report(c.funcName.getText(), c.funcName, out);
        }
        // Always recurse — a report on a construct does not hide violations nested inside it.
        for (int i = 0; i < t.getChildCount(); i++) walk(t.getChild(i), hidden, ignored, out);
    }

    private static void checkKeyword(String keyword, ParserRuleContext ctx, Set<String> hidden, List<String> out) {
        if (hidden.contains(keyword)) report(keyword, ctx.getStart(), out);
    }

    private static void report(String name, Token at, List<String> out) {
        out.add("line " + at.getLine() + ":" + at.getCharPositionInLine()
                + ": '" + name + "' is not available in this environment");
    }
}
