package sh.soml;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/*
Types made of other types: generics through inheritance, nested containers, and decoding from many threads at once.
*/
class BindingShapesTest {
	static class Base<T> {
		T value;
		List<T> history = List.of();
	}

	static class Middle<X> extends Base<List<X>> {
		String name = "unnamed";
	}

	static final class Leaf extends Middle<Integer> {
		boolean isLeaf;
	}

	@Test
	void resolvesInheritedGenericFields() {
		var leaf = Soml.decode("value: [1, 2]\nhistory: [[3]]\nname: 'a'\nisLeaf: true", Leaf.class);
		assertEquals(List.of(1, 2), leaf.value);
		assertEquals(List.of(List.of(3)), leaf.history);
		assertEquals("a", leaf.name);
		assertTrue(leaf.isLeaf);
		assertEquals("value: [\n\t1\n\t2\n]\nhistory: [\n\t[\n\t\t3\n\t]\n]\nname: 'a'\nisLeaf: true\n", Soml.encode(leaf));
	}

	record Server(String host, int port) {}

	record Cluster(Map<String, List<Server>> regions, Optional<List<Server>> backups, Server[] seeds, List<Map<String, Duration>> timeouts) {}

	@Test
	void decodesNestedContainers() {
		var cluster = Soml.decode("""
			regions: {
				eu: [{host: 'a', port: 1}]
				us: []
			}
			backups: [{host: 'b', port: 2}]
			seeds: [{host: 'c', port: 3}]
			timeouts: [{read: 1s}, {}]
			""", Cluster.class);

		assertEquals(Map.of("eu", List.of(new Server("a", 1)), "us", List.of()), cluster.regions());
		assertEquals(Optional.of(List.of(new Server("b", 2))), cluster.backups());
		assertArrayEquals(new Server[] {new Server("c", 3)}, cluster.seeds());
		assertEquals(List.of(Map.of("read", Duration.ofSeconds(1)), Map.of()), cluster.timeouts());

		var error = org.junit.jupiter.api.Assertions.assertThrows(SomlException.class, () -> Soml.decode("regions: {eu: [{host: 'a', port: 'x'}]}\nseeds: []\ntimeouts: []", Cluster.class));
		assertEquals(List.of("regions", "eu", 0, "port"), error.path());
		assertEquals(1, error.line());
		assertEquals(34, error.column());
	}

	record Tree(String name, List<Tree> children, Optional<Tree> parent) {}

	@Test
	void decodesFromManyThreads() throws Exception {
		var text = "name: 'root'\nchildren: [{name: 'a', children: [{name: 'b', children: []}]}]";
		var expected = new Tree("root", List.of(new Tree("a", List.of(new Tree("b", List.of(), Optional.empty())), Optional.empty())), Optional.empty());

		for (var round = 0; round < 20; round++) {
			// A new mapper, so that the threads race to make the codecs.
			var mapper = SomlMapper.builder().build();
			var tasks = new ArrayList<Callable<Tree>>();

			for (var thread = 0; thread < 16; thread++) {
				tasks.add(() -> mapper.decode(text, Tree.class));
			}

			try (var executor = Executors.newFixedThreadPool(16)) {
				for (var future : executor.invokeAll(tasks)) {
					assertEquals(expected, future.get());
				}
			}
		}
	}
}
