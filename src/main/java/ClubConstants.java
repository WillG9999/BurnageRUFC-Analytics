import java.util.List;
import java.util.Map;

public class ClubConstants {

    public static final String FILTER_FIRST_TEAM = "FIRST_TEAM";
    public static final String FILTER_ALL_SENIOR = "ALL_SENIOR";

    public static final List<Club> ALL_CLUBS = List.of(
        new Club("burnage-rugby-club", "Burnage", "BRC", "GB", FILTER_FIRST_TEAM),
        new Club("birkenhead-park-rfc", "Birkenhead Park", "Bpf", "GB", FILTER_FIRST_TEAM),
        new Club("winnington-park-rugby-football-club", "Winnington Park", "WP", "GB", FILTER_ALL_SENIOR),
        new Club("north-ribblesdale-rufc", "North Ribblesdale RUFC", "RIB", "GB", FILTER_ALL_SENIOR),
        new Club("widnes-rufc-b1367c81", "Widnes RUFC", "WRC", "GB", FILTER_ALL_SENIOR),
        new Club("vale-of-lune", "Vale Of Lune", "VOL", "GB", FILTER_ALL_SENIOR),
        new Club("sandbach-rufc-096253f1", "Sandbach RUFC", "B4L", "GB", FILTER_ALL_SENIOR),
        new Club("douglas-rugby-clube", "Douglas rugby club", "", "", FILTER_ALL_SENIOR),
        new Club("northwich-rufc", "Northwich RUFC", "NW", "GB", FILTER_ALL_SENIOR)
    );

    public static final Map<String, String> SLUG_TO_NAME = Map.of(
        "burnage-rugby-club", "Burnage",
        "birkenhead-park-rfc", "Birkenhead Park",
        "winnington-park-rugby-football-club", "Winnington Park",
        "north-ribblesdale-rufc", "North Ribblesdale RUFC",
        "widnes-rufc-b1367c81", "Widnes RUFC",
        "vale-of-lune", "Vale Of Lune",
        "sandbach-rufc-096253f1", "Sandbach RUFC",
        "douglas-rugby-clube", "Douglas rugby club",
        "northwich-rufc", "Northwich RUFC"
    );

    public static final List<String> ALL_SLUGS = List.of(
        "burnage-rugby-club",
        "birkenhead-park-rfc",
        "winnington-park-rugby-football-club",
        "north-ribblesdale-rufc",
        "widnes-rufc-b1367c81",
        "vale-of-lune",
        "sandbach-rufc-096253f1",
        "douglas-rugby-clube",
        "northwich-rufc"
    );

    public static final List<String> KNOWN_MATCH_SLUGS = List.of(
        "20260214-burnage-rufc-1st-team-vs-vale-of-lune-v916c08d",
        "20260131-match-1st-team-f75d2e00",
        "20251220-wprfc-v-burnage-81a144f5",
        "20251206-mens-vs-burnage-115338da",
        "20251122-match-widnes-rufc-8a2804b3",
        "20251025-vale-of-lune-vs-burnage-19477909",
        "20240928-burnage-h-aba99ab6"
    );

    public record Club(String slug, String title, String shortName, String countryCode, String filterType) {}
}

