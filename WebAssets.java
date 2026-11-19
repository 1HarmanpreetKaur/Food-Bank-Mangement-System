import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class WebAssets {
    static void register(HttpServer server) {
        server.createContext("/assets/styles.css", request ->
                send(request, "/assets/styles.css", "styles.css", "text/css"));
        server.createContext("/assets/dashboard.js", request ->
                send(request, "/assets/dashboard.js", "dashboard.js", "text/javascript"));
    }

    private static void send(HttpExchange request, String route, String file, String contentType)
            throws IOException {
        try {
            if (!route.equals(request.getRequestURI().getPath())) {
                request.sendResponseHeaders(404, -1);
                return;
            }
            if (!"GET".equals(request.getRequestMethod())) {
                request.getResponseHeaders().set("Allow", "GET");
                request.sendResponseHeaders(405, -1);
                return;
            }
            Path asset = Path.of("frontend", file);
            if (!Files.isRegularFile(asset)) {
                request.sendResponseHeaders(404, -1);
                return;
            }
            byte[] content = Files.readAllBytes(asset);
            request.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            request.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            request.sendResponseHeaders(200, content.length);
            request.getResponseBody().write(content);
        } finally {
            request.close();
        }
    }
}
