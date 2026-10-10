package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BindingRefinementTest {
	@Test
	void stringMismatchPreviewsAreQuotedBeforeReportingEscapedContent() {
		var raw = "\n\t\u0001'😀".repeat(100_000);
		var error = assertThrows(SomlException.class, () -> Soml.fromValue(new SomlString(raw), Integer.class));
		assertEquals("Expected an int, but found the string " + new SomlString(Parser.abbreviate(raw, 40)), error.reason());
	}

	@Test
	void invisibleStringSuffixesDoNotChangeMismatchPreviews() {
		var prefix = "a".repeat(39) + "😀";
		var plain = new SomlString(prefix + "x".repeat(100_000));
		var escaped = new SomlString(prefix + "x".repeat(100_000) + "'");
		var expected = "the string '" + "a".repeat(39) + "…'";
		assertEquals(expected, Binding.describeValue(plain));
		assertEquals(expected, Binding.describeValue(escaped));
	}

	record Pair(int a, int z) {}

	@Test
	void alreadySortedMembersKeepCanonicalOrderAndUnknownKeySuggestions() {
		var mapper = SomlMapper.builder().rejectUnknownKeys(true).build();
		assertEquals("a: 1\nz: 2\n", mapper.encodeCanonical(new Pair(1, 2)));
		var error = assertThrows(SomlException.class, () -> mapper.decode("unknown: 1", Pair.class));
		assertEquals("Unknown key unknown. Pair has no member for it, and its members are a, z", error.reason());
		assertEquals(new Pair(1, 2), mapper.fromValue(new SomlObject(Map.of("z", new SomlInt(2), "a", new SomlInt(1))), Pair.class));
	}

	@Test
	void primitiveArrayTreesRespectAdaptersOnBoxedAncestors() {
		var mapper = SomlMapper.builder().adapter(Number.class, SomlAdapter.of(number -> new SomlString(number.toString()), _ -> 1)).build();
		var expected = SomlArray.of(new SomlString("1"), new SomlString("2"));
		assertEquals(expected, mapper.toValue(new int[] {1, 2}));
		assertEquals(expected, mapper.toValue(new long[] {1, 2}));
		assertEquals(expected, mapper.toValue(new short[] {1, 2}));
		assertEquals(expected, mapper.toValue(new byte[] {1, 2}));
		assertEquals(expected, Soml.parse(mapper.encode(new int[] {1, 2})));
	}

	@Test
	void everyPrimitiveRejectsNullWhileItsWrapperAcceptsIt() {
		var pairs = Map.<Class<?>, Class<?>>of(boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class, int.class, Integer.class, long.class, Long.class, float.class, Float.class, double.class, Double.class, char.class, Character.class);

		for (var pair : pairs.entrySet()) {
			assertThrows(SomlException.class, () -> Soml.fromValue(SomlNull.INSTANCE, pair.getKey()));
			assertNull(Soml.fromValue(SomlNull.INSTANCE, pair.getValue()));
		}
	}
}
