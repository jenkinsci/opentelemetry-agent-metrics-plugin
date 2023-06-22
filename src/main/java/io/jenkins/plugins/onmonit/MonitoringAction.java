package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Action;
import hudson.model.Run;
import jenkins.model.Jenkins;
import jenkins.model.RunAction2;
import jenkins.tasks.SimpleBuildStep;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class MonitoringAction implements Action, RunAction2, SimpleBuildStep.LastBuildAction, Serializable {
	private static final Logger LOGGER = Logger.getLogger(MonitoringAction.class.getName());
	private static final long serialVersionUID = -7093396061479813632L;
	private static final ONTemplating templating = new ONTemplating();

	private final String jobGroup;
	private final String jobName;
	private final String jobId;
	private transient Run run;
	private boolean isProjectAction;

	public MonitoringAction(String jobGroup, String jobName, String jobId) {
		this.jobGroup = jobGroup;
		this.jobName = jobName;
		this.jobId = jobId;
	}

	public MonitoringAction(MonitoringAction original, boolean isProjectAction) {
		this.jobGroup = original.jobGroup;
		this.jobName = original.jobName;
		this.jobId = original.jobId;
		this.run = original.run;
		this.isProjectAction = isProjectAction;
	}

	public MonitoringAction(ONTemplating.UrlContext context) {
		this(context.getJobGroup(), context.getJobName(), context.getJobId());
	}

	@Override
	public void onAttached(Run<?, ?> r) {
		this.run = r;
	}

	@Override
	public void onLoad(Run<?, ?> r) {
		this.run = r;
	}

	@Override
	public Collection<? extends Action> getProjectActions() {
		// Alternatively we could show the monitoring action of only the last successful build,
		// we could search for the last build which has a MonitoringAction,
		// or we could create a transient monitoring action spanning several of the last builds.
		Run<?, ?> build = run.getParent().getLastBuild();
		if (build == null) {
			return new ArrayList<>();
		}
		return build.getActions(MonitoringAction.class).stream()
				.map(a -> new MonitoringAction(a, true))
				.collect(Collectors.toList());
	}

	@Override
	public String getIconFileName() {
		return null;
	}

	@Override
	public String getDisplayName() {
		return "ONMonit";
	}

	@Override
	public String getUrlName() {
		return null;
	}

	@NonNull
	public List<MonitoringDashboardLink> getLinks() {
		String dashboardUrlTemplate = ONMonitConfig.get().getGrafanaDashboard();

		if (dashboardUrlTemplate.isEmpty()) {
			return Collections.singletonList(new MonitoringDashboardLink(
					"Please define an ONMonit dashboard URL in Jenkins configuration",
					Jenkins.get().getRootUrl() + "/configure",
					"icon-gear2"));
		}
		Map<String, String> binding = new HashMap<>();
		binding.put("jobGroup", this.jobGroup);
		binding.put("jobName", this.jobName);
		binding.put("jobId", this.jobId);
		binding.put("startTime", String.valueOf(run.getStartTimeInMillis()));
		if (run.isBuilding()) {
			binding.put("endTime", "now");
		} else {
			binding.put("endTime", String.valueOf(run.getStartTimeInMillis() + run.getDuration()));
		}

		return Collections.singletonList(new MonitoringDashboardLink(
				(isProjectAction ? "View last" : "View" ) + " build with Grafana",
				templating.getVisualisationUrl(dashboardUrlTemplate, binding),
				ONMonitConfig.ICON_CLASS_GRAFANA));
	}

	@Override
	public String toString() {
		return "MonitoringAction{" +
				"jobGroup='" + jobGroup + '\'' +
				", jobName='" + jobName + '\'' +
				", jobId='" + jobId + '\'' +
				'}';
	}

	public static class MonitoringDashboardLink {
		final String label;
		final String url;
		final String iconClass;

		public MonitoringDashboardLink(String label, String url, String iconClass) {
			this.label = label;
			this.url = url;
			this.iconClass = iconClass;
		}

		public String getLabel() {
			return label;
		}

		public String getUrl() {
			return url;
		}

		public String getIconClass() {
			return iconClass;
		}

		@Override
		public String toString() {
			return "MonitoringDashboardLink{" +
					"label='" + label + '\'' +
					", url='" + url + '\'' +
					", iconClass='" + iconClass + '\'' +
					'}';
		}
	}
}
