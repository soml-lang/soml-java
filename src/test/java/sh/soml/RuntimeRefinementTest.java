package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RuntimeRefinementTest {
	@Test
	void floatOutputMatchesAtNotationBoundariesAndAcrossEncodingRoutes() {
		var values = new ArrayList<SomlValue>();

		for (var boundary : new double[] {0, Double.MIN_VALUE, Double.MIN_NORMAL, 1e-7, 1e-6, 1e-3, 1e7, 1e20, 1e21, Double.MAX_VALUE}) {
			for (var number : new double[] {Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
				values.add(new SomlFloat(number));
				values.add(new SomlFloat(-number));
			}
		}

		var random = new Random(0x50_4D_4C);

		for (var index = 0; index < 10_000; index++) {
			var number = Double.longBitsToDouble(random.nextLong());

			if (!Double.isNaN(number)) {
				values.add(new SomlFloat(number));
			}
		}

		var numbers = values.stream().mapToDouble(value -> ((SomlFloat) value).value()).toArray();
		var text = Soml.encode(numbers);
		assertEquals(values, ((SomlArray) Soml.parse(text)).items());
		assertEquals(text, Soml.encode(new SomlArray(values)));
		assertEquals(text, Soml.encode(values.stream().map(value -> ((SomlFloat) value).value()).toList()));
		assertEquals(text, Soml.encodeCanonical(new SomlArray(values)));

		for (var value : values) {
			assertEquals(value, ((SomlArray) Soml.parse("[" + value + "]")).get(0));
		}
	}

	@Test
	void durationWritingMatchesAnIndependentIntegerOracle() {
		var random = new Random(812);

		for (var index = 0; index < 5000; index++) {
			var nanoseconds = switch (index) {
				case 0 -> Long.MIN_VALUE;
				case 1 -> Long.MAX_VALUE;
				case 2 -> 0L;
				default -> random.nextLong();
			};
			var value = SomlDuration.ofNanos(nanoseconds);
			var text = durationText(nanoseconds);
			assertEquals(text, value.toString());
			assertEquals("[\n\t" + text + "\n]\n", Soml.encode(List.of(Duration.ofNanos(nanoseconds))));
			assertEquals(value, ((SomlArray) Soml.parse("[" + text + "]")).get(0));
		}
	}

	private static String durationText(long nanoseconds) {
		if (nanoseconds == 0) {
			return "0s";
		}

		var magnitude = BigInteger.valueOf(nanoseconds).abs();
		var text = new StringBuilder(nanoseconds < 0 ? "-" : "");
		var sizes = new long[] {3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L};
		var units = new char[] {'h', 'm', 's'};

		for (var index = 0; index < sizes.length; index++) {
			var parts = magnitude.divideAndRemainder(BigInteger.valueOf(sizes[index]));
			magnitude = parts[1];

			if (parts[0].signum() == 0 && (index < 2 || magnitude.signum() == 0)) {
				continue;
			}

			text.append(parts[0]);

			if (index == 2 && magnitude.signum() > 0) {
				var fraction = "%09d".formatted(magnitude);
				var end = fraction.length();

				while (fraction.charAt(end - 1) == '0') {
					end--;
				}

				text.append('.').append(fraction, 0, end);
			}

			text.append(units[index]);
		}

		return text.toString();
	}

	@Test
	void stringEscapesKeepEveryRepresentableAsciiCharacter() {
		var raw = new StringBuilder();

		for (var character = 0; character < 128; character++) {
			if (character != '\r') {
				raw.append((char) character);
			}
		}

		var string = raw.toString();
		var text = Soml.encode(List.of(string, "'", "\\", "😀"));
		assertEquals(List.of(string, "'", "\\", "😀"), Soml.decode(text, new TypeReference<List<String>>() {}));
		assertTrue(text.contains("\\u{0}"));
		assertTrue(text.contains("\\u{10}"));
		assertTrue(text.contains("\\u{1f}"));
		assertTrue(text.contains("\\u{7f}"));
		assertFalse(text.contains("\\u{0a}"));
		assertEquals(SomlArray.of(new SomlString(string), new SomlString("'"), new SomlString("\\"), new SomlString("😀")), Soml.parse(text));
	}

	@Test
	void codeFramesKeepEmptyLinesGutterWidthsAndClippedSurrogatePairs() {
		var source = "x\n\n\n\n\n\n\n\n\n\t😀\u0085bad";
		var error = SomlException.at("bad\u202Ereason", source, source.indexOf("bad"));
		assertEquals(10, error.line());
		assertEquals(4, error.column());
		assertEquals("   8 |\n   9 |\n> 10 | \t😀�bad\n     | \t  ^", error.codeFrame());
		assertEquals("bad�reason", error.reason());

		var longLine = "😀".repeat(100) + "x" + "😀".repeat(100);
		var clipped = SomlException.at("bad", longLine, 200);
		assertEquals(101, clipped.column());
		assertFalse(clipped.codeFrame().contains("�"));
		assertTrue(clipped.codeFrame().startsWith("> 1 | …"));
		assertTrue(clipped.codeFrame().contains("…\n"));
	}

	@Test
	void sameOrderObjectEqualityStillChecksValuesAndCrossMapContracts() {
		var members = new LinkedHashMap<String, SomlValue>();

		for (var index = 0; index < 100; index++) {
			members.put("key" + index, SomlArray.of(new SomlInt(index)));
		}

		var object = new SomlObject(members);
		var same = (SomlObject) Soml.parse(Soml.encode(object));
		assertEquals(object, same);
		assertEquals(object.hashCode(), same.hashCode());
		assertEquals(object.members(), members);
		assertNull(object.members().getOrDefault("missing", null));
		assertEquals(SomlArray.of(new SomlInt(0)), object.members().getOrDefault("key0", null));
		assertEquals(new ArrayList<>(object.members().values()).hashCode(), object.members().values().hashCode());
		members.put("key99", SomlNull.INSTANCE);
		assertFalse(object.equals(new SomlObject(members)));
	}

	@Test
	void failedEditsLeaveTextAndValueTogetherAndRemainEditable() {
		var original = "# note\na: [{n: 1}] # tail\nb: 'kept'\n";
		var document = SomlDocument.parse(original);
		var value = document.value();
		assertThrows(SomlException.class, () -> document.set(List.of("a", 4), 2));
		assertThrows(SomlException.class, () -> document.set(List.of("a", 0, "n"), Double.NaN));
		assertEquals(original, document.toString());
		assertSame(value, document.value());
		assertFalse(document.remove(List.of("missing")));
		assertSame(value, document.value());
		document.set(List.of("a", 0, "n"), 3);
		assertEquals(new SomlInt(3), document.get(List.of("a", 0, "n")));
		assertEquals(Soml.parse(document.toString()), document.value());
		assertTrue(document.toString().contains("# tail"));
		assertTrue(document.remove(List.of("a", 0)));
		assertEquals(Soml.parse(document.toString()), document.value());
	}

	record Numbers(int first, List<Integer> nested, int last) {}

	@Test
	void positionedFallbackKeepsNestedErrorsAndSyntaxErrorPriority() {
		var text = "first: 'x'\nnested: [1, 'y', null]\nlast: 'z'";
		var error = assertThrows(SomlException.class, () -> Soml.decode(text, Numbers.class));
		assertEquals(List.of(List.of("first"), List.of("nested", 1), List.of("last")), error.errors().stream().map(SomlException::path).toList());
		assertEquals(List.of(1, 2, 3), error.errors().stream().map(SomlException::line).toList());
		assertEquals(List.of(8, 13, 7), error.errors().stream().map(SomlException::column).toList());
		var syntax = assertThrows(SomlException.class, () -> Soml.decode(text + "\nbroken: [", Numbers.class));
		assertEquals(List.of(), syntax.path());
		assertEquals(1, syntax.errors().size());
		assertEquals(4, syntax.line());
	}

	@Test
	void lengthLimitsCountFragmentedBytesAndUtf16ReaderCharacters() throws Exception {
		var text = "a: '😀é'";
		var bytes = text.getBytes(StandardCharsets.UTF_8);
		var mapper = SomlMapper.builder().maxDocumentLength(text.length()).build();
		assertEquals(Map.of("a", "😀é"), mapper.decode(Reader.of(text), Map.class));
		assertThrows(SomlException.class, () -> mapper.decode(Reader.of(text + " "), Map.class));
		assertThrows(SomlException.class, () -> mapper.decode(bytes, Map.class));
		var byteMapper = SomlMapper.builder().maxDocumentLength(bytes.length).build();
		var fragmented = new ByteArrayInputStream(bytes) {
			@Override
			public synchronized int read(byte[] destination, int offset, int length) {
				return super.read(destination, offset, Math.min(1, length));
			}
		};
		assertEquals(Map.of("a", "😀é"), byteMapper.decode(fragmented, Map.class));
		assertEquals(Map.of("a", "😀é"), Soml.decode(Reader.of(text), Map.class));
	}

	@Test
	void concurrentEncodingFailuresDoNotContaminatePooledOutput() throws Exception {
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var tasks = new ArrayList<Callable<Void>>();

			for (var index = 0; index < 32; index++) {
				var identifier = index;
				tasks.add(() -> {
					for (var attempt = 0; attempt < 30; attempt++) {
						assertThrows(SomlException.class, () -> Soml.encode(List.of(identifier, Double.NaN)));
						var expected = new Message(identifier, "'😀\n".repeat(identifier + 1));
						assertEquals(expected, Soml.decode(Soml.encode(expected), Message.class));
					}

					return null;
				});
			}

			for (var future : executor.invokeAll(tasks)) {
				future.get();
			}
		}
	}

	record Message(int id, String text) {}
}
