/*
 * Copyright (c) 2022, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.regex.tregex.parser.flavors.java;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.regex.AbstractRegexObject;
import com.oracle.truffle.regex.RegexFlags;
import com.oracle.truffle.regex.RegexLanguage;
import com.oracle.truffle.regex.RegexSource;
import com.oracle.truffle.regex.RegexSyntaxException;
import com.oracle.truffle.regex.UnsupportedRegexException;
import com.oracle.truffle.regex.charset.CodePointSet;
import com.oracle.truffle.regex.charset.CodePointSetAccumulator;
import com.oracle.truffle.regex.charset.Constants;
import com.oracle.truffle.regex.charset.UnicodeProperties;
import com.oracle.truffle.regex.errors.ErrorMessages;
import com.oracle.truffle.regex.errors.RbErrorMessages;
import com.oracle.truffle.regex.tregex.buffer.CompilationBuffer;
import com.oracle.truffle.regex.tregex.buffer.IntArrayBuffer;
import com.oracle.truffle.regex.tregex.parser.CaseFoldTable;
import com.oracle.truffle.regex.tregex.parser.RegexASTBuilder;
import com.oracle.truffle.regex.tregex.parser.RegexParser;
import com.oracle.truffle.regex.tregex.parser.Token;
import com.oracle.truffle.regex.tregex.parser.ast.RegexAST;
import com.oracle.truffle.regex.tregex.parser.ast.RegexASTRootNode;
import com.oracle.truffle.regex.util.TBitSet;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Implements the parsing and translating of java.util.Pattern regular expressions to ECMAScript
 * regular expressions.
 */
public final class JavaRegexParser implements RegexParser {

    /**
     * Characters that are considered special in ECMAScript regexes. To match these characters, they
     * need to be escaped using a backslash.
     */
    private static final TBitSet SYNTAX_CHARACTERS = TBitSet.valueOf('$', '(', ')', '*', '+', '.', '?', '[', '\\', ']', '^', '{', '|', '}');
    /**
     * Characters that are considered special in ECMAScript regex character classes.
     */
    private static final TBitSet CHAR_CLASS_SYNTAX_CHARACTERS = TBitSet.valueOf('-', '\\', ']', '^');

    private static final TBitSet PREDEFINED_CHAR_CLASSES = TBitSet.valueOf('D', 'H', 'S', 'V', 'W', 'd', 'h', 's', 'v', 'w');

    /**
     * An enumeration of the possible grammatical categories of Ruby regex terms, for use with
     * parsing quantifiers.
     */
    private enum TermCategory { // TODO what is necessarily needed in JAVA
        /**
         * A lookahead or lookbehind assertion.
         */
        LookAroundAssertion,
        /**
         * An assertion other than lookahead or lookbehind, e.g. beginning-of-string/line,
         * end-of-string/line or (non)-word-boundary assertion.
         */
        OtherAssertion,
        /**
         * A literal character, a character class or a group.
         */
        Atom,
        /**
         * A term followed by any kind of quantifier.
         */
        Quantifier,
        /**
         * Used as the grammatical category when the term in question does not exist.
         */
        None
    }

    /**
     * Metadata about an enclosing capture group.
     */
    private static final class Group {
        /**
         * The index of the capture group.
         */
        public final int groupNumber;

        Group(int groupNumber) {
            this.groupNumber = groupNumber;
        }
    }

//    /**
//     * Characters considered as whitespace in Java's regex verbose mode.
//     */
//    private static final TBitSet WHITESPACE = TBitSet.valueOf('\t', '\n', '\f', '\u000b', '\r', ' '); // '\x0B' = '\u000b'

    static {
        // https://stackoverflow.com/questions/4304928/unicode-equivalents-for-w-and-b-in-java-regular-expressions
        final String escapes = "[\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u180E\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]";
//        final CodePointSet escapesSet = CodePointSet.createNoDedup(
//                0x0009, 0x000d,
//                0x0020, 0x0020,
//                0x0085, 0x0085,
//                0x00a0, 0x00a0,
//                0x1680, 0x1680,
//                0x180e, 0x180e,
//                0x2000, 0x200a,
//                0x2028, 0x2029,
//                0x202f, 0x202f,
//                0x205f, 0x205f,
//                0x3000, 0x3000,
//                0xfeff, 0xfeff);
//        final CodePointSet escapeSSet = CodePointSet.createNoDedup(
//                0x0000, 0x0008,
//                0x000e, 0x001f,
//                0x0021, 0x0084,
//                0x0086, 0x009f,
//                0x00a1, 0x167f,
//                0x1681, 0x180d,
//                0x180f, 0x1fff,
//                0x200b, 0x2027,
//                0x202a, 0x202e,
//                0x2030, 0x205e,
//                0x2060, 0x2fff,
//                0x3001, 0xfefe,
//                0xff00, 0x10ffff
//        );

        final String escapev = "[\\u000A-\\u000D\\u0085\\u2028\\u2029]";
        final String escapeh = "[\\u0009\\u0020\\u00A0\\u1680\\u180E\\u2000-\\u200A\\u202F\\u205F\\u3000]";
        final String escapew = "[\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]]";
        final String escapeb = "(?:(?<=[\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]])(?![\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]])|(?<![\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]])(?=[\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]]))";
        final String escapeB = "(?:(?<=[\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]])(?=[\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]])|(?<![\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]])(?![\\pL\\pM\\p{Nd}\\p{Nl}\\p{Pc}[\\p{InEnclosedAlphanumerics}&&\\p{So}]]))";
        final String escaped = "\\p{Nd}";
        final String escapeD = "\\P{Nd}";
        final String escapeR = "(?:(?>\\u000D\\u000A)|[\\u000A\\u000B\\u000C\\u000D\\u0085\\u2028\\u2029])";
        final String escapeX = "(?>\\PM\\pM*)";
    }

    /**
     * The source object of the input pattern.
     */
    private final RegexSource inSource;

    /**
     * The source of the input pattern.
     */
    private final String inPattern;

    /**
     * The source of the flags of the input pattern.
     */
    private final String inFlags;

    /**
     * Whether or not the parser should attempt to construct an ECMAScript regex during parsing or
     * not. Setting this to {@code false} is not there to gain efficiency, but to avoid triggering
     * {@link UnsupportedRegexException}s when checking for syntax errors.
     */
    private boolean silent;

    /**
     * The index of the next character in {@link #inPattern} to be parsed.
     */
    private int position;


    private Token lastToken;

    /**
     * A {@link StringBuilder} hosting the resulting ECMAScript pattern.
     */
    private StringBuilder outPattern;

    /**
     * The contents of the character class that is currently being parsed.
     */
    private CodePointSetAccumulator curCharClass = new CodePointSetAccumulator();
    /**
     * The characters which are allowed to be full case-foldable (i.e. they are allowed to cross the
     * ASCII boundary) in this character class. This set is constructed as the set of all characters
     * that are included in the character class by being mentioned either:
     * <ul>
     * <li>literally, as in [a]</li>
     * <li>as part of a range, e.g. [a-c]</li>
     * <li>through a POSIX character property other than [[:word:]] and [[:ascii:]]</li>
     * <li>through a Unicode property other than \p{Ascii}</li>
     * <li>through a character type other than \w or \W</li>
     * </ul>
     * This includes character mentioned inside negations, intersections and other nested character
     * classes.
     */
    private final CodePointSetAccumulator fullyFoldableCharacters = new CodePointSetAccumulator();
    /**
     * A temporary buffer for case-folding character classes.
     */
    private final CodePointSetAccumulator charClassTmp = new CodePointSetAccumulator();
    /**
     * When parsing nested character classes, we need several instances of
     * {@link CodePointSetAccumulator}s. In order to avoid having to repeatedly allocate new ones,
     * we return unused instances to this shared pool, to be reused later.
     */
    private final List<CodePointSetAccumulator> charClassPool = new ArrayList<>();

    /**
     * What can be the result of trying to parse a POSIX character class, e.g. [[:alpha:]].
     */
    private enum PosixClassParseResult {
        /**
         * We successfully parsed a (nested) POSIX character class.
         */
        WasNestedPosixClass,
        /**
         * We haven't found a POSIX character class, but we should check for a regular nested
         * character class, e.g. [a[b]].
         */
        TryNestedClass,
        /**
         * We haven't found a POSIX character class. Furthermore, we should *not* treat this as a
         * nested character class, but interpret the opening bracket as a literal character.
         */
        NotNestedClass
    }


    // TODO do we need to do syntax checking purposes?
    /**
     * For syntax checking purposes, we need to know if we are inside a lookbehind assertion, where
     * backreferences are not allowed.
     */
    private int lookbehindDepth;

    /**
     * The grammatical category of the last term parsed. This is needed to detect improper usage of
     * quantifiers.
     */
    private TermCategory lastTerm;

    /**
     * The global flags are the flags given when compiling the regular expression.
     */
    private final JavaFlags globalFlags;
    /**
     * A stack of the locally enabled flags. Ruby enables establishing new flags and modifying flags
     * within the scope of certain expressions.
     */
    private final Deque<JavaFlags> flagsStack;

    /**
     * For syntax checking purposes, we need to maintain some metadata about the current enclosing
     * capture groups.
     */
    private final Deque<JavaRegexParser.Group> groupStack;
    /**
     * A map from names of capture groups to their indices. Is null if the pattern contained no
     * named capture groups so far.
     */
    private final Map<String, Integer> namedCaptureGroups;
    /**
     * A set of capture groups names which occur repeatedly in the expression. Backreferences to
     * such capture groups can refer to either of the homonymous capture groups, depending on which
     * of them matched most recently. Such backreferences are not supported in TRegex.
     */
    private Set<String> ambiguousCaptureGroups;

    /**
     * The number of capture groups encountered in the input pattern so far, i.e. the (zero-based)
     * index of the next capture group to be processed.
     */
    private int groupIndex;
    /**
     * The total number of capture groups present in the expression.
     */
    private int numberOfCaptureGroups;

    private RegexLanguage language;
    private CompilationBuffer compilationBuffer;
    private final RegexASTBuilder astBuilder;

    /**
     * A reusable buffer for storing the codepoint contents of literal strings. We need to scan the
     * entire string before case folding so that we correctly handle cases when several codepoints
     * in sequence can case-unfold to a single codepoint.
     */
    private final IntArrayBuffer codepointsBuffer = new IntArrayBuffer();

    private static RegexFlags makeTRegexFlags(boolean sticky) {
        // We need to set the Unicode flag to true so that character classes will treat the entire
        // Unicode code point range as the set of all characters, not just the UTF-16 code units.
        // We will also need to set the sticky flag to properly reflect both the sticky flag in the
        // incoming regex flags and the any \G assertions used in the expression.
        return RegexFlags.builder().unicode(true).sticky(sticky).build();
    }

    @CompilerDirectives.TruffleBoundary
    public JavaRegexParser(RegexSource inSource, RegexASTBuilder astBuilder) throws RegexSyntaxException {
        this.inSource = inSource;
        this.inPattern = inSource.getPattern();
        this.inFlags = inSource.getFlags();
        this.position = 0;
        this.lastTerm = TermCategory.None;


        this.groupStack = new ArrayDeque<>();
        this.namedCaptureGroups = null;
        this.groupIndex = 0;


        this.globalFlags = new JavaFlags(inFlags);
        this.flagsStack = new LinkedList<>();

        this.astBuilder = astBuilder;
        this.silent = astBuilder == null;
    }

    public static RegexParser createParser(RegexLanguage language, RegexSource source, CompilationBuffer compilationBuffer) throws RegexSyntaxException {
        return new JavaRegexParser(source, new RegexASTBuilder(language, source, makeTRegexFlags(false), compilationBuffer));
    }

    public void validate() throws RegexSyntaxException {
        silent = true;
        parseInternal();
    }

    public RegexAST parse() {
        astBuilder.pushRootGroup();
        parseInternal();
        RegexAST ast = astBuilder.popRootGroup();

        // ast.setFlags(...);

        return ast;
    }

    @Override
    public Map<String, Integer> getNamedCaptureGroups() {
        return Collections.emptyMap();
    }

    @Override
    public AbstractRegexObject getFlags() {
        return globalFlags;
    }

//    @Override
//    public boolean isUnicodePattern() {
//        return false;
//    }


    // The parser
    // equivalent to run() in Ruby

    private void parseInternal() {
        // ...

        flagsStack.push(globalFlags);

        disjunction();

        flagsStack.pop();

        // ...


        // check if we are at the end and if not --> SyntaxError
        if (!atEnd()) {
            assert curChar() == ')';
            throw syntaxErrorHere(RbErrorMessages.UNBALANCED_PARENTHESIS);
        }
    }

    /**
     * Disjunction, the topmost syntactic category, is a series of alternatives separated by
     * vertical bars.
     */
    private void disjunction() {
        while (true) {
            alternative();

            if (match("|")) {
                nextSequence();
                lastTerm = TermCategory.None;
            } else {
                break;
            }
        }
    }

    /**
     * An alternative is a sequence of Terms.
     */
    private void alternative() {
        while (!atEnd() && curChar() != '|'/* && curChar() != ')'*/) {
            Token token = term();
            switch (token.kind) {
                case caret: // TODO java version of it
                    // (?:^|(?<=[\n])(?=.)) only, when multiline flag is set, otherwise just caret
                    if (globalFlags.isMultiline()) {
                        pushGroup(); // (?:
                        addCaret(); // ^
                        nextSequence(); // |
                        pushLookBehindAssertion(false); // (?<=
                        addCharClass(CodePointSet.create('\n')); // [\n]
                        popGroup(); // )
                        pushLookAheadAssertion(false); // (?=
                        addCharClass(inSource.getEncoding().getFullSet()); // .
                        popGroup(); // )
                        popGroup(); // )
                        lastTerm = TermCategory.OtherAssertion;
                    } else {
                        addCaret();
                    }
                    break;
                case dollar: // TODO java version of it
                    // (?:$|(?=[\n])) only, when multiline flag is set, otherwise just dollar
                    if (globalFlags.isMultiline()) {
                        pushGroup(); // (?:
                        addDollar(); // $
                        nextSequence(); // |
                        pushLookAheadAssertion(false); // (?=
                        addCharClass(CodePointSet.create('\n')); // [\n]
                        popGroup(); // )
                        popGroup(); // )
                        lastTerm = TermCategory.OtherAssertion;
                    } else {
                        addDollar();
                    }
                    break;
                case wordBoundary:
                case nonWordBoundary:
                case backReference:
                    break;
                case quantifier:
                    if (astBuilder.getCurTerm() == null) {
                        throw syntaxErrorHere(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
                    }
                    if (globalFlags.isUnicode() && astBuilder.getCurTerm().isLookAheadAssertion()) {
                        throw syntaxErrorHere(ErrorMessages.QUANTIFIER_ON_LOOKAHEAD_ASSERTION);
                    }
                    if (astBuilder.getCurTerm().isLookBehindAssertion()) {
                        throw syntaxErrorHere(ErrorMessages.QUANTIFIER_ON_LOOKBEHIND_ASSERTION);
                    }
                    astBuilder.addQuantifier((Token.Quantifier) token);
                    break;
                case anchor:
                    Token.Anchor anc = (Token.Anchor) token;
                    switch (anc.getAncCps().getHi(0)) {
                        case 'A':
                            addCaret();
                            break;
                        case 'Z':
                            // (?:$|(?=[\r\n]$))
                            pushGroup(); // (?:
                            addDollar(); // $
                            nextSequence(); // |
                            pushLookAheadAssertion(false); // (?=
                            addCharClass(CodePointSet.create('\n', '\n', '\r', '\r')); // [\r\n]
                            addDollar(); // $
                            popGroup(); // )
                            popGroup(); // )
                            break;
                        case 'z':
                            addDollar();
                            break;
                        case 'G':
                            bailOut("\\G anchor is only supported at the beginning of top-level alternatives");
                    }
                case alternation:   // TODO already handled in function disjunction()
                    break;
                case captureGroupBegin:
                    astBuilder.pushCaptureGroup(token);
                    break;
                case nonCaptureGroupBegin:
                    astBuilder.pushGroup(token);
                    break;
                case lookAheadAssertionBegin:
                    astBuilder.pushLookAheadAssertion(token, ((Token.LookAheadAssertionBegin) token).isNegated());
                    break;
                case lookBehindAssertionBegin:
                    astBuilder.pushLookBehindAssertion(token, ((Token.LookBehindAssertionBegin) token).isNegated());
                    break;
                case groupEnd:
                    if (astBuilder.getCurGroup().getParent() instanceof RegexASTRootNode) {
                        throw syntaxErrorHere(ErrorMessages.UNMATCHED_RIGHT_PARENTHESIS);
                    }
                    astBuilder.popGroup(token);
                    break;
                case charClass:
                    astBuilder.addCharClass((Token.CharacterClass) token);
                    break;

            }
            lastToken = token;
        }
    }

    private JavaFlags getLocalFlags() {
        return flagsStack.peek();
    }

    private void setLocalFlags(JavaFlags newLocalFlags) {
        flagsStack.pop();
        flagsStack.push(newLocalFlags);
    }

    /// Input scanning

    private int curChar() {
        return inPattern.codePointAt(position);
    }

    private int consumeChar() {
        final int c = curChar();
        advance();
        return c;
    }

    private String getMany(Predicate<Integer> pred) {
        StringBuilder out = new StringBuilder();
        while (!atEnd() && pred.test(curChar())) {
            out.appendCodePoint(consumeChar());
        }
        return out.toString();
    }

    private String getUpTo(int count, Predicate<Integer> pred) {
        StringBuilder out = new StringBuilder();
        int found = 0;
        while (found < count && !atEnd() && pred.test(curChar())) {
            out.appendCodePoint(consumeChar());
            found++;
        }
        return out.toString();
    }

    private void advance() {
        if (atEnd()) {
            throw syntaxErrorAtEnd(RbErrorMessages.UNEXPECTED_END_OF_PATTERN);
        }
        advance(1);
    }

    private void retreat() {
        advance(-1);
    }

    private void advance(int len) {
        position = inPattern.offsetByCodePoints(position, len);
    }

    private boolean match(String next) {
        if (inPattern.regionMatches(position, next, 0, next.length())) {
            position += next.length();
            return true;
        } else {
            return false;
        }
    }

    private void mustMatch(String next) {
        assert "}".equals(next) || ")".equals(next);
        if (!match(next)) {
            throw syntaxErrorHere("}".equals(next) ? RbErrorMessages.EXPECTED_BRACE : RbErrorMessages.EXPECTED_PAREN);
        }
    }

    private boolean atEnd() {
        return position >= inPattern.length();
    }

    // Error reporting

    private RegexSyntaxException syntaxErrorAtEnd(String message) {
        return RegexSyntaxException.createPattern(inSource, message, inPattern.length() - 1);
    }

    private RegexSyntaxException syntaxErrorHere(String message) {
        return RegexSyntaxException.createPattern(inSource, message, position);
    }

    private RegexSyntaxException syntaxErrorAt(String message, int pos) {
        return RegexSyntaxException.createPattern(inSource, message, pos);
    }

    /**
     * Parses a string of literal characters starting with the {@code firstCodepoint}.
     */
    private void string(int firstCodepoint) {
        codepointsBuffer.clear();
        codepointsBuffer.add(firstCodepoint);

        stringLoop:
        while (!atEnd() && curChar() != '|' && curChar() != ')') {
            int ch = consumeChar();

//            if (getLocalFlags().isExtended()) {
//                if (WHITESPACE.get(ch)) {
//                    continue;
//                }
//                if (ch == '#') {
//                    comment();
//                    continue;
//                }
//            }

            switch (ch) {
                case '\\':
//                    if (isProperEscapeNext()) {
//                        retreat();
//                        break stringLoop;
//                    }
//                    codepointsBuffer.add(fetchEscapedChar());
                    // TODO: add the right escape-method
                    break;
                case '[':
                case '*':
                case '+':
                case '?':
                case '{':
                case '.':
                case '(':
                case '^':
                case '$':
                    retreat();
                    break stringLoop;
                default:
                    System.out.println("B: " + (char) ch);
                    codepointsBuffer.add(ch);
            }
        }

        boolean isQuantifierNext = isQuantifierNext();
        int last = codepointsBuffer.get(codepointsBuffer.length() - 1);

        if (!silent) {
            if (isQuantifierNext) {
                codepointsBuffer.setLength(codepointsBuffer.length() - 1);
            }
//
//            if (getLocalFlags().isIgnoreCase()) {
//                RubyCaseFolding.caseFoldUnfoldString(codepointsBuffer.toArray(), inSource.getEncoding().getFullSet(), astBuilder);
//            } else {
            for (int i = 0; i < codepointsBuffer.length(); i++) {
                addChar(codepointsBuffer.get(i));
            }
//            }
//
            if (isQuantifierNext) {
                buildChar(last);
            }
        }

        lastTerm = TermCategory.Atom;
    }

    /**
     * Adds a matcher for a given character. Since case-folding (IGNORECASE flag) can be enabled, a
     * single character in the pattern could correspond to a variety of different characters in the
     * input.
     *
     * @param codepoint the character to be matched
     */
    private void buildChar(int codepoint) {
        if (!silent) {
//            if (getLocalFlags().isIgnoreCase()) {
//                RubyCaseFolding.caseFoldUnfoldString(new int[]{codepoint}, inSource.getEncoding().getFullSet(), astBuilder);
//            } else {
            addChar(codepoint);
//            }
        }
    }

    /**
     * A comment starts with a '#' and ends at the end of the line. The leading '#' is assumed to
     * have already been parsed.
     */
    private void comment() {
        while (!atEnd()) {
            int ch = consumeChar();
            if (ch == '\\' && !atEnd()) {
                advance();
            } else if (ch == '\n') {
                break;
            }
        }
    }

    /**
     * Parses a term. A term is either:
     * <ul>
     * <li>whitespace (if in vebose mode)</li>
     * <li>a comment (if in verbose mode)</li>
     * <li>an escape sequence</li>
     * <li>a character class</li>
     * <li>a quantifier</li>
     * <li>a group</li>
     * <li>an assertion</li>
     * <li>a literal character</li>
     * </ul>
     */
    private Token term() {
        int ch = consumeChar();

        System.out.println(ch + " : " + (char) ch);
//
//        if (getLocalFlags().isVerbose()) {
//            if (WHITESPACE.get(ch)) {
//                return;
//            }
//            if (ch == '#') {
//                comment();
//                return;
//            }
//        }

        switch (ch) {
            case '\\':
                return escape();
//                break;
            case '[':
                return characterClass();
            case '*':
            case '+':
            case '?':
            case '{':
//                quantifier(ch);
//                return Token.createQuantifier(0, 0, false);   // TODO dummy Quantifier as it is already handled in quantifier(ch);
                return parseQuantifier(ch);
            case '.':
//                if (getLocalFlags().isMultiline()) {
//                    addCharClass(inSource.getEncoding().getFullSet());
//                } else {
//                    addCharClass(CodePointSet.create('\n').createInverse(inSource.getEncoding()));
//                }
//                lastTerm = TermCategory.Atom;
                return Token.createCharClass(Constants.DOT);    // TODO check for DotAll - flag ?
            case '(':
                return parens();    // TODO group() does the closing bracket part already, no need for Token.createGroupEnd()
            case ')':
                return Token.createGroupEnd();
            case '^':
                return Token.createCaret();
            case '$':
                return Token.createDollar();
            default:
//                string(ch);
                if (globalFlags.isIgnoreCase()) {
                    curCharClass.clear();
                    curCharClass.appendRange(ch, ch);

                    boolean wasSingleChar = curCharClass.matchesSingleChar();
                    CaseFoldTable.CaseFoldingAlgorithm caseFolding = globalFlags.isUnicode() ? CaseFoldTable.CaseFoldingAlgorithm.ECMAScriptUnicode : CaseFoldTable.CaseFoldingAlgorithm.ECMAScriptNonUnicode;
                    CaseFoldTable.applyCaseFold(curCharClass, charClassTmp, caseFolding);

                    CodePointSet cps = curCharClass.toCodePointSet();
                    return Token.createCharClass(cps, wasSingleChar);
                } else {
                    return Token.createCharClass(CodePointSet.create(ch), true);
                }
        }
    }

    /**
     * Parses a character class. The syntax of Ruby character classes is quite different to the one
     * in ECMAScript (set intersections, nested char classes, POSIX brackets...). For that reason,
     * we do not transpile the character class expression piece-by-piece, but we parse it completely
     * to build up a character set representation and then we generate an ECMAScript character class
     * expression that describes that character set. Assumes that the opening {@code '['} was
     * already parsed.
     */
//    private void characterClass() {
//        curCharClassClear();
//        collectCharClass();
//        buildCharClass();
//    }
    private Token characterClass() {
        curCharClassClear();
        collectCharClass();
//        buildCharClass();
        return Token.createCharClass(curCharClass.toCodePointSet(), curCharClass.matchesSingleChar());  // TODO maybe change to !invert && curCharClass.matchesSingleChar()
    }

    private void buildCharClass() {
        addCharClass(curCharClass.toCodePointSet());
    }

    private void collectCharClass() {
        boolean negated = false;
        int beginPos = position - 1;
        if (match("^")) {
            negated = true;
        }
        int firstPosInside = position;
        classBody:
        while (true) {
            if (atEnd()) {
                throw syntaxErrorAt(RbErrorMessages.UNTERMINATED_CHARACTER_SET, beginPos);
            }
            int rangeStart = position;
            Optional<Integer> lowerBound;
            boolean wasNestedCharClass = false;
            int ch = consumeChar();
            switch (ch) {
                case ']':
                    if (position == firstPosInside + 1) {
                        lowerBound = Optional.of((int) ']');
                    } else {
                        break classBody;
                    }
                    break;
                case '\\':
                    lowerBound = classEscape();
                    break;
                case '[':
                    if (nestedCharClass()) {
                        wasNestedCharClass = true;
                        lowerBound = Optional.empty();
                    } else {
                        lowerBound = Optional.of(ch);
                    }
                    break;
                case '&':
                    if (match("&")) {
                        charClassIntersection();
                        break classBody;
                    } else {
                        lowerBound = Optional.of(ch);
                    }
                    break;
                default:
                    lowerBound = Optional.of(ch);
                    System.out.println("lower Bound: " + (char) ch);
            }
            // a hyphen following a nested char class is never interpreted as a range operator
            if (!wasNestedCharClass && match("-")) {
                if (atEnd()) {
                    throw syntaxErrorAt(RbErrorMessages.UNTERMINATED_CHARACTER_SET, beginPos);
                }
                Optional<Integer> upperBound;
                ch = consumeChar();
                switch (ch) {
                    case ']':
                        lowerBound.ifPresent(this::curCharClassAddCodePoint);
                        curCharClassAddCodePoint('-');
                        break classBody;
                    case '\\':
                        upperBound = classEscape();
                        break;
                    case '[':
                        if (nestedCharClass()) {
                            wasNestedCharClass = true;
                            upperBound = Optional.empty();
                        } else {
                            upperBound = Optional.of(ch);
                        }
                        break;
                    case '&':
                        if (match("&")) {
                            lowerBound.ifPresent(this::curCharClassAddCodePoint);
                            curCharClassAddCodePoint('-');
                            charClassIntersection();
                            break classBody;
                        } else {
                            upperBound = Optional.of(ch);
                        }
                        break;
                    default:
                        upperBound = Optional.of(ch);
                }
                // if the right operand of a range operator was a nested char class, Ruby drops
                // both the left operand and the range operator
                if (!wasNestedCharClass) {
                    if (lowerBound.isEmpty() || upperBound.isEmpty() || upperBound.get() < lowerBound.get()) {
                        throw syntaxErrorAt(RbErrorMessages.badCharacterRange(inPattern.substring(rangeStart, position)), rangeStart);
                    }
                    curCharClassAddRange(lowerBound.get(), upperBound.get());
                }
            } else lowerBound.ifPresent(this::curCharClassAddCodePoint);
        }
//        if (getLocalFlags().isIgnoreCase()) {
//            caseClosure();
//        }
        if (negated) {
            curCharClass.invert(inSource.getEncoding());
        }

    }

    private void curCharClassClear() {
        curCharClass.clear();
//        if (getLocalFlags().isIgnoreCase()) {
//            fullyFoldableCharacters.clear();
//        }
    }

    private void curCharClassAddCodePoint(int codepoint) {
        curCharClass.addCodePoint(codepoint);
//        if (getLocalFlags().isIgnoreCase()) {
//            fullyFoldableCharacters.addCodePoint(codepoint);
//        }
    }

    private void curCharClassAddCodePoint(int... codepoints) {
        for (int codepoint : codepoints) {
            curCharClassAddCodePoint(codepoint);
        }
    }

    private void curCharClassAddRange(int lower, int upper) {
        curCharClass.addRange(lower, upper);
//        if (getLocalFlags().isIgnoreCase()) {
//            fullyFoldableCharacters.addRange(lower, upper);
//        }
    }

    private CodePointSetAccumulator acquireCodePointSetAccumulator() {
        if (charClassPool.isEmpty()) {
            return new CodePointSetAccumulator();
        } else {
            CodePointSetAccumulator accumulator = charClassPool.remove(charClassPool.size() - 1);
            accumulator.clear();
            return accumulator;
        }
    }

    private void releaseCodePointSetAccumulator(CodePointSetAccumulator accumulator) {
        charClassPool.add(accumulator);
    }

    private void charClassIntersection() {
        CodePointSetAccumulator curCharClassBackup = curCharClass;
        CodePointSetAccumulator foldableCharsBackup = fullyFoldableCharacters;
        curCharClass = acquireCodePointSetAccumulator();
//        if (getLocalFlags().isIgnoreCase()) {
//            fullyFoldableCharacters = acquireCodePointSetAccumulator();
//        }
        collectCharClass();
        curCharClassBackup.intersectWith(curCharClass.get());
        curCharClass = curCharClassBackup;
//        if (getLocalFlags().isIgnoreCase()) {
//            foldableCharsBackup.addSet(fullyFoldableCharacters.get());
//            fullyFoldableCharacters = foldableCharsBackup;
//        }
    }

    /**
     * Parses a nested character class.
     *
     * @return true iff a nested character class was found, otherwise, the input should be treated
     * as literal characters
     */
    private boolean nestedCharClass() {
        CodePointSetAccumulator curCharClassBackup = curCharClass;
        curCharClass = acquireCodePointSetAccumulator();
        PosixClassParseResult parseResult = collectPosixCharClass();
        if (parseResult == PosixClassParseResult.TryNestedClass) {
            collectCharClass();
        }
        curCharClassBackup.addSet(curCharClass.get());
        releaseCodePointSetAccumulator(curCharClass);
        curCharClass = curCharClassBackup;
        return parseResult != PosixClassParseResult.NotNestedClass;
    }

    private PosixClassParseResult collectPosixCharClass() {
        int restorePosition = position;
        if (!match(":")) {
            return PosixClassParseResult.TryNestedClass;
        }
        boolean negated = match("^");
        String className = getMany(c -> c != '\\' && c != ':' && c != ']');
        if (className.length() > 20) {
            position = restorePosition;
            return PosixClassParseResult.NotNestedClass;
        }
//        if (match(":]")) {
//            if (!UNICODE_POSIX_CHAR_CLASSES.containsKey(className)) {
//                throw syntaxErrorAt(RbErrorMessages.INVALID_POSIX_BRACKET_TYPE, restorePosition);
//            }
//            CodePointSet charSet;
//            if (getLocalFlags().isAscii()) {
//                charSet = ASCII_POSIX_CHAR_CLASSES.get(className);
//            } else {
//                assert getLocalFlags().isDefault() || getLocalFlags().isUnicode();
//                charSet = getUnicodePosixCharClass(className);
//            }
//            if (negated) {
//                charSet = charSet.createInverse(inSource.getEncoding());
//            }
//            curCharClass.addSet(charSet);
//            if (getLocalFlags().isIgnoreCase() && !getLocalFlags().isAscii() && !className.equals("word") && !className.equals("ascii")) {
//                fullyFoldableCharacters.addSet(charSet);
//            }
//            return PosixClassParseResult.WasNestedPosixClass;
//        } else {
        position = restorePosition;
        return PosixClassParseResult.TryNestedClass;
//        }
    }

    /**
     * Escape sequence are special sequences starting with a backslash character. When calling this
     * method, the backslash is assumed to have already been parsed.
     * <p>
     * Valid escape sequences are:
     * <ul>
     * <li>assertion escapes</li>
     * <li>character class escapes</li>
     * <li>backreferences</li>
     * <li>named backreferences</li>
     * <li>line breaks</li>
     * <li>extended grapheme clusters</li>
     * <li>keep commands</li>
     * <li>subexpression calls</li>
     * <li>string escapes</li>
     * <li>character escapes</li>
     * </ul>
     */
    private Token escape() {
        if (atEnd()) {
            throw syntaxErrorAtEnd(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
        }
        int ch = consumeChar();
//        if ('1' <= c && c <= '9') {
//            final int restoreIndex = index;
//            final int backRefNumber = (c - '0');
//            if (backRefNumber < numberOfCaptureGroups()) {
//                return Token.createBackReference(backRefNumber);
//            } else if (flags.isUnicode()) {
//                throw syntaxError(ErrorMessages.MISSING_GROUP_FOR_BACKREFERENCE);
//            }
//            index = restoreIndex;
//        }
        switch (ch) {
            case 'A':
            case 'Z':
            case 'z':
            case 'G':
//                addCaret();
                return Token.createAnchor(CodePointSet.create(ch));
//            case 'Z':
////                // (?:$|(?=[\r\n]$))
////                pushGroup(); // (?:
////                addDollar(); // $
////                nextSequence(); // |
////                pushLookAheadAssertion(false); // (?=
////                addCharClass(NEWLINE_RETURN); // [\r\n]
////                addDollar(); // $
////                popGroup(); // )
////                popGroup(); // )
//                return Token.createAnchor("Z", CodePointSet.create('Z'));
//            case 'z':
////                addDollar();
//                return Token.createAnchor("z", CodePointSet.create('z'));
//            case 'G':
////                bailOut("\\G anchor is only supported at the beginning of top-level alternatives");
//                return Token.createAnchor("G", CodePointSet.create('G'));
//            case 'k':
//                if (flags.isUnicode() || hasNamedCaptureGroups()) {
//                    if (atEnd()) {
//                        throw syntaxError(ErrorMessages.ENDS_WITH_UNFINISHED_ESCAPE_SEQUENCE);
//                    }
//                    if (consumeChar() != '<') {
//                        throw syntaxError(ErrorMessages.MISSING_GROUP_NAME);
//                    }
//                    String groupName = parseGroupName();
//                    // backward reference
//                    if (namedCaptureGroups != null && namedCaptureGroups.containsKey(groupName)) {
//                        return Token.createBackReference(namedCaptureGroups.get(groupName));
//                    }
//                    // possible forward reference
//                    Map<String, Integer> allNamedCaptureGroups = getNamedCaptureGroups();
//                    if (allNamedCaptureGroups != null && allNamedCaptureGroups.containsKey(groupName)) {
//                        return Token.createBackReference(allNamedCaptureGroups.get(groupName));
//                    }
//                    throw syntaxError(ErrorMessages.MISSING_GROUP_FOR_BACKREFERENCE);
//                } else {
//                    return charClass(c);
//                }
//            case 'b':
//                return Token.createWordBoundary();
//            case 'B':
//                return Token.createNonWordBoundary();
            default:
                // Here we differentiate the case when parsing one of the six basic pre-defined
                // character classes (\w, \W, \d, \D, \s, \S) and Unicode character property
                // escapes. Both result in sets of characters, but in the former case, we can skip
                // the case-folding step in the `charClass` method and call `Token::createCharClass`
                // directly.
                if (isPredefCharClass(ch)) {
                    return Token.createCharClass(parsePredefCharClass(ch));
//                } else if (globalFlags.isUnicode() && (ch == 'p' || ch == 'P')) {
//                    return charClass(parseUnicodeCharacterProperty(ch == 'P'));
                } else {
                    return Token.createCharClass(CodePointSet.create(parseEscapeChar(ch, false)));
                }
        }
    }

    private static boolean isPredefCharClass(int c) {
        return PREDEFINED_CHAR_CLASSES.get(c);
    }

    // Note that the CodePointSet returned by this function has already been
    // case-folded and negated.
    // TODO combine the other Escape and PredefCharClass Methods!!!
    private CodePointSet parsePredefCharClass(int c) {
        switch (c) {
            case 's':
                if (inSource.getOptions().isU180EWhitespace()) {
                    return Constants.LEGACY_WHITE_SPACE;
                } else {
                    return Constants.WHITE_SPACE;
                }


//                curCharClassClear();
//                curCharClassAddRange(0x000A, 0x000D);
//                curCharClassAddRange(0x2000, 0x200A);
//                curCharClassAddCodePoint(0x0020, 0x0085, 0x00A0, 0x1680, 0x180E, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000);
//
//
//                System.out.println(curCharClass.toCodePointSet().equals(Constants.WHITE_SPACE) ? "True" : "False");
//
//                return curCharClass.toCodePointSet();

            case 'S':
                if (inSource.getOptions().isU180EWhitespace()) {
                    return Constants.LEGACY_NON_WHITE_SPACE;
                } else {
                    return Constants.NON_WHITE_SPACE;
                }
//                curCharClassClear();
//                curCharClassAddRange(0x000A, 0x000D);
//                curCharClassAddRange(0x2000, 0x200A);
//                curCharClassAddCodePoint(0x0020, 0x0085, 0x00A0, 0x1680, 0x180E, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000);
//                curCharClass.invert(inSource.getEncoding());
//
//                return curCharClass.toCodePointSet();

            case 'd':
                System.out.println(Constants.DIGITS.equals(UnicodeProperties.getProperty("Nd")) ? "True" : "False");
//                return Constants.DIGITS;
                // TODO which one to use?       check if UNICODE_CHARACTER_CLASS flag is set (above when it is not set, below when it is set)
                // dummy project, util with /d  und testen falls noch mehr unicodes unterstützt werden als 0 - 9
                return UnicodeProperties.getProperty("Nd");
            case 'D':
                return Constants.NON_DIGITS;
            case 'w':
                if (globalFlags.isUnicode() && globalFlags.isIgnoreCase()) {
                    return Constants.WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.WORD_CHARS;
                }
            case 'W':
                if (globalFlags.isUnicode() && globalFlags.isIgnoreCase()) {
                    return Constants.NON_WORD_CHARS_UNICODE_IGNORE_CASE;
                } else {
                    return Constants.NON_WORD_CHARS;
                }
            case 'v':
                return CodePointSet.createNoDedup(
                        0x000a, 0x000d,
                        0x0085, 0x0085,
                        0x2028, 0x2029
                );
            case 'V':
                return CodePointSet.createNoDedup(
                        0x0000, 0x0009,
                        0x000e, 0x0084,
                        0x0086, 0x2027,
                        0x202a, 0x10ffff
                );
            case 'h':
                return CodePointSet.createNoDedup(
                        0x0009, 0x0009,
                        0x0020, 0x0020,
                        0x00a0, 0x00a0,
                        0x1680, 0x1680,
                        0x2000, 0x200a,
                        0x202f, 0x202f,
                        0x205f, 0x205f,
                        0x3000, 0x3000
                );
            case 'H':
                return CodePointSet.createNoDedup(
                        0x0000, 0x0008,
                        0x000a, 0x001f,
                        0x0021, 0x009f,
                        0x00a1, 0x180d,
                        0x180f, 0x1fff,
                        0x202b, 0x202e,
                        0x2030, 0x205e,
                        0x2060, 0x2fff,
                        0x3001, 0x10ffff
                );
            default:
                throw CompilerDirectives.shouldNotReachHere();
        }
    }

    // TODO: do something like parseUnicodeEscapeChar() or parseUnicodeCharacterProperty() here too maybe?

    private int parseEscapeChar(int c, boolean inCharClass) throws RegexSyntaxException {
        if (inCharClass && c == 'b') {
            return '\b';
        }
        switch (c) {
            case '0':
                if (globalFlags.isUnicode() && !atEnd() && isDecDigit(curChar())) {
                    throw syntaxErrorHere(ErrorMessages.INVALID_ESCAPE);
                }
//                if (!globalFlags.isUnicode() && !atEnd() && isOctDigit(curChar())) {
//                    return parseOctal(0);
//                }
                return '\0';
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'f':
                return '\f';
            case 'r':
                return '\r';
//            case 's':
////                CodePointSet cps = CodePointSet.create(0x0020, 0x0085, 0x00A0, 0x1680, 0x180E, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000);
////                cps.inv
//
//                curCharClassClear();
//                curCharClassAddRange(0x000A, 0x000D);
//                curCharClassAddRange(0x2000, 0x200A);
//                curCharClassAddCodePoint(0x0020, 0x0085, 0x00A0, 0x1680, 0x180E, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000);
//                addCharClass();
//            case 'c':
//                if (atEnd()) {
//                    retreat();
//                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
//                }
//                final char controlLetter = curChar();
//                if (!globalFlags.isUnicode() && (isDecDigit(controlLetter) || controlLetter == '_') && inCharClass) {
//                    advance();
//                    return controlLetter % 32;
//                }
//                if (!('a' <= controlLetter && controlLetter <= 'z' || 'A' <= controlLetter && controlLetter <= 'Z')) {
//                    retreat();
//                    return escapeCharSyntaxError('\\', ErrorMessages.INVALID_CONTROL_CHAR_ESCAPE);
//                }
//                advance();
//                return Character.toUpperCase(controlLetter) - ('A' - 1);
//            case 'u':
//                final int unicodeEscape = parseUnicodeEscapeChar();
//                return unicodeEscape < 0 ? c : unicodeEscape;
//            case 'x':
//                final int value = parseHex(2, 2, 0xff, ErrorMessages.INVALID_ESCAPE);
//                return value < 0 ? c : value;
//            case '-':
//                if (!inCharClass) {
//                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
//                }
//                return c;
            default:
                if (!globalFlags.isUnicode() && isOctDigit(c)) {
                    return parseOctal(c - '0');
                }
                if (!SYNTAX_CHARACTERS.get(c)) {
                    if (globalFlags.isUnicode()) {
                        throw syntaxErrorHere(ErrorMessages.INVALID_ESCAPE);
                    }
//                    return escapeCharSyntaxError(c, ErrorMessages.INVALID_ESCAPE);
                }
                return c;
        }
    }

    private int parseOctal(int firstDigit) {
        int ret = firstDigit;
        for (int i = 0; !atEnd() && isOctDigit(curChar()) && i < 2; i++) {
            if (ret * 8 > 255) {
                return ret;
            }
            ret *= 8;
            ret += consumeChar() - '0';
        }
        return ret;
    }

    /**
     * Like {@link #escape}, but restricted to the forms of escapes usable in character classes.
     * This includes character escapes and character class escapes, but not assertion escapes or
     * backreferences.
     *
     * @return {@code Optional.of(ch)} if the escape sequence was a character escape sequence for
     * some character {@code ch}; {@code Optional.empty()} if it was a character class
     * escape sequence
     */
    private Optional<Integer> classEscape() {
        if (categoryEscape(true)) {
            return Optional.empty();
        }
        Optional<Integer> characterEscape = characterEscape();
        if (characterEscape.isPresent()) {
            return characterEscape;
        } else {
            return Optional.of(fetchEscapedChar());
        }
    }

    /**
     * Parses a character escape sequence. A character escape sequence can be one of the following:
     * <ul>
     * <li>a hexadecimal escape sequence</li>
     * <li>a unicode escape sequence</li>
     * <li>an octal escape sequence</li>
     * </ul>
     */
    private Optional<Integer> characterEscape() {   // TODO combine the other escape method with this one?
        int beginPos = position;
        switch (curChar()) {
            case 'x': {
                advance();
                String code = getUpTo(2, JavaRegexParser::isHexDigit);
                int byteValue = Integer.parseInt(code, 16);
                if (byteValue > 0x7F) {
                    // This is a non-ASCII byte escape. The escaped character might be part of a
                    // multibyte sequece. These sequences are encoding specific and supporting
                    // them would mean having to include decoders for all of Ruby's encodings.
                    // Fortunately, TruffleRuby decodes these for us and replaces them with
                    // verbatim characters or other forms of escape. Therefore, this can be
                    // trigerred by either:
                    // *) TruffleRuby's ClassicRegexp#preprocess was not called on the input
                    // *) TruffleRuby's ClassicRegexp#preprocess emitted a non-ASCII \\x escape
                    bailOut("unsupported multibyte escape");
                }
                return Optional.of(byteValue);
            }
            case 'u': {
                advance();
                String code;
                if (match("{")) {
                    code = getMany(JavaRegexParser::isHexDigit);
                    mustMatch("}");
                } else {
                    code = getUpTo(4, JavaRegexParser::isHexDigit);
                    if (code.length() < 4) {
                        throw syntaxErrorAt(RbErrorMessages.incompleteEscape(code), beginPos);
                    }
                }
                try {
                    int codePoint = Integer.parseInt(code, 16);
                    if (codePoint > 0x10FFFF) {
                        throw syntaxErrorAt(RbErrorMessages.invalidUnicodeEscape(code), beginPos);
                    }
                    return Optional.of(codePoint);
                } catch (NumberFormatException e) {
                    throw syntaxErrorAt(RbErrorMessages.badEscape(code), beginPos);
                }
            }
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7': {
                String code = getUpTo(3, JavaRegexParser::isOctDigit);
                int codePoint = Integer.parseInt(code, 8);
                if (codePoint > 0xFF) {
                    throw syntaxErrorAt(RbErrorMessages.TOO_BIG_NUMBER, beginPos);
                }
                return Optional.of(codePoint);
            }
            default:
                return Optional.empty();
        }
    }

    /**
     * Parses an escaped codepoint. This definition is distinct from {@link #characterEscape()},
     * because the escape sequences below do not break strings (i.e. they can be found inside
     * strings and should be case-unfolded along with their surroundings).
     * <p>
     * This method assumes that the leading backslash was already consumed.
     * <p>
     * This method handles the following escape sequences:
     * <ul>
     * <li>\a, \b, \e. \f, \n, \r, \t and \v</li>
     * <li>\cX, \C-X and \M-X control characters</li>
     * <li>syntax character escapes like \., \* or \\</li>
     * <li>any superfluous uses of backslash, e.g. \: or \"</li>
     * </ul>
     *
     * @return the escaped codepoint
     */
    private int fetchEscapedChar() {
        int beginPos = position;
        int ch = consumeChar();
        switch (ch) {
            case 'a':
                return '\u0007';
            case 'b':
                return '\b';
            case 'e':
                return '\u001b';
            case 'f':
                return '\f';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 't':
                return '\t';
//            case 'v':
//                return '\u000b';
            case 'c':
            case 'C': {
                if (atEnd()) {
                    throw syntaxErrorAt(RbErrorMessages.END_PATTERN_AT_CONTROL, beginPos);
                }
                if (ch == 'C' && !match("-")) {
                    throw syntaxErrorAt(RbErrorMessages.INVALID_CONTROL_CODE_SYNTAX, beginPos);
                }
                int c = consumeChar();
                if (c == '?') {
                    return 0177;
                }
                if (c == '\\') {
                    c = fetchEscapedChar();
                }
                return c & 0x9f;
            }
            case 'M': {
                if (atEnd()) {
                    throw syntaxErrorAt(RbErrorMessages.END_PATTERN_AT_META, beginPos);
                }
                if (!match("-")) {
                    throw syntaxErrorAt(RbErrorMessages.INVALID_META_CODE_SYNTAX, beginPos);
                }
                if (atEnd()) {
                    throw syntaxErrorAt(RbErrorMessages.END_PATTERN_AT_META, beginPos);
                }
                int c = consumeChar();
                if (c == '\\') {
                    c = fetchEscapedChar();
                }
                return (c & 0xff) | 0x80;
            }
            default:
                return ch;
        }
    }

    /**
     * Tries to parse a character class escape. The following character classes are available:
     * <ul>
     * <li>\d (digits)</li>
     * <li>\D (non-digits)</li>
     * <li>\d (hexadecimal digits)</li>
     * <li>\D (not hexadecimal digits)</li>
     * <li>\s (spaces)</li>
     * <li>\S (non-spaces)</li>
     * <li>\w (word characters)</li>
     * <li>\W (non-word characters)</li>
     * <li>\p{...} (Unicode properties)</li>
     * </ul>
     *
     * @param inCharClass whether or not this escape was found in a character class
     * @return {@code true} iff a category escape was found
     */
    private boolean categoryEscape(boolean inCharClass) {
        int restorePosition = position;
        switch (curChar()) {
//            case 'd':
//            case 'D':
//            case 'h':
//            case 'H':
//            case 's':
//            case 'S':
//            case 'w':
//            case 'W':
//                char className = (char) curChar();
//                advance();
//                CodePointSet charSet;
//                if (getLocalFlags().isAscii() || getLocalFlags().isDefault()) {
//                    charSet = ASCII_CHAR_CLASSES.get(className);
//                } else {
//                    assert getLocalFlags().isUnicode();
//                    charSet = getUnicodeCharClass('w');
//                }
//                if (inCharClass) {
//                    curCharClass.addSet(charSet);
//                    if (getLocalFlags().isIgnoreCase() && className != 'w' && className != 'W') {
//                        fullyFoldableCharacters.addSet(charSet);
//                    }
//                } else {
//                    addCharClass(charSet);
//                }
//                return true;
//            case 'p':
//            case 'P':
//                boolean capitalP = curChar() == 'P';
//                advance();
//                if (match("{")) {
//                    String propertySpec = getMany(c -> c != '}');
//                    if (atEnd()) {
//                        position = restorePosition;
//                        return false;
//                    } else {
//                        advance();
//                    }
//                    boolean caret = propertySpec.startsWith("^");
//                    boolean negative = (capitalP || caret) && (!capitalP || !caret);
//                    if (caret) {
//                        propertySpec = propertySpec.substring(1);
//                    }
//                    CodePointSet property;
//                    if (UNICODE_POSIX_CHAR_CLASSES.containsKey(propertySpec.toLowerCase())) {
//                        property = getUnicodePosixCharClass(propertySpec.toLowerCase());
//                    } else if (UnicodeProperties.isSupportedGeneralCategory(propertySpec, true)) {
//                        property = trimToEncoding(UnicodeProperties.getProperty("General_Category=" + propertySpec, true));
//                    } else if (UnicodeProperties.isSupportedScript(propertySpec, true)) {
//                        property = trimToEncoding(UnicodeProperties.getProperty("Script=" + propertySpec, true));
//                    } else if (UnicodeProperties.isSupportedProperty(propertySpec, true)) {
//                        property = trimToEncoding(UnicodeProperties.getProperty(propertySpec, true));
//                    } else {
//                        bailOut("unsupported Unicode property " + propertySpec);
//                        // So that the property variable is always written to.
//                        property = CodePointSet.getEmpty();
//                    }
//                    if (negative) {
//                        property = property.createInverse(Encodings.UTF_32);
//                    }
//                    if (inCharClass) {
//                        curCharClass.addSet(property);
//                        if (getLocalFlags().isIgnoreCase() && !propertySpec.equalsIgnoreCase("ascii")) {
//                            fullyFoldableCharacters.addSet(property);
//                        }
//                    } else {
//                        addCharClass(property);
//                    }
//                    return true;
//                } else {
//                    position = restorePosition;
//                    return false;
//                }
            default:
                return false;
        }
    }

    private void bailOut(String reason) throws UnsupportedRegexException {
        if (!silent) {
            throw new UnsupportedRegexException(reason);
        }
    }

    /// RegexASTBuilder method wrappers

    private void pushGroup() {
        if (!silent) {
            astBuilder.pushGroup();
        }
    }

    private void pushCaptureGroup() {
        if (!silent) {
            astBuilder.pushCaptureGroup();
        }
    }

    private void pushLookAheadAssertion(boolean negate) {
        if (!silent) {
            astBuilder.pushLookAheadAssertion(negate);
        }
    }

    private void pushLookBehindAssertion(boolean negate) {
        if (!silent) {
            astBuilder.pushLookBehindAssertion(negate);
        }
    }

    private void popGroup() {
        if (!silent) {
            astBuilder.popGroup();
        }
    }

    private void nextSequence() {
        if (!silent) {
            astBuilder.nextSequence();
        }
    }

    private void addCharClass(CodePointSet charSet) {
        if (!silent) {
            astBuilder.addCharClass(charSet);
        }
    }

    private void addChar(int codepoint) {
        if (!silent) {
            astBuilder.addCharClass(CodePointSet.create(codepoint), true);
        }
    }

    private void addBackReference(int groupNumber) {
        if (!silent) {
            astBuilder.addBackReference(groupNumber);
        }
    }

    private void addSubexpressionCall(int groupNumber) {
        if (!silent) {
            astBuilder.addSubexpressionCall(groupNumber);
        }
    }

    private void addCaret() {
        if (!silent) {
            astBuilder.addCaret();
        }
    }

    private void addDollar() {
        if (!silent) {
            astBuilder.addDollar();
        }
    }

    private void addQuantifier(Token.Quantifier quantifier) {
        if (!silent) {
            astBuilder.addQuantifier(quantifier);
        }
    }

    private void addDeadNode() {
        if (!silent) {
            astBuilder.addDeadNode();
        }
    }

    private void wrapCurTermInGroup() {
        if (!silent) {
            astBuilder.wrapCurTermInGroup();
        }
    }

    // Character predicates

    private static boolean isOctDigit(int c) {
        return c >= '0' && c <= '7';
    }

    private static boolean isDecDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static boolean isAscii(int c) {
        return c < 128;
    }

    // ---- Quantifier

    private static final EnumSet<Token.Kind> QUANTIFIER_PREV = EnumSet.of(Token.Kind.charClass, Token.Kind.groupEnd, Token.Kind.backReference);

    private Token parseQuantifier(int c) throws RegexSyntaxException {
        int min;
        int max = -1;
        boolean greedy;
        if (c == '{') {
            final int resetIndex = position;
            BigInteger literalMin = parseDecimal();
            if (literalMin.compareTo(BigInteger.ZERO) < 0) {
                return countedRepetitionSyntaxError(resetIndex);
            }
            min = literalMin.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 ? literalMin.intValue() : -1;
            if (consumingLookahead(",}")) {
                greedy = !consumingLookahead("?");
            } else if (consumingLookahead("}")) {
                max = min;
                greedy = !consumingLookahead("?");
            } else {
                BigInteger literalMax;
                if (!consumingLookahead(",") || (literalMax = parseDecimal()).compareTo(BigInteger.ZERO) < 0 || !consumingLookahead("}")) {
                    return countedRepetitionSyntaxError(resetIndex);
                }
                max = literalMax.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 ? literalMax.intValue() : -1;
                greedy = !consumingLookahead("?");
                if (literalMin.compareTo(literalMax) > 0) {
                    throw syntaxErrorHere(ErrorMessages.QUANTIFIER_OUT_OF_ORDER);
                }
            }
        } else {
            greedy = !consumingLookahead("?");
            min = c == '+' ? 1 : 0;
            if (c == '?') {
                max = 1;
            }
        }
        if (lastToken == null) {
            throw syntaxErrorHere(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        if (lastToken.kind == Token.Kind.quantifier) {
            throw syntaxErrorHere(ErrorMessages.QUANTIFIER_ON_QUANTIFIER);
        }
        if (!QUANTIFIER_PREV.contains(lastToken.kind)) {
            throw syntaxErrorHere(ErrorMessages.QUANTIFIER_WITHOUT_TARGET);
        }
        return Token.createQuantifier(min, max, greedy);
    }

    private BigInteger parseDecimal() {
        if (atEnd() || !isDecDigit(curChar())) {
            return BigInteger.valueOf(-1);
        }
        return parseDecimal(BigInteger.ZERO);
    }

    private BigInteger parseDecimal(BigInteger firstDigit) {
        BigInteger ret = firstDigit;
        while (!atEnd() && isDecDigit(curChar())) {
            ret = ret.multiply(BigInteger.TEN);
            ret = ret.add(BigInteger.valueOf(consumeChar() - '0'));
        }
        return ret;
    }

    private Token countedRepetitionSyntaxError(int resetIndex) throws RegexSyntaxException {
        if (globalFlags.isUnicode()) {
            throw syntaxErrorHere(ErrorMessages.INCOMPLETE_QUANTIFIER);
        }
        position = resetIndex;
        return charClass('{');
    }

    private Token charClass(int codePoint) {
        if (globalFlags.isIgnoreCase()) {
            curCharClass.clear();
            curCharClass.appendRange(codePoint, codePoint);
            return charClass(false);
        } else {
            return Token.createCharClass(CodePointSet.create(codePoint), true);
        }
    }

    private Token charClass(CodePointSet codePointSet) {
        if (globalFlags.isIgnoreCase()) {
            curCharClass.clear();
            curCharClass.addSet(codePointSet);
            return charClass(false);
        } else {
            return Token.createCharClass(codePointSet);
        }
    }

    private Token charClass(boolean invert) {
        boolean wasSingleChar = !invert && curCharClass.matchesSingleChar();
        if (globalFlags.isIgnoreCase()) {
            CaseFoldTable.CaseFoldingAlgorithm caseFolding = globalFlags.isUnicode() ? CaseFoldTable.CaseFoldingAlgorithm.ECMAScriptUnicode : CaseFoldTable.CaseFoldingAlgorithm.ECMAScriptNonUnicode;
            CaseFoldTable.applyCaseFold(curCharClass, charClassTmp, caseFolding);
        }
        CodePointSet cps = curCharClass.toCodePointSet();
        return Token.createCharClass(invert ? cps.createInverse(inSource.getEncoding()) : cps, wasSingleChar);
    }

    private boolean consumingLookahead(String match) {  // TODO: position messes things up here somehow, fix it
        final boolean matches = lookahead(match);
        if (matches) {
//            position--;
            advance(match.length());
        }
        return matches;
    }

    private boolean lookahead(String match) {
        if (inPattern.length() - position < match.length()) {
            return false;
        }
        return inPattern.regionMatches(position, match, 0, match.length());
    }

//    private static final class Quantifier {
//        public static final int INFINITY = -1;
//
//        public int lower;
//        public int upper;
//        public boolean greedy;
//
//        Quantifier(int lower, int upper, boolean greedy) {
//            this.lower = lower;
//            this.upper = upper;
//            this.greedy = greedy;
//        }
//
//        @Override
//        public String toString() {
//            StringBuilder output = new StringBuilder();
//            if (lower == 0 && upper == INFINITY) {
//                output.append("*");
//            } else if (lower == 1 && upper == INFINITY) {
//                output.append("+");
//            } else if (lower == 0 && upper == 1) {
//                output.append("?");
//            } else {
//                output.append("{");
//                output.append(lower);
//                output.append(",");
//                if (upper != INFINITY) {
//                    output.append(upper);
//                }
//                output.append("}");
//            }
//            if (!greedy) {
//                output.append("?");
//            }
//            return output.toString();
//        }
//    }

//    private void quantifier(int ch) {
//        int start = position - 1;
//        Quantifier quantifier = parseQuantifier(ch);
//        if (quantifier != null) {
//            buildQuantifier(quantifier, start);
//        } else {
//            string(consumeChar());
//        }
//    }

    /**
     * Parses a quantifier whose first character is the argument {@code ch}.
     */
//    private Quantifier parseQuantifier(int ch) {
//        int start = position - 1;
//        if (ch == '{') {
//            if (match("}") || match(",}")) {
//                position = start;
//                return null;
//            } else {
//                Optional<BigInteger> lowerBound = Optional.empty();
//                Optional<BigInteger> upperBound = Optional.empty();
//                boolean canBeNonGreedy = true;
//                String lower = getMany(JavaRegexParser::isDecDigit);
//                if (!lower.isEmpty()) {
//                    lowerBound = Optional.of(new BigInteger(lower));
//                }
//                if (match(",")) {
//                    String upper = getMany(JavaRegexParser::isDecDigit);
//                    if (!upper.isEmpty()) {
//                        upperBound = Optional.of(new BigInteger(upper));
//                    }
//                } else {
//                    upperBound = lowerBound;
//                    canBeNonGreedy = false;
//                }
//                if (!match("}")) {
//                    position = start;
//                    return null;
//                }
//                if (lowerBound.isPresent() && upperBound.isPresent() && lowerBound.get().compareTo(upperBound.get()) > 0) {
//                    throw syntaxErrorAt(RbErrorMessages.MIN_REPEAT_GREATER_THAN_MAX_REPEAT, start);
//                }
//                boolean greedy = true;
//                if (canBeNonGreedy && match("?")) {
//                    greedy = false;
//                }
//                return new Quantifier(lowerBound.orElse(BigInteger.ZERO).intValue(),
//                        upperBound.orElse(BigInteger.valueOf(Quantifier.INFINITY)).intValue(),
//                        greedy);
//            }
//        } else {
//            int lower;
//            int upper;
//            switch (ch) {
//                case '*':
//                    lower = 0;
//                    upper = Quantifier.INFINITY;
//                    break;
//                case '+':
//                    lower = 1;
//                    upper = Quantifier.INFINITY;
//                    break;
//                case '?':
//                    lower = 0;
//                    upper = 1;
//                    break;
//                default:
//                    throw new IllegalStateException("should not reach here");
//            }
//            boolean greedy = true;
//            if (match("?")) {
//                greedy = false;
//            } else if (match("+")) {
//                bailOut("possessive quantifiers not supported");
//            }
//            return new Quantifier(lower, upper, greedy);
//        }
//    }

//    private void buildQuantifier(Quantifier quantifier, int start) {
//        switch (lastTerm) {
//            case None:
//                throw syntaxErrorAt(RbErrorMessages.NOTHING_TO_REPEAT, start);
//            case LookAroundAssertion:
//                // A lookaround assertion might contain capture groups and thus have side effects.
//                // ECMAScript regular expressions do not accept extraneous empty matches. Therefore,
//                // an expression like /(?:(?=(a)))?/ would capture the 'a' in a capture group in
//                // Ruby but it would not do so in ECMAScript. To avoid this, we bail out on
//                // quantifiers on complex assertions (i.e. lookaround assertions), which might
//                // contain capture groups.
//                // NB: This could be made more specific. We could target only lookaround assertions
//                // which contain capture groups and only when the quantifier is actually optional
//                // (min = 0, such as ?, *, or {,x}).
//                bailOut("quantifiers on lookaround assertions not supported");
//                lastTerm = TermCategory.Quantifier;
//                break;
//            case Quantifier:
//            case OtherAssertion:
//                wrapCurTermInGroup();
//                addQuantifier(Token.createQuantifier(quantifier.lower, quantifier.upper, quantifier.greedy));
//                lastTerm = TermCategory.Quantifier;
//                break;
//            case Atom:
//                addQuantifier(Token.createQuantifier(quantifier.lower, quantifier.upper, quantifier.greedy));
//                lastTerm = TermCategory.Quantifier;
//                break;
//        }
//    }

    /**
     * Indicates whether a quantifier is coming up next.
     */
    public boolean isQuantifierNext() {
        if (atEnd()) {
            return false;
        }
        switch (curChar()) {
            case '*':
            case '+':
            case '?':
                return true;
            case '{':
                int oldPosition = position;
                try {
                    advance();
                    if (match("}") || match(",}")) {
                        return false;
                    } else {
                        // lower bound
                        getMany(JavaRegexParser::isDecDigit);
                        // upper bound
                        if (match(",")) {
                            getMany(JavaRegexParser::isDecDigit);
                        }
                        return match("}");
                    }
                } finally {
                    position = oldPosition;
                }
            default:
                return false;
        }
    }

    /**
     * Parses one of the many syntactic forms that start with a parenthesis, assuming that the
     * parenthesis was already parsed. These consist of the following:
     * <ul>
     * <li>non-capturing groups (?:...)</li>
     * <li>comments (?#...)</li>
     * <li>positive and negative lookbehind assertions, (?<=...) and (?<!...)</li>
     * <li>positive and negative lookahead assertions (?=...) and (?!...)</li>
     * <li>named capture groups (?P<name>...)</li>
     * <li>atomic groups (?>...)</li>
     * <li>conditional backreferences (?(id/name)yes-pattern|no-pattern)</li>
     * <li>inline local and global flags, (?aiLmsux-imsx:...) and (?aiLmsux)</li>
     * <li>regular capture groups (...)</li>
     * </ul>
     */
    private Token parens() {
        if (atEnd()) {
            throw syntaxErrorAtEnd(RbErrorMessages.UNTERMINATED_SUBPATTERN);
        }
        if (match("?")) {
            final int ch1 = consumeChar();
            switch (ch1) {
                case ':':
//                    group(false);
                    return Token.createNonCaptureGroupBegin();

//                case '#':
//                    parenComment();
//                    break;

                case '<': {
                    final int ch2 = consumeChar();
                    switch (ch2) {
                        case '=':
                            return Token.createLookBehindAssertionBegin(false);
//                            lookbehind(false);
                        case '!':
                            return Token.createLookBehindAssertionBegin(true);
//                            lookbehind(true);
                        default:
                            retreat();
                            parseGroupName('>');
//                            group(true);
                            return Token.createCaptureGroupBegin();
                    }
                }

                case '=':
//                    lookahead(false);
                    return Token.createLookBehindAssertionBegin(false);

                case '!':
//                    lookahead(true);
                    return Token.createLookBehindAssertionBegin(true);

                case '>':
                    if (!inSource.getOptions().isIgnoreAtomicGroups()) {
                        bailOut("atomic groups are not supported");
                    }
//                    group(false);
                    return Token.createNonCaptureGroupBegin();

//                case '(':
//                    conditionalBackreference();
//                    break;
                case '-':       // https://www.regular-expressions.info/refmodifiers.html
                case 'm':
                case 's':
                case 'i':
                case 'x':
                case 'd':
                case 'u':
//                case 'U': // TODO help :D, how is it done with the tokens?
                    // use the InlineFlagToken, check if ":" or ")" and deal with it properly
                    // or just use flags() and deal with it internally
                    // --> still needed to be decided
                    flags(ch1);
//                    registerCaptureGroup();
                    return Token.createGroupEnd();

                default:
                    throw syntaxErrorAt(RbErrorMessages.unknownExtension(ch1), position - 1);
            }
        } else {
            group(!containsNamedCaptureGroups());
        }

        return Token.createCaptureGroupBegin();
    }

    private boolean containsNamedCaptureGroups() {
        return namedCaptureGroups != null;
    }

    /**
     * Just like {@code #lookahead}, but for lookbehind assertions.
     */
    private void lookbehind(boolean negate) {
        pushLookBehindAssertion(negate);
        lookbehindDepth++;
        disjunction();
        lookbehindDepth--;
        if (match(")")) {
            popGroup();
            lastTerm = JavaRegexParser.TermCategory.LookAroundAssertion;
        } else {
            throw syntaxErrorHere(RbErrorMessages.UNTERMINATED_SUBPATTERN);
        }
    }

    /**
     * Parses a group name terminated by the given character.
     *
     * @return the group name
     */
    private String parseGroupName(char terminator) {
        String groupName = getMany(c -> c != terminator);
        if (!match(Character.toString(terminator))) {
            throw syntaxErrorHere(RbErrorMessages.unterminatedName(terminator));
        }
        if (groupName.isEmpty()) {
            throw syntaxErrorHere(RbErrorMessages.MISSING_GROUP_NAME);
        }
        return groupName;
    }

    /**
     * Parses a local flag block or an inline declaration of a global flags. Assumes that the prefix
     * '(?' was already parsed, as well as the first flag which is passed as the argument.
     */

    // TODO adjust to the use of tokens instead of the Ruby version of it
    private void flags(int ch0) {
        int ch = ch0;
        JavaFlags newFlags = getLocalFlags();
        boolean negative = false;
        while (ch != ')' && ch != ':') {
            System.out.println("flag: " + (char) ch + " : " + ch);
            if (ch == '-') {
                negative = true;
            } else if (JavaFlags.isValidFlagChar(ch)) {
                if (negative) {
                    if (JavaFlags.isTypeFlag(ch)) {
                        throw syntaxErrorHere(RbErrorMessages.UNDEFINED_GROUP_OPTION);
                    }
                    newFlags = newFlags.delFlag(ch);
                } else {
                    newFlags = newFlags.addFlag(ch);
                }
            } else if (Character.isAlphabetic(ch)) {
                throw syntaxErrorHere(RbErrorMessages.UNDEFINED_GROUP_OPTION);
            } else {
                throw syntaxErrorHere(RbErrorMessages.MISSING_DASH_COLON_PAREN);
            }

            if (atEnd()) {
                throw syntaxErrorAtEnd(RbErrorMessages.MISSING_FLAG_DASH_COLON_PAREN);
            }
            ch = consumeChar();
        }

        // TODO necessary?
        if (ch == ')') {
            openEndedLocalFlags(newFlags);
        } else {
            assert ch == ':';
            localFlags(newFlags);
        }
    }

    private void openEndedLocalFlags(JavaFlags newFlags) {
        setLocalFlags(newFlags);
        lastTerm = TermCategory.None;
        // Using "open-ended" flag modifiers, e.g. /a(?i)b|c/, makes Java wrap the continuation
        // of the flag modifier in parentheses, so that the above regex is equivalent to
        // /a(?i:b|c)/.
        pushGroup();
        disjunction();
        popGroup();
    }

    /**
     * Parses a block with local flags, assuming that the opening parenthesis, the flags and the ':'
     * have been parsed.
     *
     * @param newFlags - the new set of flags to be used in the block
     */
    private void localFlags(JavaFlags newFlags) {
        flagsStack.push(newFlags);
        group(false);
        flagsStack.pop();
    }

    /**
     * Parses a group, assuming that its opening parenthesis has already been parsed. Note that this
     * is used not only for ordinary capture groups, but also for named capture groups,
     * non-capturing groups or the contents of a local flags block.
     *
     * @param capturing whether or not we should push a capturing group
     */
    private void group(boolean capturing) {
        if (capturing) {
            groupIndex++;
            groupStack.push(new Group(groupIndex));
            pushCaptureGroup();
        } else {
            pushGroup();
        }
        disjunction();
        if (match(")")) {
            popGroup();
            if (capturing) {
                groupStack.pop();
            }
            lastTerm = JavaRegexParser.TermCategory.Atom;
        } else {
            throw syntaxErrorHere(RbErrorMessages.UNTERMINATED_SUBPATTERN);
        }
    }
}
