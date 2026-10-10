package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class BuiltinsBindingSecondTest {
	static class SeededMap extends ConcurrentHashMap<String, Integer> {
		private static final long serialVersionUID = 1L;

		SeededMap() {
			put("seed", 99);
		}
	}

	@Test
	void capacityAwareConcurrentMapsKeepSmallLargeAndSubclassContracts() {
		var mapType = new TypeReference<ConcurrentHashMap<String, Integer>>() {};

		for (var size : new int[] {0, 1, 10, 11, 12, 128}) {
			var entries = new LinkedHashMap<String, Integer>();

			for (var index = size; index > 0; index--) {
				entries.put("item" + index, index);
			}

			for (var map : List.of(Soml.fromValue(Soml.toValue(entries), mapType), Soml.decode(Soml.encode(entries), mapType))) {
				assertEquals(entries, map);
				map.put("added", 42);
				assertEquals(42, map.get("added"));
			}
		}

		var seeded = Soml.parse("added: 1");
		assertEquals(java.util.Map.of("seed", 99, "added", 1), Soml.fromValue(seeded, SeededMap.class));
		assertEquals(java.util.Map.of("seed", 99, "added", 1), Soml.decode("added: 1", SeededMap.class));

		for (var error : List.of(assertThrows(SomlException.class, () -> Soml.decode("missing: null", mapType)), assertThrows(SomlException.class, () -> Soml.fromValue(Soml.parse("missing: null"), mapType)))) {
			assertEquals(List.of("missing"), error.path());
		}
	}
}
