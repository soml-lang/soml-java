package sh.soml;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/*
Sets or removes one value in a document, and keeps everything else as it is written, by the editing rules of the spec: only the text of the member or item that is added, replaced, or removed changes, with the comments it owns and its comma.

A port of the JavaScript reference's `edit()`. It replaces one range of the text and parses the result, so that a mistake here throws rather than writing a broken document.
*/
final class Editor {
	/*
	The changed text and its value, which is null when nothing changed.
	*/
	record Result(String text, @Nullable SomlValue value) {}

	private record Replacement(int start, int end, String text) {}

	/*
	A range of the text to remove. `isWholeLines` is whether it takes whole lines, which may leave a blank line next to another one.
	*/
	private static final class Removal {
		int start;
		int end;
		final boolean isWholeLines;

		Removal(int start, int end, boolean isWholeLines) {
			this.start = start;
			this.end = end;
			this.isWholeLines = isWholeLines;
		}
	}

	private final String text;
	private final List<SyntaxTree.Comment> comments;
	private final List<Object> path;
	// The new value, or null to remove the value at the path.
	private final @Nullable SomlValue value;
	private @Nullable Replacement replacement;
	// Whether the container being edited is inside one that stays on one line, so that it stays on one line too. An edit follows one path, so one flag is enough.
	private boolean isInsideOneLine;

	private Editor(SyntaxTree tree, List<Object> path, @Nullable SomlValue value) {
		this.text = tree.text;
		this.comments = tree.comments;
		this.path = path;
		this.value = value;
	}

	/*
	Sets `path` to `value`, or removes it when `value` is null. The text is already validated by SomlDocument.

	@throws SomlException When the path does not fit the document.
	*/
	static Result edit(String text, List<Object> path, @Nullable SomlValue value) {
		var tree = SyntaxTree.ofValid(text);
		var editor = new Editor(tree, path, value);
		editor.descend(tree.body, 0, 1);

		var replacement = editor.replacement;

		if (replacement == null) {
			return new Result(text, null);
		}

		// An edit follows one path and replaces one range, including any new objects on that path.
		var edited = new StringBuilder(text.length() - (replacement.end() - replacement.start()) + replacement.text().length())
			.append(text, 0, replacement.start()).append(replacement.text())
			.append(text, replacement.end(), text.length()).toString();

		try {
			return new Result(edited, Parser.parse(edited));
		} catch (SomlException error) {
			throw new IllegalStateException("The edit of " + describePath(path, path.size()) + " made a document that is not valid. This is a bug in the SOML library", error);
		}
	}

	private SomlException fail(String reason) {
		return new SomlException("Cannot edit " + describePath(path, path.size()) + ", because " + reason);
	}

	/*
	Edits the path from `index` on, inside `node`, whose own depth is `depth`.
	*/
	private void descend(SyntaxTree.ValueNode node, int index, int depth) {
		switch (node) {
			case SyntaxTree.ArrayNode array -> editInArray(array, index, depth);
			case SyntaxTree.ObjectNode object -> editInObject(object, index, depth);
			case SyntaxTree.ScalarNode _ -> throw fail(describeParent(index) + " is not an object or an array");
		}
	}

	private void editInArray(SyntaxTree.ArrayNode array, int index, int depth) {
		if (!(path.get(index) instanceof Integer position)) {
			throw fail(describeParent(index) + " is an array, so it needs an index, not a key");
		}

		var elements = array.elements();

		if (position >= elements.size()) {
			// Removing an item that is not there changes nothing, at the end of the array or past it, and so does removing something below it.
			if (value == null) {
				return;
			}

			var count = elements.size();

			if (position != count) {
				var owner = index == 0 ? "the document" : "the array at " + describePath(path, index);
				throw fail(owner + " has " + (count == 1 ? "1 item" : count + " items") + ". Add an item at index " + count);
			}

			var newValue = nest(index + 1, value);
			insert(array, elements.isEmpty() ? null : elements.getLast(), indentation -> valueText(newValue, depth + 1, indentation, array));
			return;
		}

		var element = elements.get(position);

		if (index < path.size() - 1) {
			isInsideOneLine = isOneLine(array);
			descend(element, index + 1, depth + 1);
		} else if (value == null) {
			removeNode(array, element);
		} else {
			replace(element, value, depth + 1, array);
		}
	}

	private void editInObject(SyntaxTree.ObjectNode object, int index, int depth) {
		if (!(path.get(index) instanceof String key)) {
			throw fail(describeParent(index) + " is an object, so it needs a key, not an index");
		}

		var member = object.member(key);

		if (member != null) {
			if (index < path.size() - 1) {
				isInsideOneLine = isOneLine(object);
				descend(member.value(), index + 1, depth + 1);
			} else if (value == null) {
				removeMember(object, member);
			} else {
				replaceMemberValue(member, value, depth + 1, object);
			}

			return;
		}

		if (value == null) {
			return;
		}

		var newValue = nest(index + 1, value);
		var members = object.members();
		insert(object, members.isEmpty() ? null : members.getLast(), indentation -> Writer.formatKey(key) + ": " + valueText(newValue, depth + 1, indentation, object));
	}

	private void replace(SyntaxTree.ValueNode node, SomlValue newValue, int depth, SyntaxTree.Container container) {
		replacement = new Replacement(node.start(), node.end(), valueText(newValue, depth, indentation(node.start()), container));
	}

	/*
	Replaces the value of `member`. A block string begins on the line after its key, and the new value is never a block string, so when only whitespace comes between the `:` and a block string, the new value goes on the key's line, as the formatter writes it.
	*/
	private void replaceMemberValue(SyntaxTree.MemberNode member, SomlValue newValue, int depth, SyntaxTree.ObjectNode object) {
		// Nothing may come between a key and its `:`, so the `:` is the character right after the key.
		var colonEnd = member.key().end() + 1;

		if (!(member.value() instanceof SyntaxTree.ScalarNode scalar && scalar.isBlockString() && skipWhitespace(colonEnd) == scalar.start())) {
			replace(member.value(), newValue, depth, object);
			return;
		}

		replacement = new Replacement(colonEnd, member.value().end(), " " + valueText(newValue, depth, indentation(member.start()), object));
	}

	/*
	Removes `member`. Removing the only member of a document without braces leaves `{}` in its place, because a document is never empty, and the comments that the member owned stay.
	*/
	private void removeMember(SyntaxTree.ObjectNode object, SyntaxTree.MemberNode member) {
		if (object.isBraced() || object.members().size() > 1) {
			removeNode(object, member);
			return;
		}

		replacement = new Replacement(member.start(), member.end(), "{}");
	}

	/*
	Removes `node` from `container`. The removal also takes a blank line that would be left next to another one or directly inside a bracket. When the only item of a braced container goes and no comment is left inside, its brackets close up, as `[]`.
	*/
	private void removeNode(SyntaxTree.Container container, SyntaxTree.Item node) {
		var items = container.items();
		// When the removed item is the last one and has no comma after it, it takes the comma before it, so that no trailing comma is left, as `[1, 2]` becomes `[1]`.
		var shouldTakeCommaBefore = node == items.getLast() && code(skipTrivia(node.end())) != ',';
		var removal = nodeRemoval(node, shouldTakeCommaBefore);

		if (removal.isWholeLines) {
			takeBlankLine(removal, container, contentStart(container));
		}

		if (items.size() == 1 && container.isBraced() && !isCommentLeft(container, removal)) {
			replacement = new Replacement(container.start() + 1, container.end() - 1, "");
			return;
		}

		replacement = new Replacement(removal.start, removal.end, "");
	}

	/*
	Whether a comment inside `container` is outside `removal`.
	*/
	private boolean isCommentLeft(SyntaxTree.Container container, Removal removal) {
		var first = commentIndexAt(container.start() + 1);
		var end = commentIndexAt(container.end());
		return first < end && (comments.get(first).start() < removal.start || comments.get(end - 1).start() >= removal.end);
	}

	/*
	What removing a member or an item, with the comments it owns and its comma, takes out of the text. With `shouldTakeCommaBefore`, a comma directly before it goes too.
	*/
	private Removal nodeRemoval(SyntaxTree.Item node, boolean shouldTakeCommaBefore) {
		var start = ownedStart(node.start());
		var end = ownedEnd(node.end());
		var next = skipSpaces(end);
		// Only a braced container can have commas.
		var hasComma = code(next) == ',';

		// The comma before it is only taken when spaces alone are between them and the comments it owns. The scan back only crosses spaces, so it stays short on a long line.
		var before = skipSpacesBack(start);
		var removalStart = shouldTakeCommaBefore && code(before - 1) == ',' ? before - 1 : start;
		// The comments after its comma go with it when they end its line, as the formatter keeps them after it.
		var lineEndAfterComma = hasComma ? findLineEndAfterTrivia(next + 1) : -1;
		var removalEnd = hasComma ? (lineEndAfterComma == -1 ? next + 1 : lineEndAfterComma) : end;
		var removal = removal(removalStart, removalEnd);

		// When it takes the comma before it, the spaces before a comment after it stay, so that the comment does not join the item before the comma.
		if (removalStart != start && !removal.isWholeLines && hasCommentAt(removal.end)) {
			removal.end = skipSpacesBack(removal.end);
		}

		return removal;
	}

	/*
	The start of the block comments that a member or an item at `offset` owns before it: those on its line after the comma or the opening bracket before it, as the comment in `[1, /* note *\/ 2]`. The formatter keeps them in front of it. It is `offset` when there are none.
	*/
	private int ownedStart(int offset) {
		// The start of each block comment is found by its end, walking backwards through the ordered comments.
		for (var position = commentIndexAt(offset) - 1; position >= 0; position--) {
			var comment = comments.get(position);

			if (!comment.isBlock() || comment.end() != skipSpacesBack(offset)) {
				return offset;
			}

			offset = comment.start();
		}

		return offset;
	}

	/*
	The end of the comments that a member or an item ending at `offset` owns after it: those on its line before its comma or the closing bracket, and a line comment at the end of its line. The formatter keeps them after it. It is `offset` when there are none.
	*/
	private int ownedEnd(int offset) {
		// The end of each comment is found by its start, so that the trivia between tokens can be skipped.
		for (var position = commentIndexAt(offset); position < comments.size(); position++) {
			var comment = comments.get(position);

			if (comment.start() != skipSpaces(offset)) {
				return offset;
			}

			offset = comment.end();
		}

		return offset;
	}

	/*
	The removal of the text from `start` to `end`. When nothing else is on its lines, it takes the lines, including a comment at the end of the last one.
	*/
	private Removal removal(int start, int end) {
		var lineEnd = findLineEndAfterTrivia(end);

		// The line start is only looked for when nothing follows on the line, because the scan back to it is long for an item in the middle of a long line, and doing it for every item would be quadratic.
		if (lineEnd != -1) {
			var lineStart = lineStart(start);

			if (skipSpaces(lineStart) == start) {
				return new Removal(lineStart, Math.min(lineEnd + 1, text.length()), true);
			}
		}

		// The spaces after the text go too, and the spaces before it when nothing follows it on the line or in its container.
		var after = skipSpaces(end);
		var code = code(after);
		var isLast = after == text.length() || code == '\n' || code == '}' || code == ']';
		return new Removal(isLast ? skipSpacesBack(start) : start, after, false);
	}

	/*
	Widens a removal of whole lines by a blank line that would otherwise be left next to another blank line, or directly inside a bracket. `contentStart` is the container's, from `contentStart()`, or -1.
	*/
	private void takeBlankLine(Removal removal, SyntaxTree.Container container, int contentStart) {
		var isBlankBefore = removal.start > 0 && isBlankLine(lineStart(removal.start - 1));
		var isBlankAfter = removal.end < text.length() && isBlankLine(removal.end);

		// With a blank line on both sides, the one before goes, as the spec says.
		if (isBlankBefore && (isBlankAfter || isAtClosing(removal.end, container))) {
			removal.start = lineStart(removal.start - 1);
		} else if (isBlankAfter && removal.start == contentStart) {
			removal.end = text.indexOf('\n', removal.end) + 1;
		}
	}

	/*
	The offset after the blank lines that start at `offset`.
	*/
	private int skipBlankLines(int offset) {
		while (offset < text.length() && isBlankLine(offset)) {
			offset = text.indexOf('\n', offset) + 1;
		}

		return offset;
	}

	/*
	Whether only blank lines come between the line at `offset` and the line that closes `container`, or the end of the text.
	*/
	private boolean isAtClosing(int offset, SyntaxTree.Container container) {
		offset = skipBlankLines(offset);
		return offset == text.length() || (container.isBraced() && skipSpaces(offset) == container.end() - 1);
	}

	/*
	The start of the first line that is not blank after the line that opens `container`, or after the start of the text. The line that opens a container ends after the comments that follow its bracket, and when an item follows on it, this is -1.
	*/
	private int contentStart(SyntaxTree.Container container) {
		if (!container.isBraced()) {
			return skipBlankLines(0);
		}

		var lineEnd = findLineEndAfterTrivia(container.start() + 1);
		return lineEnd == -1 ? -1 : skipBlankLines(lineEnd + 1);
	}

	private boolean isBlankLine(int lineStart) {
		return code(skipSpaces(lineStart)) == '\n';
	}

	private int lineStart(int offset) {
		return offset == 0 ? 0 : text.lastIndexOf('\n', offset - 1) + 1;
	}

	/*
	Adds an item after `previous`, or into the empty `container` when there is none. `makeItem` gets the indentation of the item's line. An item on its own line needs no comma, so only an item added on the line of the item before it gets one, as in a one-line container.
	*/
	private void insert(SyntaxTree.Container container, SyntaxTree.@Nullable Item previous, Function<String, String> makeItem) {
		if (previous == null) {
			var closing = container.end() - 1;

			// A container that stays on one line keeps the item on that line, so `[/* note */]` becomes `[/* note */ 1]`, and `[1, []]` becomes `[1, [2]]`.
			if (isOneLine(container)) {
				var start = skipSpacesBack(closing);
				// One space after a comment before the item, and none directly inside the brackets.
				replacement = new Replacement(start, closing, (start > container.start() + 1 ? " " : "") + makeItem.apply(""));
				return;
			}

			var outer = indentation(container.start());
			var inner = outer + "\t";
			var lineStart = lineStart(closing);

			// A closing bracket on its own line keeps that line, and the item goes on a line before it.
			if (lineStart > container.start() && skipSpaces(lineStart) == closing) {
				replacement = new Replacement(lineStart, lineStart, inner + makeItem.apply(inner) + "\n");
			} else {
				replacement = new Replacement(skipSpacesBack(closing), closing, "\n" + inner + makeItem.apply(inner) + "\n" + outer);
			}

			return;
		}

		// The new item goes after the comments that the one before it owns, so that they stay with that one.
		var previousEnd = ownedEnd(previous.end());
		var next = skipTrivia(previousEnd);
		var hasComma = code(next) == ',';
		var afterComma = hasComma ? next + 1 : previousEnd;
		var lineEnd = findLineEndAfterTrivia(afterComma);

		// Something follows on the same line, such as the closing bracket of a one-line object, so the item goes on that line too, and takes its indentation.
		if (lineEnd == -1) {
			var item = makeItem.apply(indentation(afterComma));
			// After a comma, the new item gets a comma too, so `[1, 2,]` becomes `[1, 2, 3,]`.
			replacement = hasComma ? new Replacement(afterComma, afterComma, " " + item + ",") : new Replacement(previousEnd, previousEnd, ", " + item);
			return;
		}

		var indentation = indentation(previous.start());
		replacement = new Replacement(lineEnd, lineEnd, "\n" + indentation + makeItem.apply(indentation));
	}

	/*
	Whether `container` stays on one line: its brackets are on one line and something is between them, which the formatter keeps, or it is inside a container that stays on one line. An empty `[]` or `{}` on its own gets its new item on a line of its own.
	*/
	private boolean isOneLine(SyntaxTree.Container container) {
		if (isInsideOneLine) {
			return true;
		}

		if (!container.isBraced() || skipSpaces(container.start() + 1) >= container.end() - 1) {
			return false;
		}

		return text.indexOf('\n', container.start(), container.end()) == -1;
	}

	/*
	A new value inside `container`. In a container on one line, the value is on one line too.
	*/
	private String valueText(SomlValue newValue, int depth, String indentation, SyntaxTree.Container container) {
		try {
			return Writer.writeValueAt(newValue, depth, indentation, isOneLine(container));
		} catch (Binding.BindingError _) {
			throw fail("the change would nest the document more than " + Writer.MAX_DEPTH + " levels deep");
		}
	}

	/*
	The spaces and tabs at the start of the line that holds `offset`. A line that a block comment from an earlier line runs into, as in a comment that ends before `x: 1` on its last line, is indented as the line where that comment starts.
	*/
	private String indentation(int offset) {
		var lineStart = lineStart(offset);

		for (var comment = commentAcross(lineStart); comment != null; comment = commentAcross(lineStart)) {
			lineStart = lineStart(comment.start());
		}

		return text.substring(lineStart, skipSpaces(lineStart));
	}

	/*
	The comment that starts before `offset` and ends after it, if any. The comments are in source order, so a binary search finds the last one that starts before `offset`.
	*/
	private SyntaxTree.@Nullable Comment commentAcross(int offset) {
		var position = commentIndexAt(offset);

		if (position == 0) {
			return null;
		}

		var comment = comments.get(position - 1);
		return comment.end() > offset ? comment : null;
	}

	/*
	The first comment whose start is at or after `offset`. The comments never overlap and are kept in source order.
	*/
	private int commentIndexAt(int offset) {
		// Note: Not using `Collections.binarySearch()` because it increased allocations in the paired editor benchmarks.
		var low = 0;
		var high = comments.size();

		while (low < high) {
			var middle = (low + high) >>> 1;

			if (comments.get(middle).start() < offset) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}

		return low;
	}

	/*
	Skips spaces, tabs, line feeds, and comments.
	*/
	private int skipTrivia(int offset) {
		return skipTrivia(offset, true);
	}

	/*
	Skips spaces, tabs, and comments, but not a line break outside a comment.
	*/
	private int skipLineTrivia(int offset) {
		return skipTrivia(offset, false);
	}

	/*
	Insertion and removal walk the ordered comment ranges directly, avoiding per-edit comment ownership maps. One binary search starts the scan, and each comment is visited once.
	*/
	private int skipTrivia(int offset, boolean includesLineFeeds) {
		var position = commentIndexAt(offset);

		while (offset < text.length()) {
			var character = text.charAt(offset);

			if (Parser.isSpace(character) || (includesLineFeeds && character == '\n')) {
				offset++;
			} else if (position < comments.size() && comments.get(position).start() == offset) {
				offset = comments.get(position++).end();
			} else {
				return offset;
			}
		}

		return offset;
	}

	private boolean hasCommentAt(int offset) {
		var position = commentIndexAt(offset);
		return position < comments.size() && comments.get(position).start() == offset;
	}

	/*
	The offset of the line break that ends the line at `offset`, or the end of the text, when only whitespace and comments come before it. Otherwise -1.
	*/
	private int findLineEndAfterTrivia(int offset) {
		offset = skipLineTrivia(offset);
		return offset == text.length() || code(offset) == '\n' ? offset : -1;
	}

	/*
	Skips spaces, tabs, and line feeds, but not comments.
	*/
	private int skipWhitespace(int offset) {
		while (Parser.isSpace(code(offset)) || code(offset) == '\n') {
			offset++;
		}

		return offset;
	}

	private int skipSpaces(int offset) {
		while (Parser.isSpace(code(offset))) {
			offset++;
		}

		return offset;
	}

	private int skipSpacesBack(int offset) {
		while (offset > 0 && Parser.isSpace(text.charAt(offset - 1))) {
			offset--;
		}

		return offset;
	}

	private int code(int offset) {
		return offset >= 0 && offset < text.length() ? text.charAt(offset) : -1;
	}

	/*
	`newValue` inside new objects for the keys of the path from `index` on. An index there names an item of an array that does not exist yet.
	*/
	private SomlValue nest(int index, SomlValue newValue) {
		if (path.size() > Writer.MAX_DEPTH) {
			throw fail("the change would nest the document more than " + Writer.MAX_DEPTH + " levels deep");
		}

		var nested = newValue;
		// Note: Not using `List.listIterator()` because the owned path is array-backed and indexed traversal needs no iterator.

		for (var position = path.size() - 1; position >= index; position--) {
			if (path.get(position) instanceof Integer item) {
				throw fail(describeParent(position) + " does not exist, so it has no index " + item);
			}

			nested = SomlObject.ofTrusted(new String[] {(String) path.get(position)}, new SomlValue[] {nested});
		}

		return nested;
	}

	/*
	The value that holds the path component at `index`.
	*/
	private String describeParent(int index) {
		return index == 0 ? "the document" : describePath(path, index);
	}

	/*
	The first `length` components of a path, as in `servers[0].port`, cut short when long.
	*/
	static String describePath(List<Object> path, int length) {
		var description = new StringBuilder();

		for (var index = 0; index < length; index++) {
			if (path.get(index) instanceof Integer item) {
				description.append('[').append(item).append(']');
			} else {
				if (index > 0) {
					description.append('.');
				}

				description.append(Parser.describeKey((String) path.get(index)));
			}

			if (description.length() > 200) {
				break;
			}
		}

		return Parser.abbreviate(description.toString(), 200);
	}
}
