package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import org.junit.jupiter.api.Test;

class VectorBuiltinsTest {
	static class SeededVector extends Vector<Long> {
		private static final long serialVersionUID = 1L;

		SeededVector() {
			add(99L);
		}
	}

	@Test
	void concreteVectorsKeepOrderNullsMutabilityAndSubclassInitialization() {
		var type = new TypeReference<Vector<Long>>() {};

		for (var count : new int[] {0, 1, 10, 11, 128}) {
			var expected = new ArrayList<Long>();
			for (var index = 0; index < count; index++) {
				expected.add(index % 3 == 0 ? null : (long) index);
			}

			var text = Soml.encode(expected);
			var tree = Soml.parse(text);
			for (var vector : List.of(Soml.decode(text, type), Soml.fromValue(tree, type))) {
				assertEquals(Vector.class, vector.getClass());
				assertEquals(expected, vector);
				vector.add(123L);
				assertEquals(123L, vector.getLast());
				vector.removeLast();
				assertEquals(expected, vector);
			}
		}

		assertEquals(List.of(99L, 1L, 2L), Soml.decode("[1, 2]", SeededVector.class));
		assertEquals(List.of(99L, 1L, 2L), Soml.fromValue(Soml.parse("[1, 2]"), SeededVector.class));
		var error = assertThrows(SomlException.class, () -> Soml.decode("[1, 'wrong', 2]", type));
		assertEquals(List.of(1), error.path());
		assertEquals(1, error.line());
		assertEquals(5, error.column());
	}
}
