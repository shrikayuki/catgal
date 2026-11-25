import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/** Deployment-only probe. Uses an anonymous read and never prints its response. */
public class HealthCheck {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:8080/game/page?pageNo=1&pageSize=1"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        boolean successful = response.statusCode() == 200
                && Pattern.compile("\"code\"\\s*:\\s*200\\b").matcher(response.body()).find();
        System.exit(successful ? 0 : 1);
    }
}
