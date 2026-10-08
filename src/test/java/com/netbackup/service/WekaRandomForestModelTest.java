package com.netbackup.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WekaRandomForestModelTest {
    private final WekaRandomForestModel model = new WekaRandomForestModel();

    @Test
    void predictsHighForWeakSnmpChangeFromV5ToV6() {
        String v5 = ConfigSimulator.generate("Core-Router-01", "10.0.1.1", 5);
        String v6 = ConfigSimulator.generate("Core-Router-01", "10.0.1.1", 6)
                .replace("snmp-server community monitorRO RO", "snmp-server community public RO");

        assertEquals("HIGH", model.predict(v5, v6).riskLevel());
        AiConfigAnalyzer.Outcome outcome = new AiConfigAnalyzer(model).analyze("Core-Router-01", 5, 6, v5, v6);

        assertEquals("HIGH", outcome.ai().riskLevel());
        assertEquals(WekaRandomForestModel.MODEL_NAME, outcome.ai().model());
        assertTrue(outcome.ai().keyChanges().contains("Weak/default SNMP community introduced"));
    }

    @Test
    void predictsHighForAclDenyRemovalFromV3ToV1() {
        String v3 = ConfigSimulator.generate("Core-Router-01", "10.0.1.1", 3);
        String v1 = ConfigSimulator.generate("Core-Router-01", "10.0.1.1", 1);

        assertEquals("HIGH", model.predict(v3, v1).riskLevel());
        assertTrue(model.predict(v3, v1).keyChanges().contains("ACL deny rule removed"));
    }

    @Test
    void predictsHighForCriticalNetworkSecurityAndAvailabilityChanges() {
        assertEquals("HIGH", model.predict(
                "interface GigabitEthernet0/0\n no shutdown\n",
                "interface GigabitEthernet0/0\n shutdown\n").riskLevel());
        assertEquals("HIGH", model.predict(
                "interface GigabitEthernet0/0\n ip address 10.0.0.1 255.255.255.0\n",
                "interface GigabitEthernet0/0\n ip address 10.0.1.1 255.255.255.0\n").riskLevel());
        assertEquals("HIGH", model.predict(
                "ip access-list extended EDGE\n deny ip any any\n",
                "ip access-list extended EDGE\n permit ip any any\n").riskLevel());
        assertEquals("HIGH", model.predict(
                "ip route 10.0.0.0 255.0.0.0 192.0.2.1\n",
                "ip route 0.0.0.0 0.0.0.0 192.0.2.1\n").riskLevel());
        assertEquals("HIGH", model.predict(
                "line vty 0 4\n transport input ssh\n",
                "line vty 0 4\n transport input telnet\n").riskLevel());
    }

    @Test
    void distinguishesMediumAndLowConfigurationChanges() {
        assertEquals("MEDIUM", model.predict(
                "snmp-server community monitorRO RO\n",
                "snmp-server community operationsRO RO\n").riskLevel());
        assertEquals("MEDIUM", model.predict(
                "ip route 172.16.1.0 255.255.255.0 10.0.0.1\n",
                "ip route 172.16.1.0 255.255.255.0 10.0.0.2\n").riskLevel());
        assertEquals("LOW", model.predict(
                "ntp server 192.0.2.1\n",
                "ntp server 192.0.2.2\n").riskLevel());
    }

    @Test
    void routineVlanOnlyChangeIsNotHighRisk() {
        String risk = model.predict("vlan 10\n name Staff\n", "vlan 10\n name Employees\n").riskLevel();

        assertTrue(risk.equals("LOW") || risk.equals("MEDIUM"));
    }
}
