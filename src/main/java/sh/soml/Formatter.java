package sh.soml;

import java.util.List;
import org.jspecify.annotations.Nullable;

/*
The formatter of the spec: it changes the layout of a document and nothing else. One tab per level, every member and item of a container that spans lines on its own line with no commas, a container on one line kept on one line, comments in place, and the spelling of every value as it is.

A port of the JavaScript reference's `format()`. Every scan of the text is bounded by the range it is about, so that a document with long lines stays linear.
*/
final class Formatter {
	private final String text;
	private final SyntaxTree tree;
	private final StringBuilder output;
	private int commentIndex;

	private Formatter(SyntaxTree tree) {
		this.text = tree.text;
		this.tree = tree;
		this.output = new StringBuilder(text.length() + 16);
	}

	static String format(String text) {
		return new Formatter(SyntaxTree.parse(text)).print();
	}

	private String print() {
		var body = tree.body;
		List<? extends SyntaxTree.Item> items = body instanceof SyntaxTree.ObjectNode object && !object.isBraced() ? object.members() : List.of(body);

		// The document is a list without brackets: the members of a brace-less object, or one braced collection.
		list(items, 0, text.length(), 0, false);
		return output.append('\n').toString();
	}

	/*
	Starts a new line at `level`, with one blank line before it when the source had one. The first line of the file has no line break before it.
	*/
	private void newLine(int level, boolean isBlank) {
		if (output.isEmpty()) {
			return;
		}

		output.append(isBlank ? "\n\n" : "\n").repeat('\t', level);
	}

	private boolean isSameLine(int start, int end) {
		return text.indexOf('\n', start, end) == -1;
	}

	/*
	Whether a line between `start` and `end` holds only spaces and tabs, if anything.
	*/
	private boolean hasBlankLine(int start, int end) {
		for (var offset = text.indexOf('\n', start, end); offset != -1; ) {
			var next = offset + 1;

			while (next < end && Parser.isSpace(text.charAt(next))) {
				next++;
			}

			if (next < end && text.charAt(next) == '\n') {
				return true;
			}

			offset = text.indexOf('\n', next, end);
		}

		return false;
	}

	/*
	The next comment that ends at or before `end`, without consuming it.
	*/
	private SyntaxTree.@Nullable Comment peekComment(int end) {
		if (commentIndex >= tree.comments.size()) {
			return null;
		}

		var comment = tree.comments.get(commentIndex);
		return comment.end() <= end ? comment : null;
	}

	private void writeComment(SyntaxTree.Comment comment) {
		commentIndex++;

		if (!comment.isBlock()) {
			output.append(text, comment.start(), trimEnd(comment.start(), comment.end()));
			return;
		}

		// The lines of a block comment keep their own indentation. Trailing whitespace is removed, and runs of blank lines collapse to one, as everywhere outside a block string.
		var isPreviousEmpty = false;
		var isFirst = true;

		for (var lineStart = comment.start(); lineStart <= comment.end(); ) {
			var lineEnd = lineEndWithin(lineStart, comment.end());

			var trimmedEnd = trimEnd(lineStart, lineEnd);
			var isEmpty = trimmedEnd == lineStart;

			if (!(isEmpty && isPreviousEmpty && !isFirst)) {
				if (!isFirst) {
					output.append('\n');
				}

				output.append(text, lineStart, trimmedEnd);
			}

			isFirst = false;
			isPreviousEmpty = isEmpty;
			lineStart = lineEnd + 1;
		}
	}

	/*
	The line feed after `start`, or `end` when none comes before it. The search stops at `end`, so that many comments on one long line stay linear.
	*/
	private int lineEndWithin(int start, int end) {
		var lineEnd = text.indexOf('\n', start, end);
		return lineEnd == -1 ? end : lineEnd;
	}

	/*
	The end of the text from `start` to `end` without the spaces and tabs at its end. Only spaces and tabs are whitespace in SOML. Any other character, such as a no-break space, is content.
	*/
	private int trimEnd(int start, int end) {
		while (end > start && Parser.isSpace(text.charAt(end - 1))) {
			end--;
		}

		return end;
	}

	/*
	The offset of the `,` after an item in the source, or -1 when there is none. Between two items there is only whitespace, comments, and at most one comma.
	*/
	private int findComma(int start, int end) {
		for (var position = commentIndex; position < tree.comments.size(); position++) {
			var comment = tree.comments.get(position);

			if (comment.start() >= end) {
				break;
			}

			var comma = text.indexOf(',', start, comment.start());

			if (comma != -1) {
				return comma;
			}

			// A comma inside a comment is not one. The syntax tree already found its end, so its content need not be scanned again.
			start = comment.end();
		}

		return text.indexOf(',', start, end);
	}

	/*
	Writes `items` one per line at `level`, with the comments between them, from `start` to `end` in the source.

	A comment on the line of the item before it, or of the opening bracket, stays at the end of that line. The exception is a block comment after the comma that the next item follows on the same line, as in `[1, /* note *\/ 2]`: it stays in front of that item. Every other comment starts a new line, and a comment or an item that follows a block comment on its line stays on that line.
	*/
	private void list(List<? extends SyntaxTree.Item> items, int start, int end, int level, boolean isBracketed) {
		var previousEnd = start;
		var itemEnd = -1;
		// Whether a comment may stay at the end of the line before it, which needs an item or an opening bracket there.
		var canTrail = isBracketed;
		// Whether the list has written something other than a comment at the end of the opening bracket's line. A blank line is kept only after that, so none is left directly inside the bracket or at the start of the file.
		var hasWritten = false;
		var isOnSameLine = false;

		for (var position = 0; position <= items.size(); position++) {
			var item = position < items.size() ? items.get(position) : null;
			var itemStart = item == null ? end : item.start();
			var comma = isBracketed ? findComma(previousEnd, itemStart) : -1;
			// Found once per item rather than once per comment, which would be quadratic in the number of comments on one line.
			var itemLineStart = item == null ? -1 : lineStartAfter(previousEnd, itemStart);

			for (var comment = peekComment(itemStart); comment != null; comment = peekComment(itemStart)) {
				var isTrailing = canTrail && !isOnSameLine && isTrailing(comment, previousEnd, itemEnd, itemLineStart, comma);

				if (isTrailing || isOnSameLine) {
					output.append(' ');
				} else {
					newLine(level, hasWritten && hasBlankLine(previousEnd, comment.start()));
				}

				writeComment(comment);
				hasWritten |= !isTrailing;

				// A block comment that the next comment or item follows on the same line keeps it on that line.
				var next = peekComment(itemStart);
				var nextStart = next == null ? itemStart : next.start();
				isOnSameLine = comment.isBlock() && !isTrailing && isSameLine(comment.end(), nextStart);
				previousEnd = comment.end();
			}

			if (item == null) {
				return;
			}

			if (isOnSameLine) {
				output.append(' ');
			} else {
				newLine(level, hasWritten && hasBlankLine(previousEnd, itemStart));
			}

			item(item, level);
			previousEnd = item.end();
			itemEnd = previousEnd;
			canTrail = true;
			hasWritten = true;
			isOnSameLine = false;
		}
	}

	/*
	The start of the line that holds `offset`, but not before `start`.
	*/
	private int lineStartAfter(int start, int offset) {
		for (var position = offset - 1; position >= start; position--) {
			if (text.charAt(position) == '\n') {
				return position + 1;
			}
		}

		return start;
	}

	/*
	Whether a comment stays at the end of the line before it. That line ends at the item before it, or at the comma after that item.
	*/
	private boolean isTrailing(SyntaxTree.Comment comment, int previousEnd, int itemEnd, int itemLineStart, int comma) {
		var isAfterComma = comma != -1 && comment.start() > comma;

		// A block comment after the comma that the next item follows on the same line stays in front of that item. The comment ends on the item's line when it ends after the start of that line.
		if (itemLineStart != -1 && comment.isBlock() && (comma == -1 || isAfterComma) && comment.end() >= itemLineStart) {
			return false;
		}

		// A comment after the comma ends the comma's line, which is later than the item's when a block comment that spans lines comes between them. The comma is removed, so the comment moves to the end of the item's line, which only holds when nothing came between them.
		var lineEnd = isAfterComma && previousEnd == itemEnd ? comma + 1 : previousEnd;
		return isSameLine(lineEnd, comment.start());
	}

	/*
	Writes a container that is on one line in the source on one line, with a comma and a space between its members or items and no trailing comma. Only a block comment can be inside it, and each one stays where it is among the items and commas.
	*/
	private void oneLine(List<? extends SyntaxTree.Item> items, int start, int end, int level, char opening, char closing) {
		output.append(opening);
		var previousEnd = start + 1;
		var hasWrittenToken = false;

		for (var position = 0; position <= items.size(); position++) {
			var item = position < items.size() ? items.get(position) : null;
			var itemStart = item == null ? end - 1 : item.start();
			// The comma after the last item is left out.
			var comma = item == null ? -1 : findComma(previousEnd, itemStart);
			var hasWrittenComma = false;

			for (var comment = peekComment(itemStart); comment != null; comment = peekComment(itemStart)) {
				if (!hasWrittenComma && comma != -1 && comma < comment.start()) {
					output.append(',');
					hasWrittenComma = true;
				}

				if (hasWrittenToken) {
					output.append(' ');
				}

				writeComment(comment);
				hasWrittenToken = true;
			}

			if (item == null) {
				break;
			}

			if (comma != -1 && !hasWrittenComma) {
				output.append(", ");
			} else if (hasWrittenToken) {
				output.append(' ');
			}

			item(item, level);
			previousEnd = item.end();
			hasWrittenToken = true;
		}

		output.append(closing);
	}

	private void item(SyntaxTree.Item item, int level) {
		switch (item) {
			case SyntaxTree.MemberNode member -> member(member, level);
			case SyntaxTree.ValueNode value -> value(value, level);
		}
	}

	private void member(SyntaxTree.MemberNode member, int level) {
		var key = member.key();
		var value = member.value();
		output.append(text, key.start(), key.end()).append(':');
		var isBlockString = value instanceof SyntaxTree.ScalarNode scalar && scalar.isBlockString();

		if (peekComment(value.start()) == null) {
			// A block string begins on the next line, one level deeper, so its delimiters and content line up.
			if (isBlockString) {
				newLine(level + 1, false);
				value(value, level + 1);
				return;
			}

			output.append(' ');
			value(value, level);
			return;
		}

		// With comments between the `:` and the value, the line breaks between them are kept, but not the blank lines, and a value on a new line is one level deeper. Nothing may come between a key and its `:`, so the `:` is the character right after the key.
		var previousEnd = key.end() + 1;
		var valueLevel = level;

		for (var comment = peekComment(value.start()); comment != null; comment = peekComment(value.start())) {
			if (writeSeparator(previousEnd, comment.start(), level + 1)) {
				valueLevel = level + 1;
			}

			writeComment(comment);
			previousEnd = comment.end();
		}

		if (isBlockString) {
			newLine(level + 1, false);
			valueLevel = level + 1;
		} else if (writeSeparator(previousEnd, value.start(), level + 1)) {
			valueLevel = level + 1;
		}

		value(value, valueLevel);
	}

	/*
	A space when `end` is on the line of `start` in the source, and otherwise a new line at `level`. Returns whether it started a new line.
	*/
	private boolean writeSeparator(int start, int end, int level) {
		if (isSameLine(start, end)) {
			output.append(' ');
			return false;
		}

		newLine(level, false);
		return true;
	}

	private void value(SyntaxTree.ValueNode node, int level) {
		switch (node) {
			case SyntaxTree.Container container -> {
				var opening = container instanceof SyntaxTree.ObjectNode ? '{' : '[';
				var closing = container instanceof SyntaxTree.ObjectNode ? '}' : ']';
				var items = container.items();

				// Every comment before the container is already written, so a next comment that ends by its end is inside it. A container that holds only comments is not empty.
				if (items.isEmpty() && peekComment(container.end()) == null) {
					output.append(opening).append(closing);
					return;
				}

				if (isSameLine(container.start(), container.end())) {
					oneLine(items, container.start(), container.end(), level, opening, closing);
					return;
				}

				output.append(opening);
				list(items, container.start() + 1, container.end() - 1, level + 1, true);
				newLine(level, false);
				output.append(closing);
			}
			case SyntaxTree.ScalarNode scalar when scalar.isBlockString() -> reindentBlockString(scalar, level);
			case SyntaxTree.ScalarNode scalar -> output.append(text, scalar.start(), scalar.end());
		}
	}

	/*
	Moves a block string to the indentation of `level`. Its content is relative to the closing delimiter's indentation, so replacing that indentation on every line keeps the value. A blank line is left as it is, because it becomes an empty line either way. The first line starts at the opening delimiter, after the indentation that is already written.
	*/
	private void reindentBlockString(SyntaxTree.ScalarNode node, int level) {
		var closingStart = text.lastIndexOf('\n', node.end() - 1) + 1;
		var oldIndentationLength = 0;

		while (closingStart + oldIndentationLength < node.end() && Parser.isSpace(text.charAt(closingStart + oldIndentationLength))) {
			oldIndentationLength++;
		}

		for (var lineStart = node.start(); lineStart <= node.end(); ) {
			var lineEnd = lineEndWithin(lineStart, node.end());

			if (lineStart > node.start()) {
				output.append('\n');
			}

			if (lineStart == node.start() || isBlank(lineStart, lineEnd)) {
				output.append(text, lineStart, lineEnd);
			} else {
				// The parser checked that every line that is not blank starts with the closing line's indentation, so cutting its length removes exactly that.
				output.repeat('\t', level).append(text, lineStart + oldIndentationLength, lineEnd);
			}

			lineStart = lineEnd + 1;
		}
	}

	private boolean isBlank(int start, int end) {
		return trimEnd(start, end) == start;
	}
}
