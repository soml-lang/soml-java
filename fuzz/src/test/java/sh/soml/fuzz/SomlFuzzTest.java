package sh.soml.fuzz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sh.soml.Soml;
import sh.soml.SomlArray;
import sh.soml.SomlDocument;
import sh.soml.SomlException;
import sh.soml.SomlValue;

/*
Coverage-guided fuzz tests. Each one checks what must hold for every input, besides that only `SomlException` escapes for a document that is not valid. `mvn test` runs the inputs in `src/test/resources` as regression tests, and `JAZZER_FUZZ=1 mvn test` fuzzes, and adds an input that fails there.
*/
class SomlFuzzTest {
	@FuzzTest(maxDuration = "2m")
	void parse(byte[] input) throws IOException {
		SomlValue value;

		try {
			value = Soml.parse(input);
		} catch (SomlException _) {
			// A stream gives the same error.
			assertThrows(SomlException.class, () -> Soml.parse(new ByteArrayInputStream(input)));
			return;
		}

		assertEquals(value, Soml.parse(new ByteArrayInputStream(input)));

		// Canonical form reads back as the same value, and is its own canonical form.
		var canonical = Soml.encodeCanonical(value);
		assertEquals(value, Soml.parse(canonical));
		assertEquals(canonical, Soml.encodeCanonical(Soml.parse(canonical)));
		assertEquals(value, Soml.parse(Soml.encode(value)));
	}

	@FuzzTest(maxDuration = "2m")
	void format(FuzzedDataProvider data) {
		var text = data.consumeRemainingAsString();
		SomlValue value;

		try {
			value = Soml.parse(text);
		} catch (SomlException _) {
			assertThrows(SomlException.class, () -> Soml.format(text));
			return;
		}

		// Formatting keeps the value, and formatting again changes nothing.
		var formatted = Soml.format(text);
		assertEquals(value, Soml.parse(formatted));
		assertEquals(formatted, Soml.format(formatted));
	}

	sealed interface Check permits Http, Command {}

	record Http(String url, Optional<Integer> status) implements Check {}

	record Command(List<String> arguments) implements Check {}

	enum Level {
		DEBUG,
		INFO
	}

	record Server(String host, int port, Optional<Duration> timeout, Set<String> tags) {}

	record Config(String name, long replicas, double ratio, boolean isEnabled, Instant started, Level level, List<Server> servers, Map<String, Object> extra, Map<Level, Integer> limits, Optional<Check> check, int[] ports, char separator, Optional<LocalDate> launchDate) {}

	@FuzzTest(maxDuration = "2m")
	void decode(byte[] input) {
		Config decoded = null;

		try {
			decoded = Soml.decode(input, Config.class);
		} catch (SomlException _) {
			// A document that is not valid, or does not fit.
		}

		if (decoded != null) {
			// Direct binding and binding a value tree must agree, including primitive arrays, whose Java equality compares identity.
			assertEquals(Soml.toValue(Soml.fromValue(Soml.parse(input), Config.class)), Soml.toValue(decoded));
		}

		Object untyped;

		try {
			untyped = Soml.decode(input, Object.class);
		} catch (SomlException _) {
			return;
		}

		// Plain Java objects encode as the same value.
		assertEquals(Soml.parse(input), Soml.toValue(untyped));
		assertEquals(Soml.encodeCanonical(Soml.parse(input)), Soml.encodeCanonical(untyped));
	}

	@FuzzTest(maxDuration = "2m")
	void edit(FuzzedDataProvider data) {
		var path = new ArrayList<Object>();

		for (var count = data.consumeInt(1, 4); path.size() < count;) {
			path.add(data.consumeBoolean() ? data.consumeInt(0, 3) : data.consumeString(4));
		}

		var isRemove = data.consumeBoolean();
		Object newValue = switch (data.consumeInt(0, 4)) {
			case 0 -> data.consumeLong();
			case 1 -> data.consumeString(8);
			case 2 -> List.of(data.consumeInt(), data.consumeString(4));
			case 3 -> LocalDate.of(data.consumeInt(1, 9999), data.consumeInt(1, 12), data.consumeInt(1, 28));
			default -> Map.of(data.consumeString(4), data.consumeBoolean());
		};

		SomlDocument document;

		try {
			document = SomlDocument.parse(data.consumeRemainingAsString());
		} catch (SomlException _) {
			return;
		}

		var original = document.toString();

		try {
			if (isRemove) {
				var parentPath = path.subList(0, path.size() - 1);
				var parent = document.get(parentPath);
				var removed = document.remove(path);

				// After an array item is removed, its index is the next item's.
				if (parent instanceof SomlArray array && path.getLast() instanceof Integer index) {
					var expected = new ArrayList<>(array.items());
					var exists = index < expected.size();

					if (exists) {
						expected.remove(index.intValue());
					}

					assertEquals(exists, removed);
					assertEquals(new SomlArray(expected), document.get(parentPath));
				} else if (path.getLast() instanceof String) {
					assertNull(document.get(path));
				}
			} else {
				document.set(path, newValue);
				assertEquals(Soml.toValue(newValue), document.get(path));
			}
		} catch (SomlException | IllegalArgumentException _) {
			// A path that does not fit the document, or a key that no document can have.
			assertEquals(original, document.toString());
		}

		// The text is always a valid document with the value of the document.
		assertEquals(document.value(), Soml.parse(document.toString()));
	}
}
