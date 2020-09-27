package io.jenkins.plugins.prometheusmonit;

import hudson.model.AbstractBuild;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.tasks.test.AbstractTestResultAction;
import org.kohsuke.stapler.StaplerProxy;

import javax.annotation.CheckForNull;
import java.io.PrintStream;

/**
 * Action which controls the execution management and results updating for AWS Device Farm runs.
 */
public class PrometheusMonitoringAction extends AbstractTestResultAction<PrometheusMonitoringAction> implements StaplerProxy {

    private static final int DefaultUpdateInterval = 30 * 1000;
    private AWSDeviceFarmTestResult result;

    public PrometheusMonitoringAction(AbstractBuild<?, ?> owner, AWSDeviceFarmTestResult result) {
        super(owner);
        this.result = result;
    }

    public PrometheusMonitoringAction(hudson.model.Run<?, ?> owner, AWSDeviceFarmTestResult result) {
        super();
        onAttached(owner);
        this.result = result;
    }

    /**
     * @deprecated log is no longer passed, use {@link #PrometheusMonitoringAction(AbstractBuild, AWSDeviceFarmTestResult)}
     */
    @Deprecated
    public PrometheusMonitoringAction(AbstractBuild<?, ?> owner, AWSDeviceFarmTestResult result, @CheckForNull PrintStream log) {
        this(owner, result);
    }

    /**
     * Returns the Jenkins result which matches the result of this AWS Device Farm run.
     *
     * @return
     */
    public Result getBuildResult(Boolean ignoreRunError) {
        return getResult().getBuildResult(ignoreRunError);
    }

    /**
     * Returns the most recent Prometheus monitoring action from the previous build.
     *
     * @return
     */
    @Override
    public PrometheusMonitoringAction getPreviousResult() {
        AbstractBuild<?, ?> build = getOwner();
        if (owner == null) {
            return null;
        }
        return PrometheusMonitoringUtils.previousPrometheusMonitoringAction(build.getProject());
    }

    /**
     * Returns a snapshot of the current results for this AWS Device Farm run.
     *
     * @return
     */
    @Override
    public AWSDeviceFarmTestResult getResult() {
        return result;
    }

    /**
     * Returns a snapshot of the current results for this AWS Device Farm run.
     *
     * @return
     */
    public AWSDeviceFarmTestResult getTarget() {
        return getResult();
    }

    /**
     * Returns the number of failed tests for this AWS Device Farm run.
     *
     * @return
     */
    @Override
    public int getFailCount() {
        AWSDeviceFarmTestResult result = getResult();
        if (result != null) {
            return getResult().getFailCount();
        } else {
            return -1;
        }
    }

    /**
     * Returns the total number of tests for this AWS Device Farm run.
     *
     * @return
     */
    @Override
    public int getTotalCount() {
        AWSDeviceFarmTestResult result = getResult();
        if (result != null) {
            return getResult().getTotalCount();
        } else {
            return -1;
        }
    }

    public AbstractBuild<?, ?> getOwner() {
        return owner;
    }

    @Override
    public String getUrlName() {
        return "aws-device-farm";
    }

    @Override
    public String getDisplayName() {
        return "AWS Device Farm";
    }

    @Override
    public String getIconFileName() {
        return "/plugin/aws-device-farm/service-icon.svg";
    }

    //// Helper Methods

    private void writeToLog(PrintStream log, String message) {
        if (log != null) {
            log.println(String.format("[AWSDeviceFarm] %s", message));
        }
    }
}
