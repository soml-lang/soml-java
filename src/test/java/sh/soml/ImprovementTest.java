package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ImprovementTest {
	@Test
	void parserPoolBoundsStorageAcrossAllCollectionDepths() throws Exception {
		for (var depth = 1; depth <= 100; depth++) {
			Soml.parse("[".repeat(depth) + "0,".repeat(16_384) + "]".repeat(depth));
		}

		var poolField = Parser.class.getDeclaredField("SPARE_SCRATCH");
		poolField.setAccessible(true);
		var scratch = ((AtomicReference<?>) poolField.get(null)).get();
		long slots = 0;

		if (scratch != null) {
			for (var name : List.of("membersByDepth", "itemsByDepth")) {
				var field = scratch.getClass().getDeclaredField(name);
				field.setAccessible(true);

				for (var collection : (Object[]) field.get(scratch)) {
					if (collection == null) {
						continue;
					}

					for (var buffer : collection.getClass().getDeclaredFields()) {
						if (!java.lang.reflect.Modifier.isStatic(buffer.getModifiers()) && buffer.getType().isArray()) {
							buffer.setAccessible(true);
							slots += Array.getLength(buffer.get(collection));
						}
					}
				}
			}
		}

		assertTrue(slots <= 1 << 20, "The parser retained " + slots + " collection slots");
	}

	@Test
	void hugeRejectedIntegersDoNotProduceHugeDecimalDiagnostics() {
		var value = BigInteger.ONE.shiftLeft(1_000_000);
		var error = assertThrows(SomlException.class, () -> Soml.encode(Map.of("number", value)));
		assertTrue(error.getMessage().length() < 200);
		assertEquals(List.of("number"), error.path());
		assertTrue(error.reason().contains("outside the 64-bit range"));
		assertTrue(assertThrows(SomlException.class, () -> Soml.toValue(List.of(value))).getMessage().length() < 200);
	}

	@Test
	void directScalarWritingKeepsCanonicalRepresentationsAndOptionalOmission() {
		var instant = Instant.parse("0001-01-01T00:00:00.000000001Z");
		var values = Arrays.asList(Byte.MIN_VALUE, Short.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, BigInteger.valueOf(Long.MIN_VALUE), 0.1f, -0.0f, Double.NEGATIVE_INFINITY, 'é', instant, instant.atOffset(java.time.ZoneOffset.UTC), instant.atZone(ZoneId.of("UTC")), Duration.ofNanos(Long.MIN_VALUE), OptionalInt.of(3), OptionalLong.of(Long.MAX_VALUE), OptionalDouble.of(0.1), OptionalInt.empty(), OptionalLong.empty(), OptionalDouble.empty(), null);
		assertEquals("[\n\t-128\n\t32767\n\t-9223372036854775808\n\t9223372036854775807\n\t-9223372036854775808\n\t0.1\n\t0.0\n\t-infinity\n\t'é'\n\t0001-01-01T00:00:00.000000001Z\n\t0001-01-01T00:00:00.000000001Z\n\t0001-01-01T00:00:00.000000001Z\n\t-2562047h47m16.854775808s\n\t3\n\t9223372036854775807\n\t0.1\n\tnull\n\tnull\n\tnull\n\tnull\n]\n", Soml.encode(values));
		assertEquals("{}\n", Soml.encode(Map.of("i", OptionalInt.empty(), "l", OptionalLong.empty(), "d", OptionalDouble.empty())));
	}

	@Test
	void directScalarWritingStillHonorsRegisteredAdapters() {
		var mapper = SomlMapper.builder().adapter(Number.class, SomlAdapter.of(_ -> new SomlString("custom"), _ -> 1)).build();
		assertEquals("[\n\t'custom'\n\t'custom'\n\t'custom'\n]\n", mapper.encode(List.of(1, 0.5, BigInteger.TEN)));
		assertEquals("[\n\t1\n\t0.5\n\t10\n]\n", Soml.encode(List.of(1, 0.5, BigInteger.TEN)));
	}

	@Test
	void directScalarWritingPreservesErrorPathsAndReleasesBuffers() {
		for (var value : List.of(Double.NaN, OptionalDouble.of(Double.NaN), Instant.MAX, OffsetDateTime.MAX, Duration.ofSeconds(Long.MAX_VALUE), '\uD800', "bad\rtext")) {
			var error = assertThrows(SomlException.class, () -> Soml.encode(List.of(value)));
			assertEquals(List.of(0), error.path());
			assertEquals("[\n\t1\n]\n", Soml.encode(List.of(1)));
		}
	}

	@Test
	void repeatedMapViewsKeepMembershipEncounterOrderAndImmutability() {
		var object = (SomlObject) Soml.parse("z: 1\na: null\nm: 2");
		var map = object.members();
		for (var attempt = 0; attempt < 10; attempt++) {
			assertEquals(List.of("z", "a", "m"), map.keySet().stream().toList());
			assertEquals(List.of(new SomlInt(1), SomlNull.INSTANCE, new SomlInt(2)), map.values().stream().toList());
			assertTrue(map.entrySet().contains(Map.entry("a", SomlNull.INSTANCE)));
			assertThrows(UnsupportedOperationException.class, () -> map.values().clear());
		}
		assertFalse(map.containsKey(null));
		assertNotNull(map.get("a"));
		assertEquals(new LinkedHashMap<>(map), map);
	}

	@TestFactory
	Stream<DynamicTest> rejectedJdkScalarsDoNotRetainRawInputInTheirCauses() {
		return Stream.of(URI.class, Path.class, UUID.class, ZoneId.class, ZoneOffset.class, Locale.class).map(type -> DynamicTest.dynamicTest(type.getSimpleName(), () -> {
			var input = "secret" + "x".repeat(100_000) + "\u001B\u0000";
			var tree = SomlArray.of(new SomlString(input));
			for (var error : List.of(assertThrows(SomlException.class, () -> Soml.fromValue(tree, type.arrayType())), assertThrows(SomlException.class, () -> Soml.decode(Soml.encode(tree), type.arrayType())))) {
				assertNull(error.getCause());
				var trace = new StringWriter();
				error.printStackTrace(new PrintWriter(trace));
				assertFalse(trace.toString().contains(input));
				assertFalse(trace.toString().contains("\u001B"));
				assertFalse(trace.toString().contains("\u0000"));
				assertTrue(error.getMessage().length() < 500);
			}
		}));
	}

	@Test
	void rejectedUuidMapKeysDoNotLeakRawInputInCauses() {
		var key = "secret\u001B" + "x".repeat(100_000);
		var type = new TypeReference<Map<UUID, Integer>>() {};
		var error = assertThrows(SomlException.class, () -> Soml.decode(Writer.formatKey(key) + ": 1", type));
		assertNull(error.getCause());
		assertTrue(error.getMessage().length() < 500);
		assertEquals(List.of(key), error.path());
	}

	@Test
	void oversizedUnterminatedBlockDelimitersHaveBoundedHints() {
		for (var quote : List.of("'", "\"")) {
			var delimiter = quote.repeat(100_000);
			var error = assertThrows(SomlException.class, () -> Soml.parse("a: " + delimiter + "\nx" + delimiter));
			assertEquals("Unterminated block string", error.reason());
			assertTrue(error.getMessage().length() < 250);
			assertEquals(1, error.line());
			assertEquals(4, error.column());
		}
	}

	@Test
	void throwingIteratorCallbacksConsumeOnlyTheDeliveredItem() {
		var iterator = ((SomlArray) Soml.parse("[1, 2, 3]")).items().iterator();
		var expected = new IllegalStateException("stop");
		assertSame(expected, assertThrows(IllegalStateException.class, () -> iterator.forEachRemaining(_ -> {
			throw expected;
		})));
		assertEquals(new SomlInt(2), iterator.next());
		var remainder = new ArrayList<SomlValue>();
		iterator.forEachRemaining(remainder::add);
		assertEquals(List.of(new SomlInt(3)), remainder);
		assertFalse(iterator.hasNext());
		assertThrows(java.util.NoSuchElementException.class, iterator::next);
		assertThrows(NullPointerException.class, () -> iterator.forEachRemaining(null));
	}

	@Test
	void frozenListTraversalAndHashingFollowTheListContract() {
		var items = ((SomlArray) Soml.parse("[1, null, {a: '😀'}, 1]")).items();
		var copy = new ArrayList<>(items);
		var visited = new ArrayList<SomlValue>();
		items.forEach(visited::add);
		assertEquals(copy, visited);
		assertEquals(copy.hashCode(), items.hashCode());
		assertEquals(copy, items);
		assertEquals(items, ((SomlArray) Soml.parse("[1, null, {a: '😀'}, 1]")).items());
		assertThrows(NullPointerException.class, () -> items.forEach(null));
		assertThrows(NullPointerException.class, () -> SomlArray.of().items().forEach(null));
	}

	sealed interface Variant permits Tagged, Empty {}

	record Tagged(String z, Optional<Integer> a) implements Variant {}

	record Empty(OptionalInt omitted) implements Variant {}

	@Test
	void taggedWritingKeepsTagsOmissionsIndentationAndCanonicalOrder() {
		var value = new Tagged("😀", Optional.of(3));
		assertEquals("type: 'Tagged'\nz: '😀'\na: 3\n", Soml.encode(value));
		assertEquals("a: 3\ntype: 'Tagged'\nz: '😀'\n", Soml.encodeCanonical(value));
		assertEquals("type: 'Empty'\n", Soml.encode(new Empty(OptionalInt.empty())));
		assertEquals("[\n\t{\n\t\ttype: 'Tagged'\n\t\tz: '😀'\n\t\ta: 3\n\t}\n]\n", Soml.encode(List.of(value)));
		assertEquals(Soml.encodeCanonical(Soml.toValue(List.of(value))), Soml.encodeCanonical(List.of(value)));
	}

	@Test
	void concreteTaggedStreamingDecodeAcceptsTagAtEveryPositionAndRejectsWrongTag() {
		var mapper = SomlMapper.builder().rejectUnknownKeys(true).build();
		for (var text : List.of("type: 'Tagged'\nz: 'x'\na: 1", "z: 'x'\ntype: 'Tagged'\na: 1", "z: 'x'\na: 1\ntype: 'Tagged'", "z: 'x'\na: 1")) {
			assertEquals(new Tagged("x", Optional.of(1)), mapper.decode(text, Tagged.class));
		}
		for (var tag : List.of("'Empty'", "null", "{}", "[]", "1")) {
			var error = assertThrows(SomlException.class, () -> mapper.decode("z: 'x'\ntype: " + tag, Tagged.class));
			assertEquals(List.of("type"), error.path());
			assertEquals(2, error.line());
		}
		assertEquals(new Tagged("x", Optional.empty()), mapper.decode("type: 'Tagged'\nz: 'x'", Variant.class));
	}

	@Test
	void scalarCodecSharingDoesNotShareMapperOptionsOrAdapters() {
		record Count(int value, OptionalLong optional) {}
		var ordinary = SomlMapper.builder().build();
		var adapted = SomlMapper.builder().adapter(Integer.class, SomlAdapter.of(number -> new SomlString(number.toString()), value -> Integer.valueOf(((SomlString) value).value()))).build();
		assertEquals(new Count(1, OptionalLong.empty()), ordinary.decode("value: 1", Count.class));
		assertEquals(new Count(1, OptionalLong.empty()), adapted.decode("value: '1'", Count.class));
		assertEquals("value: 1\n", ordinary.encode(new Count(1, OptionalLong.empty())));
		assertEquals("value: '1'\n", adapted.encode(new Count(1, OptionalLong.empty())));
	}

	@Test
	void duplicateEncodedMapKeysAreRejectedBeforeTheirValuesRun() {
		record SideEffect(int value) {}
		var calls = new int[1];
		var mapper = SomlMapper.builder().adapter(SideEffect.class, SomlAdapter.of(value -> {
			calls[0]++;
			return new SomlInt(value.value());
		}, _ -> new SideEffect(1))).build();
		var members = new LinkedHashMap<Object, Object>();
		members.put(1, 0);
		members.put("1", new SideEffect(1));
		var error = assertThrows(SomlException.class, () -> mapper.toValue(members));
		assertEquals(List.of("1"), error.path());
		assertEquals(0, calls[0]);
	}

	@TestFactory
	Stream<DynamicTest> utf8PrefixesDistinguishTruncationFromMalformedBytes() {
		return IntStream.of(0xC0, 0xC1, 0xC2, 0xDF, 0xE0, 0xE1, 0xED, 0xEF, 0xF0, 0xF1, 0xF4, 0xF5, 0xFF).boxed().flatMap(first -> IntStream.range(0, 256).mapToObj(second -> DynamicTest.dynamicTest("UTF-8 %02X %02X".formatted(first, second), () -> {
			var bytes = new byte[] {first.byteValue(), (byte) second};
			var continuation = second >= 0x80 && second <= 0xBF;
			var complete = first >= 0xC2 && first <= 0xDF && continuation;
			var prefix = continuation && ((first >= 0xE0 && first <= 0xEF && (first != 0xE0 || second >= 0xA0) && (first != 0xED || second <= 0x9F)) || (first >= 0xF0 && first <= 0xF4 && (first != 0xF0 || second >= 0x90) && (first != 0xF4 || second <= 0x8F)));
			if (complete) {
				assertEquals(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Parser.decode(bytes));
			} else {
				var error = assertThrows(SomlException.class, () -> Parser.decode(bytes));
				assertEquals(prefix ? "Incomplete UTF-8 sequence at the end of the input" : "Invalid UTF-8 byte 0x%02X".formatted(first), error.reason());
			}
		})));
	}

	@Test
	void emptyContainerPositionsRemainDistinctDuringErrorAggregation() {
		record Nested(int value) {}
		record Pair(Nested first, Nested second) {}
		var error = assertThrows(SomlException.class, () -> Soml.decode("first: {}\nsecond: {}", Pair.class));
		assertEquals(2, error.errors().size());
		assertEquals(1, error.errors().getFirst().line());
		assertEquals(2, error.errors().getLast().line());
		assertEquals(8, error.errors().getFirst().column());
		assertEquals(9, error.errors().getLast().column());
	}

	@Test
	void largePlainIntegersKeepTheirTypesThroughParsingAndStreaming() {
		for (var number : new long[] {1_000_000_000_000_000L, -1_000_000_000_000_000L, 999_999_999_999_999_999L, -999_999_999_999_999_999L, Long.MIN_VALUE, Long.MAX_VALUE}) {
			var text = "[" + number + "]";
			assertEquals(SomlArray.of(new SomlInt(number)), Soml.parse(text));
			assertEquals(number, Soml.decode(text, long[].class)[0]);
		}
		assertThrows(SomlException.class, () -> Soml.parse("[10000000000000000000]"));
	}

	@Test
	void fractionalDurationsReduceBeforeMultiplicationWithoutRounding() {
		assertEquals(SomlDuration.ofNanos(1), ((SomlArray) Soml.parse("[0.000000001s]")).get(0));
		assertEquals(SomlDuration.ofNanos(6), ((SomlArray) Soml.parse("[0.0000000001m]")).get(0));
		assertEquals(SomlDuration.ofNanos(36), ((SomlArray) Soml.parse("[0.00000000001h]")).get(0));
		assertEquals(SomlDuration.ofNanos(3_599_999_999_964L), ((SomlArray) Soml.parse("[0.99999999999h]")).get(0));
		for (var value : List.of("0.0000000000001h", "0.00000000001m", "0.0000000001s", "0.1ns")) {
			assertTrue(assertThrows(SomlException.class, () -> Soml.parse("[" + value + "]")).reason().contains("not a whole number of nanoseconds"));
		}
	}

	@TestFactory
	Stream<DynamicTest> primitiveArrayWritingKeepsExactRepresentations() {
		record Shape(String name, Object array, String items) {}
		return Stream.of(
			new Shape("int", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE}, "-2147483648\n\t0\n\t2147483647"),
			new Shape("long", new long[] {Long.MIN_VALUE, Long.MAX_VALUE}, "-9223372036854775808\n\t9223372036854775807"),
			new Shape("short", new short[] {Short.MIN_VALUE, Short.MAX_VALUE}, "-32768\n\t32767"),
			new Shape("byte", new byte[] {Byte.MIN_VALUE, Byte.MAX_VALUE}, "-128\n\t127"),
			new Shape("boolean", new boolean[] {true, false}, "true\n\tfalse"),
			new Shape("char", new char[] {'a', 'é', '\t'}, "'a'\n\t'é'\n\t\"\\t\""),
			new Shape("double", new double[] {Double.MIN_VALUE, -0.0, Double.POSITIVE_INFINITY}, "5e-324\n\t0.0\n\tinfinity"),
			new Shape("float", new float[] {Float.MIN_VALUE, -0.0f, 0.1f}, "1.4e-45\n\t0.0\n\t0.1")
		).map(shape -> DynamicTest.dynamicTest(shape.name(), () -> {
			assertEquals("[\n\t" + shape.items() + "\n]\n", Soml.encode(shape.array()));
			assertEquals(Soml.encode(Soml.toValue(shape.array())), Soml.encode(shape.array()));
			assertEquals("[]\n", Soml.encode(Array.newInstance(shape.array().getClass().getComponentType(), 0)));
		}));
	}

	@Test
	void primitiveArrayWritingKeepsAdapterPrecedenceAndErrorIndexes() {
		var mapper = SomlMapper.builder().adapter(Number.class, SomlAdapter.of(_ -> new SomlString("custom"), _ -> 1)).build();
		for (var array : List.of(new int[] {1, 2}, new long[] {1, 2}, new double[] {1, Double.NaN}, new float[] {1, Float.NaN})) {
			assertEquals("[\n\t'custom'\n\t'custom'\n]\n", mapper.encode(array));
		}
		for (var array : List.of(new double[] {1, Double.NaN}, new float[] {1, Float.NaN}, new char[] {'a', '\r'}, new char[] {'a', '\uD800'})) {
			assertEquals(List.of(1), assertThrows(SomlException.class, () -> Soml.encode(array)).path());
		}
	}

	@Test
	void fractionalDurationResultsAgreeWithIndependentArbitraryPrecisionArithmetic() {
		var units = List.of("h", "m", "s", "ms", "us", "ns");
		var sizes = new long[] {3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L, 1_000_000L, 1000L, 1L};
		var random = new java.util.Random(9926);
		for (var unit = 0; unit < units.size(); unit++) {
			for (var width = 1; width <= 13; width++) {
				var scale = BigInteger.TEN.pow(width);
				for (var sample = 0; sample < 20; sample++) {
					var digits = random.nextLong(scale.longValueExact());
					var fraction = Long.toString(digits);
					var text = "[0." + "0".repeat(width - fraction.length()) + fraction + units.get(unit) + "]";
					var division = BigInteger.valueOf(digits).multiply(BigInteger.valueOf(sizes[unit])).divideAndRemainder(scale);
					if (division[1].signum() == 0) {
						assertEquals(SomlDuration.ofNanos(division[0].longValueExact()), ((SomlArray) Soml.parse(text)).get(0), text);
					} else {
						assertTrue(assertThrows(SomlException.class, () -> Soml.parse(text)).reason().contains("not a whole number of nanoseconds"), text);
					}
				}
			}
		}
	}
}
