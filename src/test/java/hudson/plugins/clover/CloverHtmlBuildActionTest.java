package hudson.plugins.clover;

import static java.util.Objects.requireNonNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.plugins.clover.targets.CoverageTarget;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.htmlunit.Page;
import org.htmlunit.WebResponse;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CloverHtmlBuildActionTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private void writeHtmlReport(FilePath siteDir) throws Exception {
        siteDir.mkdirs();
        siteDir.child("index.html").write("<html><body>coverage</body></html>", "UTF-8");
        siteDir.child("js").mkdirs();
        siteDir.child("js").child("foo.js").write("console.log('cov');", "UTF-8");
        siteDir.child("clover.xml")
                .copyFrom(requireNonNull(
                        CloverWorkflowTest.class.getResourceAsStream("/hudson/plugins/clover/clover.xml")));
    }

    @Test
    void testHtmlReportIsZipped() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("TestHtmlReportZip");
        FilePath workspace = j.jenkins.getWorkspaceFor(project);
        assertNotNull(workspace, "Workspace should not be null");
        writeHtmlReport(workspace.child("target").child("site"));

        CloverPublisher publisher = new CloverPublisher("target/site", "clover.xml");
        project.getPublishersList().add(publisher);

        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        // The report is stored as a single ZIP, not unpacked into the build root.
        File zip = new File(build.getRootDir(), "clover-html-report.zip");
        assertTrue(zip.exists(), "clover-html-report.zip should exist in the build dir");
        assertFalse(new File(build.getRootDir(), "index.html").exists(), "index.html should not be unpacked");
        assertFalse(new File(build.getRootDir(), "js").exists(), "js/ should not be unpacked");
        assertTrue(new File(build.getRootDir(), "clover.xml").exists(), "clover.xml stays loose for the summary");

        // The ZIP contains the report files.
        List<String> entries = getZipFileEntries(zip);
        // FilePath.zip keeps the top report directory as the entry prefix.
        assertThat(entries, hasItem(endsWith("/index.html")));
        assertThat(entries, hasItem(endsWith("/js/foo.js")));

        CloverHtmlBuildAction action = build.getAction(CloverHtmlBuildAction.class);
        assertNotNull(action, "CloverHtmlBuildAction should be attached");
        assertEquals("clover-report", action.getUrlName());

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            wc.setThrowExceptionOnFailingStatusCode(false);

            // The action downloads the ZIP.
            WebResponse response = downloadReport(wc, build, action);
            assertEquals(200, response.getStatusCode());
            assertEquals("application/zip", response.getContentType());

            // The build directory is no longer browsable through the action.
            Page browse = wc.getPage(new URL(wc.getContextPath() + build.getUrl() + action.getUrlName() + "/build.xml"));
            assertEquals(404, browse.getWebResponse().getStatusCode());
        }
    }

    @Test
    void testMultipleHtmlReportsProduceDistinctZips() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("TestMultipleHtmlReportZips");
        FilePath workspace = j.jenkins.getWorkspaceFor(project);
        assertNotNull(workspace, "Workspace should not be null");
        writeHtmlReport(workspace.child("app1").child("target").child("site"));
        writeHtmlReport(workspace.child("app2").child("target").child("site"));

        CloverPublisher publisher1 = new CloverPublisher(
                "app1/target/site",
                "clover.xml",
                new CoverageTarget(70, 80, 80),
                new CoverageTarget(50, 60, 60),
                new CoverageTarget(0, 0, 0));
        publisher1.setReportId("1");

        CloverPublisher publisher2 = new CloverPublisher(
                "app2/target/site",
                "clover.xml",
                new CoverageTarget(60, 70, 70),
                new CoverageTarget(40, 50, 50),
                new CoverageTarget(0, 0, 0));
        publisher2.setReportId("2");

        project.getPublishersList().add(publisher1);
        project.getPublishersList().add(publisher2);

        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        assertTrue(new File(build.getRootDir(), "clover-html-report-1.zip").exists());
        assertTrue(new File(build.getRootDir(), "clover-html-report-2.zip").exists());

        List<CloverHtmlBuildAction> actions = build.getActions(CloverHtmlBuildAction.class);
        assertEquals(2, actions.size(), "Should have two CloverHtmlBuildAction instances");
        List<String> urls = getActionUrlNames(actions);
        assertThat(urls, containsInAnyOrder("clover-report-1", "clover-report-2"));

        try (JenkinsRule.WebClient wc = j.createWebClient()) {
            for (CloverHtmlBuildAction action : actions) {
                assertEquals("application/zip", downloadReport(wc, build, action).getContentType());
            }
        }
    }

    private static @NonNull List<String> getZipFileEntries(File zip) throws IOException {
        final List<String> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip)) {
            var e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory()) {
                    entries.add(entry.getName());
                }
            }
        }
        return entries;
    }

    private static @NonNull List<String> getActionUrlNames(List<CloverHtmlBuildAction> actions) {
        List<String> urls = new ArrayList<>();
        for (CloverHtmlBuildAction action : actions) {
            urls.add(action.getUrlName());
        }
        return urls;
    }

    private WebResponse downloadReport(JenkinsRule.WebClient wc, FreeStyleBuild build, CloverHtmlBuildAction action)
            throws Exception {
        Page page = wc.getPage(new URL(wc.getContextPath() + build.getUrl() + action.getUrlName()));
        return page.getWebResponse();
    }
}
