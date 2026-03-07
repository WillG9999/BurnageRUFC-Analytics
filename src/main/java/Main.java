import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    private static final String OUTPUT_FILE = "matches.txt";
    private static final String CLUBS_FILE = "clubs.txt";
    private static final String BASE_URL = "https://app.veo.co";
    private static final String API_BASE = "https://app.veo.co/api/app";

    private static final Pattern CLUB_SLUG_PATTERN = Pattern.compile("\"slug\"\\s*:\\s*\"([a-z0-9-]+-[a-f0-9]{8})\"");
    private static final Pattern TEAM_SLUG_PATTERN = Pattern.compile("\"slug\"\\s*:\\s*\"([a-z0-9]+-[a-f0-9]{8})\"");
    private static final Pattern MATCH_URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"(/matches/[^\"]+)\"");
    private static final Pattern MATCH_SLUG_PATTERN = Pattern.compile("\"slug\"\\s*:\\s*\"(\\d{8}-[^\"]+)\"");
    private static final Pattern NEXT_PAGE_PATTERN = Pattern.compile("\"next\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient;
    private final Set<String> clubSlugs = new HashSet<>();
    private final Set<String> matchUrls = new TreeSet<>();
    private String authCookie = null;

    public static void main(String[] args) {
        Main crawler = new Main();

        String cookie = null;
        List<String> filteredArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--cookie") && i + 1 < args.length) {
                cookie = args[i + 1];
                i++;
            } else {
                filteredArgs.add(args[i]);
            }
        }

        if (cookie != null) {
            crawler.setAuthCookie(cookie);
            System.out.println("Using authentication cookie\n");
        }

        args = filteredArgs.toArray(new String[0]);

        if (args.length > 0 && args[0].equals("--discover")) {
            crawler.discoverAllClubs();
        } else if (args.length > 0 && args[0].equals("--search") && args.length > 1) {
            crawler.searchClubs(args[1]);
        } else if (args.length > 0 && args[0].equals("--sport") && args.length > 1) {
            crawler.searchBySport(args[1]);
        } else if (args.length > 0 && args[0].equals("--find-clubs")) {
            crawler.findAllClubs();
        } else if (args.length > 0 && args[0].equals("--club-slugs")) {
            crawler.findClubSlugs();
        } else if (args.length > 0 && args[0].equals("--explore")) {
            crawler.exploreApi();
        } else if (args.length > 0 && args[0].equals("--probe") && args.length > 1) {
            crawler.probeEndpoint(args[1]);
        } else if (args.length > 0 && !args[0].startsWith("--")) {
            crawler.fetchMatchesForClub(args[0]);
        } else {
            crawler.printUsage();
        }
    }

    public void setAuthCookie(String cookie) {
        this.authCookie = cookie;
    }

    public Main() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private void printUsage() {
        System.out.println("Veo Match Crawler");
        System.out.println("=================");
        System.out.println("\nUsage:");
        System.out.println("  java Main <club-slug>         - Fetch all matches for a specific club");
        System.out.println("  java Main --club-slugs        - Find club slugs from known matches");
        System.out.println("  java Main --search <name>     - Search for clubs by name");
        System.out.println("  java Main --sport <sport>     - Search by sport (rugby, football, soccer)");
        System.out.println("  java Main --explore           - Explore API endpoints to find club data");
        System.out.println("  java Main --probe <endpoint>  - Probe a specific endpoint path");
        System.out.println("\nAuthentication:");
        System.out.println("  --cookie <value>              - Add auth cookie to all requests");
        System.out.println("\nExamples:");
        System.out.println("  java Main --club-slugs");
        System.out.println("  java Main --cookie \"csrftoken=xxx\" --club-slugs");
        System.out.println("  java Main sandbach-rufc-096253f1");
    }

    private void discoverAllClubs() {
        System.out.println("Discovering clubs via Veo API...\n");

        tryDiscoverEndpoints();

        System.out.println("\n========================================");
        System.out.println("CLUBS DISCOVERED: " + clubSlugs.size());
        System.out.println("========================================");

        for (String club : clubSlugs) {
            System.out.println("  " + club);
        }

        if (!clubSlugs.isEmpty()) {
            writeClubsToFile();

            System.out.println("\nFetching matches from all discovered clubs...\n");
            for (String clubSlug : clubSlugs) {
                fetchMatchesForClub(clubSlug);
            }

            printResults();
            writeResultsToFile();
        }
    }

    private void exploreApi() {
        System.out.println("Exploring Veo API endpoints...\n");
        System.out.println("Legend: 403 = exists but needs login, 404 = doesn't exist, 200 = public\n");

        String[] fastEndpoints = {
            "/",
            "/teams/",
            "/matches/",
            "/recordings/",
            "/search/",
            "/discover/",
            "/public/",
            "/me/",
            "/graphql/"
        };

        for (String endpoint : fastEndpoints) {
            probeEndpointQuiet(endpoint, 5);
        }

        System.out.println("\n--- Detailed /clubs/ probing with extended timeout (120s) ---\n");

        probeEndpointDetailed("/clubs/?limit=1", 120);
        probeEndpointDetailed("/clubs/?limit=5", 120);

        System.out.println("\n--- Other endpoints with limit=1 ---\n");

        probeEndpointQuiet("/teams/?limit=1", 30);
        probeEndpointQuiet("/matches/?limit=1", 30);
        probeEndpointQuiet("/recordings/?limit=1", 30);

        System.out.println("\n========================================");
        System.out.println("EXPLORATION COMPLETE");
        System.out.println("========================================");
        System.out.println("Club slugs found: " + clubSlugs.size());

        if (!clubSlugs.isEmpty()) {
            System.out.println("\nClubs:");
            for (String club : clubSlugs) {
                System.out.println("  " + club);
            }
            writeClubsToFile();
        }

        System.out.println("\nEndpoints returning 403 exist but need authentication.");
        System.out.println("To access them, you'd need to login and capture auth cookies/token.");
    }

    private void probeEndpointDetailed(String path, int timeoutSeconds) {
        String url = API_BASE + path;
        System.out.println("========================================");
        System.out.println("DETAILED PROBE: " + path);
        System.out.println("URL: " + url);
        System.out.println("Timeout: " + timeoutSeconds + " seconds");
        System.out.println("Auth: " + (authCookie != null ? "YES" : "NO"));
        System.out.println("========================================\n");

        long startTime = System.currentTimeMillis();

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Veo-App-Id", "hazard")
                    .header("veo-agent", "veo:svc:web-app")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();

            if (authCookie != null) {
                builder.header("Cookie", authCookie);
            }

            HttpRequest request = builder.build();

            System.out.println("[REQUEST HEADERS]");
            request.headers().map().forEach((key, values) -> {
                for (String value : values) {
                    System.out.println("  " + key + ": " + value);
                }
            });
            System.out.println();

            System.out.println("Waiting for response...");

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - startTime;

            System.out.println("\n[RESPONSE RECEIVED in " + elapsed + "ms]");
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Body Length: " + response.body().length() + " bytes");

            System.out.println("\n[RESPONSE HEADERS]");
            response.headers().map().forEach((key, values) -> {
                for (String value : values) {
                    System.out.println("  " + key + ": " + value);
                }
            });

            extractAndPrintUrls(response.headers());

            String body = response.body();
            System.out.println("\n[RESPONSE BODY]");
            if (body.length() > 5000) {
                System.out.println(body.substring(0, 5000));
                System.out.println("\n... [truncated, showing first 5000 of " + body.length() + " chars]");
            } else {
                System.out.println(body);
            }

            extractClubSlugs(body);
            extractMatchUrls(body);

            System.out.println("\n----------------------------------------\n");

        } catch (java.net.http.HttpTimeoutException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("\n[TIMEOUT after " + elapsed + "ms]");
            System.out.println("The request timed out after " + timeoutSeconds + " seconds");
            System.out.println("Error: " + e.getMessage());
            System.out.println("\n----------------------------------------\n");
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("\n[ERROR after " + elapsed + "ms]");
            System.out.println("Exception Type: " + e.getClass().getName());
            System.out.println("Message: " + e.getMessage());
            e.printStackTrace();
            System.out.println("\n----------------------------------------\n");
        }
    }

    private void extractAndPrintUrls(java.net.http.HttpHeaders headers) {
        Pattern urlPattern = Pattern.compile("https?://[^\"\\s,}]+");
        Set<String> urls = new TreeSet<>();

        headers.map().forEach((key, values) -> {
            for (String value : values) {
                Matcher matcher = urlPattern.matcher(value);
                while (matcher.find()) {
                    String foundUrl = matcher.group()
                            .replace("\\u0026", "&")
                            .replaceAll("[\"'}\\]]$", "");
                    urls.add(foundUrl);
                }
            }
        });

        if (!urls.isEmpty()) {
            System.out.println("\n[CLICKABLE URLs FROM HEADERS]");
            for (String foundUrl : urls) {
                System.out.println(foundUrl);
            }
        }
    }

    private void probeEndpoint(String path) {
        String url;
        if (path.startsWith("http")) {
            url = path;
        } else if (path.startsWith("/api/")) {
            url = BASE_URL + path;
        } else if (path.startsWith("/")) {
            url = API_BASE + path;
        } else {
            url = API_BASE + "/" + path;
        }

        System.out.println("Probing: " + url + "\n");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json")
                    .header("Veo-App-Id", "hazard")
                    .header("veo-agent", "veo:svc:web-app")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("Status: " + response.statusCode());
            System.out.println("Content-Type: " + response.headers().firstValue("content-type").orElse("N/A"));
            System.out.println("Body length: " + response.body().length() + " bytes\n");

            String body = response.body();
            if (body.length() > 2000) {
                System.out.println("Response (first 2000 chars):");
                System.out.println(body.substring(0, 2000));
                System.out.println("\n... [truncated]");
            } else {
                System.out.println("Response:");
                System.out.println(body);
            }

            extractClubSlugs(body);
            extractMatchUrls(body);

            if (!clubSlugs.isEmpty()) {
                System.out.println("\n----------------------------------------");
                System.out.println("Club slugs found in response:");
                for (String slug : clubSlugs) {
                    System.out.println("  " + slug);
                }
            }

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void probeEndpointQuiet(String path, int timeoutSeconds) {
        String url = API_BASE + path;
        System.out.print("GET " + path + " ... ");

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "application/json")
                    .header("Veo-App-Id", "hazard")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();

            if (authCookie != null) {
                builder.header("Cookie", authCookie);
            }

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();

            System.out.print(status);

            if (status == 200) {
                System.out.print(" OK (" + body.length() + " bytes)");
                extractClubSlugs(body);

                if (body.contains("\"slug\"")) {
                    System.out.print(" [has slugs]");
                }
                if (body.contains("\"club\"")) {
                    System.out.print(" [has club data]");
                }
                if (body.contains("\"results\"")) {
                    System.out.print(" [has results]");
                }
                if (body.contains("\"next\"")) {
                    System.out.print(" [has pagination]");
                }
            } else if (status == 403) {
                System.out.print(" FORBIDDEN [EXISTS - NEEDS AUTH]");
            } else if (status == 401) {
                System.out.print(" UNAUTHORIZED [EXISTS - NEEDS AUTH]");
            } else if (status == 404) {
                System.out.print(" NOT FOUND");
            } else {
                System.out.print(" (" + body.length() + " bytes)");
            }

            System.out.println();

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
        }
    }

    private void probeEndpointQuiet(String path) {
        probeEndpointQuiet(path, 5);
    }

    private void findAllClubs() {
        System.out.println("Finding club slugs via API search...\n");

        String[] searchTerms = {
            "rufc", "rfc", "rugby", "fc",
            "firwood", "sandbach", "warrington", "sale",
            "manchester", "liverpool", "chester", "stockport",
            "wilmslow", "lymm", "leigh", "wigan", "burnage"
        };

        for (String term : searchTerms) {
            String endpoint = API_BASE + "/search/?q=" + term;
            String response = makeApiRequest(endpoint);
            if (response != null && response.length() > 50) {
                extractClubSlugsQuietly(response, term);
            }
        }

        System.out.println("\n========================================");
        System.out.println("CLUBS FOUND: " + clubSlugs.size());
        System.out.println("========================================\n");

        List<String> sortedClubs = new ArrayList<>(clubSlugs);
        sortedClubs.sort(String::compareTo);

        for (String club : sortedClubs) {
            System.out.println(club);
        }

        writeClubsToFile();
    }

    private void findClubSlugs() {
        System.out.println("Finding club slugs from Veo...\n");

        Set<String> clubsWithNames = new TreeSet<>();

        System.out.println("Known clubs from constants:");
        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            System.out.println("  " + club.slug() + " -> " + club.title());
            clubSlugs.add(club.slug());
            clubsWithNames.add(club.slug() + " -> " + club.title());
        }

        System.out.println("\nFetching club data from " + ClubConstants.KNOWN_MATCH_SLUGS.size() + " matches...\n");

        for (String matchSlug : ClubConstants.KNOWN_MATCH_SLUGS) {
            String matchUrl = API_BASE + "/matches/" + matchSlug + "/";
            System.out.print("  " + matchSlug.substring(0, Math.min(50, matchSlug.length())) + "... ");

            String response = makeApiRequest(matchUrl);
            if (response != null) {
                System.out.println("OK");
                extractClubInfo(response, clubsWithNames);
                extractOpponentClubInfo(response, clubsWithNames);
            } else {
                System.out.println("FAILED");
            }
        }

        System.out.println("\n========================================");
        System.out.println("CLUB SLUGS FOUND: " + clubSlugs.size());
        System.out.println("========================================\n");

        if (!clubsWithNames.isEmpty()) {
            System.out.println("CLUBS:");
            for (String clubInfo : clubsWithNames) {
                System.out.println("  " + clubInfo);
            }
        }

        writeClubsToFile();

        try {
            Files.write(Path.of("clubs_with_names.txt"), clubsWithNames);
            System.out.println("\nClubs saved to clubs_with_names.txt");
        } catch (Exception e) {
            System.out.println("Failed to write clubs_with_names.txt");
        }
    }

    private void extractOpponentClubInfo(String json, Set<String> clubsWithNames) {
        Pattern opponentPattern = Pattern.compile("\"opponent_club_name\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = opponentPattern.matcher(json);
        while (matcher.find()) {
            String opponentName = matcher.group(1);
            System.out.println("    Found opponent: " + opponentName);
        }
    }

    private void extractClubInfo(String json, Set<String> clubsWithNames) {
        Pattern clubBlockPattern = Pattern.compile(
            "\"club\"\\s*:\\s*\\{[^}]*\"slug\"\\s*:\\s*\"([^\"]+)\"[^}]*\"title\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}"
        );
        Matcher matcher = clubBlockPattern.matcher(json);
        while (matcher.find()) {
            String slug = matcher.group(1);
            String title = matcher.group(2);
            if (isValidClubSlug(slug) && clubSlugs.add(slug)) {
                clubsWithNames.add(slug + " -> " + title);
            }
        }

        Pattern reversePattern = Pattern.compile(
            "\"club\"\\s*:\\s*\\{[^}]*\"title\"\\s*:\\s*\"([^\"]+)\"[^}]*\"slug\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}"
        );
        Matcher reverseMatcher = reversePattern.matcher(json);
        while (reverseMatcher.find()) {
            String title = reverseMatcher.group(1);
            String slug = reverseMatcher.group(2);
            if (isValidClubSlug(slug) && clubSlugs.add(slug)) {
                clubsWithNames.add(slug + " -> " + title);
            }
        }
    }

    private boolean isValidClubSlug(String slug) {
        if (slug == null || slug.isEmpty()) return false;
        if (slug.matches("^\\d{8}-.*")) return false;
        if (!slug.matches(".*-[a-f0-9]{8}$")) return false;
        return true;
    }

    private void searchForClubsQuietly(String searchTerm) {
        String[] endpoints = {
            API_BASE + "/search/?q=" + searchTerm,
            API_BASE + "/clubs/?search=" + searchTerm,
            API_BASE + "/clubs/?q=" + searchTerm
        };

        for (String endpoint : endpoints) {
            String response = makeApiRequest(endpoint);
            if (response != null && response.length() > 50) {
                extractClubSlugsQuietly(response, searchTerm);
            }
        }
    }

    private void extractClubSlugsQuietly(String jsonResponse, String searchTerm) {
        Matcher matcher = CLUB_SLUG_PATTERN.matcher(jsonResponse);
        while (matcher.find()) {
            String slug = matcher.group(1);
            if (clubSlugs.add(slug)) {
                System.out.println("[" + searchTerm + "] Found: " + slug);
            }
        }

        Pattern titleSlugPattern = Pattern.compile("\"slug\"\\s*:\\s*\"([^\"]+)\"");
        Matcher titleMatcher = titleSlugPattern.matcher(jsonResponse);
        while (titleMatcher.find()) {
            String slug = titleMatcher.group(1);
            if (slug.matches("[a-z0-9-]+-[a-f0-9]{8}") && !slug.startsWith("20")) {
                if (clubSlugs.add(slug)) {
                    System.out.println("[" + searchTerm + "] Found: " + slug);
                }
            }
        }
    }

    private void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void searchClubs(String searchTerm) {
        System.out.println("Searching for clubs matching: " + searchTerm + "\n");

        String[] searchEndpoints = {
            API_BASE + "/search/?q=" + searchTerm,
            API_BASE + "/search/clubs/?q=" + searchTerm,
            API_BASE + "/clubs/?search=" + searchTerm,
            API_BASE + "/clubs/?q=" + searchTerm,
            API_BASE + "/discover/?q=" + searchTerm,
            API_BASE + "/discover/clubs/?q=" + searchTerm,
            "https://app.veo.co/api/v1/clubs/?search=" + searchTerm,
            "https://app.veo.co/api/v1/search/?q=" + searchTerm
        };

        Set<String> foundClubs = new TreeSet<>();
        List<String> clubDetails = new ArrayList<>();

        for (String endpoint : searchEndpoints) {
            System.out.println("Trying: " + endpoint);
            String response = makeApiRequest(endpoint);

            if (response != null && response.length() > 10) {
                System.out.println("  Response: " + response.length() + " bytes");

                Pattern clubPattern = Pattern.compile(
                    "\"slug\"\\s*:\\s*\"([^\"]+)\"[^}]*\"title\"\\s*:\\s*\"([^\"]+)\"|" +
                    "\"title\"\\s*:\\s*\"([^\"]+)\"[^}]*\"slug\"\\s*:\\s*\"([^\"]+)\"|" +
                    "\"name\"\\s*:\\s*\"([^\"]+)\"[^}]*\"slug\"\\s*:\\s*\"([^\"]+)\"|" +
                    "\"slug\"\\s*:\\s*\"([^\"]+)\"[^}]*\"name\"\\s*:\\s*\"([^\"]+)\""
                );

                Matcher matcher = clubPattern.matcher(response);
                while (matcher.find()) {
                    String slug = matcher.group(1) != null ? matcher.group(1) :
                                  matcher.group(4) != null ? matcher.group(4) :
                                  matcher.group(6) != null ? matcher.group(6) : matcher.group(7);
                    String name = matcher.group(2) != null ? matcher.group(2) :
                                  matcher.group(3) != null ? matcher.group(3) :
                                  matcher.group(5) != null ? matcher.group(5) : matcher.group(8);

                    if (slug != null && slug.matches("[a-z0-9-]+-[a-f0-9]{8}")) {
                        if (foundClubs.add(slug)) {
                            String detail = slug + " -> " + (name != null ? name : "Unknown");
                            clubDetails.add(detail);
                            System.out.println("    [FOUND] " + detail);
                        }
                    }
                }

                Matcher simpleMatcher = CLUB_SLUG_PATTERN.matcher(response);
                while (simpleMatcher.find()) {
                    String slug = simpleMatcher.group(1);
                    if (slug.toLowerCase().contains(searchTerm.toLowerCase())) {
                        if (foundClubs.add(slug)) {
                            System.out.println("    [FOUND] " + slug);
                        }
                    }
                }

                if (response.length() < 1000) {
                    System.out.println("  Full response: " + response);
                }
            } else {
                System.out.println("  No data");
            }
        }

        System.out.println("\n========================================");
        System.out.println("SEARCH RESULTS FOR: " + searchTerm);
        System.out.println("========================================");
        System.out.println("Found " + foundClubs.size() + " clubs:\n");

        for (String club : foundClubs) {
            System.out.println("  " + club);
        }

        if (!foundClubs.isEmpty()) {
            clubSlugs.addAll(foundClubs);
            writeClubsToFile();
        }
    }

    private void searchBySport(String sport) {
        System.out.println("Searching for " + sport + " clubs and recordings...\n");

        String[] sportEndpoints = {
            API_BASE + "/recordings/?sport=" + sport,
            API_BASE + "/recordings/?sport=" + sport + "&limit=100",
            API_BASE + "/matches/?sport=" + sport,
            API_BASE + "/matches/?sport=" + sport + "&limit=100",
            API_BASE + "/clubs/?sport=" + sport,
            API_BASE + "/clubs/?sport=" + sport + "&limit=100",
            API_BASE + "/search/?sport=" + sport,
            API_BASE + "/search/?q=" + sport + "&sport=" + sport,
            API_BASE + "/discover/?sport=" + sport,
            API_BASE + "/discover/recordings/?sport=" + sport,
            API_BASE + "/public/recordings/?sport=" + sport,
            API_BASE + "/recordings/public/?sport=" + sport,
            API_BASE + "/?sport=" + sport,
            "https://app.veo.co/api/v1/recordings/?sport=" + sport,
            "https://app.veo.co/api/v1/matches/?sport=" + sport
        };

        for (String endpoint : sportEndpoints) {
            System.out.println("Trying: " + endpoint);
            probeEndpointWithDetails(endpoint);
        }

        System.out.println("\n========================================");
        System.out.println("SPORT SEARCH RESULTS: " + sport);
        System.out.println("========================================");
        System.out.println("Clubs found: " + clubSlugs.size());
        System.out.println("Matches found: " + matchUrls.size());

        if (!clubSlugs.isEmpty()) {
            System.out.println("\nClubs:");
            for (String club : clubSlugs) {
                System.out.println("  " + club);
            }
            writeClubsToFile();
        }

        if (!matchUrls.isEmpty()) {
            System.out.println("\nMatches:");
            for (String match : matchUrls) {
                System.out.println("  " + match);
            }
            writeResultsToFile();
        }
    }

    private void probeEndpointWithDetails(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json")
                    .header("Veo-App-Id", "hazard")
                    .header("veo-agent", "veo:svc:web-app")
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            if (authCookie != null) {
                builder.header("Cookie", authCookie);
            }

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body();

            System.out.print("  Status: " + status);

            if (status == 200 && body.length() > 10) {
                System.out.println(" (" + body.length() + " bytes)");

                extractClubSlugs(body);
                extractMatchUrls(body);

                if (body.contains("\"sport\"")) {
                    System.out.println("  [HAS SPORT DATA]");
                }
                if (body.contains("\"results\"")) {
                    System.out.println("  [HAS RESULTS]");
                }
                if (body.contains("\"next\"")) {
                    System.out.println("  [HAS PAGINATION]");
                    fetchAllPages(body);
                }

                if (body.length() < 2000) {
                    System.out.println("  Response: " + body);
                } else {
                    System.out.println("  Preview: " + body.substring(0, 500) + "...");
                }
            } else if (status == 403) {
                System.out.println(" FORBIDDEN [NEEDS AUTH]");
            } else if (status == 404) {
                System.out.println(" NOT FOUND");
            } else {
                System.out.println(" (" + body.length() + " bytes)");
                if (body.length() < 500) {
                    System.out.println("  Response: " + body);
                }
            }
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
        }
    }

    private void tryDiscoverEndpoints() {
        String[] endpointsToTry = {
            API_BASE + "/clubs/",
            API_BASE + "/discover/",
            API_BASE + "/discover/clubs/",
            API_BASE + "/search/",
            API_BASE + "/search/clubs/",
            API_BASE + "/recordings/",
            API_BASE + "/matches/",
            API_BASE + "/public/clubs/",
            API_BASE + "/public/recordings/"
        };

        for (String endpoint : endpointsToTry) {
            System.out.println("Trying: " + endpoint);
            String response = makeApiRequest(endpoint);

            if (response != null) {
                System.out.println("  Got response (" + response.length() + " bytes)");
                extractClubSlugs(response);
                extractMatchUrls(response);

                if (response.length() > 100) {
                    System.out.println("  Preview: " + response.substring(0, Math.min(200, response.length())) + "...");
                }
            } else {
                System.out.println("  No data");
            }
        }

        trySearchWithQuery("rugby");
        trySearchWithQuery("football");
        trySearchWithQuery("soccer");
    }

    private void trySearchWithQuery(String query) {
        String searchUrl = API_BASE + "/search/?q=" + query;
        System.out.println("Trying search: " + searchUrl);

        String response = makeApiRequest(searchUrl);
        if (response != null) {
            System.out.println("  Got response (" + response.length() + " bytes)");
            extractClubSlugs(response);
            extractMatchUrls(response);
        }
    }

    private void extractClubSlugs(String jsonResponse) {
        Matcher matcher = CLUB_SLUG_PATTERN.matcher(jsonResponse);
        while (matcher.find()) {
            String slug = matcher.group(1);
            if (clubSlugs.add(slug)) {
                System.out.println("    [CLUB] " + slug);
            }
        }
    }

    private void fetchMatchesForClub(String clubSlug) {
        System.out.println("Fetching matches for club: " + clubSlug);
        System.out.println("========================================\n");

        List<String> teamSlugs = fetchTeamsForClub(clubSlug);

        if (teamSlugs.isEmpty()) {
            System.out.println("No teams found, trying direct club recordings...");
            fetchClubRecordings(clubSlug);
        } else {
            for (String teamSlug : teamSlugs) {
                fetchTeamRecordings(clubSlug, teamSlug);
            }
        }

        printResults();
        writeResultsToFile();
    }

    private List<String> fetchTeamsForClub(String clubSlug) {
        List<String> teamSlugs = new ArrayList<>();

        String teamsUrl = API_BASE + "/clubs/" + clubSlug + "/teams/";
        System.out.println("Fetching teams: " + teamsUrl);

        String response = makeApiRequest(teamsUrl);
        if (response == null) {
            System.out.println("  Failed to fetch teams\n");
            return teamSlugs;
        }

        Matcher matcher = TEAM_SLUG_PATTERN.matcher(response);
        while (matcher.find()) {
            String slug = matcher.group(1);
            if (!teamSlugs.contains(slug) && !slug.equals(clubSlug)) {
                teamSlugs.add(slug);
                System.out.println("  Team: " + slug);
            }
        }

        System.out.println("  Found " + teamSlugs.size() + " teams\n");
        return teamSlugs;
    }

    private void fetchTeamRecordings(String clubSlug, String teamSlug) {
        String recordingsUrl = API_BASE + "/clubs/" + clubSlug + "/teams/" + teamSlug + "/recordings/";
        System.out.println("Fetching recordings for team: " + teamSlug);

        String response = makeApiRequest(recordingsUrl);
        if (response == null) {
            return;
        }

        extractMatchUrls(response);
        fetchAllPages(response);
    }

    private void fetchClubRecordings(String clubSlug) {
        String recordingsUrl = API_BASE + "/clubs/" + clubSlug + "/recordings/";
        System.out.println("Fetching club recordings: " + recordingsUrl);

        String response = makeApiRequest(recordingsUrl);
        if (response != null) {
            extractMatchUrls(response);
            fetchAllPages(response);
        }
    }

    private void fetchAllPages(String jsonResponse) {
        Matcher matcher = NEXT_PAGE_PATTERN.matcher(jsonResponse);
        if (matcher.find()) {
            String nextUrl = matcher.group(1)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");

            System.out.println("  Fetching next page...");
            String response = makeApiRequest(nextUrl);
            if (response != null) {
                extractMatchUrls(response);
                fetchAllPages(response);
            }
        }
    }

    private void extractMatchUrls(String jsonResponse) {
        Matcher urlMatcher = MATCH_URL_PATTERN.matcher(jsonResponse);
        while (urlMatcher.find()) {
            String matchPath = urlMatcher.group(1);
            String fullUrl = BASE_URL + matchPath;
            if (fullUrl.endsWith("/")) {
                fullUrl = fullUrl.substring(0, fullUrl.length() - 1);
            }
            if (matchUrls.add(fullUrl)) {
                if (isFirstXvMatch(fullUrl)) {
                    System.out.println("  [1XV MATCH] " + fullUrl);
                } else {
                    System.out.println("  [MATCH] " + fullUrl);
                }
            }
        }

        Matcher slugMatcher = MATCH_SLUG_PATTERN.matcher(jsonResponse);
        while (slugMatcher.find()) {
            String slug = slugMatcher.group(1);
            String fullUrl = BASE_URL + "/matches/" + slug;
            if (matchUrls.add(fullUrl)) {
                if (isFirstXvMatch(fullUrl)) {
                    System.out.println("  [1XV MATCH] " + fullUrl);
                } else {
                    System.out.println("  [MATCH] " + fullUrl);
                }
            }
        }
    }

    private String makeApiRequest(String url) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Veo-App-Id", "hazard")
                    .header("veo-agent", "veo:svc:web-app")
                    .timeout(Duration.ofSeconds(30))
                    .GET();

            if (authCookie != null) {
                builder.header("Cookie", authCookie);
            }

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }

            return null;
        } catch (Exception exception) {
            return null;
        }
    }

    private void printResults() {
        System.out.println("\n========================================");
        System.out.println("RESULTS");
        System.out.println("========================================");
        System.out.println("Total matches found: " + matchUrls.size());

        List<String> sortedMatches = new ArrayList<>(matchUrls);
        sortedMatches.sort((a, b) -> {
            String dateA = extractDateFromUrl(a);
            String dateB = extractDateFromUrl(b);
            return dateB.compareTo(dateA);
        });

        List<String> firstXvMatches = new ArrayList<>();
        for (String url : sortedMatches) {
            if (isFirstXvMatch(url)) {
                firstXvMatches.add(url);
            }
        }

        if (!firstXvMatches.isEmpty()) {
            System.out.println("\n1XV MATCHES (" + firstXvMatches.size() + "):");
            System.out.println("----------------------------------------");
            for (String url : firstXvMatches) {
                System.out.println(url);
            }
        }

        if (!sortedMatches.isEmpty()) {
            System.out.println("\nALL MATCHES (most recent first):");
            System.out.println("----------------------------------------");
            for (String url : sortedMatches) {
                System.out.println(url);
            }
        }
    }

    private String extractDateFromUrl(String url) {
        Pattern datePattern = Pattern.compile("/matches/(\\d{8})-");
        Matcher matcher = datePattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "00000000";
    }

    private boolean isFirstXvMatch(String url) {
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("1xv") ||
               lowerUrl.contains("1-xv") ||
               lowerUrl.contains("1_xv") ||
               lowerUrl.contains("1st-xv") ||
               lowerUrl.contains("1st_xv") ||
               lowerUrl.contains("1stxv") ||
               lowerUrl.contains("first-xv") ||
               lowerUrl.contains("firstxv") ||
               lowerUrl.contains("1s-vs") ||
               lowerUrl.contains("1s-v-") ||
               lowerUrl.contains("-1s-");
    }

    private void writeResultsToFile() {
        try {
            List<String> sortedMatches = new ArrayList<>(matchUrls);
            sortedMatches.sort((a, b) -> {
                String dateA = extractDateFromUrl(a);
                String dateB = extractDateFromUrl(b);
                return dateB.compareTo(dateA);
            });

            Files.write(Path.of(OUTPUT_FILE), sortedMatches);
            System.out.println("\nAll matches saved to " + OUTPUT_FILE);

            List<String> firstXvMatches = new ArrayList<>();
            for (String url : sortedMatches) {
                if (isFirstXvMatch(url)) {
                    firstXvMatches.add(url);
                }
            }

            if (!firstXvMatches.isEmpty()) {
                Files.write(Path.of("1xv_matches.txt"), firstXvMatches);
                System.out.println("1XV matches saved to 1xv_matches.txt");
            }
        } catch (Exception exception) {
            System.out.println("Failed to write matches: " + exception.getMessage());
        }
    }

    private void writeClubsToFile() {
        try {
            Files.write(Path.of(CLUBS_FILE), clubSlugs);
            System.out.println("Clubs saved to " + CLUBS_FILE);
        } catch (Exception exception) {
            System.out.println("Failed to write clubs: " + exception.getMessage());
        }
    }
}