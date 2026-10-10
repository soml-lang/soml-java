package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenericTypeNameBuiltinsTest {
	record ListKey<T>(Map<List<T>, Long> entries) {}
	record MapKey<T>(Map<Map<String, T>, Long> entries) {}
	record NestedKey<T>(Map<List<Map<T, Integer[]>>, Long> entries) {}

	@Test
	void resolvedGenericKeyErrorsKeepArgumentOrderArraysAndNestedNames() {
		var cases = Map.of(
			new TypeReference<ListKey<String>>() {}, "java.util.List<java.lang.String>",
			new TypeReference<MapKey<Long>>() {}, "java.util.Map<java.lang.String, java.lang.Long>",
			new TypeReference<NestedKey<String>>() {}, "java.util.List<java.util.Map<java.lang.String, java.lang.Integer[]>>"
		);
		var text = "entries: {one: 1}";
		var tree = Soml.parse(text);

		for (var entry : cases.entrySet()) {
			var reason = "Cannot decode a map key to " + entry.getValue() + ". A map key can be a String, a char, an enum, an integer type, or a UUID";
			var fromText = assertThrows(SomlException.class, () -> Soml.decode(text, entry.getKey()));
			var fromTree = assertThrows(SomlException.class, () -> Soml.fromValue(tree, entry.getKey()));
			for (var error : List.of(fromText, fromTree)) {
				assertEquals(reason, error.reason());
				assertEquals(List.of("entries", "one"), error.path());
			}
			assertEquals(1, fromText.line());
			assertEquals(11, fromText.column());
			assertEquals(0, fromTree.line());
		}
	}
}
