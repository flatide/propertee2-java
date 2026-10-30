package com.flatide.propertee2.core;

import com.flatide.propertee2.parser.ProperTeeLexer;
import com.flatide.propertee2.parser.ProperTeeParser;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.List;

public final class ScriptParser {
    private ScriptParser() {
    }

    private static class ErrorCollector extends BaseErrorListener {
        List<String> errors = new ArrayList<String>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                RecognitionException e) {
            errors.add("Line " + line + ":" + (charPositionInLine + 1) + " - " + msg);   // the column from 1 (spec v0.37.0)
        }
    }

    public static ProperTeeParser.RootContext parse(String scriptText, List<String> errors) {
        return parse(scriptText, "<memory>", errors);
    }

    /** Parse with a stable source id retained by tokens for module-aware errors/debugging. */
    public static ProperTeeParser.RootContext parse(String scriptText, String sourceId, List<String> errors) {
        ProperTeeLexer lexer = new ProperTeeLexer(CharStreams.fromString(scriptText, sourceId));
        ErrorCollector lexerErrors = new ErrorCollector();
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexerErrors);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        ProperTeeParser parser = new ProperTeeParser(tokens);
        ErrorCollector parserErrors = new ErrorCollector();
        parser.removeErrorListeners();
        parser.addErrorListener(parserErrors);

        ProperTeeParser.RootContext tree = parser.root();

        if (!lexerErrors.errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Lexer errors:\n");
            for (String e : lexerErrors.errors) {
                sb.append("  ").append(e).append("\n");
            }
            errors.add(sb.toString());
            return null;
        }
        if (!parserErrors.errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Parser errors:\n");
            for (String e : parserErrors.errors) {
                sb.append("  ").append(e).append("\n");
            }
            errors.add(sb.toString());
            return null;
        }

        return tree;
    }
}
