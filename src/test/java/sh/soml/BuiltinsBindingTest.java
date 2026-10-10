package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuiltinsBindingTest {
	record Reordered(int zeta, @SomlName("a.b") String label, Optional<Integer> count) {}

	record Empty() {}

	@Test
	void fallbackMemberLookupDistinguishesIndexZeroUnknownAndOmittedValues() {
		var text = "count: null\n'a.b': 'value'\nzeta: 7";
		var expected = new Reordered(7, "value", Optional.empty());
		var strict = SomlMapper.builder().rejectUnknownKeys(true).build();
		assertEquals(expected, strict.decode(text, Reordered.class));
		assertEquals(expected, strict.fromValue(Soml.parse(text), Reordered.class));
		assertEquals(expected, strict.decode("'a.b': 'value'\nzeta: 7", Reordered.class));
		var error = assertThrows(SomlException.class, () -> strict.decode("extra: 0\n" + text, Reordered.class));
		assertEquals(List.of("extra"), error.path());
		assertEquals("Unknown key extra. Reordered has no member for it, and its members are \"a.b\", count, zeta", error.reason());
		assertEquals(expected, Soml.decode("extra: 0\n" + text, Reordered.class));
	}

	@Test
	void unknownKeyDiagnosticsKeepTheEmptyMemberDescription() {
		var mapper = SomlMapper.builder().rejectUnknownKeys(true).build();
		var streamed = assertThrows(SomlException.class, () -> mapper.decode("unknown: 1", Empty.class));
		var converted = assertThrows(SomlException.class, () -> mapper.fromValue(Soml.parse("unknown: 1"), Empty.class));
		assertEquals("Unknown key unknown. Empty has no member for it, and it has no members", streamed.reason());
		assertEquals(streamed.reason(), converted.reason());
		assertEquals(List.of("unknown"), converted.path());
	}

	@Test
	void copyingAcrossSeveralGrowthsKeepsNestedArrayRuntimeTypesAndNulls() {
		for (var size : new int[] {20, 21, 40, 41, 80, 81}) {
			var expected = new String[size][];

			for (var index = 0; index < size; index++) {
				expected[index] = index % 3 == 0 ? null : new String[] {"item " + index, null};
			}

			var parsed = Soml.decode(Soml.encode(expected), String[][].class);
			assertEquals(String[][].class, parsed.getClass());
			assertTrue(Arrays.deepEquals(expected, parsed));
			assertEquals(String[].class, parsed[1].getClass());
			var generic = Soml.decode("[" + "[1,null],".repeat(size) + "]", new TypeReference<List<Integer>[]>() {});
			assertEquals(List[].class, generic.getClass());
			assertEquals(size, generic.length);
			assertEquals(Arrays.asList(1, null), generic[size - 1]);
		}
	}
}
