package io.jenkins.plugins.prometheusmonit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.google.common.base.Optional;
import com.thoughtworks.xstream.XStream;

import antlr.ANTLRException;
import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.Util;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.AbstractProject;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Items;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.Run.RunnerAbortedException;
import hudson.model.labels.LabelAtom;
import hudson.model.listeners.RunListener;
import hudson.remoting.Channel;
import hudson.remoting.ChannelClosedException;
import hudson.slaves.ComputerListener;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tools.ToolInstallation;
import hudson.util.ArgumentListBuilder;
import hudson.util.FormValidation;
import hudson.util.ProcessTree;
import hudson.util.ProcessTree.OSProcess;
import hudson.util.XStream2;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import jenkins.tasks.SimpleBuildWrapper;
import net.sf.json.JSONObject;

public class PrometheusMonitoring extends SimpleBuildWrapper {

    private static final class ComputerNameComparator implements Comparator<Computer> {

        private static final ComputerNameComparator INSTANCE = new ComputerNameComparator();

        @Override
        public int compare(final Computer left, final Computer right) {
            return left.getName().compareTo(right.getName());
        }

    }

    @Extension(ordinal = Double.MAX_VALUE)
    public static class PrometheusMonitoringBuildWrapperDescriptor extends BuildWrapperDescriptor {

        /** NExporter installations, this descriptor persists all installations configured. */
        @CopyOnWrite
        private volatile NExporterInstallation[] installations = new NExporterInstallation[0];

        public PrometheusMonitoringBuildWrapperDescriptor() {
            load();
        }

        /** adopted from @see hudson.model.AbstractProject.AbstractProjectDescriptor#doAutoCompleteAssignedLabels */
        public AutoCompletionCandidates doAutoCompleteAssignedLabels(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value) {
            final AutoCompletionCandidates candidates = new AutoCompletionCandidates();
            final Set<Label> labels = Jenkins.getInstance().getLabels();

            for (final Label label : labels) {
                if (value == null || label.getName().startsWith(value)) {
                    candidates.add(label.getName());
                }
            }

            return candidates;
        }

        /** adopted from @see hudson.model.AbstractProject.AbstractProjectDescriptor#doCheckAssignedLabels */
        public FormValidation doCheckAssignedLabels(@AncestorInPath final AbstractProject<?, ?> project, @QueryParameter final String value) {
            if (Util.fixEmpty(value) == null) {
                return FormValidation.ok();
            }

            try {
                Label.parseExpression(value);
            } catch (final ANTLRException e) {
                return FormValidation.error(e, Messages.PrometheusMonitoringBuildWrapper_AssignedLabelString_InvalidBooleanExpression(e.getMessage()));
            }

            final Jenkins jenkins = Jenkins.getInstance();
            final Label label = jenkins.getLabel(value);

            if (label.isEmpty()) {
                for (final LabelAtom labelAtom : label.listAtoms()) {
                    if (labelAtom.isEmpty()) {
                        final LabelAtom nearest = LabelAtom.findNearest(labelAtom.getName());
                        return FormValidation.warning(Messages.PrometheusMonitoringBuildWrapper_AssignedLabelString_NoMatch_DidYouMean(labelAtom.getName(), nearest.getDisplayName()));
                    }
                }
                return FormValidation.warning(Messages.PrometheusMonitoringBuildWrapper_AssignedLabelString_NoMatch());
            }

            return FormValidation.okWithMarkup(Messages.PrometheusMonitoringBuildWrapper_LabelLink(jenkins.getRootUrl(), label.getUrl(), label.getNodes().size() + label.getClouds().size()));
        }

        public FormValidation doCheckPort(@QueryParameter final String value) throws IOException {
            return validateOptionalPositiveInteger(value);
        }

        public FormValidation doCheckTimeout(@QueryParameter final String value) throws IOException {
            return validateOptionalNonNegativeInteger(value);
        }

        @Override
        public String getDisplayName() {
            return Messages.PrometheusMonitoringBuildWrapper_DisplayName();
        }

        public NExporterInstallation[] getInstallations() {
            return installations;
        }

        public NExporterInstallation.DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(NExporterInstallation.DescriptorImpl.class);
        }

        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public BuildWrapper newInstance(final StaplerRequest req, final JSONObject formData) throws hudson.model.Descriptor.FormException {
            return req.bindJSON(PrometheusMonitoring.class, formData);
        }

        public void setInstallations(final NExporterInstallation... installations) {
            this.installations = installations;
            save();
        }

        private FormValidation validateOptionalNonNegativeInteger(final String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }

            return FormValidation.validateNonNegativeInteger(value);
        }

        private FormValidation validateOptionalPositiveInteger(final String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.ok();
            }

            return FormValidation.validatePositiveInteger(value);
        }
    }

    private static final String JENKINS_XVFB_COOKIE = "_JENKINS_XVFB_COOKIE";

    private static final String STDERR_FD = "2";

/*    @SuppressWarnings("rawtypes")
    @Extension
    public static final RunListener<Run> xvfbShutdownListener = new RunListener<Run>() {
        @Override
        public void onCompleted(final Run r, final TaskListener listener) {
            final XvfbEnvironment xvfbEnvironment = r.getAction(XvfbEnvironment.class);

            if (xvfbEnvironment != null && xvfbEnvironment.shutdownWithBuild) {
                try {
                    final Executor executor = r.getExecutor();
                    final Computer computer = executor.getOwner();
                    final Node node = computer.getNode();
                    final Launcher launcher = node.createLauncher(listener);

                    Xvfb.shutdownAndCleanup(xvfbEnvironment, launcher, listener);
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                } catch (final InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    };

    private static final Map<String, List<XvfbEnvironment>> zombies = createOrLoadZombiesMap();

    @Extension
    public static final ComputerListener nodeListener = new ComputerListener() {
        @Override
        public void preOnline(final Computer c, final Channel channel, final FilePath root, final TaskListener listener) throws IOException, InterruptedException {
            final List<XvfbEnvironment> zombiesAtComputer = zombies.get(c.getName());

            if (zombiesAtComputer == null) {
                return;
            }

            final List<XvfbEnvironment> slained = new ArrayList<XvfbEnvironment>();
            for (final XvfbEnvironment zombie : zombiesAtComputer) {
                shutdownAndCleanupZombie(channel, zombie, listener);

                slained.add(zombie);
            }

            zombiesAtComputer.removeAll(slained);
        }

    };

    private static final int MILLIS_IN_SECOND = 1000;

    private static ConcurrentHashMap<String, List<XvfbEnvironment>> createOrLoadZombiesMap() {
        Jenkins.XSTREAM.registerConverter(new XvfbEnvironmentConverter());

        final XmlFile fileOfZombies = zombiesFile();

        if (fileOfZombies.exists()) {
            try {
                @SuppressWarnings("unchecked")
                final ConcurrentHashMap<String, List<XvfbEnvironment>> oldZombies = (ConcurrentHashMap<String, List<XvfbEnvironment>>) fileOfZombies.read();

                return oldZombies;
            } catch (final IOException ignore) {
            } finally {
                fileOfZombies.delete();
            }
        }

        return new ConcurrentHashMap<String, List<XvfbEnvironment>>();
    }

    static void shutdownAndCleanup(final XvfbEnvironment xvfbEnvironment, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {

        listener.getLogger().println(Messages.XvfbBuildWrapper_Stopping());

        try {
            launcher.kill(Collections.singletonMap(JENKINS_XVFB_COOKIE, xvfbEnvironment.cookie));
            final FilePath frameBufferPath = new FilePath(launcher.getChannel(), xvfbEnvironment.frameBufferDir);
            frameBufferPath.deleteRecursive();
        } catch (final ChannelClosedException e) {
            synchronized (zombies) {
                final Computer currentComputer = Computer.currentComputer();
                final String computerName = currentComputer.getName();

                List<XvfbEnvironment> zombiesAtComputer = zombies.get(computerName);

                if (zombiesAtComputer == null) {
                    zombiesAtComputer = new CopyOnWriteArrayList<XvfbEnvironment>();
                    zombies.put(computerName, zombiesAtComputer);
                }

                zombiesAtComputer.add(new XvfbEnvironment(xvfbEnvironment.cookie, xvfbEnvironment.frameBufferDir, xvfbEnvironment.displayName, false));

                final XmlFile fileOfZombies = zombiesFile();
                fileOfZombies.write(zombies);
            }
        }
    }

    private static void shutdownAndCleanupZombie(final Channel channel, final XvfbEnvironment zombie, final TaskListener listener) throws IOException, InterruptedException {

        listener.getLogger().println(Messages.XvfbBuildWrapper_KillingZombies(zombie.displayName, zombie.frameBufferDir));

        try {
            channel.call(new MasterToSlaveCallable<Void, InterruptedException>() {
                private static final long serialVersionUID = 1L;

                @Override
                public Void call() throws InterruptedException {
                    final ProcessTree processTree = ProcessTree.get();

                    for (final Iterator<OSProcess> i = processTree.iterator(); i.hasNext();) {
                        final OSProcess osProcess = i.next();

                        final EnvVars environment = osProcess.getEnvironmentVariables();

                        final String processCookie = environment.get(JENKINS_XVFB_COOKIE);

                        if (processCookie != null && processCookie.equals(zombie.cookie)) {
                            osProcess.kill();
                            new File(zombie.frameBufferDir).delete();
                        }
                    }

                    return null;
                }
            });
        } catch (final InterruptedException e) {
            // if we propagate the exception, slave will be obstructed from going online
            listener.getLogger().println(Messages.XvfbBuildWrapper_ZombieSlainFailed());
            e.printStackTrace(listener.getLogger());
        }
    }

    private static XmlFile zombiesFile() {
        return new XmlFile(Jenkins.XSTREAM, new File(Jenkins.getInstance().getRootDir(), XvfbEnvironment.class.getName() + "-zombies.xml"));
    };
*/
    /** Name of the installation used in a configured job. */
    private String installationName;

    /** Prometheus node exporter port. */
    private int port = 9100;

    /** Should the Xvfb output be displayed in job output. */
    private boolean debug = false;

    /** Time in milliseconds to wait for Xvfb initialization, by default 1 second. */
    private long timeout = 1;

    /** Additional options to be passed to the Prometheus node exporter */
    private String additionalOptions;

    /** Should the Xvfb display be around for post build actions, i.e. should it terminate with the whole build */
    private boolean shutdownWithBuild = false;

    /** Run only on nodes labeled */
    private String assignedLabels;

    /** Run on same node in parallel */
    private boolean parallelBuild = false;

    @DataBoundConstructor
    public PrometheusMonitoring() {
    }

    protected ArgumentListBuilder createCommandArguments(final NExporterInstallation installation, final FilePath frameBufferDir, final int displayNameUsed) {
        final String path = installation.getHome();

        final ArgumentListBuilder cmd;
        if ("".equals(path)) {
            cmd = new ArgumentListBuilder("node_exporter");
        }
        else {
            cmd = new ArgumentListBuilder(path + "/node_exporter");
        }

        cmd.add("--web.listen-address=" + port);

        if (additionalOptions != null) {
            cmd.addTokenized(additionalOptions);
        }
        return cmd;
    }

    public String getAdditionalOptions() {
        return additionalOptions;
    }

    public String getAssignedLabels() {
        return assignedLabels;
    }

    @Override
    public PrometheusMonitoringBuildWrapperDescriptor getDescriptor() {
        return (PrometheusMonitoringBuildWrapperDescriptor) super.getDescriptor();
    }

    public NExporterInstallation getInstallation(final EnvVars env, final Node node, final TaskListener listener) {
        final NExporterInstallation[] installations = getDescriptor().getInstallations();

        // if there is only one installation and no name specified use that
        if (installationName == null && installations.length == 1) {
            return installations[0];
        }

        // if no installation name specified use 'default'
        final String installationNameToUse = Optional.fromNullable(installationName).or("default");

        for (final NExporterInstallation installation : installations) {
            if (installationNameToUse.equals(installation.getName())) {
                try {
                    return installation.forEnvironment(env).forNode(node, TaskListener.NULL);
                } catch (final IOException e) {
                    listener.fatalError("IOException while locating installation", e);
                } catch (final InterruptedException e) {
                    listener.fatalError("InterruptedException while locating installation", e);
                }
            }
        }

        return null;
    }

    public String getInstallationName() {
        return installationName;
    }

    public int getPort() {
        return port;
    }

    public long getTimeout() {
        return timeout;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isParallelBuild() {
        return parallelBuild;
    }

    public boolean isShutdownWithBuild() {
        return shutdownWithBuild;
    }

    /*private NExporterEnvironment launchNExporter(final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        final Computer currentComputer = workspace.toComputer();

        int displayNameUsed;

        if (displayName == null) {
            if (!autoDisplayName) {
                final Executor executor = run.getExecutor();
                if (parallelBuild) {
                    final Computer[] computers = Jenkins.getInstance().getComputers();
                    final int nodeIndex = Arrays.binarySearch(computers, currentComputer, ComputerNameComparator.INSTANCE);

                    displayNameUsed = nodeIndex * 100 + executor.getNumber() + displayNameOffset;
                }
                else {
                    displayNameUsed = executor.getNumber() + displayNameOffset;
                }
            }
            else {
                displayNameUsed = -1;
            }
        }
        else {
            displayNameUsed = displayName;
        }

        final Node currentNode = currentComputer.getNode();

        if (!workspace.exists()) {
            workspace.mkdirs();
        }

        final FilePath frameBufferDir = workspace.createTempDir(".xvfb-" + run.getId() + "-", ".fbdir");

        final EnvVars environment = currentComputer.getEnvironment();
        final NExporterInstallation installation = getInstallation(environment, currentNode, listener);

        if (installation == null) {
            listener.error(Messages.PrometheusMonitoringBuildWrapper_NoInstallationsConfigured());

            throw new RunnerAbortedException();
        }

        final ArgumentListBuilder cmd = createCommandArguments(installation, frameBufferDir, displayNameUsed);

        final ProcStarter procStarter = launcher.launch().cmds(cmd);

        final ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
        final OutputStream stdout = debug ? listener.getLogger() : stdoutStream;

        final ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();
        final AutoDisplayNameFilterStream stderr = new AutoDisplayNameFilterStream(debug ? listener.getLogger() : stderrStream);

        listener.getLogger().print(Messages.PrometheusMonitoringBuildWrapper_Starting());
        procStarter.stdout(stdout).stderr(stderr);

        final String cookie = UUID.randomUUID().toString();
        procStarter.envs(Collections.singletonMap(JENKINS_XVFB_COOKIE, cookie));

        final Proc process = procStarter.start();

        Thread.sleep(timeout * MILLIS_IN_SECOND);

        if (!process.isAlive()) {
            if (!debug) {
                listener.getLogger().write(stdoutStream.toByteArray());
                listener.getLogger().write(stderrStream.toByteArray());
            }

            listener.getLogger().println();

            listener.error(Messages.XvfbBuildWrapper_FailedToStart());

            throw new RunnerAbortedException();
        }

        if (autoDisplayName) {
            displayNameUsed = stderr.getDisplayNumber();
        }

        final NExporterEnvironment nexporterEnvironment = new NExporterEnvironment(cookie, frameBufferDir.getRemote(), displayNameUsed, shutdownWithBuild);

        return nexporterEnvironment;
    }*/

    @DataBoundSetter
    public void setAdditionalOptions(final String additionalOptions) {
        this.additionalOptions = additionalOptions;
    }

    @DataBoundSetter
    public void setAssignedLabels(final String assignedLabels) {
        this.assignedLabels = assignedLabels;
    }

    @DataBoundSetter
    public void setDebug(final boolean debug) {
        this.debug = debug;
    }

    @DataBoundSetter
    public void setInstallationName(final String installationName) {
        this.installationName = installationName;
    }

    @DataBoundSetter
    public void setParallelBuild(final boolean parallelBuild) {
        this.parallelBuild = parallelBuild;
    }

    @DataBoundSetter
    public void setPort(final int port) {
        this.port = port;
    }

    @DataBoundSetter
    public void setShutdownWithBuild(final boolean shutdownWithBuild) {
        this.shutdownWithBuild = shutdownWithBuild;
    }

    @DataBoundSetter
    public void setTimeout(final long timeout) {
        this.timeout = timeout;
    }

    @Override
    public void setUp(final Context context, final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener, final EnvVars initialEnvironment)
            throws IOException, InterruptedException {
        if (assignedLabels != null && !assignedLabels.trim().isEmpty()) {
            final Label label;
            try {
                label = Label.parseExpression(assignedLabels);
            } catch (final ANTLRException e) {
                throw new IOException(e);
            }

            final Computer computer = Computer.currentComputer();
            final Node node = computer.getNode();

            if (!label.matches(node)) {
                // not running on node with requested label
                return;
            }
        }

        if (!launcher.isUnix()) {
            listener.getLogger().println(Messages.PrometheusMonitoringBuildWrapper_NotUnix());

            // we will not run on non Unix machines
            return;
        }

        /*@SuppressWarnings("rawtypes")
        final Run rawRun = run;
        final NExporterEnvironment nexporterEnvironment = launchNExporter(rawRun, workspace, launcher, listener);
        run.addAction(nexporterEnvironment);

        context.setDisposer(new NExporterDisposer(nexporterEnvironment));*/
    }
}
