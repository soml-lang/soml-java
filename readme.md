# SOML for Java

> Read and write [SOML](https://soml.sh), a config format for humans, in Java

> [!NOTE]
> The format is a draft.

## Highlights

- Strict: every rule in the spec is checked, such as duplicate keys, the int64 range, date, instant, and duration ranges, raw control characters, and the nesting limit
- Errors have a line, a column, and a code frame, with the same position and message as the JavaScript reference implementation
- Exact: an int is a `long` and a float is a `double`, so `3` and `3.0` keep their types, dates are `java.time.LocalDate`, instants keep their nanoseconds, and durations are `java.time.Duration`
- Records, classes, enums, sealed interfaces, collections, maps, `Optional`, and `java.time` types, with no annotations needed
- Writes members in the order of your fields, or in canonical form, where the same value always gives the same bytes
- A sealed value tree for documents of unknown shape, made for `switch` with record patterns
- A formatter that changes layout and nothing else, by the normative rules of the spec
- Change one value in a file and keep its comments, order, and spelling
- Passes the language-neutral [conformance suite](https://github.com/soml-lang/soml/tree/main/conformance) of the spec, including its formatter and editing cases
- Null-marked with [JSpecify](https://jspecify.dev), its only dependency

## Install

[`sh.soml:soml`](https://central.sonatype.com/artifact/sh.soml/soml) on Maven Central. Requires Java 25 or later.

```xml
<dependency>
	<groupId>sh.soml</groupId>
	<artifactId>soml</artifactId>
	<version>0.0.1</version>
</dependency>
```

```kotlin
implementation("sh.soml:soml:0.0.1")
```

## Usage

### Read

```java
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import sh.soml.KeyNaming;
import sh.soml.SomlMapper;

record Config(String name, int replicas, Duration timeout, LocalDate launchDate, Instant deployedAt, List<String> labels, Postgres postgres) {}

record Postgres(String host, int port) {}

var mapper = SomlMapper.builder()
	.keyNaming(KeyNaming.KEBAB_CASE)
	.build();

var config = mapper.decode("""
	/* The edge service. */
	name: 'api-gateway'
	replicas: 3
	timeout: 1m30s # Including retries.
	launch-date: 2027-09-19
	deployed-at: 2026-09-19T14:00:00Z
	labels: ['prod', 'eu-west']
	postgres: {host: 'db.internal', port: 5432}
	""", Config.class);

config.postgres().port();
//=> 5432

config.timeout();
//=> PT1M30S
```

Indentation never matters in SOML, so a document can be indented like the code around it.

`Soml.decode()` does the same with the default options, where a key is the Java name, as in `deployedAt`. `KeyNaming.KEBAB_CASE` gives the usual SOML style, `deployed-at`. `decode()` also reads a `Path`, UTF-8 `byte[]` or `InputStream`, or a `Reader`. A stream is read to its end and not closed.

A generic type, such as a document that is a list, needs a `TypeReference`, as in Jackson:

```java
List<Postgres> databases = Soml.decode("[{host: 'a', port: 1}]", new TypeReference<List<Postgres>>() {});
```

Use a direct `TypeReference` subclass with concrete type arguments. Unresolved variables, including `List<T>` inside a generic method, are rejected. A wildcard uses its lower bound when present, otherwise its upper bound.

`@SomlName` sets a key or the string of an enum constant, and `@SomlIgnore` leaves out a field of a class:

```java
record Server(@SomlName("max-connections") int maxConnections) {}

enum Level {
	@SomlName("debug") DEBUG,
	@SomlName("info") INFO
}
```

### Write

```java
record Server(int port, List<String> hosts) {}

Soml.encode(new Server(8080, List.of("a", "b")));
//=> "port: 8080\nhosts: [\n\t'a'\n\t'b'\n]\n"

Soml.encodeCanonical(new Server(8080, List.of("a", "b")));
//=> "hosts: [\n\t'a'\n\t'b'\n]\nport: 8080\n"
```

`encode()` writes one member or item per line, with tab indentation and no commas. Members keep the order of the value: a record's components and a class's fields in the order they are declared, and a map's entries in the order it iterates in.

`encodeCanonical()` writes canonical form, which is the same with members sorted by key, so the same value always gives the same bytes. Use it to hash, sign, or compare documents, and to write a `HashMap` or a `Map.of()`, which iterate in no particular order.

Write a file with `Files.writeString(Path.of("config.soml"), Soml.encode(config))`, and a stream with `Soml.encode(config, output)`, where `output` is an `OutputStream`, written as UTF-8, or a `Writer`. The stream is not flushed or closed.

### Values of unknown shape

```java
import sh.soml.*;

var document = (SomlObject) Soml.parse("port: 8080\nhosts: ['a', 'b']");

if (document.get("port") instanceof SomlInt(var port)) {
	System.out.println(port);
	//=> 8080
}

var hostCount = switch (document.get("hosts")) {
	case SomlArray(var items) -> items.size();
	case SomlString _ -> 1;
	case null, default -> 0;
};
//=> 2
```

`Soml.parse()` returns a `SomlValue`, a sealed interface with one record per SOML type: `SomlString`, `SomlInt`, `SomlFloat`, `SomlBool`, `SomlNull`, `SomlDate`, `SomlInstant`, `SomlDuration`, `SomlArray`, and `SomlObject`. A document is always a `SomlObject` or a `SomlArray`.

Equality follows the spec: an int and a float are never equal, so `3` and `3.0` differ, `-0.0` is `0.0`, the order of an object's members does not matter, dates compare their year, month, and day, and two instants are equal when they are the same point in time. `toString()` gives the canonical spelling, such as `'text'`, `1.5`, or `1h30m`. `Soml.toValue()` and `Soml.fromValue()` convert between a tree and your types, and `Soml.encode()` writes a tree as a document.

A pattern with a default, such as `instanceof SomlInt(var port) ? port : 8080`, silently gives the default when the value has another type, as in `port: '8080'`. To read known members with their types, decode to a type that has only those members. Other members are ignored, field initializers are defaults, and a value that does not fit is an error with its path and line:

```java
class Server {
	String host = "localhost";
	int port = 8080;
}

class Settings {
	Server server = new Server();
}

var settings = Soml.decode("server: {port: 9}", Settings.class);
settings.server.host;
//=> "localhost"

Soml.decode("server: {port: '9'}", Settings.class);
//=> SomlException: "server.port: Expected an int, but found the string '9' at line 1, column 16 …"
```

### Change a document and keep its comments

```java
var document = SomlDocument.parse("""
	# The edge service.
	port: 0x1F90 # The default.
	server: {host: 'a'}
	""");

document.set(List.of("port"), 8080);
document.set(List.of("server", "port"), 443);
document.set(List.of("replicas"), 3);
document.remove(List.of("server", "host"));

document.toString();
//=> "# The edge service.\nport: 8080 # The default.\nserver: {port: 443}\nreplicas: 3\n"
```

An unchanged document prints the exact text it was parsed from. A changed one keeps the comments, blank lines, indentation, member order, and the spelling of every value outside the change. A path is a list of keys and array indexes. Changes follow the editing rules of the spec, as in the JavaScript reference, so a change to a formatted document leaves it formatted:

- A new value is written as `Soml.encode()` writes it, and on one line inside a container that is on one line, so `{host: 'a'}` becomes `{host: 'a', port: 443}`.
- A new member goes after the last member of its object, and missing objects on the path are created. A new item goes at the end of its array, at the index that is its length.
- A removed member or item takes its comma and the comments it owns, such as one after it on its line. A comment on a line of its own stays.
- Removing a path that does not exist is not an error, and `remove()` returns whether it removed something. A path that does not fit the document, such as a key where an array is, is an error.

`get()` reads the value at a path, and `value()` gives the whole document as a `SomlValue`. To save a document, write its text, as in `Files.writeString(file, document.toString())`.

### Format

```java
Soml.format("pool: {min: 2,   max: 16,} # Connections");
//=> "pool: {min: 2, max: 16} # Connections\n"

Soml.format("pool: {\nmin: 2, max: 16}");
//=> "pool: {\n\tmin: 2\n\tmax: 16\n}\n"
```

The formatter changes layout and nothing else: one tab of indentation per level, every member and item on its own line, no commas, at most one blank line in a row, and no trailing spaces or tabs. An object or an array whose brackets are on one line stays on one line, with a comma and a space between its members or items. To give it one member or item per line, put a line break anywhere inside it. Comments stay where they are, and so do member order, block strings, and the spelling of every value, so `0o644` stays `0o644`. Formatting a formatted document gives the same text. The formatter rules of the spec are normative, so every conforming formatter, including this one, gives the same text.

### Errors

```java
record Server(String host, int port) {}

try {
	Soml.decode("host: 'a'\nport: 'eighty'", Server.class);
} catch (SomlException error) {
	error.getMessage();
	//=> "port: Expected an int, but found the string 'eighty' at line 2, column 7\n\n  1 | host: 'a'\n> 2 | port: 'eighty'\n    |       ^"

	error.reason();
	//=> "Expected an int, but found the string 'eighty'"

	error.path();
	//=> ["port"]
}
```

Every method throws the unchecked `SomlException` for a document that is not valid or does not fit, and for a value that cannot be written. The methods that read a `Path` also throw `IOException`. A `SomlException` has the `reason()`, the `line()` and `column()` (in Unicode scalar values), the `path()` of keys and indexes to the value, and a `codeFrame()` for a terminal. An encoding error has a path and no position. A syntax error gives what was most likely meant, such as quoting a string, writing a duration without a space, or nesting with braces rather than a `.` in a key.

When a valid document does not fit your type, decoding continues past the first value that does not fit, and the exception reports all of them, up to 10. `reason()`, `path()`, `line()`, and `column()` describe the first error, and `errors()` gives each one as its own exception. A record whose members do not fit is not created, so its constructor's validation runs once they are fixed. A syntax error is always the only one, because the rest of a document that is not valid cannot be read reliably.

## Types

| SOML | Java | Notes |
|---|---|---|
| string | `String`, `char`, an enum, `UUID`, `URI`, `Path`, `Locale`, `ZoneId`, `ZoneOffset` | An enum constant is its name, or the name in `@SomlName`. A `Locale` is a language tag, such as `'en-US'`. |
| int | `long`, `int`, `short`, `byte`, `BigInteger` | A value that does not fit the type is an error. An int is 64-bit, also in a `BigInteger`. A `double` or a `float` takes an int too, when it is exact. |
| float | `double`, `float` | NaN is an error. A float never goes into an integer type. |
| bool | `boolean` | |
| null | `null`, an empty `Optional` | A primitive refuses it. |
| date | `LocalDate` | Proleptic Gregorian, years 0001 to 9999, with no timezone or time of day. A quoted date stays a string. |
| instant | `Instant`, `OffsetDateTime`, `ZonedDateTime` | Read in UTC, because the offset is not part of the value. |
| duration | `Duration` | |
| array | `List`, `Set`, `SortedSet`, `EnumSet`, `Queue`, `Deque`, `Collection`, `Iterable`, a `Collection` class with a constructor without parameters, an array | A list, a set, and a map are unmodifiable when you ask for the interface, and the class you name otherwise. A set refuses an array with the same item twice. |
| object | a record, a class, `Map`, `SortedMap`, `EnumMap` | A map key is a `String`, a `char`, an enum, an integer type (`404: 'x'`), or a `UUID`. |
| any value | `SomlValue` or one of its records, `Object` | An `Object` gets a `String`, `Long`, `Double`, `Boolean`, `null`, `LocalDate`, `Instant`, `Duration`, `List`, or `Map`. |

`Optional`, `OptionalInt`, `OptionalLong`, and `OptionalDouble` wrap any of these.

Integer map keys use decimal notation without leading zeros or a `+` sign. `BigInteger` keys have no 64-bit limit because keys are strings. Distinct keys that encode to the same string, or decode to the same Java key, are errors.

### Sealed types

A sealed interface or abstract class is an object whose `type` member names the subtype to create:

```java
sealed interface Check permits Http, Command {}

record Http(String url) implements Check {}

record Command(List<String> arguments) implements Check {}

record Service(String name, List<Check> checks) {}

Soml.decode("""
	name: 'api'
	checks: [
		{type: 'Http', url: 'https://example.com/health'}
		{type: 'Command', arguments: ['pg_isready']}
	]
	""", Service.class);
```

- The name is the simple name of the subtype, or the name in `@SomlName` on it, as in `@SomlName("http") record Http(…)`.
- A record or class whose direct supertype is sealed is always written with its `type` member, also on its own, so the document says what it is. `encode()` writes it first, and canonical form sorts it with the other keys. Decoded as itself, it does not need a `type` member, but if it has one, it must name it.
- The subtypes are records and ordinary final classes, also through sealed interfaces between them. Map and collection subtypes are not supported. Wrap the container in a record, or put an adapter on the sealed type. Only permitted subtypes can be created, so a document cannot choose any other class.
- A subtype cannot have a member with the key `type`. Give it another key with `@SomlName`.
- A generic sealed type, such as `Result<T>`, and an enum that implements a sealed interface can be encoded but not decoded. A sealed class that is not abstract is decoded as itself.
- `KeyNaming` changes keys, not the names of subtypes or the strings of enum constants. Use `@SomlName` for those.

### Records and classes

- A record is created with its canonical constructor, so its validation runs. A member that the document leaves out is an empty `Optional` for an `Optional` component, `null` for a component annotated with any annotation named `Nullable`, such as [JSpecify's](https://jspecify.dev), and otherwise an error.
- A class needs a constructor without parameters. An inner class (not `static`, or declared in an instance method) has none, because its constructor needs the outer object. Make it `static`, declare it in a static method, or use a record. A field that the document leaves out keeps its initial value, so field initializers are defaults. Static and `transient` fields, and fields annotated with `@SomlIgnore`, are left out.
- A key that no component or field has is ignored. Use `SomlMapper.builder().rejectUnknownKeys(true)` to make it an error, which catches a typo such as `prot: 80`.
- When encoding, a member with an empty `Optional` is left out, and a member that is `null` is written as `null`.
- An explicit `null` is accepted for any component or field that is not a primitive, also without `@Nullable`. Use `Optional` to tell a value from its absence.
- `@SomlName("key")` sets the key of a component, a field, the string of an enum constant, or the name of a subtype of a sealed type.

A record component has no field initializer. To give a member a default, make the component `@Nullable`, so that a member that the document leaves out is `null`, and replace `null` in the compact constructor. This also replaces an explicit `null`, so `port()` never returns `null`, and `encode()` writes `port: 8080`:

```java
record Server(String host, @Nullable Integer port) {
	Server {
		port = port == null ? 8080 : port;
	}
}

Soml.decode("host: 'example.com'", Server.class);
//=> Server[host=example.com, port=8080]
```

To validate a value, throw an exception, such as `IllegalArgumentException`, in the compact constructor. The decoder reports it as a `SomlException` with the position of the record and the exception as its cause. To point to one value, give it its own type, such as `record Port(int number)`, or a `SomlAdapter`. For annotations such as `@Min` and `@NotBlank`, run a Jakarta Bean Validation `Validator` on the decoded value. Its violations have Java names in their paths and no position.

### Custom types

```java
record Color(int red, int green, int blue) {
	static final SomlAdapter<Color> SOML = SomlAdapter.of(
		color -> new SomlString("#%02X%02X%02X".formatted(color.red(), color.green(), color.blue())),
		value -> {
			if (!(value instanceof SomlString(var hex)) || !hex.matches("#[0-9A-F]{6}")) {
				throw new SomlException("Expected a color, such as '#FF8800'");
			}

			return new Color(Integer.parseInt(hex, 1, 3, 16), Integer.parseInt(hex, 3, 5, 16), Integer.parseInt(hex, 5, 7, 16));
		}
	);
}

record Theme(Color accent) {}

Soml.decode("accent: '#FF8800'", Theme.class);
//=> Theme[accent=Color[red=255, green=136, blue=0]]
```

A static `SomlAdapter<Color>` field in `Color`, with any name, is its adapter, and `Soml` and every mapper use it. For a type that you cannot change, register the adapter with a mapper, as in `SomlMapper.builder().adapter(Money.class, adapter)`. An adapter of the mapper wins over the type's own.

The decoder adds the path and the position to a `SomlException` that an adapter throws. An adapter for an interface or abstract class also applies to the classes that implement it, and the nearest adapter wins. A primitive and its wrapper share one registration, so the last adapter registered for `int.class` or `Integer.class` is used for both.

- An adapter for a subtype of a sealed type writes the value without the `type` member, so a field of the sealed type cannot read it back. Put the adapter on the sealed type.
- An adapter must not call `Soml` or the mapper for a type that it covers, because that recurses without end.

## File format

Documents follow the [SOML specification](https://soml.sh).

- File extension: `.soml`
- Encoding: UTF-8 without a byte order mark, with LF line endings
- Media type: `application/soml`
- Nesting is limited to 100 levels, as the spec requires. Every object and array counts, including the document's own.

## Performance

The CPU time per document on an Apple M4 Pro with JDK 27, against the most used JSON and TOML libraries for Java. Each cell is the median of three runs. The [fixtures](https://github.com/soml-lang/soml/tree/main/benchmark) are shared by every SOML implementation and have the same data in each format. *Read* parses a document into each library's tree of generic values, and *write* writes that tree back as a document for people, with JSON pretty-printed.

| | SOML | Jackson 3.2 (JSON) | Gson 2.14 | Jackson 3.2 (TOML) | tomlj 2.2 |
|---|---|---|---|---|---|
| Read service | 5.6 µs | 6.3 µs | 7.9 µs | 22.6 µs | 144 µs |
| Read platform | 49.2 µs | 39.3 µs | 50.0 µs | 226 µs | 1.30 ms |
| Read earthquakes | 1.83 ms | 1.58 ms | 2.40 ms | 5.44 ms | 38.0 ms |
| Write service | 3.7 µs | 3.7 µs | 8.7 µs | 5.9 µs | 10.6 µs |
| Write platform | 23.5 µs | 25.1 µs | 59.0 µs | 42.9 µs | 105 µs |
| Write earthquakes | 1.18 ms | 1.29 ms | 2.63 ms | 1.23 ms | 3.38 ms |

SOML reads and writes at about the speed of the JSON libraries, while it also checks every rule of the spec, such as duplicate keys, ranges, and the nesting limit. The platform fixture is 35 KB as SOML and TOML, and 21 KB as JSON, which has no comments.

```sh
mvn install -DskipTests
mvn -f bench/pom.xml package
java -cp bench/target/benchmarks.jar sh.soml.bench.CpuTime ReadBenchmark WriteBenchmark
```

## Limitations

- In a named module, open your package to `sh.soml`, as in `opens com.example.config to sh.soml;`.
- In a GraalVM native image, decoding and encoding your types needs reflection configuration (`parse()` and `format()` do not). Generate it on the JVM with `-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image` and input that uses each type. To write it yourself, register each type, member type, sealed subtype, and superclass in `META-INF/native-image/reachability-metadata.json`: `allDeclaredConstructors` and `allDeclaredMethods` for a record, `allDeclaredConstructors` and `allDeclaredFields` for a class, and `allPublicFields` for an enum. A type with its own `SomlAdapter` field also needs `allDeclaredFields`.
- An interface or abstract class that is not sealed needs a `SomlAdapter` to be decoded, because the document does not say which class to create.
- `encode()` writes a `HashSet`, `HashMap`, or `Map.of()` in iteration order, which can change between runs. Use a `LinkedHashSet` or `LinkedHashMap`. `encodeCanonical()` sorts map members, but not set items.
- `toString()`, `equals()`, and `hashCode()` overflow the stack on a `SomlValue` tree built in code thousands of levels deep. A parsed tree is at most 100 levels deep.
- The decoder creates records and calls adapters while it parses, so they can run for a document that it then refuses, and run again to report all values that do not fit. Keep them free of side effects.
- A `Set` of a type that is not `Comparable`, such as a record, takes quadratic time when hash codes collide, as in `HashSet`. For untrusted documents, use a `List`, or a `Set` of strings, numbers, or `UUID`s.
- For untrusted documents, limit memory use with `SomlMapper.builder().maxDocumentLength(1_000_000)` (bytes for UTF-8 input, characters for a `String` or `Reader`). `Soml` and `SomlDocument` have no limit.
