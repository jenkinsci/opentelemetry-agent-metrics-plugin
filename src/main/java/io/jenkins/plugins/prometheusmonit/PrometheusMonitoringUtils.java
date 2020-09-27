package io.jenkins.plugins.prometheusmonit;

import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Job;
import hudson.model.Run;

import java.util.ArrayList;
import java.util.List;

/**
 * Contains collection of helper functions for common Prometheus/Jenkins actions.
 */
public class PrometheusMonitoringUtils {

    /**
     * Returns the Prometheus monitoring action from the most recent build.
     *
     * @param project The Jenkins project which contains builds/runs to examine.
     * @return The previous Device Farm build result action.
     */
    public static PrometheusMonitoringAction previousPrometheusMonitoringBuildAction(AbstractProject<?, ?> project) {
        AbstractBuild<?, ?> build = PrometheusMonitoringUtils.previousPrometheusMonitoringBuild(project);
        if (build == null) {
            return null;
        }
        return build.getAction(PrometheusMonitoringAction.class);
    }

    /**
     * Returns the most recent build which contained Prometheus monitoring.
     *
     * @param project The Jenkins project which contains runs to examine.
     * @return The previous build with Prometheus monitoring.
     */
    public static AbstractBuild<?, ?> previousPrometheusMonitoringBuild(AbstractProject<?, ?> project) {
        AbstractBuild<?, ?> last = project.getLastBuild();
        while (last != null) {
            if (last.getAction(PrometheusMonitoringAction.class) != null) {
                break;
            }
            last = last.getPreviousBuild();
        }
        return last;
    }

    /**
     * Return collection of all previous builds of the given project which contain Prometheus monitoring.
     *
     * @param project The Jenkins project which contains runs to examine.
     * @return The previous Prometheus monitoring builds.
     */
    public static List<PrometheusMonitoringAction> previousAWSDeviceFarmBuilds(AbstractProject<?, ?> project) {
        List<PrometheusMonitoringAction> actions = new ArrayList<PrometheusMonitoringAction>();

        AbstractBuild<?, ?> build = project.getLastBuild();
        while (build != null) {
            PrometheusMonitoringAction action = build.getAction(PrometheusMonitoringAction.class);
            if (action != null) {
                actions.add(action);
            }
            build = build.getPreviousBuild();
        }
        return actions;
    }

}
