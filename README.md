Otel + Node exporter monitoring of agents
-------------------------------

This plugin integrates with the Opentelemetry Collector and Node exporter for monitoring of build agents.

It does this by deploying the Prometheus node exporter and Opentelemetry (Otel) Collector on job agents.
The Otel Collector will scrape the metrics from node exporter.

On the job page a link will be displayed to a Grafana dashboard displaying the metrics of any build agents for a given build.

![main-job-page](images/job-page.png)

![grafana-dashboard](images/grafana-dashboard.png)

![configuration](images/configuration.png)

Usage
=====

## Building the plugin:

1. Clone the GitHub repository.
2. Import the Maven project into your favorite IDE (IntelliJ, Eclipse, etc.).
3. Build the plugin using the gradle script (`./gradlew build` or `gradlew.bat build` on Windows).
4. The plugin is created at `build/libs/otel-monitoring.hpi`.

## Installing the plugin:

### Manual install:

1. Copy the `hpi` file to your Jenkins build server and place it in the Jenkins plugin directory (usually `/var/lib/jenkins/plugins`).
2. Ensure that the plugin is owned by the `jenkins` user.
3. Restart Jenkins.

### Web UI install:

1. Log into your Jenkins web UI.
2. On the left-hand side of the screen, click “Manage Jenkins”.
3. Click “Manage Plugins”.
4. Near the top of the screen, click on the “Advanced” tab.
5. Under the “Upload Plugin”, click “Choose File” and select the Otel monitoring plugin that you previously downloaded.
6. Click “Upload”.
7. Check the “Restart Jenkins when installation is complete and no jobs are running” checkbox.
8. Wait for Jenkins to restart.

## First-time configuration instructions:

1. Log into your Jenkins web UI.
2. On the left-hand side of the screen, click “Manage Jenkins”
3. Click “Configure System”.
4. Scroll down to the “ONMonit” header.
5. Enter the URL of the Grafana dashboard which will display the agent metrics.
6. Click “Save”.

## Using the plugin in Jenkins Pipeline

1. Go to Job > Pipeline Syntax > Snippet Generator
2. Select "onMonit" sample step.
3. Click "Generate Pipeline Script"

When used in a pipeline involving multiple agents (eg. parallel stages), then `onMonit` needs to be explicitly
used for each stage running on a different node!

## Using the plugin in declarative Jenkins Pipeline

1. Go to Job > Pipeline Syntax > Declarative Directive Generator
2. Select "options" sample directive.
3. Add "onMonit".
4. Click "Generate Declarative Directive"

When used in a pipeline involving multiple agents (eg. parallel stages), then `onMonit` needs to be explicitly
used for each stage running on a different node!

## Using the plugin in Jenkins job:

Currently, it's only possible to use this plugin in Pipeline jobs and not in freestyle jobs.

## Configuration of Grafana

The dashboard to which this plugin links should be able to display the metrics gathered from the agents.
[Node Exporter Full](https://grafana.com/grafana/dashboards/1860-node-exporter-full/) is a good basis.

The dashboard [node-exporter-full-ci.json](node-exporter-full-ci.json) was built from the one linked above.<br>
It additionally allows selecting by jobGroup, jobName, jobId and executor.

* The jobGroup variable is useful for multi-branch pipeline jobs to narrow the selection of jobNames to only
those of the selected multibranch pipeline.
* The jobName corresponds to the Jenkins job name
* The jobId is the job ID as assigned by Jenkins to each build
* The executor allows to select which agent of a build the metrics were gathered on.
  This is useful for builds involving multiple agents (eg. using parallel stages).
  The `onMonit` step needs to be explicitly used for each stage running on a different node.

## Hardware requirements

The monitoring processes usually use 2MB in memory and 200 MB in disk size.
CPU usage is minimal.
