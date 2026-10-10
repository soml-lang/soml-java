package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class Utf8BuiltinTest {
	@Test
	void replacementCharactersBeforeUtf8BoundariesKeepTheirValueAndDiagnosticPosition() {
		var prefix = "# �😀\nvalue: 'é�😀".getBytes(StandardCharsets.UTF_8);
		var decoder = StandardCharsets.UTF_8.newDecoder();
		var hex = HexFormat.of();

		for (var sequence : new String[] {"c2", "df", "e0a0", "ed9f", "efbf", "f09080", "f48fbf"}) {
			var leading = hex.parseHex(sequence);
			var bytes = Arrays.copyOf(prefix, prefix.length + leading.length + 1);
			System.arraycopy(leading, 0, bytes, prefix.length, leading.length);

			for (var last = 0; last < 256; last++) {
				bytes[bytes.length - 1] = (byte) last;
				String expected;

				try {
					expected = decoder.decode(ByteBuffer.wrap(bytes)).toString();
				} catch (CharacterCodingException _) {
					var error = assertThrows(SomlException.class, () -> Parser.decode(bytes));
					assertEquals("Invalid UTF-8 byte 0x" + hex.withUpperCase().toHexDigits(leading[0]), error.reason());
					assertEquals(2, error.line());
					assertEquals(12, error.column());
					continue;
				}

				assertEquals(expected, Parser.decode(bytes));
			}

			var truncated = Arrays.copyOf(bytes, bytes.length - 1);
			var error = assertThrows(SomlException.class, () -> Parser.decode(truncated));
			assertEquals("Incomplete UTF-8 sequence at the end of the input", error.reason());
			assertEquals(2, error.line());
			assertEquals(12, error.column());
		}
	}
}
