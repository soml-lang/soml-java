package sh.soml;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/*
Every input shape takes linear time: 8 times the input must not take much more than 8 times as long. A quadratic step would take 64 times as long. The shapes come from the Go implementation, with more for the diagnostics, which look further into the text when a document is not valid.
*/
class ComplexityTest {
	private static final int SIZE = 4000;

	record Shape(String name, IntFunction<String> document) {}

	private static final List<Shape> SHAPES = List.of(
		new Shape("a long literal string", size -> "a: '" + "x".repeat(size * 20) + "'"),
		new Shape("a long escaped string", size -> "a: \"" + "x\\n\\u{1f600}".repeat(size * 2) + "\""),
		new Shape("a long block string", size -> "a: '''\n" + "\tline\n\n".repeat(size) + "\t'''"),
		new Shape("a long escaped block string", size -> "a: \"\"\"\n" + "\tline of text\n".repeat(size) + "\t\"\"\""),
		new Shape("many escaped block strings", size -> {
			var text = new StringBuilder();

			for (var index = 0; index < size; index++) {
				text.append('k').append(index).append(": \"\"\"\n\tx\n\t\"\"\"\n");
			}

			return text.toString();
		}),
		new Shape("a long comment", size -> "# " + "x".repeat(size * 20) + "\na: 1"),
		new Shape("a long block comment", size -> "/* " + "x*".repeat(size * 10) + " */ a: 1"),
		new Shape("many keys", size -> {
			var text = new StringBuilder();

			for (var index = 0; index < size; index++) {
				text.append("key").append("x".repeat(index % 7)).append((char) ('a' + index % 26)).append(index).append(": 1\n");
			}

			return text.toString();
		}),
		new Shape("many array items on one line", size -> "a: [" + "'x', 1.5, 2026-01-01T00:00:00Z, 1h30m, ".repeat(size) + "]"),
		new Shape("many small objects", size -> "a: [" + "{b: 1, c: [true]}, ".repeat(size) + "]"),
		new Shape("a long number", size -> "a: 1" + "0".repeat(size * 20) + ".5"),
		new Shape("a long int", size -> "a: 1" + "0".repeat(size * 20)),
		new Shape("a long duration", size -> "a: 1." + "0".repeat(size * 20) + "1s"),
		new Shape("a long invalid number", size -> "a: 1" + "x".repeat(size * 20)),
		new Shape("a long number with underscores in the wrong places", size -> "a: 1" + "__1".repeat(size * 5)),
		new Shape("a long hexadecimal number with lowercase digits", size -> "a: 0x" + "1".repeat(size * 20) + "ff"),
		new Shape("a long time-like number", size -> "a: 1" + ":2".repeat(size * 5)),
		new Shape("a long instant-like token", size -> "a: 2026-01-01T00:00:00" + "0".repeat(size * 20)),
		new Shape("a long unterminated string", size -> "a: '" + "x".repeat(size * 20)),
		new Shape("a long unterminated escaped string", size -> "a: \"" + "x\\t".repeat(size * 10)),
		new Shape("a long unterminated block string", size -> "a: '''\n" + "line\n".repeat(size)),
		new Shape("a long key without a colon", size -> "ab ".repeat(size * 10)),
		new Shape("a long key with dots", size -> "ab.".repeat(size * 10) + ": 1"),
		new Shape("a long key with colons", size -> "a" + ":b".repeat(size * 10) + ": 1"),
		new Shape("a long unquoted value", size -> "a: " + "word ".repeat(size * 10)),
		new Shape("many strings on one line", size -> "a: [" + "'x', \"y\", ".repeat(size) + "]"),
		new Shape("many block comments after a value", size -> "a: 1" + " /* c */".repeat(size) + "\nb: 2"),
		new Shape("many block comments before an item", size -> "a: [1, " + "/* c */ ".repeat(size) + "\n2]"),
		new Shape("many items without commas", size -> "a: [" + "1 ".repeat(size * 4) + "]"),
		new Shape("many entries on one line", size -> "a: 1 " + "b: 2 ".repeat(size * 4)),
		new Shape("a missing value before many lines", size -> "a:\n" + "\n".repeat(size * 20) + "b: 1"),
		new Shape("many spaces between an instant and its offset", size -> "a: 2026-09-19T14:00:00" + " ".repeat(size * 20) + "Z"),
		new Shape("many numbers with a unit and a slash", size -> "a: [" + "10 km/h, ".repeat(size) + "]"),
		new Shape("many unclosed brackets", size -> "a: " + "[".repeat(Math.min(size, 99)) + "1,".repeat(size * 4)),
		new Shape("many keys with one hash code", size -> {
			// `Aa` and `BB` have the same hash code, so every key of 16 of them has the same one too.
			var text = new StringBuilder();

			for (var index = 0; index < size; index++) {
				for (var bit = 0; bit < 16; bit++) {
					text.append((index >> bit & 1) == 0 ? "Aa" : "BB");
				}

				text.append(": 1\n");
			}

			return text.toString();
		})
	);

	@TestFactory
	Stream<DynamicTest> linearTime() {
		record Operation(String name, java.util.function.Consumer<String> run) {}

		var operations = List.of(
			new Operation("parse", text -> {
				try {
					Soml.parse(text);
				} catch (SomlException _) {
					// Errors are part of the shapes.
				}
			}),
			new Operation("decode", text -> {
				try {
					Soml.decode(text, Map.class);
				} catch (SomlException _) {
					// Errors are part of the shapes.
				}
			}),
			new Operation("format", text -> {
				try {
					Soml.format(text);
				} catch (SomlException _) {
					// Errors are part of the shapes.
				}
			}),
			new Operation("set and remove", text -> {
				try {
					var document = SomlDocument.parse(text);
					document.set(List.of("added"), 1);
					document.remove(List.of("a"));
				} catch (SomlException _) {
					// Errors are part of the shapes, and a document that is an array has no key.
				}
			}),
			new Operation("decode with an error in every item", text -> {
				try {
					Soml.decode(text, new TypeReference<Map<String, List<Integer>>>() {});
				} catch (SomlException _) {
					// Most shapes have items that are not ints.
				}
			}),
			new Operation("decode with an error", text -> {
				try {
					Soml.decode(text, Integer.class);
				} catch (SomlException _) {
					// Every shape fails, the valid ones when they are decoded.
				}
			})
		);

		return SHAPES.stream().flatMap(shape -> operations.stream().map(operation -> DynamicTest.dynamicTest(shape.name() + ": " + operation.name(), () -> {
			var small = shape.document().apply(SIZE);
			var large = shape.document().apply(SIZE * 8);
			var ratio = 0.0;

			// Measured up to five times, keeping the shortest time of each size, because other processes can slow down any measurement.
			var smallTime = Long.MAX_VALUE;
			var largeTime = Long.MAX_VALUE;

			for (var attempt = 0; attempt < 5; attempt++) {
				smallTime = Math.min(smallTime, measure(() -> operation.run().accept(small)));
				largeTime = Math.min(largeTime, measure(() -> operation.run().accept(large)));
				ratio = (double) largeTime / Math.max(smallTime, Duration.ofMillis(1).toNanos());

				// Linear is about 8, and quadratic about 64. The limit leaves room for noise and for the cache effects of a larger input.
				if (ratio <= 32) {
					break;
				}
			}

			assertTrue(ratio <= 32, "8 times the input took %.1f times as long".formatted(ratio));
		})));
	}

	/*
	The CPU time of the thread, rather than the wall time, which other load on the machine changes much more.
	*/
	private static long measure(Runnable run) {
		var threads = ManagementFactory.getThreadMXBean();
		var shortest = Long.MAX_VALUE;

		for (var attempt = 0; attempt < 3; attempt++) {
			var start = threads.getCurrentThreadCpuTime();
			run.run();
			shortest = Math.min(shortest, threads.getCurrentThreadCpuTime() - start);
		}

		return shortest;
	}
}
