package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class BindingRoundTwoTest {
	@Test
	void directSinksKeepUnicodeAndCallerLifecycleAcrossLargeBuffers() throws IOException {
		var value = Map.of("text", "é中😀\t\n'".repeat(4000), "nested", List.of(Map.of("name", "last")));
		var mapper = SomlMapper.builder().build();
		var expected = mapper.encode(value);
		var received = new StringBuilder();
		var lifecycle = new ArrayList<String>();
		var writer = new Writer() {
			@Override
			public void write(char[] characters, int offset, int count) {
				assertEquals("inner: 1\n", mapper.encode(Map.of("inner", 1)));
				received.append(characters, offset, count);
			}

			@Override
			public void flush() {
				lifecycle.add("writer flush");
			}

			@Override
			public void close() {
				lifecycle.add("writer close");
			}
		};
		mapper.encode(value, writer);
		assertEquals(expected, received.toString());
		var bytes = new ByteArrayOutputStream();
		var stream = new OutputStream() {
			@Override
			public void write(int value) {
				bytes.write(value);
			}

			@Override
			public void write(byte[] source, int offset, int count) {
				assertEquals("inner: 1\n", mapper.encode(Map.of("inner", 1)));
				bytes.write(source, offset, count);
			}

			@Override
			public void flush() {
				lifecycle.add("stream flush");
			}

			@Override
			public void close() {
				lifecycle.add("stream close");
			}
		};
		mapper.encode(value, stream);
		assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), bytes.toByteArray());
		assertEquals(List.of(), lifecycle);
	}

	@Test
	void jdkToolModulesCannotSilentlyBecomeEmptyObjectsAndStillAllowAdapters() {
		var tool = java.util.spi.ToolProvider.findFirst("javap").orElseThrow();
		var error = assertThrows(SomlException.class, () -> Soml.toValue(tool));
		assertTrue(error.reason().contains("it is a JDK class"));
		assertThrows(SomlException.class, () -> Soml.decode("{}", tool.getClass()));
		var mapper = SomlMapper.builder().adapter(java.util.spi.ToolProvider.class, SomlAdapter.of(value -> new SomlString(value.name()), _ -> tool)).build();
		assertEquals(new SomlString("javap"), mapper.toValue(tool));
		assertSame(tool, mapper.fromValue(new SomlString("javap"), tool.getClass()));
	}

	sealed interface Variant permits TaggedValue {}

	record TaggedValue(int number, String name) implements Variant {}

	@Test
	void taggedTreeDecodingKeepsStrictAndLenientKeyPoliciesAtEveryTagPosition() {
		var expected = new TaggedValue(4, "value");

		for (var text : List.of("type: 'TaggedValue'\nnumber: 4\nname: 'value'", "number: 4\ntype: 'TaggedValue'\nname: 'value'", "number: 4\nname: 'value'\ntype: 'TaggedValue'")) {
			var tree = Soml.parse(text);

			for (var strict : List.of(false, true)) {
				var mapper = SomlMapper.builder().rejectUnknownKeys(strict).build();
				assertEquals(expected, mapper.fromValue(tree, Variant.class));
				assertEquals(expected, mapper.fromValue(tree, TaggedValue.class));
				assertEquals(expected, mapper.decode(text, Variant.class));
			}

			var unknown = Soml.parse(text + "\nunknown: 1");
			assertEquals(expected, Soml.fromValue(unknown, Variant.class));
			var error = assertThrows(SomlException.class, () -> SomlMapper.builder().rejectUnknownKeys(true).build().fromValue(unknown, Variant.class));
			assertEquals(List.of("unknown"), error.path());
			var wrong = assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text.replace("TaggedValue", "Other")), TaggedValue.class));
			assertEquals(List.of("type"), wrong.path());
		}
	}

	record LateFailure(String name, List<Double> values) {}

	@Test
	void lateConversionFailuresWriteNothingAndReleaseTheBuffer() throws IOException {
		var invalid = new LateFailure("valid".repeat(10_000), List.of(1.0, Double.NaN));
		var mapper = SomlMapper.builder().build();
		var writer = new StringWriter();
		var stream = new ByteArrayOutputStream();
		var textError = assertThrows(SomlException.class, () -> mapper.encode(invalid, writer));
		var byteError = assertThrows(SomlException.class, () -> mapper.encode(invalid, stream));
		assertEquals(List.of("values", 1), textError.path());
		assertEquals(textError.path(), byteError.path());
		assertEquals(textError.reason(), byteError.reason());
		assertEquals("", writer.toString());
		assertEquals(0, stream.size());
		mapper.encode(Map.of("valid", 2), writer);
		mapper.encode(Map.of("valid", 2), stream);
		assertEquals("valid: 2\n", writer.toString());
		assertEquals(writer.toString(), stream.toString(StandardCharsets.UTF_8));
	}

	@Test
	void sinkIoFailuresKeepTheirIdentityAndDoNotCorruptLaterEncoding() {
		var failure = new IOException("deliberate sink failure");
		var writer = new Writer() {
			@Override
			public void write(char[] characters, int offset, int count) throws IOException {
				throw failure;
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		};
		var stream = new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				throw failure;
			}
		};
		assertSame(failure, assertThrows(IOException.class, () -> Soml.encode(Map.of("key", "value"), writer)));
		assertSame(failure, assertThrows(IOException.class, () -> Soml.encode(Map.of("key", "value"), stream)));
		assertEquals("key: 'value'\n", Soml.encode(Map.of("key", "value")));
	}

	@Test
	void childCodecCachesKeepMapperAdaptersIsolatedAcrossNestedContainers() {
		var type = new TypeReference<Map<String, List<Map<String, String>>>>() {};
		var first = SomlMapper.builder().adapter(String.class, SomlAdapter.of(SomlString::new, value -> "first:" + ((SomlString) value).value())).build();
		var second = SomlMapper.builder().adapter(String.class, SomlAdapter.of(SomlString::new, value -> "second:" + ((SomlString) value).value())).build();
		var text = "items: [{name: 'value'}]";
		var tree = Soml.parse(text);

		for (var attempt = 0; attempt < 20; attempt++) {
			assertEquals(Map.of("items", List.of(Map.of("name", "first:value"))), first.decode(text, type));
			assertEquals(Map.of("items", List.of(Map.of("name", "second:value"))), second.fromValue(tree, type));
			assertEquals(Map.of("items", List.of(Map.of("name", "first:value"))), first.fromValue(tree, type));
			assertEquals(Map.of("items", List.of(Map.of("name", "second:value"))), second.decode(text, type));
		}
	}

	@Test
	void builderAdapterAllocationKeepsEarlierMappersImmutable() {
		var builder = SomlMapper.builder();
		var plain = builder.build();
		builder.adapter(String.class, SomlAdapter.of(value -> new SomlString("first:" + value), value -> ((SomlString) value).value()));
		var first = builder.build();
		builder.adapter(String.class, SomlAdapter.of(value -> new SomlString("second:" + value), value -> ((SomlString) value).value()));
		var second = builder.build();
		assertEquals("text: 'value'\n", plain.encode(Map.of("text", "value")));
		assertEquals("text: 'first:value'\n", first.encode(Map.of("text", "value")));
		assertEquals("text: 'second:value'\n", second.encode(Map.of("text", "value")));
	}

	record Adapted(int integer, long wide, short small, byte tiny, float ratio, double precise) {}

	@Test
	void cachedPrimitiveAdapterTargetsKeepExactTypesAndNullRejection() {
		var mapper = SomlMapper.builder().adapter(Number.class, SomlAdapter.of(value -> new SomlString(value.toString()), value -> {
			var text = ((SomlString) value).value();
			return switch (text.charAt(0)) {
				case 'i' -> Integer.valueOf(text.substring(1));
				case 'l' -> Long.valueOf(text.substring(1));
				case 's' -> Short.valueOf(text.substring(1));
				case 'b' -> Byte.valueOf(text.substring(1));
				case 'f' -> Float.valueOf(text.substring(1));
				default -> Double.valueOf(text.substring(1));
			};
		})).build();
		var text = "integer: 'i1'\nwide: 'l2'\nsmall: 's3'\ntiny: 'b4'\nratio: 'f0.5'\nprecise: 'd0.25'";
		var expected = new Adapted(1, 2, (short) 3, (byte) 4, 0.5f, 0.25);
		assertEquals(expected, mapper.decode(text, Adapted.class));
		assertEquals(expected, mapper.fromValue(Soml.parse(text), Adapted.class));
		var wrongType = assertThrows(SomlException.class, () -> mapper.decode(text.replace("'l2'", "'i2'"), Adapted.class));
		assertEquals(List.of("wide"), wrongType.path());
		var nullValue = assertThrows(SomlException.class, () -> mapper.decode(text.replace("'l2'", "null"), Adapted.class));
		assertEquals(List.of("wide"), nullValue.path());
	}

	@Test
	@SuppressWarnings("unchecked")
	void untypedEmptyContainersStayImmutableWithinNestedAndNullValues() {
		for (var text : List.of("[]", "{}", "[[], {}, null]", "items: []\nmetadata: {}\nmissing: null")) {
			var tree = Soml.parse(text);
			var streamed = Soml.decode(text, Object.class);
			var converted = Soml.fromValue(tree, Object.class);
			assertEquals(converted, streamed);
			assertEquals(tree, Soml.toValue(streamed));

			if (streamed instanceof List<?> list) {
				assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) list).add("extra"));
			} else if (streamed instanceof Map<?, ?> map) {
				assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) map).put("extra", 1));
			}
		}
	}

	@Test
	void primitiveOptionalEncodingKeepsExtremesEmptyValuesAndErrorPaths() {
		var values = List.of(OptionalInt.of(Integer.MIN_VALUE), OptionalInt.empty(), OptionalLong.of(Long.MAX_VALUE), OptionalLong.empty(), OptionalDouble.of(-0.0), OptionalDouble.of(Double.POSITIVE_INFINITY), OptionalDouble.empty());
		var expected = Soml.parse("[-2147483648, null, 9223372036854775807, null, 0.0, infinity, null]");
		assertEquals(expected, Soml.toValue(values));
		assertEquals(expected, Soml.parse(Soml.encode(values)));
		var invalid = List.of(OptionalInt.of(1), OptionalDouble.of(Double.NaN));
		assertEquals(List.of(1), assertThrows(SomlException.class, () -> Soml.toValue(invalid)).path());
		assertEquals(List.of(1), assertThrows(SomlException.class, () -> Soml.encode(invalid)).path());
		var adapted = SomlMapper.builder().adapter(OptionalLong.class, SomlAdapter.of(_ -> new SomlString("override"), _ -> OptionalLong.of(2))).build();
		assertEquals(Soml.parse("['override']"), adapted.toValue(List.of(OptionalLong.of(1))));
		assertEquals("[\n\t'override'\n]\n", adapted.encode(List.of(OptionalLong.of(1))));
	}

	@Test
	void integralFloatFastPathKeepsShortestDecimalsAcrossItsBoundaries() {
		var values = new float[] {-0.0f, 0.0f, -1f, 1f, -0x1p24f, 0x1p24f, Math.nextDown(-0x1p24f), Math.nextUp(0x1p24f), 0.1f, Float.MIN_VALUE, Float.MAX_VALUE, Float.POSITIVE_INFINITY};
		var expected = new ArrayList<SomlValue>();

		for (var value : values) {
			expected.add(new SomlFloat(Double.parseDouble(Float.toString(value))));
		}

		var tree = new SomlArray(expected);
		assertEquals(tree, Soml.toValue(values));
		assertEquals(tree, Soml.parse(Soml.encode(values)));
		assertEquals(List.of(0), assertThrows(SomlException.class, () -> Soml.encode(new float[] {Float.NaN})).path());
	}

	@Test
	void characterWritingMatchesStringQuotingForPrimitiveAndBoxedValues() {
		var characters = new char[] {'a', ' ', '\\', '"', '\'', '\0', '\t', '\n', '\u007F', '\u0085', 'é', '中', '\uE000', '\uFFFF'};
		var boxed = new ArrayList<Character>();
		var strings = new ArrayList<String>();

		for (var character : characters) {
			boxed.add(character);
			strings.add(Character.toString(character));
		}

		var expected = Soml.encode(strings);
		assertEquals(expected, Soml.encode(characters));
		assertEquals(expected, Soml.encode(boxed));
		assertEquals(Soml.toValue(strings), Soml.parse(Soml.encode(characters)));
		var mapper = SomlMapper.builder().adapter(Character.class, SomlAdapter.of(character -> new SomlString("changed:" + character), _ -> 'x')).build();
		assertEquals(mapper.encode(boxed), mapper.encode(characters));
		assertTrue(mapper.encode(characters).contains("'changed:a'"));
		var unrelated = SomlMapper.builder().adapter(Integer.class, SomlAdapter.of((Integer number) -> new SomlInt(number), _ -> 1)).build();
		assertEquals(expected, unrelated.encode(characters));
		assertEquals(expected, unrelated.encode(boxed));
	}

	@Test
	void invalidCharactersKeepOriginalReasonsCausesAndIndexPaths() {
		for (var invalid : new char[] {'\r', '\uD800', '\uDC00'}) {
			var primitive = new char[] {'a', invalid};
			var boxed = List.of('a', invalid);
			var expected = assertThrows(SomlException.class, () -> Soml.toValue(boxed));

			for (var value : List.of(primitive, boxed)) {
				var actual = assertThrows(SomlException.class, () -> Soml.encode(value));
				assertEquals(expected.reason(), actual.reason());
				assertEquals(List.of(1), actual.path());
				assertEquals(expected.getCause().getClass(), actual.getCause().getClass());
			}
		}
	}

	@Test
	void emptyJavaMapsKeepDocumentNestingCanonicalAndAdapterBehavior() {
		var mapper = SomlMapper.builder().rejectUnknownKeys(true).build();
		var empty = new java.util.LinkedHashMap<String, Object>();
		assertEquals("{}\n", mapper.encode(empty));
		assertEquals("{}\n", mapper.encodeCanonical(empty));
		assertEquals("[\n\t{}\n]\n", mapper.encode(List.of(empty)));
		assertEquals(mapper.encode(List.of(empty)), mapper.encodeCanonical(List.of(empty)));
		var adapted = SomlMapper.builder().adapter(Map.class, SomlAdapter.of(_ -> new SomlObject(Map.of("adapted", SomlBool.of(true))), _ -> Map.of())).build();
		assertEquals("adapted: true\n", adapted.encode(empty));
		assertEquals("adapted: true\n", adapted.encodeCanonical(empty));
		Object tooDeep = empty;

		for (var depth = 0; depth < sh.soml.Writer.MAX_DEPTH; depth++) {
			tooDeep = List.of(tooDeep);
		}

		var invalid = tooDeep;
		assertThrows(SomlException.class, () -> mapper.encode(invalid));
		assertThrows(SomlException.class, () -> mapper.encodeCanonical(invalid));
	}

	@Test
	void sharedContainerFailureHandlingKeepsEveryOriginalValueAndPath() {
		var text = "items: [null, null]";
		var queues = new TypeReference<Map<String, java.util.Deque<Integer>>>() {};
		var streamError = assertThrows(SomlException.class, () -> Soml.decode(text, queues));
		var treeError = assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), queues));
		assertEquals(List.of(List.of("items", 0), List.of("items", 1)), streamError.errors().stream().map(SomlException::path).toList());
		assertEquals(streamError.errors().stream().map(SomlException::reason).toList(), treeError.errors().stream().map(SomlException::reason).toList());
		var maps = new TypeReference<ConcurrentHashMap<String, Integer>>() {};
		var mapError = assertThrows(SomlException.class, () -> Soml.decode("first: null\nsecond: null", maps));
		assertEquals(List.of(List.of("first"), List.of("second")), mapError.errors().stream().map(SomlException::path).toList());
	}
}
