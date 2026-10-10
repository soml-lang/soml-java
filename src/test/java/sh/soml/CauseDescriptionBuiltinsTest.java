package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CauseDescriptionBuiltinsTest {
	record Token(String value) {}
	record Holder(Token at) {}

	@Test
	void adapterFailuresKeepNullEmptyAndOrdinaryMessagesOnBothPaths() {
		for (var message : Arrays.asList(null, "", "rejected value")) {
			var cause = new IllegalArgumentException(message);
			var mapper = SomlMapper.builder().adapter(Token.class, SomlAdapter.<Token>of(_ -> {
				throw cause;
			}, _ -> {
				throw cause;
			})).build();
			var description = message == null ? "IllegalArgumentException" : message;
			var expected = "The SomlAdapter for Token failed: " + description;

			var encoding = assertThrows(SomlException.class, () -> mapper.encode(List.of(new Token("value"))));
			assertEquals(expected, encoding.reason());
			assertEquals(List.of(0), encoding.path());
			assertSame(cause, encoding.getCause());

			var decoding = assertThrows(SomlException.class, () -> mapper.decode("at: 'value'", Holder.class));
			assertEquals(expected, decoding.reason());
			assertEquals(List.of("at"), decoding.path());
			assertEquals(1, decoding.line());
			assertEquals(5, decoding.column());
			assertSame(cause, decoding.getCause());
		}
	}
}
