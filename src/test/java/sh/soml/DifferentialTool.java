package sh.soml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/*
A command-line tool for `scripts/differential.js`. For each file, it prints one line of JSON:

	{"file": "...", "ok": true, "canonical": "..."}
	{"file": "...", "ok": false, "message": "...", "line": 1, "column": 5, "codeFrame": "..."}

With `-` as the only argument, it reads the paths from standard input, one per line.
*/
final class DifferentialTool {
	private DifferentialTool() {}

	public static void main(String[] arguments) throws IOException {
		var files = new ArrayList<String>();

		if (arguments.length == 1 && arguments[0].equals("-")) {
			var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

			for (var line = reader.readLine(); line != null; line = reader.readLine()) {
				if (!line.isEmpty()) {
					files.add(line);
				}
			}
		} else {
			files.addAll(java.util.List.of(arguments));
		}

		var output = new PrintStream(System.out, false, StandardCharsets.UTF_8);

		for (var file : files) {
			var line = new StringBuilder("{\"file\": ").append(quote(file));

			try {
				var bytes = Files.readAllBytes(Path.of(file));
				var value = Soml.parse(bytes);
				var text = new String(bytes, StandardCharsets.UTF_8);
				line.append(", \"ok\": true, \"canonical\": ").append(quote(Soml.encodeCanonical(value)));
				line.append(", \"formatted\": ").append(quote(Soml.format(text)));
				var edited = change(text, Path.of(file + ".change.soml"));

				if (edited != null) {
					line.append(", \"edited\": ").append(quote(edited));
				}
			} catch (SomlException error) {
				line.append(", \"ok\": false, \"message\": ").append(quote(error.reason())).append(", \"line\": ").append(error.line()).append(", \"column\": ").append(error.column()).append(", \"codeFrame\": ").append(quote(error.codeFrame()));
			}

			output.println(line.append('}'));
		}

		output.flush();
	}

	/*
	The text after the change in the file, as `scripts/differential.js` writes it: a document with a `path` of keys and indexes, and a `value` that is SOML text, or null to remove. Null when there is no change, or the change is an error.
	*/
	private static String change(String text, Path changeFile) throws IOException {
		if (!Files.exists(changeFile)) {
			return null;
		}

		var change = (SomlObject) Soml.parse(Files.readAllBytes(changeFile));
		var path = new ArrayList<Object>();

		for (var component : ((SomlArray) change.get("path")).items()) {
			path.add(component instanceof SomlString(var key) ? key : (Object) (int) ((SomlInt) component).value());
		}

		var document = SomlDocument.parse(text);

		try {
			if (change.get("value") instanceof SomlString(var valueText)) {
				document.set(path, ((SomlArray) Soml.parse("[" + valueText + "]")).get(0));
			} else {
				document.remove(path);
			}
		} catch (SomlException _) {
			return null;
		}

		return document.toString();
	}

	private static String quote(String text) {
		var output = new StringBuilder("\"");

		for (var index = 0; index < text.length(); index++) {
			var character = text.charAt(index);

			if (character == '"' || character == '\\') {
				output.append('\\').append(character);
			} else if (character < 0x20 || character == 0x7F || Character.isSurrogate(character) && !isPaired(text, index)) {
				output.append("\\u%04x".formatted((int) character));
			} else {
				output.append(character);
			}
		}

		return output.append('"').toString();
	}

	private static boolean isPaired(String text, int index) {
		var character = text.charAt(index);
		return Character.isHighSurrogate(character) ? index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)) : index > 0 && Character.isHighSurrogate(text.charAt(index - 1));
	}
}
