package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import org.junit.jupiter.api.Test;

class CollectionContractsTest {
	@Test
	void searchesRepeatedItemsFromBothEnds() {
		var items = ((SomlArray) Soml.parse("[1, null, 'x', 1, null]")).items();
		assertEquals(0, items.indexOf(new SomlInt(1)));
		assertEquals(3, items.lastIndexOf(new SomlInt(1)));
		assertEquals(1, items.indexOf(SomlNull.INSTANCE));
		assertEquals(4, items.lastIndexOf(SomlNull.INSTANCE));
		assertEquals(-1, items.indexOf(null));
		assertEquals(-1, items.lastIndexOf(null));
		assertEquals(-1, items.lastIndexOf(new SomlString("absent")));
	}

	@Test
	void reversedAndSlicedListsKeepTheListContract() {
		var items = ((SomlArray) Soml.parse("[1, 2, 3, 2]")).items();
		var reversed = items.reversed();
		assertEquals(List.of(new SomlInt(2), new SomlInt(3), new SomlInt(2), new SomlInt(1)), reversed);
		assertEquals(0, reversed.indexOf(new SomlInt(2)));
		assertEquals(2, reversed.lastIndexOf(new SomlInt(2)));
		assertEquals(items, reversed.reversed());
		assertArrayEquals(new SomlInt[] {new SomlInt(2), new SomlInt(3)}, items.subList(1, 3).toArray(SomlInt[]::new));
		assertThrows(UnsupportedOperationException.class, () -> reversed.set(0, SomlNull.INSTANCE));
	}

	@Test
	void splitTraversalKeepsEveryItemInOrder() {
		var items = ((SomlArray) Soml.parse("[1, 2, null, 'x', true]")).items();
		var suffix = items.spliterator();
		assertTrue(suffix.hasCharacteristics(Spliterator.ORDERED | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.SIZED | Spliterator.SUBSIZED));
		assertEquals(items.size(), suffix.getExactSizeIfKnown());
		var prefix = suffix.trySplit();
		var visited = new ArrayList<SomlValue>();
		prefix.forEachRemaining(visited::add);
		suffix.forEachRemaining(visited::add);
		assertEquals(items, visited);
		assertEquals(0, suffix.getExactSizeIfKnown());
		assertEquals(items, items.parallelStream().toList());
	}

	@Test
	void entryMembershipUsesKeyAndValueEqualityAcrossLookupSizes() {
		for (var size : new int[] {1, 8, 9, 128}) {
			var members = new LinkedHashMap<String, SomlValue>();

			for (var index = 0; index < size; index++) {
				members.put("key" + index, new SomlInt(index));
			}

			var entries = new SomlObject(members).members().entrySet();
			assertTrue(entries.contains(new AbstractMap.SimpleEntry<>("key" + (size - 1), new SomlInt(size - 1))));
			assertFalse(entries.contains(Map.entry("key0", SomlNull.INSTANCE)));
			assertFalse(entries.contains(new AbstractMap.SimpleEntry<>("key0", null)));
			assertFalse(entries.contains(new AbstractMap.SimpleEntry<>(null, new SomlInt(0))));
			assertFalse(entries.contains(Map.entry(0, new SomlInt(0))));
			assertFalse(entries.contains("key0"));
			assertFalse(entries.contains(null));
		}
	}

	@Test
	void collectionViewsCannotChangeTheValueTree() {
		var object = (SomlObject) Soml.parse("a: [1]\nb: null");
		var members = object.members();
		var original = object.toString();
		assertThrows(UnsupportedOperationException.class, () -> members.keySet().remove("a"));
		assertThrows(UnsupportedOperationException.class, () -> members.values().remove(SomlNull.INSTANCE));
		assertThrows(UnsupportedOperationException.class, () -> members.entrySet().iterator().next().setValue(new SomlInt(2)));
		assertThrows(UnsupportedOperationException.class, () -> members.replaceAll((_, _) -> SomlNull.INSTANCE));
		assertThrows(UnsupportedOperationException.class, () -> ((SomlArray) object.get("a")).items().addFirst(new SomlInt(2)));
		assertEquals(original, object.toString());
	}

	@Test
	void mapForEachRejectsANullActionEvenWhenEmpty() {
		assertThrows(NullPointerException.class, () -> new SomlObject(Map.of()).members().forEach(null));
		var visited = new ArrayList<String>();
		((SomlObject) Soml.parse("z: 0\na: 1")).members().forEach((key, value) -> visited.add(key + value));
		assertEquals(List.of("z0", "a1"), visited);
	}
}
