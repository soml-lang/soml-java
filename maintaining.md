# Maintaining

## Test

```sh
mvn test
```

`FloatVectorsTest` reads the float vectors of the C implementation at `../soml-c`, and is skipped without it.

`mvn verify` also checks the Javadoc. CI runs `mvn install` on Java 25 and 27, runs the saved fuzz inputs on Java 25, and fuzzes each target for 2 minutes.

The build runs [NullAway](https://github.com/uber/NullAway) on the library through Error Prone, which needs the exports in `.mvn/jvm.config`. Everything is not null unless it is `@Nullable`, as `@NullMarked` on the module says.

## Fuzz

The fuzz tests are a separate project in `fuzz/`, because [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) supports JUnit 5 and the tests here use JUnit 6. Use Java 25, because Jazzer's bundled ASM does not support Java 27 class files.

```sh
mvn install -DskipTests
mvn -f fuzz/pom.xml test
JAZZER_FUZZ=1 mvn -f fuzz/pom.xml test -Dtest='SomlFuzzTest#parse'
```

The first run checks the inputs in `fuzz/src/test/resources` as regression tests. The second fuzzes one target for 2 minutes, because Jazzer fuzzes one target per run: `parse`, `format`, `decode`, or `edit`. A failing input is saved in `fuzz/src/test/resources`. Fix the bug, and keep the input as a regression test.

## Update the conformance suite

Check out the [`soml`](https://github.com/soml-lang/soml) repository next to this one, then:

```sh
../soml/sync-conformance.sh src/test/resources/conformance
mvn test
```

## Compare with the JavaScript reference

Check out [`soml-javascript`](https://github.com/soml-lang/soml-javascript) next to this repository. It needs Node.js 26 or later.

```sh
mvn test-compile
node scripts/differential.js 50000 1
```

The arguments are the number of documents and a seed. The script generates and mutates documents, and checks that both implementations accept the same ones, write the same canonical form, and report each error with the same position, reason, and code frame.

To compare with a commit of the reference instead of its working tree, export it and point `SOML_REFERENCE` at it:

```sh
mkdir /tmp/reference
git -C ../soml-javascript archive <commit> source package.json | tar -x -C /tmp/reference
SOML_REFERENCE=/tmp/reference/source/index.ts node scripts/differential.js 50000 1
```

The parser's diagnostics are a port of the reference's, so a new diagnostic in the reference shows as a different reason until it is ported to `Parser.java`.

## Benchmark

```sh
mvn install -DskipTests
mvn -f bench/pom.xml package
java -jar bench/target/benchmarks.jar
```

Run it on a quiet machine, and check that the error of each score is small. Add `-prof gc` for the bytes allocated per operation, which other load does not change.

`ValueBenchmark` isolates numeric output, canonical key order, immutable collections, block-string formatting, and edits, at sizes 8, 128, and 1024. When you change those paths, run `java -jar bench/target/benchmarks.jar ValueBenchmark -prof gc`.

The readme table is thread CPU time, which other load changes much less than wall time. Run `java -cp bench/target/benchmarks.jar sh.soml.bench.CpuTime ReadBenchmark WriteBenchmark` 3 times, and take the median of each cell.

To update the fixtures, check out `soml` next to this repository and run `rsync --archive --delete ../soml/benchmark/ bench/fixtures/`. The fixtures are only in the benchmark module, so they are not in the published jar.

## Publish a new version

The [Release workflow](.github/workflows/release.yml) publishes to Maven Central. Maven Central has no trusted publishing, so the workflow uses a token and a signing key from the repository secrets.

For each version:

1. Run `sh scripts/set-version.sh 1.2.3`. It sets the version in all three projects and in the install snippets of the readme, updates the reproducible build timestamp, and commits.
2. Review the commit with `git show`, then push: `git push`
3. Run the Release workflow on `main` from the Actions tab, or with `gh workflow run release.yml`. It tests, signs, uploads, [publishes automatically](https://central.sonatype.org/publish/publish-portal-maven/#automatic-publishing), waits until Maven Central confirms publication, and pushes the `v1.2.3` tag.
4. Create a release on GitHub.

If the workflow fails or times out, check the [deployment](https://central.sonatype.com/publishing/deployments) before you run it again. A published version cannot be overwritten.

## Future plans

- **Value classes** ([JEP 401](https://openjdk.org/jeps/401)): when the feature is final, make the scalar values (`SomlInt`, `SomlFloat`, and the others) value classes, so that they need no allocation. It is a preview feature in JDK 27.
- **Vector API** ([JEP 537](https://openjdk.org/jeps/537)): when the API is no longer an incubator module, scan plain text with SIMD instead of the SWAR scan in `Parser.skipPlain`.
- **AOT cache** ([JEP 483](https://openjdk.org/jeps/483)): the application makes the cache, not the library, but the readme can show how to use it for a faster first parse.
