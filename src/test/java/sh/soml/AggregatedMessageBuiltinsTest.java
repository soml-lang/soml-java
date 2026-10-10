package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AggregatedMessageBuiltinsTest {
	record Token(String value) {}

	@Test
	void emptyAdapterReasonsKeepEmptyKeyPathsPositionsAndMessageSeparators() {
		var cause = new SomlException("");
		var mapper = rejectingMapper(cause);
		var text = "'': 'one'\nlast: 'two'\n";
		var type = new TypeReference<Map<String, Token>>() {};
		var treeError = assertThrows(SomlException.class, () -> mapper.fromValue(Soml.parse(text), type));
		assertEquals("[\"\"]: \n\nlast: ", treeError.getMessage());
		var textError = assertThrows(SomlException.class, () -> mapper.decode(text, type));
		assertEquals("[\"\"]:  at line 1, column 5\n\n> 1 | '': 'one'\n    |     ^\n\nlast:  at line 2, column 7\n\n  1 | '': 'one'\n> 2 | last: 'two'\n    |       ^", textError.getMessage());

		for (var error : List.of(treeError, textError)) {
			assertEquals(2, error.errors().size());
			assertEquals(List.of(""), error.errors().getFirst().path());
			assertEquals(List.of("last"), error.errors().getLast().path());
			assertEquals("", error.reason());
			assertSame(cause, error.getCause());
			assertSame(cause, error.errors().getLast().getCause());
		}
	}

	@Test
	void theStopSummaryFollowsTenEmptyReasonErrorsInDocumentOrder() {
		var mapper = rejectingMapper(new SomlException(""));
		var value = Soml.parse("['x', 'x', 'x', 'x', 'x', 'x', 'x', 'x', 'x', 'x', 'x', 'x']");
		var type = new TypeReference<List<Token>>() {};
		var error = assertThrows(SomlException.class, () -> mapper.fromValue(value, type));
		assertEquals(10, error.errors().size());
		assertEquals("[0]: \n\n[1]: \n\n[2]: \n\n[3]: \n\n[4]: \n\n[5]: \n\n[6]: \n\n[7]: \n\n[8]: \n\n[9]: \n\nDecoding stopped after 10 errors", error.getMessage());
		assertEquals(List.of(9), error.errors().getLast().path());
	}

	private static SomlMapper rejectingMapper(SomlException cause) {
		return SomlMapper.builder().adapter(Token.class, SomlAdapter.<Token>of(token -> new SomlString(token.value()), _ -> {
			throw cause;
		})).build();
	}
}
