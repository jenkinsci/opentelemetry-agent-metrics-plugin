/**
 * Copyright © 2012, Zoran Regvart
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those
 * of the authors and should not be interpreted as representing official policies,
 * either expressed or implied, of the FreeBSD Project.
 */
package io.jenkins.plugins.onmonit;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.NodeSpecific;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import hudson.tools.ToolProperty;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

@SuppressWarnings("serial")
public class NExporterInstallation extends ToolInstallation implements NodeSpecific<NExporterInstallation>, EnvironmentSpecific<NExporterInstallation> {

    @Extension
    @Symbol("nodeExporter")
    public static class DescriptorImpl extends ToolDescriptor<NExporterInstallation> {

        @Override
        public boolean configure(final StaplerRequest req, final JSONObject json) throws FormException {
            final List<NExporterInstallation> boundList = req.bindJSONToList(NExporterInstallation.class, json.get("tool"));

            setInstallations(boundList.toArray(new NExporterInstallation[boundList.size()]));

            return true;
        }

        @Override
        public FormValidation doCheckHome(@QueryParameter final File value) {
            // this can be used to check the existence of a file on the server, so needs to be protected
            if (!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }

            if ("".equals(value.getPath())) {
                // will try path
                return FormValidation.ok();
            }

            if (!value.isDirectory()) {
                return FormValidation.error(Messages.ONMonitoringInstallation_HomeNotDirectory(value));
            }

            final File nexporterExecutable = new File(value, "node_exporter");
            if (!nexporterExecutable.exists()) {
                return FormValidation.error(Messages.NEMonitoringInstallation_HomeDoesntContainNExporter(value));
            }

            if (!nexporterExecutable.canExecute()) {
                return FormValidation.error(Messages.NEMonitoringInstallation_NExporterIsNotExecutable(value));
            }

            return FormValidation.ok();
        }

        @Override
        public FormValidation doCheckName(@QueryParameter final String value) {
            return FormValidation.validateRequired(value);
        }

        @Override
        public String getDisplayName() {
            return Messages.NEMonitoringInstallation_DisplayName();
        }

        @Override
        public NExporterInstallation[] getInstallations() {
            return Jenkins.getInstance().getDescriptorByType(ONMonitoring.ONMonitoringBuildWrapperDescriptor.class).getNeInstallations();
        }

        public DescriptorImpl getToolDescriptor() {
            return ToolInstallation.all().get(DescriptorImpl.class);
        }

        @Override
        public void setInstallations(final NExporterInstallation... installations) {
            Jenkins.getInstance().getDescriptorByType(ONMonitoring.ONMonitoringBuildWrapperDescriptor.class).setNEInstallations(installations);
        }
    }

    @DataBoundConstructor
    public NExporterInstallation(final String name, final String home, final List<? extends ToolProperty<?>> properties) {
        super(name, home, properties);
    }

    @Override
    public NExporterInstallation forEnvironment(final EnvVars environment) {
        return new NExporterInstallation(getName(), environment.expand(getHome()), getProperties().toList());
    }

    @Override
    public NExporterInstallation forNode(final Node node, final TaskListener log) throws IOException, InterruptedException {
        return new NExporterInstallation(getName(), translateFor(node, log), getProperties().toList());
    }
}
