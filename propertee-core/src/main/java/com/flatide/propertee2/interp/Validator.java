package com.flatide.propertee2.interp;

import com.flatide.propertee2.parser.ProperTeeParser.*;
import com.flatide.propertee2.value.TeeError;

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
        List<Violation> found = new ArrayList<>();
        walk(tree, hiddenKeywords, ignoredFunctions, found);
        List<String> out = new ArrayList<>(found.size());
        for (Violation v : found) {
            out.add("line " + v.line + ":" + v.col + ": '" + v.name + "' is not available in this environment");
        }
        return out;
    }

    /**
     * Load-time rejection (spec v0.14.0): a script containing any blocked construct must not run.
     * Returns the <b>first</b> violation in document order as a {@link TeeError} carrying the same
     * message and position as the runtime backstop would — {@code positioned()} renders the identical
     * {@code Runtime Error at line L:C: 'X' is not available in this environment} — or {@code null}
     * when clean. Callers with no restrictions can skip the walk entirely.
     */
    public static TeeError firstViolation(ParseTree tree, Set<String> hiddenKeywords, Set<String> ignoredFunctions) {
        List<Violation> found = new ArrayList<>();
        walk(tree, hiddenKeywords, ignoredFunctions, found);
        if (found.isEmpty()) return null;
        Violation v = found.get(0);
        return new TeeError("'" + v.name + "' is not available in this environment", v.line, v.col);
    }

    private static void walk(ParseTree t, Set<String> hidden, Set<String> ignored, List<Violation> out) {
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

    private static void checkKeyword(String keyword, ParserRuleContext ctx, Set<String> hidden, List<Violation> out) {
        if (hidden.contains(keyword)) report(keyword, ctx.getStart(), out);
    }

    private static void report(String name, Token at, List<Violation> out) {
        out.add(new Violation(name, at.getLine(), at.getCharPositionInLine()));
    }

    private record Violation(String name, int line, int col) {}
}
