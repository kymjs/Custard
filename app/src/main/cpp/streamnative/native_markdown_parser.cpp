#include <jni.h>

#include <string>
#include <utility>
#include <vector>

namespace {

// MarkdownProcessorType ordinals (must match app/src/main/java/.../MarkdownProcessor.kt)
constexpr int MD_HEADER = 0;
constexpr int MD_BLOCK_QUOTE = 1;
constexpr int MD_CODE_BLOCK = 2;
constexpr int MD_ORDERED_LIST = 3;
constexpr int MD_UNORDERED_LIST = 4;
constexpr int MD_HORIZONTAL_RULE = 5;
constexpr int MD_BLOCK_LATEX = 6;
constexpr int MD_TABLE = 7;
constexpr int MD_XML_BLOCK = 8;
constexpr int MD_PLAN_EXECUTION = 9;
constexpr int MD_BOLD = 10;
constexpr int MD_ITALIC = 11;
constexpr int MD_INLINE_CODE = 12;
constexpr int MD_LINK = 13;
constexpr int MD_IMAGE = 14;
constexpr int MD_STRIKETHROUGH = 15;
constexpr int MD_UNDERLINE = 16;
constexpr int MD_INLINE_LATEX = 17;
constexpr int MD_PLAIN_TEXT = 18;

struct Piece {
    int start;
    int end; // exclusive
};

struct InlineNode {
    int type;
    std::vector<Piece> pieces;
};

struct BlockNode {
    int type;
    std::vector<Piece> pieces; // used when inline is empty
    std::vector<InlineNode> inlineNodes;
};

inline bool isStartOfLine(const jchar* chars, int i) {
    return i == 0 || chars[i - 1] == u'\n';
}

inline int findLineEnd(const jchar* chars, int len, int start) {
    int i = start;
    while (i < len && chars[i] != u'\n') {
        i++;
    }
    return i;
}

inline bool startsWithAscii(const jchar* chars, int len, int i, const char* lit) {
    int k = 0;
    while (lit[k] != 0) {
        if (i + k >= len) return false;
        if (chars[i + k] != static_cast<jchar>(lit[k])) return false;
        k++;
    }
    return true;
}

inline bool isDigit(jchar c) {
    return c >= u'0' && c <= u'9';
}

static int countRun(const jchar* chars, int len, int i, jchar ch) {
    int j = i;
    while (j < len && chars[j] == ch) {
        j++;
    }
    return j - i;
}

static int findSubseqNoNewline(const jchar* chars, int start, int end, const std::u16string& pat) {
    if (pat.empty()) return -1;
    for (int i = start; i + static_cast<int>(pat.size()) <= end; i++) {
        bool ok = true;
        for (int k = 0; k < static_cast<int>(pat.size()); k++) {
            const jchar c = chars[i + k];
            if (c == u'\n') return -1;
            if (c != pat[static_cast<size_t>(k)]) {
                ok = false;
                break;
            }
        }
        if (ok) return i;
    }
    return -1;
}

static void addPlainInline(std::vector<InlineNode>& out, int start, int end) {
    if (start >= end) return;
    InlineNode n;
    n.type = MD_PLAIN_TEXT;
    n.pieces.push_back({start, end});
    out.push_back(std::move(n));
}

static std::vector<InlineNode> parseInline(const jchar* chars, int start, int end) {
    std::vector<InlineNode> out;
    out.reserve(16);

    int i = start;
    int plainStart = start;

    while (i < end) {
        const jchar c = chars[i];

        // Link: [text](url) (keep delimiters)
        if (c == u'[') {
            int closeBracket = -1;
            for (int j = i + 1; j < end; j++) {
                if (chars[j] == u'\n') break;
                if (chars[j] == u']') {
                    closeBracket = j;
                    break;
                }
            }
            if (closeBracket != -1 && closeBracket + 1 < end && chars[closeBracket + 1] == u'(') {
                int closeParen = -1;
                for (int j = closeBracket + 2; j < end; j++) {
                    if (chars[j] == u'\n') break;
                    if (chars[j] == u')') {
                        closeParen = j;
                        break;
                    }
                }
                if (closeParen != -1) {
                    addPlainInline(out, plainStart, i);
                    InlineNode n;
                    n.type = MD_LINK;
                    n.pieces.push_back({i, closeParen + 1});
                    out.push_back(std::move(n));
                    i = closeParen + 1;
                    plainStart = i;
                    continue;
                }
            }
        }

        // Inline code: `code` or ``code`` (strip ticks)
        if (c == u'`') {
            const int tickCount = countRun(chars, end, i, u'`');
            std::u16string pat(static_cast<size_t>(tickCount), u'`');
            const int close = findSubseqNoNewline(chars, i + tickCount, end, pat);
            if (close != -1) {
                addPlainInline(out, plainStart, i);
                InlineNode n;
                n.type = MD_INLINE_CODE;
                n.pieces.push_back({i + tickCount, close});
                out.push_back(std::move(n));
                i = close + tickCount;
                plainStart = i;
                continue;
            }
        }

        // Strikethrough: ~~text~~ (strip delimiters)
        if (c == u'~' && i + 1 < end && chars[i + 1] == u'~') {
            const int close = findSubseqNoNewline(chars, i + 2, end, u"~~");
            if (close != -1) {
                addPlainInline(out, plainStart, i);
                InlineNode n;
                n.type = MD_STRIKETHROUGH;
                n.pieces.push_back({i + 2, close});
                out.push_back(std::move(n));
                i = close + 2;
                plainStart = i;
                continue;
            }
        }

        // Underline: __text__ (keep delimiters)
        if (c == u'_' && i + 1 < end && chars[i + 1] == u'_') {
            const int close = findSubseqNoNewline(chars, i + 2, end, u"__");
            if (close != -1) {
                addPlainInline(out, plainStart, i);
                InlineNode n;
                n.type = MD_UNDERLINE;
                n.pieces.push_back({i, close + 2});
                out.push_back(std::move(n));
                i = close + 2;
                plainStart = i;
                continue;
            }
        }

        // Bold: **text** (strip delimiters)
        if (c == u'*' && i + 1 < end && chars[i + 1] == u'*') {
            const int close = findSubseqNoNewline(chars, i + 2, end, u"**");
            if (close != -1) {
                addPlainInline(out, plainStart, i);
                InlineNode n;
                n.type = MD_BOLD;
                n.pieces.push_back({i + 2, close});
                out.push_back(std::move(n));
                i = close + 2;
                plainStart = i;
                continue;
            }
        }

        // Italic: *text* (strip delimiters) - avoid **
        if (c == u'*' && !(i + 1 < end && chars[i + 1] == u'*')) {
            int close = -1;
            for (int j = i + 1; j < end; j++) {
                if (chars[j] == u'\n') break;
                if (chars[j] == u'*') {
                    close = j;
                    break;
                }
            }
            if (close != -1) {
                addPlainInline(out, plainStart, i);
                InlineNode n;
                n.type = MD_ITALIC;
                n.pieces.push_back({i + 1, close});
                out.push_back(std::move(n));
                i = close + 1;
                plainStart = i;
                continue;
            }
        }

        i++;
    }

    addPlainInline(out, plainStart, end);
    return out;
}

static bool isHorizontalRuleLine(const jchar* chars, int len, int lineStart, int lineEnd) {
    int count = 0;
    jchar marker = 0;
    for (int i = lineStart; i < lineEnd; i++) {
        const jchar c = chars[i];
        if (c == u' ' || c == u'\t' || c == u'\r') continue;
        if (marker == 0) {
            if (c != u'-' && c != u'*' && c != u'_') return false;
            marker = c;
            count = 1;
        } else {
            if (c != marker) return false;
            count++;
        }
    }
    return marker != 0 && count >= 3;
}

static std::vector<BlockNode> parseMarkdown(const jchar* chars, int len) {
    std::vector<BlockNode> blocks;
    blocks.reserve(32);

    int i = 0;
    int plainStart = 0;

    auto flushPlainAsBlock = [&](int endExclusive) {
        if (plainStart >= endExclusive) {
            plainStart = endExclusive;
            return;
        }
        BlockNode b;
        b.type = MD_PLAIN_TEXT;
        b.inlineNodes = parseInline(chars, plainStart, endExclusive);
        blocks.push_back(std::move(b));
        plainStart = endExclusive;
    };

    while (i < len) {
        const bool atSol = isStartOfLine(chars, i);

        // <plan...>...</plan>
        if (chars[i] == u'<' && startsWithAscii(chars, len, i, "<plan")) {
            int endTag = -1;
            for (int j = i + 5; j + 6 < len; j++) {
                if (chars[j] == u'<' && startsWithAscii(chars, len, j, "</plan>")) {
                    endTag = j + 7;
                    break;
                }
            }
            if (endTag != -1) {
                flushPlainAsBlock(i);
                BlockNode b;
                b.type = MD_PLAN_EXECUTION;
                b.pieces.push_back({i, endTag});
                blocks.push_back(std::move(b));
                i = endTag;
                plainStart = i;
                continue;
            }
        }

        // Fenced code block ```...
        if (atSol && chars[i] == u'`') {
            const int tickCount = countRun(chars, len, i, u'`');
            if (tickCount >= 3) {
                const int lineEnd = findLineEnd(chars, len, i);
                int search = (lineEnd < len) ? (lineEnd + 1) : len;

                int endPos = -1;
                while (search < len) {
                    const int ls = search;
                    const int le = findLineEnd(chars, len, ls);
                    int p = ls;
                    while (p < le && chars[p] == u' ') p++;
                    if (p + tickCount <= le) {
                        bool ok = true;
                        for (int k = 0; k < tickCount; k++) {
                            if (chars[p + k] != u'`') {
                                ok = false;
                                break;
                            }
                        }
                        if (ok) {
                            endPos = (le < len) ? (le + 1) : le;
                            break;
                        }
                    }
                    search = (le < len) ? (le + 1) : len;
                }

                if (endPos == -1) {
                    endPos = len;
                }

                flushPlainAsBlock(i);
                BlockNode b;
                b.type = MD_CODE_BLOCK;
                b.pieces.push_back({i, endPos});
                blocks.push_back(std::move(b));
                i = endPos;
                plainStart = i;
                continue;
            }
        }

        // Header #.. (1-6)
        if (atSol && chars[i] == u'#') {
            int count = 0;
            int j = i;
            while (j < len && chars[j] == u'#') {
                count++;
                j++;
            }
            if (count >= 1 && count <= 6 && j < len && chars[j] == u' ') {
                const int le = findLineEnd(chars, len, i);
                flushPlainAsBlock(i);
                BlockNode b;
                b.type = MD_HEADER;
                b.inlineNodes = parseInline(chars, i, le);
                blocks.push_back(std::move(b));
                i = (le < len) ? (le + 1) : le;
                plainStart = i;
                continue;
            }
        }

        // Block quote lines starting with "> " (strip marker)
        if (atSol && chars[i] == u'>' && i + 1 < len && chars[i + 1] == u' ') {
            flushPlainAsBlock(i);

            BlockNode b;
            b.type = MD_BLOCK_QUOTE;

            int cur = i;
            while (cur < len) {
                const int le = findLineEnd(chars, len, cur);
                const int contentStart = std::min(cur + 2, le);
                if (contentStart < le) {
                    b.inlineNodes = parseInline(chars, contentStart, le);
                } else {
                    b.inlineNodes.clear();
                }

                // Merge as plain text inline nodes per line to preserve content
                // Represent each line as inline nodes to keep delimiter stripping.
                // We keep it simple: re-parse inline per line and append.
                if (b.inlineNodes.empty()) {
                    InlineNode n;
                    n.type = MD_PLAIN_TEXT;
                    n.pieces.push_back({contentStart, le});
                    b.inlineNodes.push_back(std::move(n));
                }

                if (le < len) {
                    InlineNode nl;
                    nl.type = MD_PLAIN_TEXT;
                    nl.pieces.push_back({le, le + 1});
                    b.inlineNodes.push_back(std::move(nl));
                }

                if (le >= len) {
                    cur = len;
                    break;
                }

                const int next = le + 1;
                if (next < len && chars[next] == u'>' && next + 1 < len && chars[next + 1] == u' ') {
                    cur = next;
                    continue;
                }

                cur = next;
                break;
            }

            blocks.push_back(std::move(b));
            i = cur;
            plainStart = i;
            continue;
        }

        // Horizontal rule line
        if (atSol) {
            const int le = findLineEnd(chars, len, i);
            if (isHorizontalRuleLine(chars, len, i, le)) {
                flushPlainAsBlock(i);
                BlockNode b;
                b.type = MD_HORIZONTAL_RULE;
                b.pieces.push_back({i, le});
                blocks.push_back(std::move(b));
                i = (le < len) ? (le + 1) : le;
                plainStart = i;
                continue;
            }
        }

        i++;
    }

    flushPlainAsBlock(len);

    return blocks;
}

static jintArray blocksToIntArray(JNIEnv* env, const std::vector<BlockNode>& blocks) {
    std::vector<jint> out;
    out.reserve(1024);

    out.push_back(static_cast<jint>(blocks.size()));

    for (const auto& b : blocks) {
        out.push_back(static_cast<jint>(b.type));
        out.push_back(static_cast<jint>(b.pieces.size()));
        for (const auto& p : b.pieces) {
            out.push_back(static_cast<jint>(p.start));
            out.push_back(static_cast<jint>(p.end));
        }
        out.push_back(static_cast<jint>(b.inlineNodes.size()));
        for (const auto& in : b.inlineNodes) {
            out.push_back(static_cast<jint>(in.type));
            out.push_back(static_cast<jint>(in.pieces.size()));
            for (const auto& p : in.pieces) {
                out.push_back(static_cast<jint>(p.start));
                out.push_back(static_cast<jint>(p.end));
            }
        }
    }

    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

} // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_operit_util_streamnative_NativeMarkdownParser_nativeParseMarkdown(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring content
) {
    if (content == nullptr) {
        return env->NewIntArray(0);
    }

    const jsize len = env->GetStringLength(content);
    const jchar* chars = env->GetStringChars(content, nullptr);

    std::vector<BlockNode> blocks = parseMarkdown(chars, static_cast<int>(len));

    env->ReleaseStringChars(content, chars);

    return blocksToIntArray(env, blocks);
}
