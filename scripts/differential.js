/*
Differential testing against the JavaScript reference implementation in `../soml-javascript`, which must be checked out next to this repository. Run it with Node.js 26 or later, which the reference implementation needs for Temporal, after `mvn test-compile`:

	node scripts/differential.js [count] [seed]

It generates documents in random spellings, and mutates them byte by byte and token by token. For every input, both implementations must accept it or both must reject it. When both accept it, both must write exactly the same canonical form and formatted form, and give the same text for a random change, or both refuse the change. When both reject it, both must report the error at the same line and column, with the same reason and code frame.

Arguments: the number of inputs (default 20000) and a seed (default 1). The generator comes from the C implementation's `test/differential.js`.
*/
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// `SOML_REFERENCE` can point to another copy of the reference's `source/index.ts`, such as an export of a commit.
const referencePath = process.env.SOML_REFERENCE ?? path.join(import.meta.dirname, '..', '..', 'soml-javascript', 'source', 'index.ts');
const {parse, stringify, format, edit} = await import(referencePath);
const root = path.join(import.meta.dirname, '..');
const count = Number(process.argv[2] ?? 20_000);
let state = BigInt(process.argv[3] ?? 1) * 0x9E37_79B9_7F4A_7C15n || 1n;

function random() {
	state ^= (state << 13n) & 0xFFFF_FFFF_FFFF_FFFFn;
	state ^= state >> 7n;
	state ^= (state << 17n) & 0xFFFF_FFFF_FFFF_FFFFn;
	return Number(state & 0xFF_FFFFn) / 0x100_0000;
}

function integer(minimum, maximum) {
	return minimum + Math.floor(random() * (maximum - minimum + 1));
}

function pick(items) {
	return items[integer(0, items.length - 1)];
}

const keyCharacters = ['a', 'b', 'z', 'A', '0', '9', '_', '-', '.', ' ', '\'', '"', 'é', '😀', '\\', '\t', ':'];

function randomKey() {
	const length = integer(0, 4);
	let key = '';

	for (let index = 0; index < length; index++) {
		key += pick(keyCharacters);
	}

	return key;
}

function writeKey(key) {
	if (key !== '' && /^[\w\-]+$/v.test(key) && random() < 0.7) {
		return key;
	}

	if (!key.includes('\'') && !key.includes('\n') && random() < 0.5) {
		return `'${key}'`;
	}

	return `"${escapeString(key)}"`;
}

function escapeString(string) {
	let output = '';

	for (const character of string) {
		const code = character.codePointAt(0);

		if (character === '\\' || character === '"') {
			output += `\\${character}`;
		} else if (character === '\n') {
			output += random() < 0.5 ? '\\n' : '\\u{a}';
		} else if (character === '\t') {
			output += random() < 0.5 ? '\\t' : '\t';
		} else if (code < 0x20 || code === 0x7F || random() < 0.05) {
			output += `\\u{${code.toString(16)}}`;
		} else {
			output += character;
		}
	}

	return output;
}

const stringCharacters = ['a', ' ', '\'', '"', '\\', '\n', '\t', 'é', '😀', '\u{0}', '\u{1}', '\u{10}', '\u{1F}', '\u{7F}', '\u{2028}', '#', '/*'];

function randomString() {
	const length = integer(0, 6);
	let string = '';

	for (let index = 0; index < length; index++) {
		string += pick(stringCharacters);
	}

	return string;
}

function writeString(string) {
	const choice = random();

	if (choice < 0.3 && !/['\n\u{0}-\u{8}\u{B}-\u{1F}\u{7F}]/v.test(string)) {
		return `'${string}'`;
	}

	if (choice < 0.45 && string !== '' && !/^[\t ]*$|\n[\t ]*\n|\n[\t ]*$|^[\t ]*\n|'''|[\u{0}-\u{8}\u{B}-\u{1F}\u{7F}]/v.test(string) && !string.split('\n').some(line => /^[\t ]*$/v.test(line))) {
		const indentation = pick(['', '\t', '  ']);
		return `'''\n${string.split('\n').map(line => indentation + line).join('\n')}\n${indentation}'''`;
	}

	return `"${escapeString(string)}"`;
}

function withUnderscores(digits) {
	let output = '';

	for (const [index, digit] of [...digits].entries()) {
		if (index > 0 && random() < 0.15) {
			output += '_';
		}

		output += digit;
	}

	return output;
}

function pad(number, width = 2) {
	return String(number).padStart(width, '0');
}

function randomScalar() {
	switch (integer(0, 11)) {
		case 0: {
			return writeString(randomString());
		}

		case 1: {
			const value = BigInt(integer(-1_000_000, 1_000_000)) * BigInt(pick([1, 1, 1000, 1_000_000_007, 9_223_372_036]));
			const spelling = random();

			if (value >= 0n && spelling < 0.15) {
				return `0x${withUnderscores(value.toString(16).toUpperCase())}`;
			}

			if (value >= 0n && spelling < 0.25) {
				return `0o${withUnderscores(value.toString(8))}`;
			}

			if (value >= 0n && spelling < 0.3) {
				return `0b${withUnderscores(value.toString(2))}`;
			}

			return (value < 0n ? '-' : '') + withUnderscores((value < 0n ? -value : value).toString());
		}

		case 2: {
			return pick(['9223372036854775807', '-9223372036854775808', '9223372036854775808', '0', '-0', '00', '0x', '0xff', '-0x1', '1__0', '_1']);
		}

		case 3: {
			const mantissa = `${integer(0, 99_999)}.${integer(0, 999_999)}`;
			const exponent = random() < 0.5 ? `e${pick(['', '-'])}${integer(0, 330)}` : '';
			return pick(['', '-']) + mantissa + exponent;
		}

		case 4: {
			return pick(['infinity', '-infinity', '1e308', '1e309', '5e-324', '2.4703282292062328e-324', '2.4703282292062327e-324', '1e-400', '0.0e999', '1.0', '1e0', '1e-0', '1E5', '1.', '.5', '0.1e-5', '123456789012345678901234567890.5']);
		}

		case 5: {
			const date = `${pad(integer(1, 9999), 4)}-${pad(integer(1, 12))}-${pad(integer(1, 31))}`;
			const time = `T${pad(integer(0, 24))}:${pad(integer(0, 59))}:${pad(integer(0, 60))}`;
			const fraction = random() < 0.3 ? `.${String(integer(0, 999_999_999)).slice(0, integer(1, 10))}` : '';
			const offset = random() < 0.5 ? 'Z' : `${pick(['+', '-'])}${pad(integer(0, 23))}:${pad(integer(0, 59))}`;
			return date + time + fraction + offset;
		}

		case 6: {
			const units = ['h', 'm', 's', 'ms', 'us', 'ns'];
			let text = random() < 0.2 ? '-' : '';
			let unitIndex = integer(0, 5);

			do {
				text += String(integer(0, 99_999));

				if (random() < 0.2) {
					text += `.${integer(0, 999)}`;
				}

				text += units[unitIndex];
				unitIndex += integer(1, 3);
			} while (unitIndex < units.length && random() < 0.5);

			return text;
		}

		case 7: {
			return pick(['true', 'false', 'null', 'nan', 'True', 'yes']);
		}

		case 8: {
			return pick(['2562047h47m16.854775807s', '-2562047h47m16.854775808s', '2562047h47m16.854775808s', '0.5ns', '1.0000000001s', '0.0000000001h', '-0s', '1d', '1h1h']);
		}

		case 9: {
			// Tokens for the diagnostics that tell a number from a word or a unit.
			return pick(['1y', '2yrs', '1y2', '100yen', '3years1h', '2026-09-19T14:00:00 Z', '2026-09-19T14:00:00   +02:00', '2026-09-19T14:00:00 Zz', '5E', '5E-', '5EUR', '5.5E3', '00:1A:2B', '"\\u{D}"', '"\\u{1F600}"', '"\\u{0E9}"', '10 km/h', '10 km/*x*/', '4k_video', '1_e', '1byte', '-verbose', '-nano', '-inf', '-nan', '1e', '1e-', '512 MiB', '1,000', '0644']);
		}

		case 10: {
			return `${pad(integer(0, 10_000), 4)}-${pad(integer(0, 13))}-${pad(integer(0, 32))}${pick(['', '', '', 'T', 'Z', 'foo', 'T12:00:00'])}`;
		}

		default: {
			return String(integer(0, 100));
		}
	}
}

function trivia() {
	return pick(['', '', '', ' ', '\t', ' # comment\n', ' /* c */ ', '\n', '\n\n']);
}

// Inside brackets, a comma, a line break, or both separate items.
function separator() {
	return pick([',', ',', '\n', ',\n', '\n,\n']);
}

function randomValue(depth) {
	const choice = random();

	if (depth > 3 || choice < 0.6) {
		return randomScalar();
	}

	if (choice < 0.8) {
		const items = Array.from({length: integer(0, 4)}, () => trivia() + randomValue(depth + 1) + trivia());
		return `[${items.map((item, index) => (index > 0 ? separator() : '') + item).join('')}${items.length > 0 && random() < 0.5 ? ',' : ''}]`;
	}

	const members = Array.from({length: integer(0, 4)}, () => `${trivia()}${randomEntryKey()}:${pick([' ', '', '\n'])}${randomValue(depth + 1)}${trivia()}`);
	return `{${members.map((member, index) => (index > 0 ? separator() : '') + member).join('')}${members.length > 0 && random() < 0.5 ? ',' : ''}}`;
}

function randomEntryKey() {
	return writeKey(random() < 0.3 ? pick(['a', 'b', 'c']) : randomKey());
}

/*
Nesting around the limit of 100 levels, written with brackets, braces, or a brace-less top level.
*/
function deepDocument() {
	const depth = integer(97, 103);

	switch (integer(0, 2)) {
		case 0: {
			return '['.repeat(depth) + ']'.repeat(depth);
		}

		case 1: {
			return `${'{a: '.repeat(depth - 1)}{}${'}'.repeat(depth - 1)}`;
		}

		default: {
			return `a: ${'['.repeat(depth - 1)}${']'.repeat(depth - 1)}`;
		}
	}
}

function randomDocument() {
	const choice = random();

	if (choice < 0.02) {
		return deepDocument();
	}

	if (choice < 0.15) {
		return randomValue(0);
	}

	const entries = Array.from({length: integer(1, 6)}, () => `${randomEntryKey()}: ${randomValue(1)}`);
	return pick(['', '# start\n', '\n']) + entries.join(pick(['\n', '\n\n', ' # c\n', '\n/* x */\n'])) + pick(['', '\n', ' # end']);
}

const tokens = ['{', '}', '[', ']', ':', ',', '.', '\'', '"', '\n', ' ', '\t', '#', '/*', '*/', '\\', 'a', '1', '-', '_', 'e', 'x', 'T', 'Z', '\'\'\'\n', '\u{FEFF}', '\r', '\u{0}', 'é'];

function mutate(text) {
	const characters = [...text];
	const count = integer(1, 3);

	for (let index = 0; index < count; index++) {
		const position = integer(0, characters.length);
		const operation = random();

		if (operation < 0.4 && characters.length > 0) {
			characters.splice(Math.min(position, characters.length - 1), 1);
		} else if (operation < 0.8) {
			characters.splice(position, 0, pick(tokens));
		} else if (characters.length > 0) {
			characters[Math.min(position, characters.length - 1)] = pick(tokens);
		}
	}

	return characters.join('');
}

function referenceResult(bytes, change) {
	try {
		// Parsed first, so that invalid UTF-8 is a parse error.
		const canonical = stringify(parse(bytes), {canonical: true});
		const text = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes);
		return {ok: true, canonical, formatted: format(text), edited: referenceEdit(text, change)};
	} catch (error) {
		if (error.name === 'ParseError') {
			return {ok: false, message: error.reason, line: error.line, column: error.column, codeFrame: error.codeFrame};
		}

		throw error;
	}
}

/*
The result of one change to a valid document, or `undefined` when the change is an error.
*/
function referenceEdit(text, change) {
	if (change === undefined) {
		return undefined;
	}

	try {
		return edit(text, change.path, change.value === null ? undefined : parse(`[${change.value}]`)[0]);
	} catch (error) {
		if (error instanceof TypeError || error instanceof RangeError) {
			return undefined;
		}

		throw error;
	}
}

/*
The paths of every member and item of a value, as candidates for a change.
*/
function collectPaths(value, path, paths) {
	if (Array.isArray(value)) {
		paths.push([...path, value.length]);

		for (const [index, item] of value.entries()) {
			paths.push([...path, index]);
			collectPaths(item, [...path, index], paths);
		}
	} else if (typeof value === 'object' && value !== null && Object.getPrototypeOf(value) === Object.prototype) {
		paths.push([...path, 'new'], [...path, 'x', 'y']);

		for (const [key, item] of Object.entries(value)) {
			paths.push([...path, key]);
			collectPaths(item, [...path, key], paths);
		}
	}

	return paths;
}

/*
A random change to a valid document: a removal, or a new value as SOML text, at a path that exists, or one that does not.
*/
function randomChange(bytes) {
	let value;

	try {
		value = parse(bytes);
	} catch {
		return undefined;
	}

	const paths = collectPaths(value, [], []);

	if (paths.length === 0) {
		return undefined;
	}

	let newValue = null;

	if (random() < 0.7) {
		do {
			newValue = random() < 0.8 ? randomScalar() : randomValue(1).replaceAll('\n', ' ');
		} while (!isValue(newValue));
	}

	return {path: pick(paths), value: newValue};
}

/*
Whether `text` is one valid value. A JavaScript object puts integer-like keys, such as `9`, before every other key, so the reference cannot keep their order in a new value, and such a value is left out.
*/
function isValue(text) {
	try {
		const values = parse(`[${text}]`);
		return values.length === 1 && !hasIndexLikeKey(values[0]);
	} catch {
		return false;
	}
}

function hasIndexLikeKey(value) {
	if (Array.isArray(value)) {
		return value.some(item => hasIndexLikeKey(item));
	}

	if (typeof value !== 'object' || value === null || Object.getPrototypeOf(value) !== Object.prototype) {
		return false;
	}

	return Object.entries(value).some(([key, item]) => /^(?:0|[1-9]\d*)$/v.test(key) || hasIndexLikeKey(item));
}

const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'soml-differential-'));
const files = [];
const inputs = [];
const changes = [];

for (let index = 0; index < count; index++) {
	let text = randomDocument();

	if (random() < 0.3) {
		text = mutate(text);
	}

	const file = path.join(directory, `${index}.soml`);
	const bytes = Buffer.from(text, 'utf8');

	// Some inputs are invalid UTF-8 on purpose.
	if (random() < 0.02 && bytes.length > 0) {
		bytes[integer(0, bytes.length - 1)] = pick([0x80, 0xC0, 0xED, 0xFF]);
	}

	fs.writeFileSync(file, bytes);
	const change = randomChange(bytes);

	if (change !== undefined) {
		// As SOML, which the Java tool reads with the library itself. Indexes are ints with `integers: 'number'`.
		fs.writeFileSync(`${file}.change.soml`, stringify(change, {integers: 'number'}));
	}

	files.push(file);
	inputs.push(bytes);
	changes.push(change);
}

let output;

try {
	output = execFileSync('java', ['-cp', [path.join(root, 'target', 'classes'), path.join(root, 'target', 'test-classes')].join(path.delimiter), 'sh.soml.DifferentialTool', '-'], {input: files.join('\n'), maxBuffer: 1024 * 1024 * 1024, encoding: 'utf8'});
} finally {
	fs.rmSync(directory, {recursive: true});
}

const results = new Map(output.trim().split('\n').map(line => {
	const result = JSON.parse(line);
	return [result.file, result];
}));

let accepted = 0;
let changed = 0;
const disagreements = [];

for (const [index, file] of files.entries()) {
	const java = results.get(file);

	if (java === undefined) {
		throw new Error(`No result for ${file}`);
	}

	const reference = referenceResult(new Uint8Array(inputs[index]), changes[index]);

	const isSameError = java.ok || (java.line === reference.line && java.column === reference.column && java.message === reference.message && java.codeFrame === reference.codeFrame);

	if (java.ok !== reference.ok || (java.ok && java.canonical !== reference.canonical) || !isSameError) {
		disagreements.push({file, text: inputs[index].toString('utf8'), java: java.ok ? java.canonical : `rejected at ${java.line}:${java.column}: ${java.message}`, reference: reference.ok ? reference.canonical : `rejected at ${reference.line}:${reference.column}: ${reference.message}`});
	} else if (java.ok && java.formatted !== reference.formatted) {
		disagreements.push({file, text: inputs[index].toString('utf8'), javaFormatted: java.formatted, referenceFormatted: reference.formatted});
	} else if (java.ok && java.edited !== reference.edited) {
		disagreements.push({file, text: inputs[index].toString('utf8'), change: changes[index], javaEdited: java.edited ?? '(error)', referenceEdited: reference.edited ?? '(error)'});
	}

	accepted += java.ok ? 1 : 0;
	changed += java.edited === undefined ? 0 : 1;
}

console.log(`${count} inputs, ${accepted} accepted by Java, ${changed} changed, ${disagreements.length} disagreements`);

for (const disagreement of disagreements.slice(0, 10)) {
	console.log(JSON.stringify(disagreement, undefined, '\t'));
}

process.exitCode = disagreements.length > 0 ? 1 : 0;
