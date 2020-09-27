package io.jenkins.plugins.prometheusmonit;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.util.ChartUtil;
import hudson.util.Graph;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Prometheus monitoring specific action tied to a Jenkins project.
 * <p>
 * This class is used for the top-level project view of your project if it is configured to use Prometheus monitoring.
 * It is responsible for serving up the project level graph.
 */
public class PrometheusMonitoringProjectAction implements Action {
    private AbstractProject<?, ?> project;

    /**
     * Create new Prometheus monitoring project action.
     *
     * @param project The Project which this action will be applied to.
     */
    public PrometheusMonitoringProjectAction(AbstractProject<?, ?> project) {
        this.project = project;
    }

    /**
     * Get project associated with this action.
     *
     * @return The project.
     */
    public AbstractProject<?, ?> getProject() {
        return project;
    }

    /**
     * Returns true if there are any builds in the associated project.
     *
     * @return Whether or not the graph should be displayed.
     */
    public boolean shouldDisplayGraph() {
        return true;
    }

    /**
     * Return the action of last build associated with AWS Device Farm.
     *
     * @return The most recent build with AWS Device Farm or null.
     */
    public AWSDeviceFarmTestResultAction getLastBuildAction() {
        return null;
    }

    /**
     * Serve up Prometheus monitoring project page which redirects to the latest test results or 404.
     *
     * @param request  The request object.
     * @param response The response object.
     * @throws IOException
     */
    @SuppressWarnings("unused")
    public void doIndex(StaplerRequest request, StaplerResponse response) throws IOException {
        AbstractBuild<?, ?> prev = AWSDeviceFarmUtils.previousAWSDeviceFarmBuild(project);
        if (prev == null) {
            response.sendRedirect2("404");
        } else {
            // Redirect to build page of most recent AWS Device Farm test run.
            response.sendRedirect2(String.format("../%d/%s", prev.getNumber(), getUrlName()));
        }
    }

    /**
     * Get the icon file name.
     *
     * @return The path to the icon.
     */
    public String getIconFileName() {
        //return "http://g-ecx.images-amazon.com/images/G/01/aws-device-farm/service-icon.svg";
        return "/plugin/prometheus-monitoring/service-icon.svg";
    }

    /**
     * Get the display name.
     *
     * @return The display name.
     */
    public String getDisplayName() {
        return "Prometheus monitoring";
    }

    /**
     * Get the URL name.
     *
     * @return The URL name.
     */
    public String getUrlName() {
        return "prometheus-monitoring";
    }
}
