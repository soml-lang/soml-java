package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class BuiltinsSecondValuesTest {
	@Test
	void emptyObjectViewsKeepTraversalAndTypedArrayContracts() {
		var members = new SomlObject(Map.of()).members();

		for (var view : List.<Collection<?>>of(members.keySet(), members.values(), members.entrySet())) {
			assertEquals(0, view.size());
			assertFalse(view.contains(null));
			assertFalse(view.iterator().hasNext());
			assertThrows(NoSuchElementException.class, () -> view.iterator().next());
			assertThrows(NullPointerException.class, () -> view.iterator().forEachRemaining(null));
			assertThrows(NullPointerException.class, () -> view.forEach(null));
			assertEquals(0, view.spliterator().getExactSizeIfKnown());
			var destination = new Object[] {"first", "second"};
			assertArrayEquals(new Object[] {null, "second"}, view.toArray(destination));
		}

		assertThrows(UnsupportedOperationException.class, () -> members.keySet().add("new"));
		assertThrows(UnsupportedOperationException.class, () -> members.values().add(SomlNull.INSTANCE));
		assertThrows(UnsupportedOperationException.class, () -> members.entrySet().add(Map.entry("new", SomlNull.INSTANCE)));
		assertEquals("{}", new SomlObject(members).toString());
	}
}
