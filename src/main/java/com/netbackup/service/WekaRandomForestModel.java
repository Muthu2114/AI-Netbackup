package com.netbackup.service;

import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Trains and runs a Weka Random Forest locally using labeled configuration-change examples. */
public final class WekaRandomForestModel {
    public static final String MODEL_NAME = "Weka Random Forest";
    private static final List<String> RISK_LEVELS = List.of("LOW", "MEDIUM", "HIGH");
    private static final List<String> FEATURE_NAMES = List.of(
            "weakSnmpAdded", "snmpCommunityChanged", "ipAddressChanged", "staticRouteChanged",
            "defaultRouteChanged", "routingProtocolChanged", "interfaceShutdown", "aclWideOpen",
            "aclDenyRemoved", "aclChanged", "insecureManagement", "credentialChanged",
            "routineServiceChanged", "changedLineCount"
    );
    private static final Pattern WEAK_SNMP = Pattern.compile(
            "(?i)^snmp-server\\s+community\\s+(public|private|cisco|default)(?:\\s|$).*");
    private static final List<FeatureName> FEATURE_LABELS = List.of(
            new FeatureName("weakSnmpAdded", "Weak/default SNMP community introduced"),
            new FeatureName("snmpCommunityChanged", "SNMP community changed"),
            new FeatureName("ipAddressChanged", "IP address changed"),
            new FeatureName("staticRouteChanged", "Static route changed"),
            new FeatureName("defaultRouteChanged", "Default route changed"),
            new FeatureName("routingProtocolChanged", "Routing protocol changed"),
            new FeatureName("interfaceShutdown", "Interface shutdown added"),
            new FeatureName("aclWideOpen", "Broad permit-any ACL rule added"),
            new FeatureName("aclDenyRemoved", "ACL deny rule removed"),
            new FeatureName("aclChanged", "ACL entry changed"),
            new FeatureName("insecureManagement", "Insecure management setting enabled"),
            new FeatureName("credentialChanged", "Privileged credential changed"),
            new FeatureName("routineServiceChanged", "Routine service or VLAN setting changed")
    );

    private final RandomForest forest;
    private final Instances header;

    public WekaRandomForestModel() {
        try {
            List<Attribute> attributes = new ArrayList<>();
            for (String name : FEATURE_NAMES) attributes.add(new Attribute(name));
            attributes.add(new Attribute("riskLevel", RISK_LEVELS));
            header = new Instances("network_configuration_changes", new ArrayList<>(attributes), 0);
            header.setClassIndex(header.numAttributes() - 1);

            Instances training = trainingData(header);
            forest = new RandomForest();
            forest.setNumIterations(200);
            forest.setSeed(42);
            forest.buildClassifier(training);
        } catch (Exception e) {
            throw new IllegalStateException("Could not train the local Weka Random Forest model.", e);
        }
    }

    public Prediction predict(String oldConfig, String newConfig) {
        FeatureVector features = FeatureVector.extract(oldConfig, newConfig);
        try {
            DenseInstance instance = new DenseInstance(1.0, features.asValues());
            instance.setDataset(header);
            String risk = RISK_LEVELS.get((int) forest.classifyInstance(instance));
            List<String> keyChanges = features.keyChanges();
            String summary = keyChanges.isEmpty()
                    ? features.changedLines + " configuration line(s) changed without a recognized high-impact feature."
                    : String.join("; ", keyChanges) + ".";
            return new Prediction(risk, summary, keyChanges);
        } catch (Exception e) {
            throw new IllegalStateException("Could not classify the configuration change with the local Random Forest.", e);
        }
    }

    private static Instances trainingData(Instances header) {
        List<LabeledChange> examples = List.of(
                change("ntp server 192.0.2.1", "ntp server 192.0.2.2", "LOW"),
                change("logging host 192.0.2.10", "logging host 192.0.2.11", "LOW"),
                change("interface GigabitEthernet0/1\n description Uplink", "interface GigabitEthernet0/1\n description Core uplink", "LOW"),
                change("vlan 10\n name Staff", "vlan 10\n name Employees", "LOW"),
                change("banner motd #Authorized users only#", "banner motd #Authorized access only#", "LOW"),
                change("snmp-server community monitorRO RO", "snmp-server community operationsRO RO", "MEDIUM"),
                change("snmp-server community monitorRO RO", "snmp-server community networkRO RO", "MEDIUM"),
                change("snmp-server community secureReadOnly RO", "snmp-server community monitorRO RO", "MEDIUM"),
                change("snmp-server community monitorRO RO", "snmp-server community snmpReadOnly RO", "MEDIUM"),
                change("ip route 172.16.1.0 255.255.255.0 10.0.0.1",
                        "ip route 172.16.1.0 255.255.255.0 10.0.0.2", "MEDIUM"),
                change("ip access-list extended STAFF\n permit tcp 10.0.0.0 0.0.0.255 any eq 443",
                        "ip access-list extended STAFF\n permit tcp 10.0.0.0 0.0.0.255 any eq 443\n permit udp 10.0.0.0 0.0.0.255 any eq 53", "MEDIUM"),
                change("username operator privilege 1", "username operator privilege 5", "MEDIUM"),
                change("snmp-server community monitorRO RO", "snmp-server community public RO", "HIGH"),
                change("snmp-server community private RW", "snmp-server community public RO", "HIGH"),
                change("snmp-server community monitorRO RO", "snmp-server community private RO", "HIGH"),
                change("snmp-server community secureReadOnly RO", "snmp-server community cisco RW", "HIGH"),
                change("snmp-server community secureReadOnly RO", "snmp-server community default RO", "HIGH"),
                change("! Revision 5\nsnmp-server community monitorRO RO",
                        "! Revision 6\nsnmp-server community public RO", "HIGH"),
                change(ConfigSimulator.generate("Core-Router-01", "10.0.1.1", 5),
                        ConfigSimulator.generate("Core-Router-01", "10.0.1.1", 6)
                                .replace("snmp-server community monitorRO RO", "snmp-server community public RO"),
                        "HIGH"),
                change("interface GigabitEthernet0/1\n no shutdown", "interface GigabitEthernet0/1\n shutdown", "HIGH"),
                change("interface GigabitEthernet0/1\n ip address 10.0.0.1 255.255.255.0",
                        "interface GigabitEthernet0/1\n ip address 10.0.1.1 255.255.255.0", "HIGH"),
                change("ip route 10.0.0.0 255.0.0.0 192.0.2.1",
                        "ip route 0.0.0.0 0.0.0.0 192.0.2.1", "HIGH"),
                change("router ospf 1\n network 10.0.0.0 0.0.0.255 area 0",
                        "router ospf 1\n network 10.1.0.0 0.0.0.255 area 0", "HIGH"),
                change("ip access-list extended GUEST\n deny ip any any",
                        "ip access-list extended GUEST\n permit ip any any", "HIGH"),
                change("ip access-list extended EDGE\n deny tcp any any eq 23\n permit ip any any",
                        "ip access-list extended EDGE\n permit ip any any", "HIGH"),
                change("line vty 0 4\n transport input ssh", "line vty 0 4\n transport input telnet", "HIGH"),
                change("no ip http server", "ip http server", "HIGH"),
                change("ip ssh version 2", "no ip ssh", "HIGH"),
                change("service password-encryption", "no service password-encryption", "HIGH")
        );
        Instances data = new Instances(header, examples.size());
        for (LabeledChange example : examples) {
            FeatureVector features = FeatureVector.extract(example.before, example.after);
            double[] values = features.asValues();
            values[FEATURE_NAMES.size()] = RISK_LEVELS.indexOf(example.label);
            data.add(new DenseInstance(1.0, values));
        }
        return data;
    }

    private static LabeledChange change(String before, String after, String label) {
        return new LabeledChange(before, after, label);
    }

    public record Prediction(String riskLevel, String summary, List<String> keyChanges) {}

    private record LabeledChange(String before, String after, String label) {}
    private record FeatureName(String name, String label) {}

    private static final class FeatureVector {
        private final boolean weakSnmpAdded;
        private final boolean snmpCommunityChanged;
        private final boolean ipAddressChanged;
        private final boolean staticRouteChanged;
        private final boolean defaultRouteChanged;
        private final boolean routingProtocolChanged;
        private final boolean interfaceShutdown;
        private final boolean aclWideOpen;
        private final boolean aclDenyRemoved;
        private final boolean aclChanged;
        private final boolean insecureManagement;
        private final boolean credentialChanged;
        private final boolean routineServiceChanged;
        private final int changedLines;

        private FeatureVector(boolean weakSnmpAdded, boolean snmpCommunityChanged, boolean ipAddressChanged,
                              boolean staticRouteChanged, boolean defaultRouteChanged, boolean routingProtocolChanged,
                              boolean interfaceShutdown, boolean aclWideOpen, boolean aclDenyRemoved,
                              boolean aclChanged, boolean insecureManagement, boolean credentialChanged,
                              boolean routineServiceChanged, int changedLines) {
            this.weakSnmpAdded = weakSnmpAdded;
            this.snmpCommunityChanged = snmpCommunityChanged;
            this.ipAddressChanged = ipAddressChanged;
            this.staticRouteChanged = staticRouteChanged;
            this.defaultRouteChanged = defaultRouteChanged;
            this.routingProtocolChanged = routingProtocolChanged;
            this.interfaceShutdown = interfaceShutdown;
            this.aclWideOpen = aclWideOpen;
            this.aclDenyRemoved = aclDenyRemoved;
            this.aclChanged = aclChanged;
            this.insecureManagement = insecureManagement;
            this.credentialChanged = credentialChanged;
            this.routineServiceChanged = routineServiceChanged;
            this.changedLines = changedLines;
        }

        private static FeatureVector extract(String oldConfig, String newConfig) {
            boolean weakSnmpAdded = false, snmpCommunityChanged = false, ipAddressChanged = false;
            boolean staticRouteChanged = false, defaultRouteChanged = false, routingProtocolChanged = false;
            boolean interfaceShutdown = false, aclWideOpen = false, aclDenyRemoved = false, aclChanged = false;
            boolean insecureManagement = false, credentialChanged = false, routineServiceChanged = false;
            int changedLines = 0;

            for (Map<String, String> line : DiffUtil.diff(oldConfig, newConfig)) {
                String type = line.get("type");
                if (type.equals("same")) continue;
                String text = line.get("text").trim().toLowerCase();
                if (text.isEmpty() || text.startsWith("!")) continue;
                changedLines++;
                boolean added = type.equals("added");
                if (text.startsWith("snmp-server community")) {
                    snmpCommunityChanged = true;
                    if (added && WEAK_SNMP.matcher(line.get("text").trim()).matches()) weakSnmpAdded = true;
                }
                if (text.startsWith("ip address ")) ipAddressChanged = true;
                if (text.startsWith("ip route ") || text.startsWith("ipv6 route ")) {
                    staticRouteChanged = true;
                    if (text.startsWith("ip route 0.0.0.0 0.0.0.0") || text.startsWith("ipv6 route ::/0"))
                        defaultRouteChanged = true;
                }
                if (text.startsWith("router ") || text.startsWith("network ") || text.startsWith("neighbor ")
                        || text.startsWith("redistribute ")) routingProtocolChanged = true;
                if (added && text.equals("shutdown")) interfaceShutdown = true;
                if (text.startsWith("ip access-list") || text.startsWith("access-list")
                        || text.startsWith("permit ") || text.startsWith("deny ")) aclChanged = true;
                if (added && (text.equals("permit ip any any") || text.startsWith("permit tcp any any")
                        || text.startsWith("permit udp any any"))) aclWideOpen = true;
                if (!added && text.startsWith("deny ")) aclDenyRemoved = true;
                if (added && (text.equals("no service password-encryption")
                        || text.startsWith("transport input telnet") || text.equals("ip http server")
                        || text.equals("no ip ssh"))) insecureManagement = true;
                if (text.startsWith("enable secret") || text.startsWith("enable password")
                        || text.startsWith("username ")) credentialChanged = true;
                if (text.startsWith("ntp server") || text.startsWith("logging ")
                        || text.startsWith("vlan ") || text.startsWith("description "))
                    routineServiceChanged = true;
            }

            return new FeatureVector(weakSnmpAdded, snmpCommunityChanged, ipAddressChanged, staticRouteChanged,
                    defaultRouteChanged, routingProtocolChanged, interfaceShutdown, aclWideOpen, aclDenyRemoved,
                    aclChanged, insecureManagement, credentialChanged, routineServiceChanged, changedLines);
        }

        private double[] asValues() {
            return new double[] {
                    bit(weakSnmpAdded), bit(snmpCommunityChanged), bit(ipAddressChanged), bit(staticRouteChanged),
                    bit(defaultRouteChanged), bit(routingProtocolChanged), bit(interfaceShutdown), bit(aclWideOpen),
                    bit(aclDenyRemoved), bit(aclChanged), bit(insecureManagement), bit(credentialChanged),
                    bit(routineServiceChanged), changedLines, 0
            };
        }

        private List<String> keyChanges() {
            List<String> found = new ArrayList<>();
            for (FeatureName feature : FEATURE_LABELS) {
                if (value(feature.name)) found.add(feature.label);
            }
            return found.stream().limit(6).toList();
        }

        private boolean value(String name) {
            return switch (name) {
                case "weakSnmpAdded" -> weakSnmpAdded;
                case "snmpCommunityChanged" -> snmpCommunityChanged;
                case "ipAddressChanged" -> ipAddressChanged;
                case "staticRouteChanged" -> staticRouteChanged;
                case "defaultRouteChanged" -> defaultRouteChanged;
                case "routingProtocolChanged" -> routingProtocolChanged;
                case "interfaceShutdown" -> interfaceShutdown;
                case "aclWideOpen" -> aclWideOpen;
                case "aclDenyRemoved" -> aclDenyRemoved;
                case "aclChanged" -> aclChanged;
                case "insecureManagement" -> insecureManagement;
                case "credentialChanged" -> credentialChanged;
                case "routineServiceChanged" -> routineServiceChanged;
                default -> false;
            };
        }

        private static double bit(boolean value) {
            return value ? 1 : 0;
        }
    }
}
