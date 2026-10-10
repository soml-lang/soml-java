package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeyPrefixBuiltinsTest {
	@Test
	void editingMatchesWholeDecodedKeysAcrossStringEncodings() {
		for (var text : List.of("{port: 1, portal: 2}", "{'port': 1, portal: 2}", "{\"port\": 1, portal: 2}", "{\"p\\u{6f}rt\": 1, portal: 2}", "{'😀': 1, '😀suffix': 2}", "{'é': 1, 'ésuffix': 2}", "{'': 1, suffix: 2}")) {
			var document = SomlDocument.parse(text);
			var original = (SomlObject) document.value();
			var key = original.keyAt(0);
			var otherKey = original.keyAt(1);
			document.set(List.of(key), 3);
			assertEquals(new SomlInt(3), document.get(List.of(key)), text);
			assertEquals(new SomlInt(2), document.get(List.of(otherKey)), text);
			assertEquals(2, ((SomlObject) document.value()).memberCount(), text);
			assertTrue(document.remove(List.of(key)), text);
			assertFalse(document.remove(List.of(key)), text);
			assertEquals(new SomlInt(2), document.get(List.of(otherKey)), text);
		}
	}

	@Test
	void cachedKeysRemainDistinctAcrossPrefixesAndStringEncodings() {
		for (var prefix : List.of("", "# 😀\n")) {
			for (var key : List.of("port", "é", "😀", "")) {
				var text = prefix + "[{" + Writer.formatKey(key) + ": 1}, {" + Writer.formatKey(key + "suffix") + ": 2}, {" + Writer.formatKey(key) + ": 3}]";
				var parsed = (SomlArray) Soml.parse(text);
				assertEquals(new SomlInt(1), ((SomlObject) parsed.items().get(0)).get(key), text);
				assertEquals(new SomlInt(2), ((SomlObject) parsed.items().get(1)).get(key + "suffix"), text);
				assertEquals(new SomlInt(3), ((SomlObject) parsed.items().get(2)).get(key), text);
			}
		}
	}
}
