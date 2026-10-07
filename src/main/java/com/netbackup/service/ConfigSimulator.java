package com.netbackup.service;

import java.util.Random;

/** Generates fake device configurations (plain Java, no Spring). */
public final class ConfigSimulator {
    private ConfigSimulator() {}

    /** Same output as the original v1 generator: higher versions add/change lines. */
    public static String generate(String name, String ip, int v) {
        StringBuilder s = new StringBuilder();
        s.append("! Simulated configuration for ").append(name).append("\n");
        s.append("! Revision ").append(v).append("\n");
        s.append("hostname ").append(name).append("\n!\n");
        s.append("service password-encryption\n");
        s.append("enable secret 5 $1$simu$a1b2c3d4e5\n!\n");
        s.append("interface GigabitEthernet0/0\n description Uplink\n ip address ")
                .append(ip).append(" 255.255.255.0\n no shutdown\n!\n");
        if (v >= 2) s.append("vlan ").append(10 * v).append("\n name Dept-").append(v).append("\n!\n");
        if (v >= 3) s.append("ip access-list extended BLOCK-TELNET\n deny tcp any any eq 23\n permit ip any any\n!\n");
        s.append("ntp server 10.0.0.").append(v >= 4 ? 2 : 1).append("\n");
        s.append("snmp-server community ").append(v >= 5 ? "monitorRO" : "public").append(" RO\n");
        s.append("logging host 10.0.0.50\n!\nend\n");
        return s.toString();
    }

    /**
     * Simulates "configuration drift" for scheduled backups: about 40% of the time someone
     * "changed something on the device". Returns the same text when nothing changed.
     */
    public static String drift(String c, Random r) {
        if (r.nextInt(100) >= 40) return c;
        String out = null;
        switch (r.nextInt(6)) {
            case 0 -> out = c.replaceFirst(" no shutdown", " shutdown");
            case 1 -> out = c.replaceFirst("(ip address \\d+\\.\\d+\\.\\d+\\.)\\d+", "$1" + (100 + r.nextInt(100)));
            case 2 -> out = c.replace("ntp server 10.0.0.1", "ntp server 10.0.0.2");
            case 3 -> out = c.replace("snmp-server community public", "snmp-server community monitorRO");
            case 4 -> out = c.replace("logging host 10.0.0.50", "logging host 10.0.0.60");
            default -> { }
        }
        if (out == null || out.equals(c)) {
            out = c + (c.endsWith("\n") ? "" : "\n") + "ip route 172.16." + (1 + r.nextInt(200)) + ".0 255.255.255.0 10.0.0.254\n";
        }
        return out;
    }
}
