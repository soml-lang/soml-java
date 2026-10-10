package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UntypedSingletonBuiltinsTest {
	@Test
	void smallUntypedContainersKeepNullsNestedValuesAndImmutableOwnership() {
		for (var text : List.of("[]", "{}", "[null]", "['text']", "[{}]", "[[null]]", "only: null", "only: [null]", "only: {nested: null}", "[null, true, false, 1, 2, 3, 4, 5]")) {
			var tree = Soml.parse(text);
			var fromTree = Soml.fromValue(tree, Object.class);
			var fromText = Soml.decode(text, Object.class);
			assertEquals(fromText, fromTree, text);
			assertEquals(tree, Soml.parse(Soml.encode(fromTree)), text);
			assertImmutable(fromTree);
			assertImmutable(fromText);
		}
	}

	@SuppressWarnings("unchecked")
	private static void assertImmutable(Object value) {
		if (value instanceof Map<?, ?> map) {
			assertThrows(UnsupportedOperationException.class, () -> ((Map<Object, Object>) map).put("additional", true));
			for (var nested : map.values()) {
				assertImmutable(nested);
			}
		} else if (value instanceof List<?> list) {
			assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) list).add(true));
			for (var nested : list) {
				assertImmutable(nested);
			}
		}
	}
}
