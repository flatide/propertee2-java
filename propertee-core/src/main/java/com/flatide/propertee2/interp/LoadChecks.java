package com.flatide.propertee2.interp;

import com.flatide.propertee2.parser.ProperTeeParser.*;
import com.flatide.propertee2.value.TeeError;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The load-time checks of spec v0.37.0. A script that breaks one does not run: it is refused before
 * its first statement, at the first violation in document order, the way a blocked construct is
 * (spec v0.14.0, {@link Validator}).
 *
 * <ul>
 *   <li>{@code on error} comes right after the imports, at most once, and {@code on} is followed by
 *       {@code error}.</li>
 *   <li>The value of a {@code return} starts on the {@code return} line — line breaks do not end
 *       statements, so a bare {@code return} would otherwise take the next line as its value.</li>
 *   <li>{@code break} and {@code continue} act on a loop around them in the same function body. A
 *       function body and a monitor body are boundaries; a {@code multi} setup is not.</li>
 * </ul>
 */
public final class LoadChecks {

    private LoadChecks() {}

    /** The first violation of these checks, or of the host restrictions when given, in document order. */
    public static TeeError firstViolation(RootContext root, Set<String> hiddenKeywords, Set<String> ignoredFunctions) {
        TeeError own = firstViolation(root);
        if (hiddenKeywords.isEmpty() && ignoredFunctions.isEmpty()) return own;
        TeeError blocked = Validator.firstViolation(root, hiddenKeywords, ignoredFunctions);
        if (own == null) return blocked;
        if (blocked == null) return own;
        boolean blockedFirst = blocked.line() < own.line()
                || (blocked.line() == own.line() && blocked.col() < own.col());
        return blockedFirst ? blocked : own;
    }

    /** The first violation of these checks in document order, or {@code null}. */
    public static TeeError firstViolation(RootContext root) {
        List<Violation> found = new ArrayList<>();
        List<StatementContext> statements = root.statement();
        ErrorHandlerContext placed = !statements.isEmpty() && statements.get(0) instanceof ErrorHandlerStmtContext h
                ? h.errorHandler() : null;
        walk(root, 0, placed, found);
        Violation first = null;
        for (Violation v : found) {
            if (first == null || v.line < first.line || (v.line == first.line && v.col < first.col)) first = v;
        }
        return first == null ? null : new TeeError(first.message, first.line, first.col);
    }

    private static void walk(ParseTree t, int loopsAround, ErrorHandlerContext placed, List<Violation> out) {
        int loops = loopsAround;
        boolean outside = loopsAround == 0;
        switch (t) {
            case ErrorHandlerContext h -> {
                if (h != placed) {
                    report(placed != null ? "A script can have only one 'on error'"
                            : "'on error' must come right after the imports", h.K_ON().getSymbol(), out);
                }
                if (!h.ID().getText().equals("error")) report("Expected 'error' after 'on'", h.ID().getSymbol(), out);
            }
            case BreakStmtContext b when outside -> report("'break' must be inside a loop", b.K_BREAK().getSymbol(), out);
            case FallbackBreakContext b when outside -> report("'break' must be inside a loop", b.K_BREAK().getSymbol(), out);
            case ContinueStmtContext c when outside -> report("'continue' must be inside a loop", c.K_CONTINUE().getSymbol(), out);
            case FallbackContinueContext c when outside -> report("'continue' must be inside a loop", c.K_CONTINUE().getSymbol(), out);
            case ReturnStmtContext r -> sameLine(r.K_RETURN().getSymbol(), r.expression(), out);
            case FallbackReturnContext r -> sameLine(r.K_RETURN().getSymbol(), r.expression(), out);
            case BlockContext block -> {
                // A loop body adds a loop; a function body and a monitor body start without one.
                if (block.getParent() instanceof IterationStmtContext) loops++;
                else if (block.getParent() instanceof FunctionDefContext
                        || block.getParent() instanceof MonitorClauseContext) loops = 0;
            }
            default -> { }
        }
        for (int i = 0; i < t.getChildCount(); i++) walk(t.getChild(i), loops, placed, out);
    }

    private static void sameLine(Token keyword, ExpressionContext value, List<Violation> out) {
        if (value != null && value.getStart().getLine() != keyword.getLine()) {
            report("The value of 'return' must start on the same line", keyword, out);
        }
    }

    private static void report(String message, Token at, List<Violation> out) {
        out.add(new Violation(message, at.getLine(), at.getCharPositionInLine()));
    }

    private record Violation(String message, int line, int col) {}
}
