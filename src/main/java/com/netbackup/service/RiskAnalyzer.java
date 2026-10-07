package com.netbackup.service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Rule-based configuration risk analyzer (plain Java, no Spring).
 * Looks at the lines that changed between two configs and rates the change LOW / MEDIUM / HIGH.
 * The overall level is the highest severity found; the score is HIGH=40, MEDIUM=20, LOW=5 per finding (max 100).
 */
public class RiskAnalyzer {

    public record Finding(String severity, String rule, String line) {}

    public record Result(String level, int score, List<Finding> findings) {
        /** Stored in the database as lines of SEVERITY|rule|config line. */
        public String encode() {
            return findings.stream()
                    .map(f -> f.severity() + "|" + f.rule() + "|" + f.line().replace("|", "/").replace("\n", " "))
                    .collect(Collectors.joining("\n"));
        }
    }

    /** First version of a device: nothing to compare with. */
    public Result baseline() {
        return new Result("LOW", 0, List.of(new Finding("INFO", "Initial version", "Baseline - nothing to compare with")));
    }

    private static final class Change { String sev, title, oldLine, newLine; boolean plain; }

    public Result analyze(String oldConfig, String newConfig) {
        List<Finding> out = new ArrayList<>();
        Map<String, Change> changes = new LinkedHashMap<>();
        int otherLines = 0;
        String iface = "";

        for (Map<String, String> l : DiffUtil.diff(oldConfig, newConfig)) {
            String type = l.get("type"), raw = l.get("text"), t = raw.trim(), low = t.toLowerCase();
            if (raw.startsWith("interface ")) { iface = t.substring(10); continue; }   // context only
            if (!raw.startsWith(" ") && !t.isEmpty() && !t.startsWith("!")) iface = "";   // left the interface block
            if (type.equals("same") || t.isEmpty() || t.startsWith("!")) continue;
            boolean add = type.equals("added");

            if (low.equals("shutdown")) {
                if (add) out.add(new Finding("HIGH", "Interface shutdown", t + " on " + iface));
            } else if (low.equals("no shutdown")) {
                if (!add) continue;   // removal is reported through the matching "shutdown" line
                out.add(new Finding("LOW", "Interface enabled", t + " on " + iface));
            } else if (low.startsWith("ip address ")) {
                note(changes, "ip:" + iface, "HIGH", "IP address on " + iface, add, t);
            } else if (low.startsWith("ip route") || low.startsWith("ipv6 route")) {
                String[] tok = t.split("\\s+");
                String key = String.join(" ", Arrays.copyOf(tok, Math.min(4, tok.length)));
                boolean dflt = low.startsWith("ip route 0.0.0.0 0.0.0.0");
                note(changes, "route:" + key, dflt ? "HIGH" : "MEDIUM", dflt ? "Default route" : "Static route", add, t);
            } else if (low.startsWith("router ") || low.startsWith("network ") || low.startsWith("neighbor ")
                    || low.startsWith("redistribute ")) {
                note(changes, "proto:" + t, "HIGH", "Routing protocol", add, t);
            } else if (low.equals("no service password-encryption") || low.startsWith("transport input telnet")
                    || low.startsWith("ip http server") || low.startsWith("no ip ssh")) {
                if (add) out.add(new Finding("HIGH", "Insecure management setting", t));
            } else if (low.startsWith("enable secret") || low.startsWith("enable password")) {
                note(changes, "secret", "HIGH", "Privileged password", add, t);
            } else if (low.startsWith("username ")) {
                note(changes, "user:" + t.split("\\s+")[1], "MEDIUM", "Local user", add, t);
            } else if (low.startsWith("snmp-server community")) {
                boolean weak = add && (low.contains("community public") || low.contains("community private"));
                note(changes, "snmp:" + (weak ? "weak" : "ok"), weak ? "HIGH" : "MEDIUM", weak ? "Default SNMP community" : "SNMP community", add, t);
            } else if (low.startsWith("permit ip any any")) {
                if (add) out.add(new Finding("MEDIUM", "Permit-any ACL rule", t));
            } else if (low.startsWith("deny ") && !add) {
                out.add(new Finding("HIGH", "ACL deny rule removed", t));
            } else if (low.startsWith("ip access-list") || low.startsWith("access-list") || low.startsWith("deny ")
                    || low.startsWith("permit ")) {
                note(changes, "acl:" + t, "MEDIUM", "ACL entry", add, t);
            } else if (low.startsWith("hostname ")) {
                note(changes, "hostname", "MEDIUM", "Hostname", add, t);
            } else if (low.startsWith("ntp server")) {
                note(changes, "ntp", "LOW", "NTP server", add, t);
            } else if (low.startsWith("logging ")) {
                note(changes, "logging", "LOW", "Logging", add, t);
            } else if (low.startsWith("vlan ")) {
                note(changes, "vlan:" + t, "LOW", "VLAN", add, t);
            } else {
                otherLines++;
            }
        }

        for (Change c : changes.values()) {
            if (c.oldLine != null && c.newLine != null)
                out.add(new Finding(c.sev, c.title + " changed", c.oldLine + "  ->  " + c.newLine));
            else if (c.newLine != null) out.add(new Finding(c.sev, c.title + " added", c.newLine));
            else out.add(new Finding(c.sev, c.title + " removed", c.oldLine));
        }
        if (out.isEmpty() && otherLines > 0)
            out.add(new Finding("LOW", "Minor configuration change", otherLines + " other line(s) changed"));

        int score = 0, rank = 0;
        for (Finding f : out) {
            int r = switch (f.severity()) { case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; };
            rank = Math.max(rank, r);
            score += switch (r) { case 3 -> 40; case 2 -> 20; default -> 5; };
        }
        out.sort(Comparator.comparingInt((Finding f) -> -sevRank(f.severity())));
        return new Result(rank == 3 ? "HIGH" : rank == 2 ? "MEDIUM" : "LOW", Math.min(100, score), out);
    }

    private static int sevRank(String s) { return s.equals("HIGH") ? 3 : s.equals("MEDIUM") ? 2 : 1; }

    private void note(Map<String, Change> m, String key, String sev, String title, boolean add, String line) {
        Change c = m.computeIfAbsent(key, k -> new Change());
        c.sev = sev; c.title = title;
        if (add) c.newLine = line; else c.oldLine = line;
    }
}
