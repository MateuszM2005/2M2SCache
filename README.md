# 2M2S Cache

A high-concurrency Java cache with a three-queue eviction policy (window, probation, protected), inspired by Segmented LRU / LIRS-style admission. Updates are batched through a lock-free buffer; a Count-Min Sketch estimates frequency for eviction decisions.

Requires **JDK 17 or newer**. CI builds on 24.

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

## Build

From the repository root:

```bash
mkdir -p out/classes
javac -d out/classes src/concurrent/*.java
```

PowerShell:

```powershell
New-Item -ItemType Directory -Force -Path out\classes | Out-Null
javac -d out\classes src\concurrent\*.java
```

## Tests

JUnit 6 tests live under `src/test/`.

- `SmallTests` / `MediumTests`: run in CI and locally by default
- `BigTests` / `Benchmark`: `@Disabled` by default (long / resource-heavy)

### Local runs (recommended)

`tools/run-tests.ps1` needs nothing but a JDK on `PATH`. It downloads the JUnit console
launcher into `tools/lib/` on first use and then compiles and runs the same way CI does.

```powershell
.\tools\run-tests.ps1                         # SmallTests + MediumTests (~35 s)
.\tools\run-tests.ps1 -Class test.SmallTests  # fast feedback (~4 s)
.\tools\run-tests.ps1 -CompileOnly            # type-check only
.\tools\run-tests.ps1 -Clean                  # rebuild from scratch
.\tools\run-tests.ps1 -Method 'test.SmallTests#testValueUpdate'
.\tools\run-tests.ps1 -Class test.Benchmark -IncludeDisabled
```

`-IncludeDisabled` deactivates the JUnit condition that honours `@Disabled`, which is how
`BigTests` and `Benchmark` can be run without editing annotations. JUnit XML reports land in
`out/test-reports/`.

### Manual invocation

If you would rather drive `javac` yourself, after compiling production classes:

```bash
curl -fsSL -o junit-platform-console-standalone.jar \
  https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/6.0.0/junit-platform-console-standalone-6.0.0.jar
mkdir -p out/test-classes
javac -cp junit-platform-console-standalone.jar:out/classes \
  -d out/test-classes \
  src/test/*.java
java -ea -jar junit-platform-console-standalone.jar execute \
  --class-path out/classes:out/test-classes \
  --scan-class-path=out/test-classes
```

## CI

GitHub Actions (`.github/workflows/build.yml`) compiles the library and runs JUnit tests.
