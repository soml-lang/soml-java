package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/*
The language-neutral conformance suite of the spec, in `src/test/resources/conformance`, which is a copy made by `../soml/sync-conformance.sh`.
*/
class ConformanceTest {
	private static final Path ROOT = Path.of("src/test/resources/conformance");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@TestFactory
	Stream<DynamicTest> valid() throws IOException {
		return cases(ROOT.resolve("valid"), ".json").map(expectedPath -> {
			var name = caseName(ROOT.resolve("valid"), expectedPath, ".json");
			return DynamicTest.dynamicTest(name, () -> {
				var value = Soml.parse(Files.readAllBytes(fixturePath(expectedPath, ".soml")));
				assertEquals(JSON.readTree(expectedPath.toFile()), toTagged(value), "value");

				var canonical = Files.readString(fixturePath(expectedPath, ".canonical.soml"), StandardCharsets.UTF_8);
				assertEquals(canonical, Soml.encodeCanonical(value), "canonical form");
				assertEquals(canonical, Soml.encodeCanonical(Soml.parse(canonical)), "canonical form of the canonical form");
			});
		});
	}

	/*
	The encoder writes Java objects directly, without a value tree, and must write the same text as the tree. Each valid case is decoded to plain maps and lists, and written both ways.
	*/
	@TestFactory
	Stream<DynamicTest> encodesJavaObjectsAsTheTree() throws IOException {
		return cases(ROOT.resolve("valid"), ".json").map(expectedPath -> {
			var name = caseName(ROOT.resolve("valid"), expectedPath, ".json");
			return DynamicTest.dynamicTest(name, () -> {
				var untyped = Soml.decode(Files.readAllBytes(fixturePath(expectedPath, ".soml")), Object.class);
				var tree = Soml.toValue(untyped);
				assertEquals(Soml.encode(tree), Soml.encode(untyped), "document order");
				assertEquals(Soml.encodeCanonical(tree), Soml.encodeCanonical(untyped), "canonical form");
			});
		});
	}

	/*
	The decoder decodes while it parses, without a value tree, and must give what decoding the tree gives.
	*/
	@TestFactory
	Stream<DynamicTest> decodesWhileParsingAsFromTheTree() throws IOException {
		return cases(ROOT.resolve("valid"), ".json").map(expectedPath -> {
			var name = caseName(ROOT.resolve("valid"), expectedPath, ".json");
			return DynamicTest.dynamicTest(name, () -> {
				var bytes = Files.readAllBytes(fixturePath(expectedPath, ".soml"));
				assertEquals(Soml.fromValue(Soml.parse(bytes), Object.class), Soml.decode(bytes, Object.class));
				assertEquals(Soml.parse(bytes), Soml.decode(bytes, SomlValue.class));
			});
		});
	}

	@TestFactory
	Stream<DynamicTest> formatted() throws IOException {
		return cases(ROOT.resolve("valid"), ".json").map(expectedPath -> {
			var name = caseName(ROOT.resolve("valid"), expectedPath, ".json");
			return DynamicTest.dynamicTest(name, () -> {
				var source = Files.readString(fixturePath(expectedPath, ".soml"), StandardCharsets.UTF_8);
				var formatted = Files.readString(fixturePath(expectedPath, ".formatted.soml"), StandardCharsets.UTF_8);
				assertEquals(formatted, Soml.format(source), "formatted");
				assertEquals(formatted, Soml.format(formatted), "formatted again");
				assertEquals(Soml.parse(source), Soml.parse(formatted), "formatting preserves the value");
			});
		});
	}

	@TestFactory
	Stream<DynamicTest> invalid() throws IOException {
		return invalidCases(Soml::parse);
	}

	/*
	Invalid documents must fail in the streaming decoder with the specification's exact diagnostics, independently of the tree parser. These cases exercise decoder events before a syntax error.
	*/
	@TestFactory
	Stream<DynamicTest> invalidStreamingDecode() throws IOException {
		return invalidCases(bytes -> Soml.decode(bytes, Object.class));
	}

	private static Stream<DynamicTest> invalidCases(Function<byte[], ?> read) throws IOException {
		var reasons = JSON.readTree(ROOT.resolve("invalid-reasons.json").toFile());

		return cases(ROOT.resolve("invalid"), ".soml").map(path -> {
			var name = caseName(ROOT.resolve("invalid"), path, ".soml");
			return DynamicTest.dynamicTest(name, () -> {
				var bytes = Files.readAllBytes(path);
				var error = assertThrows(SomlException.class, () -> read.apply(bytes));
				var expected = reasons.get(name);
				assertEquals(expected.get("reason").asString(), error.reason(), "reason");
				assertEquals(expected.get("line").asInt() + ":" + expected.get("column").asInt(), error.line() + ":" + error.column(), "position");
				assertEquals(java.util.List.of(), error.path(), "syntax errors have no binding path");
				assertEquals(java.util.List.of(error), error.errors(), "syntax errors are never aggregated");
			});
		});
	}

	@TestFactory
	Stream<DynamicTest> edit() throws IOException {
		return cases(ROOT.resolve("edit"), ".json").map(path -> {
			var name = caseName(ROOT.resolve("edit"), path, ".json");
			return DynamicTest.dynamicTest(name, () -> {
				var testCase = JSON.readTree(path.toFile());
				var document = SomlDocument.parse(testCase.get("document").asString());
				var originalText = document.toString();
				var originalValue = document.value();
				var editPath = new ArrayList<Object>();

				for (var component : testCase.get("path")) {
					editPath.add(component.isString() ? component.asString() : (Object) component.asInt());
				}

				Runnable change = testCase.has("value") ? () -> document.set(editPath, fromTagged(testCase.get("value"))) : () -> {
					var existed = document.get(editPath) != null;
					assertEquals(existed, document.remove(editPath), "remove reports whether the value existed");
				};

				if (testCase.has("error")) {
					assertThrows(SomlException.class, change::run);
					assertEquals(originalText, document.toString(), "failed edits preserve the text");
					assertEquals(originalValue, document.value(), "failed edits preserve the tree");
					return;
				}

				change.run();
				assertEquals(testCase.get("expected").asString(), document.toString());
				assertEquals(Soml.parse(document.toString()), document.value(), "edited text and value agree");
			});
		});
	}

	/*
	A value from the suite's tagged JSON.
	*/
	static SomlValue fromTagged(JsonNode node) {
		if (node.isArray()) {
			var items = new ArrayList<SomlValue>();

			for (var item : node) {
				items.add(fromTagged(item));
			}

			return new SomlArray(items);
		}

		if (node.has("type") && node.has("value") && node.size() == 2 && node.get("type").isString()) {
			var value = node.get("value").asString();

			return switch (node.get("type").asString()) {
				case "string" -> new SomlString(value);
				case "int" -> new SomlInt(Long.parseLong(value));
				case "float" -> new SomlFloat(value.equals("infinity") ? Double.POSITIVE_INFINITY : value.equals("-infinity") ? Double.NEGATIVE_INFINITY : Double.parseDouble(value));
				case "bool" -> SomlBool.of(value.equals("true"));
				case "null" -> SomlNull.INSTANCE;
				case "date" -> new SomlDate(LocalDate.parse(value));
				case "instant" -> new SomlInstant(Instant.parse(value));
				case "duration" -> SomlDuration.ofNanos(Long.parseLong(value));
				default -> throw new IllegalArgumentException(value);
			};
		}

		var members = new java.util.LinkedHashMap<String, SomlValue>();

		for (var entry : node.properties()) {
			members.put(entry.getKey(), fromTagged(entry.getValue()));
		}

		return new SomlObject(members);
	}

	private static Stream<Path> cases(Path directory, String extension) throws IOException {
		return Files.walk(directory)
			.filter(path -> path.toString().endsWith(extension) && !path.toString().endsWith(".canonical.soml") && !path.toString().endsWith(".formatted.soml"))
			.sorted();
	}

	private static String caseName(Path directory, Path path, String extension) {
		var relative = directory.relativize(path).toString().replace('\\', '/');
		return relative.substring(0, relative.length() - extension.length());
	}

	/*
	A fixture alongside its tagged JSON expectation, with the requested suffix.
	*/
	private static Path fixturePath(Path expected, String suffix) {
		var filename = expected.getFileName().toString();
		return expected.resolveSibling(filename.substring(0, filename.length() - ".json".length()) + suffix);
	}

	/*
	A value as the suite's tagged JSON: a scalar as `{"type": …, "value": …}`, with a float's canonical spelling, a date's exact spelling, an instant's canonical UTC form, and a duration's length in nanoseconds.
	*/
	static JsonNode toTagged(SomlValue value) {
		var nodes = JsonNodeFactory.instance;

		return switch (value) {
			case SomlString(var string) -> tag("string", string);
			case SomlInt(var number) -> tag("int", Long.toString(number));
			case SomlFloat float_ -> tag("float", float_.toString());
			case SomlBool(var bool) -> tag("bool", Boolean.toString(bool));
			case SomlNull _ -> tag("null", "null");
			case SomlDate date -> tag("date", date.toString());
			case SomlInstant instant -> tag("instant", instant.toString());
			case SomlDuration duration -> tag("duration", Long.toString(duration.nanoseconds()));
			case SomlArray(var items) -> {
				var array = nodes.arrayNode();

				for (var item : items) {
					array.add(toTagged(item));
				}

				yield array;
			}
			case SomlObject(var members) -> {
				var object = nodes.objectNode();

				for (var entry : members.entrySet()) {
					object.set(entry.getKey(), toTagged(entry.getValue()));
				}

				yield object;
			}
		};
	}

	private static JsonNode tag(String type, String value) {
		var node = JsonNodeFactory.instance.objectNode();
		node.put("type", type);
		node.put("value", value);
		return node;
	}
}
