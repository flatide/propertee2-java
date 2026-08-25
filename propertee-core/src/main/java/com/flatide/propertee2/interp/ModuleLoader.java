package com.flatide.propertee2.interp;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.module.ModuleRequest;
import com.flatide.propertee2.module.ModuleResolutionException;
import com.flatide.propertee2.module.ModuleResolver;
import com.flatide.propertee2.module.ModuleSource;
import com.flatide.propertee2.parser.ProperTeeParser.FunctionDefContext;
import com.flatide.propertee2.parser.ProperTeeParser.FuncDefStmtContext;
import com.flatide.propertee2.parser.ProperTeeParser.ImportStmtContext;
import com.flatide.propertee2.parser.ProperTeeParser.RootContext;
import com.flatide.propertee2.parser.ProperTeeParser.StatementContext;
import com.flatide.propertee2.value.TeeError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolve and validate every direct import before the entry's first side effect. */
final class ModuleLoader {
    private ModuleLoader() {}

    static LoadedModules load(RootContext entry, ModuleResolver resolver,
                              Set<String> hiddenKeywords, Set<String> ignoredFunctions) {
        if (entry.importStmt().isEmpty()) return LoadedModules.EMPTY;
        if (resolver == null) {
            throw entryAt("import requires a module resolver", entry.importStmt(0));
        }

        Map<String, ModuleInstance> aliases = new LinkedHashMap<>();
        Map<String, ModuleInstance> identities = new LinkedHashMap<>();
        for (ImportStmtContext imp : entry.importStmt()) {
            String alias = imp.alias.getText();
            if (aliases.containsKey(alias)) throw entryAt("Duplicate import alias '" + alias + "'", imp);

            String moduleId = imp.modulePath().getText();
            Integer version = parseVersion(imp);
            ModuleRequest request = new ModuleRequest(moduleId, version);
            ModuleSource source;
            try {
                source = resolver.resolve(request);
            } catch (ModuleResolutionException e) {
                throw entryAt(e.getMessage(), imp);
            } catch (RuntimeException e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw entryAt("Cannot resolve module '" + request.displayName() + "': " + detail, imp);
            }
            if (source == null) throw entryAt("Module '" + request.displayName() + "' was not found", imp);
            if (!moduleId.equals(source.moduleId())) {
                throw entryAt("Resolver returned module '" + source.moduleId() + "' for '" + moduleId + "'", imp);
            }
            if (version != null && !version.equals(source.version())) {
                throw entryAt("Resolver returned version '" + source.version() + "' for exact module '"
                        + request.displayName() + "'", imp);
            }
            if (identities.containsKey(source.identity())) {
                throw entryAt("Duplicate import of module '" + source.identity() + "'", imp);
            }

            RootContext moduleRoot;
            try {
                moduleRoot = Parsing.parse(source.source(), source.sourceId());
            } catch (Parsing.SyntaxException e) {
                throw new TeeError("Invalid module '" + source.identity() + "': " + e.getMessage(),
                        source.sourceId(), e.line(), e.column());
            }
            if (!moduleRoot.importStmt().isEmpty()) {
                ImportStmtContext nested = moduleRoot.importStmt(0);
                throw moduleAt("Imported module '" + source.identity() + "' cannot contain import statements", nested);
            }
            if (!hiddenKeywords.isEmpty() || !ignoredFunctions.isEmpty()) {
                TeeError blocked = Validator.firstViolation(moduleRoot, hiddenKeywords, ignoredFunctions);
                if (blocked != null) {
                    throw new TeeError(blocked.getMessage(), source.sourceId(), blocked.line(), blocked.col());
                }
            }

            ModuleInstance instance = new ModuleInstance(source);
            collectFunctions(instance, moduleRoot);
            identities.put(instance.identity(), instance);
            aliases.put(alias, instance);
        }
        return new LoadedModules(Map.copyOf(aliases), List.copyOf(identities.values()));
    }

    private static Integer parseVersion(ImportStmtContext imp) {
        if (imp.version == null) return null;
        String text = imp.version.getText();
        if (!text.matches("[1-9][0-9]*")) {
            throw entryAt("Module version must be a canonical positive integer", imp);
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw entryAt("Module version is out of range: " + text, imp);
        }
    }

    private static void collectFunctions(ModuleInstance module, RootContext root) {
        for (StatementContext statement : root.statement()) {
            if (!(statement instanceof FuncDefStmtContext def)) continue;
            FunctionDefContext f = def.functionDef();
            String name = f.funcName.getText();
            if (name.matches("[A-Z][A-Z0-9_]*")) {
                throw moduleAt("Cannot define function '" + name
                        + "': all-uppercase names are reserved for built-in and host functions", f);
            }
            List<String> params = new ArrayList<>();
            if (f.parameterList() != null) {
                f.parameterList().ID().forEach(id -> params.add(id.getText()));
            }
            module.functions.put(name, new UserFunction(name, params, f.block(), module));
        }
    }

    /** Entry diagnostics retain the language's path-free compatibility rendering. */
    private static TeeError entryAt(String message, org.antlr.v4.runtime.ParserRuleContext ctx) {
        return new TeeError(message, ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine());
    }

    /** Imported-source diagnostics include source identity so the module can be located. */
    private static TeeError moduleAt(String message, org.antlr.v4.runtime.ParserRuleContext ctx) {
        String sourceId = ctx.getStart().getInputStream().getSourceName();
        return new TeeError(message, sourceId, ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine());
    }
}
