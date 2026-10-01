package com.example.hotel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * FULL VERIFICATION MODE guard for the PostgreSQL/Testcontainers integration suite (Maven profile
 * {@code integration}, invoked as {@code mvn verify -Pintegration}).
 *
 * <p>Every PostgreSQL integration test in this repository is annotated
 * {@code @Testcontainers(disabledWithoutDocker = true)}: when Docker is unreachable, JUnit marks those tests
 * SKIPPED rather than FAILED. Surefire does not fail the build on skips, so {@code mvn test} can report
 * {@code BUILD SUCCESS} while every one of those tests silently did nothing. That is the correct, convenient
 * behavior for fast/default local development, but it means a human has to remember to read the skipped count
 * to know whether the PostgreSQL safety net actually ran.</p>
 *
 * <p>This class removes that reliance on a human. Maven Failsafe (not Surefire; see the naming convention below)
 * runs it in the {@code integration-test} phase, strictly after the {@code test} phase has finished and written
 * every {@code target/surefire-reports/*.xml} file. It sums each report's {@code tests}/{@code skipped}/
 * {@code failures}/{@code errors} totals and fails loudly if anything other than a fully-executed, fully-green
 * run is detected: any skip (Docker unavailable, misconfigured Testcontainers client, or any other reason a
 * {@code @Testcontainers(disabledWithoutDocker = true)} class silently stood down), any failure or error that
 * somehow reached this phase, or a total test count far short of what a real run should produce.</p>
 *
 * <p><b>Why this never runs under {@code mvn test}:</b> Maven Failsafe's default class-discovery pattern is
 * {@code **&#47;*IT.java} / {@code **&#47;IT*.java} / {@code **&#47;*ITCase.java} -- Surefire's default pattern
 * ({@code **&#47;*Test.java} and friends) explicitly does not match this class. The {@code integration} Maven
 * profile is the only place Failsafe's {@code integration-test}/{@code verify} goals are bound in this project's
 * {@code pom.xml}, so this guard plays no role at all unless that profile is active. Fast/default local
 * development ({@code mvn test}, the IDE's default test run) is completely unaffected.</p>
 */
class PostgresIntegrationSuiteCompletenessIT {

    /**
     * The last known-good total test count for a complete run of this suite (see AGENTS.md /
     * docs/coding-rules for how to run the full PostgreSQL verification). This is a floor, not an exact
     * pin: update it upward when tests are legitimately added. Its only job is to catch a run that silently
     * executed far fewer classes than expected, for example a stray {@code -Dtest=...} filter left on the
     * command line.
     */
    private static final int MINIMUM_EXPECTED_TESTS = 1867;

    private static final File SUREFIRE_REPORTS_DIRECTORY = new File("target/surefire-reports");

    /**
     * Aggregates every Surefire XML report from the {@code test} phase that just ran and asserts the run was
     * complete: no skips, no failures/errors, and at least {@link #MINIMUM_EXPECTED_TESTS} tests executed.
     *
     * @throws Exception if a Surefire report cannot be parsed
     */
    @Test
    void everyPostgresIntegrationTestActuallyRanWithNoSkips() throws Exception {
        File[] reportFiles = SUREFIRE_REPORTS_DIRECTORY.listFiles((directory, name) -> name.endsWith(".xml"));
        assertTrue(reportFiles != null && reportFiles.length > 0, "No " + SUREFIRE_REPORTS_DIRECTORY
                + "/*.xml reports were found. `mvn verify -Pintegration` must run the `test` phase first (do not "
                + "pass -DskipTests or -Dmaven.test.skip=true) so this completeness guard has something to check.");

        int totalTests = 0;
        int totalSkipped = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        for (File reportFile : reportFiles) {
            Document document = factory.newDocumentBuilder().parse(reportFile);
            Element root = document.getDocumentElement();
            totalTests += intAttribute(root, "tests");
            totalSkipped += intAttribute(root, "skipped");
            totalFailures += intAttribute(root, "failures");
            totalErrors += intAttribute(root, "errors");
        }

        assertTrue(totalSkipped == 0, "FULL verification mode requires every test to actually execute, but "
                + totalSkipped + " of " + totalTests + " were SKIPPED. Every PostgreSQL integration test in this "
                + "repository uses @Testcontainers(disabledWithoutDocker = true), which silently skips instead of "
                + "failing when Docker is unreachable. Start Docker Desktop (or otherwise make a working Docker "
                + "environment resolvable by Testcontainers) and re-run `mvn verify -Pintegration`.");
        assertTrue(totalFailures == 0 && totalErrors == 0, "The test phase reported " + totalFailures
                + " failure(s) and " + totalErrors + " error(s); Maven should already have stopped before this "
                + "guard ran unless test failures are being ignored (-Dmaven.test.failure.ignore=true or -fae). "
                + "Fix the underlying test failures; do not relax this guard.");
        assertTrue(totalTests >= MINIMUM_EXPECTED_TESTS, "Only " + totalTests + " tests ran; expected at least "
                + MINIMUM_EXPECTED_TESTS + ". A narrow -Dtest filter or a broken test-discovery configuration may "
                + "have silently run only part of the suite.");
    }

    /**
     * Reads one integer Surefire report attribute, treating a missing/blank value as zero.
     *
     * @param element the report's root {@code <testsuite>} element
     * @param name the attribute name ({@code tests}, {@code skipped}, {@code failures}, or {@code errors})
     * @return the parsed attribute value, or zero when absent
     */
    private static int intAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isBlank() ? 0 : Integer.parseInt(value);
    }
}
