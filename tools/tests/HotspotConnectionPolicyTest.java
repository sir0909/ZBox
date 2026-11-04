package com.zbox.ai.ptpip;

import java.util.*;

/** Offline JVM tests against the actual compiled Kotlin policy; no mocked network scans. */
public final class HotspotConnectionPolicyTest {
    private static int passed;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        System.out.println("PASS " + (++passed) + " " + name);
    }
    public static void main(String[] args) {
        HotspotCameraEndpoint a = new HotspotCameraEndpoint("192.168.50.193", "192.168.50.197", "wlan1");
        HotspotCameraEndpoint b = new HotspotCameraEndpoint("192.168.50.194", "192.168.50.197", "wlan1");
        HotspotCameraEndpoint c = new HotspotCameraEndpoint("192.168.50.195", "192.168.50.197", "wlan1");
        HotspotCameraEndpoint duplicate = new HotspotCameraEndpoint(a.getCameraAddress(), "192.168.101.8", "wlan0");
        HotspotConnectionPolicy policy = HotspotConnectionPolicy.INSTANCE;
        HotspotConnectionPolicy.Decision zero = policy.decide(Collections.emptyList());
        check(zero.getAction() == HotspotConnectionPolicy.Action.NONE, "zero candidates do not connect");
        check(zero.getCandidates().isEmpty(), "zero candidates keep no chooser");
        HotspotConnectionPolicy.Decision one = policy.decide(Collections.singletonList(a));
        check(one.getAction() == HotspotConnectionPolicy.Action.AUTO_CONNECT, "one camera auto-connects");
        check(one.getCandidates().get(0) == a, "original discovered endpoint retained");
        check(one.getCandidates().get(0).getPhoneAddress().equals("192.168.50.197"), "hotspot source binding retained");
        check(one.getCandidates().get(0).getInterfaceName().equals("wlan1"), "hotspot interface retained");
        HotspotConnectionPolicy.Decision two = policy.decide(Arrays.asList(a, b));
        check(two.getAction() == HotspotConnectionPolicy.Action.CHOOSE, "two cameras require explicit choice");
        check(two.getCandidates().size() == 2, "both cameras remain selectable");
        check(policy.decide(Arrays.asList(a, b, c)).getAction() == HotspotConnectionPolicy.Action.CHOOSE, "three cameras never auto-select first");
        HotspotConnectionPolicy.Decision dedup = policy.decide(Arrays.asList(a, duplicate));
        check(dedup.getAction() == HotspotConnectionPolicy.Action.AUTO_CONNECT, "duplicate routes do not invent another camera");
        check(dedup.getCandidates().size() == 1, "same camera address deduplicated");
        List<HotspotCameraEndpoint> input = new ArrayList<>(Arrays.asList(a, b, duplicate));
        check(policy.decide(input).getAction() == HotspotConnectionPolicy.Action.CHOOSE && input.size() == 3, "dedup keeps two-camera choice without mutating input");
        System.out.println("ALL " + passed + " POLICY CHECKS PASSED");
    }
}
