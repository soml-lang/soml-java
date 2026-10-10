package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuiltinBoundaryContractsTest {
	@SuppressWarnings("serial")
	public static final class CaseInsensitiveSet extends TreeSet<String> {
		public CaseInsensitiveSet() {
			super(String.CASE_INSENSITIVE_ORDER);
		}
	}

	@Test
	void comparatorDuplicatesAreReportedAtTheRepeatedItem() {
		var valid = Soml.decode("['A', 'b']", CaseInsensitiveSet.class);
		assertEquals(List.of("A", "b"), List.copyOf(valid));
		assertTrue(valid.add("c"));

		var text = "['A', 'a']";
		for (var error : List.of(assertThrows(SomlException.class, () -> Soml.decode(text, CaseInsensitiveSet.class)), assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), CaseInsensitiveSet.class)))) {
			assertEquals(List.of(1), error.path());
			assertEquals("The array has this item twice, which a set cannot hold", error.reason());
			assertEquals(1, error.errors().size());
		}
	}

	@Test
	void convertedMapKeyDuplicatesPrecedeValueConversion() {
		var type = new TypeReference<Map<UUID, String>>() {};
		var firstKey = "abcdef01-abcd-abcd-abcd-abcdef012345";
		var repeatedKey = "ABCDEF01-ABCD-ABCD-ABCD-ABCDEF012345";
		var text = "'" + firstKey + "': 'first'\n'" + repeatedKey + "': true";
		assertEquals(UUID.fromString(firstKey), UUID.fromString(repeatedKey));

		for (var error : List.of(assertThrows(SomlException.class, () -> Soml.decode(text, type)), assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse(text), type)))) {
			assertEquals(List.of(repeatedKey), error.path());
			assertEquals("Two keys decode to the same map key", error.reason());
			assertEquals(1, error.errors().size());
		}
	}

	@Test
	void decodedNavigableInterfacesKeepNavigationAndImmutability() {
		var map = Soml.decode("b: 2\na: 1\nc: 3", new TypeReference<NavigableMap<String, Integer>>() {});
		assertEquals("a", map.lowerKey("b"));
		assertEquals(List.of("c", "b", "a"), List.copyOf(map.descendingKeySet()));
		assertThrows(UnsupportedOperationException.class, () -> map.pollFirstEntry());
		assertThrows(UnsupportedOperationException.class, () -> map.descendingMap().put("d", 4));

		var set = Soml.decode("[3, 1, 2]", new TypeReference<NavigableSet<Integer>>() {});
		assertEquals(1, set.lower(2));
		assertEquals(List.of(3, 2, 1), List.copyOf(set.descendingSet()));
		assertThrows(UnsupportedOperationException.class, () -> set.pollFirst());
		assertThrows(UnsupportedOperationException.class, () -> set.descendingSet().add(4));
	}
}
