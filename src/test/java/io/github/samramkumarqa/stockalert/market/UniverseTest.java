package io.github.samramkumarqa.stockalert.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.samramkumarqa.stockalert.StubServer;

class UniverseTest {

    private static final String HEADER = "Company Name,Industry,Symbol,Series,ISIN Code\n";

    private static String csvWith(int n) {
        return HEADER + IntStream.range(0, n)
                .mapToObj(i -> "Company " + i + ",Industry,SYM" + i + ",EQ,INE00000000" + i)
                .collect(Collectors.joining("\n"));
    }

    @Test
    void parsesSymbolsFromNseCsvKeepingOnlyEqSeries() {
        String csv = "﻿" + HEADER
                + "Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018\r\n"
                + "Mahindra & Mahindra Ltd.,Automobile,M&M,EQ,INE101A01026\r\n"
                + "Some Bond,Financial Services,XYZ,BE,INE999\r\n"
                + "Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018\r\n";

        assertThat(Universe.parseCsv(csv)).containsExactly("RELIANCE", "M&M");
    }

    @Test
    void rejectsCsvWithoutSymbolColumn() {
        assertThatThrownBy(() -> Universe.parseCsv("a,b\n1,2")).hasMessageContaining("Symbol");
    }

    @Test
    void downloadsAndCachesTheList(@TempDir Path dir) throws Exception {
        try (StubServer stub = new StubServer()) {
            stub.enqueue(200, csvWith(500));
            Path cache = dir.resolve("data/nifty500.csv");

            assertThat(Universe.load(stub.baseUrl() + "/list.csv", cache, HttpClient.newHttpClient())).hasSize(500);
            assertThat(Files.readString(cache)).contains("SYM499");
        }
    }

    @Test
    void triesNextUrlThenFallsBackToCache(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("nifty500.csv");
        Files.writeString(cache, csvWith(60));
        try (StubServer stub = new StubServer()) {
            stub.enqueue(403, "blocked").enqueue(200, "<html>captcha</html>");
            String urls = stub.baseUrl() + "/a.csv," + stub.baseUrl() + "/b.csv";

            assertThat(Universe.load(urls, cache, HttpClient.newHttpClient())).hasSize(60);
            assertThat(stub.requests()).hasSize(2);
        }
    }

    @Test
    void failsClearlyWhenNothingIsAvailable(@TempDir Path dir) {
        try (StubServer stub = new StubServer()) {
            stub.enqueue(500, "down");
            assertThatThrownBy(() -> Universe.load(stub.baseUrl() + "/x.csv", dir.resolve("none.csv"),
                    HttpClient.newHttpClient()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UNIVERSE_FILE");
        }
    }
}
