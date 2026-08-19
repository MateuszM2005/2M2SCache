# 2M2S Cache

A high-concurrency Java cache with a three-queue eviction policy (window, probation, protected), inspired by Segmented LRU / LIRS-style admission. Updates are batched through a lock-free buffer; a Count-Min Sketch estimates frequency for eviction decisions.

Requires **JDK 24** (uses `jdk.internal.vm.annotation.Contended`).

## Public API

`Cache2M2S<K, V>` in the `concurrent` package:

| Method | Description |
| --- | --- |
| `Cache2M2S()` | Cache with default max size (1,000,000) |
| `Cache2M2S(int maxCapacity)` | Cache with a given capacity (minimum 10,000) |
| `void put(K key, V value)` | Insert or update |
| `V get(K key)` | Lookup; marks the key as recently used |
| `void remove(K key)` | Remove a mapping |
| `int size()` | Number of entries |

See [src/concurrent/README.md](src/concurrent/README.md) for architecture details.

## Build a JAR

From the repository root (PowerShell or bash):

```bash
mkdir -p out/classes
javac --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -d out/classes \
  src/Main.java \
  src/concurrent/*.java
jar --create --file 2M2SCache.jar --main-class Main -C out/classes .
```

On Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force -Path out\classes | Out-Null
javac --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -d out\classes src\Main.java src\concurrent\*.java
jar --create --file 2M2SCache.jar --main-class Main -C out\classes .
```

Run:

```bash
java --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -jar 2M2SCache.jar
```

IntelliJ: open this folder as a project (module `Cacher`). `Cacher.iml` is committed so source roots and JUnit 6 stay shared. Compiler extra options already include the `--add-exports` flag.

## Tests

JUnit 6 tests live under `src/test/` and are not packaged into the JAR.

- `SmallTests` / `MediumTests`: run in CI and in IntelliJ
- `BigTests` / `Benchmark`: `@Disabled` by default (long / resource-heavy)

From the command line, after compiling production classes:

```bash
curl -fsSL -o junit-platform-console-standalone.jar \
  https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/6.0.0/junit-platform-console-standalone-6.0.0.jar
mkdir -p out/test-classes
javac --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -cp junit-platform-console-standalone.jar:out/classes \
  -d out/test-classes \
  src/test/*.java
java --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar junit-platform-console-standalone.jar execute \
  --class-path out/classes:out/test-classes \
  --scan-class-path
```

## CI

GitHub Actions (`.github/workflows/build.yml`) compiles the library, runs JUnit tests, and uploads `2M2SCache.jar` as a workflow artifact. The JAR is only uploaded if tests pass.
