import java.util.concurrent.StructuredTaskScope;

/**
 * Spike step 0 — empirically determine whether java.util.concurrent.StructuredTaskScope
 * is still a preview API on this JDK.
 *
 * Compile this WITHOUT --enable-preview:
 *     javac StsProbe.java
 * If STS is preview, javac fails with:
 *     "error: ... is a preview API and is disabled by default"
 * If it compiles clean, STS is final on this JDK and `multi` could use it directly.
 *
 * Uses the redesigned JEP 505 API (open()/Joiner/fork/join) so a clean compile also
 * confirms the *new* shape, not the old removed one.
 */
public class StsProbe {
    public static void main(String[] args) throws Exception {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<String>allSuccessfulOrThrow())) {
            var a = scope.fork(() -> "a");
            var b = scope.fork(() -> "b");
            scope.join();
            System.out.println("STS ran: " + a.get() + b.get());
        }
    }
}
