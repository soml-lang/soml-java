package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IntegralFloatBuiltinsTest {
	@Test
	void floatsRoundTripAcrossIntegralShortcutBoundaries() {
		var values = new float[] {0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, Math.nextDown(1f), 1f, Math.nextUp(1f), -1f, Math.nextDown(0x1p24f), 0x1p24f, Math.nextUp(0x1p24f), Math.nextDown(-0x1p24f), -0x1p24f, Math.nextUp(-0x1p24f), 0.1f, 1e7f, Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
		var expected = values.clone();

		for (var index = 0; index < expected.length; index++) {
			expected[index] += 0f;
		}

		var tree = Soml.toValue(values);
		var text = Soml.encode(values);
		assertEquals(tree, Soml.parse(text));
		assertArrayEquals(expected, Soml.fromValue(tree, float[].class));
		assertArrayEquals(expected, Soml.decode(text, float[].class));
		assertEquals(Soml.encode(Soml.fromValue(tree, float[].class)), text);
	}
}
