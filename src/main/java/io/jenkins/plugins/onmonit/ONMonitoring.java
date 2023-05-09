package io.jenkins.plugins.onmonit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

public class ONMonitoring extends SimpleBuildWrapper {

    private static final class ComputerNameComparator implements Comparator<Computer> {

        private static final ComputerNameComparator INSTANCE = new ComputerNameComparator();

        @Override
        public int compare(final Computer left, final Computer right) {
            return left.getName().compareTo(right.getName());
        }

    }

    @Extension(ordinal = Double.MAX_VALUE)
    public static class ONMonitoringBuildWrapperDescriptor extends BuildWrapperDescriptor {

        /** NExporter installations, this descriptor persists all installations configured. */
        @CopyOnWrite
        private volatile NExporterInstallation[] neInstallations = new NExporterInstallation[0];

        /** Otel collector installations, this descriptor persists all installations configured. */
        @CopyOnWrite
        private volatile OtelCollectorInstallation[] ocInstallations = new OtelCollectorInstallation[0];

        public ONMonitoringBuildWrapperDescriptor() {
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
                return FormValidation.error(e, Messages.ONMonitoringBuildWrapper_AssignedLabelString_InvalidBooleanExpression(e.getMessage()));
            }

            final Jenkins jenkins = Jenkins.getInstance();
            final Label label = jenkins.getLabel(value);

            if (label.isEmpty()) {
                for (final LabelAtom labelAtom : label.listAtoms()) {
                    if (labelAtom.isEmpty()) {
                        final LabelAtom nearest = LabelAtom.findNearest(labelAtom.getName());
                        return FormValidation.warning(Messages.ONMonitoringBuildWrapper_AssignedLabelString_NoMatch_DidYouMean(labelAtom.getName(), nearest.getDisplayName()));
                    }
                }
                return FormValidation.warning(Messages.ONMonitoringBuildWrapper_AssignedLabelString_NoMatch());
            }

            return FormValidation.okWithMarkup(Messages.ONMonitoringBuildWrapper_LabelLink(jenkins.getRootUrl(), label.getUrl(), label.getNodes().size() + label.getClouds().size()));
        }

        public FormValidation doCheckPort(@QueryParameter final String value) throws IOException {
            return validateOptionalPositiveInteger(value);
        }

        public FormValidation doCheckTimeout(@QueryParameter final String value) throws IOException {
            return validateOptionalNonNegativeInteger(value);
        }

        @Override
        public String getDisplayName() {
            return Messages.ONMonitoringBuildWrapper_DisplayName();
        }

        public NExporterInstallation[] getNeInstallations() {
            return neInstallations;
        }

        public OtelCollectorInstallation[] getOcInstallations() {
            return ocInstallations;
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
            return req.bindJSON(ONMonitoring.class, formData);
        }

        public void setNEInstallations(final NExporterInstallation... neInstallations) {
            this.neInstallations = neInstallations;
            save();
        }

        public void setOCInstallations(final OtelCollectorInstallation... ocInstallations) {
            this.ocInstallations = ocInstallations;
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

    private static final String JENKINS_PM_NODE_EXPORTER_COOKIE = "_JENKINS_PM_NODE_EXPORTER_COOKIE";
    private static final String JENKINS_PM_OTEL_COLLECTOR_COOKIE = "_JENKINS_PM_OTEL_COLLECTOR_COOKIE";

    private static final String STDERR_FD = "2";

    @SuppressWarnings("rawtypes")
    @Extension
    public static final RunListener<Run> nexporterShutdownListener = new RunListener<Run>() {
        @Override
        public void onCompleted(final Run r, final TaskListener listener) {
            final ONMonitoringEnvironment pmEnvironment = r.getAction(ONMonitoringEnvironment.class);

            if (pmEnvironment != null && pmEnvironment.shutdownWithBuild) {
                try {
                    final Executor executor = r.getExecutor();
                    final Computer computer = executor.getOwner();
                    final Node node = computer.getNode();
                    final Launcher launcher = node.createLauncher(listener);

                    ONMonitoring.shutdownAndCleanup(pmEnvironment, launcher, listener);
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                } catch (final InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    };

    private static final Map<String, List<ONMonitoringEnvironment>> zombies = createOrLoadZombiesMap();

    @Extension
    public static final ComputerListener nodeListener = new ComputerListener() {
        @Override
        public void preOnline(final Computer c, final Channel channel, final FilePath root, final TaskListener listener) throws IOException, InterruptedException {
            final List<ONMonitoringEnvironment> zombiesAtComputer = zombies.get(c.getName());

            if (zombiesAtComputer == null) {
                return;
            }

            final List<ONMonitoringEnvironment> slaied = new ArrayList<ONMonitoringEnvironment>();
            for (final ONMonitoringEnvironment zombie : zombiesAtComputer) {
                shutdownAndCleanupZombie(channel, zombie, listener);

                slaied.add(zombie);
            }

            zombiesAtComputer.removeAll(slaied);
        }

    };

    private static final int MILLIS_IN_SECOND = 1000;

    private static ConcurrentHashMap<String, List<ONMonitoringEnvironment>> createOrLoadZombiesMap() {
        Jenkins.XSTREAM.registerConverter(new ONMonitoringEnvironmentConverter());

        final XmlFile fileOfZombies = zombiesFile();

        if (fileOfZombies.exists()) {
            try {
                @SuppressWarnings("unchecked")
                final ConcurrentHashMap<String, List<ONMonitoringEnvironment>> oldZombies = (ConcurrentHashMap<String, List<ONMonitoringEnvironment>>) fileOfZombies.read();

                return oldZombies;
            } catch (final IOException ignore) {
            } finally {
                fileOfZombies.delete();
            }
        }

        return new ConcurrentHashMap<String, List<ONMonitoringEnvironment>>();
    }

    static void shutdownAndCleanup(final ONMonitoringEnvironment pmEnvironment, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {

        listener.getLogger().println(Messages.ONMonitoringBuildWrapper_Stopping());

        try {
            launcher.kill(Collections.singletonMap(JENKINS_PM_NODE_EXPORTER_COOKIE, pmEnvironment.neCookie));
            launcher.kill(Collections.singletonMap(JENKINS_PM_OTEL_COLLECTOR_COOKIE, pmEnvironment.ocCookie));
            final FilePath configPath = new FilePath(launcher.getChannel(), pmEnvironment.configDir);
            configPath.deleteRecursive();
        } catch (final ChannelClosedException e) {
            synchronized (zombies) {
                final Computer currentComputer = Computer.currentComputer();
                final String computerName = currentComputer.getName();

                List<ONMonitoringEnvironment> zombiesAtComputer = zombies.get(computerName);

                if (zombiesAtComputer == null) {
                    zombiesAtComputer = new CopyOnWriteArrayList<ONMonitoringEnvironment>();
                    zombies.put(computerName, zombiesAtComputer);
                }

                zombiesAtComputer.add(new ONMonitoringEnvironment(pmEnvironment.neCookie, pmEnvironment.ocCookie, pmEnvironment.configDir, pmEnvironment.port, false));

                final XmlFile fileOfZombies = zombiesFile();
                fileOfZombies.write(zombies);
            }
        }
    }

    private static void shutdownAndCleanupZombie(final Channel channel, final ONMonitoringEnvironment zombie, final TaskListener listener) throws IOException, InterruptedException {

        listener.getLogger().println(Messages.ONMonitoringBuildWrapper_KillingZombies(zombie.port, zombie.configDir));

        try {
            channel.call(new MasterToSlaveCallable<Void, InterruptedException>() {
                private static final long serialVersionUID = 1L;

                @Override
                public Void call() throws InterruptedException {
                    final ProcessTree processTree = ProcessTree.get();

                    for (final Iterator<OSProcess> i = processTree.iterator(); i.hasNext();) {
                        final OSProcess osProcess = i.next();

                        final EnvVars environment = osProcess.getEnvironmentVariables();

                        final String processCookie = environment.get(JENKINS_PM_NODE_EXPORTER_COOKIE);

                        if (processCookie != null && processCookie.equals(zombie.neCookie)) {
                            osProcess.kill();
                            new File(zombie.configDir).delete();
                        }
                    }

                    return null;
                }
            });
        } catch (final InterruptedException e) {
            // if we propagate the exception, slave will be obstructed from going online
            listener.getLogger().println(Messages.ONMonitoringBuildWrapper_ZombieSlainFailed());
            e.printStackTrace(listener.getLogger());
        }
    }

    private static XmlFile zombiesFile() {
        return new XmlFile(Jenkins.XSTREAM, new File(Jenkins.getInstance().getRootDir(), ONMonitoringEnvironment.class.getName() + "-zombies.xml"));
    };

    /** Name of the nodeExporter installation used in a configured job. */
    private String nodeExporterInstallationName;

    /** Name of the otelCollector installation used in a configured job. */
    private String otelCollectorInstallationName;

    /** Prometheus node exporter port. */
    private int port = 9100;

    /** Should the Prometheus node exporter output be displayed in job output. */
    private boolean debug = true;

    /** Time in milliseconds to wait for Prometheus node exporter initialization, by default 1 second. */
    private long timeout = 1;

    /** Additional options to be passed to the Prometheus node exporter */
    private String additionalOptions;

    /** Should the Prometheus node exporter be around for post build actions, i.e. should it terminate with the whole build */
    private boolean shutdownWithBuild = false;

    /** Run only on nodes labeled */
    private String assignedLabels;

    /** Run on same node in parallel */
    //private boolean parallelBuild = false;

    private ONTemplating templating = new ONTemplating();

    @DataBoundConstructor
    public ONMonitoring() {
    }

    protected ArgumentListBuilder createNeCommandArguments(final NExporterInstallation installation, final FilePath configDir, final int portUsed) {
        final String path = installation.getHome();

        final ArgumentListBuilder cmd;
        final String executable = isWindows() ? "windows_exporter.exe" : "node_exporter";
        if ("".equals(path)) {
            cmd = new ArgumentListBuilder(executable);
        } else {
            cmd = new ArgumentListBuilder(path + File.separator + executable);
        }

        cmd.add("--web.listen-address=:" + portUsed);

        //cmd.add("--collector.textfile.directory=" + configDir);

        if (additionalOptions != null) {
            cmd.addTokenized(additionalOptions);
        }
        return cmd;
    }

    protected ArgumentListBuilder createOcCommandArguments(final OtelCollectorInstallation installation, final FilePath configDir, final int portUsed) {
        final String path = installation.getHome();

        final ArgumentListBuilder cmd;

        final String executable = isWindows() ? "otelcol-contrib.exe" : "otelcol-contrib";
        if ("".equals(path)) {
            cmd = new ArgumentListBuilder(executable);
        } else {
            cmd = new ArgumentListBuilder(path + File.separator + executable);
        }

        cmd.add("--config=file:" + configDir + File.separator + "otel.yaml");

        if (additionalOptions != null) {
            cmd.addTokenized(additionalOptions);
        }
        return cmd;
    }

    private String toOtelCompatibleUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            if (url.getPort() == -1) {
                url = new URL(url.getProtocol(), url.getHost(), url.getDefaultPort(), url.getFile());
            }
            if ("/".equals(url.getFile())) {
                url = new URL(url.getProtocol(), url.getHost(), url.getPort(), "");
            }
            return url.toString();
        } catch(MalformedURLException e) {
            return urlStr;
        }
    }

    private String trimSuffix(String original, String suffix) {
        if (original.endsWith(suffix)) {
            return original.substring(0, original.length() - suffix.length());
        }
        return original;
    }

    private String trimWithDefault(String original, String suffix, String dfault) {
        String trimmed = trimSuffix(original, suffix);
        return trimmed.length()==0 ? dfault : trimSuffix(trimmed, "/");
    }

    private org.thymeleaf.context.Context getJobContext(final Run<?, ?> run, EnvVars environment) {
        org.thymeleaf.context.Context context = new org.thymeleaf.context.Context();
        String pageUrl = Jenkins.getInstance().getRootUrl() + run.getUrl();
        String otlpEndpoint = environment.get("OTEL_EXPORTER_OTLP_ENDPOINT");
        String otlpHeader = environment.get("OTEL_EXPORTER_OTLP_HEADERS");
        String jobName = environment.get("JOB_NAME");
        String jobBaseName = environment.get("JOB_BASE_NAME");
        context.setVariable("JENKINS_URL", Jenkins.getInstance().getRootUrl());
        context.setVariable("pageUrl", pageUrl);
        context.setVariable("env", environment);
        context.setVariable("serviceName", "ci_jemmic_com");
        context.setVariable("jobName", jobName);
        context.setVariable("jobGroupName", trimWithDefault(jobName, jobBaseName, "-"));
        context.setVariable("otlpEndpoint", toOtelCompatibleUrl(otlpEndpoint));
        context.setVariable("otlpAuthHeader", otlpHeader.substring(otlpHeader.indexOf("=") + 1));
        return context;
    }

    protected void writeOtelConfigFile(final Run<?, ?> run, final TaskListener listener, FilePath configDir, EnvVars environment) {
        FilePath configFile = configDir.child("otel.yaml");

        try (Writer w = new OutputStreamWriter(configFile.write(), StandardCharsets.UTF_8)) {
            String content = templating.renderTemplate(getJobContext(run, environment));
            listener.getLogger().println("otel.yaml content:\n" + content);
            w.write(content);
        } catch (InterruptedException e) {
            listener.fatalError("InterruptedException while writing yaml config file", e);
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            listener.fatalError("IOException while writing yaml config file", e);
        }
    }

    public String getAdditionalOptions() {
        return additionalOptions;
    }

    public String getAssignedLabels() {
        return assignedLabels;
    }

    @Override
    public ONMonitoringBuildWrapperDescriptor getDescriptor() {
        return (ONMonitoringBuildWrapperDescriptor) super.getDescriptor();
    }

    public NExporterInstallation getNeInstallation(final EnvVars env, final Node node, final TaskListener listener) {
        final NExporterInstallation[] neInstallations = getDescriptor().getNeInstallations();

        // if there is only one installation and no name specified use that
        if (nodeExporterInstallationName == null && neInstallations.length == 1) {
            return neInstallations[0];
        }

        // if no installation name specified use 'default'
        final String installationNameToUse = Optional.fromNullable(nodeExporterInstallationName).or("default");

        for (final NExporterInstallation installation : neInstallations) {
            if (installationNameToUse.equals(installation.getName())) {
                try {
                    return installation.forEnvironment(env).forNode(node, TaskListener.NULL);
                } catch (final IOException e) {
                    listener.fatalError("IOException while locating installation", e);
                } catch (final InterruptedException e) {
                    listener.fatalError("InterruptedException while locating installation", e);
                    Thread.currentThread().interrupt();
                }
            }
        }

        return null;
    }

    public OtelCollectorInstallation getOcInstallation(final EnvVars env, final Node node, final TaskListener listener) {
        final OtelCollectorInstallation[] ocInstallations = getDescriptor().getOcInstallations();

        // if there is only one installation and no name specified use that
        if (otelCollectorInstallationName == null && ocInstallations.length == 1) {
            return ocInstallations[0];
        }

        // if no installation name specified use 'default'
        final String installationNameToUse = Optional.fromNullable(otelCollectorInstallationName).or("default");

        for (final OtelCollectorInstallation installation : ocInstallations) {
            if (installationNameToUse.equals(installation.getName())) {
                try {
                    return installation.forEnvironment(env).forNode(node, TaskListener.NULL);
                } catch (final IOException e) {
                    listener.fatalError("IOException while locating installation", e);
                } catch (final InterruptedException e) {
                    listener.fatalError("InterruptedException while locating installation", e);
                    Thread.currentThread().interrupt();
                }
            }
        }

        return null;
    }

    public String getNodeExporterInstallationName() {
        return nodeExporterInstallationName;
    }

    public String getOtelCollectorInstallationName() {
        return otelCollectorInstallationName;
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

    //public boolean isParallelBuild() {
    //    return parallelBuild;
    //}

    public boolean isShutdownWithBuild() {
        return shutdownWithBuild;
    }

    private ONMonitoringEnvironment launchONEnvironment(final Run<?, ?> run, final FilePath workspace, final Launcher launcher, final TaskListener listener) throws IOException, InterruptedException {
        final Computer currentComputer = workspace.toComputer();

        final Node currentNode = currentComputer.getNode();

        if (!workspace.exists()) {
            workspace.mkdirs();
        }

        final FilePath configDir = workspace.createTempDir(".otel-nexporter-" + port + "-", ".confdir");

        final EnvVars environment = currentComputer.getEnvironment();
        final NExporterInstallation neInstallation = getNeInstallation(environment, currentNode, listener);
        final OtelCollectorInstallation ocInstallation = getOcInstallation(environment, currentNode, listener);

        if (neInstallation == null) {
            listener.error(Messages.ONMonitoringBuildWrapper_NoInstallationsConfigured());

            throw new RunnerAbortedException();
        }

        writeOtelConfigFile(run, listener, configDir, run.getEnvironment(listener));

        final ArgumentListBuilder neCmd = createNeCommandArguments(neInstallation, configDir, port);
        final ArgumentListBuilder ocCmd = createOcCommandArguments(ocInstallation, configDir, port);
        listener.getLogger().println("Working with installation " + neInstallation.toString() + " with home " + neInstallation.getHome());
        listener.getLogger().println("Working with installation " + ocInstallation.toString() + " with home " + ocInstallation.getHome());
        listener.getLogger().println("Trying to start " + neCmd.toString());
        listener.getLogger().println("Trying to start " + ocCmd.toString());

        final ProcStarter neProcStarter = launcher.launch().cmds(neCmd);
        final ProcStarter ocProcStarter = launcher.launch().cmds(ocCmd);

        final ByteArrayOutputStream neStdoutStream = new ByteArrayOutputStream();
        final OutputStream neStdout = debug ? listener.getLogger() : neStdoutStream;

        final ByteArrayOutputStream neStderrStream = new ByteArrayOutputStream();
        final OutputStream neStderr = debug ? listener.getLogger() : neStderrStream;

        final ByteArrayOutputStream ocStdoutStream = new ByteArrayOutputStream();
        final OutputStream ocStdout = debug ? listener.getLogger() : ocStdoutStream;

        final ByteArrayOutputStream ocStderrStream = new ByteArrayOutputStream();
        final OutputStream ocStderr = debug ? listener.getLogger() : ocStderrStream;

        listener.getLogger().print(Messages.ONMonitoringBuildWrapper_Starting());
        neProcStarter.stdout(neStdout).stderr(neStderr);
        ocProcStarter.stdout(ocStdout).stderr(ocStderr);

        final String neCookie = UUID.randomUUID().toString();
        final String ocCookie = UUID.randomUUID().toString();
        neProcStarter.envs(Collections.singletonMap(JENKINS_PM_NODE_EXPORTER_COOKIE, neCookie));
        ocProcStarter.envs(Collections.singletonMap(JENKINS_PM_OTEL_COLLECTOR_COOKIE, ocCookie));

        final Proc neProcess = neProcStarter.start();
        final Proc ocProcess = ocProcStarter.start();

        Thread.sleep(timeout * MILLIS_IN_SECOND);

        if (!neProcess.isAlive() || !ocProcess.isAlive()) {
            if (!debug) {
                listener.getLogger().println("Stdout / Stderr of node_exporter:");
                listener.getLogger().write(neStdoutStream.toByteArray());
                listener.getLogger().write(neStderrStream.toByteArray());
                listener.getLogger().println("Stdout / Stderr of otelcol-contrib:");
                listener.getLogger().write(ocStdoutStream.toByteArray());
                listener.getLogger().write(ocStderrStream.toByteArray());
            }

            listener.getLogger().println();

            listener.error(Messages.ONMonitoringBuildWrapper_FailedToStart());

            throw new RunnerAbortedException();
        }

        final ONMonitoringEnvironment onEnvironment = new ONMonitoringEnvironment(neCookie, ocCookie, configDir.getRemote(), port, shutdownWithBuild);

        return onEnvironment;
    }

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
    public void setNodeExporterInstallationName(final String neInstallationName) {
        this.nodeExporterInstallationName = neInstallationName;
    }

    @DataBoundSetter
    public void setOtelCollectorInstallationName(final String ocInstallationName) {
        this.otelCollectorInstallationName = ocInstallationName;
    }

    //@DataBoundSetter
    //public void setParallelBuild(final boolean parallelBuild) {
    //    this.parallelBuild = parallelBuild;
    //}

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
            listener.getLogger().println(Messages.ONMonitoringBuildWrapper_NotUnix());

            // we will not run on non Unix machines
            return;
        }

        @SuppressWarnings("rawtypes")
        final Run rawRun = run;
        final ONMonitoringEnvironment onEnvironment = launchONEnvironment(rawRun, workspace, launcher, listener);
        run.addAction(onEnvironment);

        context.setDisposer(new ONDisposer(onEnvironment));
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
