package dev.stannismod.stellurgy.test.contract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Where, in one source file, the member a red-witness record names is: the span its fragment is searched in --
 * or why the record names no single member, which is a REFUSAL and never a pick.
 *
 * <p><b>The form a record names a member in</b> (the record's {@code member}, after the type):</p>
 * <ul>
 *   <li>The TYPE is its simple name, or a dotted chain of the types that enclose it ({@code Outer.Inner}).
 *   A member belongs to the innermost NAMED type around it: a method of an anonymous class is its enclosing
 *   type's, a method of a nested or local class is that class's and never its outer's. An enum constant's
 *   body is named through the constant ({@code Enum.CONSTANT#m}); a bare {@code Enum#m} counts the constant
 *   bodies' declarations too, so two constants that each declare {@code m} refuse it.</li>
 *   <li>A METHOD is its name; an overloaded one is its name and its parameter types,
 *   {@code step(double,double)} -- simple names, no generic arguments, {@code T...} written {@code T[]},
 *   {@code ()} for the one without parameters. A bare name that matches several declarations is refused,
 *   naming them: the build does not choose the overload the author meant.</li>
 *   <li>A FIELD of the class body, or an enum constant, is its name; its span is the declaration from the
 *   name through the {@code ;} that ends it (a constant: through the {@code ,} that ends it). A bare name that
 *   is both a field and a method is refused; the method is named with its parameter list.</li>
 * </ul>
 *
 * <p>Comments and literals are blanked before anything is matched, and a declaration is recognised only at the
 * level of a class body: a call {@code if (member(a)) {} } inside a method is never one.</p>
 *
 * <p><b>What it does not see.</b> Generic type parameters are erased by name, not resolved: {@code m(T)} and a
 * hypothetical {@code m(Object)} stay distinct, and two parameters spelled differently for one type (a fully
 * qualified and a simple name of DIFFERENT classes that share the simple name) read as the same.</p>
 */
final class MemberAddress {

    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern TYPE = Pattern.compile("(?<![\\w$.])(class|interface|enum)\\s+([A-Za-z_$][\\w$]*)");
    /** A constant: a compiled {@link Pattern} is immutable. The member part of a record: a name, maybe a parameter list. */
    private static final Pattern MEMBER = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*(\\(([^()]*)\\))?");

    /** The start of the span, inclusive; -1 when refused. */
    final int start;
    /** The end of the span, exclusive. */
    final int end;
    /** Why the record names no single member; null when it does. */
    final String refusal;

    private MemberAddress(int start, int end, String refusal) {
        this.start = start;
        this.end = end;
        this.refusal = refusal;
    }

    private static MemberAddress refuse(String why) {
        return new MemberAddress(-1, -1, why);
    }

    /** The simple names of every type declared in {@code src}, nested ones included. */
    static List<String> declaredTypes(String src) {
        Matcher m = TYPE.matcher(blank(src));
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(2));
        }
        return out;
    }

    /**
     * The span of {@code member} of the type {@code type} (simple name or {@code Outer.Inner} chain) in
     * {@code src}, or the refusal saying why there is no single one.
     */
    static MemberAddress resolve(String src, String type, String member) {
        Matcher mm = MEMBER.matcher(member.trim());
        if (!mm.matches()) {
            return refuse("`" + member + "` is not a member name, nor a name and a parameter list");
        }
        Source s = new Source(src);
        List<String> chain = Arrays.asList(type.split("\\."));
        List<Decl> types = new ArrayList<>();
        for (Decl d : s.types) {
            if (d.chainEndsWith(chain)) {
                types.add(d);
            }
        }
        if (types.isEmpty()) {
            return refuse("no type " + type + " in the file");
        }
        if (types.size() > 1) {
            return refuse("the type " + type + " is declared " + types.size() + " times in the file ("
                    + types.stream().map(Decl::qualified).collect(Collectors.joining(", "))
                    + ") -- name it through its outer type, as Outer.Inner#member");
        }
        Decl owner = types.get(0);
        String name = mm.group(1);
        String params = mm.group(2) == null ? null : normalisedList(mm.group(3));
        List<Method> methods = s.methods(owner, name);
        if (params != null) {
            List<Method> named = new ArrayList<>();
            for (Method m : methods) {
                if (m.params.equals(params)) {
                    named.add(m);
                }
            }
            if (named.isEmpty()) {
                return refuse("no overload " + owner.name + "#" + name + "(" + params + ")" + (methods.isEmpty()
                        ? "" : "; declared: " + signatures(methods)));
            }
            if (named.size() > 1) {
                return refuse(named.size() + " declarations of " + owner.name + "#" + name + "(" + params
                        + ") share that signature (methods of anonymous classes or enum constant bodies of the type)"
                        + " -- an enum constant's is named through the constant, as " + owner.name + ".CONSTANT#"
                        + name + "; an anonymous class's cannot be named");
            }
            return named.get(0).address(owner);
        }
        List<int[]> fields = s.fields(owner, name);
        if (methods.size() + fields.size() == 0) {
            return refuse("no method or field " + owner.name + "#" + name);
        }
        if (methods.size() > 1) {
            boolean distinct = methods.stream().map(Method::signature).distinct().count() == methods.size();
            return refuse(owner.name + "#" + name + " is overloaded: " + signatures(methods) + (distinct
                    ? " -- name one by its parameter types, as " + owner.name + "#" + methods.get(0).signature()
                    : " -- declarations share a signature (enum constant bodies or anonymous classes): name an enum"
                    + " constant's through the constant, as " + owner.name + ".CONSTANT#" + name));
        }
        if (!methods.isEmpty() && !fields.isEmpty()) {
            return refuse(owner.name + "#" + name + " is both a field and a method -- name the method with its"
                    + " parameter list, as " + owner.name + "#" + methods.get(0).signature());
        }
        if (fields.size() > 1) {
            return refuse("the field " + owner.name + "#" + name + " is declared " + fields.size() + " times");
        }
        if (!methods.isEmpty()) {
            return methods.get(0).address(owner);
        }
        return new MemberAddress(fields.get(0)[0], fields.get(0)[1], null);
    }

    private static String signatures(List<Method> methods) {
        return methods.stream().map(Method::signature).collect(Collectors.joining(", "));
    }

    /** {@code src} with comments and string / char literals replaced by spaces, offsets kept. */
    static String blank(String src) {
        char[] c = src.toCharArray();
        int i = 0;
        int n = c.length;
        while (i < n) {
            if (c[i] == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n') {
                    c[i++] = ' ';
                }
            } else if (c[i] == '/' && i + 1 < n && c[i + 1] == '*') {
                c[i] = ' ';
                c[i + 1] = ' ';
                i += 2;
                while (i < n && !(c[i] == '*' && i + 1 < n && c[i + 1] == '/')) {
                    if (c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    c[i] = ' ';
                    c[i + 1] = ' ';
                    i += 2;
                }
            } else if (c[i] == '"' || c[i] == '\'') {
                char q = c[i++];
                while (i < n && c[i] != q && c[i] != '\n') {
                    if (c[i] == '\\' && i + 1 < n) {
                        c[i++] = ' ';
                    }
                    c[i++] = ' ';
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(c);
    }

    /** A parameter list as the record and the declaration are both compared: simple erased types, comma-joined. */
    static String normalisedList(String list) {
        List<String> out = new ArrayList<>();
        for (String p : splitTopLevel(list, ',')) {
            if (!p.trim().isEmpty()) {
                out.add(normalisedType(p));
            }
        }
        return String.join(",", out);
    }

    /** One parameter or one record type: annotations, {@code final}, generic arguments, packages and a name dropped. */
    private static String normalisedType(String param) {
        String p = param.replaceAll("@[\\w$.]+(\\s*\\([^)]*\\))?", " ").replaceAll("\\bfinal\\b", " ");
        p = eraseGenerics(p).replace("...", " [] ").trim();
        // `int a[]` -> `int[]`; a trailing identifier after the type is the parameter's name.
        StringBuilder dims = new StringBuilder();
        Matcher name = Pattern.compile("^(.*?[\\w$\\]])\\s+([A-Za-z_$][\\w$]*)\\s*((?:\\[\\s*\\]\\s*)*)$").matcher(p);
        if (name.matches()) {
            p = name.group(1);
            dims.append(name.group(3).replaceAll("\\s", ""));
        }
        p = p.replaceAll("\\s", "");
        String base = p.replaceAll("(\\[\\])+$", "");
        String arrays = p.substring(base.length());
        base = base.substring(base.lastIndexOf('.') + 1);
        return base + arrays + dims;
    }

    private static String eraseGenerics(String s) {
        StringBuilder b = new StringBuilder();
        int depth = 0;
        for (char ch : s.toCharArray()) {
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            } else if (depth == 0) {
                b.append(ch);
            }
        }
        return b.toString();
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<' || ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == sep && depth == 0) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        out.add(s.substring(from));
        return out;
    }

    /** One type declaration: its name, kind, body braces and the type whose body declares it. */
    private static final class Decl {
        String name;
        boolean isEnum;
        /** An enum constant's body: an anonymous class, named through its constant. */
        boolean constant;
        int open;
        int close;
        Decl parent;
        /** For an enum: where its constants end (the first top-level {@code ;}, or the body's end). */
        int constantsEnd;

        boolean chainEndsWith(List<String> chain) {
            Decl d = this;
            for (int i = chain.size() - 1; i >= 0; i--) {
                if (d == null || !d.name.equals(chain.get(i))) {
                    return false;
                }
                d = d.parent;
            }
            return true;
        }

        String qualified() {
            return parent == null ? name : parent.qualified() + "." + name;
        }
    }

    /** One method declaration: its parameter list and its body braces (-1 for one declared without a body). */
    private static final class Method {
        String name;
        String params;
        int bodyOpen = -1;
        int bodyClose = -1;

        String signature() {
            return name + "(" + params + ")";
        }

        MemberAddress address(Decl owner) {
            return bodyOpen < 0 ? refuse(owner.name + "#" + signature() + " is declared without a body")
                    : new MemberAddress(bodyOpen + 1, bodyClose, null);
        }
    }

    /** A blanked source with its brace blocks and type declarations. */
    private static final class Source {
        final String text;
        /** For each offset, the innermost open brace whose block holds it (-1 at the top level). */
        final int[] inner;
        /** For each open brace, its close; -1 for any other offset. */
        final int[] close;
        final List<Decl> types = new ArrayList<>();

        Source(String src) {
            text = blank(src);
            int n = text.length();
            inner = new int[n];
            close = new int[n];
            Arrays.fill(close, -1);
            Deque<Integer> open = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                char ch = text.charAt(i);
                if (ch == '}' && !open.isEmpty()) {
                    close[open.pop()] = i;
                }
                inner[i] = open.isEmpty() ? -1 : open.peek();
                if (ch == '{') {
                    open.push(i);
                }
            }
            Matcher m = TYPE.matcher(text);
            while (m.find()) {
                int brace = text.indexOf('{', m.end());
                int semi = text.indexOf(';', m.end());
                if (brace < 0 || (semi >= 0 && semi < brace) || close[brace] < 0) {
                    continue;
                }
                Decl d = new Decl();
                d.name = m.group(2);
                d.isEnum = "enum".equals(m.group(1));
                d.open = brace;
                d.close = close[brace];
                d.constantsEnd = d.close;
                types.add(d);
            }
            List<Decl> constants = new ArrayList<>();
            for (Decl d : types) {
                d.parent = typeAround(d.open);
                if (d.isEnum) {
                    int depth = 0;
                    for (int i = d.open + 1; i < d.close; i++) {
                        char ch = text.charAt(i);
                        depth += ch == '(' || ch == '{' ? 1 : ch == ')' || ch == '}' ? -1 : 0;
                        if (ch == ';' && depth == 0) {
                            d.constantsEnd = i;
                            break;
                        }
                    }
                    // An enum constant's body is an anonymous class the record can name through the constant.
                    for (int i = d.open + 1; i < d.constantsEnd; i++) {
                        if (text.charAt(i) != '{' || inner[i] != d.open || close[i] < 0) {
                            continue;
                        }
                        int q = i - 1;
                        while (q > d.open && Character.isWhitespace(text.charAt(q))) {
                            q--;
                        }
                        if (text.charAt(q) == ')') {
                            for (int depth2 = 0; q > d.open; q--) {
                                depth2 += text.charAt(q) == ')' ? 1 : text.charAt(q) == '(' ? -1 : 0;
                                if (depth2 == 0) {
                                    break;
                                }
                            }
                            q--;
                            while (q > d.open && Character.isWhitespace(text.charAt(q))) {
                                q--;
                            }
                        }
                        int e = q + 1;
                        while (q > d.open && (Character.isLetterOrDigit(text.charAt(q)) || text.charAt(q) == '_'
                                || text.charAt(q) == '$')) {
                            q--;
                        }
                        Decl c = new Decl();
                        c.name = text.substring(q + 1, e);
                        c.constant = true;
                        c.open = i;
                        c.close = close[i];
                        c.constantsEnd = c.close;
                        c.parent = d;
                        constants.add(c);
                    }
                }
            }
            types.addAll(constants);
        }

        /** The type whose body is the block opening at {@code brace}, or null. */
        Decl typeOf(int brace) {
            for (Decl d : types) {
                if (d.open == brace) {
                    return d;
                }
            }
            return null;
        }

        /** The innermost named type whose body holds offset {@code at}, or null. */
        Decl typeAround(int at) {
            for (int b = inner[at]; b >= 0; b = inner[b]) {
                Decl d = typeOf(b);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }

        /**
         * Whether the block opening at {@code brace} is a class body -- a type's, an anonymous class's or an
         * enum constant's -- so that a declaration directly inside it is a member.
         */
        boolean isClassBody(int brace) {
            if (typeOf(brace) != null) {
                return true;
            }
            int q = brace - 1;
            while (q >= 0 && Character.isWhitespace(text.charAt(q))) {
                q--;
            }
            Decl around = brace > 0 && inner[brace] >= 0 ? typeOf(inner[brace]) : null;
            if (around != null && around.isEnum && brace < around.constantsEnd) {
                return true;
            }
            if (q < 0 || text.charAt(q) != ')') {
                return false;
            }
            int depth = 0;
            for (int i = q; i >= 0; i--) {
                char ch = text.charAt(i);
                depth += ch == ')' ? 1 : ch == '(' ? -1 : 0;
                if (depth == 0) {
                    String before = text.substring(Math.max(0, i - 200), i);
                    return Pattern.compile("(?<![\\w$.])new\\s+[\\w$.]+\\s*(?:<[^;{}()]*>)?\\s*$").matcher(before).find();
                }
            }
            return false;
        }

        /** Whether offset {@code at} sits directly in a class body that belongs to {@code owner}. */
        boolean memberLevelOf(Decl owner, int at) {
            int b = inner[at];
            if (b < 0 || !isClassBody(b)) {
                return false;
            }
            Decl t = typeOf(b);
            t = t != null ? t : typeAround(b);
            // A constant body's member is its enum's too: a bare Enum#m counts it, and is refused when the
            // constants each declare one.
            return t == owner || (t != null && t.constant && t.parent == owner);
        }

        String wordBefore(int at) {
            int q = at - 1;
            while (q >= 0 && Character.isWhitespace(text.charAt(q))) {
                q--;
            }
            int e = q + 1;
            while (q >= 0 && (Character.isLetterOrDigit(text.charAt(q)) || text.charAt(q) == '_'
                    || text.charAt(q) == '$')) {
                q--;
            }
            return text.substring(q + 1, e);
        }

        char charBefore(int at) {
            int q = at - 1;
            while (q >= 0 && Character.isWhitespace(text.charAt(q))) {
                q--;
            }
            return q < 0 ? '\0' : text.charAt(q);
        }

        boolean inConstants(int at) {
            int b = inner[at];
            Decl t = b < 0 ? null : typeOf(b);
            return t != null && t.isEnum && at < t.constantsEnd;
        }

        /** Every method {@code name} declared directly in a class body of {@code owner}. */
        List<Method> methods(Decl owner, String name) {
            List<Method> out = new ArrayList<>();
            Matcher m = Pattern.compile("(?<![\\w$.@])" + Pattern.quote(name) + "\\s*\\(").matcher(text);
            m.region(owner.open + 1, owner.close);
            while (m.find()) {
                int at = m.start();
                if (!memberLevelOf(owner, at) || inConstants(at)) {
                    continue;
                }
                char before = charBefore(at);
                String word = wordBefore(at);
                boolean constructor = name.equals(owner.name);
                boolean typed = Character.isLetterOrDigit(before) || before == '_' || before == '$' || before == '>'
                        || before == ']';
                if ("new".equals(word) || (!constructor && !typed)) {
                    continue;
                }
                int paren = m.end() - 1;
                int shut = matching(paren);
                if (shut < 0) {
                    continue;
                }
                int i = shut + 1;
                while (i < text.length() && text.charAt(i) != '{' && text.charAt(i) != ';') {
                    i++;
                }
                String between = text.substring(shut + 1, Math.min(i, text.length())).trim();
                if (!between.isEmpty() && !between.startsWith("throws") && !between.startsWith("default")) {
                    continue;
                }
                Method meth = new Method();
                meth.name = name;
                meth.params = normalisedList(text.substring(paren + 1, shut));
                if (i < text.length() && text.charAt(i) == '{' && close[i] >= 0) {
                    meth.bodyOpen = i;
                    meth.bodyClose = close[i];
                }
                out.add(meth);
            }
            return out;
        }

        int matching(int paren) {
            int depth = 0;
            for (int i = paren; i < text.length(); i++) {
                char ch = text.charAt(i);
                depth += ch == '(' ? 1 : ch == ')' ? -1 : 0;
                if (depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        /** The declaration spans of every field or enum constant {@code name} of {@code owner}. */
        List<int[]> fields(Decl owner, String name) {
            List<int[]> out = new ArrayList<>();
            if (owner.isEnum) {
                int from = owner.open + 1;
                for (String piece : splitTopLevel(text.substring(from, owner.constantsEnd), ',')) {
                    Matcher c = Pattern.compile("^\\s*(?:@[\\w$.]+(?:\\s*\\([^)]*\\))?\\s*)*([A-Za-z_$][\\w$]*)")
                            .matcher(piece);
                    if (c.find() && c.group(1).equals(name)) {
                        out.add(new int[]{from + c.start(1), from + piece.length()});
                    }
                    from += piece.length() + 1;
                }
            }
            Matcher m = Pattern.compile("(?<![\\w$.@])" + Pattern.quote(name) + "\\s*(?=[=;,](?!=))").matcher(text);
            m.region(owner.open + 1, owner.close);
            while (m.find()) {
                int at = m.start();
                if (!memberLevelOf(owner, at) || inConstants(at) || insideParens(at)) {
                    continue;
                }
                char before = charBefore(at);
                boolean typed = Character.isLetterOrDigit(before) || before == '_' || before == '$' || before == '>'
                        || before == ']' || before == ',';
                if (!typed || "new".equals(wordBefore(at))) {
                    continue;
                }
                int end = statementEnd(at);
                if (end > 0) {
                    out.add(new int[]{at, end});
                }
            }
            return out;
        }

        /** Whether {@code at} is inside an unclosed {@code (} of its own statement (a parameter, an argument). */
        boolean insideParens(int at) {
            int depth = 0;
            for (int i = at - 1; i > inner[at]; i--) {
                char ch = text.charAt(i);
                if (ch == ')') {
                    depth++;
                } else if (ch == '(') {
                    if (depth == 0) {
                        return true;
                    }
                    depth--;
                } else if (depth == 0 && (ch == ';' || ch == '{' || ch == '}')) {
                    return false;
                }
            }
            return false;
        }

        int statementEnd(int at) {
            int depth = 0;
            for (int i = at; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '(' || ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == ')' || ch == '}' || ch == ']') {
                    depth--;
                } else if (ch == ';' && depth == 0) {
                    return i + 1;
                }
            }
            return -1;
        }
    }
}
