package hudson.plugins.clover;

import hudson.model.Run;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;


public class CloverHtmlBuildAction implements RunAction2 {

    private transient Run<?, ?> build;

    private String reportId;

    public CloverHtmlBuildAction() {
    }

    public CloverHtmlBuildAction(String reportId) {
        this.reportId = reportId;
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        build = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        build = r;
    }

    public String getDisplayName() {
        return Messages.CloverHtmlBuildAction_DisplayName();
    }

    /**
     * Serves the Clover HTML report as a downloadable ZIP archive. Jenkins' default content security policy
     * blocks the active content in the report when served in-browser, so we hand back the whole report as
     * a single ZIP file instead.
     */
    public void doIndex(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        final File report = CloverPublisher.getCloverHtmlReport(build, reportId);
        if (!report.exists()) {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND, "Clover HTML report is not available for this build.");
            return;
        }
        rsp.setContentType("application/zip");
        rsp.setHeader("Content-Disposition", "attachment; filename=\"" + report.getName() + "\"");
        rsp.setContentLengthLong(report.length());
        try (OutputStream out = rsp.getOutputStream()) {
            Files.copy(report.toPath(), out);
        }
    }

    public String getIconFileName() {
        return CloverProjectAction.ICON;
    }

    public String getUrlName() {
        return (reportId == null || reportId.isEmpty()) ? "clover-report" : "clover-report-" + reportId;
    }

    public String getReportId() {
        return reportId;
    }
}
