package com.netbackup.service;

import java.util.*;

/** Line-by-line diff using the Longest Common Subsequence algorithm (plain Java, no Spring). */
public final class DiffUtil {
    private DiffUtil() {}

    public static List<Map<String, String>> diff(String a, String b) {
        String[] x = a.split("\n", -1), y = b.split("\n", -1);
        int[][] l = new int[x.length + 1][y.length + 1];
        for (int i = x.length - 1; i >= 0; i--)
            for (int j = y.length - 1; j >= 0; j--)
                l[i][j] = x[i].equals(y[j]) ? l[i + 1][j + 1] + 1 : Math.max(l[i + 1][j], l[i][j + 1]);
        List<Map<String, String>> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < x.length && j < y.length) {
            if (x[i].equals(y[j])) { out.add(line("same", x[i])); i++; j++; }
            else if (l[i + 1][j] >= l[i][j + 1]) out.add(line("removed", x[i++]));
            else out.add(line("added", y[j++]));
        }
        while (i < x.length) out.add(line("removed", x[i++]));
        while (j < y.length) out.add(line("added", y[j++]));
        return out;
    }

    private static Map<String, String> line(String type, String text) { return Map.of("type", type, "text", text); }
}
