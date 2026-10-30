package com.flatide.propertee2;

import com.flatide.propertee2.parser.ProperTeeLexer;
import com.flatide.propertee2.parser.ProperTeeParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * Front-end facade: turn ProperTee source into a parse tree. Syntax errors are made
 * loud (default ANTLR listeners only print to stderr and recover); the recursive
 * tree-walk interpreter (PB) will consume {@link ProperTeeParser.RootContext} from here.
 */
public final class Parsing {
    private Parsing() {}

    public static ProperTeeParser.RootContext parse(String source) {
        return parse(source, "<memory>");
    }

    /** Parse source while retaining a stable host source id in every token. */
    public static ProperTeeParser.RootContext parse(String source, String sourceId) {
        ProperTeeLexer lexer = new ProperTeeLexer(CharStreams.fromString(source, sourceId));
        lexer.removeErrorListeners();
        lexer.addErrorListener(Throwing.INSTANCE);

        ProperTeeParser parser = new ProperTeeParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(Throwing.INSTANCE);
        return parser.root();
    }

    /** Thrown on any lexer/parser syntax error. */
    public static final class SyntaxException extends RuntimeException {
        private final boolean atEof;
        private final String sourceId;
        private final int line;
        private final int column;

        public SyntaxException(String message) { this(message, false, "<memory>", -1, -1); }

        public SyntaxException(String message, boolean atEof) {
            this(message, atEof, "<memory>", -1, -1);
        }

        public SyntaxException(String message, boolean atEof, String sourceId, int line, int column) {
            super(message);
            this.atEof = atEof;
            this.sourceId = sourceId;
            this.line = line;
            this.column = column;
        }

        /**
         * True when the offending token was end-of-input — the source stopped mid-construct
         * (an unclosed {@code if}/{@code loop}/{@code function} block, a dangling operator, an
         * open paren). A REPL uses this to keep reading lines instead of reporting an error.
         */
        public boolean atEof() { return atEof; }
        public String sourceId() { return sourceId; }
        public int line() { return line; }
        public int column() { return column; }
    }

    private static final class Throwing extends BaseErrorListener {
        static final Throwing INSTANCE = new Throwing();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            boolean atEof = offendingSymbol instanceof org.antlr.v4.runtime.Token t
                    && t.getType() == org.antlr.v4.runtime.Token.EOF;
            String sourceId = recognizer.getInputStream().getSourceName();
            // the column counts from 1, as in every error report (spec v0.37.0)
            throw new SyntaxException("syntax error at " + line + ":" + (charPositionInLine + 1) + " - " + msg,
                    atEof, sourceId, line, charPositionInLine);
        }
    }
}
