package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class ArrayDecodingOptimizationTest {
	@TestFactory
	Stream<DynamicTest> everyArrayComponentSurvivesEveryGrowthBoundary() {
		return Stream.of(int.class, long.class, short.class, byte.class, boolean.class, char.class, double.class, float.class, String.class).flatMap(component -> Stream.of(0, 1, 9, 10, 11, 128).map(size -> DynamicTest.dynamicTest(component.getSimpleName() + "[" + size + "]", () -> {
			var expected = Array.newInstance(component, size);

			for (var index = 0; index < size; index++) {
				Object item;

				if (component == boolean.class) {
					item = index % 2 == 0;
				} else if (component == char.class) {
					item = index % 2 == 0 ? '\t' : 'é';
				} else if (component == String.class) {
					item = index % 3 == 0 ? null : "item " + index;
				} else if (component == float.class) {
					item = index + 0.25f;
				} else if (component == double.class) {
					item = index + 0.25;
				} else {
					item = (byte) (index - 64);
				}

				Array.set(expected, index, item);
			}

			var tree = Soml.toValue(expected);
			var arrayType = expected.getClass();
			var parsed = Soml.decode(Soml.encode(expected), arrayType);
			var converted = Soml.fromValue(tree, arrayType);
			assertEquals(arrayType, parsed.getClass());
			assertEquals(arrayType, converted.getClass());
			assertTrue(Arrays.deepEquals(new Object[] {expected}, new Object[] {parsed}));
			assertTrue(Arrays.deepEquals(new Object[] {expected}, new Object[] {converted}));
		})));
	}

	@Test
	void typedArraysKeepAdapterResultsAndNullReferences() {
		var mapper = SomlMapper.builder().adapter(Number.class, SomlAdapter.of(number -> new SomlString("n" + number), value -> Integer.valueOf(((SomlString) value).value().substring(1)))).build();
		var text = "[" + "'n1024',".repeat(128) + "]";
		var expected = new int[128];
		Arrays.fill(expected, 1024);
		assertTrue(Arrays.equals(expected, mapper.decode(text, int[].class)));
		assertTrue(Arrays.equals(expected, mapper.fromValue(Soml.parse(text), int[].class)));
		var strings = SomlMapper.builder().adapter(String.class, SomlAdapter.of(SomlString::new, value -> ((SomlString) value).value().toUpperCase(java.util.Locale.ROOT))).build();
		assertTrue(Arrays.equals(new String[] {"A", null, "B"}, strings.decode("['a', null, 'b']", String[].class)));
	}

	@Test
	void multidimensionalAndGenericArraysKeepTheirRuntimeComponentTypes() {
		var expected = new int[][] {{1, 2}, null, {}, {3}};
		assertTrue(Arrays.deepEquals(expected, Soml.decode(Soml.encode(expected), int[][].class)));
		assertTrue(Arrays.deepEquals(expected, Soml.fromValue(Soml.toValue(expected), int[][].class)));
		var type = new TypeReference<List<Integer>[]>() {};
		var lists = Soml.decode("[[1, 2], null, [], [3]]", type);
		assertEquals(List[].class, lists.getClass());
		assertEquals(List.of(1, 2), lists[0]);
		assertEquals(null, lists[1]);
		assertEquals(List.of(), lists[2]);
		assertEquals(List.of(3), lists[3]);
	}

	@Test
	void primitiveArrayErrorsKeepTheirIndexesAcrossGrowthBoundaries() {
		var text = "values: [" + "1,".repeat(10) + "null,'bad',2]";
		var type = new TypeReference<Map<String, int[]>>() {};
		var error = assertThrows(SomlException.class, () -> Soml.decode(text, type));
		assertEquals(List.of(List.of("values", 10), List.of("values", 11)), error.errors().stream().map(SomlException::path).toList());
		var treeError = assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), type));
		assertEquals(error.errors().stream().map(SomlException::reason).toList(), treeError.errors().stream().map(SomlException::reason).toList());
	}
}
