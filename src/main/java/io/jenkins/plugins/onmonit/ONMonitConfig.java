package io.jenkins.plugins.onmonit;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.util.FormValidation;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.ServletException;
import jenkins.YesNoMaybe;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkins.ui.icon.Icon;
import org.jenkins.ui.icon.IconSet;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Global configuration for the ONMonit plug-in, as shown on the Jenkins Configure System page.
 */
@Extension(dynamicLoadable = YesNoMaybe.YES)
@Symbol({"onMonitConfig"})
public final class ONMonitConfig extends GlobalConfiguration {

	/**
	 * Get the current ONMonit global configuration.
	 *
	 * @return the ONMonit configuration, or {@code null} if Jenkins has been shut down
	 */
	public static ONMonitConfig get() {
		return ExtensionList.lookupSingleton(ONMonitConfig.class);
	}

	private static final Pattern urlVariable = Pattern.compile("\\{([^}]*)\\}");
	private static final Set<String> validVariables = Set.of("jobGroup", "jobName", "jobId", "startTime", "endTime");
	private static final String ICONS_PREFIX = "plugin/otel-monitoring/images/svgs/";
	private static final String ICON_GRAFANA = ICONS_PREFIX + "grafana.svg";
	private static final String ICON_OTEL = ICONS_PREFIX + "opentelemetry.svg";

	public static final String ICON_CLASS_GRAFANA = "icon-onmonit-grafana";
	public static final String ICON_CLASS_OTEL = "icon-onmonit-otel";

	static {
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_GRAFANA + " icon-sm",
						ICON_GRAFANA,
						Icon.ICON_SMALL_STYLE));
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_GRAFANA + " icon-md",
						ICON_GRAFANA,
						Icon.ICON_MEDIUM_STYLE));
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_GRAFANA + " icon-lg",
						ICON_GRAFANA,
						Icon.ICON_LARGE_STYLE));
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_GRAFANA + " icon-xlg",
						ICON_GRAFANA,
						Icon.ICON_XLARGE_STYLE));
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_OTEL + " icon-sm",
						ICON_OTEL,
						Icon.ICON_SMALL_STYLE));
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_OTEL + " icon-md",
						ICON_OTEL,
						Icon.ICON_MEDIUM_STYLE));
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_OTEL + " icon-lg",
						ICON_OTEL,
						Icon.ICON_LARGE_STYLE));
		IconSet.icons.addIcon(
				new Icon(
						ICON_CLASS_OTEL + " icon-xlg",
						ICON_OTEL,
						Icon.ICON_XLARGE_STYLE));	}

	/**
	 * The URL to a Grafana dashboard which can displayed the metrics gathered by ONMonit.
	 */
	@CheckForNull
	private String grafanaDashboard;

	/**
	 * The base URL from which node_exporter and otel-contrib can be downloaded.
	 */
	@CheckForNull
	private String downloadBaseUrl;

	/**
	 * A custom template for the OTEL config file.
	 */
	@CheckForNull
	private String otelConfigTemplate;

	/** Constructor. */
	public ONMonitConfig() {
		load();
	}

	/**
	 * Get the URL for a Grafana dashboard which can display the metrics gathered by ONMonit.
	 *
	 * @return the Grafana dashboard URL
	 */
	public String getGrafanaDashboard() {
		return grafanaDashboard == null ? "" : grafanaDashboard;
	}

	/**
	 * Set the URL for a Grafana dashboard which can display the metrics gathered by ONMonit.
	 *
	 * @param grafanaDashboard the Grafana dashboard URL
	 */
	public void setGrafanaDashboard(@CheckForNull String grafanaDashboard) {
		this.grafanaDashboard = grafanaDashboard != null ? grafanaDashboard.trim() : null;
		save();
	}

	@POST
	public FormValidation doCheckGrafanaDashboard(@QueryParameter String grafanaDashboard)
			throws IOException, ServletException {
		Jenkins.get().checkPermission(Jenkins.ADMINISTER);

		if (Util.fixEmptyAndTrim(grafanaDashboard) == null) {
			return FormValidation.ok();
		}

		String badVariables = urlVariable.matcher(grafanaDashboard).results()
				.map(result -> result.group(1))
				.filter(v -> !validVariables.contains(v))
				.collect(Collectors.joining("}, {"));
		if (!badVariables.isEmpty()) {
			return FormValidation.error("Bad variables: {" + badVariables + "}");
		}
		String urlWithoutVariables = urlVariable.matcher(grafanaDashboard).replaceAll("varValue");
		return validateUrl(urlWithoutVariables);
	}

	/**
	 * Get the format for displaying the elapsed time.
	 *
	 * @return the elapsed time format
	 */
	public String getDownloadBaseUrl() {
		return downloadBaseUrl == null ? "" : downloadBaseUrl;
	}

	/**
	 * Set a base URL from under which node_exporter and otel-contrib binaries can be downloaded.
	 *
	 * @param downloadBaseUrl the URL
	 */
	public void setDownloadBaseUrl(@CheckForNull String downloadBaseUrl) {
		this.downloadBaseUrl = downloadBaseUrl != null ? downloadBaseUrl.trim() : null;
		save();
	}

	@POST
	public FormValidation doCheckDownloadBaseUrl(@QueryParameter String downloadBaseUrl)
			throws IOException, ServletException {
		Jenkins.get().checkPermission(Jenkins.ADMINISTER);

		if (Util.fixEmptyAndTrim(downloadBaseUrl) == null) {
			return FormValidation.ok();
		}

		return validateUrl(downloadBaseUrl);
	}

	/**
	 * Get the configuration template for the otel-contrib process.
	 *
	 * @return the otel configuration template
	 */
	public String getOtelConfigTemplate() {
		return otelConfigTemplate == null ? "" : otelConfigTemplate;
	}

	/**
	 * Set a configuration template for the otel-contrib process.
	 * If null, the default template from the plugin resources is used.
	 *
	 * @param otelConfigTemplate the OTEL configuration template
	 */
	public void setOtelConfigTemplate(@CheckForNull String otelConfigTemplate) {
		this.otelConfigTemplate = otelConfigTemplate != null ? otelConfigTemplate.trim() : null;
		save();
	}

	/** Validates the given URL. */
	private static FormValidation validateUrl(@NonNull String url) {
		try {
			new URL(url).toURI();
			return FormValidation.ok();
		} catch (MalformedURLException | URISyntaxException e) {
			return FormValidation.error("Invalid URL");
		}
	}

}