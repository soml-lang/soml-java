package sh.soml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BulkPathTest {
	record PathChangingValue(List<Object> source) {
		@Override
		public List<Object> source() {
			source.clear();
			return List.of();
		}
	}

	@Test
	void editsOwnThePathBeforeReadingRecordAccessors() {
		var path = new ArrayList<Object>(List.of("target"));
		var document = SomlDocument.parse("target: 1\nuntouched: 0xCAFE\n");
		document.set(path, new PathChangingValue(path));
		assertTrue(path.isEmpty());
		assertEquals(Soml.parse("source: []"), document.get(List.of("target")));
		assertTrue(document.toString().endsWith("untouched: 0xCAFE\n"));
	}

	@Test
	void immutableArrayLinkedAndSublistPathsKeepTheirComponents() {
		var components = List.<Object>of("items", 0, "value");
		var larger = new ArrayList<Object>(List.of("before", "items", 0, "value", "after"));
		for (var path : List.of(components, new ArrayList<>(components), new LinkedList<>(components), larger.subList(1, 4))) {
			var document = SomlDocument.parse("items: [{value: 1}]\nuntouched: 0xCAFE\n");
			document.set(path, 2);
			assertEquals(new SomlInt(2), document.get(components));
			assertTrue(document.remove(path));
			assertEquals(components, path);
			assertTrue(document.toString().endsWith("untouched: 0xCAFE\n"));
		}
		assertEquals(List.of("before", "items", 0, "value", "after"), larger);
	}

	@Test
	void nullComponentsKeepThePathErrorAndCannotPartiallyEdit() {
		var source = "items: [{value: 1}]\n";
		var document = SomlDocument.parse(source);
		var before = document.value();
		for (var path : List.of(Arrays.asList("items", null, "value"), new LinkedList<>(Arrays.asList("items", 0, null)))) {
			for (var operation : List.<Runnable>of(() -> document.set(path, 2), () -> document.remove(path))) {
				var error = assertThrows(IllegalArgumentException.class, operation::run);
				assertTrue(error.getMessage().endsWith("but it has null"));
				assertEquals(source, document.toString());
				assertSame(before, document.value());
			}
		}
	}
}
