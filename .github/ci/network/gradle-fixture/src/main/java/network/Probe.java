package network;

import org.junit.Assert;

public final class Probe {
    public static void main(String[] args) {
        Assert.assertEquals("21", System.getProperty("java.specification.version"));
        Assert.assertTrue(System.getProperty("java.vendor").contains("Amazon"));
        Assert.assertNull(System.getenv("GITHUB_TOKEN"));
        Assert.assertNull(System.getenv("GITHUB_META_TOKEN"));
        Assert.assertNull(System.getenv("ACTIONS_RUNTIME_TOKEN"));
        Assert.assertNull(System.getenv("AWS_ACCESS_KEY_ID"));
        System.out.println("Corretto 21 executed compiled candidate Java with a clean environment.");
    }
}
