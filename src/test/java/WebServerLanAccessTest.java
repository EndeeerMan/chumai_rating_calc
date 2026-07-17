import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Dependency-free checks for IPv4 wildcard binding and printed access URLs. */
public final class WebServerLanAccessTest {
    private static int tests;

    private WebServerLanAccessTest() {
    }

    public static void main(String[] args) throws Exception {
        InetSocketAddress requested = WebServer.ipv4WildcardAddress(0);
        expect(true, requested.getAddress() instanceof Inet4Address,
                "bind address is IPv4");
        expect(true, requested.getAddress().isAnyLocalAddress(),
                "bind address is the IPv4 wildcard");
        expect("0.0.0.0", requested.getAddress().getHostAddress(),
                "bind address is explicit rather than host-dependent");

        verifyFirewallScriptContract();
        if (args.length == 1 && "--static-only".equals(args[0])) {
            System.out.println(
                    "LAN firewall static tests passed: " + tests);
            return;
        }

        HttpServer server = HttpServer.create(requested, 0);
        server.createContext("/health", WebServerLanAccessTest::respond);
        server.start();
        try {
            int port = server.getAddress().getPort();
            List<String> urls = WebServer.localAccessUrls(port);
            expect("http://localhost:" + port + "/", urls.getFirst(),
                    "localhost URL is always printed first");
            for (String address : WebServer.discoverLanIpv4Addresses()) {
                expect(true, urls.contains("http://" + address + ":" + port + "/"),
                        "each active private IPv4 address is printed");
            }
            for (String baseUrl : urls) {
                expect(200, get(baseUrl + "health"),
                        "wildcard listener accepts " + baseUrl);
            }
        } finally {
            server.stop(0);
        }

        System.out.println("WebServer LAN access tests passed: " + tests);
    }

    private static void verifyFirewallScriptContract() throws IOException {
        String script = Files.readString(Path.of("setup-lan-access.ps1"));
        expect(true, script.contains("[switch]$Helper"),
                "firewall script exposes explicit Helper mode");
        expect(1, occurrences(script,
                        "MaimaiRatingCalc-Web-PrivateLAN"),
                "website rule has one fixed name");
        expect(1, occurrences(script,
                        "MaimaiRatingCalc-WeChatHelper-PrivateLAN"),
                "Helper rule has one independent fixed name");

        int selectorStart = script.indexOf("if ($Helper) {");
        int selectorEnd = script.indexOf("function Test-IsAdministrator");
        expect(true, selectorStart >= 0 && selectorEnd > selectorStart,
                "rule selector is defined before elevation");
        String selector = script.substring(selectorStart, selectorEnd);
        expect(true, selector.contains("$effectivePort = 8081")
                        && selector.contains("$ruleName = \"MaimaiRatingCalc-WeChatHelper-PrivateLAN\"")
                        && selector.contains("$effectivePort = $Port")
                        && selector.contains("$ruleName = \"MaimaiRatingCalc-Web-PrivateLAN\""),
                "Helper and website rules select separate ports and names");
        expect(true, script.contains(
                        "-Helper always manages the fixed TCP 8081 Helper rule"),
                "Helper mode rejects a conflicting custom port");
        expect(true, script.contains("$arguments += \"-Helper\"")
                        && script.contains("$arguments += @(\"-Port\", [string]$Port)"),
                "elevated copy preserves the selected rule mode");

        expect(true, script.contains("Profile             = \"Private\"")
                        && script.contains("Direction           = \"Inbound\"")
                        && script.contains("Action              = \"Allow\"")
                        && script.contains("EdgeTraversalPolicy = \"Block\"")
                        && script.contains("RemoteAddress       = \"LocalSubnet\"")
                        && script.contains("Protocol            = \"TCP\"")
                        && script.contains("LocalPort           = [string]$effectivePort"),
                "firewall rule remains restricted to private local-subnet TCP");
        expect(true, script.contains("[switch]$Disable")
                        && script.contains("[switch]$Remove")
                        && script.contains("Set-NetFirewallRule `")
                        && script.contains("Remove-NetFirewallRule -Name $ruleName"),
                "both independently selected rules support disable and remove");
        expect(true, script.contains(
                        "Get-NetFirewallRule -Name $ruleName -PolicyStore PersistentStore")
                        && script.contains("New-NetFirewallRule `"),
                "rule updates are deterministic and idempotent");

        String instructions = Files.readString(Path.of("说明文件.txt"));
        String helperReadme = Files.readString(
                Path.of("wechat-helper", "README.md"));
        for (String documentation : List.of(instructions, helperReadme)) {
            expect(true, documentation.contains(
                            ".\\setup-lan-access.ps1 -Helper")
                            && documentation.contains(
                            ".\\setup-lan-access.ps1 -Helper -Disable")
                            && documentation.contains(
                            ".\\setup-lan-access.ps1 -Helper -Remove"),
                    "documentation covers Helper rule lifecycle");
            expect(true, documentation.contains("403")
                            && documentation.contains("Clash"),
                    "documentation distinguishes proxy checks from firewall checks");
        }
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(fragment, offset)) >= 0;
                offset += fragment.length()) {
            count++;
        }
        return count;
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private static int get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url)
                .toURL()
                .openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static void expect(Object expected, Object actual, String name) {
        tests++;
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected " + expected
                    + ", got " + actual);
        }
    }
}
