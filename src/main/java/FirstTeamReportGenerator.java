import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FirstTeamReportGenerator {

    private static final String API_BASE = "https://app.veo.co/api/app";
    private static final String BASE_URL = "https://app.veo.co";
    private static final Pattern MATCH_URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"(/matches/[^\"]+)\"");
    private static final Pattern MATCH_TITLE_PATTERN = Pattern.compile("\"title\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEAM_NAME_PATTERN = Pattern.compile("\"team\"\\s*:\\s*\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient;
    private final List<MatchRecord> allMatches = new ArrayList<>();
    private final List<MatchRecord> firstTeamMatches = new ArrayList<>();

    public static void main(String[] args) {
        FirstTeamReportGenerator generator = new FirstTeamReportGenerator();
        generator.run();
    }

    public FirstTeamReportGenerator() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    private void run() {
        System.out.println("========================================");
        System.out.println("FIRST TEAM MATCH REPORT GENERATOR");
        System.out.println("========================================\n");

        fetchMatchesFromAllClubs();
        categorizeMatches();
        sortByDate();
        generateReport();
        writeReportToFile();
    }

    private void fetchMatchesFromAllClubs() {
        System.out.println("Fetching matches from " + ClubConstants.ALL_CLUBS.size() + " clubs...\n");

        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            System.out.println("Club: " + club.title() + " (" + club.slug() + ")");
            fetchClubRecordings(club);
            System.out.println();
        }
    }

    private void fetchClubRecordings(ClubConstants.Club club) {
        String recordingsUrl = API_BASE + "/clubs/" + club.slug() + "/recordings/?limit=50";

        String response = makeApiRequest(recordingsUrl);
        if (response == null) {
            System.out.println("  No recordings found");
            return;
        }

        extractMatches(response, club);
        fetchAllPages(response, club);
    }

    private void fetchAllPages(String jsonResponse, ClubConstants.Club club) {
        Pattern nextPattern = Pattern.compile("\"next\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = nextPattern.matcher(jsonResponse);

        if (matcher.find()) {
            String nextUrl = matcher.group(1)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/");

            String response = makeApiRequest(nextUrl);
            if (response != null) {
                extractMatches(response, club);
                fetchAllPages(response, club);
            }
        }
    }

    private void extractMatches(String json, ClubConstants.Club club) {
        Pattern matchBlockPattern = Pattern.compile(
            "\\{[^{]*\"url\"\\s*:\\s*\"(/matches/[^\"]+)\"[^}]*\"title\"\\s*:\\s*\"([^\"]+)\"[^}]*\\}|" +
            "\\{[^{]*\"title\"\\s*:\\s*\"([^\"]+)\"[^}]*\"url\"\\s*:\\s*\"(/matches/[^\"]+)\"[^}]*\\}"
        );

        Matcher urlMatcher = MATCH_URL_PATTERN.matcher(json);
        while (urlMatcher.find()) {
            String matchPath = urlMatcher.group(1);
            String fullUrl = BASE_URL + matchPath;
            if (fullUrl.endsWith("/")) {
                fullUrl = fullUrl.substring(0, fullUrl.length() - 1);
            }

            String title = extractTitleForMatch(json, matchPath);
            String teamName = extractTeamNameForMatch(json, matchPath);
            String date = extractDateFromUrl(fullUrl);

            MatchRecord record = new MatchRecord(fullUrl, title, teamName, club.title(), club.slug(), date);
            allMatches.add(record);
            System.out.println("  Found: " + title);
        }
    }

    private String extractTitleForMatch(String json, String matchPath) {
        int matchIndex = json.indexOf(matchPath);
        if (matchIndex > 0) {
            int searchStart = Math.max(0, matchIndex - 500);
            int searchEnd = Math.min(json.length(), matchIndex + 500);
            String context = json.substring(searchStart, searchEnd);

            Matcher titleMatcher = MATCH_TITLE_PATTERN.matcher(context);
            if (titleMatcher.find()) {
                return titleMatcher.group(1);
            }
        }
        return "Unknown";
    }

    private String extractTeamNameForMatch(String json, String matchPath) {
        int matchIndex = json.indexOf(matchPath);
        if (matchIndex > 0) {
            int searchStart = Math.max(0, matchIndex - 1000);
            int searchEnd = Math.min(json.length(), matchIndex + 200);
            String context = json.substring(searchStart, searchEnd);

            Matcher teamMatcher = TEAM_NAME_PATTERN.matcher(context);
            if (teamMatcher.find()) {
                return teamMatcher.group(1);
            }
        }
        return "Unknown";
    }

    private String extractDateFromUrl(String url) {
        Pattern datePattern = Pattern.compile("/matches/(\\d{8})-");
        Matcher matcher = datePattern.matcher(url);
        if (matcher.find()) {
            String dateStr = matcher.group(1);
            return dateStr.substring(0, 4) + "-" + dateStr.substring(4, 6) + "-" + dateStr.substring(6, 8);
        }
        return "0000-00-00";
    }

    private void categorizeMatches() {
        System.out.println("Categorizing matches...\n");

        for (MatchRecord match : allMatches) {
            if (isFirstTeamMatch(match)) {
                firstTeamMatches.add(match);
            }
        }

        System.out.println("Total matches: " + allMatches.size());
        System.out.println("First team matches: " + firstTeamMatches.size());
    }

    private boolean isFirstTeamMatch(MatchRecord match) {
        String title = match.title.toLowerCase();
        String team = match.teamName.toLowerCase();
        String url = match.url.toLowerCase();

        if (isExcludedMatch(title, team, url)) {
            return false;
        }

        String filterType = getFilterTypeForClub(match.clubSlug);

        if (ClubConstants.FILTER_ALL_SENIOR.equals(filterType)) {
            return true;
        }

        return title.contains("1st") ||
               title.contains("1xv") ||
               title.contains("first") ||
               title.contains("1 xv") ||
               team.contains("1st") ||
               team.contains("1xv") ||
               team.contains("first") ||
               team.contains("mens") ||
               team.contains("senior") ||
               url.contains("1st") ||
               url.contains("1xv") ||
               url.contains("-1s-") ||
               url.contains("mens");
    }

    private String getFilterTypeForClub(String clubSlug) {
        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            if (club.slug().equals(clubSlug)) {
                return club.filterType();
            }
        }
        return ClubConstants.FILTER_FIRST_TEAM;
    }

    private boolean isExcludedMatch(String title, String team, String url) {
        String combined = title + " " + team + " " + url;

        if (combined.contains("ladies") || combined.contains("women")) {
            return true;
        }

        if (combined.matches(".*\\bu\\d{1,2}s?\\b.*") ||
            combined.matches(".*\\bunder\\s*\\d{1,2}\\b.*") ||
            combined.matches(".*\\bu-\\d{1,2}\\b.*")) {
            return true;
        }

        if (combined.contains("colts") || combined.contains("colt")) {
            return true;
        }

        if (combined.contains("2nd xv") || combined.contains("2nd team") ||
            combined.contains("3rd xv") || combined.contains("3rd team") ||
            combined.contains("2 xv") || combined.contains("3 xv") ||
            combined.contains("2xv") || combined.contains("3xv") ||
            combined.matches(".*\\b2s\\b.*") || combined.matches(".*\\b3s\\b.*") ||
            combined.matches(".*\\b2's\\b.*") || combined.matches(".*\\b3's\\b.*")) {
            return true;
        }

        if (combined.matches(".*\\b\\w+\\s+2\\s+v\\b.*") ||
            combined.matches(".*\\bv\\s+\\w+\\s+2\\b.*") ||
            combined.matches(".*\\b\\w+\\s+2\\s+vs\\b.*") ||
            combined.matches(".*\\bvs\\s+\\w+\\s+2\\b.*")) {
            return true;
        }

        if (combined.contains("training") || combined.contains("fire vs")) {
            return true;
        }

        if (combined.contains("ljmu")) {
            return true;
        }

        if (combined.contains("transition") || combined.contains("development")) {
            return true;
        }

        return false;
    }

    private void sortByDate() {
        firstTeamMatches.sort((a, b) -> b.date.compareTo(a.date));
        allMatches.sort((a, b) -> b.date.compareTo(a.date));
    }

    private void generateReport() {
        System.out.println("\n========================================");
        System.out.println("1ST TEAM / MENS MATCHES REPORT");
        System.out.println("(Grouped by Club, Most Recent First)");
        System.out.println("========================================\n");

        System.out.println("TRACKED CLUBS:");
        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            int matchCount = getMatchesForClub(club.title()).size();
            String filterLabel = ClubConstants.FILTER_ALL_SENIOR.equals(club.filterType()) ? "All Senior" : "1st XV Only";
            System.out.println("  " + club.title() + " [" + filterLabel + "] - " + matchCount + " matches");
        }

        if (firstTeamMatches.isEmpty()) {
            System.out.println("\nNo first team matches found.");
            return;
        }

        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            List<MatchRecord> clubMatches = getMatchesForClub(club.title());
            if (clubMatches.isEmpty()) {
                continue;
            }

            System.out.println("\n--- " + club.title() + " ---");
            for (MatchRecord match : clubMatches) {
                System.out.println(match.date + " | " + match.title);
                System.out.println("  " + match.url);
            }
        }

        System.out.println("\n========================================");
        System.out.println("CLUBS REQUIRING ALTERNATIVE ACCESS:");
        System.out.println("  Firwood Waterloo - Use alternative recording source (not Veo)");
        System.out.println("  AK (Altrincham Kersal) - Requires login credentials to access");
    }

    private List<MatchRecord> getMatchesForClub(String clubName) {
        List<MatchRecord> clubMatches = new ArrayList<>();
        for (MatchRecord match : firstTeamMatches) {
            if (match.clubName.equals(clubName)) {
                clubMatches.add(match);
            }
        }
        clubMatches.sort((a, b) -> b.date.compareTo(a.date));
        return clubMatches;
    }

    private void writeReportToFile() {
        List<String> reportLines = new ArrayList<>();

        reportLines.add("1ST TEAM / MENS MATCHES REPORT");
        reportLines.add("Generated: " + java.time.LocalDate.now());
        reportLines.add("========================================");
        reportLines.add("");
        reportLines.add("MATCHES BY CLUB (Most Recent First):");
        reportLines.add("");

        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            List<MatchRecord> clubMatches = getMatchesForClub(club.title());
            if (clubMatches.isEmpty()) {
                continue;
            }

            reportLines.add("--- " + club.title() + " ---");
            for (MatchRecord match : clubMatches) {
                reportLines.add(match.date + " | " + match.title);
                reportLines.add("  URL: " + match.url);
                reportLines.add("");
            }
        }

        reportLines.add("========================================");
        reportLines.add("TRACKED CLUBS:");
        reportLines.add("");
        for (ClubConstants.Club club : ClubConstants.ALL_CLUBS) {
            int matchCount = getMatchesForClub(club.title()).size();
            String filterLabel = ClubConstants.FILTER_ALL_SENIOR.equals(club.filterType()) ? "All Senior" : "1st XV Only";
            reportLines.add("  " + club.title() + " (" + club.slug() + ") [" + filterLabel + "] - " + matchCount + " matches");
        }
        reportLines.add("");
        reportLines.add("========================================");
        reportLines.add("Total first team matches: " + firstTeamMatches.size());
        reportLines.add("Total all matches: " + allMatches.size());
        reportLines.add("");
        reportLines.add("========================================");
        reportLines.add("CLUBS REQUIRING ALTERNATIVE ACCESS:");
        reportLines.add("");
        reportLines.add("  Firwood Waterloo - Use alternative recording source (not Veo)");
        reportLines.add("  AK (Altrincham Kersal) - Requires login credentials to access");
        reportLines.add("");

        try {
            Files.write(Path.of("first_team_report.txt"), reportLines);
            System.out.println("\nReport saved to first_team_report.txt");
        } catch (Exception e) {
            System.out.println("Failed to write report: " + e.getMessage());
        }

        List<String> urlsOnly = new ArrayList<>();
        for (MatchRecord match : firstTeamMatches) {
            urlsOnly.add(match.url);
        }

        try {
            Files.write(Path.of("first_team_urls.txt"), urlsOnly);
            System.out.println("URLs saved to first_team_urls.txt");
        } catch (Exception e) {
            System.out.println("Failed to write URLs: " + e.getMessage());
        }
    }

    private String makeApiRequest(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15")
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("Veo-App-Id", "hazard")
                    .header("veo-agent", "veo:svc:web-app")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private record MatchRecord(String url, String title, String teamName, String clubName, String clubSlug, String date) {}
}

