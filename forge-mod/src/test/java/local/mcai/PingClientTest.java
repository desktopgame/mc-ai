package local.mcai;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;

public class PingClientTest {
    @Test public void validReply() throws Exception {
        assertEquals("pong", PingClient.parseReply("{\"version\":1,\"say\":\"pong\",\"actions\":[]}"));
    }

    @Test public void rejectsInvalidResponsesAndActions() throws Exception {
        for (String value : new String[] {"invalid", "[]", "null", "{}",
                "{\"version\":2,\"say\":\"pong\",\"actions\":[]}",
                "{\"version\":\"1\",\"say\":\"pong\",\"actions\":[]}",
                "{\"version\":1,\"say\":42,\"actions\":[]}",
                "{\"version\":1,\"say\":\"pong\",\"actions\":[{\"type\":\"shell\"}]}",
                "{\"version\":1,\"say\":\"pong\",\"actions\":[{\"type\":\"follow\"}]}"}) {
            try { PingClient.parseReply(value); fail("Accepted invalid response"); }
            catch (IOException expected) { }
        }
    }

    @Test public void httpRoundTripAndNon200() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/turn", exchange -> {
            byte[] response = "{\"version\":1,\"say\":\"pong\",\"actions\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertEquals("pong", new PingClient(base).ping("test-player"));
            try { new PingClient(base + "/missing").ping("test-player"); fail(); }
            catch (IOException expected) { }
        } finally { server.stop(0); }
        try { new PingClient(base).ping("test-player"); fail(); }
        catch (IOException expected) { }
    }

    @Test public void timesOutWithoutResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/turn", exchange -> {
            try { Thread.sleep(3500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            new PingClient("http://127.0.0.1:" + server.getAddress().getPort()).ping("test-player");
            fail("Expected timeout");
        } catch (java.net.SocketTimeoutException expected) {
        } finally { server.stop(0); }
    }
}
