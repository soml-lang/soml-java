package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentTest {
	@Test
	void settingNullKeepsTheMemberUntilItIsRemoved() {
		var document = SomlDocument.parse("a: [1, 2]\nb: true # Keep\n");
		document.set(List.of("a", 0), null);
		document.set(List.of("b"), null);
		assertEquals("a: [null, 2]\nb: null # Keep\n", document.toString());
		assertSame(SomlNull.INSTANCE, document.get(List.of("a", 0)));
		assertSame(SomlNull.INSTANCE, document.get(List.of("b")));
		assertTrue(document.remove(List.of("a", 0)));
		assertTrue(document.remove(List.of("b")));
		assertNull(document.get(List.of("b")));
		assertEquals("a: [2]\n", document.toString());
		assertEquals(Soml.parse(document.toString()), document.value());
	}

	@Test
	void failedChangesKeepBothTextAndValue() {
		var document = SomlDocument.parse("a: [1, 2] # Keep\nb: true\n");
		var text = document.toString();
		var value = document.value();
		SomlValue nested = SomlArray.of();

		for (var depth = 1; depth < 100; depth++) {
			nested = SomlArray.of(nested);
		}

		var tooDeep = nested;

		for (var change : List.<Runnable>of(
			() -> document.set(List.of("a", 0), Double.NaN),
			() -> document.set(List.of("a", 0), tooDeep),
			() -> document.set(List.of("a", 4), 3),
			() -> document.remove(List.of("b", "x"))
		)) {
			assertThrows(SomlException.class, change::run);
			assertEquals(text, document.toString());
			assertSame(value, document.value());
		}

		assertFalse(document.remove(List.of("missing", "child")));
		assertSame(value, document.value());
		document.set(List.of("a", 1), 3);
		assertEquals("a: [1, 3] # Keep\nb: true\n", document.toString());
		assertEquals(Soml.parse(document.toString()), document.value());
	}

	@Test
	void reindentsLargeBlockStringsWithoutChangingTheirContent() {
		for (var delimiter : List.of("'''", "\"\"\"\"")) {
			var text = new StringBuilder("before: 1\nobject: {\n    value:\n        " + delimiter + "\n");

			for (var index = 0; index < 2000; index++) {
				text.append(index % 3 == 0 ? " \t\n" : "        line " + index + "  😀\n");
			}

			text.append("        ").append(delimiter).append(" # Keep\n}\nafter: 2\n");
			var source = text.toString();
			var formatted = Soml.format(source);
			assertEquals(Soml.parse(source), Soml.parse(formatted));
			assertEquals(formatted, Soml.format(formatted));
			assertTrue(formatted.endsWith(delimiter + " # Keep\n}\nafter: 2\n"));
			assertTrue(formatted.contains(" \t\n"));
		}
	}

	@Test
	void changesValuesAndKeepsTheRest() {
		var document = SomlDocument.parse("""
			# The edge service.
			port: 0x1F90 # The default.
			server: {host: 'a'}
			""");

		document.set(List.of("port"), 8080);
		document.set(List.of("server", "port"), 443);
		document.set(List.of("replicas"), 3);
		assertTrue(document.remove(List.of("server", "host")));
		assertEquals("# The edge service.\nport: 8080 # The default.\nserver: {port: 443}\nreplicas: 3\n", document.toString());
		assertEquals(new SomlInt(443), document.get(List.of("server", "port")));
	}

	@Test
	void printsAnUnchangedDocumentExactly() {
		var text = "a:   1 # Note\n\n\n/* x */ b: [1,2,]";
		assertEquals(text, SomlDocument.parse(text).toString());
	}

	@Test
	void getsValues() {
		var document = SomlDocument.parse("servers: [{port: 80}]\nname: 'x'");
		assertEquals(new SomlInt(80), document.get(List.of("servers", 0, "port")));
		assertEquals(document.value(), document.get(List.of()));
		assertNull(document.get(List.of("servers", 1)));
		assertNull(document.get(List.of("servers", "port")));
		assertNull(document.get(List.of("name", "x")));
		assertNull(document.get(List.of("missing", "x")));
	}

	@Test
	void removesSafely() {
		var document = SomlDocument.parse("a: 1\nb: [1]\n");
		assertTrue(document.remove(List.of("a")));
		assertFalse(document.remove(List.of("a")));
		assertFalse(document.remove(List.of("x", "y")));
		assertFalse(document.remove(List.of("b", 5)));
		assertEquals("b: [1]\n", document.toString());
		assertTrue(document.remove(List.of("b")));
		assertEquals("{}\n", document.toString());
		assertEquals(new SomlObject(Map.of()), document.value());
	}

	@Test
	void refusesPathsThatDoNotFit() {
		var document = SomlDocument.parse("a: [1]\nb: 'x'\n");
		assertEquals("Cannot edit a[3], because the array at a has 1 item. Add an item at index 1", assertThrows(SomlException.class, () -> document.set(List.of("a", 3), 1)).reason());
		assertEquals("Cannot edit a.x, because a is an array, so it needs an index, not a key", assertThrows(SomlException.class, () -> document.set(List.of("a", "x"), 1)).reason());
		assertEquals("Cannot edit b.c, because b is not an object or an array", assertThrows(SomlException.class, () -> document.remove(List.of("b", "c"))).reason());
		assertEquals("Cannot edit x[0], because x does not exist, so it has no index 0", assertThrows(SomlException.class, () -> document.set(List.of("x", 0), 1)).reason());
		assertEquals("a: [1]\nb: 'x'\n", document.toString());

		assertThrows(IllegalArgumentException.class, () -> document.set(List.of(), 1));
		assertThrows(IllegalArgumentException.class, () -> document.set(List.of("a", -1), 1));
		assertThrows(IllegalArgumentException.class, () -> document.set(List.of("a", 1L), 1));
		assertThrows(IllegalArgumentException.class, () -> document.set(List.of("b\r"), 1));
		assertThrows(IllegalArgumentException.class, () -> document.set(List.of("\uD800"), 1));
		assertEquals("a: [1]\nb: 'x'\n", document.toString());
	}

	record Server(String host, int port) {}

	@Test
	void writesNewValuesAsTheEncoderDoes() {
		var document = SomlDocument.parse("servers: [\n\t{host: 'a', port: 1}\n]\n");
		document.set(List.of("servers", 1), new Server("b", 2));
		assertEquals("servers: [\n\t{host: 'a', port: 1}\n\t{\n\t\thost: 'b'\n\t\tport: 2\n\t}\n]\n", document.toString());

		var oneLine = SomlDocument.parse("a: [1, []]\n");
		oneLine.set(List.of("a", 1, 0), new Server("c", 3));
		assertEquals("a: [1, [{host: 'c', port: 3}]]\n", oneLine.toString());
	}

	@Test
	void refusesTooDeepChanges() {
		SomlValue deep = SomlArray.of();

		for (var level = 0; level < 99; level++) {
			deep = SomlArray.of(deep);
		}

		var document = SomlDocument.parse("a: 1\n");
		var tooDeep = deep;
		var error = assertThrows(SomlException.class, () -> document.set(List.of("a"), tooDeep));
		assertEquals("Cannot edit a, because the change would nest the document more than 100 levels deep", error.reason());
		document.set(List.of("a"), ((SomlArray) deep).get(0));
	}

	@Test
	void editsLargeDocumentsInLinearTime() {
		var text = new StringBuilder();

		for (var index = 0; index < 20_000; index++) {
			text.append("key").append(index).append(": [").append(index).append(", 'x'] # Note\n");
		}

		var document = SomlDocument.parse(text.toString());
		document.set(List.of("key19999", 2), true);
		document.remove(List.of("key0"));
		assertEquals(new SomlBool(true), document.get(List.of("key19999", 2)));
		assertNull(document.get(List.of("key0")));
	}

	@Test
	void formats() {
		assertEquals("pool: {min: 2, max: 16} # Connections\n", Soml.format("pool: {min: 2,   max: 16,} # Connections"));
		assertEquals("pool: {\n\tmin: 2\n\tmax: 16\n}\n", Soml.format("pool: {\nmin: 2, max: 16}"));
		// Runs of blank lines collapse to one, and the spelling of each value stays.
		assertEquals("a: 0xFF\n\nb: 90m\nc: 'x'\n", Soml.format("a:   0xFF\n\n\n\nb:90m\nc: 'x'"));

		var error = assertThrows(SomlException.class, () -> Soml.format("a: 1 b: 2"));
		assertEquals(1, error.line());

		var lines = new ArrayList<String>();

		for (var index = 0; index < 3; index++) {
			lines.add("  item" + index + ":  " + index + "  ");
		}

		assertEquals("item0: 0\nitem1: 1\nitem2: 2\n", Soml.format(String.join("\n", lines)));
	}
}
