package hudson.plugins.clover;

import hudson.model.Job;
import hudson.model.ProminentProjectAction;
import hudson.model.Run;
import hudson.model.Result;
import hudson.model.Actionable;
import hudson.util.Graph;
import jakarta.servlet.http.HttpServletResponse;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import java.io.File;
import java.io.IOException;

/**
 * Project level action.
 * TODO: refactor this action in a similar manner to JavadocArchiver and BaseJavadocAction etc to avoid duplication.
 */
public class CloverProjectAction extends Actionable implements ProminentProjectAction {

    static final String ICON = "/plugin/clover/clover_48x48.png";

    private transient final Job<?, ?> project;

    public CloverProjectAction(Job<?, ?> project) {
        this.project = project;
    }

    public String getIconFileName() {
        final File reportDir = getLastBuildReportDir();
        if (reportDir != null && (hasHtmlReport() || hasPdfReport(reportDir) || hasXmlReport(reportDir))) {
            return ICON;
        } else {
            return null;
        }
    }

    private boolean hasHtmlReport() {
        final Run<?, ?> lastBuild = project.getLastBuild();
        return lastBuild != null && CloverPublisher.getCloverHtmlReport(lastBuild, "").exists();
    }

    private static boolean hasPdfReport(File reportDir) {
        return new File(reportDir, "clover.pdf").exists();
    }

    private static boolean hasXmlReport(File reportDir) {
        return new File(reportDir, "clover.xml").exists();
    }

    private File getLastBuildReportDir() {
        if (project.getLastBuild() == null) {
            // no clover report links, until there is at least one build
            return null;
        }
        // report dir
        return project.getLastBuild().getRootDir();
    }

    public String getDisplayName() {
        final File reportDir = getLastBuildReportDir();

        if (reportDir == null) return null;
        if (hasHtmlReport()) return Messages.CloverProjectAction_HTML_DisplayName();
        if (hasPdfReport(reportDir)) return Messages.CloverProjectAction_PDF_DisplayName();
        if (hasXmlReport(reportDir)) return Messages.CloverProjectAction_XML_DisplayName();

        return null;
    }

    public String getUrlName() {
        return "clover";
    }

    /**
     * Returns the last Result that was successful.
     * WARNING: this method is invoked dynamically from CloverProjectAction/floatingBox.jelly
     *
     * @return the last successful build result
     */
    public CloverBuildAction getLastSuccessfulResult() {
        for (Run<?, ?> b = project.getLastBuild(); b != null; b = b.getPreviousBuild()) {
            if (b.getResult() == Result.FAILURE)
                continue;
            CloverBuildAction r = b.getAction(CloverBuildAction.class);
            if (r != null)
                return r;
        }
        return null;
    }

    public Graph getTrendGraph() {
        CloverBuildAction action = getLastSuccessfulResult();
        if (action != null)
            return action.getResult().getTrendGraph();
        return null;
    }

    /**
     * Redirects to the HTML coverage report (a ZIP download) of the last build.
     */
    public void doDynamic(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        final Run<?, ?> lastBuild = project.getLastBuild();
        if (lastBuild != null && CloverPublisher.getCloverHtmlReport(lastBuild, "").exists()) {
            rsp.sendRedirect2(req.getContextPath() + '/' + lastBuild.getUrl() + "clover-report");
        } else {
            rsp.sendError(HttpServletResponse.SC_NOT_FOUND, "Clover HTML report is not available.");
        }
    }

    public String getSearchUrl() {
        return getUrlName();
    }
}
