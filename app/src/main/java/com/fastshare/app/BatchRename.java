package com.fastshare.app;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Renames many files at once by a pattern, with a preview that catches problems before anything is touched. */
public final class BatchRename {
    private BatchRename() { }

    public enum Mode { NUMBERING, FIND_REPLACE, PREFIX_SUFFIX, CASE, DATE }

    public enum CaseMode { LOWER, UPPER, TITLE, SENTENCE }

    public static final class Options {
        public Mode mode = Mode.NUMBERING;
        public boolean keepExtension = true;       // rename "photo.jpg" as "photo" + ".jpg"
        // numbering
        public String base = "";
        public int start = 1;
        public int digits = 2;
        // find and replace
        public String find = "";
        public String replace = "";
        public boolean matchCase;
        // prefix and suffix
        public String prefix = "";
        public String suffix = "";
        // case
        public CaseMode caseMode = CaseMode.LOWER;
        // date
        public String datePattern = "yyyyMMdd_HHmmss";
    }

    /** What one file would become. */
    public static final class Preview {
        public final File file;
        public final String newName;
        public final String error;     // null when fine

        Preview(File file, String newName, String error) {
            this.file = file;
            this.newName = newName;
            this.error = error;
        }

        public boolean changes() {
            return error == null && !newName.equals(file.getName());
        }
    }

    private static String[] split(String name, boolean keepExt) {
        if (!keepExt) return new String[]{name, ""};
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return new String[]{name, ""};
        return new String[]{name.substring(0, dot), name.substring(dot)};
    }

    /** Works out the new names. Files are numbered in name order so the result is predictable. */
    public static List<Preview> plan(List<File> files, Options o) {
        List<File> ordered = new ArrayList<>(files);
        Collections.sort(ordered, new Comparator<File>() {
            @Override public int compare(File a, File b) { return naturalCompare(a.getName(), b.getName()); }
        });

        List<String> names = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) names.add(newName(ordered.get(i), i, o));

        // problems: empty / illegal names, clashes inside the batch, clashes with files outside it
        Set<String> batchPaths = new HashSet<>();
        for (File f : ordered) batchPaths.add(f.getPath());
        Set<String> seen = new HashSet<>();
        List<Preview> out = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            File f = ordered.get(i);
            String n = names.get(i);
            String err = FileOps.checkName(n);
            if (err == null) {
                File target = new File(f.getParentFile(), n);
                String key = target.getPath().toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    err = "“" + n + "” would be used twice";
                } else if (!n.equals(f.getName()) && target.exists() && !batchPaths.contains(target.getPath())) {
                    err = "“" + n + "” already exists";
                }
            }
            out.add(new Preview(f, n, err));
        }
        return out;
    }

    private static String newName(File f, int index, Options o) {
        String[] parts = split(f.getName(), o.keepExtension);
        String base = parts[0];
        String ext = parts[1];
        switch (o.mode) {
            case NUMBERING: {
                String num = String.format(Locale.ROOT, "%0" + Math.max(1, o.digits) + "d", o.start + index);
                String b = o.base.isEmpty() ? "" : o.base;
                return b + num + ext;
            }
            case FIND_REPLACE: {
                if (o.find.isEmpty()) return f.getName();
                String replaced = o.matchCase ? base.replace(o.find, o.replace)
                        : base.replaceAll("(?i)" + java.util.regex.Pattern.quote(o.find),
                        java.util.regex.Matcher.quoteReplacement(o.replace));
                return replaced + ext;
            }
            case PREFIX_SUFFIX:
                return o.prefix + base + o.suffix + ext;
            case CASE:
                return applyCase(base, o.caseMode) + ext;
            case DATE: {
                try {
                    String d = new SimpleDateFormat(o.datePattern, Locale.US).format(new Date(f.lastModified()));
                    return d + ext;
                } catch (IllegalArgumentException bad) {
                    return "";
                }
            }
            default:
                return f.getName();
        }
    }

    static String applyCase(String s, CaseMode m) {
        switch (m) {
            case UPPER: return s.toUpperCase(Locale.ROOT);
            case LOWER: return s.toLowerCase(Locale.ROOT);
            case SENTENCE: {
                String l = s.toLowerCase(Locale.ROOT);
                return l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1);
            }
            default: {
                StringBuilder sb = new StringBuilder();
                boolean start = true;
                for (char c : s.toCharArray()) {
                    if (Character.isLetterOrDigit(c)) {
                        sb.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                        start = false;
                    } else {
                        sb.append(c);
                        start = true;
                    }
                }
                return sb.toString();
            }
        }
    }

    /** Orders "img2" before "img10". */
    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    /**
     * Renames the files that change. Done in two steps (to a temporary name, then to the final one) so names can
     * trade places, and rolled back if anything fails. Returns {newFile, oldFile} pairs for undo.
     */
    public static List<File[]> apply(List<Preview> previews) throws IOException {
        List<Preview> todo = new ArrayList<>();
        for (Preview p : previews) {
            if (p.error != null) throw new IOException(p.error);
            if (p.changes()) todo.add(p);
        }
        List<File[]> pairs = new ArrayList<>();
        for (Preview p : todo) pairs.add(new File[]{p.file, new File(p.file.getParentFile(), p.newName)});
        renamePairs(pairs);
        List<File[]> undo = new ArrayList<>();
        for (File[] pr : pairs) undo.add(new File[]{pr[1], pr[0]});
        return undo;
    }

    /** Moves each {from, to}. Everything goes through temporary names first. */
    public static void renamePairs(List<File[]> pairs) throws IOException {
        List<File[]> step1 = new ArrayList<>();      // {original, temp}
        long stamp = System.nanoTime();
        try {
            int i = 0;
            for (File[] p : pairs) {
                File tmp = new File(p[0].getParentFile(), ".fbtmp-" + stamp + "-" + (i++));
                if (!p[0].renameTo(tmp)) throw new IOException("Couldn't rename " + p[0].getName());
                step1.add(new File[]{p[0], tmp});
            }
            for (int k = 0; k < pairs.size(); k++) {
                File tmp = step1.get(k)[1];
                File to = pairs.get(k)[1];
                if (!tmp.renameTo(to)) throw new IOException("Couldn't rename to " + to.getName());
                step1.get(k)[1] = to;
                step1.get(k)[0] = null;           // done
            }
        } catch (IOException e) {
            // put back anything still sitting under a temporary name or already renamed
            for (int k = 0; k < step1.size(); k++) {
                File[] s = step1.get(k);
                File current = s[1];
                File original = pairs.get(k)[0];
                if (current.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    current.renameTo(original);
                }
            }
            throw e;
        }
    }
}
