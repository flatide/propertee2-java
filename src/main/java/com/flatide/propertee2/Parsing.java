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
        public SyntaxException(String message) { super(message); }
    }

    private static final class Throwing extends BaseErrorListener {
        static final Throwing INSTANCE = new Throwing();
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new SyntaxException("syntax error at " + line + ":" + charPositionInLine + " - " + msg);
        }
    }
}
