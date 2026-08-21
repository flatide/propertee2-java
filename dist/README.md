# dist/

Release artifacts. Build the self-contained runnable JAR here with:

```bash
./gradlew dist        # -> dist/propertee2-<version>.jar + dist/propertee2.jar
```

Run it (requires **JDK 25** on PATH / `JAVA_HOME`):

```bash
java -jar dist/propertee2-0.1.0.jar path/to/script.tee
java -jar dist/propertee2-0.1.0.jar -p '{"width":100}' script.tee
```

The JAR bundles `propertee-cli` + `propertee-core` + `antlr4-runtime` (Main-Class
`com.flatide.propertee2.cli.Main`). The built `propertee2-<version>.jar` is committed here
(as in v1); refresh it with `./gradlew dist` after changing the engine.

`propertee2.jar` is an identical, **version-less copy** — a stable path for IDE settings,
aliases, and scripts that should survive releases. Point the VS Code extension at it once:

```jsonc
"propertee.runtime.command": "<jdk25>/bin/java",
"propertee.runtime.args": ["-jar", "<repo>/dist/propertee2.jar"]
```
