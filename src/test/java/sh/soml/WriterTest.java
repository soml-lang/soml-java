package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class WriterTest {
	@Test
	void writesSignedIntegersAtEveryDecimalWidth() {
		var numbers = new java.util.ArrayList<Long>();
		numbers.add(Long.MIN_VALUE);
		numbers.add(Long.MAX_VALUE);
		numbers.add(0L);

		for (long power = 1, width = 1; width <= 19; width++, power *= 10) {
			for (var delta = -1; delta <= 1; delta++) {
				numbers.add(power + delta);
				numbers.add(-power + delta);
			}
		}

		var random = new Random(42);

		for (var index = 0; index < 10_000; index++) {
			numbers.add(random.nextLong());
		}

		for (var number : numbers) {
			var output = new Writer.TextBuffer(0);
			output.append('[').append(number).append(']');
			assertEquals("[" + Long.toString(number) + "]", output.toString());
		}
	}

	@Test
	void keepsMixedOutputIntactAcrossBufferGrowth() {
		for (var capacity : new int[] {0, 1, 19, 20, 31, 64}) {
			var output = new Writer.TextBuffer(capacity);
			var expected = new StringBuilder();

			for (var index = 0; index < 100; index++) {
				output.append("prefix", 1, 5).append(Long.MIN_VALUE).repeat(' ', index % 7);
				output.appendString("it's 😀\n\t\\", null);
				output.append(false).append('!');
				expected.append("refi").append(Long.MIN_VALUE).repeat(' ', index % 7).append("\"it's 😀\\n\\t\\\\\"false!");
				assertEquals(expected.toString(), output.toString(), "capacity " + capacity);
			}
		}
	}
}
