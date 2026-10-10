package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Spliterator;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class HardeningTest {
	interface Parent {}

	record DuplicateAdapters(int value) implements Parent {
		static final SomlAdapter<DuplicateAdapters> FIRST = SomlAdapter.of(_ -> new SomlInt(1), _ -> new DuplicateAdapters(1));
		static final SomlAdapter<DuplicateAdapters> SECOND = FIRST;
	}

	record NullAdapter(int value) implements Parent {
		static final SomlAdapter<NullAdapter> ADAPTER = null;
	}

	@Test
	void registeredAncestorAdapterOverridesDuplicateOwnAdapters() {
		var mapper = SomlMapper.builder().adapter(Parent.class, SomlAdapter.of(_ -> new SomlInt(7), _ -> new DuplicateAdapters(7))).build();
		assertEquals(new SomlInt(7), mapper.toValue(new DuplicateAdapters(3)));
		assertEquals(new DuplicateAdapters(7), mapper.fromValue(new SomlInt(7), DuplicateAdapters.class));
		assertEquals("[\n\t7\n]\n", mapper.encode(List.of(new DuplicateAdapters(3))));
		assertEquals(List.of(new DuplicateAdapters(7)), mapper.decode("[7]", new TypeReference<List<DuplicateAdapters>>() {}));
	}

	@Test
	void registeredAncestorAdapterOverridesNullOwnAdapter() {
		var mapper = SomlMapper.builder().adapter(Parent.class, SomlAdapter.of(_ -> new SomlInt(7), _ -> new NullAdapter(7))).build();
		assertEquals(new SomlInt(7), mapper.toValue(new NullAdapter(3)));
		assertEquals(new NullAdapter(7), mapper.fromValue(new SomlInt(7), NullAdapter.class));
	}

	@Test
	void invalidOwnAdaptersStillFailWithoutARegisteredOverride() {
		assertTrue(assertThrows(SomlException.class, () -> Soml.toValue(new DuplicateAdapters(3))).reason().contains("two SomlAdapter fields"));
		assertTrue(assertThrows(SomlException.class, () -> Soml.toValue(new NullAdapter(3))).reason().contains("is null"));
	}

	@TestFactory
	Stream<DynamicTest> readersConsumeOnlyOneCharacterBeyondTheirLimit() {
		return Stream.of(0, 1, 10, 8192, 8193).map(limit -> DynamicTest.dynamicTest("reader limit " + limit, () -> {
			var input = new CountingReader();
			var mapper = SomlMapper.builder().maxDocumentLength(limit).build();
			var error = assertThrows(SomlException.class, () -> mapper.decode(input, Object.class));
			assertEquals("The document is longer than the limit of " + limit + " characters", error.reason());
			assertEquals(limit + 1, input.count);
			assertFalse(input.closed);
		}));
	}

	private static final class CountingReader extends Reader {
		int count;
		boolean closed;

		@Override
		public int read(char[] buffer, int offset, int length) {
			Arrays.fill(buffer, offset, offset + length, 'a');
			count += length;
			return length;
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	@TestFactory
	Stream<DynamicTest> noncanonicalIntegerMapKeysAreRejected() {
		return Stream.of("", "+1", "01", "-01", "-0", " 1", "1 ", "١", "１２", "1x", "--1").map(key -> DynamicTest.dynamicTest("integer key " + Parser.quoteJson(key), () -> {
			var tree = new SomlObject(Map.of(key, new SomlInt(1)));
			for (var type : List.of(new TypeReference<Map<Long, Integer>>() {}, new TypeReference<Map<BigInteger, Integer>>() {})) {
				var error = assertThrows(SomlException.class, () -> Soml.fromValue(tree, type));
				assertTrue(error.reason().startsWith("Expected a key with a decimal integer"));
				assertEquals(List.of(key), error.path());
				assertNull(error.getCause());
				assertEquals(error.reason(), assertThrows(SomlException.class, () -> Soml.decode(Soml.encode(tree), type)).reason());
			}
		}));
	}

	@Test
	void hugeNoncanonicalIntegerKeysHaveBoundedErrors() {
		var key = "0" + "1".repeat(100_000);
		var error = assertThrows(SomlException.class, () -> Soml.decode(key + ": 1", new TypeReference<Map<BigInteger, Integer>>() {}));
		assertTrue(error.getMessage().length() < 400);
		assertEquals(List.of(key), error.path());
		assertNull(error.getCause());
	}

	@Test
	void bigIntegerKeysKeepTheirUnboundedMagnitude() {
		var key = "9".repeat(1000);
		var expected = Map.of(new BigInteger(key), 1);
		var type = new TypeReference<Map<BigInteger, Integer>>() {};
		assertEquals(expected, Soml.decode(key + ": 1", type));
		assertEquals(expected, Soml.fromValue(Soml.parse(key + ": 1"), type));
		assertEquals(key + ": 1\n", Soml.encode(expected));
	}

	@Test
	void replacementCharactersDoNotHideMalformedUtf8AfterThem() {
		var prefix = "a: '�😀'\nb: '";
		var bytes = Arrays.copyOf(prefix.getBytes(StandardCharsets.UTF_8), prefix.getBytes(StandardCharsets.UTF_8).length + 1);
		bytes[bytes.length - 1] = (byte) 0xFF;
		var error = assertThrows(SomlException.class, () -> Soml.parse(bytes));
		assertEquals("Invalid UTF-8 byte 0xFF", error.reason());
		assertEquals(2, error.line());
		assertEquals(5, error.column());
	}

	@Test
	void replacementCharactersAreValidUtf8Values() {
		var bytes = "a: '�😀�'".getBytes(StandardCharsets.UTF_8);
		assertEquals(new SomlObject(Map.of("a", new SomlString("�😀�"))), Soml.parse(bytes));
		assertEquals(Map.of("a", "�😀�"), Soml.decode(bytes, Object.class));
	}

	@Test
	void objectViewsAdvertiseTheirImmutableEncounterOrder() {
		var members = ((SomlObject) Soml.parse("z: 1\na: 2\nb: 1")).members();
		var characteristics = Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.SIZED | Spliterator.SUBSIZED;
		assertTrue(members.keySet().spliterator().hasCharacteristics(characteristics | Spliterator.DISTINCT));
		assertTrue(members.entrySet().spliterator().hasCharacteristics(characteristics | Spliterator.DISTINCT));
		assertTrue(members.values().spliterator().hasCharacteristics(characteristics));
		assertFalse(members.values().spliterator().hasCharacteristics(Spliterator.DISTINCT));
		assertEquals(List.of("z", "a", "b"), members.keySet().parallelStream().toList());
		assertEquals("z", members.entrySet().parallelStream().findFirst().orElseThrow().getKey());
	}

	@Test
	void namingStrategiesRejectNullConsistently() {
		for (var strategy : KeyNaming.values()) {
			assertThrows(NullPointerException.class, () -> strategy.apply(null));
		}
	}

	@Test
	void longCodeFramesSanitizeOnlyTheDisplayedWindow() {
		var source = "a".repeat(100_000) + "\u001B\u202E😀" + "z".repeat(100_000);
		var error = SomlException.at("bad\u001B\u202E", source, 100_004);
		assertEquals("bad��", error.reason());
		assertEquals(100_004, error.column());
		assertTrue(error.codeFrame().contains("��😀"));
		assertTrue(error.codeFrame().length() < 180);
		assertFalse(error.getMessage().contains("\u001B"));
		assertFalse(error.getMessage().contains("\u202E"));
	}

	@Test
	void codeFrameWindowsNeverSplitValidSurrogatePairs() {
		var source = "a".repeat(49) + "😀" + "b".repeat(48) + "😀" + "c".repeat(100);
		var error = SomlException.at("bad", source, 100);
		assertFalse(error.codeFrame().contains("�"));
		assertEquals(100, error.column());
		assertTrue(error.codeFrame().startsWith("> 1 | …"));
	}

	@Test
	void codeFrameWindowsReplaceLoneSurrogatesAtTheirEdges() {
		var source = "a".repeat(50) + "\uDC00" + "b".repeat(99) + "\uDC00" + "c".repeat(100);
		var error = SomlException.at("bad", source, 100);
		assertTrue(error.codeFrame().startsWith("> 1 | …�"));
		assertFalse(error.codeFrame().contains("\uDC00"));
	}

	@Test
	void utf8ValidationAgreesWithTheJdkForArbitraryByteSequences() {
		var random = new Random(904);
		var decoder = StandardCharsets.UTF_8.newDecoder();

		for (var attempt = 0; attempt < 10_000; attempt++) {
			var bytes = new byte[random.nextInt(1, 12)];
			random.nextBytes(bytes);
			String expected;

			try {
				expected = decoder.decode(ByteBuffer.wrap(bytes)).toString();
			} catch (CharacterCodingException _) {
				assertThrows(SomlException.class, () -> Parser.decode(bytes));
				continue;
			}

			assertEquals(expected, Parser.decode(bytes));
		}
	}

	@Test
	void shortReaderChunksPreserveSplitSurrogatePairsAtTheExactLimit() throws IOException {
		var text = "a: '😀'";
		var input = new StringReader(text) {
			@Override
			public int read(char[] buffer, int offset, int length) throws IOException {
				return super.read(buffer, offset, Math.min(1, length));
			}
		};
		var mapper = SomlMapper.builder().maxDocumentLength(text.length()).build();
		assertEquals(Map.of("a", "😀"), mapper.decode(input, Map.class));
	}

	@Test
	void largeReaderLimitsDoNotOverflowTheRequestedReadLength() throws IOException {
		for (var limit : new int[] {Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
			var mapper = SomlMapper.builder().maxDocumentLength(limit).build();
			assertEquals(Map.of(), mapper.decode(new StringReader("{}"), Map.class));
		}
	}

	@Test
	void inputIoExceptionsPropagateUnchanged() {
		var expected = new IOException("unreadable");
		var reader = new Reader() {
			@Override
			public int read(char[] buffer, int offset, int length) throws IOException {
				throw expected;
			}

			@Override
			public void close() {}
		};
		var input = new InputStream() {
			@Override
			public int read() throws IOException {
				throw expected;
			}
		};

		for (var mapper : List.of(SomlMapper.DEFAULT, SomlMapper.builder().maxDocumentLength(10).build())) {
			assertSame(expected, assertThrows(IOException.class, () -> mapper.decode(reader, Object.class)));
			assertSame(expected, assertThrows(IOException.class, () -> mapper.decode(input, Object.class)));
		}
	}

	@Test
	void outputIoExceptionsPropagateUnchanged() {
		var expected = new IOException("unwritable");
		var writer = new java.io.Writer() {
			@Override
			public void write(char[] buffer, int offset, int length) throws IOException {
				throw expected;
			}

			@Override
			public void flush() {}

			@Override
			public void close() {}
		};
		var output = new OutputStream() {
			@Override
			public void write(int value) throws IOException {
				throw expected;
			}
		};
		assertSame(expected, assertThrows(IOException.class, () -> Soml.encode(Map.of("a", 1), output)));
		assertSame(expected, assertThrows(IOException.class, () -> Soml.encode(Map.of("a", 1), writer)));
	}

	@Test
	void invalidMapEncodingWritesNothingAndLeavesThePoolUsable() throws IOException {
		var map = new LinkedHashMap<String, Object>();
		map.put("first", "x".repeat(8192));
		map.put("second", List.of(Double.NaN));
		var output = new ByteArrayOutputStream();
		var error = assertThrows(SomlException.class, () -> Soml.encode(map, output));
		assertEquals(List.of("second", 0), error.path());
		assertEquals(0, output.size());
		assertEquals("ok: 1\n", Soml.encode(Map.of("ok", 1)));
	}

	@Test
	void directMapEncodingKeepsOmissionNullAndNestedOrder() {
		var map = new LinkedHashMap<String, Object>();
		map.put("omitted", Optional.empty());
		map.put("null", null);
		map.put("nested", new LinkedHashMap<>(Map.of("a", 1)));
		map.put("last", Optional.of("'\n😀"));
		var original = new ArrayList<>(map.keySet());
		assertEquals(Soml.encode(Soml.toValue(map)), Soml.encode(map));
		assertEquals(Soml.encodeCanonical(Soml.toValue(map)), Soml.encodeCanonical(map));
		assertEquals(original, new ArrayList<>(map.keySet()));
		assertEquals("{}\n", Soml.encode(Map.of("a", Optional.empty())));
	}

	@Test
	void trustedCollectionEncodingCopiesTheInputAndKeepsNullItems() {
		var items = new ArrayList<Object>(Arrays.asList(1, null, Optional.empty(), "😀"));
		var tree = (SomlArray) Soml.toValue(items);
		items.clear();
		assertEquals(SomlArray.of(new SomlInt(1), SomlNull.INSTANCE, SomlNull.INSTANCE, new SomlString("😀")), tree);
		assertThrows(UnsupportedOperationException.class, () -> tree.items().set(0, SomlNull.INSTANCE));
	}

	@Test
	void blockStringsDiscardLargeBlankLinesBetweenShortContentLines() {
		for (var quote : List.of("'''", "\"\"\"")) {
			var source = "a: " + quote + "\nX\n" + " \t".repeat(100_000) + "\nY\n" + quote;
			var expected = new SomlObject(Map.of("a", new SomlString("X\n\nY")));
			assertEquals(expected, Soml.parse(source));
			assertEquals(Map.of("a", "X\n\nY"), Soml.decode(source, Object.class));
			assertEquals(expected, Soml.parse(Soml.format(source)));
		}
	}

	@Test
	void reentrantAdaptersDoNotOverwriteOuterEncodingBuffers() {
		record Wrapped(int number) {}
		var mapper = SomlMapper.builder().adapter(Wrapped.class, SomlAdapter.of(value -> {
			assertEquals("nested: 2\n", Soml.encode(Map.of("nested", value.number())));
			return new SomlInt(value.number());
		}, value -> new Wrapped((int) ((SomlInt) value).value()))).build();
		assertEquals("[\n\t1\n\t2\n\t3\n]\n", mapper.encode(List.of(1, new Wrapped(2), 3)));
	}

	@Test
	void pooledParsingAndWritingRemainIsolatedAcrossVirtualThreads() throws Exception {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = new ArrayList<java.util.concurrent.Future<?>>();

			for (var index = 0; index < 64; index++) {
				var number = index;
				tasks.add(executor.submit(() -> {
					var expected = Map.of("id", number, "text", "😀'\n" + number);
					for (var iteration = 0; iteration < 20; iteration++) {
						var encoded = Soml.encode(expected);
						assertEquals(Soml.toValue(expected), Soml.parse(encoded));
						assertEquals(Soml.encodeCanonical(expected), Soml.encodeCanonical(Soml.parse(encoded)));
						assertThrows(SomlException.class, () -> Soml.parse("a: ["));
						assertNotNull(Soml.decode(encoded, Object.class));
					}
				}));
			}

			for (var task : tasks) {
				task.get();
			}
		}
	}
}
