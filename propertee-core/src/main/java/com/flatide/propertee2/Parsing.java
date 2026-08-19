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
        ProperTeeLexer lexer = new ProperTeeLexer(CharStreams.fromString(source));
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

        public SyntaxException(String message) { this(message, false); }

        public SyntaxException(String message, boolean atEof) {
            super(message);
            this.atEof = atEof;
        }

        /**
         * True when the offending token was end-of-input — the source stopped mid-construct
         * (an unclosed {@code if}/{@code loop}/{@code function} block, a dangling operator, an
         * open paren). A REPL uses this to keep reading lines instead of reporting an error.
         */
        public boolean atEof() { return atEof; }
    }

    private static final class Throwing extends BaseErrorListener {
        static final Throwing INSTANCE = new Throwing();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            boolean atEof = offendingSymbol instanceof org.antlr.v4.runtime.Token t
                    && t.getType() == org.antlr.v4.runtime.Token.EOF;
            throw new SyntaxException("syntax error at " + line + ":" + charPositionInLine + " - " + msg, atEof);
        }
    }
}
