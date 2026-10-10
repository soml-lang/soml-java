package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BindingTest {
	@Test
	void decodedInterfaceContainersCannotBeChangedThroughTheirViews() {
		var decoded = Soml.decode("a: [1, null]\nb: [2]", new TypeReference<Map<String, List<Integer>>>() {});
		assertThrows(UnsupportedOperationException.class, () -> decoded.put("c", List.of(3)));
		assertThrows(UnsupportedOperationException.class, () -> decoded.entrySet().iterator().next().setValue(List.of(3)));
		assertThrows(UnsupportedOperationException.class, () -> decoded.values().clear());
		assertThrows(UnsupportedOperationException.class, () -> decoded.keySet().remove("a"));
		assertThrows(UnsupportedOperationException.class, () -> decoded.get("a").set(0, 3));
		assertThrows(UnsupportedOperationException.class, () -> decoded.get("a").subList(0, 1).clear());

		var sorted = Soml.decode("[3, 1, 2]", new TypeReference<java.util.NavigableSet<Integer>>() {});
		assertThrows(UnsupportedOperationException.class, () -> sorted.descendingSet().pollFirst());
		assertThrows(UnsupportedOperationException.class, () -> sorted.headSet(3, true).clear());
		assertEquals(List.of(1, 2, 3), new ArrayList<>(sorted));
		assertEquals(Soml.parse("a: [1, null]\nb: [2]"), Soml.toValue(decoded));
	}

	@Test
	void primitiveArrayErrorsKeepEveryInvalidIndex() {
		var text = "[1, 'bad', 2147483648, null, 2]";
		var streamError = assertThrows(SomlException.class, () -> Soml.decode(text, int[].class));
		var treeError = assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), int[].class));
		var paths = List.of(List.of(1), List.of(2), List.of(3));
		assertEquals(paths, streamError.errors().stream().map(SomlException::path).toList());
		assertEquals(paths, treeError.errors().stream().map(SomlException::path).toList());
		assertEquals(streamError.errors().stream().map(SomlException::reason).toList(), treeError.errors().stream().map(SomlException::reason).toList());
		assertEquals(List.of(5, 12, 24), streamError.errors().stream().map(SomlException::column).toList());
		assertEquals(Soml.parse("[1, 2]"), Soml.toValue(new int[] {1, 2}));
		assertEquals(SomlArray.of(), Soml.toValue(new int[0]));
	}

	record ParsesInConstructor(int value) {
		ParsesInConstructor {
			var nested = Soml.decode("numbers: [3, 4]", new TypeReference<Map<String, List<Integer>>>() {});
			assertEquals(List.of(3, 4), nested.get("numbers"));
			var tree = (SomlObject) Soml.parse("nested: {value: 5}");
			assertEquals(new SomlInt(5), ((SomlObject) tree.get("nested")).get("value"));
		}
	}

	@Test
	void nestedParsingFromConstructorsKeepsTheOuterParseIntact() {
		var text = "[{value: 1}, {value: 2}, {value: 3}]";
		var type = new TypeReference<List<ParsesInConstructor>>() {};
		var expected = List.of(new ParsesInConstructor(1), new ParsesInConstructor(2), new ParsesInConstructor(3));
		assertEquals(expected, Soml.decode(text, type));
		assertEquals(expected, Soml.fromValue(Soml.parse(text), type));
		assertEquals(Soml.parse(text), Soml.toValue(Soml.decode(text, type)));
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE_USE, ElementType.RECORD_COMPONENT, ElementType.FIELD})
	@interface Nullable {}

	enum Level {
		@SomlName("debug")
		DEBUG,
		@SomlName("info")
		INFO,
		WARN
	}

	record Postgres(String host, int port) {}

	record Config(
		String name,
		int replicas,
		double ratio,
		boolean drained,
		Duration timeout,
		Instant deployedAt,
		List<String> labels,
		Map<String, Integer> limits,
		Postgres postgres,
		Level level,
		Optional<String> owner,
		@Nullable String comment
	) {}

	private static final String CONFIG = """
		name: 'api-gateway'
		replicas: 3
		ratio: 0.75
		drained: false
		timeout: 1m30s
		deployedAt: 2026-09-19T14:00:00Z
		labels: ['prod', 'eu-west']
		limits: {cpu: 2, memory: 512}
		postgres: {host: 'db.internal', port: 5432}
		level: 'debug'
		""";

	@Test
	void decodesRecord() {
		var config = Soml.decode(CONFIG, Config.class);
		assertEquals(new Config("api-gateway", 3, 0.75, false, Duration.ofSeconds(90), Instant.parse("2026-09-19T14:00:00Z"), List.of("prod", "eu-west"), Map.of("cpu", 2, "memory", 512), new Postgres("db.internal", 5432), Level.DEBUG, Optional.empty(), null), config);
	}

	@Test
	void encodesRecordInDeclarationOrder() {
		var config = Soml.decode(CONFIG + "owner: 'sindre'\n", Config.class);
		assertEquals("""
			name: 'api-gateway'
			replicas: 3
			ratio: 0.75
			drained: false
			timeout: 1m30s
			deployedAt: 2026-09-19T14:00:00Z
			labels: [
				'prod'
				'eu-west'
			]
			limits: {
				cpu: 2
				memory: 512
			}
			postgres: {
				host: 'db.internal'
				port: 5432
			}
			level: 'debug'
			owner: 'sindre'
			comment: null
			""", Soml.encode(config));
	}

	@Test
	void encodesCanonicalForm() {
		assertEquals("a: 1\nb: [\n\t2\n]\n", Soml.encodeCanonical(new LinkedHashMap<>(Map.of("b", List.of(2), "a", 1))));
		assertEquals("{}\n", Soml.encode(Map.of()));
		assertEquals("[]\n", Soml.encode(List.of()));
	}

	class InnerSettings {
		int port = 8080;
	}

	@Test
	void explainsThatAnInnerClassCannotBeDecoded() {
		class LocalSettings {
			int port = 8080;
		}

		assertEquals("Cannot decode to InnerSettings, because it is an inner class, whose constructor needs the outer object. Make it static, declare it in a static method, or make it a record", assertThrows(SomlException.class, () -> Soml.decode("port: 1", InnerSettings.class)).reason());
		assertEquals("Cannot decode to LocalSettings, because it is an inner class, whose constructor needs the outer object. Make it static, declare it in a static method, or make it a record", assertThrows(SomlException.class, () -> Soml.decode("port: 1", LocalSettings.class)).reason());
	}

	private static Class<?> localClassWithParameters() {
		class Sized {
			int size;

			Sized(int size) {
				this.size = size;
			}
		}

		return Sized.class;
	}

	@Test
	void explainsThatALocalClassInAStaticMethodNeedsAConstructor() {
		assertEquals("Cannot decode to Sized, because it has no constructor without parameters. Add one, or make it a record", assertThrows(SomlException.class, () -> Soml.decode("size: 1", localClassWithParameters())).reason());
	}

	record BadlyNamed(@SomlName("a\uD800b") int number) {}

	@Test
	void reportsAnInvalidNameOnce() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("items: [{number: 1}, {number: 2}, {number: 3}]", new TypeReference<Map<String, List<BadlyNamed>>>() {}));
		assertEquals(1, error.errors().size());
		assertEquals(List.of("items", 0), error.path());
	}

	@Test
	void reportsMissingMember() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("postgres: {host: 'db'}", WithPostgres.class));
		assertEquals("Missing the member port, which Postgres requires. Make it Optional or @Nullable if it may be left out", error.reason());
		assertEquals(List.of("postgres"), error.path());
		assertEquals(1, error.line());
		assertEquals(11, error.column());
	}

	record WithPostgres(Postgres postgres) {}

	record Server(String host, int port) {}

	record Servers(List<Server> servers) {}

	@Test
	void reportsMismatchWithPathAndPosition() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("""
			servers: [
				{host: 'a', port: 80}
				{host: 'b', port: 'eighty'}
			]
			""", Servers.class));
		assertEquals("Expected an int, but found the string 'eighty'", error.reason());
		assertEquals(List.of("servers", 1, "port"), error.path());
		assertEquals("servers[1].port", error.pathString());
		assertEquals(3, error.line());
		assertEquals(20, error.column());
		assertTrue(error.getMessage().startsWith("servers[1].port: Expected an int, but found the string 'eighty' at line 3, column 20\n\n"), error.getMessage());
	}

	@Test
	void ignoresUnknownKeysByDefault() {
		assertEquals(new Server("a", 80), Soml.decode("host: 'a'\nport: 80\nprot: 81", Server.class));
	}

	@Test
	void rejectsUnknownKeys() {
		var mapper = SomlMapper.builder().rejectUnknownKeys(true).build();
		var error = assertThrows(SomlException.class, () -> mapper.decode("host: 'a'\nport: 80\nprot: 81", Server.class));
		assertEquals("Unknown key prot. Server has no member for it, and its members are host, port", error.reason());
		assertEquals(3, error.line());
		assertEquals(1, error.column());
	}

	record Deployment(String serviceName, Instant deployedAt, @SomlName("max_retries") int maxRetries) {}

	@Test
	void convertsKeyNames() {
		var mapper = SomlMapper.builder().keyNaming(KeyNaming.KEBAB_CASE).build();
		var deployment = mapper.decode("service-name: 'api'\ndeployed-at: 2026-09-19T14:00:00Z\nmax_retries: 3", Deployment.class);
		assertEquals(new Deployment("api", Instant.parse("2026-09-19T14:00:00Z"), 3), deployment);
		assertEquals("service-name: 'api'\ndeployed-at: 2026-09-19T14:00:00Z\nmax_retries: 3\n", mapper.encode(deployment));
	}

	@Test
	void namesWords() {
		assertEquals("deployed-at", KeyNaming.KEBAB_CASE.apply("deployedAt"));
		assertEquals("parse-http-response", KeyNaming.KEBAB_CASE.apply("parseHTTPResponse"));
		assertEquals("http-url", KeyNaming.KEBAB_CASE.apply("httpURL"));
		assertEquals("ipv6-address", KeyNaming.KEBAB_CASE.apply("ipv6Address"));
		assertEquals("address2", KeyNaming.KEBAB_CASE.apply("address2"));
		assertEquals("max_body_size", KeyNaming.SNAKE_CASE.apply("maxBodySize"));
		assertEquals("name", KeyNaming.KEBAB_CASE.apply("name"));
	}

	static class Settings {
		String host = "localhost";
		int port = 8080;
		List<String> tags = List.of();
		transient String cache = "kept";
		@SomlIgnore
		String ignored = "kept";
	}

	@Test
	void decodesClassWithDefaults() {
		var settings = Soml.decode("port: 9000\ncache: 'x'\nignored: 'x'", Settings.class);
		assertEquals("localhost", settings.host);
		assertEquals(9000, settings.port);
		assertEquals(List.of(), settings.tags);
		assertEquals("kept", settings.cache);
		assertEquals("kept", settings.ignored);
		assertEquals("host: 'localhost'\nport: 9000\ntags: []\n", Soml.encode(settings));
	}

	record Box<T>(T value, List<T> values) {}

	@Test
	void decodesGenericRecord() {
		var box = Soml.decode("value: 1\nvalues: [2, 3]", new TypeReference<Box<Long>>() {});
		assertEquals(new Box<>(1L, List.of(2L, 3L)), box);

		var error = assertThrows(SomlException.class, () -> Soml.decode("value: 'x'\nvalues: []", new TypeReference<Box<Long>>() {}));
		assertEquals("Expected an int, but found the string 'x'", error.reason());
	}

	record Node(String name, List<Node> children) {}

	@Test
	void decodesRecursiveRecord() {
		var node = Soml.decode("name: 'a'\nchildren: [{name: 'b', children: []}]", Node.class);
		assertEquals(new Node("a", List.of(new Node("b", List.of()))), node);
	}

	@Test
	void decodesMapsWithIntegerKeys() {
		var responses = Soml.decode("200: 'ok'\n404: 'not found'", new TypeReference<Map<Integer, String>>() {});
		assertEquals(Map.of(200, "ok", 404, "not found"), responses);
		assertEquals("200: 'ok'\n404: 'not found'\n", Soml.encode(new TreeMap<>(responses)));

		var error = assertThrows(SomlException.class, () -> Soml.decode("007: 'bond'", new TypeReference<Map<Integer, String>>() {}));
		assertEquals("Expected a key with a decimal integer, such as 404, but found 007", error.reason());
		assertEquals(1, error.column());
	}

	@Test
	void decodesSortedMap() {
		var map = Soml.decode("b: 1\na: 2", new TypeReference<SortedMap<String, Integer>>() {});
		assertEquals(List.of("a", "b"), new ArrayList<>(map.keySet()));
	}

	@Test
	void rejectsDuplicateSetItems() {
		assertEquals(Set.of("a", "b"), Soml.decode("['a', 'b']", new TypeReference<Set<String>>() {}));

		var error = assertThrows(SomlException.class, () -> Soml.decode("['a', 'a']", new TypeReference<Set<String>>() {}));
		assertEquals("The array has this item twice, which a set cannot hold", error.reason());
		assertEquals(List.of(1), error.path());
		assertEquals(7, error.column());
	}

	record Numbers(double ratio, float small, long big, byte tiny, BigInteger huge, OptionalInt maybe) {}

	@Test
	void convertsNumbers() {
		var numbers = Soml.decode("ratio: 3\nsmall: 0.1\nbig: 9223372036854775807\ntiny: -128\nhuge: 1\nmaybe: 5", Numbers.class);
		assertEquals(new Numbers(3.0, 0.1f, Long.MAX_VALUE, (byte) -128, BigInteger.ONE, OptionalInt.of(5)), numbers);
		assertEquals("ratio: 3.0\nsmall: 0.1\nbig: 9223372036854775807\ntiny: -128\nhuge: 1\nmaybe: 5\n", Soml.encode(numbers));

		var floatAsInt = assertThrows(SomlException.class, () -> Soml.decode("replicas: 3.0", Replicas.class));
		assertEquals("Expected an int, but found the float 3.0", floatAsInt.reason());

		var outOfRange = assertThrows(SomlException.class, () -> Soml.decode("ratio: 1\nsmall: 1\nbig: 1\ntiny: 128\nhuge: 1", Numbers.class));
		assertEquals("The int 128 does not fit the Java type byte, which holds -128 to 127", outOfRange.reason());

		var inexact = assertThrows(SomlException.class, () -> Soml.decode("ratio: 9007199254740993\nsmall: 1\nbig: 1\ntiny: 1\nhuge: 1", Numbers.class));
		assertTrue(inexact.reason().startsWith("The int 9007199254740993 cannot be represented exactly by the Java type double"), inexact.reason());
	}

	record Replicas(int replicas) {}

	@Test
	void rejectsNullForPrimitive() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("replicas: null", Replicas.class));
		assertEquals("Expected an int, but found null", error.reason());
	}

	record Arrays_(int[] numbers, String[] names) {}

	@Test
	void decodesArrays() {
		var arrays = Soml.decode("numbers: [1, 2]\nnames: ['a']", Arrays_.class);
		assertArrayEquals(new int[] {1, 2}, arrays.numbers());
		assertArrayEquals(new String[] {"a"}, arrays.names());
		assertEquals("numbers: [\n\t1\n\t2\n]\nnames: [\n\t'a'\n]\n", Soml.encode(arrays));
	}

	@Test
	void decodesUntyped() {
		var value = Soml.decode("a: [1, 1.5, 'x', true, null, 1s, 2026-01-01T00:00:00Z]\nb: {c: 1}", Object.class);
		var expected = new LinkedHashMap<String, Object>();
		var items = new ArrayList<>();
		items.add(1L);
		items.add(1.5);
		items.add("x");
		items.add(true);
		items.add(null);
		items.add(Duration.ofSeconds(1));
		items.add(Instant.parse("2026-01-01T00:00:00Z"));
		expected.put("a", items);
		expected.put("b", Map.of("c", 1L));
		assertEquals(expected, value);
	}

	record Color(int red, int green, int blue) {}

	record Theme(Color accent) {}

	@Test
	void usesAdapters() {
		var mapper = SomlMapper.builder()
			.adapter(Color.class, new SomlAdapter<>() {
				@Override
				public SomlValue encode(Color color) {
					return new SomlString("#%02X%02X%02X".formatted(color.red(), color.green(), color.blue()));
				}

				@Override
				public Color decode(SomlValue value) {
					if (!(value instanceof SomlString(var hex)) || !hex.matches("#[0-9A-F]{6}")) {
						throw new SomlException("Expected a color, such as '#FF8800'");
					}

					return new Color(Integer.parseInt(hex, 1, 3, 16), Integer.parseInt(hex, 3, 5, 16), Integer.parseInt(hex, 5, 7, 16));
				}
			})
			.build();

		assertEquals(new Theme(new Color(255, 136, 0)), mapper.decode("accent: '#FF8800'", Theme.class));
		assertEquals("accent: '#FF8800'\n", mapper.encode(new Theme(new Color(255, 136, 0))));

		var error = assertThrows(SomlException.class, () -> mapper.decode("accent: 'red'", Theme.class));
		assertEquals("Expected a color, such as '#FF8800'", error.reason());
		assertEquals(List.of("accent"), error.path());
		assertEquals(9, error.column());
	}

	record Hex(int red, int green, int blue) {
		private static final SomlAdapter<Hex> SOML = SomlAdapter.of(
			color -> new SomlString("#%02X%02X%02X".formatted(color.red(), color.green(), color.blue())),
			value -> {
				if (!(value instanceof SomlString(var hex)) || !hex.matches("#[0-9A-F]{6}")) {
					throw new SomlException("Expected a color, such as '#FF8800'");
				}

				return new Hex(Integer.parseInt(hex, 1, 3, 16), Integer.parseInt(hex, 3, 5, 16), Integer.parseInt(hex, 5, 7, 16));
			}
		);

		// Not its adapter, because it is for another type.
		static final SomlAdapter<Color> COLOR = SomlAdapter.of(_ -> new SomlString("color"), _ -> new Color(0, 0, 0));
	}

	record Palette(Hex accent, List<Hex> others, Optional<Hex> border) {}

	@Test
	void usesTheAdapterOfAType() {
		var palette = new Palette(new Hex(255, 136, 0), List.of(new Hex(0, 0, 0)), Optional.empty());
		assertEquals(palette, Soml.decode("accent: '#FF8800'\nothers: ['#000000']", Palette.class));
		assertEquals("accent: '#FF8800'\nothers: [\n\t'#000000'\n]\n", Soml.encode(palette));
		assertEquals("accent: '#FF8800'\nothers: [\n\t'#000000'\n]\n", Soml.encodeCanonical(palette));
		assertEquals(new SomlString("#FF8800"), Soml.toValue(new Hex(255, 136, 0)));
		assertEquals(new Hex(255, 136, 0), Soml.fromValue(new SomlString("#FF8800"), Hex.class));

		var document = SomlDocument.parse("accent: '#000000' # The brand color.\n");
		document.set(List.of("accent"), new Hex(255, 136, 0));
		assertEquals("accent: '#FF8800' # The brand color.\n", document.toString());

		var error = assertThrows(SomlException.class, () -> Soml.decode("accent: '#FF8800'\nothers: ['red']", Palette.class));
		assertEquals("Expected a color, such as '#FF8800'", error.reason());
		assertEquals(List.of("others", 0), error.path());
		assertEquals(2, error.line());
		assertEquals(10, error.column());
	}

	@Test
	void prefersTheAdapterOfTheMapperToTheAdapterOfAType() {
		var mapper = SomlMapper.builder()
			.adapter(Hex.class, SomlAdapter.of(_ -> new SomlString("mapper"), _ -> new Hex(1, 2, 3)))
			.build();

		assertEquals(new SomlString("mapper"), mapper.toValue(new Hex(0, 0, 0)));
		assertEquals(new Hex(1, 2, 3), mapper.fromValue(new SomlString("#FF8800"), Hex.class));
	}

	record Twice(int value) {
		static final SomlAdapter<Twice> FIRST = SomlAdapter.of(_ -> new SomlString("a"), _ -> new Twice(1));
		static final SomlAdapter<Twice> SECOND = SomlAdapter.of(_ -> new SomlString("b"), _ -> new Twice(2));
	}

	@Test
	void refusesATypeWithTwoAdapters() {
		var error = assertThrows(SomlException.class, () -> Soml.toValue(new Twice(1)));
		assertEquals("Twice has two SomlAdapter fields, FIRST and SECOND. Keep one", error.reason());
	}

	enum Tier {
		FREE,
		PAID;

		static final SomlAdapter<Tier> SOML = SomlAdapter.of(tier -> new SomlString(tier.name().toLowerCase(java.util.Locale.ROOT)), value -> Tier.valueOf(((SomlString) value).value().toUpperCase(java.util.Locale.ROOT)));
	}

	@Test
	void usesTheAdapterOfAnEnumForMapKeys() {
		var limits = Soml.decode("free: 1\npaid: 2", new TypeReference<Map<Tier, Integer>>() {});
		assertEquals(Map.of(Tier.FREE, 1, Tier.PAID, 2), limits);
		assertEquals("free: 1\npaid: 2\n", Soml.encodeCanonical(limits));
	}

	@Test
	void locatesInvalidEnumMapKeysAtTheKey() {
		var text = "'wrong': 1\n";

		for (var type : List.<TypeReference<?>>of(new TypeReference<Map<Level, Integer>>() {}, new TypeReference<Map<Tier, Integer>>() {})) {
			var error = assertThrows(SomlException.class, () -> Soml.decode(text, type));
			assertEquals(List.of("wrong"), error.path());
			assertEquals(1, error.line());
			assertEquals(1, error.column());
			assertEquals("> 1 | 'wrong': 1\n    | ^", error.codeFrame());
			assertEquals(error.reason(), assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), type)).reason());
		}
	}

	interface Shape {
		SomlAdapter<Shape> SOML = SomlAdapter.of(
			shape -> new SomlString(shape instanceof Circle(var radius) ? "circle " + radius : "unknown"),
			value -> new Circle(Double.parseDouble(((SomlString) value).value().substring(7)))
		);
	}

	record Circle(double radius) implements Shape {}

	record Square(double side) implements Shape {
		static final SomlAdapter<Square> SOML = SomlAdapter.of(square -> new SomlString("square " + square.side()), _ -> new Square(1));
	}

	record Canvas(Shape shape) {}

	@Test
	void usesTheAdapterOfAnInterfaceForItsImplementations() {
		assertEquals("shape: 'circle 1.5'\n", Soml.encode(new Canvas(new Circle(1.5))));
		assertEquals(new Canvas(new Circle(1.5)), Soml.decode("shape: 'circle 1.5'", Canvas.class));
		assertEquals(List.of(new Circle(2.0)), Soml.decode("['circle 2.0']", new TypeReference<List<Circle>>() {}));
		assertEquals(new SomlString("square 2.0"), Soml.toValue(new Square(2)));

		// The adapter of the mapper wins over the type's own, also when it is for an interface above it.
		var mapper = SomlMapper.builder().adapter(Shape.class, SomlAdapter.of(_ -> new SomlString("shape"), _ -> new Circle(0))).build();
		assertEquals(new SomlString("shape"), mapper.toValue(new Square(2)));
	}

	record Machine(java.nio.file.Path home, java.util.Locale language, java.time.ZoneId zone, java.time.ZoneId offset) {}

	@Test
	void convertsPathsLocalesAndTimeZones() {
		var machine = new Machine(java.nio.file.Path.of("/srv/app"), java.util.Locale.forLanguageTag("nb-NO"), java.time.ZoneId.of("Europe/Oslo"), ZoneOffset.ofHours(2));
		var text = "home: '/srv/app'\nlanguage: 'nb-NO'\nzone: 'Europe/Oslo'\noffset: '+02:00'\n";
		assertEquals(machine, Soml.decode(text, Machine.class));
		assertEquals(text, Soml.encode(machine));

		var error = assertThrows(SomlException.class, () -> Soml.decode("home: '/'\nlanguage: 'nb_NO'\nzone: 'UTC'\noffset: 'Z'", Machine.class));
		assertEquals("Expected a string with a language tag, such as 'en-US', but found the string 'nb_NO'", error.reason());
		assertEquals(List.of("language"), error.path());

		// An offset is a time zone, but a time zone is not an offset.
		record Offsets(ZoneOffset offset, List<ZoneOffset> others) {}

		assertEquals(new Offsets(ZoneOffset.ofHours(2), List.of(ZoneOffset.UTC)), Soml.decode("offset: '+02:00'\nothers: ['Z']", Offsets.class));
		var offsetError = assertThrows(SomlException.class, () -> Soml.decode("offset: 'Europe/Oslo'\nothers: []", Offsets.class));
		assertEquals("Expected a string with an offset, such as '+02:00', but found the string 'Europe/Oslo'", offsetError.reason());
		var itemError = assertThrows(SomlException.class, () -> Soml.decode("offset: 'Z'\nothers: ['Europe/Oslo']", Offsets.class));
		assertEquals(List.of("others", 0), itemError.path());
	}

	sealed interface Animal permits Dog, Cat {}

	record Dog(String name) implements Animal {}

	record Cat(String name) implements Animal {}

	record Pets(Animal first, List<Animal> others) {}

	@Test
	void usesAnAdapterForAnInterfaceForItsImplementations() {
		var mapper = SomlMapper.builder()
			.adapter(Animal.class, SomlAdapter.of(
				animal -> new SomlString((animal instanceof Dog ? "dog " : "cat ") + (animal instanceof Dog(var name) ? name : ((Cat) animal).name())),
				value -> {
					var parts = ((SomlString) value).value().split(" ");
					return parts[0].equals("dog") ? new Dog(parts[1]) : new Cat(parts[1]);
				}
			))
			.adapter(java.nio.file.Path.class, SomlAdapter.of(path -> new SomlString("file:" + path), value -> java.nio.file.Path.of(((SomlString) value).value().substring(5))))
			.build();

		var pets = new Pets(new Dog("rex"), List.of(new Cat("tom")));
		assertEquals("first: 'dog rex'\nothers: [\n\t'cat tom'\n]\n", mapper.encode(pets));
		assertEquals(pets, mapper.decode("first: 'dog rex'\nothers: ['cat tom']", Pets.class));
		assertEquals(new SomlString("file:/srv"), mapper.toValue(java.nio.file.Path.of("/srv")));

		var error = assertThrows(SomlException.class, () -> mapper.fromValue(new SomlString("cat tom"), Dog.class));
		assertEquals("Expected Dog, but the SomlAdapter for Animal returned Cat", error.reason());

		// A primitive takes the adapters of its wrapper's supertypes, because its values are encoded as the wrapper.
		record Numbers(int primitive, Integer boxed) {}

		var numbers = SomlMapper.builder().adapter(Number.class, SomlAdapter.of(number -> new SomlString("n" + number), value -> Integer.parseInt(((SomlString) value).value().substring(1)))).build();
		assertEquals("primitive: 'n1'\nboxed: 'n2'\n", numbers.encode(new Numbers(1, 2)));
		assertEquals(new Numbers(1, 2), numbers.decode("primitive: 'n1'\nboxed: 'n2'", Numbers.class));
	}

	record Port(int number) {
		Port {
			if (number < 1 || number > 65_535) {
				throw new IllegalArgumentException("A port is 1 to 65535");
			}
		}
	}

	record Listener(Port port) {}

	@Test
	void reportsConstructorValidation() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("port: {number: 0}", Listener.class));
		assertEquals("Could not create Port: A port is 1 to 65535", error.reason());
		assertEquals(List.of("port"), error.path());
		assertEquals(7, error.column());
		assertTrue(error.getCause() instanceof IllegalArgumentException);
	}

	record Endpoint(String host, Port port) {}

	record Cluster(String name, int replicas, List<Endpoint> endpoints) {}

	@Test
	void reportsEveryValueThatDoesNotFit() {
		var mapper = SomlMapper.builder().rejectUnknownKeys(true).build();
		var text = """
			name: 'api'
			replicas: 'three'
			endpoints: [
				{host: 'a', port: {number: 80}}
				{host: 'b', prot: {number: 81}}
				{host: 'c', port: {number: 0}}
			]
			""";
		var error = assertThrows(SomlException.class, () -> mapper.decode(text, Cluster.class));
		assertEquals("Expected an int, but found the string 'three'", error.reason());
		assertEquals(List.of("replicas"), error.path());
		assertEquals(List.of("replicas", "endpoints[1].prot", "endpoints[1]", "endpoints[2].port"), error.errors().stream().map(SomlException::pathString).toList());
		assertEquals(List.of(2, 5, 5, 6), error.errors().stream().map(SomlException::line).toList());
		assertEquals("Could not create Port: A port is 1 to 65535", error.errors().getLast().reason());
		assertEquals(String.join("\n\n", error.errors().stream().map(SomlException::getMessage).toList()), error.getMessage());
	}

	@Test
	void reportsOneErrorAsItself() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("port: {number: 0}", Listener.class));
		assertEquals(List.of(error), error.errors());
	}

	@Test
	void createsNoRecordWithMembersThatDoNotFit() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("host: 1\nport: {number: 0}", Endpoint.class));
		assertEquals(List.of("host", "port"), error.errors().stream().map(SomlException::pathString).toList());
		assertEquals("Expected an object, but found the int 1", assertThrows(SomlException.class, () -> Soml.decode("host: 'a'\nport: 1", Endpoint.class)).reason());
	}

	@Test
	void stopsAfterTenErrors() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("a: [" + "'x', ".repeat(25) + "]", new TypeReference<Map<String, List<Integer>>>() {}));
		assertEquals(10, error.errors().size());
		assertEquals(List.of("a", 9), error.errors().getLast().path());
		assertTrue(error.getMessage().endsWith("\n\nDecoding stopped after 10 errors"));

		var split = assertThrows(SomlException.class, () -> Soml.decode("a: [" + "'x', ".repeat(8) + "]\nb: [" + "'y', ".repeat(8) + "]", new TypeReference<Map<String, List<Integer>>>() {}));
		assertEquals(List.of("a[7]", "b[0]", "b[1]"), split.errors().subList(7, 10).stream().map(SomlException::pathString).toList());

		var ten = assertThrows(SomlException.class, () -> Soml.decode("a: [" + "'x', ".repeat(10) + "]", new TypeReference<Map<String, List<Integer>>>() {}));
		assertEquals(10, ten.errors().size());
		assertFalse(ten.getMessage().contains("Decoding stopped"));

		var eleven = assertThrows(SomlException.class, () -> Soml.decode("a: [" + "'x', ".repeat(10) + "]\nb: ['y']", new TypeReference<Map<String, List<Integer>>>() {}));
		assertEquals(10, eleven.errors().size());
		assertTrue(eleven.getMessage().endsWith("\n\nDecoding stopped after 10 errors"));
	}

	record Tasks(int count, List<Runnable> tasks) {}

	static class Freezer {
		String name = "";
		Frozen frozen = new Frozen();
	}

	@Test
	void reportsAnErrorAboutTheTypesAlone() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("count: 'x'\ntasks: [{}, {}, {}]", Tasks.class));
		assertEquals(List.of(error), error.errors());
		assertEquals(List.of("tasks", 0), error.path());
		assertTrue(error.reason().startsWith("Cannot decode to Runnable"));

		var frozen = assertThrows(SomlException.class, () -> Soml.decode("name: 1\nfrozen: {host: 2, port: 8080}", Freezer.class));
		assertEquals(List.of(frozen), frozen.errors());
		assertEquals("Cannot decode the final field port of Frozen. Make it not final, or make Frozen a record", frozen.reason());
		assertEquals(List.of("frozen", "port"), frozen.path());
	}

	@Test
	void reportsEveryValueThatDoesNotFitInATree() {
		var value = Soml.parse("host: 1\nport: 'x'");
		var error = assertThrows(SomlException.class, () -> Soml.fromValue(value, Endpoint.class));
		assertEquals(List.of("host", "port"), error.errors().stream().map(SomlException::pathString).toList());
		assertEquals("host: Expected a string, but found the int 1\n\nport: Expected an object, but found the string 'x'", error.getMessage());
	}

	record Kennel(Dog dog, Port port, int size) {}

	@Test
	void reportsTheErrorsOfAdaptersWithTheOthers() {
		var mapper = SomlMapper.builder()
			.adapter(Animal.class, SomlAdapter.of(_ -> new SomlString("animal"), _ -> new Cat("tom")))
			.adapter(Port.class, SomlAdapter.of(port -> new SomlInt(port.number()), _ -> {
				throw new SomlException("Expected a port");
			}))
			.build();

		var error = assertThrows(SomlException.class, () -> mapper.decode("dog: 'rex'\nport: 80\nsize: 'x'", Kennel.class));
		assertEquals(List.of("Expected Dog, but the SomlAdapter for Animal returned Cat", "Expected a port", "Expected an int, but found the string 'x'"), error.errors().stream().map(SomlException::reason).toList());
		assertEquals(List.of(1, 2, 3), error.errors().stream().map(SomlException::line).toList());
	}

	@Test
	void refusesScalarDocument() {
		var error = assertThrows(SomlException.class, () -> Soml.encode("text"));
		assertEquals("The top-level value must be an object or an array, because a document is a collection. Got a string", error.reason());
	}

	@Test
	void reportsEncodingErrorsWithPath() {
		var error = assertThrows(SomlException.class, () -> Soml.encode(Map.of("metrics", List.of(1.0, Double.NaN))));
		assertEquals("Cannot encode NaN, which is not representable. Use null for a missing value", error.reason());
		assertEquals(List.of("metrics", 1), error.path());
		assertEquals(0, error.line());
		assertEquals("metrics[1]: Cannot encode NaN, which is not representable. Use null for a missing value", error.getMessage());
	}

	@Test
	void refusesCycles() {
		var map = new HashMap<String, Object>();
		map.put("self", map);
		var error = assertThrows(SomlException.class, () -> Soml.encode(map));
		assertTrue(error.reason().startsWith("Cannot encode a value nested more than 100 levels deep"), error.reason());
	}

	@Test
	void writesOptionalAndNull() {
		var values = new LinkedHashMap<String, Object>();
		values.put("present", Optional.of(1));
		values.put("absent", Optional.empty());
		values.put("null", null);
		values.put("items", List.of(Optional.empty(), Optional.of("x")));
		assertEquals("present: 1\nnull: null\nitems: [\n\tnull\n\t'x'\n]\n", Soml.encode(values));
	}

	record When(OffsetDateTime at) {}

	@Test
	void convertsTimes() {
		var when = Soml.decode("at: 2026-09-19T16:00:00+02:00", When.class);
		assertEquals(OffsetDateTime.of(2026, 9, 19, 14, 0, 0, 0, ZoneOffset.UTC), when.at());
		assertEquals("at: 2026-09-19T14:00:00Z\n", Soml.encode(new When(OffsetDateTime.of(2026, 9, 19, 16, 0, 0, 0, ZoneOffset.ofHours(2)))));

		var error = assertThrows(SomlException.class, () -> Soml.encode(Map.of("at", Instant.MAX)));
		assertTrue(error.reason().startsWith("Cannot encode the instant"), error.reason());
	}

	@Test
	void convertsValueTrees() {
		var value = Soml.toValue(new Server("a", 80));
		assertEquals(new SomlObject(Map.of("host", new SomlString("a"), "port", new SomlInt(80))), value);
		assertEquals(new Server("a", 80), Soml.fromValue(value, Server.class));

		var error = assertThrows(SomlException.class, () -> Soml.fromValue(new SomlObject(Map.of("host", new SomlInt(1), "port", new SomlInt(80))), Server.class));
		assertEquals("host: Expected a string, but found the int 1", error.getMessage());
	}

	record Ids(UUID id, Level level) {}

	@Test
	void reportsEnumChoices() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("id: '123e4567-e89b-12d3-a456-426614174000'\nlevel: 'trace'", Ids.class));
		assertEquals("Expected one of 'debug', 'info', 'WARN' (Level), but found the string 'trace'", error.reason());
		assertEquals(new Ids(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), Level.WARN), Soml.decode("id: '123e4567-e89b-12d3-a456-426614174000'\nlevel: 'WARN'", Ids.class));
	}

	@Test
	void decodesSomlValues() {
		record Raw(SomlValue anything, SomlObject object, @Nullable SomlValue missing) {}

		var raw = Soml.decode("anything: [1]\nobject: {a: null}", Raw.class);
		assertEquals(SomlArray.of(new SomlInt(1)), raw.anything());
		assertEquals(SomlNull.INSTANCE, raw.object().get("a"));
		assertNull(raw.missing());
	}

	record NotComparable(String name) {}

	record Sorted(SortedSet<NotComparable> items) {}

	record Strings(Deque<String> items) {}

	@Test
	void reportsItemsThatCollectionsRefuse() {
		var notComparable = assertThrows(SomlException.class, () -> Soml.decode("items: [{name: 'x'}]", Sorted.class));
		assertTrue(notComparable.reason().startsWith("The SortedSet cannot hold an object: "), notComparable.reason());
		assertEquals(List.of("items", 0), notComparable.path());

		var nullItem = assertThrows(SomlException.class, () -> Soml.decode("items: ['a', null]", Strings.class));
		assertEquals("The Deque cannot hold null: NullPointerException", nullItem.reason());
		assertEquals(List.of("items", 1), nullItem.path());

		var nullMember = assertThrows(SomlException.class, () -> Soml.decode("b: null", new TypeReference<ConcurrentHashMap<String, Integer>>() {}));
		assertEquals(List.of("b"), nullMember.path());

		var unknownClass = assertThrows(SomlException.class, () -> Soml.decode("b: 1", new TypeReference<ConcurrentMap<String, Integer>>() {}));
		assertTrue(unknownClass.reason().startsWith("Cannot decode to ConcurrentMap, because it is not known which class to create for it"), unknownClass.reason());
	}

	record LevelCounts(EnumSet<Level> enabled, EnumMap<Level, Integer> counts) {}

	@Test
	void decodesEnumSetsAndEnumMaps() {
		var text = "enabled: ['info', 'debug']\ncounts: {info: 2, debug: 1}";
		var expected = new LevelCounts(EnumSet.of(Level.DEBUG, Level.INFO), new EnumMap<>(Map.of(Level.DEBUG, 1, Level.INFO, 2)));

		assertEquals(expected, Soml.decode(text, LevelCounts.class));
		assertEquals(expected, Soml.fromValue(Soml.parse(text), LevelCounts.class));
		assertEquals("enabled: [\n\t'debug'\n\t'info'\n]\ncounts: {\n\tdebug: 1\n\tinfo: 2\n}\n", Soml.encode(expected));

		var duplicate = assertThrows(SomlException.class, () -> Soml.decode("['info', 'info']", new TypeReference<EnumSet<Level>>() {}));
		assertEquals(List.of(1), duplicate.path());
	}

	static class ServerList extends ArrayList<Server> {
		private static final long serialVersionUID = 1L;
	}

	static class Registry extends HashMap<String, Server> {
		private static final long serialVersionUID = 1L;
	}

	static class RecursiveList extends ArrayList<RecursiveList> {
		private static final long serialVersionUID = 1L;
	}

	static class RecursiveMap extends HashMap<String, RecursiveMap> {
		private static final long serialVersionUID = 1L;
	}

	@Test
	void decodesRecursiveContainers() {
		assertEquals(List.of(List.of()), Soml.decode("[[]]", RecursiveList.class));
		assertEquals(Map.of("child", Map.of()), Soml.decode("child: {}", RecursiveMap.class));
		assertEquals(List.of(List.of()), Soml.fromValue(Soml.parse("[[]]"), RecursiveList.class));
		assertEquals(Map.of("child", Map.of()), Soml.fromValue(Soml.parse("child: {}"), RecursiveMap.class));
	}

	@Test
	void sharesAMapperAcrossConcurrentFirstUses() throws Exception {
		var mapper = SomlMapper.builder().build();
		var expected = Soml.decode(CONFIG, Config.class);
		var encoded = Soml.encode(expected);
		var start = new java.util.concurrent.CountDownLatch(1);

		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
			var tasks = new ArrayList<java.util.concurrent.Future<?>>();

			for (var task = 0; task < 8; task++) {
				tasks.add(executor.submit(() -> {
					start.await();

					for (var attempt = 0; attempt < 25; attempt++) {
						assertEquals(expected, mapper.decode(CONFIG, Config.class));
						assertEquals(encoded, mapper.encode(expected));
						assertEquals(List.of(List.of()), mapper.decode("[[]]", RecursiveList.class));
						assertEquals(Map.of("child", Map.of()), mapper.decode("child: {}", RecursiveMap.class));
					}

					return null;
				}));
			}

			start.countDown();

			for (var task : tasks) {
				task.get();
			}
		}
	}

	@Test
	void refusesDecodedMapKeyCollisions() {
		var first = "abcdef00-0000-0000-0000-000000000000";
		var second = first.toUpperCase(java.util.Locale.ROOT);
		var source = first + ": null\n" + second + ": 2";
		var type = new TypeReference<Map<UUID, Integer>>() {};
		var error = assertThrows(SomlException.class, () -> Soml.decode(source, type));
		assertEquals(List.of(second), error.path());
		assertEquals(1, error.column());
		assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(source), type));
	}

	record WildcardKeys(Map<? super Integer, String> values) {}

	@Test
	void usesLowerBoundsForMapKeys() {
		var source = "1: 'one'";
		var expected = Map.of(1, "one");
		var type = new TypeReference<Map<? super Integer, String>>() {};
		assertEquals(expected, Soml.decode(source, type));
		assertEquals(expected, Soml.fromValue(Soml.parse(source), type));
		assertEquals(expected, Soml.decode("values: {" + source + "}", WildcardKeys.class).values());
	}

	@Test
	void roundTripsLargeIntegerMapKeys() {
		var value = Map.of(BigInteger.ONE.shiftLeft(80), 1);
		var type = new TypeReference<Map<BigInteger, Integer>>() {};
		assertEquals(value, Soml.decode(Soml.encode(value), type));
		assertEquals(value, Soml.fromValue(Soml.toValue(value), type));

		for (var key : List.of("+1", "01", "-0", "١", "word")) {
			assertThrows(SomlException.class, () -> Soml.decode("'" + key + "': 1", type));
		}
	}

	@Test
	void usesAdaptersForTopLevelValueTrees() {
		var object = new SomlObject(Map.of());
		var replacement = SomlArray.of(new SomlInt(9));
		var mapper = SomlMapper.builder().adapter(SomlObject.class, SomlAdapter.of(value -> replacement, value -> object)).build();
		assertEquals(replacement, mapper.toValue(object));
		assertEquals(Soml.encode(replacement), mapper.encode(object));
		assertEquals(Soml.encodeCanonical(replacement), mapper.encodeCanonical(object));

		var nullMapper = SomlMapper.builder().adapter(SomlValue.class, SomlAdapter.of(value -> replacement, value -> replacement)).build();
		assertEquals(SomlNull.INSTANCE, nullMapper.toValue(null));
		assertThrows(SomlException.class, () -> nullMapper.encode(null));
	}

	static class Flipped<A, B> extends ArrayList<B> {
		private static final long serialVersionUID = 1L;
	}

	@Test
	void resolvesItemTypesOfSubclasses() {
		assertEquals(new Server("a", 1), Soml.decode("[{host: 'a', port: 1}]", ServerList.class).getFirst());
		assertEquals(new Server("a", 1), Soml.decode("k: {host: 'a', port: 1}", Registry.class).get("k"));
		assertEquals(new Server("a", 1), Soml.decode("[{host: 'a', port: 1}]", new TypeReference<Flipped<String, Server>>() {}).getFirst());
	}

	enum Body {
		A {
			@Override
			public String toString() {
				return "body";
			}
		}
	}

	@Test
	void usesEnumAdaptersForConstantsWithBodies() {
		var mapper = SomlMapper.builder()
			.adapter(Body.class, new SomlAdapter<>() {
				@Override
				public SomlValue encode(Body value) {
					return new SomlString("custom");
				}

				@Override
				public Body decode(SomlValue value) {
					return Body.A;
				}
			})
			.build();

		assertEquals("a: 'custom'\n", mapper.encode(Map.of("a", Body.A)));
		assertEquals("a: 'A'\n", Soml.encode(Map.of("a", Body.A)));
		assertEquals("custom: 1\n", mapper.encode(Map.of(Body.A, 1)));
		assertEquals(Map.of(Body.A, 1), mapper.decode("custom: 1", new TypeReference<Map<Body, Integer>>() {}));
	}

	record SelfBound<T extends List<T>>(T value) {}

	@Test
	void decodesSelfReferentialTypeVariable() {
		assertEquals(List.of(), Soml.decode("value: []", SelfBound.class).value());
	}

	record Single(float value) {}

	@Test
	void refusesFloatUnderflow() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("value: 1e-50", Single.class));
		assertEquals("The float 1e-50 does not fit the Java type float", error.reason());
		assertEquals(0.0f, Soml.decode("value: 0.0", Single.class).value());
	}

	@Test
	void wrapsAdapterFailures() {
		var mapper = SomlMapper.builder()
			.adapter(Color.class, new SomlAdapter<>() {
				@Override
				public SomlValue encode(Color color) {
					return null;
				}

				@Override
				public Color decode(SomlValue value) {
					return new Color(Integer.parseInt("zz"), 0, 0);
				}
			})
			.build();

		var decodeError = assertThrows(SomlException.class, () -> mapper.decode("accent: 'x'", Theme.class));
		assertEquals("The SomlAdapter for Color failed: For input string: \"zz\"", decodeError.reason());
		assertEquals(List.of("accent"), decodeError.path());

		var encodeError = assertThrows(SomlException.class, () -> mapper.encode(new Theme(new Color(0, 0, 0))));
		assertEquals("The SomlAdapter for Color returned null. Return SomlNull.INSTANCE for null", encodeError.reason());
	}

	record Strict(String name) {
		Strict {
			java.util.Objects.requireNonNull(name);
		}
	}

	@Test
	void describesExceptionsWithoutMessage() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("name: null", Strict.class));
		assertEquals("Could not create Strict: NullPointerException", error.reason());
	}

	@Test
	void refusesTypeVariableInTypeReference() {
		assertThrows(IllegalStateException.class, BindingTest::<String>typeReferenceOf);
	}

	private static <T> TypeReference<T> typeReferenceOf() {
		return new TypeReference<T>() {};
	}

	abstract static class ListReference<T> extends TypeReference<List<T>> {}

	@Test
	void refusesIndirectTypeReferencesInsteadOfCapturingTheWrongType() {
		assertThrows(IllegalStateException.class, () -> new ListReference<String>() {});
	}

	@Test
	void refusesNestedTypeVariablesInTypeReferences() {
		assertUnresolvedTypeReferences();
		assertEquals(List.of("a"), Soml.decode("['a']", new TypeReference<List<? extends String>>() {}));
	}

	@Test
	void separatesSupplementaryLettersInJavaNames() {
		assertEquals("a-𐐨b", KeyNaming.KEBAB_CASE.apply("a𐐀B"));
		assertEquals("𐐨_name", KeyNaming.SNAKE_CASE.apply("𐐀Name"));
		assertEquals("𐐨-name", KeyNaming.KEBAB_CASE.apply("𐐨Name"));
	}

	private static <T> void assertUnresolvedTypeReferences() {
		assertThrows(IllegalStateException.class, () -> new TypeReference<List<T>>() {});
		assertThrows(IllegalStateException.class, () -> new TypeReference<T[]>() {});
		assertThrows(IllegalStateException.class, () -> new TypeReference<List<? extends T>>() {});
		assertThrows(IllegalStateException.class, () -> new TypeReference<List<? super T>>() {});
	}

	record Ignored(@SomlIgnore int x, int y) {}

	record NullableUnsupported(Runnable action, Number number, Thread thread) {}

	@Test
	void decodesNullForUnsupportedReferenceTypes() {
		var source = "action: null\nnumber: null\nthread: null";
		var expected = new NullableUnsupported(null, null, null);
		assertEquals(expected, Soml.decode(source, NullableUnsupported.class));
		assertEquals(expected, Soml.fromValue(Soml.parse(source), NullableUnsupported.class));
		assertNull(Soml.fromValue(SomlNull.INSTANCE, Runnable.class));
		assertThrows(SomlException.class, () -> Soml.fromValue(new SomlInt(1), Runnable.class));
	}

	@Test
	void refusesIgnoredRecordComponent() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("x: 1\ny: 2", Ignored.class));
		assertTrue(error.reason().startsWith("Cannot leave out the component x of Ignored with @SomlIgnore"), error.reason());
	}

	@Test
	void decodesCharacterKeys() {
		var map = Soml.decode("a: 1", new TypeReference<Map<Character, Integer>>() {});
		assertEquals(Map.of('a', 1), map);
		assertEquals("a: 1\n", Soml.encode(map));
	}

	enum Duplicate {
		@SomlName("x")
		FIRST,
		@SomlName("x")
		SECOND
	}

	@Test
	void refusesDuplicateEnumNames() {
		var error = assertThrows(SomlException.class, () -> Soml.encode(Map.of("a", Duplicate.FIRST)));
		assertTrue(error.reason().startsWith("Cannot use Duplicate, because two of its constants are both named 'x'"), error.reason());
	}

	record Timeout(Duration timeout) {}

	@Test
	void hintsAtCommonMistakes() {
		assertEquals("Expected a duration, but found the int 30. A duration has a unit, as in 30s or 30ms", assertThrows(SomlException.class, () -> Soml.decode("timeout: 30", Timeout.class)).reason());
		assertEquals("Expected one of 'debug', 'info', 'WARN' (Level), but found the string 'warn'. The names are case-sensitive, so write 'WARN'", assertThrows(SomlException.class, () -> Soml.decode("id: '123e4567-e89b-12d3-a456-426614174000'\nlevel: 'warn'", Ids.class)).reason());
	}

	@Test
	void reportsSyntaxErrorsBeforeValuesThatDoNotFit() {
		// The decoder decodes while it parses, so it meets the value that does not fit first, and still reports the syntax error after it.
		var error = assertThrows(SomlException.class, () -> Soml.decode("host: 1\nport: 80\nbad", Server.class));
		assertEquals("Expected “:” after the key", error.reason());
		assertEquals(3, error.line());

		var fit = assertThrows(SomlException.class, () -> Soml.decode("servers: [\n\t{host: 'a', port: 80}\n\t{host: 'b', port: 'x'}\n]", Servers.class));
		assertEquals(List.of("servers", 1, "port"), fit.path());
		assertEquals(3, fit.line());
	}

	static class Frozen {
		final int port = 80;
		String host = "a";
	}

	@Test
	void refusesFinalFields() {
		var error = assertThrows(SomlException.class, () -> Soml.decode("host: 'b'\nport: 8080", Frozen.class));
		assertEquals("Cannot decode the final field port of Frozen. Make it not final, or make Frozen a record", error.reason());
		assertEquals(List.of("port"), error.path());
		assertEquals("b", Soml.decode("host: 'b'", Frozen.class).host);
		assertEquals("port: 80\nhost: 'a'\n", Soml.encode(new Frozen()));
	}

	record Checked(int a) {
		Checked {
			new SomlString("x\r");
		}
	}

	@Test
	void checksStringsThatCodeMakesWhileDecoding() {
		// The parser's strings skip their check, and the constructors that the decoder calls must not.
		assertThrows(SomlException.class, () -> Soml.decode("a: 1\n", Checked.class));
	}

	record Point(int x) {}

	record Holder(Point point) {}

	@Test
	void refusesNullFromAnAdapter() {
		var mapper = SomlMapper.builder().adapter(Point.class, SomlAdapter.<Point>of(_ -> new SomlString("x"), _ -> null)).build();
		var error = assertThrows(SomlException.class, () -> mapper.decode("point: 1\n", Holder.class));
		assertEquals("The SomlAdapter for Point returned null. Return a value, or throw a SomlException", error.reason());
		assertEquals(List.of("point"), error.path());
	}

	record Count(int number) {}

	record Counts(int primitive, Integer boxed) {}

	@Test
	void replacesPrimitiveAndWrapperAdaptersTogether() {
		var first = SomlAdapter.<Integer>of(_ -> new SomlString("first"), _ -> 1);
		var second = SomlAdapter.<Integer>of(_ -> new SomlString("second"), _ -> 2);
		var text = "primitive: 'input'\nboxed: 'input'\n";
		var tree = Soml.parse(text);

		for (var type : List.of(int.class, Integer.class)) {
			var builder = SomlMapper.builder().adapter(type, first);
			var before = builder.build();
			var mapper = builder.adapter(type == int.class ? Integer.class : int.class, second).build();

			assertEquals(new Counts(1, 1), before.decode(text, Counts.class));
			assertEquals(new Counts(2, 2), mapper.decode(text, Counts.class));
			assertEquals(new Counts(2, 2), mapper.fromValue(tree, Counts.class));
			assertEquals("primitive: 'second'\nboxed: 'second'\n", mapper.encode(new Counts(9, 9)));
			assertEquals("boxed: 'second'\nprimitive: 'second'\n", mapper.encodeCanonical(new Counts(9, 9)));
			assertEquals(Soml.parse("primitive: 'second'\nboxed: 'second'\n"), mapper.toValue(new Counts(9, 9)));
		}
	}

	@Test
	void refusesNullForAPrimitiveWithAnAdapter() {
		var mapper = SomlMapper.builder().adapter(int.class, SomlAdapter.<Integer>of(number -> new SomlInt(number), value -> (int) ((SomlInt) value).value())).build();
		assertEquals(new Count(2), mapper.decode("number: 2\n", Count.class));
		var error = assertThrows(SomlException.class, () -> mapper.decode("number: null\n", Count.class));
		assertEquals("Expected int, but found null", error.reason());
		assertEquals(List.of("number"), error.path());
	}

	@Test
	void keepsMessagesShortAndSafe() {
		var longKey = "9".repeat(10_000);
		var number = assertThrows(SomlException.class, () -> Soml.decode("'" + longKey + "': 'a'\n", new TypeReference<Map<Integer, String>>() {}));
		assertTrue(number.getMessage().length() < 500, "message length " + number.getMessage().length());
		assertNull(number.getCause());

		var bare = assertThrows(SomlException.class, () -> Soml.decode("k" + longKey + ": 'a'\n", new TypeReference<Map<String, Integer>>() {}));
		assertTrue(bare.getMessage().length() < 500, "message length " + bare.getMessage().length());
		assertTrue(bare.pathString().length() < 100, "path length " + bare.pathString().length());

		var control = assertThrows(SomlException.class, () -> SomlMapper.builder().rejectUnknownKeys(true).build().decode("'\u202E': 1\n", Holder.class));
		assertEquals("[\"\uFFFD\"]", control.pathString());
	}
}
